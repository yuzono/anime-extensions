package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMeta
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMetaLatest
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.DetailsById
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.KitsuMetaResponse
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Torrentio :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Torrentio Anime (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(RATE_LIMIT_PERMITS)
        .build()

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ============================== Anilist API Request ===================

    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        val headers = Headers.Builder()
            .add("Referer", "https://anilist.co")
            .build()

        return Request.Builder()
            .url("https://graphql.anilist.co")
            .headers(headers)
            .post(requestBody)
            .build()
    }

    private fun parseSearchJson(jsonLine: String?, isLatestQuery: Boolean = false): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val metaData: Any = if (!isLatestQuery) {
            json.decodeFromString<AnilistMeta>(jsonData)
        } else {
            json.decodeFromString<AnilistMetaLatest>(jsonData)
        }

        val mediaList = when (metaData) {
            is AnilistMeta -> metaData.data?.page?.media.orEmpty()
            is AnilistMetaLatest -> metaData.data?.page?.airingSchedules.orEmpty().map { it.media }
            else -> emptyList()
        }

        val hasNextPage: Boolean = when (metaData) {
            is AnilistMeta -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
            is AnilistMetaLatest -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
            else -> false
        }

        val animeList = mediaList.filterNot { (it?.countryOfOrigin == "CN" || it?.isAdult == true) && isLatestQuery }.map { media ->
            val anime = SAnime.create().apply {
                url = media?.id.toString()
                title = when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
                    "romaji" -> media?.title?.romaji.toString()
                    "english" -> (media?.title?.english?.takeIf { it.isNotBlank() } ?: media?.title?.romaji).toString()
                    "native" -> media?.title?.native.toString()
                    else -> ""
                }
                thumbnail_url = media?.coverImage?.extraLarge
                description = media?.description?.replace(Regex("<br><br>"), "\n")?.replace(Regex("<.*?>"), "") ?: "No Description"

                status = when (media?.status) {
                    "RELEASING" -> SAnime.ONGOING
                    "FINISHED" -> SAnime.COMPLETED
                    "HIATUS" -> SAnime.ON_HIATUS
                    "NOT_YET_RELEASED" -> SAnime.LICENSED
                    else -> SAnime.UNKNOWN
                }

                // Extracting tags
                val tagsList = media?.tags?.mapNotNull { it.name }.orEmpty()
                // Extracting genres
                val genresList = media?.genres.orEmpty()
                genre = (tagsList + genresList).toSet().sorted().joinToString()

                // Extracting studios
                val studiosList = media?.studios?.nodes?.mapNotNull { it.name }.orEmpty()
                author = studiosList.sorted().joinToString()

                initialized = true
            }
            anime
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TRENDING_DESC",
                "status": ["FINISHED", "RELEASING"]
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TIME_DESC"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistLatestQuery(), variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData, true)
    }

    // =============================== Search ===============================
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
            val id = query.removePrefix(PREFIX_SEARCH)
            return searchAnimeByIdParse(client.get("$baseUrl/anime/$id"))
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)
        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", 30)
            put("sort", params.sort)
            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") {
                    params.genres.forEach { add(it) }
                }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") {
                    params.format.forEach { add(it) }
                }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                throw Exception("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                put("seasonYear", params.year)
            }

            if (params.status.isNotBlank()) {
                putJsonArray("status") {
                    params.status.forEach { add(it.toString()) }
                }
            }
        }

        val variables = json.encodeToString(variablesObject)

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val variables = """{"id": ${anime.url}}"""

        val metaData = runCatching {
            val request = makeGraphQLRequest(getDetailsQuery(), variables)
            val response = client.post(request.url, request.headers, request.body!!)
            json.decodeFromString<DetailsById>(response.bodyString())
        }.getOrNull()?.data?.media

        anime.title = metaData?.title?.let { title ->
            when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
                "romaji" -> title.romaji
                "english" -> (metaData.title.english?.takeIf { it.isNotBlank() } ?: metaData.title.romaji).toString()
                "native" -> title.native
                else -> ""
            }
        } ?: ""

        anime.thumbnail_url = metaData?.coverImage?.extraLarge

        anime.description = buildString {
            append(
                metaData?.description?.let {
                    Jsoup.parseBodyFragment(
                        it.replace("<br>\n", "br2n").replace("<br>", "br2n").replace("\n", "br2n"),
                    ).text().replace("br2n", "\n")
                },
            )
            append("\n\n")
            if (!(metaData?.season == null && metaData?.seasonYear == null)) {
                append("Release: ${metaData.season ?: ""} ${metaData.seasonYear ?: ""}")
            }
            metaData?.format?.let { append("\nType: ${metaData.format}") }
            metaData?.episodes?.let { append("\nTotal Episode Count: ${metaData.episodes}") }
        }.trim()

        anime.status = when (metaData?.status) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "HIATUS" -> SAnime.ON_HIATUS
            "NOT_YET_RELEASED" -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }

        // Extracting tags, genres, and studios
        val tagsList = metaData?.tags?.mapNotNull { it.name } ?: emptyList()
        val genresList = metaData?.genres ?: emptyList()
        val studiosList = metaData?.studios?.nodes?.mapNotNull { it.name } ?: emptyList()

        anime.genre = (tagsList + genresList).toSet().sorted().joinToString()
        anime.author = studiosList.sorted().joinToString()

        return anime
    }

    // =============================== Seasons ===============================
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val kitsuDeferred = async(Dispatchers.IO) {
            runCatching {
                val response = client.get("https://anime-kitsu.strem.fun/meta/anime/anilist:${anime.url}.json")
                json.decodeFromString<KitsuMetaResponse>(response.bodyString())
            }.getOrNull()
        }

        val aioDeferred = async(Dispatchers.IO) {
            runCatching {
                val response = client.get("https://aiometadata.elfhosted.com/stremio/2c986aae-accd-47b7-9a86-df16f7b932d3/meta/anime/anilist:${anime.url}.json")
                json.decodeFromString<KitsuMetaResponse>(response.bodyString())
            }.getOrNull()
        }

        val kitsuMeta = kitsuDeferred.await()?.meta
        val aioMeta = aioDeferred.await()?.meta

        val kitsuId = kitsuMeta?.kitsuId ?: aioMeta?.kitsuId
        val type = kitsuMeta?.type ?: aioMeta?.type ?: "series"
        val isMovie = type == "movie"

        val now = System.currentTimeMillis()

        fun parseReleased(released: String?): Long? {
            if (released.isNullOrBlank()) return null
            val parsed = runCatching { DATE_FORMATTER.parse(released)?.time }.getOrNull() ?: return null
            return if (parsed > now) null else parsed
        }

        if (isMovie) {
            val kitsuVideo = kitsuMeta?.videos?.firstOrNull()
            val aioVideo = aioMeta?.videos?.firstOrNull()

            if (kitsuVideo == null && aioVideo == null) return@coroutineScope emptyList()

            val dateUpload = parseReleased(aioVideo?.released ?: kitsuVideo?.released) ?: return@coroutineScope emptyList()

            return@coroutineScope listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Movie"

                    // kitsuId|imdbId|ep|season|imdbEp|type
                    url = listOf(
                        kitsuId.orEmpty(),
                        aioVideo?.imdbId ?: kitsuVideo?.imdbId.orEmpty(),
                        "",
                        "",
                        "",
                        type,
                    ).joinToString("|")

                    date_upload = dateUpload
                    summary = aioVideo?.overview ?: kitsuVideo?.overview
                    preview_url = aioVideo?.thumbnail ?: kitsuVideo?.thumbnail
                },
            )
        }

        val kitsuVideos = kitsuMeta?.videos.orEmpty().filter { it.episode != null }
        val aioVideos = aioMeta?.videos.orEmpty().filter { it.episode != null }

        val kitsuByEpisode = kitsuVideos.associateBy { it.episode }


        val aioHasValid = aioVideos.any { parseReleased(it.released) != null }
        val primaryVideos = if (aioHasValid) aioVideos else kitsuVideos

        return@coroutineScope primaryVideos
            .sortedBy { it.episode }
            .mapNotNull { video ->
                val epNum = video.episode ?: return@mapNotNull null


                val dateUpload = parseReleased(video.released) ?: return@mapNotNull null

                val kitsuVideo = if (aioHasValid) kitsuByEpisode[epNum] else video

                SEpisode.create().apply {
                    episode_number = epNum.toFloat()

                    // kitsuId|imdbId|ep|season|imdbEp|type
                    url = listOf(
                        kitsuId.orEmpty(),
                        kitsuVideo?.imdbId.orEmpty(),
                        epNum.toString(),
                        kitsuVideo?.imdbSeason?.toString().orEmpty(),
                        (kitsuVideo?.imdbEpisode ?: epNum).toString(),
                        type,
                    ).joinToString("|")

                    date_upload = dateUpload

                    name = video.title
                        ?.takeIf { it.isNotBlank() && it != "Episode $epNum" }
                        ?.let { "Episode $epNum: $it" }
                        ?: "Episode $epNum"

                    summary = video.overview
                    preview_url = video.thumbnail
                    scanlator = ""
                }
            }
            .reversed()
    }

    // ============================== Hosters ================================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val kitsuId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val imdbId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val type = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "series"
        val isMovie = type == "movie"

        var streamData: StreamDataTorrent? = null

        // 1. Try kitsu first
        if (kitsuId != null) {
            val path = if (isMovie) {
                "/stream/movie/kitsu:$kitsuId.json"
            } else {
                val epNum = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return emptyList()
                "/stream/series/kitsu:$kitsuId:$epNum.json"
            }
            streamData = fetchStreamData(path)
        }

        // 2. Fallback to imdb
        if (streamData?.streams.isNullOrEmpty() && imdbId != null) {
            val path = if (isMovie) {
                "/stream/movie/$imdbId.json"
            } else {
                val epNum = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return emptyList()
                val imdbEp = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: epNum
                val season = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "1"
                "/stream/series/$imdbId:$season:$imdbEp.json"
            }
            streamData = fetchStreamData(path)
        }

        val streams = streamData?.streams.orEmpty()
        if (streams.isEmpty()) return emptyList()

        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")
        val animeTrackers = if (debridProvider == "none") buildAnimeTrackers() else emptyList()

        return streams
            .groupBy { getProviderName(it.title, it.name) }
            .map { (provider, providerStreams) ->
                val videoList = providerStreams.map { stream ->
                    val urlOrHash = if (debridProvider == "none") {
                        buildString {
                            append("magnet:?xt=urn:btih:${stream.infoHash}")
                            append("&dn=${stream.infoHash}")
                            animeTrackers.forEach { append("&tr=$it") }
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

    // ============================ Video Links =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    private suspend fun fetchStreamData(streamPath: String): StreamDataTorrent? = runCatching {
        val res = client.get(buildUrl(streamPath), headers)
        if (!res.isSuccessful) return@runCatching null
        json.decodeFromString<StreamDataTorrent>(res.body.string())
    }.getOrNull()

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

    // ============================ Provider Naming ==========================
    private fun getProviderName(title: String?, name: String?): String {
        val titleLower = title.orEmpty().lowercase()
        val nameLower = name.orEmpty().lowercase()

        for (provider in PROVIDER_DISPLAY_NAMES.keys) {
            if (titleLower.contains(provider) || nameLower.contains(provider)) {
                return provider
            }
        }

        val titleParts = title.orEmpty().split(Regex("\\[|\\]"))
        for (part in titleParts) {
            val cleanPart = part.trim().lowercase()
            for (provider in PROVIDER_DISPLAY_NAMES.keys) {
                if (cleanPart.contains(provider)) {
                    return provider
                }
            }
        }

        return "unknown"
    }

    private suspend fun buildAnimeTrackers(): List<String> = runCatching { fetchTrackers().split("\n") }.getOrDefault(emptyList())

    private val codecPreferences
        get() = preferences.getStringSet(PREF_CODEC_KEY, PREF_CODEC_DEFAULT) ?: setOf()

    override fun List<Video>.sortVideos(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return if (codecPreferences.isNotEmpty()) {
            // Filter to only show videos matching selected codecs
            filter { video ->
                video.detectCodec() in codecPreferences
            }.sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                ),
            )
        } else {
            // If no codec preferences, use old sorting logic
            sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                    { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.videoTitle.contains(q, true) } },
                ),
            )
        }
    }

    private fun Video.detectCodec(): String = when {
        videoTitle.contains("264", true) -> "x264"
        videoTitle.contains("265", true) || videoTitle.contains("hevc", true) -> "x265"
        videoTitle.contains("av1", true) -> "av1"
        videoTitle.contains("vp9", true) -> "vp9"
        else -> "other"
    }

    private suspend fun fetchTrackers(): String {
        val response = client.get("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
        if (!response.isSuccessful) throw Exception("Unexpected code $response")
        return response.body.string().trim()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Debrid provider
        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debrid Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("none")
            summary =
                "Choose 'None' for Torrent. If you select a Debrid provider, enter your token key. No token key is needed if 'None' is selected."
        }.also(screen::addPreference)

        // Token
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

        // Provider
        MultiSelectListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Enable/Disable Providers"
            entries = PREF_PROVIDERS
            entryValues = PREF_PROVIDERS_VALUE
            setDefaultValue(PREF_PROVIDERS_DEFAULT)
        }.also(screen::addPreference)

        // Exclude Qualities
        MultiSelectListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Exclude Qualities/Resolutions"
            entries = PREF_QUALITY
            entryValues = PREF_QUALITY_VALUE
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)

        // Priority foreign language
        MultiSelectListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Priority foreign language"
            entries = PREF_LANG
            entryValues = PREF_LANG_VALUE
            setDefaultValue(PREF_LANG_DEFAULT)
        }.also(screen::addPreference)

        // Sorting
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Sorting"
            entries = PREF_SORT_ENTRIES
            entryValues = PREF_SORT_VALUES
            setDefaultValue("quality")
            summary = "%s"
        }.also(screen::addPreference)

        // Title handler
        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = "Preferred Title"
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue("romaji")
        }.also(screen::addPreference)


        SwitchPreferenceCompat(screen.context).apply {
            key = IS_DUB_KEY
            title = "Dubbed Video Priority"
            setDefaultValue(IS_DUB_DEFAULT)
        }.also(screen::addPreference)

        val efficientPref = SwitchPreferenceCompat(screen.context).apply {
            key = IS_EFFICIENT_KEY
            title = "Efficient Video Priority"
            setDefaultValue(IS_EFFICIENT_DEFAULT)
            setVisible(codecPreferences.isEmpty())
            summary = "Codec: (HEVC / x265)  & AV1. High-quality video with less data usage."
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_CODEC_KEY
            title = "Preferred Codecs"
            entries = PREF_CODEC
            entryValues = PREF_CODEC_VALUE
            setDefaultValue(PREF_CODEC_DEFAULT)
            summary = codecPreferences.joinToString()

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val newSet = newValue as Set<String>
                preferences.edit().putStringSet(key, newSet).apply()
                summary = newSet.joinToString()
                efficientPref.setVisible(newSet.isEmpty())
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val RATE_LIMIT_PERMITS = 5

        // Token
        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = ""
        private const val PREF_TOKEN_SUMMARY = "Exclusive to Debrid providers; not intended for Torrents."

        // Debrid
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

        // Sort
        private const val PREF_SORT_KEY = "sorting_link"
        private val PREF_SORT_ENTRIES = arrayOf(
            "By quality then seeders",
            "By quality then size",
            "By seeders",
            "By size",
        )
        private val PREF_SORT_VALUES = arrayOf(
            "quality",
            "qualitysize",
            "seeders",
            "size",
        )

        // Provider
        private const val PREF_PROVIDER_KEY = "provider_selection"
        private val PREF_PROVIDERS = arrayOf(
            "YTS",
            "EZTV",
            "RARBG",
            "1337x",
            "EXT",
            "ThePirateBay",
            "KickassTorrents",
            "TorrentGalaxy",
            "MagnetDL",
            "HorribleSubs",
            "NyaaSi",
            "TokyoTosho",
            "AniDex",
            "nekoBT",
            "🇷🇺 Rutor",
            "🇷🇺 Rutracker",
            "🇵🇹 Comando",
            "🇵🇹 BluDV",
            "🇵🇹 MicoLeaoDublado",
            "🇫🇷 Torrent9",
            "🇮🇹 ilCorSaRoNero",
            "🇪🇸 MejorTorrent",
            "🇪🇸 Wolfmax4k",
            "🇲🇽 Cinecalidad",
            "🇵🇱 BestTorrents",
        )

        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "ext",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
            "nekobt",
            "rutor",
            "rutracker",
            "comando",
            "bludv",
            "micoleaodublado",
            "torrent9",
            "ilcorsaronero",
            "mejortorrent",
            "wolfmax4k",
            "cinecalidad",
            "besttorrents",
        )

        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "ext",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
            "nekobt",
        )
        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

        // Maps a provider's internal preference value (e.g. "1337x") to its
        // display label (e.g. "1337x", "🇷🇺 Rutor") for Hoster naming.
        private val PROVIDER_DISPLAY_NAMES: Map<String, String> = PREF_PROVIDERS_VALUE.zip(PREF_PROVIDERS).toMap()

        // Qualities/Resolutions
        private const val PREF_QUALITY_KEY = "quality_selection"
        private val PREF_QUALITY = arrayOf(
            "BluRay REMUX",
            "HDR/HDR10+/Dolby Vision",
            "Dolby Vision",
            "Dolby Vision + HDR",
            "3D",
            "Non 3D (DO NOT SELECT IF NOT SURE)",
            "4k",
            "1080p",
            "720p",
            "480p",
            "Other (DVDRip/HDRip/BDRip...)",
            "Screener",
            "Cam",
            "Unknown",
        )

        private val PREF_QUALITY_VALUE = arrayOf(
            "brremux",
            "hdrall",
            "dolbyvision",
            "dolbyvisionwithhdr",
            "threed",
            "nonthreed",
            "4k",
            "1080p",
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_DEFAULT_QUALITY_VALUE = arrayOf(
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

        // Qualities/Resolutions
        private const val PREF_LANG_KEY = "lang_selection"
        private val PREF_LANG = arrayOf(
            "🇯🇵 Japanese",
            "🇷🇺 Russian",
            "🇮🇹 Italian",
            "🇵🇹 Portuguese",
            "🇪🇸 Spanish",
            "🇲🇽 Latino",
            "🇰🇷 Korean",
            "🇨🇳 Chinese",
            "🇹🇼 Taiwanese",
            "🇫🇷 French",

            "🇩🇪 German",
            "🇳🇱 Dutch",
            "🇮🇳 Hindi",
            "🇮🇳 Telugu",
            "🇮🇳 Tamil",
            "🇵🇱 Polish",
            "🇱🇹 Lithuanian",
            "🇱🇻 Latvian",
            "🇪🇪 Estonian",
            "🇨🇿 Czech",

            "🇸🇰 Slovakian",
            "🇸🇮 Slovenian",
            "🇭🇺 Hungarian",
            "🇷🇴 Romanian",
            "🇧🇬 Bulgarian",
            "🇷🇸 Serbian",
            "🇭🇷 Croatian",
            "🇺🇦 Ukrainian",
            "🇬🇷 Greek",
            "🇩🇰 Danish",

            "🇫🇮 Finnish",
            "🇸🇪 Swedish",
            "🇳🇴 Norwegian",
            "🇹🇷 Turkish",
            "🇸🇦 Arabic",
            "🇮🇷 Persian",
            "🇮🇱 Hebrew",
            "🇻🇳 Vietnamese",
            "🇮🇩 Indonesian",
            "🇲🇾 Malay",

            "🇹🇭 Thai",
        )
        private val PREF_LANG_VALUE = arrayOf(
            "japanese",
            "russian",
            "italian",
            "portuguese",
            "spanish",
            "latino",
            "korean",
            "chinese",
            "taiwanese",
            "french",

            "german",
            "dutch",
            "hindi",
            "telugu",
            "tamil",
            "polish",
            "lithuanian",
            "latvian",
            "estonian",
            "czech",

            "slovakian",
            "slovenian",
            "hungarian",
            "romanian",
            "bulgarian",
            "serbian",
            "croatian",
            "ukrainian",
            "greek",
            "danish",

            "finnish",
            "swedish",
            "norwegian",
            "turkish",
            "arabic",
            "persian",
            "hebrew",
            "vietnamese",
            "indonesian",
            "malay",

            "thai",

        )

        private val PREF_LANG_DEFAULT = setOf<String>()

        // Title
        private const val PREF_TITLE_KEY = "pref_title"
        private val PREF_TITLE_ENTRIES = arrayOf(
            "Romaji",
            "English",
            "Native",
        )
        private val PREF_TITLE_VALUES = arrayOf(
            "romaji",
            "english",
            "native",
        )


        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private const val PREF_CODEC_KEY = "codec_selection"
        private val PREF_CODEC = arrayOf(
            "x264",
            "x265/HEVC",
            "AV1",
            "VP9",
            "Other",
        )
        private val PREF_CODEC_VALUE = arrayOf(
            "x264",
            "x265",
            "av1",
            "vp9",
            "other",
        )
        private val PREF_CODEC_DEFAULT = setOf<String>() // Empty by default to show all

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
