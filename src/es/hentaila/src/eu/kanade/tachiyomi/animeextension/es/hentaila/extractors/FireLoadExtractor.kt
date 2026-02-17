package eu.kanade.tachiyomi.animeextension.es.hentaila.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class FireLoadExtractor(
    private val client: OkHttpClient,
) {
    fun getVideoFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute()
        val downloadLinkScript = document.asJsoup().selectFirst("script:contains(\"dlink\")")?.data()
        val downloadUrl = downloadLinkScript?.substringAfter("dlink\" : \"")?.substringBefore("\",")
        if (downloadUrl != null) {
            return listOf(Video(downloadUrl, "FireLoad", downloadUrl))
        }
        return emptyList()
    }
}
