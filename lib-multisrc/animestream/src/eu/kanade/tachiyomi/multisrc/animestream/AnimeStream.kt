package eu.kanade.tachiyomi.multisrc.animestream

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.GenresFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.OrderFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SeasonFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StatusFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StudioFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SubFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.TypeFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

abstract class AnimeStream(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    protected open val preferences by getPreferencesLazy()

    companion object {
        const val PREFIX_SEARCH = "path:"
    }

    protected open val prefQualityDefault = "720p"
    protected open val prefQualityKey = "preferred_quality"
    protected open val prefQualityTitle = when (lang) {
        "pt-BR" -> "Qualidade preferida"
        else -> "Preferred quality"
    }
    protected open val prefQualityValues = listOf("1080p", "720p", "480p", "360p")
    protected open val prefQualityEntries: List<String>
        get() = prefQualityValues

    protected open val videoSortPrefKey: String
        get() = prefQualityKey
    protected open val videoSortPrefDefault: String
        get() = prefQualityDefault

    protected open val SharedPreferences.qualityPref: String
        get() = getString(prefQualityKey, prefQualityDefault) ?: prefQualityDefault
    protected open val SharedPreferences.videoSortPref: String
        get() = getString(videoSortPrefKey, videoSortPrefDefault) ?: videoSortPrefDefault

    protected open val dateFormatter by lazy {
        val locale = when (lang) {
            "pt-BR" -> Locale("pt", "BR")
            else -> Locale.ENGLISH
        }
        SimpleDateFormat("MMMM d, yyyy", locale)
    }

    protected open val animeListUrl = "$baseUrl/anime"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        fetchFilterList()
        return super.getPopularAnime(page)
    }

    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/?page=$page&order=popular")

    override fun popularAnimeSelector() = searchAnimeSelector()

    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String? = searchAnimeNextPageSelector()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        fetchFilterList()
        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/?page=$page&order=update")

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = searchAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$path", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    protected open fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeStreamFilters.getSearchParameters(filters)
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

    override fun searchAnimeSelector() = "div.listupd article a.tip"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("div.tt, div.ttl")!!.ownText()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    override fun searchAnimeNextPageSelector(): String? = "div.pagination a.next, div.hpage > a.r"

    // ============================== Filters ===============================

    /**
     * Disable it if you don't want the filters to be automatically fetched.
     */
    protected open val fetchFilters = true

    protected open val filtersSelector = "span.sec1 > div.filter > ul"

    protected open suspend fun fetchFilterList() {
        if (fetchFilters && !AnimeStreamFilters.filterInitialized()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    AnimeStreamFilters.filterElements =
                        client.newCall(GET(animeListUrl)).awaitSuccess()
                            .useAsJsoup()
                            .select(filtersSelector)
                }
            }
        }
    }

    protected open val filtersHeader = when (lang) {
        "pt-BR" -> "NOTA: Filtros serão ignorados se usar a pesquisa por nome!"
        else -> "NOTE: Filters are going to be ignored if using search text!"
    }

    protected open val filtersMissingWarning: String = when (lang) {
        "pt-BR" -> "Aperte 'Redefinir' para tentar mostrar os filtros"
        else -> "Press 'Reset' to attempt to show the filters"
    }

    protected open val genresFilterText = when (lang) {
        "pt-BR" -> "Gêneros"
        else -> "Genres"
    }

    protected open val seasonsFilterText = when (lang) {
        "pt-BR" -> "Temporadas"
        else -> "Seasons"
    }

    protected open val studioFilterText = when (lang) {
        "pt-BR" -> "Estúdios"
        else -> "Studios"
    }

    protected open val statusFilterText = "Status"

    protected open val typeFilterText = when (lang) {
        "pt-BR" -> "Tipo"
        else -> "Type"
    }

    protected open val subFilterText = when (lang) {
        "pt-BR" -> "Legenda"
        else -> "Subtitle"
    }

    protected open val orderFilterText = when (lang) {
        "pt-BR" -> "Ordem"
        else -> "Order"
    }

    override fun getFilterList(): AnimeFilterList = if (fetchFilters && AnimeStreamFilters.filterInitialized() && AnimeStreamFilters.filterElements.isNotEmpty()) {
        AnimeFilterList(
            GenresFilter(genresFilterText),
            SeasonFilter(seasonsFilterText),
            StudioFilter(studioFilterText),
            AnimeFilter.Separator(),
            StatusFilter(statusFilterText),
            TypeFilter(typeFilterText),
            SubFilter(subFilterText),
            OrderFilter(orderFilterText),
        )
    } else if (fetchFilters) {
        AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
    } else {
        AnimeFilterList()
    }

    // =========================== Anime Details ============================
    protected open val animeDetailsSelector = "div.info-content, div.right ul.data"
    protected open val animeAltNameSelector = ".alter"
    protected open val animeTitleSelector = "h1.entry-title"
    protected open val animeThumbnailSelector = "div.thumb > img, div.limage > img"
    protected open val animeGenresSelector = "div.genxed > a, li:contains(Genre:) a"
    protected open val animeDescriptionSelector = ".entry-content[itemprop=description], .desc"
    protected open val animeAdditionalInfoSelector = "div.spe > span, li:has(b)"

    protected open val animeStatusText = "Status"
    protected open val animeAuthorText = "Fansub"
    protected open val animeArtistText = when (lang) {
        "pt-BR" -> "Estudio"
        else -> "Studio"
    }

    protected open val animeAltNamePrefix = when (lang) {
        "pt-BR" -> "Nome(s) alternativo(s): "
        else -> "Alternative name(s): "
    }

    protected open fun getAnimeDescription(document: Document) = document.selectFirst(animeDescriptionSelector)?.text()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst(animeTitleSelector)!!.text()
        thumbnail_url = document.selectFirst(animeThumbnailSelector)?.getImageUrl()

        val infos = document.selectFirst(animeDetailsSelector)!!
        genre = infos.select(animeGenresSelector).eachText().joinToString()

        status = parseStatus(infos.getInfo(animeStatusText))
        artist = infos.getInfo(animeArtistText)
        author = infos.getInfo(animeAuthorText)

        description = buildString {
            getAnimeDescription(document)?.also {
                append("$it\n\n")
            }

            document.selectFirst(animeAltNameSelector)?.text()
                ?.takeIf(String::isNotBlank)
                ?.also { append("$animeAltNamePrefix$it\n") }

            infos.select(animeAdditionalInfoSelector).eachText().forEach {
                append("$it\n")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.useAsJsoup()
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    override fun episodeListSelector() = "div.eplister > ul > li > a"

    protected open val episodePrefix = when (lang) {
        "pt-BR" -> "Episódio"
        else -> "Episode"
    }

    @Suppress("unused_parameter")
    protected open fun getEpisodeName(element: Element, epNum: String) = "$episodePrefix $epNum"

    protected open fun getEpisodeNumber(epNum: String) = epNum.substringBefore(" ").toFloatOrNull() ?: 0F

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst(".epl-num")!!.text().let {
            name = getEpisodeName(element, it)
            episode_number = getEpisodeNumber(it)
        }
        element.selectFirst(".epl-sub")?.text()?.let { scanlator = it }
        date_upload = element.selectFirst(".epl-date")?.text().let { dateFormatter.tryParse(it) }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "select.mirror > option[data-index], ul.mirror a[data-em]"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.useAsJsoup().select(videoListSelector())
        return items.parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            getVideoList(url, name)
        }
    }

    protected open suspend fun getHosterUrl(element: Element): String {
        val encodedData = when (element.tagName()) {
            "option" -> element.attr("value")
            "a" -> element.attr("data-em")
            else -> throw Exception()
        }

        return getHosterUrl(encodedData)
    }

    protected open fun getEpisodeIframeSelector() = "iframe[src~=.]"

    // Taken from LuciferDonghua
    protected open suspend fun getHosterUrl(encodedData: String): String {
        val doc = if (encodedData.toHttpUrlOrNull() == null) {
            String(Base64.decode(encodedData, Base64.DEFAULT))
                .let(Jsoup::parse) // string -> document
        } else {
            client.newCall(GET(encodedData, headers)).awaitSuccess().useAsJsoup()
        }

        return doc.selectFirst(getEpisodeIframeSelector())?.safeUrl()
            ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")!!.safeUrl("content")
    }

    private fun Element.safeUrl(attribute: String = "src"): String {
        val value = attr(attribute)
        return when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            else -> absUrl(attribute).ifEmpty { value }
        }
    }

    protected open suspend fun getVideoList(url: String, name: String): List<Video> {
        Log.i(name, "getVideoList -> URL => $url || Name => $name")
        return emptyList()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = prefQualityKey,
            title = prefQualityTitle,
            entries = prefQualityEntries,
            entryValues = prefQualityValues,
            default = prefQualityDefault,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        return sortedWith(
            compareBy { it.quality.contains(quality, true) },
        ).reversed()
    }

    protected open fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase()) {
        "completed", "completo" -> SAnime.COMPLETED
        "ongoing", "lançamento" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    protected open fun Element.getInfo(text: String): String? = selectFirst("span:contains($text)")
        ?.run {
            selectFirst("a")?.text() ?: ownText()
        }

    /**
     * Tries to get the image url via various possible attributes.
     * Taken from Tachiyomi's Madara multisrc.
     */
    protected open fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }.substringBefore("?resize")
}
