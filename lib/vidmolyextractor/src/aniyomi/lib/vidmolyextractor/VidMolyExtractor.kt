package aniyomi.lib.vidmolyextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient, headers: Headers = commonEmptyHeaders) {

    companion object {
        const val BASE_URL = "https://vidmoly.biz"
        private val hostRegex by lazy { Regex("""^https?://(?:www\.)?[^/]+/""") }

        private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""") }
        private val urlsRegex by lazy { Regex("""file\s*:\s*["'](.+?)["']""") }
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("Origin", BASE_URL)
        .set("Referer", "$BASE_URL/")
        .build()

    suspend fun videosFromUrl(iframeUrl: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (iframeUrl.startsWith(BASE_URL, true)) {
            iframeUrl
        } else {
            iframeUrl.replaceFirst(hostRegex, "$BASE_URL/")
        }

        val document = client.newCall(
            GET(fixedUrl, headers),
        ).awaitSuccess().useAsJsoup()
        val script = document.selectFirst("script:containsData(sources)")?.data() ?: return emptyList()
        val sources = sourcesRegex.find(script)?.groupValues[1] ?: return emptyList()
        val urls = urlsRegex.findAll(sources)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()
        return urls.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                videoNameGen = { quality ->
                    listOfNotNull(
                        prefix.takeIf { it.isNotBlank() },
                        "VidMoly - $quality",
                    ).joinToString(" ")
                },
                masterHeaders = headers,
                videoHeaders = headers,
            )
        }
    }
}
