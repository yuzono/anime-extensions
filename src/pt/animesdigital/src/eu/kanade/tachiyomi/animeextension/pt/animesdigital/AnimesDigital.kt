package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors.ProtectorExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors.ScriptExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnimesDigital :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animes Digital"

    override val baseUrl = "https://animesdigital.org"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val animesDigitalFilters by lazy { AnimesDigitalFilters(baseUrl, client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        animesDigitalFilters.fetchFilters()
        return super.getPopularAnime(page)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/home", headers)
    override fun popularAnimeSelector() = latestUpdatesSelector()
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeParse(response: Response) = super.popularAnimeParse(response).distinctByTitle()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        animesDigitalFilters.fetchFilters()
        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos01/page/$page/", headers)

    override fun latestUpdatesSelector() = "div.b_flex > div.itemE > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        title = element.selectFirst("span.title_anime")!!.text()
    }

    override fun latestUpdatesNextPageSelector() = "ul > li.next"

    // Listing cards link to episodes, so an anime with several new episodes shows up once per episode
    override fun latestUpdatesParse(response: Response) = super.latestUpdatesParse(response).distinctByTitle()

    private fun AnimesPage.distinctByTitle() = AnimesPage(animes.distinctBy { it.title }, hasNextPage)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = animesDigitalFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        animesDigitalFilters.fetchFilters()
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/a/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/animes-legendados-online001", headers)).execute().useAsJsoup()
            .selectFirst("div.menu_filter_box")
            ?.attr("data-secury")
            ?.ifEmpty { null }
            ?: throw Exception("Token de busca não encontrado")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = animesDigitalFilters.getSearchParameters(filters)
        val body = FormBody.Builder().apply {
            add("type", "lista")
            add("limit", "30")
            add("token", searchToken)
            if (query.isNotEmpty()) {
                add("search", query)
            }
            add("pagina", "$page")
            val filterData = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("type_url", params.type)
                addQueryParameter("filter_audio", params.audio)
                addQueryParameter("filter_letter", params.initialLetter)
                addQueryParameter("filter_order", params.orderBy)
            }.build().encodedQuery.orEmpty()

            val genres = params.genres.joinToString { "\"$it\"" }
            val delgenres = params.deletedGenres.joinToString { "\"$it\"" }

            add(
                "filters",
                """{"filter_data": "$filterData", "filter_genre_add": [$genres], "filter_genre_del": [$delgenres]}""",
            )
        }.build()

        return POST("$baseUrl/func/listanime", body = body, headers = headers)
    }

    override fun searchAnimeSelector() = "div.itemA > a"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeParse(response: Response): AnimesPage = runCatching {
        val data = response.parseAs<SearchResponseDto>()
        val animes = data.results.map(Jsoup::parseBodyFragment)
            .mapNotNull { it.selectFirst(searchAnimeSelector()) }
            .map(::searchAnimeFromElement)
        val hasNext = data.totalPage > data.page
        AnimesPage(animes, hasNext)
    }.getOrElse { AnimesPage(emptyList(), false) }

    @Serializable
    class SearchResponseDto(
        val results: List<String>,
        val page: Int,
        @SerialName("total_page") val totalPage: Int,
    )

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        thumbnail_url = doc.selectFirst("div.poster > img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        status = when (doc.selectFirst("div.clw > div.playon")?.text()) {
            "Em Lançamento" -> SAnime.ONGOING
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        with(doc.selectFirst("div.crw > div.dados")!!) {
            artist = getInfo("Estúdio")
            author = getInfo("Autor") ?: getInfo("Diretor")

            title = selectFirst("h1")!!.text()
            genre = select("div.genre a").eachText().joinToString()

            description = selectFirst("div.sinopse")?.text()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.useAsJsoup())
        val episodes = mutableListOf<SEpisode>()
        episodes += doc.select(episodeListSelector()).map(::episodeFromElement)
        val lastPage =
            doc.selectFirst("ul.content-pagination > li:nth-last-child(2) > a")?.text()
                ?.toIntOrNull()
        episodes += lastPage?.let { 2..it }
            ?.parallelCatchingFlatMapBlocking { i ->
                val request = GET(doc.location() + "/page/$i", headers)
                val res = client.newCall(request).awaitSuccess()
                val pageDoc = res.useAsJsoup()
                pageDoc.select(episodeListSelector()).map(::episodeFromElement)
            } ?: emptyList()
        return episodes
    }

    override fun episodeListSelector() = "div.item_ep > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("div.title_anime")!!.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 1F
        date_upload = element.selectFirst("div.date")?.text()?.let { parseDate(it) } ?: 0L
    }

    // ============================ Video Links =============================
    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.useAsJsoup()
        val player = document.selectFirst("div#player") ?: return emptyList()
        return player.select("div.tab-video").flatMapIndexed { index, tab ->
            val tabName = document.select("li[data-tab], a[href]")
                .firstOrNull { (it.attr("data-tab").ifEmpty { it.attr("href") }) == "#${tab.id()}" }?.text()
                ?.takeIf(String::isNotBlank) ?: "Server ${index + 1}"
            tab.select(videoSelector).map { element ->
                Hoster(
                    hosterUrl = document.location(),
                    hosterName = tabName,
                    internalData = element.outerHtml(),
                )
            }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val element = Jsoup.parse(hoster.internalData, hoster.hosterUrl)
            .selectFirst(videoSelector) ?: return emptyList()
        return videosFromElement(element)
    }

    override fun seasonListSelector() = throw UnsupportedOperationException()
    override fun seasonFromElement(element: Element) = throw UnsupportedOperationException()

    private val protectorExtractor by lazy { ProtectorExtractor(client, headers) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private suspend fun videosFromElement(element: Element): List<Video> = when (element.tagName()) {
        "iframe" -> videosFromEmbedUrl(element.absUrl("data-lazy-src").ifEmpty { element.absUrl("src") })

        "script" -> ScriptExtractor.videosFromScript(element.data(), headers)

        "a" -> protectorExtractor.embedUrlFromUrl(element.absUrl("href"))
            ?.let { videosFromEmbedUrl(it) }
            .orEmpty()

        else -> emptyList()
    }

    private suspend fun videosFromEmbedUrl(url: String): List<Video> = when {
        "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
        else -> {
            client.newCall(GET(url, headers)).awaitSuccess()
                .useAsJsoup()
                .select(videoSelector)
                .parallelCatchingFlatMap(::videosFromElement)
        }
    }

    private val scriptSelectors = listOf("eval", "player.src", "this.src", "sources:")
        .joinToString { "script:containsData($it):not(:containsData(/bg.mp4))" }

    private val videoSelector = "iframe, a.ad-protected-cover, $scriptSelectors"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    private fun getRealDoc(document: Document): Document = document.selectFirst("div.subitem > a:contains(menu)")?.let { link ->
        client.newCall(GET(link.attr("href"), headers))
            .execute()
            .useAsJsoup()
    } ?: document

    private fun Element.getInfo(key: String): String? = selectFirst("div.info:has(span:containsOwn($key))")?.run {
        ownText().takeUnless { it.isEmpty() || it == "?" }
    }

    private fun parseDate(date: String): Long {
        return try {
            val normalized = date.lowercase(Locale.ROOT).trim()

            // Espera formatos como "2 semanas atrás", "1 dia atrás", etc.
            val match = RELATIVE_DATE_REGEX.find(normalized) ?: return 0L

            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = match.groupValues[2]

            val millis = when {
                unit.startsWith("dia") -> TimeUnit.DAYS.toMillis(amount)
                unit.startsWith("semana") -> TimeUnit.DAYS.toMillis(amount * 7)
                unit.startsWith("mes") -> TimeUnit.DAYS.toMillis(amount * 30)
                unit.startsWith("mês") -> TimeUnit.DAYS.toMillis(amount * 30)
                unit.startsWith("ano") -> TimeUnit.DAYS.toMillis(amount * 365)
                else -> 0L
            }

            System.currentTimeMillis() - millis
        } catch (_: Throwable) {
            0L
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(\S+)""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p")
    }
}
