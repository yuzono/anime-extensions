package eu.kanade.tachiyomi.animeextension.all.animetsu

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parseAs
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class Animetsu :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animetsu"

    private val preferences: SharedPreferences by getPreferencesLazy { clearOldPrefs() }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DOMAIN_VALUES.first()) ?: DOMAIN_VALUES.first()

    private val apiUrl: String
        get() = "$baseUrl/v2/api"

    private val proxyUrl = "https://swiftstream.top/proxy"

    override val lang = "all"

    override val supportsLatest = true

    // Custom client to avoid HTTP/2 stream timeout and allow longer reads
    private val m3u8Client by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    // M3u8ServerManager uses the m3u8Client to ensure the local server also fetches segments via HTTP/1.1
    private val m3u8ServerManager by lazy { M3u8ServerManager(m3u8Client) }

    // PlaylistUtils uses m3u8Client for HTTP/1.1 and to handle potential garbage prefix in responses
    private val playlistUtils by lazy { PlaylistUtils(m3u8Client) }

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val excludedHosts: Set<String>
        get() = preferences.getStringSet(PREF_HOSTER_EXCLUDE_KEY, PREF_HOSTER_EXCLUDE_DEFAULT) ?: PREF_HOSTER_EXCLUDE_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_PREFERRED_SERVER_KEY, PREF_PREFERRED_SERVER_DEFAULT) ?: PREF_PREFERRED_SERVER_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_TYPE_EXCLUDE_KEY, PREF_AUDIO_TYPE_EXCLUDE_DEFAULT) ?: PREF_AUDIO_TYPE_EXCLUDE_DEFAULT

    private val enabledAudioTypes: Set<String>
        get() = AUDIO_TYPE_VALUES.toSet() - excludedAudioTypes

    private val preferredAudioType: String
        get() = preferences.getString(PREF_PREFERRED_AUDIO_TYPE_KEY, PREF_PREFERRED_AUDIO_TYPE_DEFAULT) ?: PREF_PREFERRED_AUDIO_TYPE_DEFAULT

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val showExtraInfo: Boolean
        get() = preferences.getBoolean(PREF_SHOW_EXTRA_INFO_KEY, PREF_SHOW_EXTRA_INFO_DEFAULT)

    private val showRelations: Boolean
        get() = preferences.getBoolean(PREF_SHOW_RELATIONS_KEY, PREF_SHOW_RELATIONS_DEFAULT)

    private val showCharacters: Boolean
        get() = preferences.getBoolean(PREF_SHOW_CHARACTERS_KEY, PREF_SHOW_CHARACTERS_DEFAULT)

    private val showStaff: Boolean
        get() = preferences.getBoolean(PREF_SHOW_STAFF_KEY, PREF_SHOW_STAFF_DEFAULT)

    private val showTags: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TAGS_KEY, PREF_SHOW_TAGS_DEFAULT)

    private val showTrackers: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TRACKERS_KEY, PREF_SHOW_TRACKERS_DEFAULT)

    private val showTrailer: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TRAILER_KEY, PREF_SHOW_TRAILER_DEFAULT)

    private val showBanner: Boolean
        get() = preferences.getBoolean(PREF_SHOW_BANNER_KEY, PREF_SHOW_BANNER_DEFAULT)

    private val showEpStats: Boolean
        get() = preferences.getBoolean(PREF_SHOW_EP_STATS_KEY, PREF_SHOW_EP_STATS_DEFAULT)

    private fun apiHeaders(referer: String = "$baseUrl/browse"): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    // Builds video headers from API headers, overriding only the specific fields needed for video streams
    private fun videoHeaders(referer: String = "$baseUrl/"): Headers = apiHeaders(referer).newBuilder()
        .set("Accept", "*/*")
        .set("Sec-Fetch-Site", "cross-site")
        .set("Origin", baseUrl)
        .build()

    private val rateLimit = 5

    override val client by lazy {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = rateLimit, period = 1L, unit = TimeUnit.SECONDS)
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/anime/search/?sort=trending&page=$page&per_page=35", apiHeaders())

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/anime/recent?page=$page&per_page=35", apiHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuRecentDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.mapNotNull { it.toSAnime(titleLanguage, showTags, baseUrl = baseUrl) }

        return AnimesPage(animes, dto.currentPage < dto.lastPage)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimetsuFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiUrl/anime/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "35")

            if (query.isNotBlank()) addQueryParameter("query", query)

            filters.firstInstanceOrNull<AnimetsuFilters.SortFilter>()?.getValue()
                ?.let { addQueryParameter("sort", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.FormatFilter>()?.getValue()
                ?.let { addQueryParameter("format", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.StatusFilter>()?.getValue()
                ?.let { addQueryParameter("status", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.SeasonFilter>()?.getValue()
                ?.let { addQueryParameter("season", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.YearFilter>()?.getValue()
                ?.let { addQueryParameter("year", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.CountryFilter>()?.getValue()
                ?.let { addQueryParameter("country", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.SourceFilter>()?.getValue()
                ?.let { addQueryParameter("source", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.GenreFilter>()?.getSelectedValues()?.takeIf(String::isNotBlank)
                ?.let { addQueryParameter("genres", it) }
            filters.firstInstanceOrNull<AnimetsuFilters.TagFilter>()?.getSelectedValues()?.takeIf(String::isNotBlank)
                ?.let { addQueryParameter("tags", it) }
        }

        return GET(urlBuilder.build(), apiHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuSearchDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.mapNotNull { it.toSAnime(titleLanguage, showTags, baseUrl = baseUrl) }

        return AnimesPage(animes, dto.page < dto.lastPage)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/info/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<AnimetsuAnimeDto>().toSAnime(
        titleLanguage = titleLanguage,
        showTags = showTags,
        tagField = TagField(
            showExtraInfo = showExtraInfo,
            showStaff = showStaff,
            showCharacters = showCharacters,
            showRelations = showRelations,
            showTrackers = showTrackers,
            showTrailer = showTrailer,
            showBanner = showBanner,
        ),
        baseUrl = baseUrl,
    )!!

    // ============================== Related ===============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<AnimetsuAnimeDto>()

        return buildList {
            dto.seasons?.mapNotNull { season ->
                if (season.id.isBlank()) return@mapNotNull null
                val seasonTitle = season.title?.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = season.id
                    title = seasonTitle
                    status = parseStatus(season.status)
                }
            }?.let(::addAll)

            dto.relations?.mapNotNull { rel ->
                if (rel.id.isBlank()) return@mapNotNull null
                val relTitle = rel.title?.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rel.id
                    title = relTitle
                    status = parseStatus(rel.status)
                }
            }?.let(::addAll)

            dto.recommendations?.mapNotNull { rec ->
                if (rec.id.isBlank()) return@mapNotNull null
                val recTitle = rec.title?.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rec.id
                    title = recTitle
                    status = parseStatus(rec.status)
                }
            }?.let(::addAll)
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url
        val referer = getAnimeUrl(anime)

        val response = client.newCall(
            GET("$apiUrl/anime/eps/$animeId", apiHeaders(referer)),
        ).awaitSuccess()

        return response.parseAs<List<AnimetsuEpisodeDto>>()
            .mapNotNull { it.toSEpisode(animeId, showEpStats) }
            .sortedByDescending { it.episode_number }
            .takeIf { it.isNotEmpty() } ?: throw Exception("No episodes found")
    }

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/")
        val animeId = parts[0]
        val epNum = parts[1]
        val watchReferer = "$baseUrl/watch/$animeId"

        val serverResponse = client.newCall(
            GET("$apiUrl/anime/servers/$animeId/$epNum", apiHeaders(watchReferer)),
        ).awaitSuccess()

        val allServers = serverResponse.parseAs<List<AnimetsuServerDto>>()

        val servers = allServers
            .filter { server -> server.id.lowercase() !in excludedHosts }
            .sortedByDescending { server -> server.id.equals(preferredServer, ignoreCase = true) }

        val sortedAudioTypes = enabledAudioTypes
            .sortedByDescending { type -> type == preferredAudioType }

        return servers.parallelFlatMap { server ->
            sortedAudioTypes.parallelCatchingFlatMap { sourceType ->
                val audioLabel = sourceType.uppercase()

                val sourceResponse = client.newCall(
                    GET("$apiUrl/anime/oppai/$animeId/$epNum?server=${server.id}&source_type=$sourceType", apiHeaders(watchReferer)),
                ).awaitSuccess()

                val dto = sourceResponse.parseAs<AnimetsuVideoDto>()
                val subtitleTracks = dto.subs?.map { sub ->
                    Track(sub.url, sub.lang ?: "Unknown")
                }.orEmpty()

                val subLabel = when (server.id.lowercase()) {
                    "sage", "dio", "meg" -> "[Hard Subs]"
                    "kite" -> "[Soft Subs]"
                    else -> ""
                }

                val hlsNameGen: (String) -> String = { quality ->
                    val cleanQuality = quality.substringBefore(" ").let { q ->
                        if (q.endsWith("P")) q.lowercase() else q
                    }
                    "${server.id.uppercase()}: $cleanQuality ($audioLabel) $subLabel"
                }

                dto.sources.parallelCatchingFlatMap { source ->
                    val fullUrl = when {
                        source.needProxy -> "$proxyUrl${source.url}"
                        source.url.startsWith("http") -> source.url
                        else -> "$baseUrl${source.url}"
                    }

                    when {
                        source.type?.contains("mp4") == true ->
                            listOf(
                                Video(
                                    fullUrl,
                                    "${server.id.uppercase()}: ${source.quality} ($audioLabel) $subLabel",
                                    fullUrl,
                                    videoHeaders(watchReferer),
                                    subtitleTracks,
                                ),
                            )

                        source.type?.contains("mpegurl") == true ->
                            processHls(fullUrl, hlsNameGen, watchReferer, subtitleTracks)

                        else -> emptyList()
                    }
                }
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferredQuality
        val server = preferredServer
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            restartRequired = true,
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = baseUrl,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = PREF_TITLE_LANG_ENTRIES,
            entryValues = PREF_TITLE_LANG_VALUES,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_PREFERRED_SERVER_KEY,
            title = "Preferred Host",
            entries = PREF_PREFERRED_SERVER_ENTRIES,
            entryValues = PREF_PREFERRED_SERVER_VALUES,
            default = PREF_PREFERRED_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_PREFERRED_AUDIO_TYPE_KEY,
            title = "Preferred Audio Type",
            entries = PREF_PREFERRED_AUDIO_TYPE_ENTRIES,
            entryValues = PREF_PREFERRED_AUDIO_TYPE_VALUES,
            default = PREF_PREFERRED_AUDIO_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_EXCLUDE_KEY,
            title = "Exclude Hosts",
            summary = "Choose which hosts you want to exclude",
            entries = SERVER_ENTRIES,
            entryValues = SERVER_VALUES,
            default = PREF_HOSTER_EXCLUDE_DEFAULT,
        )

        screen.addSetPreference(
            key = PREF_AUDIO_TYPE_EXCLUDE_KEY,
            title = "Exclude Audio Types",
            summary = "Choose which audio types you want to exclude",
            entries = AUDIO_TYPE_ENTRIES,
            entryValues = AUDIO_TYPE_VALUES,
            default = PREF_AUDIO_TYPE_EXCLUDE_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_ADULT_KEY,
            title = "Hide Adult Content",
            summary = "Hides 18+ content from browse, search, and latest updates.",
            default = PREF_HIDE_ADULT_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_EXTRA_INFO_KEY,
            title = "Show Extra Info",
            summary = "Shows extra information of a series in description.",
            default = PREF_SHOW_EXTRA_INFO_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_RELATIONS_KEY,
            title = "Show Relations",
            summary = "Shows related anime (sequels, prequels, etc.) in description.",
            default = PREF_SHOW_RELATIONS_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_CHARACTERS_KEY,
            title = "Show Characters",
            summary = "Shows main characters and voice actors in description.",
            default = PREF_SHOW_CHARACTERS_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_STAFF_KEY,
            title = "Show Staff",
            summary = "Shows staff information in description.",
            default = PREF_SHOW_STAFF_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_TAGS_KEY,
            title = "Show Tags in Genre",
            summary = "Appends community tags to the genre field.",
            default = PREF_SHOW_TAGS_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_TRACKERS_KEY,
            title = "Show Tracker Links",
            summary = "Shows AniList and MyAnimeList links in description.",
            default = PREF_SHOW_TRACKERS_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_TRAILER_KEY,
            title = "Show Trailer",
            summary = "Shows YouTube trailer link in description.",
            default = PREF_SHOW_TRAILER_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_BANNER_KEY,
            title = "Show Banner",
            summary = "Shows anime banner image in description.",
            default = PREF_SHOW_BANNER_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_EP_STATS_KEY,
            title = "Show Episode Stats",
            summary = "Shows Views, Likes, and Dislikes in the scanlator field.",
            default = PREF_SHOW_EP_STATS_DEFAULT,
        )
    }

    // ============================= Utilities ==============================

    /**
     * Fetches and parses HLS playlists using PlaylistUtils with m3u8Client (HTTP/1.1),
     * then enforces local M3U8 server proxying for all extracted videos.
     *
     * PlaylistUtils' parsing naturally handles any garbage prefix before the actual M3U8
     * content since it uses `substringAfter("#EXT-X-STREAM-INF:")` internally.
     */
    private suspend fun processHls(
        fullUrl: String,
        videoNameGen: (String) -> String,
        watchReferer: String,
        subtitleTracks: List<Track>,
    ): List<Video> {
        val vidHeaders = videoHeaders(watchReferer)

        val videos = playlistUtils.extractFromHls(
            playlistUrl = fullUrl,
            referer = watchReferer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
            videoNameGen = videoNameGen,
            subtitleList = subtitleTracks,
        )

        // ENFORCE M3U8 SERVER: proxy through local server, drop if it fails
        return videos.mapNotNull { video ->
            val processedUrl = getProcessedM3u8Url(video.url) ?: return@mapNotNull null
            Video(
                url = processedUrl,
                quality = video.quality,
                videoUrl = processedUrl,
                headers = video.headers,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }
    }

    /**
     * Enforces M3u8 Server with retry mechanism.
     * If the server fails to start or process, it restarts and tries again up to 3 times.
     * Returns null so the video gets strictly dropped.
     */
    private suspend fun getProcessedM3u8Url(originalUrl: String): String? {
        repeat(3) { attempt ->
            try {
                if (!m3u8ServerManager.isRunning()) {
                    m3u8ServerManager.startServer()
                    delay(200L)
                }

                val processedUrl = m3u8ServerManager.processM3u8Url(originalUrl)
                if (processedUrl != null) return processedUrl

                Log.w("Animetsu", "M3U8 server process returned null on attempt ${attempt + 1}, restarting...")
                m3u8ServerManager.stopServer()
                delay(500L)
            } catch (e: Exception) {
                Log.e("Animetsu", "M3U8 server start failed on attempt ${attempt + 1}: ${e.message}")
                m3u8ServerManager.stopServer()
                delay(500L)
            }
        }

        Log.e("Animetsu", "M3U8 server failed to process URL after 3 attempts. Dropping video to prevent cubes.")
        return null
    }

    private fun SharedPreferences.clearOldPrefs() {
        val hostExclusion = getStringSet(PREF_HOSTER_EXCLUDE_KEY, PREF_HOSTER_EXCLUDE_DEFAULT)!!
        val invalidHosters = hostExclusion.any { it !in SERVER_VALUES }
        val invalidServer = getString(PREF_PREFERRED_SERVER_KEY, PREF_PREFERRED_SERVER_DEFAULT) !in PREF_PREFERRED_SERVER_VALUES
        val oldAudioTypes = getStringSet(PREF_AUDIO_TYPE_OLD_KEY, null)

        if (invalidHosters || invalidServer || oldAudioTypes != null) {
            edit().also { editor ->
                if (invalidHosters) editor.putStringSet(PREF_HOSTER_EXCLUDE_KEY, hostExclusion.filter { it in SERVER_VALUES }.toSet())
                if (invalidServer) editor.putString(PREF_PREFERRED_SERVER_KEY, PREF_PREFERRED_SERVER_DEFAULT)
                if (oldAudioTypes != null) {
                    val newExclusion = AUDIO_TYPE_VALUES.toSet() - oldAudioTypes
                    editor.putStringSet(PREF_AUDIO_TYPE_EXCLUDE_KEY, newExclusion)
                    editor.remove(PREF_AUDIO_TYPE_OLD_KEY)
                }
            }.apply()
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("animetsu.net", "animetsu.live", "animetsu.bz", "animetsu.cc")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")

        private const val PREF_PREFERRED_SERVER_KEY = "preferred_server"
        private const val PREF_PREFERRED_SERVER_DEFAULT = "none"
        private val PREF_PREFERRED_SERVER_ENTRIES = listOf("None", "Sage - Multi Quality", "Dio - Multi Quality", "Meg - Multi Quality", "Kite - Multi Quality")
        private val PREF_PREFERRED_SERVER_VALUES = listOf("none", "sage", "dio", "meg", "kite")

        private const val PREF_HOSTER_EXCLUDE_KEY = "hoster_exclusion"
        private val PREF_HOSTER_EXCLUDE_DEFAULT = emptySet<String>()
        private val SERVER_ENTRIES = listOf("Sage - Multi Quality", "Dio - Multi Quality", "Meg - Multi Quality", "Kite - Multi Quality")
        private val SERVER_VALUES = listOf("sage", "dio", "meg", "kite")

        private const val PREF_PREFERRED_AUDIO_TYPE_KEY = "preferred_audio_type"
        private const val PREF_PREFERRED_AUDIO_TYPE_DEFAULT = "none"
        private val PREF_PREFERRED_AUDIO_TYPE_ENTRIES = listOf("None", "Sub", "Dub")
        private val PREF_PREFERRED_AUDIO_TYPE_VALUES = listOf("none", "sub", "dub")

        private const val PREF_AUDIO_TYPE_OLD_KEY = "enabled_audio_types"
        private const val PREF_AUDIO_TYPE_EXCLUDE_KEY = "audio_type_exclusion"
        private val PREF_AUDIO_TYPE_EXCLUDE_DEFAULT = emptySet<String>()
        private val AUDIO_TYPE_ENTRIES = listOf("Sub", "Dub")
        private val AUDIO_TYPE_VALUES = listOf("sub", "dub")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val PREF_HIDE_ADULT_KEY = "hide_adult_content"
        private const val PREF_HIDE_ADULT_DEFAULT = true

        private const val PREF_SHOW_EXTRA_INFO_KEY = "show_extra_info"
        private const val PREF_SHOW_EXTRA_INFO_DEFAULT = true
        private const val PREF_SHOW_STAFF_KEY = "show_staff"
        private const val PREF_SHOW_STAFF_DEFAULT = true
        private const val PREF_SHOW_RELATIONS_KEY = "show_relations"
        private const val PREF_SHOW_RELATIONS_DEFAULT = true
        private const val PREF_SHOW_CHARACTERS_KEY = "show_characters"
        private const val PREF_SHOW_CHARACTERS_DEFAULT = true
        private const val PREF_SHOW_TAGS_KEY = "show_tags_in_genre"
        private const val PREF_SHOW_TAGS_DEFAULT = true
        private const val PREF_SHOW_TRACKERS_KEY = "show_trackers"
        private const val PREF_SHOW_TRACKERS_DEFAULT = true
        private const val PREF_SHOW_TRAILER_KEY = "show_trailer"
        private const val PREF_SHOW_TRAILER_DEFAULT = true
        private const val PREF_SHOW_EP_STATS_KEY = "show_ep_stats"
        private const val PREF_SHOW_EP_STATS_DEFAULT = true
        private const val PREF_SHOW_BANNER_KEY = "show_banner"
        private const val PREF_SHOW_BANNER_DEFAULT = true

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            else -> SAnime.UNKNOWN
        }

        val newLineRegex by lazy { Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE) }
        val textStyleRegex by lazy { Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE) }
    }
}
