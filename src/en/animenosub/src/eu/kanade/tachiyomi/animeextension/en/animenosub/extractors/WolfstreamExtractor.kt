package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

class WolfstreamExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val url = client.newCall(
            GET(url),
        ).awaitSuccess().useAsJsoup()
            .selectFirst("script:containsData(sources)")
            ?.let { it: Element ->
                it.data().substringAfter("{file:\"").substringBefore("\"")
            } ?: return emptyList()
        return listOf(
            Video(url, "${prefix}WolfStream", url),
        )
    }
}
