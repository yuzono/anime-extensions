package eu.kanade.tachiyomi.animeextension.pt.animesotaku

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.RecommendedResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.SearchResponseDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnimeCore : AnimeHttpSource() {

    // AnimesOtaku -> AnimeCore
    override val id: Long = 9099608567050495800L

    override val name = "AnimeCore"

    override val baseUrl = "https://animecore.to"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val animeCoreFilters by lazy { AnimeCoreFilters(baseUrl, client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchRequest("popular", page)

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchRequest("updated", page)

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList() = animeCoreFilters.getFilterList()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
        val id = query.removePrefix(PREFIX_SEARCH)
        client.newCall(GET("$baseUrl/anime/$id"))
            .awaitSuccess()
            .use(::searchAnimeByIdParse)
    } else {
        super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBuilder = FormBody.Builder().apply {
            add("s_keyword", query)
            add("action", "advanced_search")
            add("page", page.toString())
        }

        filters.filterIsInstance<AnimeCoreFilters.QueryParameterFilter>().forEach {
            val (name, values) = it.toQueryParameter()
            values.forEach { value -> formBuilder.add(name, value) }
        }

        return POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            formBuilder.build(),
        )
    }

    private fun searchRequest(orderby: String, page: Int): Request {
        val formBuilder = FormBody.Builder().apply {
            add("s_keyword", "")
            add("orderby", orderby)
            add("order", "DESC")
            add("action", "advanced_search")
            add("page", page.toString())
        }

        return POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            formBuilder.build(),
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        animeCoreFilters.fetchFilters()
        val results = response.parseAs<SearchResponseDto>()
        val data = results.data
        val doc = Jsoup.parseBodyFragment(data.html)
        val animes = doc.parseAnimes()

        val hasNextPage = data.currentPage < data.maxPages
        return AnimesPage(animes, hasNextPage)
    }

    private val regexId by lazy { Regex("""current_post_data_id *= *(\d+)""") }
    private val episodeToAnimeUrlRegex by lazy { Regex("""/watch/([^/]+)-episodio-\d+/?""") }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(current_post_data_id)") ?: return emptyList()
        val animeId = regexId.find(script.data())?.groupValues?.get(1) ?: return emptyList()
        val recommendedResponseDto = client.newCall(
            GET(
                "$baseUrl/wp-json/kiranime/v1/widget?name=recommended&id=$animeId",
                headers,
            ),
        ).execute().parseAs<RecommendedResponseDto>()

        val recommendedDocument = Jsoup.parseBodyFragment(recommendedResponseDto.html)

        return recommendedDocument.parseAnimes()
    }

    private fun Document.parseAnimes(): List<SAnime> {
        return select("article.anime-card").mapNotNull {
            val element = it.selectFirst("h3 > a.stretched-link") ?: return@mapNotNull null
            val episodeUrl = element.attr("abs:href").ifBlank { return@mapNotNull null }
            val animeUrl = episodeToAnimeUrlRegex.find(episodeUrl)
                ?.let { match ->
                    "$baseUrl/anime/${match.groupValues[1]}"
                } ?: episodeUrl
            SAnime.create().apply {
                thumbnail_url = it.selectFirst("img")?.attr("abs:src")
                with(element) {
                    title = attr("title").cleanTitle()
                    setUrlWithoutDomain(animeUrl)
                }
            }
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = getRealDoc(response.asJsoup())

        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("abs:src")
        title = document.selectFirst("title")!!.text().cleanTitle()
        genre = document.select("div.flex a.hover\\:text-white").joinToString { it.text() }
        description = document.selectFirst("section p")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealDoc(response.asJsoup())
        val animeId = document.selectFirst("#seasonContent")!!.attr("data-season")

        return client.newCall(
            GET(
                "$baseUrl/wp-admin/admin-ajax.php?action=get_episodes&anime_id=$animeId&page=1&order=desc",
                headers,
            ),
        ).execute()
            .parseAs<EpisodeResponseDto>()
            .data.episodes.map { it.toSEpisode(dateFormat) }
    }

    // ============================ Video Links =============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("div.episode-player-box iframe")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = player.attr("abs:src")

        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)

            "proxycdn.cc" in url -> {
                listOf(Video(url, "Proxy CDN", url))
            }

            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.anime-information h4 a") ?: return document
        val originalUrl = menu.attr("abs:href").ifBlank { return document }
        return client.newCall(GET(originalUrl, headers)).execute().use { it.asJsoup() }
    }

    private fun String.cleanTitle(): String = this.replace(titleCleanRegex, "")
        .trim()

    // ( Todos Episodios Assistir Online )
    private val titleCleanRegex by lazy {
        Regex("""- Anime Core *$|\(? *(?:Todos Episodios )?Assistir(?: Online)? *\)? *$""")
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        // Formato DD/MM/YYYY
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    }
}
