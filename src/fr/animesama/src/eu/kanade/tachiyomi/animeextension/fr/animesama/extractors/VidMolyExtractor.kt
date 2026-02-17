package eu.kanade.tachiyomi.animeextension.fr.animesama.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelFlatMap
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playerRegex = Regex("""player\.setup\(\s*\{([\s\S]*?)\}\s*\);""")
    private val fileRegex = Regex("""file\s*:\s*["'](.*?)["']""")
    private val hlsResolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val host = url.toHttpUrl().run {
            "$scheme://$host"
        }

        val commonHeaders = headers.newBuilder()
            .set("Referer", "$host/")
            .set("Origin", host)
            .build()

        return runCatching {
            client.newCall(GET(url, commonHeaders)).awaitSuccess().use { response ->
                val html = response.body.string()
                val playerConfig = playerRegex.find(html)?.groupValues?.get(1) ?: return@runCatching emptyList()

                fileRegex.findAll(playerConfig)
                    .map { it.groupValues[1] }
                    .filter { it.contains(".m3u8") }
                    .toList()
                    .parallelFlatMap { m3u8Url ->
                        extractVideosFromPlaylist(m3u8Url, commonHeaders, prefix)
                    }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun extractVideosFromPlaylist(playlistUrl: String, headers: Headers, prefix: String): List<Video> {
        return runCatching {
            client.newCall(GET(playlistUrl, headers)).awaitSuccess().use { response ->
                val content = response.body.string()

                if (!content.contains("#EXT-X-STREAM-INF")) {
                    return listOf(Video(playlistUrl, "${prefix}VidMoly - Default", playlistUrl, headers = headers))
                }

                content.split("#EXT-X-STREAM-INF").drop(1).mapNotNull { variant ->
                    val quality = hlsResolutionRegex.find(variant)?.groupValues?.get(1)?.let { "${it}p" } ?: "Unknown"
                    variant.lines().drop(1).firstOrNull(String::isNotBlank)?.trim()?.let { url ->
                        val videoUrl = if (url.startsWith("http")) url else playlistUrl.toHttpUrl().resolve(url)?.toString() ?: return@mapNotNull null
                        Video(videoUrl, "${prefix}VidMoly - $quality", videoUrl, headers = headers)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
