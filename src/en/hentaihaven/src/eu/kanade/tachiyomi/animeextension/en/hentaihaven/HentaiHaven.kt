package eu.kanade.tachiyomi.animeextension.en.hentaihaven

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors.OctopusExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiHaven :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiHaven"
    override val baseUrl = "https://hentaihaven.xxx"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    private val extractor by lazy { OctopusExtractor(client) }
    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_OPTIONS = arrayOf("1080p", "720p", "360p")
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_OPTIONS
            entryValues = QUALITY_OPTIONS
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/?m_orderby=views", headers)

    override fun popularAnimeSelector() = "div.page-item-detail.video"
    override fun popularAnimeNextPageSelector() = "a.nextpostslink, div.wp-pagenavi a.next"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.item-thumb a")?.attr("href") ?: "")
        title = element.selectFirst("div.post-title a, h3.h5 a")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?m_orderby=latest", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // ── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("post_type", "wp-manga")
                .build()
            return GET(url.toString(), headers)
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()

        val browseUrl = genreFilter?.browseUrl(baseUrl) ?: tagFilter?.browseUrl(baseUrl)
        if (browseUrl != null) {
            val urlBuilder = browseUrl.toHttpUrl().newBuilder()
            if (page > 1) urlBuilder.addPathSegments("page/$page/")
            return GET(urlBuilder.build().toString(), headers)
        }

        return GET("$baseUrl/page/$page/", headers)
    }

    override fun searchAnimeSelector() = "div.c-tabs-item, div.page-item-detail.video"
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val linkEl = element.selectFirst("a[href*='/watch/']")
        setUrlWithoutDomain(linkEl?.attr("href") ?: "")
        title = linkEl?.attr("title")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("div.post-title a, h3 a, h4 a")?.text()
            ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val items = document.select(searchAnimeSelector())
            .map { searchAnimeFromElement(it) }
            .distinctBy { it.url }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(items, hasNextPage)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Genre and Tag cannot be combined — Genre takes priority"),
        AnimeFilter.Header("Filters are ignored when a search query is entered"),
        GenreFilter(),
        TagFilter(),
        SortFilter(),
    )

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("div.post-title h1")?.text()
            ?: document.selectFirst("h1.entry-title")?.text() ?: ""
        thumbnail_url = document.selectFirst(
            "div.summary_image img, div.summary-image img",
        )?.attr("abs:src")
        description = document.selectFirst(
            "div.description-summary div.summary__content, div.entry-content p",
        )?.text()
        author = document.selectFirst(
            "div.post-content_item.mg_author div.summary-content a, " +
                "div.post-content_item:contains(Studio) div.summary-content a",
        )?.text()
        genre = document.select(
            "div.genres-content a, div.post-content_item.mg_genres a",
        ).joinToString { it.text() }.takeIf { it.isNotBlank() }
        status = when (
            document.selectFirst(
                "div.post-content_item:contains(Status) div.summary-content",
            )?.text()?.lowercase()
        ) {
            "ongoing" -> SAnime.ONGOING
            "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val cleanUrl = anime.url
            .replace(Regex("/episode-\\d+/?$"), "")
            .trimEnd('/') + "/"
        return GET("$baseUrl$cleanUrl", headers)
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(animeDetailsRequest(anime)).awaitSuccess().useAsJsoup()
        return parseEpisodesFromHtml(document)
    }

    private fun parseEpisodesFromHtml(doc: Document): List<SEpisode> {
        val elements = doc.select("li.wp-manga-chapter, ul.main.version-chap li")
        val size = elements.size
        return elements.mapIndexedNotNull { index, el ->
            val link = el.selectFirst("a") ?: return@mapIndexedNotNull null
            SEpisode.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                name = link.text().trim().ifBlank { "Episode ${size - index}" }
                episode_number = Regex("""[Ee]pisode[- ](\d+(?:\.\d+)?)""")
                    .find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (size - index).toFloat()
                val dateStr = el.selectFirst("span.chapter-release-date i")?.text()
                if (!dateStr.isNullOrBlank()) date_upload = parseDateString(dateStr)
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun parseDateString(raw: String): Long = runCatching {
        listOf(
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH),
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH),
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ENGLISH),
        ).firstNotNullOfOrNull { fmt ->
            runCatching { fmt.parse(raw.trim())?.time }.getOrNull()
        } ?: 0L
    }.getOrDefault(0L)

    override fun episodeListSelector() = "li.wp-manga-chapter"
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ── Videos ────────────────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = "$baseUrl${episode.url}"
        val document = client.newCall(GET(episodeUrl, headers)).awaitSuccess().useAsJsoup()

        // Locate the player.php iframe or script — it carries `?data=<base64>`.
        val playerPhpSrc = document
            .selectFirst("iframe[src*='player-logic/player.php']")?.attr("abs:src")
            ?: document
                .selectFirst("script[src*='player-logic/player.php']")?.attr("abs:src")
            ?: document
                .selectFirst("[data-src*='player-logic/player.php']")?.attr("abs:data-src")
            ?: ""

        val playerDataB64 = if (playerPhpSrc.isNotBlank()) {
            runCatching { playerPhpSrc.toHttpUrl() }.getOrNull()
                ?.queryParameter("data") ?: ""
        } else {
            // Fallback: scan all script/iframe contents for the pattern inline.
            document.select("script, iframe").mapNotNull { el ->
                val content = el.attr("src").ifBlank { el.data() }
                Regex("""player\.php\?data=([A-Za-z0-9+/=]+)""")
                    .find(content)?.groupValues?.get(1)
            }.firstOrNull() ?: ""
        }

        if (playerDataB64.isBlank()) return emptyList()

        val playerLogicScript =
            document.selectFirst("script:containsData(player_logic)")?.data() ?: ""

        val apiUrl = Regex(""""api_url"\s*:\s*"([^"]+)"""")
            .find(playerLogicScript)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: "$baseUrl/wp-content/plugins/player-logic/api.php"

        val videos = extractor.getVideosFromPayload(apiUrl, playerDataB64, episodeUrl)

        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return videos.sortedWith(compareByDescending { it.quality == preferred })
    }

    override fun videoListSelector() = ""
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
