package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.network.get
import keiyoushi.utils.useAsJsoup
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {

    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = client.get(baseUrl + episode.url)
        .use(::hosterListParse)

    override fun hosterListParse(response: Response): List<Hoster> {
        val episodeUrl = response.request.url.toString()
        return response.useAsJsoup().select(videoListSelector()).map { element ->
            Hoster(
                hosterUrl = episodeUrl,
                hosterName = element.text(),
                internalData = element.attr("value"),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = getHosterUrl(hoster.internalData)
        return getVideoList(url, hoster.hosterName, hoster.hosterUrl)
    }

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    private suspend fun getVideoList(url: String, name: String, episodeUrl: String): List<Video> = when {
        // Embed = googlevideo/blogger MP4; Prime = HLS (.image segments via m3u8server)
        "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url, name, episodeUrl)
        else -> emptyList()
    }
}
