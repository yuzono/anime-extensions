package eu.kanade.tachiyomi.multisrc.dopeflix

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dopeflixextractor.DopeFlixExtractor
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.SourcesResponse
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoData
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.hours

abstract class DopeFlix(
    override val name: String,
    override val lang: String,
    private val megaCloudApi: String,
    private val domainList: List<String>,
    private val defaultDomain: String = "https://${domainList.first()}",
    private val hosterNames: List<String> = listOf(
        "UpCloud",
        "MegaCloud",
        "Vidcloud",
        "AKCloud",
    ),
    private val preferredHoster: String = hosterNames.first(),
    override val supportsLatest: Boolean = true,
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    protected open val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override var baseUrl by LazyMutable { preferences.domainUrl }

    protected open var docHeaders by LazyMutable {
        newHeaders()
    }

    protected open fun newHeaders(): Headers = headers.newBuilder().apply {
        add(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )
        add("Host", baseUrl.toHttpUrl().host)
        add("Referer", "$baseUrl/")
    }.build()

    protected open fun apiHeaders(referer: String = "$baseUrl/"): Headers = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Host", baseUrl.toHttpUrl().host)
        add("Referer", referer)
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    protected open val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // ============================== Popular ===============================

    protected open val filmSelector by lazy { "div.flw-item" }

    protected open fun sectionSelector(type: String) = "section.block_area:has(h2.cat-heading:contains($type)) $filmSelector"

    override fun popularAnimeSelector() = if (preferences.prefPopularType == Types.Movies.value) {
        sectionSelector("Movies")
    } else {
        sectionSelector("TV Shows")
    }

    protected fun trendingSelector() = if (preferences.prefPopularType == Types.Movies.value) {
        "#trending-movies $filmSelector"
    } else {
        "#trending-tv $filmSelector"
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = client.newCall(popularAnimeRequest(page))
        .awaitSuccess().use { response ->
            popularAnimeParse(response).let { animePage ->
                if (page == 1) {
                    AnimesPage(
                        animes = animePage.animes,
                        hasNextPage = true,
                    )
                } else {
                    animePage
                }
            }
        }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val selector = if (url.removeSuffix("/").endsWith("/home")) {
            trendingSelector()
        } else {
            popularAnimeSelector()
        }

        val document = response.asJsoup()
        val animes = document.select(selector).map { popularAnimeFromElement(it) }

        return AnimesPage(animes, hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null)
    }

    override fun popularAnimeRequest(page: Int): Request = when (page) {
        // Trending
        1 -> GET("$baseUrl/home", docHeaders, cacheControl)

        // Popular
        else -> if (preferences.prefPopularType == Types.Movies.value) {
            GET("$baseUrl/movie?page=${page - 1}", docHeaders, cacheControl)
        } else {
            GET("$baseUrl/tv-show?page=${page - 1}", docHeaders, cacheControl)
        }
    }

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")?.let { a ->
            val url = a.attr("href")
                .removeSuffix("/")
                .let {
                    it.substringBeforeLast("/") + "/watch-" + it.substringAfterLast("-")
                }
            setUrlWithoutDomain(url)
            title = a.attr("title")
        }
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[title=next]"

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = client.newCall(latestUpdatesRequest(page))
        .awaitSuccess().use { response ->
            latestUpdatesParse(response, page)
        }

    protected fun latestUpdatesParse(response: Response, page: Int): AnimesPage {
        val selector = if (page == 1) {
            if (preferences.prefLatestPriority == Types.TvShows.value) {
                sectionSelector("TV Shows")
            } else {
                sectionSelector("Movies")
            }
        } else {
            if (preferences.prefLatestPriority == Types.TvShows.value) {
                sectionSelector("Movies")
            } else {
                sectionSelector("TV Shows")
            }
        }

        val document = response.asJsoup()
        val animes = document.select(selector).map { popularAnimeFromElement(it) }

        return AnimesPage(animes, hasNextPage = page == 1)
    }

    /* Both latest Movies & TV Shows are on same home page */
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home/", docHeaders, cacheControl)

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DopeFlixFilters.getSearchParameters(filters)

        val endpoint = if (query.isEmpty()) "filter" else "search"

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(endpoint)
            if (query.isNotBlank()) {
                addPathSegment(query.replace(" ", "-"))
            } else {
                addQueryParameter("type", params.type)
                addQueryParameter("quality", params.quality)
                addQueryParameter("release_year", params.releaseYear)
                addIfNotBlank("genre", params.genres)
                addIfNotBlank("country", params.countries)
            }
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, docHeaders, cacheControl)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = filmSelector

    override fun relatedAnimeListSelector() = filmSelector

    // ============================== Filters ===============================

    override fun getFilterList() = DopeFlixFilters.FILTER_LIST

    // =========================== Anime Details ============================

    protected open val detailInfoSelector by lazy { "div.detail_page-infor" }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst(detailInfoSelector)?.run {
            thumbnail_url = selectFirst("div.film-poster img")?.attr("src")
            author = getInfo(tag = "Production:", isList = true)
            genre = getInfo("Genre:", isList = true)

            val url = document.location()
            val type = if (url.contains("/tv/")) {
                status = SAnime.ONGOING
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                "TV Shows"
            } else {
                status = SAnime.COMPLETED
                update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                "Movies"
            }

            description = buildString {
                selectFirst(".description")
                    ?.let { append("${it.text().removePrefix("Overview:").trim()}\n\n") }
                append("**Type:** $type")
                getInfo("Country:", true, isList = true)?.let(::append)
                getInfo("Casts:", true, isList = true)?.let(::append)
                getInfo("Released:", true)?.let(::append)
                getInfo("Duration:", true)?.let(::append)

                getIMDBRating()?.let { append("\n**IMDB:** $it") }
                document.getTrailer()?.let { append("\n\n$it") }
                document.getCover()?.let { append("\n\n![Cover]($it)") }
            }
        }
    }

    protected open fun Document.getTrailer(): String? = selectFirst("#iframe-trailer")?.let {
        val trailerUrl = it.attr("data-src")
        "[Trailer]($trailerUrl)"
    }

    protected open fun Element.getIMDBRating(): Float? = selectFirst("span:contains(IMDB)")?.text()
        ?.substringAfter("IMDB:")?.trim()?.toFloatOrNull()

    protected open val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    protected open val coverSelector by lazy { "div.cover_follow" }

    protected open fun Document.getCover(): String? = selectFirst(coverSelector)?.let {
        val style = it.attr("style")
        coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
    }

    protected open fun Element.getInfo(
        tag: String,
        includeTag: Boolean = false,
        isList: Boolean = false,
    ): String? {
        val value = if (isList) {
            select("div.row-line:contains($tag) > a").eachText().joinToString().ifBlank { null }
        } else {
            selectFirst("div.row-line:contains($tag)")
                ?.ownText()?.ifBlank { null }
        }
        return if (includeTag && value != null) "\n**$tag** $value" else value
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = ".eps-item"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast("-")
        if (anime.url.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    setUrlWithoutDomain("/ajax/episode/list/$id")
                    episode_number = 1F
                },
            )
        }

        val seasonRequest = GET("$baseUrl/ajax/season/list/$id", apiHeaders(baseUrl + anime.url))
        return client.newCall(seasonRequest).awaitSuccess().use { response ->
            response.asJsoup().select(".ss-item")
                .parallelFlatMap(::seasonFromElement).reversed()
        }
    }

    protected open suspend fun seasonFromElement(element: Element): List<SEpisode> = runCatching {
        val season = element.elementSiblingIndex() + 1
        val seasonId = element.attr("data-id")
        client.newCall(GET("$baseUrl/ajax/season/episodes/$seasonId", apiHeaders()))
            .awaitSuccess().use { response ->
                response.asJsoup().select(episodeListSelector()).map {
                    it.attr("data-season", "%02d".format(season)).let(::episodeFromElement)
                }
            }
    }.getOrElse {
        emptyList()
    }

    protected open val episodeRegex by lazy { """Episode (\d+)""".toRegex() }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        if (element.hasClass("link-item")) {
            val linkId = element.attr("data-linkid")
                .ifEmpty { element.attr("data-id") }
            setUrlWithoutDomain("/ajax/episode/sources/$linkId")
        } else {
            setUrlWithoutDomain("/ajax/episode/servers/" + element.attr("data-id"))
        }

        val ssNum = element.attr("data-season").ifEmpty { "0" }
        val title = element.attr("title").ifEmpty { element.text() }
        val epNum = episodeRegex.find(title)?.run { "%04d".format(groupValues[1].toInt()) } ?: "0"

        episode_number = "$ssNum.$epNum".toFloat()
        name = when {
            episode_number != 0.0F -> "S$ssNum E$epNum: ${title.substringAfter(": ")}"
            else -> title
        }
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = "a.link-item"

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", apiHeaders("$baseUrl${episode.url}"))

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        client.newCall(videoListRequest(episode)).awaitSuccess().use { response ->

            val hosterSelection = preferences.hostToggle

            val serversDoc = response.asJsoup()

            val embedLinks = serversDoc.select(videoListSelector()).parallelMapNotNull { elm ->
                val id = elm.attr("data-linkid")
                    .ifEmpty { elm.attr("data-id") }
                val name = elm.select("span").text()

                if (hosterSelection.none { it.equals(name, true) }) return@parallelMapNotNull null

                val link = client.newCall(
                    GET("$baseUrl/ajax/episode/sources/$id", apiHeaders("$baseUrl${episode.url}")),
                ).await().parseAs<SourcesResponse>().link
                    ?: return@parallelMapNotNull null

                VideoData(link, name)
            }

            return embedLinks.parallelCatchingFlatMap(::extractVideo)
                .map { video ->
                    Video(
                        url = video.url,
                        quality = video.quality,
                        videoUrl = video.videoUrl,
                        headers = video.headers,
                        subtitleTracks = subLangOrder(video.subtitleTracks),
                        audioTracks = subLangOrder(video.audioTracks),
                    )
                }
        }
    }

    protected open val dopeFlixExtractor by LazyMutable { DopeFlixExtractor(client, headers, megaCloudApi) }

    protected open fun extractVideo(server: VideoData): List<Video> = when (server.name) {
        "UpCloud", "MegaCloud", "Vidcloud", "AKCloud" -> dopeFlixExtractor.getVideosFromUrl(server.link, server.name)
        else -> emptyList()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.prefQuality
        val server = preferences.prefServer
        val qualitiesList = PREF_QUALITY_LIST.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    protected open fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.prefSubtitle
        return tracks.sortedWith(
            compareByDescending { it.lang.contains(language) },
        )
    }

    // ============================== Settings ==============================

    protected open var SharedPreferences.domainUrl
        by LazyMutable { preferences.getString(PREF_DOMAIN_KEY, defaultDomain)!! }

    protected open var SharedPreferences.prefPopularType
        by LazyMutable { preferences.getString(PREF_POPULAR_TYPE_KEY, PREF_POPULAR_TYPE_DEFAULT.value)!! }

    protected open var SharedPreferences.prefLatestPriority
        by LazyMutable { preferences.getString(PREF_LATEST_PRIORITY_KEY, PREF_LATEST_PRIORITY_DEFAULT.value)!! }

    protected open var SharedPreferences.prefQuality
        by LazyMutable { preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!! }

    protected open var SharedPreferences.prefSubtitle
        by LazyMutable { preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!! }

    protected open var SharedPreferences.prefServer
        by LazyMutable { preferences.getString(PREF_SERVER_KEY, preferredHoster)!! }

    protected open var SharedPreferences.hostToggle: MutableSet<String>
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!! }

    protected open fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, defaultDomain)!!.removePrefix("https://")
        val hostToggle = getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!!

        val invalidDomain = domain !in domainList
        val invalidHosters = hostToggle.any { it !in hosterNames }

        if (invalidDomain || invalidHosters) {
            edit().also { editor ->
                if (invalidDomain) {
                    editor.putString(PREF_DOMAIN_KEY, defaultDomain)
                }
                if (invalidHosters) {
                    editor.putStringSet(PREF_HOSTER_KEY, hosterNames.toSet())
                    editor.putString(PREF_SERVER_KEY, preferredHoster)
                }
            }.apply()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = domainList,
            entryValues = domainList.map { "https://$it" },
            default = defaultDomain,
            summary = "%s",
        ) {
            baseUrl = it
            preferences.domainUrl = it
            docHeaders = newHeaders()
        }

        screen.addListPreference(
            key = PREF_POPULAR_TYPE_KEY,
            title = PREF_POPULAR_TYPE_TITLE,
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_ENTRIES,
            default = PREF_POPULAR_TYPE_DEFAULT.value,
            summary = "%s",
        ) {
            preferences.prefPopularType = it
        }

        screen.addListPreference(
            key = PREF_LATEST_PRIORITY_KEY,
            title = PREF_LATEST_PRIORITY_TITLE,
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_ENTRIES,
            default = PREF_LATEST_PRIORITY_DEFAULT.value,
            summary = "%s",
        ) {
            preferences.prefLatestPriority = it
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_LIST,
            entryValues = PREF_QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefQuality = it
        }

        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = PREF_SUB_TITLE,
            entries = PREF_SUB_LANGUAGES,
            entryValues = PREF_SUB_LANGUAGES,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefSubtitle = it
        }

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.first(),
            summary = "%s",
        ) {
            preferences.prefServer = it
        }

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.toSet(),
        ) {
            preferences.hostToggle = it.toMutableSet()
        }
    }

    // ============================= Utilities ==============================
    protected open fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    enum class Types(val value: String) {
        Movies("Movies"),
        TvShows("Tv Shows"),
    }

    companion object {
        protected const val PREF_DOMAIN_KEY = "preferred_domain"
        protected const val PREF_DOMAIN_TITLE = "Preferred domain"

        protected const val PREF_POPULAR_TYPE_KEY = "preferred_popular_type"
        protected const val PREF_POPULAR_TYPE_TITLE = "Popular type"
        protected const val PREF_LATEST_PRIORITY_KEY = "preferred_latest_priority"
        protected const val PREF_LATEST_PRIORITY_TITLE = "Latest priority"
        protected val PREF_TYPE_ENTRIES = Types.entries.map { it.value }
        protected val PREF_POPULAR_TYPE_DEFAULT = Types.Movies
        protected val PREF_LATEST_PRIORITY_DEFAULT = Types.TvShows

        protected const val PREF_QUALITY_KEY = "preferred_quality"
        protected const val PREF_QUALITY_TITLE = "Preferred quality"
        protected val PREF_QUALITY_LIST = listOf("1080p", "720p", "480p", "360p")
        protected val PREF_QUALITY_DEFAULT = PREF_QUALITY_LIST.first()

        protected const val PREF_SUB_KEY = "preferred_subLang"
        protected const val PREF_SUB_TITLE = "Preferred sub language"
        protected const val PREF_SUB_DEFAULT = "English"
        protected val PREF_SUB_LANGUAGES = listOf(
            "Arabic", "English", "French", "German", "Hungarian",
            "Italian", "Japanese", "Portuguese", "Romanian", "Russian",
            "Spanish",
        )

        protected const val PREF_SERVER_KEY = "preferred_server"

        protected const val PREF_HOSTER_KEY = "hoster_selection"
    }
}
