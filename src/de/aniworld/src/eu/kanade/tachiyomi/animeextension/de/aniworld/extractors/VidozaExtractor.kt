package eu.kanade.tachiyomi.animeextension.de.aniworld.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient

class VidozaExtractor(private val client: OkHttpClient) {

    suspend fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val script = document.select("script:containsData(window.pData = {)")
            .firstOrNull()?.data() ?: return null
        val videoUrl = script.substringAfter("sourcesCode: [{ src: \"").substringBefore("\", type:")
        return Video(videoUrl, quality, videoUrl)
    }
}
