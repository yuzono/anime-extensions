package eu.kanade.tachiyomi.animeextension.en.hentaihaven

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors.OctopusExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.get
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiHaven :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiHaven"
    override val baseUrl = "https://hentaihaven.xxx"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    private val apiHeaders by lazy { headers.newBuilder().add("Referer", "$baseUrl/").build() }
    private val extractor by lazy { OctopusExtractor(client) }
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var filterOptions: FilterOptionsDto? = null

    @Volatile
    private var filterFetchStarted = false

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        fetchFilters()
        val url = if (page > 1) "$baseUrl/browse/trending/page/$page/" else "$baseUrl/browse/trending/"
        val document = client.get(url).useAsJsoup()
        val animes = document.select(ANIME_LIST_SELECTOR)
            .mapNotNull(::animeFromElement)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val img = element.selectFirst("img") ?: return null
        val name = img.attr("alt").takeIf { it.isNotBlank() } ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            title = name
            thumbnail_url = img.attr("abs:src").replace(" ", "%20")
        }
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/watch/page/$page/" else "$baseUrl/watch/?sort=latest"
        return client.get(url).extractNextJs<CatalogueDto> {
            it is JsonObject && "data" in it && "totalPages" in it
        }?.toAnimesPage(page) ?: AnimesPage(emptyList(), false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.value
        val release = filters.firstInstanceOrNull<ReleaseFilter>()?.value
        val author = filters.firstInstanceOrNull<AuthorFilter>()?.value

        val url = "$baseUrl/api/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "24")
            .addQueryParameter("live", "1")
            .addQueryParameter("locale", "en")
            .addQueryParameter("orderby", sort?.orderby ?: "date")
            .addQueryParameter("order", sort?.order ?: "desc")
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query.trim())
                if (!genre.isNullOrEmpty()) addQueryParameter("genre", genre)
                if (!release.isNullOrEmpty()) addQueryParameter("release", release)
                if (!author.isNullOrEmpty()) addQueryParameter("author", author)
            }
            .addQueryParameter("page", page.toString())
            .build()

        return client.get(url, apiHeaders).parseAs<CatalogueDto>().toAnimesPage(page)
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url
        .replace(EPISODE_SUFFIX_REGEX, "")
        .trimEnd('/') + "/"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.get(getAnimeUrl(anime)).useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("div[data-watch-primary] h1")!!.text()
            thumbnail_url = document
                .selectFirst("div[data-watch-primary] div[class*=\"aspect-[2/3]\"] img")
                ?.attr("abs:src")?.takeIf { it.isNotBlank() }
            description = document.selectFirst("div[data-watch-primary] p.line-clamp-2")?.text()
            genre = rowLinks(document, "Genres").joinToString().takeIf { it.isNotBlank() }
            author = rowLinks(document, "Studio").joinToString().takeIf { it.isNotBlank() }
            artist = rowLinks(document, "Artist").joinToString().takeIf { it.isNotBlank() }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    private fun rowLinks(doc: Document, label: String): List<String> = doc.select("div[data-watch-primary] span:matchesOwn(^$label$)")
        .flatMap { it.parent()?.select("a[href]").orEmpty() }
        .map { it.text() }
        .filter { it.isNotEmpty() }
        .distinct()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.get(getAnimeUrl(anime))
        val seriesPath = response.request.url.encodedPath.trimEnd('/')
        val document = response.useAsJsoup()

        return document.select("a[data-episode-preview-hover][href^='$seriesPath/episode-']")
            .mapNotNull { el ->
                val href = el.absUrl("href")
                val slug = href.toHttpUrl().pathSegments
                    .lastOrNull { it.isNotEmpty() }
                    ?.takeIf { it.startsWith("episode-") }
                    ?.removePrefix("episode-")
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val numMatch = EPISODE_NUMBER_REGEX.find(slug)
                val number = numMatch?.groupValues?.get(1)?.toFloatOrNull()
                val hasSuffix = numMatch?.groupValues?.get(2)?.isNotEmpty() == true

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = el.selectFirst("p.font-bold")?.text()?.takeIf { it.isNotEmpty() }
                        ?: number?.let { "Episode ${it.toString().removeSuffix(".0")}" }
                        ?: "Episode ${slug.replaceFirstChar(Char::uppercase)}"
                    episode_number = when {
                        number == null -> -1f
                        hasSuffix -> number + 0.5f
                        else -> number
                    }
                    el.selectFirst("p.text-zinc-400")?.text()?.let { date_upload = parseDateString(it) }
                }
            }
            .distinctBy { it.url }
    }

    private fun parseDateString(raw: String): Long = synchronized(DATE_FORMATS) {
        DATE_FORMATS.firstNotNullOfOrNull { it.tryParse(raw).takeIf { t -> t != 0L } } ?: 0L
    }

    // ============================ Videos =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeUrl = baseUrl + episode.url
        val document = client.get(episodeUrl).useAsJsoup()

        val playlistUrl = document
            .selectFirst("video source[type='application/vnd.apple.mpegurl']")
            ?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("source[data-hhaven-indexable-source]")
                ?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: extractContentUrlFromJsonLd(document)
            ?: return emptyList()

        return listOf(
            Hoster(
                hosterName = "Octopus",
                internalData = "$playlistUrl$DATA_SEPARATOR$episodeUrl",
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playlistUrl = hoster.internalData.substringBefore(DATA_SEPARATOR)
        val episodeUrl = hoster.internalData.substringAfter(DATA_SEPARATOR)

        val preferred = preferences.qualityPref
        return extractor.getVideosFromSourceUrl(playlistUrl, episodeUrl)
            .sortedWith(
                compareByDescending<Video> { it.videoTitle.contains(preferred) }
                    .thenByDescending { it.videoTitle.filter(Char::isDigit).toIntOrNull() ?: 0 },
            )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        fetchFilters()
        val options = filterOptions
            ?: return AnimeFilterList(AnimeFilter.Header("Press 'Reset' to attempt to show the filters"))

        return AnimeFilterList(
            SortFilter(),
            GenreFilter(options.genreOptions()),
            ReleaseFilter(options.yearOptions()),
            AuthorFilter(options.authorOptions()),
        )
    }

    private fun fetchFilters() {
        if (filterFetchStarted) return
        filterFetchStarted = true
        scope.launch {
            filterOptions = runCatching {
                client.get("$baseUrl/search/").extractNextJs<FilterOptionsDto> {
                    it is JsonObject && "genres" in it && "authors" in it && "years" in it
                }
            }.getOrNull()
            if (filterOptions == null) filterFetchStarted = false
        }
    }

    // ============================== Settings ==============================

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred video quality",
            entries = QUALITY_OPTIONS,
            entryValues = QUALITY_OPTIONS,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================

    override suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) = Unit

    private fun extractContentUrlFromJsonLd(document: Document): String? = document.select("script[type='application/ld+json']")
        .firstNotNullOfOrNull { script ->
            runCatching { script.data().parseAs<JsonLdDto>() }.getOrNull()?.videoUrlOrNull()
        }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")
    override fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used")
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun relatedAnimeListRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used")
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException("Not used")

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_OPTIONS = listOf("1080p", "720p", "360p")

        private const val ANIME_LIST_SELECTOR = "a[href^='/watch/']:has(img)"
        private const val NEXT_PAGE_SELECTOR =
            "nav[aria-label='Archive pages'] a[rel=next]:not([aria-disabled=true])"
        private val EPISODE_NUMBER_REGEX = Regex("""^(\d+(?:\.\d+)?)(-.+)?$""")
        private val EPISODE_SUFFIX_REGEX = Regex("""/episode-[^/]+/?$""")
        private const val DATA_SEPARATOR = "\n"
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
        )
    }
}
