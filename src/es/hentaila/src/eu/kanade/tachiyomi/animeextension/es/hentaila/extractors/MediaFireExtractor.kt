package eu.kanade.tachiyomi.animeextension.es.hentaila.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MediaFireExtractor(
    private val client: OkHttpClient,
) {
    suspend fun getVideoFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).awaitSuccess().use { it.asJsoup() }
        val downloadUrl = document.selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return listOf(Video(downloadUrl, "MediaFire", downloadUrl))
        }
        return emptyList()
    }
}
