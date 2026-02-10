package eu.kanade.tachiyomi.animeextension.fr.animesama.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playerRegex = Regex("""player\.setup\(\s*\{([\s\S]*?)\}\s*\);""")
    private val fileRegex = Regex("""file\s*:\s*["'](.*?)["']""")
    private val hlsResolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val host = url.toHttpUrl().run {
            "$scheme://$host"
        }
        val videoList = mutableListOf<Video>()

        val commonHeaders = headers.newBuilder()
            .set("Referer", "$host/")
            .set("Origin", host)
            .build()

        runCatching {
            client.newCall(GET(url, commonHeaders)).execute().use { response ->
                val html = response.body.string()
                val playerConfig = playerRegex.find(html)?.groupValues?.get(1) ?: return emptyList()

                fileRegex.findAll(playerConfig)
                    .map { it.groupValues[1] }
                    .filter { it.contains(".m3u8") }
                    .forEach { m3u8Url ->
                        videoList.addAll(extractVideosFromPlaylist(m3u8Url, commonHeaders, prefix))
                    }
            }
        }

        return videoList.sortedByDescending { it.quality }
    }

    private fun extractVideosFromPlaylist(playlistUrl: String, headers: Headers, prefix: String): List<Video> {
        return runCatching {
            client.newCall(GET(playlistUrl, headers)).execute().use { response ->
                val content = response.body.string()

                if (!content.contains("#EXT-X-STREAM-INF")) {
                    return listOf(Video(playlistUrl, "${prefix}VidMoly - Default", playlistUrl, headers = headers))
                }

                content.split("#EXT-X-STREAM-INF").drop(1).map { variant ->
                    val quality = hlsResolutionRegex.find(variant)?.groupValues?.get(1)?.let { "${it}p" } ?: "Unknown"
                    val videoUrl = variant.substringAfter("\n").substringBefore("\n").trim().let {
                        if (it.startsWith("http")) it else playlistUrl.substringBeforeLast("/") + "/" + it
                    }
                    Video(videoUrl, "${prefix}VidMoly - $quality", videoUrl, headers = headers)
                }
            }
        }.getOrDefault(emptyList())
    }
}
