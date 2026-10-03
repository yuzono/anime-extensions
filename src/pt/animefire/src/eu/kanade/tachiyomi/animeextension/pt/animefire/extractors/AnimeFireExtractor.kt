package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponseDto
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeFireExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(jsonUrl: String, headers: Headers): List<Video> {
        val responseDto = client.newCall(GET(jsonUrl, headers)).awaitSuccess().parseAs<AFResponseDto>()
        return responseDto.videos.map {
            val url = it.url.replace("\\", "")
            Video(url, it.quality, url, headers = headers)
        }
    }
}
