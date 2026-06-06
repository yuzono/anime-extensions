package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient

class StrmupExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String, name: String = "Strmup"): List<Video> {
        val id = url.split("/").last()
        val body = client.newCall(GET("https://strmup.to/ajax/stream?filecode=$id", headers))
            .awaitSuccess().bodyString()

        return when {
            "streaming_url" in body -> {
                val videoUrl = body.substringAfter("streaming_url")
                    .substringAfter(":\"")
                    .substringBefore('"')
                    .replace("\\", "")

                playlistUtils.extractFromHls(
                    videoUrl,
                    videoNameGen = { "$name - $it" },
                )
            }

            else -> emptyList()
        }
    }
}
