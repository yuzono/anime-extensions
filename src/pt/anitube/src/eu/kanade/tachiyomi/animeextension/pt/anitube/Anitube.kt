package eu.kanade.tachiyomi.animeextension.pt.anitube

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.anitube.extractors.AnitubeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Anitube :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anitube"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        getString("preferred_quality", null)?.let {
            val newValue = when (it) {
                "SD" -> "480p"
                "FULLHD" -> "1080p"
                else -> "720p"
            }
            edit()
                .putString(PREF_QUALITY_KEY, newValue)
                .remove("preferred_quality")
                .apply()
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).awaitSuccess()
        val document = response.useAsJsoup()
        val animes = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/page/$page", headers)

    private fun popularAnimeSelector() = "div.lista_de_animes div.ani_loop_item_img > a"

    private fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        val img = element.selectFirst("img")!!
        title = img.attr("title")
        thumbnail_url = img.attr("src")
    }

    private fun popularAnimeNextPageSelector() = "div.pagination > a.current:not(:nth-last-child(2)) + a, " +
        "div.pagination:not(:has(.current)):not(:has(a:first-child + a + a:last-child)) > a:last-child"

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        val document = response.useAsJsoup()
        val animes = document.select(latestUpdatesSelector()).map(::latestUpdatesFromElement)
        val hasNext = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    private fun latestUpdatesSelector() = "div.threeItensPerContent > div.epi_loop_item > a"

    private fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    private fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            if (url.pathSegments.size < 2) {
                throw Exception("Unsupported url")
            }
            val path = "${url.pathSegments[0]}/${url.pathSegments[1]}"
            return getSearchAnime(page, "${PREFIX_SEARCH}$path", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        val document = response.useAsJsoup()
        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        val hasNext = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList(): AnimeFilterList = AnitubeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url: HttpUrl = if (query.isBlank()) {
            val params = AnitubeFilters.getSearchParameters(filters)
            val season = params.season
            val genre = params.genre
            val year = params.year
            val char = params.initialChar
            val urlStr = when {
                season.isNotBlank() -> "$baseUrl/temporada/$season/$year"

                genre.isNotBlank() ->
                    "$baseUrl/genero/$genre/page/$page/${
                        char.replace(
                            "todos",
                            "",
                        )
                    }"

                else -> "$baseUrl/anime/page/$page/letra/$char"
            }
            urlStr.toHttpUrl()
        } else {
            baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("busca.php")
                addQueryParameter("s", query)
                addQueryParameter("submit", "Buscar")
            }.build()
        }

        return GET(url, headers)
    }

    private fun searchAnimeSelector() = popularAnimeSelector()
    private fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    private fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess()
        val doc = getRealDoc(response.useAsJsoup())
        return animeDetailsParse(doc).apply { initialized = true }
    }

    private fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.anime_container_content")!!
        val infos = content.selectFirst("div.anime_infos")!!

        title = document.selectFirst("div.anime_container_titulo")!!.text()
        thumbnail_url = content.selectFirst("img")
            ?.attr("src")
            ?.replace(".webp", ".jpg")
        genre = infos.getInfo("Gêneros")
        author = infos.getInfo("Autor")
        artist = infos.getInfo("Estúdio")
        status = parseStatus(infos.getInfo("Status"))

        val infoItems = listOf("Ano", "Direção", "Episódios", "Temporada", "Título Alternativo")

        description = buildString {
            append(document.selectFirst("div.sinopse_container_content")!!.text() + "\n")
            infoItems.forEach { item ->
                infos.getInfo(item)?.also { append("\n$item: $it") }
            }
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess()
        var doc = getRealDoc(response.useAsJsoup())
        return buildList {
            do {
                if (isNotEmpty()) {
                    val path = doc.selectFirst(popularAnimeNextPageSelector())!!.attr("href")
                    doc = client.newCall(GET(baseUrl + path, headers)).awaitSuccess().useAsJsoup()
                }
                doc.select(episodeListSelector())
                    .map(::episodeFromElement)
                    .also(::addAll)
            } while (doc.selectFirst(popularAnimeNextPageSelector()) != null)
            reverse()
        }
    }

    private fun episodeListSelector() = "div.animepag_episodios_item > a"

    private fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        episode_number = element.selectFirst("div.animepag_episodios_item_views")!!
            .text()
            .substringAfter(" ")
            .toFloatOrNull() ?: 0F
        name = element.selectFirst("div.animepag_episodios_item_views")!!.text()
        date_upload = element.selectFirst("div.animepag_episodios_item_date")!!
            .text()
            .let(DATE_FORMATTER::tryParse)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val anitubeExtractor by lazy { AnitubeExtractor(headers, client, preferences) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess()
        val document = response.useAsJsoup()

        val videoElements = document
            .select("div.video_container > a, div.playerContainer > a")
            .take(3) // Limit to 3 links maximum

        // Always use three resolutions: 480p, 720p, 1080p (SD, HD, FHD)
        val qualities = listOf("480p", "720p", "1080p")

        return coroutineScope {
            videoElements.mapIndexed { index, element ->
                async {
                    val url = element.absUrl("href")
                    val quality = qualities.getOrElse(index) { "720p" }
                    runCatching { anitubeExtractor.getVideosFromUrl(url, quality) }
                        .getOrElse { emptyList() }
                }
            }.awaitAll().flatten()
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

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

        // Auth Code
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim().ifBlank { PREF_DOMAIN_DEFAULT }
                preferences.edit().putString(key, value).apply()
                summary = getDomainPrefSummary(value)
                true
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private suspend fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/video/")) {
            return document
        }

        val element = document.selectFirst("div.controles_ep > a[href]:has(i.spr.listaEP)")
            ?: return document

        val path = element.attr("href")
        return client.newCall(GET(baseUrl + path, headers)).awaitSuccess().useAsJsoup()
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()) {
        "Completo" -> SAnime.COMPLETED
        "Em Progresso" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun Element.getInfo(key: String): String? {
        val element = selectFirst("div.anime_info:has(b:contains($key))")
        val genres = element?.select("a")
        val text = if (genres?.isEmpty() == true) {
            element.ownText()
        } else {
            genres?.eachText()?.joinToString()
        }
        return text?.ifEmpty { null }
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.parseQuality() },
        )
    }

    private fun getDomainPrefSummary(value: String? = null): String {
        val domain = value ?: preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        return """$domain
 Para qualquer alteração ser aplicada, o reinício da app é necessário.
        """.trimMargin()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private val DATE_FORMATTER by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.ROOT) }

        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Domínio preferido (requer reinicialização da app)"
        private const val PREF_DOMAIN_DEFAULT = "https://www.anitube.vip"
        private const val PREF_QUALITY_KEY = "preferred_quality_new"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "720p", "1080p")

        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }
}
