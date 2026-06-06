package eu.kanade.tachiyomi.animeextension.pt.animesroll.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AnrollOnlineExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).awaitSuccess().useAsJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val kaken = script.substringAfter("kaken", "")
            .substringAfter('"')
            .substringBefore('"')
            .ifEmpty { null }
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val apiUrl = "https://${url.toHttpUrl().host}/api?$kaken&_=$now"

        return client.newCall(GET(apiUrl)).awaitSuccess().parseAs<Response>().sources.map { source ->
            val videoUrl = source.file
            val quality = source.label
            val videoName = listOfNotNull(
                prefix.takeIf { it.isNotBlank() },
                quality,
            ).joinToString(" - ")

            Video(videoUrl, videoName, videoUrl)
        }
    }

    @Serializable
    data class Source(
        val file: String,
        val label: String,
    )

    @Serializable
    data class Response(
        val sources: List<Source>,
    )
}
