package eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class EmbedPlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val pageHeaders = headers.newBuilder()
            .set("Referer", url)
            .build()

        val body = client.newCall(GET(url, pageHeaders)).execute()
            .use { response -> response.body.string() }

        val streams = parseJwPlayer(body) ?: parsePlayerJs(body)
        if (streams.isNullOrEmpty()) {
            Log.w(tag, "No streams found in embed page: $url")
            return emptyList()
        }

        return streams.flatMap { (videoUrl, label) ->
            val quality = if (label.isBlank()) name else "$name - $label"
            videosFromStreamUrl(videoUrl, quality, url, pageHeaders)
        }
    }

    private fun parseJwPlayer(body: String): List<Pair<String, String>>? = when {
        "file: jw.file" in body -> {
            val videoUrl = body.substringAfter("file")
                .substringAfter(":\"")
                .substringBefore('"')
                .replace("\\", "")
                .let(::normalizeStreamUrl)
                .takeIf(String::isNotBlank)
                ?: return null
            listOf(videoUrl to "")
        }

        "sources: [" in body -> {
            body.substringAfter("sources: [")
                .substringBefore("]")
                .split("{")
                .drop(1)
                .mapNotNull {
                    val label = LABEL_REGEX.find(it)?.groupValues?.get(1).orEmpty()
                    val videoUrl = it.substringAfter("file")
                        .substringAfter(":")
                        .substringAfter('"')
                        .substringBefore('"')
                        .replace("\\", "")
                        .let(::normalizeStreamUrl)
                        .takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    videoUrl to label
                }
                .takeIf { it.isNotEmpty() }
        }

        else -> null
    }

    private fun parsePlayerJs(body: String): List<Pair<String, String>>? {
        if ("Playerjs({" !in body) return null

        return body.substringAfter("Playerjs({")
            .substringAfter("file:\"")
            .substringBefore("\"")
            .split(",")
            .mapNotNull {
                val videoUrl = it.substringAfter("]")
                    .trim()
                    .let(::normalizeStreamUrl)
                if (videoUrl.isBlank()) return@mapNotNull null
                val label = it.substringAfter("[", "").substringBefore("]")
                videoUrl to label
            }
            .takeIf { it.isNotEmpty() }
    }

    private fun videosFromStreamUrl(
        videoUrl: String,
        quality: String,
        referer: String,
        pageHeaders: Headers,
    ): List<Video> {
        if (isM3u8Url(videoUrl)) {
            return runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = videoUrl,
                    referer = referer,
                    videoNameGen = { hlsQuality ->
                        if (hlsQuality.equals("Video", ignoreCase = true)) {
                            quality
                        } else {
                            "$quality - $hlsQuality"
                        }
                    },
                )
            }.onFailure { e ->
                Log.e(tag, "Failed to expand HLS playlist: $videoUrl", e)
            }.getOrElse {
                listOf(
                    Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        headers = pageHeaders,
                    ),
                )
            }
        }
        return listOf(
            Video(
                url = videoUrl,
                quality = quality,
                videoUrl = videoUrl,
                headers = pageHeaders,
            ),
        )
    }

    private fun isM3u8Url(url: String): Boolean {
        val m3u8Regex = Regex("""\.m3u8($|\?|#)""", RegexOption.IGNORE_CASE)
        return m3u8Regex.containsMatchIn(url) ||
            url.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true)
    }

    private fun normalizeStreamUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    companion object {
        private val LABEL_REGEX by lazy { Regex("""label.*?:"([^"]+)"""") }
    }
}
