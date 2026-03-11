package aniyomi.lib.uqloadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://uqload.is/"

        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val videoHeaders = Headers.headersOf("Referer", BASE_URL)
        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"

        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }
}
