package eu.kanade.tachiyomi.animeextension.all.animetsu

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.flatMapCatching
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class Animetsu :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animetsu"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DOMAIN_VALUES.first()) ?: DOMAIN_VALUES.first()

    private val apiUrl: String
        get() = "$baseUrl/v2/api"

    private val proxyUrl = "https://mega-cloud.top/proxy"

    override val lang = "all"

    override val supportsLatest = true

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val enabledServers: Set<String>
        get() = preferences.getStringSet(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_PREFERRED_SERVER_KEY, PREF_PREFERRED_SERVER_DEFAULT) ?: PREF_PREFERRED_SERVER_DEFAULT

    private val enabledAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_TYPE_KEY, PREF_AUDIO_TYPE_DEFAULT) ?: PREF_AUDIO_TYPE_DEFAULT

    private val preferredAudioType: String
        get() = preferences.getString(PREF_PREFERRED_AUDIO_TYPE_KEY, PREF_PREFERRED_AUDIO_TYPE_DEFAULT) ?: PREF_PREFERRED_AUDIO_TYPE_DEFAULT

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private fun apiHeaders(referer: String = "$baseUrl/browse"): Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    private val rateLimit = 5

    override val client by lazy {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = rateLimit, period = 1L, unit = TimeUnit.SECONDS)
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/anime/search/?sort=popularity&page=$page&per_page=35", apiHeaders())

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/anime/recent?page=$page&per_page=35", apiHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuRecentDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.mapNotNull { it.toSAnime(titleLanguage) }

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
        val animes = filteredResults.mapNotNull { it.toSAnime(titleLanguage) }

        return AnimesPage(animes, dto.page < dto.lastPage)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/info/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<AnimetsuAnimeDto>().toSAnime(titleLanguage)!!

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
            .mapNotNull { it.toSEpisode(animeId) }
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
            .filter { server -> server.id in enabledServers }
            .sortedByDescending { server -> server.id == preferredServer }

        val sortedAudioTypes = enabledAudioTypes
            .sortedByDescending { type -> type == preferredAudioType }

        val playlistUtils = PlaylistUtils(client, apiHeaders(watchReferer))

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

                // Following order: AnimePahe proxy server, Anikoto proxy server, AnimeGG proxy server and KickAssAnime proxy server
                val subLabel = when {
                    server.id.equals("pahe", ignoreCase = true) -> " [Hard Subs]"
                    server.id.equals("kite", ignoreCase = true) -> " [Soft Subs]"
                    server.id.equals("meg", ignoreCase = true) -> " [Hard Subs]"
                    server.id.equals("kiss", ignoreCase = true) -> " [Soft Subs]"
                    else -> ""
                }

                dto.sources.flatMapCatching { source ->
                    val fullUrl = when {
                        source.needProxy -> "$proxyUrl${source.url}"
                        source.url.startsWith("http") -> source.url
                        else -> "$baseUrl${source.url}"
                    }

                    when {
                        source.type?.contains("mp4") == true ->
                            Video(
                                fullUrl,
                                "${server.id.uppercase()}: ${source.quality} ($audioLabel)$subLabel",
                                fullUrl,
                                apiHeaders(watchReferer),
                                subtitleTracks,
                            ).let(::listOf)
                        source.type?.contains("mpegurl") == true ->
                            if (source.oldHls) {
                                Video(
                                    fullUrl,
                                    "${server.id.uppercase()}: ${source.quality} ($audioLabel)$subLabel",
                                    fullUrl,
                                    apiHeaders(watchReferer),
                                    subtitleTracks,
                                ).let(::listOf)
                            } else {
                                playlistUtils.extractFromHls(
                                    playlistUrl = fullUrl,
                                    videoNameGen = { quality ->
                                        val cleanQuality = quality.substringBefore(" ").let { q ->
                                            if (q.endsWith("P")) q.lowercase() else q
                                        }
                                        "${server.id.uppercase()}: $cleanQuality ($audioLabel)$subLabel"
                                    },
                                    referer = "$baseUrl/",
                                    subtitleList = subtitleTracks,
                                )
                            }
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
        val type = preferredAudioType
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(type, true) },
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
            key = PREF_SERVER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = SERVER_ENTRIES,
            entryValues = SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
        )

        screen.addSetPreference(
            key = PREF_AUDIO_TYPE_KEY,
            title = "Enable/Disable Audio Types",
            summary = "Select which audio types to show (Sub, Dub)",
            entries = AUDIO_TYPE_ENTRIES,
            entryValues = AUDIO_TYPE_VALUES,
            default = PREF_AUDIO_TYPE_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_ADULT_KEY,
            title = "Hide Adult Content",
            summary = "Hides 18+ content from browse, search, and latest updates.",
            default = PREF_HIDE_ADULT_DEFAULT,
        )
    }

    // ============================= Utilities ==============================

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
        private val PREF_PREFERRED_SERVER_ENTRIES = listOf("None", "Pahe - Fast, Multi Quality", "Kite - Multi Quality", "Meg - Multi Quality", "Kiss - Multi Language")
        private val PREF_PREFERRED_SERVER_VALUES = listOf("none", "pahe", "kite", "meg", "kiss")

        private const val PREF_SERVER_KEY = "enabled_servers"
        private val PREF_SERVER_DEFAULT = setOf("pahe", "kite", "meg", "kiss")
        private val SERVER_ENTRIES = listOf("Pahe - Fast, Multi Quality", "Kite - Multi Quality", "Meg - Multi Quality", "Kiss - Multi Language")
        private val SERVER_VALUES = listOf("pahe", "kite", "meg", "kiss")
        private const val PREF_PREFERRED_AUDIO_TYPE_KEY = "preferred_audio_type"
        private const val PREF_PREFERRED_AUDIO_TYPE_DEFAULT = "none"
        private val PREF_PREFERRED_AUDIO_TYPE_ENTRIES = listOf("None", "Sub", "Dub")
        private val PREF_PREFERRED_AUDIO_TYPE_VALUES = listOf("none", "sub", "dub")

        private const val PREF_AUDIO_TYPE_KEY = "enabled_audio_types"
        private val PREF_AUDIO_TYPE_DEFAULT = setOf("sub")
        private val AUDIO_TYPE_ENTRIES = listOf("Sub", "Dub")
        private val AUDIO_TYPE_VALUES = listOf("sub", "dub")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val PREF_HIDE_ADULT_KEY = "hide_adult_content"
        private const val PREF_HIDE_ADULT_DEFAULT = true

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            else -> SAnime.UNKNOWN
        }
    }
}
