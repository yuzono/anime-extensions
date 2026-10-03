package eu.kanade.tachiyomi.animeextension.en.animesaga

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.api.get
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class AnimeSaga :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeSaga"

    override val baseUrl = "https://www.animesaga.net"

    override val lang = "en"

    override val supportsLatest = true

    val preferences: SharedPreferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val localProxy by lazy { LocalProxy(client) }

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }


    // ============================== Required Abstract Stubs ==============================
    // AnimeSaga overrides the suspend functions directly, so these are never called
    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = POPULAR_QUERY,
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = LATEST_QUERY,
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort = listOf("TRENDING_DESC")
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: String? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    selectedSort = listOf(filter.toValue())
                }

                is GenreFilter -> {
                    val genres = filter.getCheckedValues()
                    if (genres.isNotEmpty()) selectedGenres = genres
                }

                is FormatFilter -> {
                    val formats = filter.getCheckedValues()
                    if (formats.isNotEmpty()) selectedFormats = formats
                }

                is StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedStatus = listOf(value)
                }

                is SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedSeason = value
                }

                is YearFilter -> {
                    val value = filter.state
                    if (value.isNotBlank()) selectedYear = value.toIntOrNull()
                }

                else -> {}
            }
        }

        val queryBody = GraphQLRequest(
            query = SEARCH_QUERY,
            variables = GraphQLVariables(
                page = page,
                search = query.takeIf { it.isNotBlank() },
                sort = selectedSort,
                genres = selectedGenres,
                format = selectedFormats,
                status = selectedStatus,
                season = selectedSeason,
                seasonYear = selectedYear,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
        val pageInfo = anilistRes.data.Page
        if (pageInfo == null || pageInfo.media.isEmpty()) {
            return AnimesPage(emptyList(), false)
        }

        val animeList = pageInfo.media.map { media ->
            SAnime.create().apply {
                url = "/anime/${media.id}"
                val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, "english") ?: "english"
                title = when (titleLang) {
                    "romaji" -> media.title.romaji ?: media.title.english ?: media.title.native ?: "Unknown Title"
                    "native" -> media.title.native ?: media.title.english ?: media.title.romaji ?: "Unknown Title"
                    else -> media.title.english ?: media.title.romaji ?: media.title.native ?: "Unknown Title"
                }
                thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large
                description = media.description
                genre = media.genres.joinToString()
            }
        }
        return AnimesPage(animeList, pageInfo.pageInfo?.hasNextPage ?: (animeList.size == 24))
    }

    // ============================== Filters ==============================

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toValue() = vals[state].second
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getCheckedValues(): List<String> = state.mapIndexedNotNull { index, checkbox ->
            if (checkbox.state) vals[index].second else null
        }
    }

    class GenreFilter : CheckBoxFilterList("Genres", GENRES)
    class FormatFilter : CheckBoxFilterList("Formats", FORMATS)
    class StatusFilter : UriPartFilter("Status", STATUSES)
    class SeasonFilter : UriPartFilter("Seasons", SEASONS)
    class SortFilter : UriPartFilter("Sort By", SORT_BY)
    class YearFilter : AnimeFilter.Text("Year")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        FormatFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        AnimeFilter.Separator(),
        SeasonFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
    )

    // ============================== Anime Details ==============================

    private fun fetchAnilistMedia(id: Int): AnilistMedia? {
        try {
            val queryBody = GraphQLRequest(
                query = DETAILS_QUERY,
                variables = GraphQLVariables(id = id),
            )
            val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
            val response = client.newCall(POST("https://graphql.anilist.co", headers, body)).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val responseBody = response.body.string()
            val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
            return anilistRes.data.Media
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val anilistId = anime.url.substringAfter("/anime/").toIntOrNull() ?: return anime
        val media = fetchAnilistMedia(anilistId) ?: return anime

        val studios = media.studios?.nodes?.joinToString { it.name } ?: ""
        val score = media.averageScore?.let { it.toFloat() / 10.0f } ?: 0.0f

        return SAnime.create().apply {
            url = anime.url
            val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, "english") ?: "english"
            title = when (titleLang) {
                "romaji" -> media.title.romaji ?: media.title.english ?: media.title.native ?: anime.title
                "native" -> media.title.native ?: media.title.english ?: media.title.romaji ?: anime.title
                else -> media.title.english ?: media.title.romaji ?: media.title.native ?: anime.title
            }
            thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large ?: anime.thumbnail_url
            genre = media.genres.joinToString()
            author = studios.takeIf { it.isNotBlank() }
            status = when (media.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "NOT_YET_RELEASED" -> SAnime.LICENSED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (score > 0.0f) {
                    val full = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.1f".format(score)}\n\n")
                }
                media.description?.let { append(it) }
                if (studios.isNotEmpty()) {
                    append("\n\nStudio: $studios")
                }
                media.episodes?.let {
                    append("\nTotal Episodes: $it")
                }
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringAfter("/anime/").toIntOrNull() ?: return emptyList()

        val media = fetchAnilistMedia(anilistId) ?: return emptyList()
        val titleVal = media.title.english ?: media.title.romaji ?: media.title.native ?: ""
        val romajiVal = media.title.romaji ?: media.title.english ?: media.title.native ?: ""
        val totalEps = media.episodes ?: 12
        val malId = media.idMal

        val provider = preferences.getString(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT) ?: PREF_PROVIDER_DEFAULT

        val epUrl = "$baseUrl/api/episodes/$anilistId" +
            "?title=${Uri.encode(titleVal)}" +
            "&romaji=${Uri.encode(romajiVal)}" +
            "&totalEpisodes=$totalEps" +
            "&provider=$provider"

        val response = client.newCall(GET(epUrl, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val responseBody = response.body.string()
        val decryptedBody = parseResponseBody(responseBody)

        val episodesRes = json.decodeFromString<EpisodesResponse>(decryptedBody)
        if (!episodesRes.success) return emptyList()

        val actualProvider = episodesRes.provider ?: provider

        return episodesRes.episodes.map { item ->
            SEpisode.create().apply {
                val payload = EpisodePayload(
                    id = item.id,
                    number = item.number,
                    provider = actualProvider,
                    title = titleVal,
                    romaji = romajiVal,
                    anilistId = anilistId,
                    malId = malId,
                )
                url = json.encodeToString(payload)
                name = item.title?.takeIf { it.isNotBlank() }?.let { "Episode ${item.number} - $it" } ?: "Episode ${item.number}"
                episode_number = item.number.toFloat()



                val dateStr = item.airDate
                if (!dateStr.isNullOrBlank()) {
                    date_upload = runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
                    }.getOrDefault(0L)
                }
            }
        }.sortedByDescending { it.episode_number }
    }


    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val payload = json.decodeFromString<EpisodePayload>(episode.url)

        var streamUrl = "$baseUrl/api/stream?provider=${payload.provider}" +
            "&episodeNumber=${payload.number}" +
            "&animeTitle=${Uri.encode(payload.title)}" +
            "&type=sub" +
            "&animeId=${payload.anilistId}"

        if (payload.romaji.isNotEmpty()) {
            streamUrl += "&romajiTitle=${Uri.encode(payload.romaji)}"
        }
        if (payload.malId != null) {
            streamUrl += "&malId=${payload.malId}"
        }

        if (payload.provider == "gogoanime" || payload.provider == "gogoanime_cv") {
            streamUrl += "&episodeUrl=${Uri.encode(payload.id)}"
        } else if (payload.provider == "hianime" || payload.provider == "allwish") {
            streamUrl += "&dataIds=${Uri.encode(payload.id)}"
        }

        val response = client.newCall(GET(streamUrl, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val responseBody = response.body.string()
        val decryptedBody = parseResponseBody(responseBody)

        val streamRes = json.decodeFromString<StreamResponse>(decryptedBody)
        if (!streamRes.success) return emptyList()

        val sMap = streamRes.servers ?: return emptyList()
        val allServerNames = (
            sMap.sub.mapNotNull { it.name ?: it.label } +
                sMap.dub.mapNotNull { it.name ?: it.label } +
                sMap.raw.mapNotNull { it.name ?: it.label }
            ).distinct()

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val filteredServerNames = allServerNames.filter { it !in excludedServers }

        val videoList = mutableListOf<Video>()

        filteredServerNames.forEach { serverName ->
            val subItem = sMap.sub.firstOrNull { (it.name ?: it.label) == serverName }
            val dubItem = sMap.dub.firstOrNull { (it.name ?: it.label) == serverName }
            val rawItem = sMap.raw.firstOrNull { (it.name ?: it.label) == serverName }

            val subIdVal = subItem?.let { it.url ?: it.linkId } ?: ""
            val dubIdVal = dubItem?.let { it.url ?: it.linkId } ?: ""
            val rawIdVal = rawItem?.let { it.url ?: it.linkId } ?: ""

            if (subIdVal.isNotEmpty()) {
                runCatching {
                    extractVideos(
                        provider = payload.provider,
                        urlOrLinkId = subIdVal,
                        serverName = serverName,
                        audioType = "SUB",
                        title = payload.title,
                        romaji = payload.romaji,
                        anilistId = payload.anilistId.toString(),
                        malId = payload.malId?.toString() ?: "",
                    ).forEach { v -> videoList.add(v) }
                }
            }

            if (dubIdVal.isNotEmpty()) {
                runCatching {
                    extractVideos(
                        provider = payload.provider,
                        urlOrLinkId = dubIdVal,
                        serverName = serverName,
                        audioType = "DUB",
                        title = payload.title,
                        romaji = payload.romaji,
                        anilistId = payload.anilistId.toString(),
                        malId = payload.malId?.toString() ?: "",
                    ).forEach { v -> videoList.add(v) }
                }
            }

            if (rawIdVal.isNotEmpty()) {
                runCatching {
                    extractVideos(
                        provider = payload.provider,
                        urlOrLinkId = rawIdVal,
                        serverName = serverName,
                        audioType = "RAW",
                        title = payload.title,
                        romaji = payload.romaji,
                        anilistId = payload.anilistId.toString(),
                        malId = payload.malId?.toString() ?: "",
                    ).forEach { v -> videoList.add(v) }
                }
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, "auto") ?: "auto"
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, "SUB") ?: "SUB"
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, "1080") ?: "1080"
        return videoList.sortedWith(
            compareByDescending<Video> { prefServer != "auto" && it.quality.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.quality.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.quality.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.quality.contains("HD-1", ignoreCase = true) },
        )
    }

    private suspend fun extractVideos(
        provider: String,
        urlOrLinkId: String,
        serverName: String,
        audioType: String,
        title: String,
        romaji: String,
        anilistId: String,
        malId: String,
    ): List<Video> {
        val videoList = mutableListOf<Video>()

        if (provider == "gogoanime" || provider == "anikoto") {
            val embedUrl = urlOrLinkId
            if (embedUrl.isBlank()) return emptyList()

            val subtitleTracks = mutableListOf<Track>()
            runCatching {
                val uri = Uri.parse(embedUrl)
                val subUrl = uri.getQueryParameter("sub")
                    ?: uri.getQueryParameter("caption_1")
                    ?: uri.getQueryParameter("c1_file")
                if (!subUrl.isNullOrBlank()) {
                    val subLabel = uri.getQueryParameter("sub_1")
                        ?: uri.getQueryParameter("c1_label")
                        ?: "English"
                    subtitleTracks.add(Track(subUrl, subLabel))
                }
            }

            when {
                embedUrl.contains("vivibebe.site") || embedUrl.contains("vibevibe.workers.dev") || embedUrl.contains("bibiemb.xyz") -> {
                    val response = client.newCall(GET(embedUrl, headers)).execute()
                    if (response.isSuccessful) {
                        val iframeHtml = response.body.string()
                        val m3u8Url = vibeRegex.find(iframeHtml)?.groupValues?.get(1)
                        if (m3u8Url != null) {
                            val finalM3u8 = if (embedUrl.contains("bibiemb.xyz")) {
                                m3u8Url
                            } else {
                                localProxy.getProxyUrl(m3u8Url, headers)
                            }
                            playlistUtils.extractFromHls(
                                finalM3u8,
                                referer = embedUrl,
                                videoNameGen = { quality -> "$audioType - $quality" },
                                subtitleList = subtitleTracks,
                            ).forEach { v ->
                                videoList.add(v)
                            }
                        }
                    }
                }

                embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online") -> {
                    val extractor = VidHideExtractor(client, headers)
                    extractor.videosFromUrl(embedUrl) { quality -> "$audioType - $quality" }.forEach { v ->
                        videoList.add(
                            Video(
                                url = v.url,
                                quality = v.quality,
                                videoUrl = v.videoUrl,
                                headers = v.headers,
                                subtitleTracks = v.subtitleTracks + subtitleTracks,
                            ),
                        )
                    }
                }

                embedUrl.contains("playmogo.com") || embedUrl.contains("dood") -> {
                    val extractor = DoodExtractor(client)
                    extractor.videosFromUrl(embedUrl, quality = audioType).forEach { v ->
                        videoList.add(
                            Video(
                                url = v.url,
                                quality = v.quality,
                                videoUrl = v.videoUrl,
                                headers = v.headers,
                                subtitleTracks = v.subtitleTracks + subtitleTracks,
                            ),
                        )
                    }
                }
            }
        } else if (provider == "hianime" || provider == "allwish" || provider == "gogoanime_cv") {
            val linkId = urlOrLinkId
            val streamUrl = "$baseUrl/api/stream?provider=$provider&linkId=${Uri.encode(linkId)}"

            val response = client.newCall(GET(streamUrl, headers)).execute()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val decryptedBody = parseResponseBody(responseBody)
                val streamRes = json.decodeFromString<StreamResponse>(decryptedBody)
                if (streamRes.success) {
                    val embedUrl = streamRes.embedUrl ?: ""
                    val directUrl = if (embedUrl.startsWith("/api/stream/proxy?url=")) {
                        Uri.decode(embedUrl.substringAfter("url="))
                    } else {
                        embedUrl
                    }

                    if (directUrl.isNotEmpty()) {
                        val tracks = streamRes.tracks.map {
                            Track(it.file, it.label)
                        }

                        val refHeaders = Headers.Builder().set("Referer", "https://megaplay.buzz/").build()
                        val proxiedM3u8 = localProxy.getProxyUrl(directUrl, refHeaders)

                        playlistUtils.extractFromHls(
                            proxiedM3u8,
                            referer = "https://megaplay.buzz/",
                            videoNameGen = { quality -> "$audioType - $quality" },
                            subtitleList = tracks,
                        ).forEach { v ->
                            videoList.add(v)
                        }
                    }
                }
            }
        }

        return videoList
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val providerPref = ListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Preferred Provider"
            entries = arrayOf("GoGoAnime", "HiAnime", "AllWish")
            entryValues = arrayOf("gogoanime", "hianime", "allwish")
            setDefaultValue(PREF_PROVIDER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(PREF_PROVIDER_KEY, selected).commit()
            }
        }
        screen.addPreference(providerPref)

        val titleLangPref = ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = arrayOf("English", "Romaji", "Native")
            entryValues = arrayOf("english", "romaji", "native")
            setDefaultValue("english")
            summary = "%s"
        }
        screen.addPreference(titleLangPref)

        val audioPref = ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred Audio/Type"
            entries = arrayOf("SUB", "DUB")
            entryValues = arrayOf("SUB", "DUB")
            setDefaultValue("SUB")
            summary = "%s"
        }
        screen.addPreference(audioPref)

        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
        }
        screen.addPreference(qualityPref)

        val excludeServersPref = MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Select servers to exclude from the video list"
            entries = arrayOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream")
            entryValues = arrayOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream")
            setDefaultValue(emptySet<String>())
        }
        screen.addPreference(excludeServersPref)

        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            summary = "Which video server to try first. Currently: %s"
            entries = arrayOf("Auto", "HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream")
            entryValues = arrayOf("auto", "HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream")
            setDefaultValue("auto")
        }
        screen.addPreference(serverPref)
    }

    // ============================ Utilities =============================

    private fun parseResponseBody(responseBody: String): String {
        val cipherRes = runCatching { json.decodeFromString<CipherResponse>(responseBody) }.getOrNull()
        return cipherRes?.ciphertext?.let { decrypt(it) } ?: responseBody
    }

    private fun decrypt(ciphertext: String, key: String = "as-secure-stream-key"): String {
        val decoded = Base64.decode(ciphertext, Base64.DEFAULT)
        val decrypted = ByteArray(decoded.size)
        for (i in decoded.indices) {
            val keyChar = key[i % key.length]
            decrypted[i] = (decoded[i].toInt() xor keyChar.code).toByte()
        }
        return String(decrypted, Charsets.UTF_8)
    }

    companion object {
        private val vibeRegex = Regex("""const src\s*=\s*"([^"]+)"""")

        private const val PREF_PROVIDER_KEY = "pref_provider"
        private const val PREF_PROVIDER_DEFAULT = "gogoanime"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_EXCLUDE_SERVERS_KEY = "exclude_servers"
        private const val PREF_SERVER_KEY = "preferred_server"

        private val POPULAR_QUERY = """
            query(${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(sort: [TRENDING_DESC], type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val LATEST_QUERY = """
            query(${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(sort: [START_DATE_DESC], type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val SEARCH_QUERY = """
            query(${"$"}page: Int, ${"$"}search: String, ${"$"}sort: [MediaSort], ${"$"}genres: [String], ${"$"}format: [MediaFormat], ${"$"}status: [MediaStatus], ${"$"}season: MediaSeason, ${"$"}seasonYear: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(search: ${"$"}search, sort: ${"$"}sort, genre_in: ${"$"}genres, format_in: ${"$"}format, status_in: ${"$"}status, season: ${"$"}season, seasonYear: ${"$"}seasonYear, type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val DETAILS_QUERY = """
            query(${"$"}id: Int) {
              Media(id: ${"$"}id, type: ANIME) {
                id
                idMal
                title { english romaji native }
                coverImage { large extraLarge }
                bannerImage
                description(asHtml: false)
                status
                genres
                averageScore
                episodes
                format
                source
                studios(isMain: true) {
                  nodes {
                    name
                  }
                }
              }
            }
        """.trimIndent()

        private val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )

        private val FORMATS = arrayOf(
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        private val STATUSES = arrayOf(
            Pair("Any", ""),
            Pair("Finished", "FINISHED"),
            Pair("Airing", "RELEASING"),
            Pair("Upcoming", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
            Pair("Hiatus", "HIATUS"),
        )

        private val SEASONS = arrayOf(
            Pair("Any", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        private val SORT_BY = arrayOf(
            Pair("Trending", "TRENDING_DESC"),
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Score", "SCORE_DESC"),
            Pair("Search Match", "SEARCH_MATCH"),
            Pair("Start Date", "START_DATE_DESC"),
        )
    }
}

class LocalProxy(private val client: OkHttpClient) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    init {
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    fun shutdown() {
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    fun getProxyUrl(targetUrl: String, headers: Headers?): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray()
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(modifiedContentBytes)
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            // Peek at the first 8 bytes to detect PNG-wrapped TS without loading the full body
            val source = response.body.source()
            source.request(8L)
            val isPng = source.buffer.size >= 8 &&
                source.buffer[0] == (-119).toByte() &&
                source.buffer[1] == 80.toByte() &&
                source.buffer[2] == 78.toByte() &&
                source.buffer[3] == 71.toByte()
            if (isPng) {
                // PNG-wrapped TS: load fully to scan for IEND marker and TS sync bytes
                val rawBytes = source.readByteArray()
                val stripped = stripPngHeader(rawBytes)
                out.write(stripped)
            } else {
                // Plain TS segment: stream in 8 KB chunks to avoid loading large segments into memory
                val buf = ByteArray(8192)
                val inputStream = source.inputStream()
                var bytesRead: Int
                while (inputStream.read(buf).also { bytesRead = it } != -1) {
                    out.write(buf, 0, bytesRead)
                }
            }
        }
        out.flush()
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}

@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: GraphQLVariables? = null,
)

@Serializable
data class GraphQLVariables(
    val page: Int? = null,
    val search: String? = null,
    val sort: List<String>? = null,
    val genres: List<String>? = null,
    val format: List<String>? = null,
    val status: List<String>? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val id: Int? = null,
)

@Serializable
data class AnilistGraphQLResponse(
    val data: AnilistData,
)

@Serializable
data class AnilistData(
    val Page: AnilistPage? = null,
    val Media: AnilistMedia? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: AnilistPageInfo? = null,
    val media: List<AnilistMedia> = emptyList(),
)

@Serializable
data class AnilistPageInfo(
    val hasNextPage: Boolean,
)

@Serializable
data class AnilistMedia(
    val id: Int,
    val idMal: Int? = null,
    val title: AnilistTitle,
    val coverImage: AnilistCoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val source: String? = null,
    val studios: AnilistStudios? = null,
)

@Serializable
data class AnilistTitle(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistStudioNode> = emptyList(),
)

@Serializable
data class AnilistStudioNode(
    val name: String,
)

@Serializable
data class CipherResponse(
    val success: Boolean = false,
    val ciphertext: String? = null,
)

@Serializable
data class EpisodesResponse(
    val success: Boolean = false,
    val provider: String? = null,
    val animeId: kotlinx.serialization.json.JsonElement? = null,
    val episodes: List<EpisodeItem> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val id: String,
    val number: Int,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val img: String? = null,
    val airDate: String? = null,
)

@Serializable
data class StreamResponse(
    val success: Boolean = false,
    val provider: String? = null,
    val embedUrl: String? = null,
    val isM3U8: Boolean = false,
    val tracks: List<TrackItem> = emptyList(),
    val servers: ServerMap? = null,
)

@Serializable
data class TrackItem(
    val file: String,
    val label: String,
    val kind: String? = null,
    val default: Boolean = false,
)

@Serializable
data class ServerMap(
    val sub: List<ServerItem> = emptyList(),
    val dub: List<ServerItem> = emptyList(),
    val raw: List<ServerItem> = emptyList(),
)

@Serializable
data class ServerItem(
    val name: String? = null,
    val label: String? = null,
    val url: String? = null,
    val linkId: String? = null,
)

@Serializable
data class EpisodePayload(
    val id: String,
    val number: Int,
    val provider: String,
    val title: String,
    val romaji: String,
    val anilistId: Int,
    val malId: Int?,
)

