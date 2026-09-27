package eu.kanade.tachiyomi.animeextension.all.torrentio

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMeta
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMetaDetail
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMetaDetailResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaSearchResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Torrentio :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Torrentio (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(RATE_LIMIT)
        .build()

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val cinemetaUrl = "https://v3-cinemeta.strem.io"
    private val streamingCatalogUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ============================== Popular ================================
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage = coroutineScope {
        val movieDeferred = async(Dispatchers.IO) {
            runCatching {
                val url = "$streamingCatalogUrl/catalog/movie/$DEFAULT_STREAMING_SERVICE.json"
                val response = client.get(url)
                json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
            }.getOrDefault(emptyList())
        }

        val seriesDeferred = async(Dispatchers.IO) {
            runCatching {
                val url = "$streamingCatalogUrl/catalog/series/$DEFAULT_STREAMING_SERVICE.json"
                val response = client.get(url)
                json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
            }.getOrDefault(emptyList())
        }

        val movieResults = movieDeferred.await()
        val seriesResults = seriesDeferred.await()

        val combined = (movieResults + seriesResults).distinctBy { it.id }
        AnimesPage(combined.map { it.toSAnime() }, false)
    }

    // =============================== Latest / Trending =====================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = coroutineScope {
        val seriesDeferred = async(Dispatchers.IO) {
            runCatching {
                val url = buildCinemetaTopUrl("series", page)
                val response = client.get(url.toString())
                json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
            }.getOrDefault(emptyList())
        }

        val movieDeferred = async(Dispatchers.IO) {
            runCatching {
                val url = buildCinemetaTopUrl("movie", page)
                val response = client.get(url.toString())
                json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
            }.getOrDefault(emptyList())
        }

        val seriesResults = seriesDeferred.await()
        val movieResults = movieDeferred.await()

        val combined = (seriesResults + movieResults).sortedByDescending { it.popularity ?: 0.0 }
        val hasNextPage = seriesResults.size == CINEMETA_PAGE_SIZE || movieResults.size == CINEMETA_PAGE_SIZE

        AnimesPage(combined.map { it.toSAnime() }, hasNextPage)
    }

    private fun buildCinemetaTopUrl(type: String, page: Int) = cinemetaUrl.toHttpUrl().newBuilder()
        .addPathSegment("catalog")
        .addPathSegment(type)
        .let {
            val skip = (page - 1) * CINEMETA_PAGE_SIZE
            if (skip > 0) it.addPathSegments("top/skip=$skip.json") else it.addPathSegment("top.json")
        }
        .build()

    // =============================== Search =================================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1) ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val imdbId = query.removePrefix(PREFIX_SEARCH)

            var meta: CinemetaMetaDetail? = null
            for (t in listOf("movie", "series")) {
                meta = runCatching {
                    val res = client.get("$cinemetaUrl/meta/$t/$imdbId.json")
                    json.decodeFromString<CinemetaMetaDetailResponse>(res.body.string()).meta
                }.getOrNull()
                if (meta != null) break
            }

            meta ?: return AnimesPage(emptyList(), false)

            val resolvedType = meta.type?.lowercase() ?: "movie"

            return AnimesPage(
                listOf(
                    SAnime.create().apply {
                        url = "$imdbId,$resolvedType"
                        title = meta.name.orEmpty()
                        thumbnail_url = meta.poster.orEmpty()
                        description = meta.description.orEmpty()
                        genre = meta.genres?.joinToString() ?: meta.genre?.joinToString().orEmpty()
                        author = meta.writer?.joinToString() ?: meta.director?.joinToString().orEmpty()
                        artist = meta.cast?.take(4)?.joinToString().orEmpty()
                        status = when (meta.status?.trim()?.lowercase()) {
                            "continuing" -> SAnime.ONGOING
                            "ended" -> SAnime.COMPLETED
                            else -> SAnime.UNKNOWN
                        }
                    },
                ),
                false,
            )
        }

        val types = CatalogFilters.mediaType(filters)
        val streamingService = CatalogFilters.streamingService(filters)
        val trimmedQuery = query.trim()

        return coroutineScope {
            val deferredResults = types.map { type ->
                async(Dispatchers.IO) {
                    runCatching {
                        val url = if (trimmedQuery.isBlank()) {
                            "$streamingCatalogUrl/catalog/$type/$streamingService.json"
                        } else {
                            "$cinemetaUrl/catalog/$type/top/search=$trimmedQuery.json"
                        }
                        val response = client.get(url)
                        json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
                    }.getOrDefault(emptyList())
                }
            }

            val combined = deferredResults.awaitAll().flatten().distinctBy { it.id }
            AnimesPage(combined.map { it.toSAnime() }, false)
        }
    }

    // =============================== Filters ================================

    override fun getFilterList(): AnimeFilterList = CatalogFilters.getFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val meta = runCatching {
            json.decodeFromString<CinemetaMetaDetailResponse>(response.body.string()).meta
        }.getOrNull() ?: return SAnime.create()

        return SAnime.create().apply {
            title = meta.name.orEmpty()
            thumbnail_url = meta.poster.orEmpty()
            description = meta.description.orEmpty()
            genre = meta.genres?.joinToString() ?: meta.genre?.joinToString().orEmpty()
            author = meta.writer?.joinToString() ?: meta.director?.joinToString().orEmpty()
            artist = meta.cast?.take(4)?.joinToString().orEmpty()
            status = when (meta.status?.trim()?.lowercase()) {
                "continuing" -> SAnime.ONGOING
                "ended" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.split(",")
        val id = parts[0]
        val type = parts.getOrNull(1)?.lowercase()?.ifBlank { "movie" } ?: "movie"

        val types = if (type == "movie") listOf("movie", "series") else listOf("series", "movie")

        var meta: CinemetaMetaDetail? = null
        for (t in types) {
            meta = runCatching {
                val res = client.get("$cinemetaUrl/meta/$t/$id.json")
                json.decodeFromString<CinemetaMetaDetailResponse>(res.body.string()).meta
            }.getOrNull()
            if (meta != null) break
        }

        meta ?: return anime

        anime.title = meta.name ?: anime.title
        if (!meta.poster.isNullOrBlank()) anime.thumbnail_url = meta.poster
        if (!meta.description.isNullOrBlank()) anime.description = meta.description
        if (!meta.genres.isNullOrEmpty()) {
            anime.genre = meta.genres.joinToString()
        } else if (!meta.genre.isNullOrEmpty()) {
            anime.genre = meta.genre.joinToString()
        }
        anime.author = meta.writer?.joinToString()
            ?: meta.director?.joinToString()
            ?: anime.author
        anime.artist = meta.cast?.take(4)?.joinToString() ?: anime.artist
        anime.status = when (meta.status?.trim()?.lowercase()) {
            "continuing" -> SAnime.ONGOING
            "ended" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        return anime
    }

    // =============================== Seasons ================================
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException() // / To who ever wants to cook this good luck.

    // ============================== Episodes ================================

    override fun episodeListRequest(anime: SAnime): Request {
        val parts = anime.url.split(",")
        val type = parts.getOrNull(1)?.lowercase() ?: "movie"
        val id = parts[0]
        return Request.Builder().url("$cinemetaUrl/meta/$type/$id.json").build()
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        val meta = runCatching {
            json.decodeFromString<CinemetaMetaDetailResponse>(response.body.string()).meta
        }.getOrNull() ?: return emptyList()

        return when (meta.type) {
            "series" -> {
                val showUpcoming = preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)
                val showSeasonZero = preferences.getBoolean(SHOW_SEASON_ZERO_KEY, SHOW_SEASON_ZERO_DEFAULT)
                val now = System.currentTimeMillis()

                meta.videos.orEmpty()
                    .filter { video ->
                        showSeasonZero || (video.season ?: 0) > 0
                    }
                    .mapNotNull { video ->
                        val releaseTime = (video.firstAired ?: video.released)?.let {
                            runCatching { DATE_FORMATTER.parse(it.trim())?.time }.getOrNull()
                                ?: runCatching { SIMPLE_DATE_FORMATTER.parse(it.trim())?.time }.getOrNull()
                                ?: 0L
                        } ?: 0L

                        val isReleased = releaseTime in 1L..now

                        if (!showUpcoming && !isReleased) {
                            return@mapNotNull null
                        }

                        val season = video.season ?: 1
                        val number = video.number ?: video.episode ?: 0

                        Triple(season, number, video to releaseTime)
                    }
                    .sortedWith(
                        compareByDescending<Triple<Int, Int, Pair<*, Long>>> { it.first }
                            .thenByDescending { it.second },
                    )
                    .map { (_, _, data) ->
                        val (video, releaseTime) = data
                        val isReleased = releaseTime in 1L..now

                        val season = video.season
                        val number = video.number ?: video.episode

                        SEpisode.create().apply {
                            episode_number = number!!.toFloat()
                            url = "/stream/series/${video.id}.json"
                            date_upload = releaseTime

                            name = buildString {
                                append("S$season:E$number")

                                video.name
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        append(" - ")
                                        append(it)
                                    }
                            }

                            summary = video.overview ?: video.description ?: ""
                            preview_url = video.thumbnail

                            scanlator = buildString {
                                if (!isReleased && releaseTime > 0L) {
                                    append("Upcoming")
                                }

                                video.rating
                                    ?.takeIf { it.isNotBlank() && it != "0" }
                                    ?.let {
                                        if (isNotEmpty()) append(" • ")
                                        append("★ $it")
                                    }
                            }
                        }
                    }
            }

            "movie" -> {
                val releaseTime = meta.released?.let {
                    runCatching { DATE_FORMATTER.parse(it.trim())?.time }.getOrNull()
                        ?: runCatching { SIMPLE_DATE_FORMATTER.parse(it.trim())?.time }.getOrNull()
                        ?: 0L
                } ?: 0L

                listOf(
                    SEpisode.create().apply {
                        episode_number = 1f
                        url = "/stream/movie/${meta.id}.json"
                        name = "Complete Movie"
                        date_upload = releaseTime
                    },
                )
            }
            else -> emptyList()
        }
    }

    // ============================== Hosters ==================================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val streamData = runCatching {
            val response = client.get(buildUrl(episode.url))
            json.decodeFromString<StreamDataTorrent>(response.body.string())
        }.getOrNull()

        val streams = streamData?.streams.orEmpty()
        if (streams.isEmpty()) return emptyList()

        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")
        val trackers = if (debridProvider == "none") buildTrackerList() else emptyList()

        return streams
            .groupBy { getProviderName(it.title, it.name) }
            .map { (provider, providerStreams) ->
                val videoList = providerStreams.map { stream ->
                    val urlOrHash = if (debridProvider == "none") {
                        buildString {
                            append("magnet:?xt=urn:btih:${stream.infoHash}")
                            append("&dn=${stream.infoHash}")
                            trackers.forEach { append("&tr=$it") }
                            stream.fileIdx?.let { append("&index=$it") }
                        }
                    } else {
                        stream.url ?: ""
                    }

                    Video(
                        videoUrl = urlOrHash,
                        videoTitle = (stream.name?.removePrefix("Torrentio\n") ?: "") + "\n" + (stream.title ?: ""),
                    )
                }

                Hoster(
                    hosterName = PROVIDER_DISPLAY_NAMES[provider] ?: provider.replaceFirstChar { it.uppercase() },
                    videoList = videoList,
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    private fun buildUrl(streamPath: String): String {
        val configSegments = mutableListOf<String>()

        val addConfigParam: (String, Set<String>?) -> Unit = { key, values ->
            values?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() }?.let {
                configSegments += "$key=${it.joinToString(",")}"
            }
        }

        addConfigParam("providers", preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT))
        addConfigParam("language", preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT))
        addConfigParam("qualityfilter", preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT))

        val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
        addConfigParam("sort", sortKey?.let { setOf(it) })

        val token = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        when {
            token.isNullOrBlank() && debridProvider != "none" -> {
                handler.post {
                    Toast.makeText(
                        applicationContext,
                        "Kindly input the debrid token in the extension settings.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                throw UnsupportedOperationException()
            }

            !token.isNullOrBlank() && debridProvider != "none" -> configSegments += "$debridProvider=$token"
        }

        val configString = configSegments.joinToString("|")

        return buildString {
            append(baseUrl)
            append("/")
            append(configString)
            append(streamPath)
        }
    }

    private fun getProviderName(title: String?, name: String?): String {
        val titleLower = title.orEmpty().lowercase()
        val nameLower = name.orEmpty().lowercase()

        for (provider in PROVIDER_DISPLAY_NAMES.keys) {
            if (titleLower.contains(provider) || nameLower.contains(provider)) return provider
        }

        val titleParts = title.orEmpty().split(Regex("\\[|\\]"))
        for (part in titleParts) {
            val cleanPart = part.trim().lowercase()
            for (provider in PROVIDER_DISPLAY_NAMES.keys) {
                if (cleanPart.contains(provider)) return provider
            }
        }

        return "unknown"
    }

    private suspend fun buildTrackerList(): List<String> = runCatching { fetchTrackers().split("\n") }.getOrDefault(emptyList())

    private suspend fun fetchTrackers(): String {
        val response = client.get("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
        if (!response.isSuccessful) throw Exception("Unexpected code $response")
        return response.body.string().trim()
    }

    // ============================ Sorting / Helpers ==========================

    override fun List<Video>.sortVideos(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return sortedWith(
            compareBy(
                { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                { isDub && !it.videoTitle.contains("dubbed", true) },
                { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.videoTitle.contains(q, true) } },
            ),
        )
    }

    private fun CinemetaMeta.toSAnime(): SAnime = SAnime.create().apply {
        url = "${id.orEmpty()},${type.orEmpty()}"
        title = name.orEmpty()
        thumbnail_url = poster.orEmpty()
    }

    // ============================ Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debrid Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("none")
            summary = "Choose 'None' for Torrent. If you select a Debrid provider, enter your token key."
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN_KEY
            title = "Token"
            setDefaultValue(PREF_TOKEN_DEFAULT)
            summary = PREF_TOKEN_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_TOKEN_DEFAULT }
                    Toast.makeText(screen.context, "Restart App to apply new setting.", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Enable/Disable Providers"
            entries = PREF_PROVIDERS
            entryValues = PREF_PROVIDERS_VALUE
            setDefaultValue(PREF_PROVIDERS_DEFAULT)
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Exclude Qualities/Resolutions"
            entries = PREF_QUALITY
            entryValues = PREF_QUALITY_VALUE
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Priority foreign language"
            entries = PREF_LANG
            entryValues = PREF_LANG_VALUE
            setDefaultValue(PREF_LANG_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Sorting"
            entries = PREF_SORT_ENTRIES
            entryValues = PREF_SORT_VALUES
            setDefaultValue("quality")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = UPCOMING_EP_KEY
            title = "Show Upcoming Episodes"
            setDefaultValue(UPCOMING_EP_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_SEASON_ZERO_KEY
            title = "Show Season 0 Entries"
            setDefaultValue(SHOW_SEASON_ZERO_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_DUB_KEY
            title = "Dubbed Video Priority"
            setDefaultValue(IS_DUB_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_EFFICIENT_KEY
            title = "Efficient Video Priority"
            setDefaultValue(IS_EFFICIENT_DEFAULT)
            summary = "Codec: (HEVC / x265) & AV1. High-quality video with less data usage."
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val DEFAULT_STREAMING_SERVICE = "nfx"
        private const val CINEMETA_PAGE_SIZE = 100
        private const val RATE_LIMIT = 5

        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = ""
        private const val PREF_TOKEN_SUMMARY = "Exclusive to Debrid providers; not intended for Torrents."

        private const val PREF_DEBRID_KEY = "debrid_provider"
        private val PREF_DEBRID_ENTRIES = arrayOf(
            "None",
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
            "EasyDebrid",
            "Offcloud",
            "TorBox",
        )
        private val PREF_DEBRID_VALUES = arrayOf(
            "none",
            "realdebrid",
            "premiumize",
            "alldebrid",
            "debridlink",
            "easydebrid",
            "offcloud",
            "torbox",
        )

        private const val PREF_SORT_KEY = "sorting_link"
        private val PREF_SORT_ENTRIES = arrayOf("By quality then seeders", "By quality then size", "By seeders", "By size")
        private val PREF_SORT_VALUES = arrayOf("quality", "qualitysize", "seeders", "size")

        private const val PREF_PROVIDER_KEY = "provider_selection"
        private val PREF_PROVIDERS = arrayOf(
            "YTS", "EZTV", "RARBG", "1337x", "EXT", "ThePirateBay", "KickassTorrents", "TorrentGalaxy", "MagnetDL",
            "HorribleSubs", "NyaaSi", "TokyoTosho", "AniDex", "nekoBT", "🇷🇺 Rutor", "🇷🇺 Rutracker", "🇵🇹 Comando",
            "🇵🇹 BluDV", "🇵🇹 MicoLeaoDublado", "🇫🇷 Torrent9", "🇮🇹 ilCorSaRoNero", "🇪🇸 MejorTorrent", "🇪🇸 Wolfmax4k", "🇲🇽 Cinecalidad", "🇵🇱 BestTorrents",
        )
        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts", "eztv", "rarbg", "1337x", "ext", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt", "rutor", "rutracker", "comando",
            "bludv", "micoleaodublado", "torrent9", "ilcorsaronero", "mejortorrent", "wolfmax4k", "cinecalidad", "besttorrents",
        )
        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
            "yts", "eztv", "rarbg", "1337x", "ext", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt",
        )
        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

        // Maps a provider's internal preference value to its display label for Hoster naming.
        private val PROVIDER_DISPLAY_NAMES: Map<String, String> = PREF_PROVIDERS_VALUE.zip(PREF_PROVIDERS).toMap()

        private const val PREF_QUALITY_KEY = "quality_selection"
        private val PREF_QUALITY = arrayOf(
            "BluRay REMUX", "HDR/HDR10+/Dolby Vision", "Dolby Vision", "Dolby Vision + HDR", "3D",
            "Non 3D (DO NOT SELECT IF NOT SURE)", "4k", "1080p", "720p", "480p", "Other (DVDRip/HDRip/BDRip...)",
            "Screener", "Cam", "Unknown",
        )
        private val PREF_QUALITY_VALUE = arrayOf(
            "brremux", "hdrall", "dolbyvision", "dolbyvisionwithhdr", "threed", "nonthreed",
            "4k", "1080p", "720p", "480p", "other", "scr", "cam", "unknown",
        )
        private val PREF_DEFAULT_QUALITY_VALUE = arrayOf("720p", "480p", "other", "scr", "cam", "unknown")
        private val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

        private const val PREF_LANG_KEY = "lang_selection"
        private val PREF_LANG = arrayOf(
            "🇯🇵 Japanese", "🇷🇺 Russian", "🇮🇹 Italian", "🇵🇹 Portuguese", "🇪🇸 Spanish", "🇲🇽 Latino",
            "🇰🇷 Korean", "🇨🇳 Chinese", "🇹🇼 Taiwanese", "🇫🇷 French", "🇩🇪 German", "🇳🇱 Dutch",
            "🇮🇳 Hindi", "🇮🇳 Telugu", "🇮🇳 Tamil", "🇵🇱 Polish", "🇱🇹 Lithuanian", "🇱🇻 Latvian",
            "🇪🇪 Estonian", "🇨🇿 Czech", "🇸🇰 Slovakian", "🇸🇮 Slovenian", "🇭🇺 Hungarian", "🇷🇴 Romanian",
            "🇧🇬 Bulgarian", "🇷🇸 Serbian", "🇭🇷 Croatian", "🇺🇦 Ukrainian", "🇬🇷 Greek", "🇩🇰 Danish",
            "🇫🇮 Finnish", "🇸🇪 Swedish", "🇳🇴 Norwegian", "🇹🇷 Turkish", "🇸🇦 Arabic", "🇮🇷 Persian",
            "🇮🇱 Hebrew", "🇻🇳 Vietnamese", "🇮🇩 Indonesian", "🇲🇾 Malay", "🇹🇭 Thai",
        )
        private val PREF_LANG_VALUE = arrayOf(
            "japanese", "russian", "italian", "portuguese", "spanish", "latino", "korean", "chinese",
            "taiwanese", "french", "german", "dutch", "hindi", "telugu", "tamil", "polish", "lithuanian",
            "latvian", "estonian", "czech", "slovakian", "slovenian", "hungarian", "romanian", "bulgarian",
            "serbian", "croatian", "ukrainian", "greek", "danish", "finnish", "swedish", "norwegian",
            "turkish", "arabic", "persian", "hebrew", "vietnamese", "indonesian", "malay", "thai",
        )
        private val PREF_LANG_DEFAULT = setOf<String>()

        private const val UPCOMING_EP_KEY = "upcoming_ep"
        private const val UPCOMING_EP_DEFAULT = false

        private const val SHOW_SEASON_ZERO_KEY = "show_season_zero"
        private const val SHOW_SEASON_ZERO_DEFAULT = false

        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val SIMPLE_DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
