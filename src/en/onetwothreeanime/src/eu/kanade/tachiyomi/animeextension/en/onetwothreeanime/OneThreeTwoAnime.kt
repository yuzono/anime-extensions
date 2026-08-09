package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class OneThreeTwoAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "123Anime"

    // baseUrl is a property getter (not lazy) so switching mirrors is instant
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val extractor: OneThreeTwoAnimeExtractor
        get() = OneThreeTwoAnimeExtractor(
            client,
            headers,
            baseUrl,
            preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!,
        )

    // ==================================================================
    //  Sub/Dub preference helper
    //
    //  The site uses language[]=s for sub, language[]=d for dub.
    //  When the user picks "Sub Only" we inject language[]=s into every
    //  filter/search request (and strip it from the anime listing when
    //  browsing Popular/Latest).  "Dub Only" → language[]=d.
    //  "Sub + Dub" (default) → no language filter (site shows both).
    // ==================================================================

    private fun subDubQueryPairs(): List<Pair<String, String>> = when (preferences.getString(PREF_SUB_DUB_KEY, PREF_SUB_DUB_DEFAULT)) {
        SUB_DUB_SUB -> listOf("language[]" to "s")
        SUB_DUB_DUB -> listOf("language[]" to "d")
        else -> emptyList() // SUB_DUB_BOTH — no language filter
    }

    // ==================================================================
    //  Popular anime  (top-ranked "Day" tab on /home ranking widget)
    // ==================================================================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/home", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val rankingSection = doc.selectFirst(".widget.ranking .content[data-name=\"day\"]")
            ?: return AnimesPage(emptyList(), false)
        val animes = rankingSection.select("div.item").mapNotNull(::animeFromCard)
        return AnimesPage(animes, false)
    }

    // ==================================================================
    //  Latest updates  –  "Recently Updated" widget on /home
    //
    //  The widget has three sub-tabs: sub / dub / chinese.
    //  The PREF_LATEST_TAB_KEY preference lets the user choose which tab
    //  to show in the Latest section. The Sub/Dub preference also
    //  influences which tab is preferred here:
    //    • Sub Only  → always show "sub" tab
    //    • Dub Only  → always show "dub" tab
    //    • Sub+Dub   → use the PREF_LATEST_TAB setting
    // ==================================================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()

        // Respect Sub/Dub preference when picking the Latest tab
        val tabName = when (preferences.getString(PREF_SUB_DUB_KEY, PREF_SUB_DUB_DEFAULT)) {
            SUB_DUB_SUB -> "sub"
            SUB_DUB_DUB -> "dub"
            else -> preferences.getString(PREF_LATEST_TAB_KEY, PREF_LATEST_TAB_DEFAULT)!!
        }

        // Try the preferred tab, then each fallback in order
        val recentSection =
            doc.selectFirst(".widget.hotnew .content[data-name=\"$tabName\"]")
                ?: doc.selectFirst(".widget.hotnew .content[data-name=\"sub\"]")
                ?: doc.selectFirst(".widget.hotnew .content")
                ?: return AnimesPage(emptyList(), false)
        val animes = recentSection.select("div.item").mapNotNull(::animeFromCard)
        return AnimesPage(animes, false)
    }

    // ==================================================================
    //  Search + filter  →  GET /filter?…?page=N
    //
    //  The Sub/Dub preference injects language[] query params here so
    //  that search results already match the user's preferred language.
    //  Users can still override per-search by using the Language filter.
    // ==================================================================

    override fun getFilterList() = OneThreeTwoAnimeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = OneThreeTwoAnimeFilters.getSearchParameters(filters)

        val filterBase = if (page > 1) "$baseUrl/filter?page=$page" else "$baseUrl/filter"

        val url = filterBase.toHttpUrl().newBuilder().apply {
            params.queryPairs.forEach { (key, value) ->
                addQueryParameter(key, value)
            }
            if (params.queryPairs.none { it.first == "sort" }) {
                addQueryParameter("sort", "default")
            }
            addQueryParameter("keyword", query.trim())

            // Inject Sub/Dub language filter only when the user's explicit
            // filter selection doesn't already include a language[] param
            if (params.queryPairs.none { it.first == "language[]" }) {
                subDubQueryPairs().forEach { (key, value) ->
                    addQueryParameter(key, value)
                }
            }
        }.build().toString()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val animes = doc.select("div.film-list div.item").mapNotNull(::animeFromCard)
        val hasNext = doc.selectFirst(".paging-wrapper a.next") != null
        return AnimesPage(animes, hasNext)
    }

    // ==================================================================
    //  Anime details  –  GET /anime/{slug}
    // ==================================================================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        return SAnime.create().apply {
            doc.selectFirst("h2.title, h1.title")?.text()?.let { title = it }
            thumbnail_url = doc.selectFirst(".widget.info .thumb img")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            val meta = doc.selectFirst("dl.meta")
            genre = meta?.parseMeta("Genre:")?.joinToString { it.text() }?.takeIf { it.isNotBlank() }
            status = meta?.parseMeta("Status:")
                ?.firstOrNull()?.text()
                .toAnimeStatus()
            description = doc.selectFirst("div.desc")?.text()
        }
    }

    // ==================================================================
    //  Episode list  –  AJAX  GET /ajax/film/sv?id={slug}
    //  JSON: { "html": "…" }
    //  Episodes: ul.episodes.range li a[data-id]
    //  data-id = "mao/1"
    // ==================================================================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.removePrefix("/anime/")
        return GET("$baseUrl/ajax/film/sv?id=$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        @Serializable
        data class SvDto(val html: String = "")

        val svHtml = response.parseAs<SvDto>().html
        val svDoc = org.jsoup.Jsoup.parse(svHtml)

        return svDoc.select("ul.episodes.range li a[data-id]").map { ep ->
            SEpisode.create().apply {
                val numText = ep.text()
                setUrlWithoutDomain(ep.attr("data-id")) // "mao/1"
                name = "Episode $numText"
                episode_number = numText.toFloatOrNull() ?: 0f
            }
        }.reversed()
    }

    // ==================================================================
    //  Video list  –  per-episode, resolved by extractor
    // ==================================================================

    // episode.url is stored as "{animeSlug}/{epNum}" e.g. "aoki-hagane.../1"
    // We make a minimal request here; the body is ignored — fetchVideos() does all the work.
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/ajax/episode/info?epr=${episode.url}/0", headers)

    override fun videoListParse(response: Response): List<Video> {
        // epr param = "animeSlug/epNum/0" — drop trailing "/0" (the dummy serverId)
        val epr = response.use { it.request.url.queryParameter("epr") } ?: return emptyList()
        val withoutServerId = epr.substringBeforeLast("/")
        val lastSlash = withoutServerId.lastIndexOf('/')
        if (lastSlash < 0) return emptyList()
        val animeSlug = withoutServerId.substring(0, lastSlash)
        val epNum = withoutServerId.substring(lastSlash + 1)
        return extractor.fetchVideos(animeSlug, epNum)
    }

    // ==================================================================
    //  Preference screen
    // ==================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Mirror",
            entries = DOMAIN_ENTRIES.toList(),
            entryValues = DOMAIN_VALUES.toList(),
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SUB_DUB_KEY,
            title = "Sub / Dub",
            entries = SUB_DUB_ENTRIES.toList(),
            entryValues = SUB_DUB_VALUES.toList(),
            default = PREF_SUB_DUB_DEFAULT,
            summary = "Filter results by language: %s.\n" +
                "• Sub Only – only subbed anime in search & latest.\n" +
                "• Dub Only – only dubbed anime in search & latest.\n" +
                "• Sub + Dub – no language restriction (show both).\n" +
                "You can always override per-search using the Language filter.",
        )
        screen.addListPreference(
            key = PREF_LATEST_TAB_KEY,
            title = "Latest Updates Tab (when Sub+Dub is selected)",
            entries = LATEST_TAB_ENTRIES.toList(),
            entryValues = LATEST_TAB_VALUES.toList(),
            default = PREF_LATEST_TAB_DEFAULT,
            summary = "Which sub-tab to show in Latest when Sub+Dub mode is active: %s",
        )
        screen.addListPreference(
            key = PREF_PLAYER_KEY,
            title = "Preferred Player",
            entries = PLAYER_ENTRIES.toList(),
            entryValues = PLAYER_VALUES.toList(),
            default = PREF_PLAYER_DEFAULT,
            summary = "Video player used to fetch stream URLs: %s. " +
                "JW Player is recommended — stream URL is embedded directly in the page. " +
                "Legacy Player requires parsing obfuscated JS.",
        )
    }

    // ==================================================================
    //  Utility helpers
    // ==================================================================

    /**
     * Build an [SAnime] from a standard card `div.item`.
     *
     * Two anchor classes exist depending on context:
     *   • a.poster  – filter / search / home-recent pages
     *   • a.thumb   – home ranking widget
     */
    private fun animeFromCard(element: Element): SAnime? {
        val linkEl = element.selectFirst("a.poster[href], a.thumb[href]") ?: return null
        val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val img = linkEl.selectFirst("img[data-src]") ?: linkEl.selectFirst("img")
        val title = element.selectFirst("a.name")?.text()
            ?: img?.attr("alt")?.trim()
            ?: return null

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            thumbnail_url = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("abs:src")
            this.title = title
        }
    }

    private fun Element.parseMeta(label: String): List<Element> {
        val targetDt = select("dt").firstOrNull { it.text() == label }
            ?: return emptyList()
        val dd = targetDt.nextElementSibling() ?: return emptyList()
        return dd.select("a").takeIf { it.isNotEmpty() } ?: listOf(dd)
    }

    private fun String?.toAnimeStatus(): Int = when (this?.lowercase()?.trim()) {
        "ongoing" -> SAnime.ONGOING
        "completed", "finished" -> SAnime.COMPLETED
        "upcoming" -> SAnime.LICENSED
        else -> SAnime.UNKNOWN
    }

    // ==================================================================
    //  Companion
    // ==================================================================

    companion object {
        // Mirror preference
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val DOMAIN_ENTRIES = arrayOf(
            "123anime.ru (Primary)",
            "123anime.la",
            "123anime.cc",
            "123anime.info",
        )
        private val DOMAIN_VALUES = arrayOf(
            "https://123anime.ru",
            "https://123anime.la",
            "https://123anime.cc",
            "https://123anime.info",
        )
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        // Sub/Dub preference
        private const val PREF_SUB_DUB_KEY = "pref_sub_dub"
        private const val SUB_DUB_BOTH = "both"
        private const val SUB_DUB_SUB = "sub"
        private const val SUB_DUB_DUB = "dub"
        private val SUB_DUB_ENTRIES = arrayOf("Sub + Dub (Show Both)", "Sub Only", "Dub Only")
        private val SUB_DUB_VALUES = arrayOf(SUB_DUB_BOTH, SUB_DUB_SUB, SUB_DUB_DUB)
        private const val PREF_SUB_DUB_DEFAULT = SUB_DUB_BOTH

        // Latest tab preference (only active when Sub+Dub mode is selected)
        private const val PREF_LATEST_TAB_KEY = "pref_latest_tab"
        private val LATEST_TAB_ENTRIES = arrayOf("Subbed", "Dubbed", "Chinese")
        private val LATEST_TAB_VALUES = arrayOf("sub", "dub", "chinese")
        private val PREF_LATEST_TAB_DEFAULT = LATEST_TAB_VALUES[0]

        // Player preference
        private const val PREF_PLAYER_KEY = "pref_player"
        private val PLAYER_ENTRIES = arrayOf(
            "JW Player — Recommended",
            "Legacy Player",
        )
        private val PLAYER_VALUES = arrayOf(
            OneThreeTwoAnimeExtractor.PLAYER_JW,
            OneThreeTwoAnimeExtractor.PLAYER_LEGACY,
        )
        private val PREF_PLAYER_DEFAULT = PLAYER_VALUES[0]
    }
}
