package aniyomi.lib.uqloadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://uqload.is/"

        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
        private val packedSourceRegex by lazy { Regex(""""((?:https?:/)?/[^"]*(?:\.m3u8|\.mp4)[^"]*)"""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()

        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"
        val videoHeaders = Headers.headersOf("Referer", fixedUrl)

        val script = doc.selectFirst("script:containsData(sources:)")?.data()
        if (script != null) {
            val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
                .takeIf(String::isNotBlank)
                ?.takeIf { it.startsWith("http") }
            if (videoUrl != null) {
                return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
            }
        }

        val packed = doc.select("script")
            .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
            ?.html()
            ?.let(::autoUnpacker)
            ?: return emptyList()

        return packedSourceRegex.findAll(packed).mapNotNull { match ->
            val link = match.groupValues[1]
            val videoUrl = when {
                link.startsWith("http") -> link
                link.startsWith("//") -> "https:$link"
                else -> BASE_URL.dropLast(1) + link
            }.takeIf { it.toHttpUrlOrNull() != null } ?: return@mapNotNull null
            Video(videoUrl, quality, videoUrl, videoHeaders)
        }.distinctBy { it.url }.toList()
    }
}
