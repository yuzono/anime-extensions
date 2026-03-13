package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient

class AlucardExtractor(private val client: OkHttpClient, private val json: Json, private val baseUrl: String) {
    private val refererHeader = Headers.headersOf("referer", baseUrl)

    suspend fun extractVideos(hosterLink: String, subber: String): List<Video> = try {
        val sourcesId = hosterLink.substringBeforeLast("/true").substringAfterLast("/")
        val playerJs = client.newCall(GET("$baseUrl/js/player.js"))
            .awaitSuccess().bodyString()
        val csrf = "(?<=')[a-zA-Z]{64}(?=')".toRegex().find(playerJs)!!.value
        val sourcesResponse = client.newCall(
            GET(
                "$baseUrl/sources/$sourcesId/true",
                Headers.headersOf(
                    "Referer",
                    hosterLink,
                    "X-Requested-With",
                    "XMLHttpRequest",
                    "Cookie",
                    "__",
                    "csrf-token",
                    csrf,
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
                ),
            ),
        )
            .awaitSuccess().bodyString()

        val sources = json.decodeFromString<JsonObject>(sourcesResponse)["response"]!!
            .jsonObject["sources"]!!
            .jsonArray.first()
            .jsonObject["file"]!!
            .jsonPrimitive.content

        val masterPlaylist = client.newCall(GET(sources, refererHeader))
            .awaitSuccess().bodyString()
        val separator = "#EXT-X-STREAM-INF"
        masterPlaylist.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore("\n") + "p"
            val videoUrl = it.substringAfter("\n")
                .substringBefore("\n")
            // TODO: This gives 403 in MPV
            Video(videoUrl, "$subber: Alucard: $quality", videoUrl, refererHeader)
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
