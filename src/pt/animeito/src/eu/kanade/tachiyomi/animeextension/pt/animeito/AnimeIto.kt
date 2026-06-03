package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when {
        "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url)
        else -> emptyList()
    }
}
