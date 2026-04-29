package eu.kanade.tachiyomi.animeextension.en.sflix

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SFlix :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SFlix"
    override val baseUrl = "https://sflix.ch"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Helpers ==============================

    private fun pagedUrl(path: String, page: Int, order: String = "Latest"): String {
        val root = "$baseUrl/" + path.trim('/') + "/"
        val paged = if (page <= 1) root else "${root}page/$page/"
        return "$paged?order=$order"
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(pagedUrl("quality/hd", page, "Latest"), headers)

    override fun popularAnimeSelector() = "div.item.post"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        // Poster wraps the detail-page link: <div class="poster"><a href="…">
        val link = element.selectFirst("div.poster > a")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        // Title: <div class="meta"><a href="…">Title</a>
        title = element.selectFirst("div.meta > a")?.text()
            ?: element.selectFirst("img")?.attr("alt")
                ?: ""
        // Lazy-loaded poster: src is a base64 placeholder, real URL is in data-src
        thumbnail_url = element.selectFirst("img[data-src]")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "a.page-link.next"

    // ============================== Latest ==============================

    // Latest IS the default — same URL as popular.
    override fun latestUpdatesRequest(page: Int): Request = GET(pagedUrl("quality/hd", page, "Latest"), headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedSlug()
        val country = filters.filterIsInstance<CountryFilter>().firstOrNull()?.selectedSlug()
        val quality = filters.filterIsInstance<QualityFilter>().firstOrNull()?.selectedSlug()
        val order = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedOrder() ?: "Latest"

        return when {
            query.isNotBlank() -> {
                // Search: /?s=query  then /page/N/?s=query for subsequent pages
                val url = if (page <= 1) {
                    "$baseUrl/?s=${query.trim()}"
                } else {
                    "$baseUrl/page/$page/?s=${query.trim()}"
                }
                GET(url, headers)
            }
            !country.isNullOrBlank() ->
                GET(pagedUrl("country/$country", page, order), headers)
            !genre.isNullOrBlank() ->
                GET(pagedUrl("category/$genre", page, order), headers)
            !quality.isNullOrBlank() ->
                GET(pagedUrl("quality/$quality", page, order), headers)
            else ->
                GET(pagedUrl("quality/hd", page, order), headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(
            doc.select(popularAnimeSelector()).map { popularAnimeFromElement(it) },
            doc.selectFirst(popularAnimeNextPageSelector()) != null,
        )
    }

    // Never called — searchAnimeParse is fully overridden.
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Details ==============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1[itemprop=name]")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.poster img[data-src]")?.attr("data-src")
            ?: document.selectFirst("div.poster img")?.attr("src")
        description = document.selectFirst("div.description p")?.text()
        genre = document.select("ul.genre li.cat-item a").joinToString { it.text() }
        status = SAnime.UNKNOWN
        document.select("div.detail > div").forEach { row ->
            val label = row.selectFirst("div")?.text()?.lowercase() ?: return@forEach
            val value = row.select("span a").joinToString { it.text() }
                .ifBlank { row.selectFirst("span")?.text()?.trim() }
                ?: return@forEach
            when {
                "director" in label -> author = value
                ("country" in label || "studio" in label) && author.isNullOrBlank() ->
                    author = value
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()

        val serversRaw = doc.selectFirst("script:containsData(var Servers)")?.data() ?: ""
        val imdbId = serversKey("imdb_id").find(serversRaw)?.groupValues?.get(1)

        val isMovie = doc.selectFirst("section#episodes.movie") != null
        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1f
                    setUrlWithoutDomain(pageUrl)
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        var counter = 1
        doc.select("aside#episodes ul.episodes[data-season]").forEach { ul ->
            val season = ul.attr("data-season").toIntOrNull() ?: return@forEach
            ul.select("li").forEachIndexed { idx, _ ->
                val ep = idx + 1
                episodes.add(
                    SEpisode.create().apply {
                        name = "S${season}E${ep.toString().padStart(2, '0')}"
                        episode_number = counter.toFloat()
                        url = buildString {
                            append(pageUrl.substringBefore("?"))
                            append("?__s=$season&__e=$ep")
                            if (!imdbId.isNullOrBlank()) append("&__imdb=$imdbId")
                        }
                    },
                )
                counter++
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1f
                    setUrlWithoutDomain(pageUrl)
                },
            )
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = "aside#episodes ul.episodes li"
    override fun episodeFromElement(element: Element) = SEpisode.create()

    // ============================== Video List ============================

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url is stored relative by setUrlWithoutDomain e.g. "/megadoc-2025/"
        // For TV episodes it also carries our private params: "/show/?__s=1&__e=2&__imdb=tt…"
        // Strip our params first, then make the URL absolute before passing to GET().
        val rawPath = episode.url.substringBefore("?__")
        val absoluteUrl = if (rawPath.startsWith("http")) rawPath else "$baseUrl$rawPath"
        return GET(
            absoluteUrl,
            headers.newBuilder().set(EPISODE_HEADER, episode.url).build(),
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val episodeUrl = response.request.header(EPISODE_HEADER)
            ?: response.request.url.toString()

        val serversRaw = doc.selectFirst("script:containsData(var Servers)")
            ?.data() ?: return emptyList()

        fun key(k: String) = serversKey(k).find(serversRaw)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }

        val imdbId = tvParam("imdb").find(episodeUrl)?.groupValues?.get(1) ?: key("imdb_id")
        val season = tvParam("s").find(episodeUrl)?.groupValues?.get(1)
        val ep = tvParam("e").find(episodeUrl)?.groupValues?.get(1)
        val isTv = season != null && ep != null

        val vidsrcUrl: String? = when {
            isTv && imdbId != null -> "https://vidsrc.xyz/embed/tv/$imdbId/$season-$ep"
            else -> key("embedru")
        }
        val moviesApiUrl: String? = when {
            isTv && imdbId != null -> "https://moviesapi.club/tv/$imdbId-$season-$ep"
            else -> key("vidsrc")
        }

        val extractor = SFlixExtractor(client, headers, baseUrl)
        return listOfNotNull(vidsrcUrl, moviesApiUrl)
            .parallelCatchingFlatMapBlocking { url ->
                when {
                    "vidsrc.xyz" in url -> extractor.fromVidSrc(url, "VidSrc")
                    "moviesapi" in url -> extractor.fromMoviesApi(url, "MoviesAPI", season, ep)
                    else -> emptyList()
                }
            }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Sort ================================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(server, ignoreCase = true) }
                .thenByDescending { it.quality.contains(quality, ignoreCase = true) },
        )
    }

    // ============================== Filters =============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored when using text search"),
        SortFilter(),
        QualityFilter(),
        GenreFilter(),
        CountryFilter(),
    )

    class SortFilter : AnimeFilter.Select<String>("Sort by", SORT_LABELS) {
        fun selectedOrder(): String = SORT_VALUES[state]

        companion object {
            val SORT_LABELS = arrayOf("Latest", "Views", "Rating", "Year", "Title")
            val SORT_VALUES = arrayOf("Latest", "Views", "Rating", "Year", "Title")
        }
    }

    class QualityFilter : AnimeFilter.Select<String>("Quality", QUALITY_LABELS) {
        fun selectedSlug(): String? = QUALITY_SLUGS[state].ifBlank { null }

        companion object {
            val QUALITY_LABELS = arrayOf("All (HD)", "HD", "CAM")
            val QUALITY_SLUGS = arrayOf("hd", "hd", "cam")
        }
    }

    class GenreFilter : AnimeFilter.Select<String>("Genre", GENRE_LABELS) {
        fun selectedSlug(): String? = GENRE_SLUGS[state].ifBlank { null }

        companion object {
            val GENRE_LABELS = arrayOf(
                "All", "Action", "Adventure", "Animation", "Biography",
                "Comedy", "Crime", "Documentary", "Drama", "Family",
                "Fantasy", "History", "Horror", "Movies", "Music",
                "Mystery", "News", "Reality", "Romance", "Science Fiction",
                "Talk", "Thriller", "TV Movie", "TV Series", "War", "Western",
            )
            val GENRE_SLUGS = arrayOf(
                "", "action", "adventure", "animation", "biography",
                "comedy", "crime", "documentary", "drama", "family",
                "fantasy", "history", "horror", "movies", "music",
                "mystery", "news", "reality", "romance", "science-fiction",
                "talk", "thriller", "tv-movie", "tv-series", "war", "western",
            )
        }
    }

    class CountryFilter : AnimeFilter.Select<String>("Country", COUNTRY_LABELS) {
        fun selectedSlug(): String? = COUNTRY_SLUGS[state].ifBlank { null }

        companion object {
            val COUNTRY_LABELS = arrayOf(
                "All", "Argentina", "Australia", "Austria", "Belgium",
                "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Burkina Faso",
                "Canada", "Cape Verde", "Chile", "China", "Colombia", "Croatia",
                "Cyprus", "Czech Republic", "Denmark", "Ecuador", "Egypt",
                "Estonia", "Fiji", "Finland", "France", "Georgia", "Germany",
                "Ghana", "Greece", "Hong Kong", "Hungary", "Iceland", "India",
                "Indonesia", "Iran", "Ireland", "Israel", "Italy", "Japan",
                "Jordan", "Lebanon", "Lithuania", "Luxembourg", "Malaysia",
                "México", "Netherlands", "New Zealand", "Nigeria", "Norway",
                "Pakistan", "Palestinian Territories", "Peru", "Philippines",
                "Poland", "Portugal", "Puerto Rico", "Romania", "Russia",
                "Saudi Arabia", "Serbia", "Singapore", "Slovakia", "Slovenia",
                "South Africa", "South Korea", "Spain", "Sweden", "Switzerland",
                "Taiwan", "Thailand", "Turkey", "UK", "Ukraine", "USA",
                "Venezuela", "Vietnam", "Western Sahara",
            )
            val COUNTRY_SLUGS = arrayOf(
                "", "argentina", "australia", "austria", "belgium",
                "bosnia-and-herzegovina", "brazil", "bulgaria", "burkina-faso",
                "canada", "cape-verde", "chile", "china", "colombia", "croatia",
                "cyprus", "czech-republic", "denmark", "ecuador", "egypt",
                "estonia", "fiji", "finland", "france", "georgia", "germany",
                "ghana", "greece", "hong-kong", "hungary", "iceland", "india",
                "indonesia", "iran", "ireland", "israel", "italy", "japan",
                "jordan", "lebanon", "lithuania", "luxembourg", "malaysia",
                "mexico", "netherlands", "new-zealand", "nigeria", "norway",
                "pakistan", "palestinian-territories", "peru", "philippines",
                "poland", "portugal", "puerto-rico", "romania", "russia",
                "saudi-arabia", "serbia", "singapore", "slovakia", "slovenia",
                "south-africa", "south-korea", "spain", "sweden", "switzerland",
                "taiwan", "thailand", "turkey", "uk", "ukraine", "usa",
                "venezuela", "vietnam", "western-sahara",
            )
        }
    }

    // ============================== Preferences =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Companion ===========================

    companion object {
        private const val EPISODE_HEADER = "X-SFlix-Episode-Url"

        fun serversKey(k: String): Regex = Regex(""""$k"\s*:\s*"([^"]+)"""")
        fun tvParam(param: String): Regex = Regex("""[?&]__$param=([^&]+)""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidSrc"
        private val SERVER_LIST = arrayOf("VidSrc", "MoviesAPI")
    }
}
