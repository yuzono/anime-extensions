package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves Girigirilove media URLs with the same idea as the website player:
 * playback requests are independent from page requests, and some CDN hosts are
 * sensitive to the presence or absence of Referer.
 */
class GirigiriloveVideoResolver(
    private val client: OkHttpClient,
    private val headerCandidates: List<Headers>,
    private val generatedPlaylistUrl: (segmentBaseUrl: String, segmentCount: Int) -> String,
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
                    generatedPlaylistUrl(segmentBaseUrl, segmentCount),
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
