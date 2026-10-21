package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.get
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {

    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeItoFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val multiString = buildString {
                if (params.genres.isNotEmpty()) append(params.genres + "&")
                if (params.seasons.isNotEmpty()) append(params.seasons + "&")
                if (params.studios.isNotEmpty()) append(params.studios + "&")
            }

            GET("$animeListUrl/?page=$page&$multiString&status=${params.status}&type=${params.type}&sub=${params.sub}&order=${params.order}")
        }
    }
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeSelector() = "div.catalog-grid article a, div.listupd article a.tip"

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.catalog-grid article a"
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("h2, div.tt, div.ttl")!!.ownText()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/?page=$page&order=episode")
    override fun latestUpdatesNextPageSelector() = ".catalog-page-directions [rel=next]"
    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst(animeTitleSelector)!!.text()
        thumbnail_url = document.selectFirst(animeThumbnailSelector)?.getImageUrl()

        val infos = document.selectFirst(".animefull .clean-anime-hero")!!
        genre = infos.select("div.genxed > a").eachText().joinToString()

        status = parseStatus(infos.select(".clean-anime-status-label").text().substringAfter(" "))
        artist = infos.getInfo(animeArtistText)
        author = infos.getInfo(animeAuthorText)

        description = buildString {
            getAnimeDescription(document)?.also {
                append("$it\n\n")
            }

            document.selectFirst(".clean-anime-alternative")?.ownText()
                ?.takeIf(String::isNotBlank)
                ?.also { append("$animeAltNamePrefix$it\n") }

            infos.select(animeAdditionalInfoSelector).eachText().forEach {
                append("$it\n")
            }
        }
    }

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

    // ============================== Filters ===============================
    override val filtersSelector = ":is(.catalog-main-filters, .catalog-extra-filters) .catalog-field"

    override suspend fun fetchFilterList() {
        if (fetchFilters && !AnimeItoFilters.filterInitialized()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    AnimeItoFilters.filterElements =
                        client.newCall(GET(animeListUrl)).awaitSuccess()
                            .useAsJsoup()
                            .select(filtersSelector)
                }
            }
        }
    }

    override fun getFilterList(): AnimeFilterList = if (fetchFilters && AnimeItoFilters.filterInitialized() && AnimeItoFilters.filterElements.isNotEmpty()) {
        AnimeFilterList(
            AnimeItoFilters.GenresFilter(genresFilterText),
            AnimeItoFilters.SeasonFilter(seasonsFilterText),
            AnimeItoFilters.StudioFilter(studioFilterText),
            AnimeFilter.Separator(),
            AnimeItoFilters.StatusFilter(statusFilterText),
            AnimeItoFilters.TypeFilter(typeFilterText),
            AnimeItoFilters.SubFilter(subFilterText),
            AnimeItoFilters.OrderFilter(orderFilterText),
        )
    } else if (fetchFilters) {
        AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
    } else {
        AnimeFilterList()
    }
}
