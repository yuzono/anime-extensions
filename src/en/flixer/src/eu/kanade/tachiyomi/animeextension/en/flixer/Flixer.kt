package eu.kanade.tachiyomi.animeextension.en.flixer

import android.content.SharedPreferences
import android.text.InputType
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class Flixer :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Flixer"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val json: Json by injectLazy()

    // ============================== Domains & API ===============================

    override val baseUrl get() = preferences.domainPref

    private val apiUrl: String
        get() = "https://plsdontscrapemelove.${baseUrl.toHttpUrl().host}/api/tmdb"

    private val decryptionApiUrl = "https://enc-dec.app/api/dec-hexa"
    private val subtitleUrl = "https://sub.wyzie.io"

    private val m3u8Client by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val m3u8ServerManager by lazy { M3u8ServerManager(m3u8Client) }

    private val playlistUtils by lazy { PlaylistUtils(m3u8Client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val preferredLatest = preferences.latestPref
        val types = if (preferredLatest == "movie") listOf("movie", "tv") else listOf("tv", "movie")
        return types.parallelCatchingMapNotNull { mediaType ->
            client.newCall(latestUpdatesRequest(page, mediaType))
                .awaitSuccess()
                .use { latestUpdatesParse(it) }
        }.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val date = DATE_FORMATTER.format(Date())
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter("primary_release_date.lte", date)
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used.")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val preferredLatest = preferences.latestPref
            val types = if (preferredLatest == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            return types.parallelCatchingMapNotNull { mediaType ->
                client.newCall(searchAnimeRequest(page, query, mediaType))
                    .awaitSuccess()
                    .use { searchAnimeParse(it) }
            }.let { animePages ->
                val animes = animePages.flatMap { it.animes }
                val hasNextPage = animePages.any { it.hasNextPage }
                AnimesPage(animes, hasNextPage)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeRequest(page: Int, query: String, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.filterIsInstance<FlixerFilters.TypeFilter>().firstOrNull()?.state?.let {
            if (it == 0) "movie" else "tv"
        } ?: "movie"

        val sortFilter = filters.filterIsInstance<FlixerFilters.SortFilter>().firstOrNull()
        val sortBy = sortFilter?.state?.run {
            when (index) {
                0 -> "popularity"
                1 -> "vote_average"
                else -> if (type == "movie") "primary_release_date" else "first_air_date"
            } + if (ascending) ".asc" else ".desc"
        } ?: "popularity.desc"

        val genreMap = if (type == "movie") FlixerFilters.MOVIE_GENRE_MAP else FlixerFilters.TV_GENRE_MAP
        val genres = filters.filterIsInstance<FlixerFilters.GenreFilter>().firstOrNull()
            ?.state?.filter { it.state }?.mapNotNull { genreMap[it.name] }?.joinToString(",").orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(type)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genres.isNotBlank()) {
                addQueryParameter("with_genres", genres)
            }

            val providers = filters.filterIsInstance<FlixerFilters.WatchProviderFilter>()
                .firstOrNull()
                ?.state
                ?.filter { it.state }
                ?.joinToString(",") { it.id }
                .orEmpty()

            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US")
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = FlixerFilters.getFilterList()

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    // Site Detail URL Format:
    // Movie: /?movie={slug}&id={tmdb_id}
    // TV: /?tv={slug}&id={tmdb_id}

    private fun extractUrlData(anime: SAnime): Pair<String, String> {
        val parsedUri = (baseUrl + anime.url).toHttpUrl()
        val isMovie = parsedUri.queryParameterNames.contains("movie")
        val type = if (isMovie) "movie" else "tv"
        val tmdbId = parsedUri.queryParameter("id") ?: throw Exception("Invalid Media ID in URL")
        return type to tmdbId
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = extractUrlData(anime)
        val url = "$apiUrl/$type/$id".toHttpUrl().newBuilder().apply {
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val isMovie = "/movie/" in response.request.url.toString()
        val responseBody = response.body.string()

        return try {
            if (isMovie) movieDetailsParse(responseBody) else tvDetailsParse(responseBody)
        } catch (e: Exception) {
            throw Exception("Failed to parse details. The API might have returned an error.", e)
        }
    }

    private fun movieDetailsParse(responseBody: String): SAnime {
        val movie = responseBody.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/?movie=${movie.title.toSlug()}&id=${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            genre = movie.genres.joinToString { it.name }
            status = statusParser(movie.status)
            initialized = true

            description = buildString {
                movie.overview?.run(::append)

                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tag Line**: *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
                    movie.originalTitle?.takeIf { it.isNotBlank() && it.trim() != movie.title.trim() }?.let { "**Original Title:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "$hours hr " else ""}$minutes min"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
                )

                if (details.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(details.joinToString("\n"))
                }

                movie.backdropPath?.takeIf(String::isNotBlank)?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1920_and_h800_multi_faces$it)")
                }
            }
        }
    }

    private fun tvDetailsParse(responseBody: String): SAnime {
        val tv = responseBody.parseAs<TvDetailDto>()
        return SAnime.create().apply {
            title = tv.name
            url = "/?tv=${tv.name.toSlug()}&id=${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            genre = tv.genres.joinToString { it.name }
            status = statusParser(tv.status)
            initialized = true

            description = buildString {
                tv.overview?.run(::append)

                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tag Line**: *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf { it.isNotBlank() && it.trim() != tv.name.trim() }?.let { "**Original Title:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
                )

                if (details.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(details.joinToString("\n"))
                }

                tv.backdropPath?.takeIf(String::isNotBlank)?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1920_and_h800_multi_faces$it)")
                }
            }
        }
    }

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = extractUrlData(anime)
        val url = "$apiUrl/$type/$id/recommendations".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, _) = extractUrlData(anime)
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess().body.string()

        return if (type == "tv") {
            val tv = response.parseAs<TvDetailDto>()
            tv.seasons.filter { it.seasonNumber > 0 }
                .sortedBy { it.seasonNumber }
                .parallelCatchingFlatMap { season ->
                    val seasonDetail = client.newCall(GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}", headers))
                        .awaitSuccess().use { it.body.string().parseAs<TvSeasonDetailDto>() }

                    seasonDetail.episodes.sortedBy { it.episodeNumber }.map { episode ->
                        val extraData = EpisodeData(tv.id.toString(), "tv", season.seasonNumber.toString(), episode.episodeNumber.toString())
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                            date_upload = parseDate(episode.airDate)
                            url = "${anime.url}#${json.encodeToString(extraData)}"
                        }
                    }
                }.mapIndexed { index, sEpisode ->
                    sEpisode.apply { episode_number = (index + 1).toFloat() }
                }.reversed()
        } else {
            val movie = response.parseAs<MovieDetailDto>()
            val extraData = EpisodeData(movie.id.toString(), "movie")
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "${anime.url}#${json.encodeToString(extraData)}"
                },
            )
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val extraDataEncoded = episode.url.substringAfterLast("#")
        val epData = json.decodeFromString<EpisodeData>(extraDataEncoded)

        // 1. Generate a unique 32-byte hex key required for the decryption handshake
        val apiKey = ByteArray(32).apply { SECURE_RANDOM.nextBytes(this) }.toHex()

        val serverRequestUrl = when (epData.type) {
            "movie" -> "$apiUrl/movie/${epData.tmdbId}/images"
            "tv" -> "$apiUrl/tv/${epData.tmdbId}/season/${epData.season}/episode/${epData.episode}/images"
            else -> throw Exception("Invalid media type.")
        }

        // 2. Fetch the encrypted server list (Notice the "bw90agfmywth" header specifically for servers)
        val serverHeaders = headers.newBuilder()
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .add("X-Api-Key", apiKey)
            .add("X-Fingerprint-Lite", FINGERPRINT_LITE)
            .add("bw90agfmywth", "1")
            .add("Accept", "text/plain")
            .build()

        val serverEncryptedText = client.newCall(GET(serverRequestUrl, serverHeaders))
            .awaitSuccess().use { it.body.string().trim('"', ' ', '\n', '\r') }

        if (serverEncryptedText.length < 50) {
            throw Exception("Failed to fetch server data. Payload may be blocked or invalid.")
        }

        // 3. Decrypt the server list via external API
        val serverDecryptionPayload = json.encodeToString(mapOf("text" to serverEncryptedText, "key" to apiKey))
        val serverDecRequestBody = serverDecryptionPayload.toRequestBody("application/json".toMediaType())

        val serverDecResponse = client.newCall(POST(decryptionApiUrl, headers = headers, body = serverDecRequestBody))
            .awaitSuccess().use { it.parseAs<DecryptionResponseDto>() }

        val serverResultElement = serverDecResponse.result as? JsonObject
            ?: throw Exception("Failed to decrypt the server payload.")

        val serverData = json.decodeFromJsonElement<ExtractorResultDto>(serverResultElement)
        val availableServers = serverData.sources ?: emptyList()

        if (availableServers.isEmpty()) {
            throw Exception("No servers found.")
        }

        val subtitles = getSubtitles(epData)

        // 4. Concurrently fetch and decrypt video links for each available server
        val videos = availableServers.parallelCatchingFlatMap { source ->
            // The video stream request replaces "bw90agfmywth" with the "x-server" header
            val videoHeaders = headers.newBuilder()
                .add("Origin", baseUrl)
                .add("Referer", "$baseUrl/")
                .add("X-Api-Key", apiKey)
                .add("X-Fingerprint-Lite", FINGERPRINT_LITE)
                .add("X-Server", source.server)
                .add("Accept", "text/plain")
                .build()

            val videoEncryptedText = client.newCall(GET(serverRequestUrl, videoHeaders))
                .awaitSuccess().use { it.body.string().trim('"', ' ', '\n', '\r') }

            if (videoEncryptedText.length < 50) {
                return@parallelCatchingFlatMap emptyList()
            }

            val videoDecryptionPayload = json.encodeToString(mapOf("text" to videoEncryptedText, "key" to apiKey))
            val videoDecRequestBody = videoDecryptionPayload.toRequestBody("application/json".toMediaType())

            val videoDecResponse = client.newCall(POST(decryptionApiUrl, headers = headers, body = videoDecRequestBody))
                .awaitSuccess().use { it.parseAs<DecryptionResponseDto>() }

            val videoResultElement = videoDecResponse.result as? JsonObject
                ?: return@parallelCatchingFlatMap emptyList<Video>()

            val extractorData = json.decodeFromJsonElement<ExtractorResultDto>(videoResultElement)

            extractorData.sources?.parallelCatchingFlatMap { videoSource ->
                playlistUtils.extractFromHls(
                    playlistUrl = videoSource.url,
                    videoNameGen = { quality -> "Server: ${videoSource.server} - $quality" },
                    subtitleList = subtitles,
                    referer = "$baseUrl/",
                ).mapNotNull { video ->
                    // 5. Route the M3U8 through our local proxy to automatically strip fake image headers
                    val processedUrl = getProcessedM3u8Url(video.url) ?: return@mapNotNull null
                    Video(
                        url = processedUrl,
                        quality = video.quality,
                        videoUrl = processedUrl,
                        headers = video.headers,
                        subtitleTracks = subtitles,
                    )
                }
            } ?: emptyList()
        }

        if (videos.isEmpty()) {
            throw Exception("No videos found after extraction. Check extractor API response.")
        }

        val preferredQuality = preferences.videoQualityPref
        return videos.sortedByDescending { it.quality.contains(preferredQuality) }
    }

    private suspend fun getProcessedM3u8Url(originalUrl: String): String? {
        // Starts local proxy server to clean corrupted MPEG-TS segments with 3 retry attempts
        repeat(3) { attempt ->
            try {
                if (!m3u8ServerManager.isRunning()) {
                    m3u8ServerManager.startServer()
                    delay(200L)
                }

                val processedUrl = m3u8ServerManager.processM3u8Url(originalUrl)
                if (processedUrl != null) return processedUrl

                Log.w("Flixer", "M3U8 server process returned null on attempt ${attempt + 1}, restarting...")
                m3u8ServerManager.stopServer()
                delay(500L)
            } catch (e: Exception) {
                Log.e("Flixer", "M3U8 server start failed on attempt ${attempt + 1}: ${e.message}")
                m3u8ServerManager.stopServer()
                delay(500L)
            }
        }

        Log.e("Flixer", "M3U8 server failed to process URL after 3 attempts. Dropping video to prevent cubes.")
        return null
    }

    private suspend fun getSubtitles(data: EpisodeData): List<Track> {
        val subtitleRequestUrl = "$subtitleUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("id", data.tmdbId)
            if (data.type == "tv") {
                addQueryParameter("season", data.season)
                addQueryParameter("episode", data.episode)
            }
            addQueryParameter("source", "opensubtitles,subdl") // query best sources simultaneously
            addQueryParameter("key", "wyzie-7kkv6r8dp0mlc10nsr2i7zn50fi55tmu")
        }.build()

        val preferredSubLang = preferences.subLangPref
        val subLimit = preferences.subLimitPref.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()

        // 1. Attempt to fetch VTT (Modern, strict UTF-8, prevents MPV crashes)
        val vttUrl = subtitleRequestUrl.newBuilder().addQueryParameter("format", "vtt").build()
        var subs = try {
            client.newCall(GET(vttUrl, headers)).awaitSuccess()
                .use { it.parseAs<List<SubtitleDto>>() }
        } catch (_: Exception) {
            emptyList()
        }

        // 2. Fallback to SRT if VTT fails or returns zero results
        if (subs.isEmpty()) {
            val srtUrl = subtitleRequestUrl.newBuilder().addQueryParameter("format", "srt").build()
            subs = try {
                client.newCall(GET(srtUrl, headers)).awaitSuccess()
                    .use { it.parseAs<List<SubtitleDto>>() }
            } catch (_: Exception) {
                emptyList()
            }
        }

        // 3. Format and sort whichever list succeeded
        return subs.take(subLimit).map { sub ->
            val langLabel = sub.display ?: sub.language
            val ccLabel = if (sub.isHearingImpaired) "$langLabel (CC)" else langLabel
            Track(sub.url, ccLabel)
        }.sortedByDescending { it.lang.startsWith(preferredSubLang) }
    }

    // ============================== Settings ==============================

    private val SharedPreferences.domainPref by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    private val SharedPreferences.videoQualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.latestPref by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.subLimitPref by preferences.delegate(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = DOMAIN_ENTRIES.toList(),
            entryValues = DOMAIN_VALUES.toList(),
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LATEST_KEY,
            title = "Preferred 'Latest' Page",
            entries = listOf("Movies", "TV Shows"),
            entryValues = listOf("movie", "tv"),
            default = PREF_LATEST_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = "Preferred Subtitle Language",
            entries = SUB_LANGS.map { it.second },
            entryValues = SUB_LANGS.map { it.first },
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )

        fun String.subLimitSummary() = "Limit the number of subtitles fetched.\nCurrent: $this"

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = preferences.subLimitPref.subLimitSummary(),
            getSummary = { it.subLimitSummary() },
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
            onChange = { _, newValue ->
                val newAmount = newValue.toIntOrNull()
                (newAmount != null && newAmount >= 0)
            },
        )
    }

    // ============================= Utilities ==============================

    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results.map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType ?: if (media.title != null) "movie" else "tv"
        url = "/?$type=${title.toSlug()}&id=${media.id}"
        thumbnail_url = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun String.toSlug(): String = this.lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val SECURE_RANDOM by lazy { SecureRandom() }
        private const val FINGERPRINT_LITE = "e9136c41504646444"

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val DOMAIN_ENTRIES = arrayOf("flixer.su", "flixer.cx")
        private val DOMAIN_VALUES = arrayOf("https://flixer.su", "https://flixer.cx")
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val PREF_SUB_KEY = "pref_sub"
        private const val PREF_SUB_DEFAULT = "en"

        private val SUB_LANGS = listOf(
            Pair("ar", "Arabic"),
            Pair("bn", "Bengali"),
            Pair("zh", "Chinese"),
            Pair("en", "English"),
            Pair("fr", "French"),
            Pair("de", "German"),
            Pair("hi", "Hindi"),
            Pair("id", "Indonesian"),
            Pair("it", "Italian"),
            Pair("ja", "Japanese"),
            Pair("ko", "Korean"),
            Pair("fa", "Persian"),
            Pair("pt", "Portuguese"),
            Pair("ru", "Russian"),
            Pair("es", "Spanish"),
            Pair("tr", "Turkish"),
            Pair("ur", "Urdu"),
            Pair("vi", "Vietnamese"),
        )

        fun statusParser(status: String?): Int = when (status) {
            "Released", "Ended" -> SAnime.COMPLETED
            "Returning Series", "In Production" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        fun parseDate(dateStr: String?): Long = runCatching {
            DATE_FORMATTER.parse(dateStr ?: "")?.time ?: 0L
        }.getOrDefault(0L)
    }
}
