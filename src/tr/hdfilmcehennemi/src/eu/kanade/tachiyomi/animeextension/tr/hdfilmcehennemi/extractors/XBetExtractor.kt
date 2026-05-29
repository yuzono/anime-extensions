package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class XBetExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()

        val script = doc.selectFirst("script:containsData(playerConfigs =)")?.data()
            ?: return emptyList()

        val host = "https://${url.toHttpUrl().host}"

        val postPath = script.substringAfter("file\":\"").substringBefore('"')
            .replace("\\", "")

        val postHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", host)
            .build()

        val postRes = client.newCall(POST(host + postPath, postHeaders)).awaitSuccess()
            .parseAs<List<VideoItemDto>> { it.replace("[],", "") }

        return postRes.flatMap { video ->
            runCatching {
                val playlistUrl = client.newCall(POST(host + video.path, postHeaders)).awaitSuccess()
                    .bodyString()

                playlistUtils.extractFromHls(
                    playlistUrl,
                    url,
                    videoNameGen = { "[${video.title}] XBet - $it" },
                )
            }.getOrElse { emptyList() }
        }
    }

    @Serializable
    data class VideoItemDto(val file: String, val title: String) {
        val path = "/playlist/${file.removeSuffix("~")}.txt"
    }
}
