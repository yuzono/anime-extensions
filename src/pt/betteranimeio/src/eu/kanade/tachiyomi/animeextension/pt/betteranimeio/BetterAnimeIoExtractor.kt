package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import okhttp3.OkHttpClient

class BetterAnimeIoExtractor(
    private val client: OkHttpClient,
) {
    suspend fun extractVideosFromApi(encodedSource: String): List<Video> {
        val apiUrl = "$API_URL$encodedSource"
        return try {
            val videoResponse = client.newCall(GET(apiUrl)).awaitSuccess()
                .parseAs<VideoApiResponse>()

            if (videoResponse.status == "success") {
                videoResponse.play.map { video ->
                    Video(video.src, video.sizeText, video.src)
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val API_URL = "https://api.myblogapi.site/api/v1/decode/blogg/"
    }
}
