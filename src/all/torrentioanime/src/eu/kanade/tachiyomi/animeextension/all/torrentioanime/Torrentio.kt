package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AioMeta
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AioMetaResponse
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMeta
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMetaLatest
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistRelationsDto
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.BackUpEpisodeResponse
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.BackUpEpisodeVideo
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.applicationContext
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class Torrentio :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val aioMetadataurl = "https://aiometadata.elfhosted.com/stremio/dd765d97-5443-49a9-b733-3f5ac44bbb72"

    // ============================== Anilist API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .add("Referer", "https://anilist.co")
            .build()

        return Request.Builder()
            .url("https://graphql.anilist.co")
            .headers(headers)
            .post(requestBody)
            .build()
    }

    private fun pickTitle(romaji: String?, english: String?, native: String?): String = when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
        "english" -> english?.trim()?.takeIf { it.isNotBlank() } ?: romaji
        "native" -> native?.takeIf { it.isNotBlank() } ?: romaji
        else -> romaji
    }.orEmpty()

    private fun anilistStatus(status: String?): Int = when (status) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "HIATUS" -> SAnime.ON_HIATUS
        "NOT_YET_RELEASED" -> SAnime.LICENSED
        else -> SAnime.UNKNOWN
    }

    private fun parseSearchJson(jsonData: String, isLatestQuery: Boolean = false): AnimesPage {
        val (mediaList, hasNextPage) = if (isLatestQuery) {
            val page = jsonData.parseAs<AnilistMetaLatest>().data?.page
            val list = page?.airingSchedules?.mapNotNull { it.media }
                ?.filter { media -> media.isAdult != true }
                .orEmpty()
            list to (page?.pageInfo?.hasNextPage == true)
        } else {
            val page = jsonData.parseAs<AnilistMeta>().data?.page
            val list = page?.media.orEmpty()
                .filter { media -> media.isAdult != true }
            list to (page?.pageInfo?.hasNextPage == true)
        }

        val animeList = mediaList
            .filter { it.id != null }
            .distinctBy { it.id }
            .map { media ->
                SAnime.create().apply {
                    url = media.id.toString()
                    title = pickTitle(media.title?.romaji, media.title?.english, media.title?.native)
                    thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large
                    status = anilistStatus(media.status)
                }
            }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Popular ================================

    override fun popularAnimeRequest(page: Int): Request {
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", ANILIST_PER_PAGE)
            putJsonArray("sort") { add("POPULARITY_DESC") }
            putJsonArray("status") {
                add("FINISHED")
                add("RELEASING")
            }
        }.toString()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchJson(response.bodyString())

    // ============================== Latest =================================

    override fun latestUpdatesRequest(page: Int): Request {
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", ANILIST_PER_PAGE)
            putJsonArray("sort") { add("TIME_DESC") }
        }.toString()

        return makeGraphQLRequest(anilistLatestQuery(), variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseSearchJson(response.bodyString(), isLatestQuery = true)

    // ============================== Search =================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", ANILIST_PER_PAGE)

            val sort = params.sort.ifBlank { if (query.isNotBlank()) "SEARCH_MATCH" else "POPULARITY_DESC" }
            putJsonArray("sort") { add(sort) }

            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") { params.genres.forEach { add(it) } }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") { params.format.forEach { add(it) } }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                throw Exception("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                params.year.toIntOrNull()?.let { put("seasonYear", it) }
            }

            if (params.status.isNotBlank()) {
                putJsonArray("status") { add(params.status) }
            }
        }.toString()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchJson(response.bodyString())

    // ============================== Filters ================================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val queryUrl = query.toHttpUrlOrNull()
        if (queryUrl != null && queryUrl.host == baseUrl.toHttpUrl().host) {
            val segments = queryUrl.pathSegments
            val id = segments.getOrNull(1)
                ?.takeIf { segments.first() == "anime" && it.isNotEmpty() }
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).trim()
            val meta = fetchAioMeta(id) ?: return AnimesPage(emptyList(), false)
            val anime = SAnime.create().apply {
                url = id
                title = meta.name.orEmpty()
                thumbnail_url = meta.poster ?: meta.providerArt?.poster
            }
            return AnimesPage(listOf(anime), false)
        }

        return super.getSearchAnime(page, query, filters)
    }

    // ============================== AIO metadata ===========================

    private suspend fun fetchAioMeta(anilistId: String): AioMeta? = runCatching {
        val response = client.get("$aioMetadataurl/meta/anime/anilist:$anilistId.json")
        response.parseAs<AioMetaResponse>().meta
    }.getOrNull()

    private fun parseDate(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return synchronized(DATE_FORMATTER) {
            runCatching { DATE_FORMATTER.parse(trimmed)?.time }.getOrNull()
                ?: runCatching { SIMPLE_DATE_FORMATTER.parse(trimmed)?.time }.getOrNull()
        }
    }

    private fun cleanText(text: String?): String? = text
        ?.replace(Regex("\\s*\\(Source: [^)]*\\)\\s*$"), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    // ============================== Anime Details ==========================

    override fun animeDetailsRequest(anime: SAnime): Request = Request.Builder()
        .url("$aioMetadataurl/meta/anime/anilist:${anime.url}.json")
        .build()

    override fun animeDetailsParse(response: Response): SAnime {
        val meta = response.parseAs<AioMetaResponse>().meta

        return SAnime.create().apply {
            if (meta == null) return@apply

            cleanText(meta.description)?.let { description = it }
            meta.genres?.takeIf { it.isNotEmpty() }?.let { genre = it.joinToString() }
            (meta.background ?: meta.providerArt?.background)?.takeIf { it.isNotBlank() }?.let { background_url = it }

            status = when (meta.status?.trim()?.lowercase()) {
                "finished", "ended", "completed" -> SAnime.COMPLETED
                "continuing", "ongoing", "releasing" -> SAnime.ONGOING
                else -> if (meta.stability?.ongoing == true) SAnime.ONGOING else SAnime.UNKNOWN
            }
        }
    }
    // ============================== Related Anime  ==========================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val variables = buildJsonObject {
            put("id", anime.url.toIntOrNull() ?: 0)
        }.toString()

        return makeGraphQLRequest(anilistRelationsQuery(), variables)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val data = runCatching { response.parseAs<AnilistRelationsDto>() }.getOrNull()

        return data?.data?.media?.relations?.edges
            ?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                if (node.type != "ANIME") return@mapNotNull null

                SAnime.create().apply {
                    url = node.id.toString()
                    title = pickTitle(node.title?.romaji, node.title?.english, node.title?.native)
                    thumbnail_url = node.coverImage?.extraLarge ?: node.coverImage?.large
                    status = anilistStatus(node.status)
                }
            }.orEmpty()
    }

    // =============================== Seasons ===============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ===============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url
        val meta = fetchAioMeta(anilistId)

        val currentTime = System.currentTimeMillis()
        val futureTolerance = currentTime + (7L * 24 * 60 * 60 * 1000)

        val kitsuId = meta?.kitsuId?.takeIf { it.isNotBlank() }
            ?: meta?.videos.orEmpty()
                .firstNotNullOfOrNull { v ->
                    v.id?.takeIf { it.startsWith("kitsu:") }?.split(":")?.getOrNull(1)
                }

        if (meta != null && !meta.videos.isNullOrEmpty() && !kitsuId.isNullOrBlank()) {
            val type = meta.type ?: "series"
            val isMovie = type == "movie"

            if (isMovie) {
                val video = meta.videos.firstOrNull()
                val dateUpload = parseDate(video?.released ?: meta.released) ?: 0L

                if (dateUpload <= futureTolerance) {
                    val isFuture = dateUpload > currentTime
                    if (isFuture || video?.available != false) {
                        return listOf(
                            SEpisode.create().apply {
                                episode_number = 1f
                                name = "Movie"
                                url = "/stream/movie/kitsu:$kitsuId.json"
                                date_upload = dateUpload
                                summary = cleanText(video?.overview)
                                preview_url = video?.thumbnail
                            },
                        )
                    }
                }
            } else {
                val episodes = meta.videos.orEmpty()
                    .filter { video ->
                        val dateUpload = parseDate(video.released) ?: return@filter false
                        if (dateUpload > futureTolerance) return@filter false
                        val isFuture = dateUpload > currentTime
                        isFuture || video.available != false
                    }
                    .sortedByDescending { it.episode }
                    .mapNotNull { video ->
                        val epNum = video.episode ?: return@mapNotNull null
                        val dateUpload = parseDate(video.released) ?: return@mapNotNull null

                        SEpisode.create().apply {
                            episode_number = epNum.toFloat()
                            url = "/stream/series/kitsu:$kitsuId:$epNum.json"
                            date_upload = dateUpload

                            name = (video.title ?: video.name)
                                ?.takeIf { it.isNotBlank() && it != "Episode $epNum" }
                                ?.let { "Episode $epNum: $it" }
                                ?: "Episode $epNum"

                            summary = cleanText(video.overview)
                            preview_url = video.thumbnail
                        }
                    }

                if (episodes.isNotEmpty()) return episodes
            }
        }
        val episodeResponse = runCatching {
            val res = client.get("https://kenjitsu.vercel.app/api/anilist/anime/$anilistId/episodes")
            res.parseAs<BackUpEpisodeResponse>()
        }.getOrNull()

        val videos = episodeResponse?.data.orEmpty()
        if (videos.isEmpty()) return emptyList()

        return videos
            .map { video ->
                val raw = video.airDate
                val dateUpload = parseDate(raw) ?: 0L
                video to dateUpload
            }
            .sortedWith(
                compareByDescending<Pair<BackUpEpisodeVideo, Long>> {
                    it.first.episodeNumber ?: 0
                },
            )
            .mapNotNull { (video, dateUpload) ->
                val number = video.episodeNumber ?: 0
                val resolvedType = video.type?.lowercase() ?: "series"
                val isMovie = resolvedType == "movie"

                val streamId = video.imdbId?.takeIf { it.isNotBlank() }
                    ?: video.kitsuId?.let { if (isMovie) "kitsu:$it" else "kitsu:$it:$number" }
                    ?: return@mapNotNull null

                SEpisode.create().apply {
                    episode_number = number.toFloat()
                    url = "/stream/$resolvedType/$streamId.json"
                    date_upload = dateUpload

                    name = buildString {
                        if (resolvedType == "series") {
                            append("Episode $number")
                        } else {
                            append("Complete Movie")
                        }
                        video.title?.takeIf { it.isNotBlank() }?.let {
                            append(" - ")
                            append(it)
                        }
                    }

                    summary = cleanText(video.summary)
                    preview_url = video.thumbnail
                }
            }
    }

// ============================== Hosters ================================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val streamData = runCatching {
            val url = buildUrl(episode.url)
            val response = client.get(url)
            response.parseAs<StreamDataTorrent>()
        }.getOrNull()

        val streams = streamData?.streams.orEmpty()
        if (streams.isEmpty()) return emptyList()

        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")
        val trackers = if (debridProvider == "none") buildTrackerList() else emptyList()

        return streams
            .groupBy { getProviderName(it.title) }
            .mapNotNull { (provider, providerStreams) ->
                val videoList = providerStreams.map { stream ->
                    val urlOrHash = if (debridProvider == "none") {
                        buildString {
                            append("magnet:?xt=urn:btih:${stream.infoHash}")
                            append("&dn=${stream.infoHash}")
                            trackers.forEach { append("&tr=$it") }
                            stream.fileIdx?.let { append("&index=$it") }
                        }
                    } else {
                        stream.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    }

                    Video(
                        videoUrl = urlOrHash,
                        videoTitle = (stream.name?.removePrefix("Torrentio\n") ?: "") + "\n" + (stream.title ?: ""),
                    )
                }

                Hoster(hosterName = provider, videoList = videoList)
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

// ============================ Video Links ==============================

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
        val formattedPath = streamPath.removePrefix("/")

        return buildString {
            append(baseUrl)
            append("/")
            if (configString.isNotEmpty()) {
                append(configString)
                append("/")
            }
            append(formattedPath)
        }
    }

    private fun getProviderName(title: String?): String {
        val raw = PROVIDER_REGEX.find(title.orEmpty())?.groupValues?.get(1)?.trim()
            ?: return "Unknown"
        val key = raw.lowercase().replace(Regex("[^a-z0-9]"), "")
        return PROVIDER_DISPLAY_NAMES[key] ?: raw
    }

    private suspend fun buildTrackerList(): List<String> = runCatching { fetchTrackers().split("\n") }.getOrDefault(emptyList())

    private suspend fun fetchTrackers(): String {
        val response = client.get("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
        if (!response.isSuccessful) throw Exception("Unexpected code $response")
        return response.body.string().trim()
    }

    private val codecPreferences
        get() = preferences.getStringSet(PREF_CODEC_KEY, PREF_CODEC_DEFAULT) ?: setOf()

    private fun Video.detectCodec(): String = when {
        videoTitle.contains("264", true) -> "x264"
        videoTitle.contains("265", true) || videoTitle.contains("hevc", true) -> "x265"
        videoTitle.contains("av1", true) -> "av1"
        videoTitle.contains("vp9", true) -> "vp9"
        else -> "other"
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)
        val codecs = codecPreferences

        return if (codecs.isNotEmpty()) {
            filter { it.detectCodec() in codecs }.sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                ),
            )
        } else {
            sortedWith(
                compareBy(
                    { Regex("\\[(.+?) download]").containsMatchIn(it.videoTitle) },
                    { isDub && !it.videoTitle.contains("dubbed", true) },
                    { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.videoTitle.contains(q, true) } },
                ),
            )
        }
    }

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

        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = "Preferred Title"
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue("romaji")
            summary = "%s"
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
            summary = "Codec: (HEVC / x265) & AV1. High-quality video with less data usage."
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

        private const val ANILIST_PER_PAGE = 30

        private val PROVIDER_REGEX = Regex("\u2699\uFE0F?\\s*([^\\n]+)")

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
            "🇵🇹 BluDV", "🇵🇹 MicoLeaoDublado", "🇫🇷 Torrent9", "🇮🇹 ilCorSaRoNero", "🇪🇸 MejorTorrent", "🇪🇸 Wolfmax4k",
            "🇲🇽 Cinecalidad", "🇵🇱 BestTorrents",
        )
        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts", "eztv", "rarbg", "1337x", "ext", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt", "rutor", "rutracker", "comando",
            "bludv", "micoleaodublado", "torrent9", "ilcorsaronero", "mejortorrent", "wolfmax4k", "cinecalidad",
            "besttorrents",
        )
        private val PREF_PROVIDERS_DEFAULT = setOf(
            "yts", "eztv", "rarbg", "1337x", "ext", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt",
        )

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
        private val PREF_QUALITY_DEFAULT = setOf("720p", "480p", "other", "scr", "cam", "unknown")

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

        private const val PREF_TITLE_KEY = "pref_title"
        private val PREF_TITLE_ENTRIES = arrayOf("Romaji", "English", "Native")
        private val PREF_TITLE_VALUES = arrayOf("romaji", "english", "native")

        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private const val PREF_CODEC_KEY = "codec_selection"
        private val PREF_CODEC = arrayOf("x264", "x265/HEVC", "AV1", "VP9", "Other")
        private val PREF_CODEC_VALUE = arrayOf("x264", "x265", "av1", "vp9", "other")
        private val PREF_CODEC_DEFAULT = setOf<String>()

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val SIMPLE_DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
