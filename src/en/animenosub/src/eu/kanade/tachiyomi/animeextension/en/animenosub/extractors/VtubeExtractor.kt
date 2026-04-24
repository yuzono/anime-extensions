package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import aniyomi.lib.autoUnpacker
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VtubeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""", RegexOption.DOT_MATCHES_ALL) }
    private val urlsRegex by lazy { Regex("""file\s*:\s*["']([^"']+)["']""") }

    suspend fun videosFromUrl(url: String, baseUrl: String, prefix: String): List<Video> {
        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .build()
        val doc = client.newCall(GET(url, headers = docHeaders)).awaitSuccess().useAsJsoup()

        val jsEval = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        val unpacked = jsEval?.let(::autoUnpacker)
            ?: doc.selectFirst("script:containsData(sources)")?.data()
            ?: return emptyList()

        val sources = sourcesRegex.find(unpacked)?.groupValues[1] ?: return emptyList()
        val urls = urlsRegex.findAll(sources)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()

        return urls.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = url,
                videoNameGen = { quality ->
                    listOfNotNull(
                        prefix.takeIf { it.isNotBlank() },
                        quality,
                    ).joinToString(" ")
                },
            )
        }
    }
}
