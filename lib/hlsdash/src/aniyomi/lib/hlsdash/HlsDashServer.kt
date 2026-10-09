package aniyomi.lib.hlsdash

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.math.roundToLong

/**
 * Serves fMP4 HLS media playlists to the player as DASH manifests over loopback.
 *
 * The app's FFmpeg (7.1, used by both the player and the downloader) keeps stale mov state after a
 * seek in fMP4 (`EXT-X-MAP`) HLS, so seeking past the cache never resumes. Its DASH demuxer
 * reopens the segment demuxer on every seek instead. A separate audio rendition is served as an
 * adaptation set of the same manifest rather than as an external audio track.
 *
 * That demuxer fetches one segment at a time, which leaves small low-quality segments dominated
 * by request latency and the buffer barely filling. The segments are therefore proxied through
 * the shared OkHttp client (reusing its connections) and the next few are read ahead.
 */
object HlsDashServer {
    /**
     * Registers a stream and returns the loopback URL of its DASH manifest, to use as the video URL.
     *
     * @param client client used for the playlist and segment requests
     * @param headers headers sent with every playlist and segment request
     * @param videoUrl fMP4 HLS media playlist of the video
     * @param audioUrl fMP4 HLS media playlist of a separate audio rendition, if any
     */
    fun register(client: OkHttpClient, headers: Headers, videoUrl: String, audioUrl: String?): String = DashServer.register(client, headers, videoUrl, audioUrl)
}

private object DashServer : NanoHTTPD("127.0.0.1", 0) {

    private const val VIDEO = "video"
    private const val AUDIO = "audio"
    private const val PREFETCH_AHEAD = 3
    private const val PREFETCH_THREADS = 4
    private const val MAX_CACHED_SEGMENTS = 10

    private class Stream(val client: OkHttpClient, val headers: Headers, val videoUrl: String, val audioUrl: String?) {
        @Volatile
        var playlists: Map<String, Playlist> = emptyMap()
    }

    /** Inclusive byte range. */
    private class ByteRange(val first: Long, val last: Long)

    private class Resource(val url: String, val range: ByteRange?)

    private class Segment(val resource: Resource, val durationMs: Long)

    private class Playlist(val init: Resource, val segments: List<Segment>) {
        val totalMs = segments.sumOf { it.durationMs }
    }

    // Only registered streams are served, so the port can't be used to fetch arbitrary URLs.
    private val streams = object : LinkedHashMap<String, Stream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stream>?) = size > 64
    }

    // Segments shared by all streams, keyed by `streamId/track/index`; guarded by its own lock.
    private val cache = object : LinkedHashMap<String, FutureTask<ByteArray>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FutureTask<ByteArray>>?) = size > MAX_CACHED_SEGMENTS
    }

    private val prefetcher = Executors.newFixedThreadPool(PREFETCH_THREADS) { Thread(it, "HlsDashServer").apply { isDaemon = true } }

    @Synchronized
    fun register(client: OkHttpClient, headers: Headers, videoUrl: String, audioUrl: String?): String {
        if (!isAlive) start()
        val id = UUID.nameUUIDFromBytes("$videoUrl|$audioUrl".toByteArray()).toString()
        // Keep an existing stream so a re-registration can't drop the playlists of a playing video.
        streams.getOrPut(id) { Stream(client, headers, videoUrl, audioUrl) }
        return "http://127.0.0.1:$listeningPort/$id.mpd"
    }

    override fun handle(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        val id = parts[0].removeSuffix(".mpd")
        val stream = synchronized(this) { streams[id] } ?: return notFound()
        return try {
            when {
                parts.size == 1 && parts[0].endsWith(".mpd") -> manifest(stream, id, session.headers["range"])
                parts.size == 3 -> segment(stream, id, parts[1], parts[2], session.headers["range"])
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
        }
    }

    private fun manifest(stream: Stream, id: String, rangeHeader: String?): Response {
        val playlists = stream.loadPlaylists()
        val video = playlists.getValue(VIDEO)
        val baseUrl = "http://127.0.0.1:$listeningPort/$id"
        val mpd = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" """)
            append("""profiles="urn:mpeg:dash:profile:full:2011" minBufferTime="PT2S" mediaPresentationDuration="PT${video.totalMs / 1000.0}S"><Period>""")
            appendAdaptationSet(VIDEO, video, baseUrl)
            playlists[AUDIO]?.let { appendAdaptationSet(AUDIO, it, baseUrl) }
            append("</Period></MPD>")
        }
        // Served with range support so the player sees a seekable input and can start at a resume position.
        return respond(mpd.toByteArray(), "application/dash+xml", rangeHeader)
    }

    private fun segment(stream: Stream, id: String, track: String, name: String, rangeHeader: String?): Response {
        val playlist = (stream.playlists.ifEmpty { stream.loadPlaylists() })[track] ?: return notFound()
        val index = if (name == "init") -1 else name.toIntOrNull() ?: return notFound()
        if (index !in -1 until playlist.segments.size) return notFound()

        val current = cached(stream, id, track, playlist, index)
        for (next in index + 1..minOf(index + PREFETCH_AHEAD, playlist.segments.size - 1)) {
            cached(stream, id, track, playlist, next)
        }
        // Runs the download here if no pool thread has picked it up yet, so a seek isn't queued behind old read-aheads.
        current.run()
        val data = try {
            current.get()
        } catch (e: ExecutionException) {
            synchronized(cache) { if (cache["$id/$track/$index"] === current) cache.remove("$id/$track/$index") }
            throw e.cause ?: e
        }
        return respond(data, "$track/mp4", rangeHeader)
    }

    private fun cached(stream: Stream, id: String, track: String, playlist: Playlist, index: Int): FutureTask<ByteArray> {
        val resource = if (index < 0) playlist.init else playlist.segments[index].resource
        var created: FutureTask<ByteArray>? = null
        val task = synchronized(cache) {
            val key = "$id/$track/$index"
            val existing = cache[key]
            val failed = existing != null && existing.isDone && runCatching { existing.get() }.isFailure
            if (existing != null && !failed) {
                existing
            } else {
                FutureTask { stream.download(resource) }.also {
                    created = it
                    cache[key] = it
                }
            }
        }
        created?.let { prefetcher.execute(it) }
        return task
    }

    private fun Stream.download(resource: Resource): ByteArray {
        val range = resource.range
        val requestHeaders = range?.let { headers.newBuilder().set("Range", "bytes=${it.first}-${it.last}").build() } ?: headers
        return client.newCall(GET(resource.url.toHttpUrl(), requestHeaders)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${resource.url}" }
            val bytes = response.body.bytes()
            // A server that ignores the Range header answers 200 with the whole file.
            if (range != null && response.code != 206) {
                bytes.copyOfRange(range.first.toInt(), (range.last + 1).toInt().coerceAtMost(bytes.size))
            } else {
                bytes
            }
        }
    }

    private fun respond(data: ByteArray, mime: String, rangeHeader: String?): Response {
        val range = rangeHeader?.trim()?.let { RANGE_REGEX.matchEntire(it) }
        var start = 0
        var end = data.size - 1
        if (range != null) {
            val (from, to) = range.destructured
            if (from.isEmpty()) {
                start = (data.size - (to.toIntOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                start = from.toIntOrNull() ?: Int.MAX_VALUE
                end = to.toIntOrNull()?.coerceAtMost(end) ?: end
            }
            if (start > end) {
                return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */${data.size}") }
            }
        }
        val length = end - start + 1
        val status = if (range == null) Status.OK else Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, mime, ByteArrayInputStream(data, start, length), length.toLong()).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/${data.size}")
        }
    }

    private fun notFound() = newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    private fun Stream.loadPlaylists(): Map<String, Playlist> = buildMap {
        put(VIDEO, fetch(videoUrl))
        audioUrl?.let { put(AUDIO, fetch(it)) }
    }.also { playlists = it }

    private fun Stream.fetch(url: String): Playlist {
        val playlistUrl = url.toHttpUrl()
        val lines = client.newCall(GET(playlistUrl, headers)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for $url" }
            response.body.string()
        }.lines()

        var init: Resource? = null
        var pendingDuration = 0.0
        var pendingRange: String? = null
        // Per HLS, a BYTERANGE without an offset starts right after the previous sub-range of the same resource.
        val nextOffsets = HashMap<String, Long>()
        var elapsed = 0.0
        var elapsedMs = 0L
        val segments = mutableListOf<Segment>()

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXT-X-MAP:") -> if (init == null) {
                    val uri = MAP_URI_REGEX.find(line)?.groupValues?.get(1) ?: continue
                    val resolved = playlistUrl.resolve(uri)!!.toString()
                    val range = MAP_RANGE_REGEX.find(line)?.groupValues?.get(1)
                        ?.let { parseByteRange(it, nextOffsets, resolved) }
                    init = Resource(resolved, range)
                }

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':').trim()

                line.startsWith("#") -> Unit

                else -> {
                    val resolved = playlistUrl.resolve(line)!!.toString()
                    val range = pendingRange?.let { parseByteRange(it, nextOffsets, resolved) }
                    // Round on the running total so per-segment rounding never accumulates drift.
                    elapsed += pendingDuration
                    val endMs = (elapsed * 1000).roundToLong()
                    segments += Segment(Resource(resolved, range), endMs - elapsedMs)
                    elapsedMs = endMs
                    pendingDuration = 0.0
                    pendingRange = null
                }
            }
        }

        check(segments.isNotEmpty()) { "Media playlist has no segments" }
        return Playlist(checkNotNull(init) { "Media playlist has no EXT-X-MAP" }, segments)
    }

    /** Parses HLS `length[@offset]` into an inclusive range and records where the next one begins. */
    private fun parseByteRange(value: String, nextOffsets: MutableMap<String, Long>, url: String): ByteRange? {
        val length = value.substringBefore('@').toLongOrNull()?.takeIf { it > 0 } ?: return null
        val offset = value.substringAfter('@', "").toLongOrNull() ?: nextOffsets[url] ?: 0L
        nextOffsets[url] = offset + length
        return ByteRange(offset, offset + length - 1)
    }

    // FFmpeg maps a seek to segment `position / duration`, which is exact for fixed-length segments
    // (the shorter final one doesn't matter). Only when durations really vary is a SegmentTimeline
    // emitted so that each segment's start time stays accurate.
    private fun StringBuilder.appendAdaptationSet(type: String, playlist: Playlist, baseUrl: String) {
        val segments = playlist.segments
        val body = segments.dropLast(1).map { it.durationMs }
        val fixedLength = body.isEmpty() ||
            (body.max() - body.min() <= 1 && segments.last().durationMs <= body.max() + 1)

        append("""<AdaptationSet mimeType="$type/mp4"><Representation id="$type">""")
        if (fixedLength) {
            // The final segment may be shorter; including it shifts every seek toward a later segment.
            val duration = if (body.isEmpty()) segments.first().durationMs else body.average().roundToLong()
            append("""<SegmentList timescale="1000" duration="$duration">""")
            append("""<Initialization sourceURL="$baseUrl/$type/init"/>""")
            segments.indices.forEach { append("""<SegmentURL media="$baseUrl/$type/$it"/>""") }
            append("</SegmentList>")
        } else {
            // FFmpeg 7.1 reads a representation's timeline from SegmentTemplate, but not SegmentList.
            append("""<SegmentTemplate timescale="1000" startNumber="0" initialization="$baseUrl/$type/init" media="$baseUrl/$type/${'$'}Number${'$'}">""")
            appendTimeline(segments)
            append("</SegmentTemplate>")
        }
        append("</Representation></AdaptationSet>")
    }

    private fun StringBuilder.appendTimeline(segments: List<Segment>) {
        append("<SegmentTimeline>")
        var i = 0
        while (i < segments.size) {
            val duration = segments[i].durationMs
            var repeat = 0
            while (i + repeat + 1 < segments.size && segments[i + repeat + 1].durationMs == duration) repeat++
            append("<S ")
            if (i == 0) append("""t="0" """)
            append("""d="$duration"""")
            if (repeat > 0) append(""" r="$repeat"""")
            append("/>")
            i += repeat + 1
        }
        append("</SegmentTimeline>")
    }

    private val MAP_URI_REGEX = Regex("""URI="([^"]+)"""")
    private val MAP_RANGE_REGEX = Regex("""BYTERANGE="([^"]+)"""")
    private val RANGE_REGEX = Regex("""bytes=(\d*)-(\d*)""")
}
