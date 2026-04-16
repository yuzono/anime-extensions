package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient

class WolfstreamExtractor(private val client: OkHttpClient) {

    private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""", RegexOption.DOT_MATCHES_ALL) }
    private val urlsRegex by lazy { Regex("""file\s*:\s*["']([^"']+)["']""") }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val urls = client.newCall(
            GET(url),
        ).awaitSuccess().useAsJsoup()
            .selectFirst("script:containsData(sources)")?.data()
            ?.let { unpacked ->
                val sources = sourcesRegex.find(unpacked)?.groupValues[1] ?: return emptyList()
                urlsRegex.findAll(sources)
                    .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()
            } ?: return emptyList()
        return urls.map { videoUl ->
            Video(videoUl, "${prefix}WolfStream", videoUl)
        }
    }
}
