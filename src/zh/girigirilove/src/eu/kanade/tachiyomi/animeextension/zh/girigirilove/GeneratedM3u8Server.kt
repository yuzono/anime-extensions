package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.net.URLEncoder

/**
 * Serves generated HLS playlists for Girigirilove episodes where the origin
 * playlist is missing but the numbered TS segments are still available.
 */
class GeneratedM3u8Server : NanoHTTPD(0) {

    @Volatile
    private var running = false

    @Synchronized
    fun playlistUrl(segmentBaseUrl: String, segmentCount: Int): String {
        if (!running) {
            super.start()
            running = true
        }

        val encodedBaseUrl = URLEncoder.encode(segmentBaseUrl, Charsets.UTF_8.name())
        return "http://127.0.0.1:${super.getListeningPort()}/playlist.m3u8?base=$encodedBaseUrl&count=$segmentCount"
    }

    override fun handle(session: IHTTPSession): Response {
        if (session.uri != "/playlist.m3u8") {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val segmentBaseUrl = session.parameters["base"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing base parameter")
        val segmentCount = session.parameters["count"]?.firstOrNull()?.toIntOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing count parameter")

        if (segmentCount <= 0) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid segment count")
        }

        return newFixedLengthResponse(
            Status.OK,
            "application/vnd.apple.mpegurl",
            buildPlaylist(segmentBaseUrl, segmentCount),
        )
    }

    private fun buildPlaylist(segmentBaseUrl: String, segmentCount: Int) = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:3\n")
        append("#EXT-X-TARGETDURATION:10\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-PLAYLIST-TYPE:VOD\n")

        repeat(segmentCount) { index ->
            append("#EXTINF:6.000000,\n")
            append(segmentBaseUrl)
            append(index.toString().padStart(4, '0'))
            append(".ts\n")
        }

        append("#EXT-X-ENDLIST\n")
    }
}

/**
 * Resolves Girigirilove media URLs with the same idea as the website player:
 * playback requests are independent from page requests, and some CDN hosts are
 * sensitive to the presence or absence of Referer.
 */
class GirigiriloveVideoResolver(
    private val client: OkHttpClient,
    private val generatedM3u8Server: GeneratedM3u8Server,
    private val headerCandidates: List<Headers>,
) {

    fun resolve(videoUrl: String): ResolvedVideo {
        if (!videoUrl.isHlsPlaylist()) {
            return ResolvedVideo(videoUrl, firstWorkingHeaders(videoUrl))
        }

        headerCandidates.firstOrNull { urlExists(videoUrl, it) }?.let { headers ->
            return ResolvedVideo(videoUrl, headers)
        }

        headerCandidates.forEach { headers ->
            val segmentBaseUrl = videoUrl.substringBeforeLast('/', "") + "/"
            if (urlExists(tsSegmentUrl(segmentBaseUrl, 0), headers)) {
                val segmentCount = findTsSegmentCount(segmentBaseUrl, headers)
                return ResolvedVideo(
                    generatedM3u8Server.playlistUrl(segmentBaseUrl, segmentCount),
                    headers,
                )
            }
        }

        return ResolvedVideo(videoUrl, headerCandidates.first())
    }

    private fun firstWorkingHeaders(videoUrl: String): Headers = headerCandidates.firstOrNull { urlExists(videoUrl, it) } ?: headerCandidates.first()

    private fun findTsSegmentCount(segmentBaseUrl: String, headers: Headers): Int {
        var lastExisting = 0
        var firstMissing = 1
        while (firstMissing <= MAX_SEGMENT_PROBE && urlExists(tsSegmentUrl(segmentBaseUrl, firstMissing), headers)) {
            lastExisting = firstMissing
            firstMissing *= 2
        }

        if (lastExisting >= MAX_SEGMENT_PROBE) {
            return lastExisting + 1
        }

        while (lastExisting + 1 < firstMissing) {
            val middle = (lastExisting + firstMissing) / 2
            if (urlExists(tsSegmentUrl(segmentBaseUrl, middle), headers)) {
                lastExisting = middle
            } else {
                firstMissing = middle
            }
        }

        return lastExisting + 1
    }

    private fun String.isHlsPlaylist(): Boolean = substringBefore('?').endsWith(".m3u8")

    private fun tsSegmentUrl(segmentBaseUrl: String, index: Int): String = segmentBaseUrl + index.toString().padStart(4, '0') + ".ts"

    private fun urlExists(url: String, headers: Headers): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    data class ResolvedVideo(
        val url: String,
        val headers: Headers,
    )

    private companion object {
        const val MAX_SEGMENT_PROBE = 2048
    }
}
