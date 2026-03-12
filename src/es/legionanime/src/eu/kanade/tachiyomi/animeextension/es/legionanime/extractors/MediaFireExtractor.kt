package eu.kanade.tachiyomi.animeextension.es.legionanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient

class MediaFireExtractor(
    private val client: OkHttpClient,
) {
    suspend fun getVideoFromUrl(url: String, prefix: String = ""): Video? {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val downloadUrl = document.selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return Video(downloadUrl, "$prefix-MediaFire", downloadUrl)
        }
        return null
    }
}
