package eu.kanade.tachiyomi.animeextension.pt.animefire

import android.util.Log
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Serves Anime Fire's fMP4 HLS variants as DASH over loopback.
 *
 * The app's FFmpeg (7.1, used by both the player and the downloader) keeps stale mov state after an
 * HLS seek, so seeking an fMP4 playlist past the cache never resumes. Its DASH demuxer reopens the
 * segment demuxer on every seek instead. The CDN also calls its fMP4 files JPEG images and needs
 * Android's OkHttp TLS stack, so the init segments and fragments are fetched here, split per track
 * (see [Fmp4]) and served as `.mp4`.
 */
internal class AnimeFireStreamServer(
    private val client: OkHttpClient,
    private val headers: Headers,
) : NanoHTTPD("127.0.0.1", 0) {
    private val token = UUID.randomUUID().toString()

    private val inits = lruCache<String, Fmp4.Init>(16)
    private val segments = lruCache<String, FutureTask<ByteArray>>(MAX_CACHED_SEGMENTS)
    private val playlists = lruCache<String, Playlist>(16)

    private class Playlist(val initUrl: HttpUrl, val fragments: List<HttpUrl>)

    // FFmpeg's DASH demuxer fetches one fragment at a time, so the next few are read ahead.
    private val prefetcher = ThreadPoolExecutor(
        PREFETCH_THREADS,
        PREFETCH_THREADS,
        30,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { Thread(it, "AnimeFireStream").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    @Synchronized
    fun localUrl(url: String): String {
        val upstream = url.toHttpUrl()
        require(isCdnUrl(upstream)) { "Servidor de vídeo não suportado" }
        if (!isAlive) start()
        return route("manifest.mpd", upstream)
    }

    private fun route(
        name: String,
        url: HttpUrl,
    ): String = "http://127.0.0.1:$listeningPort/$token/$name"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("url", url.toString().encodeUtf8().base64Url())
        .build()
        .toString()

    // FFmpeg's DASH demuxer sizes its SegmentList URL buffer from the manifest URL and silently
    // truncates longer ones, so fragments are addressed by a short playlist ID and index.
    private fun local(vararg segments: String) = "http://127.0.0.1:$listeningPort/$token/${segments.joinToString("/")}"

    private fun isCdnUrl(url: HttpUrl): Boolean = url.isHttps && url.host == "akumast.net" && url.encodedPath.startsWith("/i/") && url.port == 443 && url.username.isEmpty() &&
        url.password.isEmpty()

    private fun IHTTPSession.cdnUrl(name: String) = parameters[name]
        ?.firstOrNull()
        ?.decodeBase64()
        ?.utf8()
        ?.toHttpUrlOrNull()
        ?.takeIf(::isCdnUrl)

    override fun handle(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use GET or HEAD")
        }
        val prefix = "/$token/"
        val path =
            session.uri
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.split('/')
        if (path == listOf("manifest.mpd")) {
            val url = session.cdnUrl("url") ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid CDN URL")
            return try {
                // Served with range support so the player sees a seekable input and can start at a resume position.
                bytesResponse(manifest(url).toByteArray(), "application/dash+xml", session.headers["range"])
            } catch (e: Exception) {
                failure(session, e)
            }
        }
        val playlist = path?.takeIf { it.size == 3 }?.let { synchronized(playlists) { playlists[it[0]] } }
        val kind = path?.getOrNull(1)?.let { name -> Fmp4.Kind.entries.firstOrNull { it.name.lowercase() == name } }
        val index = path?.getOrNull(2)?.removeSuffix(".mp4")?.toIntOrNull()?.takeIf { playlist != null && it in playlist.fragments.indices }
        if (playlist == null || kind == null || (path[2] != "init.mp4" && index == null)) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return try {
            val init = init(playlist.initUrl)
            val trackId = init.trackIds[kind] ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Track not found")
            val body = if (index == null) init.forTrack(kind) else Fmp4.fragments(segment(playlist, index), trackId, init.defaultSizes)
            bytesResponse(body, "video/mp4", session.headers["range"])
        } catch (e: Exception) {
            failure(session, e)
        }
    }

    private fun failure(
        session: IHTTPSession,
        e: Exception,
    ): Response {
        Log.e(TAG, "Failed to serve ${session.uri}", e)
        val status = if (e is IOException) Status.SERVICE_UNAVAILABLE else Status.INTERNAL_ERROR
        return newFixedLengthResponse(status, MIME_PLAINTEXT, e.toString())
    }

    private fun bytesResponse(
        body: ByteArray,
        mime: String,
        range: String?,
    ): Response {
        val match = range?.let { RANGE_REGEX.matchEntire(it) }
        val start = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = (match?.groupValues?.get(2)?.toIntOrNull() ?: (body.size - 1)).coerceAtMost(body.size - 1)
        val response =
            if (match != null && start <= end) {
                newFixedLengthResponse(
                    Status.PARTIAL_CONTENT,
                    mime,
                    ByteArrayInputStream(body, start, end - start + 1),
                    (end - start + 1).toLong(),
                ).apply { addHeader("Content-Range", "bytes $start-$end/${body.size}") }
            } else {
                newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(body), body.size.toLong())
            }
        return response.apply { addHeader("Accept-Ranges", "bytes") }
    }

    private fun fetch(url: HttpUrl): ByteArray = client
        .newCall(
            Request
                .Builder()
                .url(url)
                .headers(headers)
                .build(),
        ).execute()
        .use {
            if (!it.isSuccessful) throw IOException("Video CDN returned ${it.code} for ${url.encodedPath}")
            it.body.bytes()
        }

    private fun init(url: HttpUrl): Fmp4.Init = synchronized(inits) {
        inits.getOrPut(url.toString()) { Fmp4.parseInit(fetch(url)) }
    }

    // Audio and video are requested for the same fragment at about the same time, so it is downloaded once.
    private fun download(url: HttpUrl): FutureTask<ByteArray> {
        var created: FutureTask<ByteArray>? = null
        val task = synchronized(segments) {
            val key = url.toString()
            val existing = segments[key]
            val failed = existing != null && existing.isDone && runCatching { existing.get() }.isFailure
            if (existing != null && !failed) {
                existing
            } else {
                FutureTask { fetch(url) }.also {
                    created = it
                    segments[key] = it
                }
            }
        }
        created?.let(prefetcher::execute)
        return task
    }

    private fun segment(
        playlist: Playlist,
        index: Int,
    ): ByteArray {
        val url = playlist.fragments[index]
        val task = download(url)
        playlist.fragments.drop(index + 1).take(PREFETCH_AHEAD).forEach(::download)
        // Runs the download here if no pool thread has picked it up yet, so a seek isn't queued behind old read-aheads.
        task.run()
        return try {
            task.get()
        } catch (e: ExecutionException) {
            synchronized(segments) { if (segments[url.toString()] === task) segments.remove(url.toString()) }
            throw e.cause as? IOException ?: IOException(e.cause)
        }
    }

    private fun manifest(playlistUrl: HttpUrl): String {
        val lines = fetch(playlistUrl).decodeToString().lines()
        val initUrl =
            lines
                .firstNotNullOfOrNull { MAP_REGEX.find(it)?.groupValues?.get(1) }
                ?.let { playlistUrl.resolve(it) }
                ?: throw IOException("Missing HLS initialization segment")
        val fragments =
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map {
                playlistUrl.resolve(it.trim())?.takeIf(::isCdnUrl) ?: throw IOException("Unsupported HLS CDN URL")
            }
        val durations = lines.mapNotNull { it.substringAfter("#EXTINF:", "").substringBefore(',').toDoubleOrNull()?.takeIf { d -> d.isFinite() && d > 0 } }
        if (fragments.isEmpty() || durations.isEmpty() || !isCdnUrl(initUrl)) throw IOException("Invalid HLS playlist")
        // FFmpeg maps a seek to fragment `position / duration`, so every fragment but the last has to match.
        val duration = (durations.first() * 1000).roundToLong()
        val tracks = init(initUrl).trackIds.keys
        val id = UUID.nameUUIDFromBytes(playlistUrl.toString().toByteArray()).toString()
        synchronized(playlists) { playlists[id] = Playlist(initUrl, fragments) }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" """)
            append(
                """profiles="urn:mpeg:dash:profile:full:2011" minBufferTime="PT2S" mediaPresentationDuration="PT${durations.sum()}S"><Period>""",
            )
            tracks.forEach { kind ->
                val name = kind.name.lowercase()
                append("""<AdaptationSet mimeType="$name/mp4"><Representation id="$name">""")
                append(
                    """<SegmentList timescale="1000" duration="$duration"><Initialization sourceURL="${local(id, name, "init.mp4")}"/>""",
                )
                fragments.indices.forEach { append("""<SegmentURL media="${local(id, name, "$it.mp4")}"/>""") }
                append("</SegmentList></Representation></AdaptationSet>")
            }
            append("</Period></MPD>")
        }
    }

    private fun <K, V> lruCache(limit: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
    }

    companion object {
        private const val TAG = "AnimeFireStream"
        private const val MAX_CACHED_SEGMENTS = 10
        private const val PREFETCH_AHEAD = 3
        private const val PREFETCH_THREADS = 4
        private val MAP_REGEX = Regex("""^#EXT-X-MAP:.*URI="([^"]+)"""")
        private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d*)""")
    }
}
