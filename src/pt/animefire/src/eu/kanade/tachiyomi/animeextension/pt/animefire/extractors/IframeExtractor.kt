package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient

class IframeExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(iframeUrl: String, headers: Headers): List<Video> {
        val response = client.newCall(GET(iframeUrl, headers)).awaitSuccess().use { it.body.string() }
        val url = response.substringAfter("play_url")
            .substringAfter(":\"")
            .substringBefore("\"")
        val video = Video(url, "Default", url, headers = headers)
        return listOf(video)
    }
}
