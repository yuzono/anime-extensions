package eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient

class SolidFilesExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        return document.select("script").mapNotNull { script ->
            if (script.data().contains("\"downloadUrl\":")) {
                val data = script.data().substringAfter("\"downloadUrl\":").substringBefore(",")
                val url = data.replace("\"", "")
                val videoUrl = url
                val quality = prefix + "SolidFiles"
                Video(videoUrl, quality, videoUrl)
            } else {
                null
            }
        }
    }
}
