package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.os.Looper
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.extractor.KwikExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/* API: https://gist.github.com/Ellivers/f7716b6b6895802058c367963f3a2c51 */
class AnimePahe :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val interceptor = DdosGuardInterceptor(network.client) { cfBypassUserAgent }
    override val client = network.client.newBuilder()
        .addInterceptor(interceptor)
        .build()

    private val extractorClient by lazy {
        client.newBuilder().apply {
            interceptors().removeAll { it is DdosGuardInterceptor }
        }.build()
    }

    override val name = "AnimePahe"

    override val baseUrl by lazy {
        val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
        if (stored != null && stored in PREF_DOMAIN_VALUES) {
            stored
        } else {
            // Normalize invalid or null value back to the default to keep preferences consistent
            preferences.edit()
                .putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
                .apply()
            PREF_DOMAIN_DEFAULT
        }
    }

    override val lang = "en"

    override val supportsLatest = false

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val request = animeDetailsRequest(anime)
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return animeDetailsParse(response)
                } else {
                    val animeId = anime.getId()
                    if (animeId != null) {
                        val newSession = fetchSessionFromTitle(animeId, anime.title)
                        if (newSession != null) {
                            saveSessionToCache(animeId, newSession)
                            val newRequest = GET("$baseUrl/anime/$newSession")
                            client.newCall(newRequest).execute().use { newResponse ->
                                if (newResponse.isSuccessful) {
                                    return animeDetailsParse(newResponse)
                                }
                                throw IOException("HTTP ${newResponse.code} fetching details for '${anime.title}' (ID: $animeId) at ${newRequest.url}")
                            }
                        }
                    }
                    throw IOException("HTTP ${response.code} fetching details for '${anime.title}' (ID: ${animeId ?: "N/A"}) at ${request.url}")
                }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Network error fetching details for '${anime.title}' (ID: ${anime.getId() ?: "N/A"})", e)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val animeId = ANIME_ID_REGEX.find(document.outerHtml())?.groupValues?.get(1)

        return SAnime.create().apply {
            if (animeId != null) {
                url = "/a/$animeId"
            }

            title = document.selectFirst("div.title-wrapper > h1 > span")!!.text()
            author = document.selectFirst("div.col-sm-4.anime-info p:contains(Studios:)")
                ?.text()
                ?.replace("Studios: ", "")
            document.selectFirst("div.col-sm-4.anime-info p:contains(Status:) a")?.text()
                ?.let { status = parseStatus(it) }
            thumbnail_url = document.selectFirst("div.anime-poster a")?.attr("href")
            genre = document.select(
                "div.anime-genre ul li, " +
                    "div.col-sm-4.anime-info p:contains(Demographic:) a, " +
                    "div.col-sm-4.anime-info p:contains(Theme:) a",
            )
                .joinToString { it.text() }
            description = StringBuilder().apply {
                append(document.select("div.anime-summary").text())
                document.selectFirst("div.col-sm-4.anime-info p:contains(Synonyms:)")?.text()
                    .takeIf { !it.isNullOrBlank() }
                    ?.let { append("\n\n$it") }
                document.selectFirst("div.col-sm-4.anime-info p:contains(Japanese:)")?.text()
                    .takeIf { !it.isNullOrBlank() }
                    ?.let { append("\n\n$it") }
                document.selectFirst("div.col-sm-4.anime-info p:contains(Aired:)")?.text()
                    .takeIf { !it.isNullOrBlank() }
                    ?.let { append("\n\n$it") }
                document.selectFirst("div.col-sm-4.anime-info p:contains(Season:)")?.text()
                    .takeIf { !it.isNullOrBlank() }
                    ?.let { append("\n\n$it") }
                document.select("div.col-sm-4.anime-info p:contains(External Links:) a")
                    .joinToString { "[${it.ownText()}](${it.attr("abs:href")})" }
                    .takeIf { it.isNotBlank() }
                    ?.let { append("\n\n*External Links:* $it") }
            }.toString()
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api?m=airing&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val latestData = response.parseAs<ResponseDto<LatestAnimeDto>>()
        val hasNextPage = latestData.currentPage < latestData.lastPage
        val animeList = latestData.items.map { anime ->
            saveSessionToCache(anime.id.toString(), anime.session)

            SAnime.create().apply {
                title = anime.title
                thumbnail_url = anime.snapshot
                setUrlWithoutDomain("/a/${anime.id}")
                artist = anime.fansub
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<Filters.GenresFilter>().firstOrNull()
        val demographicFilter = filters.filterIsInstance<Filters.DemographicFilter>().firstOrNull()
        val themeFilter = filters.filterIsInstance<Filters.ThemeFilter>().firstOrNull()
        val yearFilter = filters.filterIsInstance<Filters.YearFilter>().firstOrNull()
        val seasonFilter = filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()

        return if (query.isNotBlank()) {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("m", "search")
                // addQueryParameter("l", "8")
                addQueryParameter("q", query)
            }
            GET(urlBuilder.build())
        } else {
            when {
                genresFilter != null && !genresFilter.isDefault() -> {
                    val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("anime")
                        addPathSegment("genre")
                        addPathSegment(genresFilter.toUriPart())
                    }
                    GET(urlBuilder.build())
                }

                demographicFilter != null && !demographicFilter.isDefault() -> {
                    val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("anime")
                        addPathSegment("demographic")
                        addPathSegment(demographicFilter.toUriPart())
                    }
                    GET(urlBuilder.build())
                }

                themeFilter != null && !themeFilter.isDefault() -> {
                    val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("anime")
                        addPathSegment("theme")
                        addPathSegment(themeFilter.toUriPart())
                    }
                    GET(urlBuilder.build())
                }

                yearFilter != null && !yearFilter.isDefault() && seasonFilter != null -> {
                    val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("anime")
                        addPathSegment("season")
                        addPathSegment("${seasonFilter.toUriPart()}-${yearFilter.toUriPart()}")
                    }
                    GET(urlBuilder.build())
                }

                else -> popularAnimeRequest(page)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        if (url.pathSegments.contains("api") && url.queryParameter("m") == "search") {
            val searchData = response.parseAs<ResponseDto<SearchResultDto>>()
            val animeList = searchData.items.map { anime ->
                saveSessionToCache(anime.id.toString(), anime.session)

                SAnime.create().apply {
                    title = anime.title
                    thumbnail_url = anime.poster
                    setUrlWithoutDomain("/a/${anime.id}")
                }
            }
            return AnimesPage(animeList, false)
        } else if (url.pathSegments.contains("anime")) {
            val document = response.useAsJsoup()
            val entries = document.select("div.index div > a").mapNotNull { a ->
                a.attr("href").takeIf { it.isNotBlank() }
                    ?.let {
                        SAnime.create().apply {
                            setUrlWithoutDomain(it)
                            title = a.ownText()
                        }
                    }
            }
            return AnimesPage(entries, false)
        }
        return AnimesPage(emptyList(), false)
    }

    // ============================== Latest ===============================
    // This source doesn't have a popular animes page,
    // so we use latest animes page instead.
    override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    // =============================== Relation/Suggestions ===============================
    override fun relatedAnimeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.useAsJsoup()
        val relationAnimes = document.select("div.anime-content div.anime-relation .mx-n1")
        val recommendationAnimes = document.select("div.anime-content div.anime-recommendation .mx-n1")
        return (relationAnimes + recommendationAnimes).mapNotNull { entry ->
            entry.selectFirst("h5 > a")?.let { it: Element ->
                val sessionUrl = it.attr("href")
                val session = sessionIdRegex.find(sessionUrl)?.groupValues?.get(1)
                val knownId = session?.let { getIdFromCache(it) }

                SAnime.create().apply {
                    setUrlWithoutDomain(knownId?.let { "/a/$it" } ?: sessionUrl)
                    title = it.ownText()
                    thumbnail_url = entry.selectFirst("img")?.attr("abs:data-src")
                }
            }
        }
    }

    // ============================== Episodes ==============================
    private val sessionIdRegex by lazy { Regex("""/anime/([\w-]+)""") }
    private val newAnimeIdRegex by lazy { Regex("""/a/(\d+)""") }
    private val oldQueryIdRegex by lazy { Regex("""\?anime_id=(\d+)""") }

    private fun SAnime.getId() = newAnimeIdRegex.find(url)?.groupValues?.get(1)
        ?: oldQueryIdRegex.find(url)?.groupValues?.get(1)

    private fun SAnime.getSession() = sessionIdRegex.find(url)?.groupValues?.get(1)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val request = episodeListRequest(anime)
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return episodeListParse(response)
                } else {
                    val animeId = anime.getId()
                    val title = anime.title
                    if (animeId != null && title.isNotBlank()) {
                        val newSession = fetchSessionFromTitle(animeId, title)
                        if (newSession != null) {
                            saveSessionToCache(animeId, newSession)
                            val newUrl = baseUrl.toHttpUrl().newBuilder().apply {
                                addPathSegment("api")
                                addQueryParameter("m", "release")
                                addQueryParameter("id", newSession)
                                addQueryParameter("sort", "episode_asc")
                                addQueryParameter("page", "1")
                                addQueryParameter("anime_id", animeId)
                                addQueryParameter("title", title)
                            }.build()
                            client.newCall(GET(newUrl)).execute().use { newResponse ->
                                if (newResponse.isSuccessful) {
                                    return episodeListParse(newResponse)
                                }
                                throw IOException("HTTP ${newResponse.code} fetching episodes for '$title' (ID: $animeId) at $newUrl")
                            }
                        }
                    }
                    throw IOException("HTTP ${response.code} fetching episodes for '$title' (ID: ${animeId ?: "N/A"}) at ${request.url}")
                }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Network error fetching episodes for '${anime.title}' (ID: ${anime.getId() ?: "N/A"})", e)
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.getId()
        var session = anime.getSession() ?: animeId?.let { getSessionFromCache(it) }

        if (session == null && animeId != null && Looper.myLooper() != Looper.getMainLooper()) {
            session = fetchSessionFromTitle(animeId, anime.title)
            if (session != null) {
                saveSessionToCache(animeId, session)
            }
        }

        if (session == null) {
            throw IllegalStateException("Anime session not found.")
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("m", "release")
            addQueryParameter("id", session)
            addQueryParameter("sort", "episode_asc")
            addQueryParameter("page", "1")
            animeId?.let { addQueryParameter("anime_id", it) }
            addQueryParameter("title", anime.title)
        }.build()

        return GET(url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url
        val session = url.queryParameter("id")
            ?: throw IllegalStateException("Anime session not found in URL: $url")

        val episodesData = response.parseAs<ResponseDto<EpisodeDto>>()
        val episodeList = mutableListOf<SEpisode>()
        recursivePages(episodeList, episodesData, session, url.toString().substringBefore("&page="))

        val showSiteEpisodeNumber = preferences.getBoolean(PREF_SHOW_SITE_NUMBER_KEY, PREF_SHOW_SITE_NUMBER_DEFAULT)

        return episodeList.mapIndexed { index, episode ->
            val siteEpisodeNumber = episode.name.removePrefix("Episode ")
            episode.apply {
                episode_number = (index + 1).toFloat()
                name = if (showSiteEpisodeNumber && siteEpisodeNumber != (index + 1).toString()) {
                    "Episode ${index + 1} ($siteEpisodeNumber)"
                } else {
                    "Episode ${index + 1}"
                }
            }
        }.reversed()
    }

    private fun recursivePages(
        episodeList: MutableList<SEpisode>,
        episodesData: ResponseDto<EpisodeDto>,
        animeSession: String,
        urlWithoutPage: String,
    ) {
        val page = episodesData.currentPage
        val hasNextPage = page < episodesData.lastPage

        if (episodesData.items.isNotEmpty()) {
            val animeId = episodesData.items.first().animeId.toString()
            saveSessionToCache(animeId, animeSession)
        }

        episodeList.addAll(parseEpisodePage(episodesData.items, animeSession))
        if (hasNextPage) {
            nextPageRequest(urlWithoutPage, page + 1).use { nextPage ->
                val nextData = nextPage.parseAs<ResponseDto<EpisodeDto>>()
                recursivePages(episodeList, nextData, animeSession, urlWithoutPage)
            }
        }
    }

    private fun parseEpisodePage(episodes: List<EpisodeDto>, animeSession: String): MutableList<SEpisode> = episodes.map { episode ->
        SEpisode.create().apply {
            date_upload = episode.createdAt.let { DATE_FORMATTER.tryParse(it) }
            val session = episode.session
            setUrlWithoutDomain("/play/$animeSession/$session?anime_id=${episode.animeId}")
            val epNum = episode.episodeNumber
            episode_number = epNum
            val epName = if (floor(epNum) == ceil(epNum)) {
                epNum.toInt().toString()
            } else {
                epNum.toString()
            }
            name = "Episode $epName"
        }
    }.toMutableList()

    private fun nextPageRequest(urlWithoutPage: String, page: Int): Response {
        val request = GET("$urlWithoutPage&page=$page")
        return client.newCall(request).execute()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val urlPath = episode.url.substringBefore("?")
        return GET("$baseUrl$urlPath", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val downloadLinks = document.select("div#pickDownload > a")
        val links = document.select("div#resolutionMenu > button").withIndex().map { (index, btn) ->
            val kwikLink = btn.attr("data-src")
            val quality = btn.text()
            val paheWinLink = downloadLinks.getOrNull(index)?.attr("href")
            Triple(kwikLink, paheWinLink, quality)
        }

        val useHLS = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)
        val cfUA = cfBypassUserAgent // Get the custom UA once

        val videos = if (!useHLS) {
            val mp4Videos = links.parallelCatchingFlatMapBlocking { (_, paheWinLink, quality) ->
                if (paheWinLink.isNullOrBlank()) return@parallelCatchingFlatMapBlocking emptyList()
                KwikExtractor(client, headers, cfUA).getStreamVideo(paheWinLink, quality).let(::listOf)
            }
            AnimePaheHlsServer.processMp4VideoList(client, mp4Videos)
        } else {
            emptyList()
        }

        return videos.ifEmpty {
            val hlsVideos = links.parallelCatchingFlatMapBlocking { (kwikLink, _, quality) ->
                KwikExtractor(extractorClient, headers, cfUA).getHlsVideo(kwikLink, referer = "$baseUrl/", quality = "$quality (HLS)")
                    .let(::listOf)
            }
            AnimePaheHlsServer.processVideoList(extractorClient, hlsVideos)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val shouldBeAv1 = preferences.getBoolean(PREF_AV1_KEY, PREF_AV1_DEFAULT)
        val shouldEndWithEng = subPreference == "eng"

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(preferredQuality) }
                .thenByDescending {
                    val quality = it.quality
                    QUALITY_REGEX_P.find(quality)?.groupValues?.get(1)?.toIntOrNull()
                        ?: QUALITY_REGEX.find(quality)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                }
                .thenByDescending {
                    val quality = it.quality.lowercase()
                    val isDub = quality.contains("eng")
                    if (shouldEndWithEng) isDub else !isDub
                }
                .thenByDescending { it.quality.lowercase().contains("av1") == shouldBeAv1 },
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.GenresFilter(),
        AnimeFilter.Separator(),
        Filters.DemographicFilter(),
        AnimeFilter.Separator(),
        Filters.ThemeFilter(),
        AnimeFilter.Separator(),
        Filters.YearFilter(),
        Filters.SeasonFilter(),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
            restartRequired = true,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = PREF_SUB_TITLE,
            entries = PREF_SUB_ENTRIES,
            entryValues = PREF_SUB_VALUES,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_SITE_NUMBER_KEY,
            title = PREF_SHOW_SITE_NUMBER_TITLE,
            summary = PREF_SHOW_SITE_NUMBER_SUMMARY,
            default = PREF_SHOW_SITE_NUMBER_DEFAULT,
        )
        screen.addSwitchPreference(
            key = PREF_LINK_TYPE_KEY,
            title = PREF_LINK_TYPE_TITLE,
            summary = PREF_LINK_TYPE_SUMMARY,
            default = PREF_LINK_TYPE_DEFAULT,
        )
        screen.addSwitchPreference(
            key = PREF_AV1_KEY,
            title = PREF_AV1_TITLE,
            summary = PREF_AV1_SUMMARY,
            default = PREF_AV1_DEFAULT,
        )
        screen.addEditTextPreference(
            key = PREF_CF_UA_KEY,
            title = PREF_CF_UA_TITLE,
            summary = PREF_CF_UA_SUMMARY,
            default = PREF_CF_UA_DEFAULT,
            onChange = { preference, newValue ->
                if (newValue.isBlank()) {
                    (preference as EditTextPreference).text = PREF_CF_UA_DEFAULT
                    false
                } else {
                    true
                }
            },
        )
    }

    // ============================= Utilities ==============================
    private fun saveSessionToCache(animeId: String, session: String) {
        val idCached = getSessionFromCache(animeId) == session
        val sessionCached = getIdFromCache(session) == animeId

        if (!idCached || !sessionCached) {
            preferences.edit()
                .putString("session_cache_$animeId", session)
                .putString("id_cache_$session", animeId)
                .apply()
        }
    }

    private fun getSessionFromCache(animeId: String): String? = preferences.getString("session_cache_$animeId", null)

    private fun getIdFromCache(session: String): String? = preferences.getString("id_cache_$session", null)

    private fun fetchSessionFromTitle(animeId: String, title: String?): String? {
        if (title.isNullOrBlank()) return null

        val searchUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("m", "search")
            addQueryParameter("q", title)
        }.build()

        return try {
            client.newCall(GET(searchUrl)).execute().use { response ->
                val searchData = response.parseAs<ResponseDto<SearchResultDto>>()
                searchData.items.firstOrNull { it.id.toString() == animeId }?.session
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString) {
        "Currently Airing" -> SAnime.ONGOING
        "Finished Airing" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private val cfBypassUserAgent: String
        get() {
            val stored = preferences.getString(PREF_CF_UA_KEY, PREF_CF_UA_DEFAULT)
            return if (stored.isNullOrBlank()) {
                PREF_CF_UA_DEFAULT
            } else {
                stored.trim()
            }
        }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private val ANIME_ID_REGEX = Regex("""<meta name="id" content="(\d+)">""")

        private val QUALITY_REGEX_P by lazy { Regex("""(\d+)p""") }
        private val QUALITY_REGEX by lazy { Regex("""(\d+)""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred Domain (Requires App Restart)"
        private val PREF_DOMAIN_ENTRIES = listOf(
            "animepahe.pw",
            "animepahe.com",
            "animepahe.org",
        )
        private val PREF_DOMAIN_VALUES = PREF_DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_VALUES.first()

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_TITLE = "Preferred Type"
        private const val PREF_SUB_DEFAULT = "jpn"
        private val PREF_SUB_ENTRIES = listOf("Sub", "Dub")
        private val PREF_SUB_VALUES = listOf("jpn", "eng")

        private const val PREF_LINK_TYPE_KEY = "preferred_link_type"
        private const val PREF_LINK_TYPE_TITLE = "Use HLS Links"
        private const val PREF_LINK_TYPE_DEFAULT = true
        private val PREF_LINK_TYPE_SUMMARY = """Enable this if you are having Cloudflare issues.
        """.trimMargin()

        private const val PREF_AV1_KEY = "preferred_av1"
        private const val PREF_AV1_TITLE = "Use AV1 Codec"
        private const val PREF_AV1_DEFAULT = false
        private val PREF_AV1_SUMMARY = """Enable to use AV1 if available
            |Turn off to never select av1 as preferred codec
        """.trimMargin()

        private const val PREF_SHOW_SITE_NUMBER_KEY = "pref_show_site_number"
        private const val PREF_SHOW_SITE_NUMBER_TITLE = "Show Site Episode Number"
        private const val PREF_SHOW_SITE_NUMBER_DEFAULT = false
        private const val PREF_SHOW_SITE_NUMBER_SUMMARY = "Show the actual episode number from the site in the episode title"

        const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        private const val PREF_CF_UA_KEY = "cf_bypass_ua"
        private const val PREF_CF_UA_TITLE = "Custom User-Agent"
        private const val PREF_CF_UA_DEFAULT = UA
        private val PREF_CF_UA_SUMMARY = """Custom User-Agent string for the Cloudflare WebView bypass.
            |Leave blank to revert to the default.
        """.trimMargin()
    }
}
