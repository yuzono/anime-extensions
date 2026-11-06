package eu.kanade.tachiyomi.animeextension.ar.witanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SharedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, quality: String = "mirror"): Video? {
        val document = runCatching { client.newCall(GET(url)).execute().asJsoup() }.getOrNull() ?: return null
        return document.selectFirst("source, video")?.let {
            val videoUrl = it.attr("abs:src").ifBlank { it.attr("src") }
            if (videoUrl.isNotBlank()) {
                Video(
                    videoUrl = videoUrl,
                    videoTitle = "4Shared: $quality",
                    headers = null,
                )
            } else {
                null
            }
        }
    }
}
