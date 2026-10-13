package eu.kanade.tachiyomi.animeextension.en.hexawatch

import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.get
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HexaWatch :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HexaWatch"

    override val baseUrl = "https://hexa.su"
    private val animeUrl = "$baseUrl/details"
    private val apiUrl = "https://theemoviedb.hexa.su/api/tmdb"
    private val subtitleUrl = "https://sub.wyzie.ru"
    private val decryptionApiUrl = "https://enc-dec.app/api/dec-hexa"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val capTokenProvider by lazy { CapTokenProvider() }

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
        return types.parallelMapNotNull { mediaType ->
            runCatching {
                client.newCall(latestUpdatesRequest(page, mediaType))
                    .awaitSuccess()
                    .use { response ->
                        latestUpdatesParse(response)
                    }
            }.getOrNull()
        }.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50") // Minimum votes to avoid low-rated content
            addQueryParameter("primary_release_date.lte", date) // Only released content
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val preferredLatest = preferences.latestPref
            val types = if (preferredLatest == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            return types.parallelMapNotNull { mediaType ->
                runCatching {
                    client.newCall(searchAnimeRequest(page, query, mediaType))
                        .awaitSuccess()
                        .use { response ->
                            searchAnimeParse(response)
                        }
                }.getOrNull()
            }.let { animePages ->
                val animes = animePages.flatMap { it.animes }
                val hasNextPage = animePages.any { it.hasNextPage }
                AnimesPage(animes, hasNextPage)
            }
        } else {
            return super.getSearchAnime(page, query, filters)
        }
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
        val type = filters.filterIsInstance<Filters.TypeFilter>().first().state.let {
            if (it == 0) "movie" else "tv"
        }
        val sortFilter = filters.filterIsInstance<Filters.SortFilter>().first()
        val sortBy = sortFilter.state?.run {
            when (index) {
                0 -> "popularity"
                1 -> "vote_average"
                else -> if (type == "movie") "primary_release_date" else "first_air_date"
            } + if (ascending) ".asc" else ".desc"
        } ?: "popularity.desc"

        val genreMap = if (type == "movie") Filters.MOVIE_GENRE_MAP else Filters.TV_GENRE_MAP
        val genres = filters.filterIsInstance<Filters.GenreFilter>().first()
            .state.filter { it.state }.mapNotNull { genreMap[it.name] }.joinToString(",")

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(type)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genres.isNotBlank()) {
                addQueryParameter("with_genres", genres)
            }

            // ====== Watch Provider Filter ======
            val providers = filters.filterIsInstance<Filters.WatchProviderFilter>()
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

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== Details ===============================
    override fun getAnimeUrl(anime: SAnime): String = animeUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = (apiUrl + anime.url).toHttpUrl().newBuilder().apply {
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url, headers)
    }

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val url = (apiUrl + anime.url).toHttpUrl().newBuilder().apply {
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        return try {
            if ("/movie/" in response.request.url.toString()) {
                movieDetailsParse(responseBody)
            } else {
                tvDetailsParse(responseBody)
            }
        } catch (e: Exception) {
            throw Exception("Failed to parse details. The API might have returned an error page.", e)
        }
    }

    private fun movieDetailsParse(responseBody: String): SAnime {
        val movie = responseBody.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/movie/${movie.id}"
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
            url = "/tv/${tv.id}"
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
                    tv.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ $${String.format(Locale.US, "%.1f", it)}" },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tag Line**: *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf { it.isNotBlank() && it.trim() != tv.name.trim() }?.let { "**Original Name:** $it" },
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

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = client.newCall(episodeListRequest(anime))
        .awaitSuccess()
        .use { response ->
            episodeListParseAsync(response)
        }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    private suspend fun episodeListParseAsync(response: Response): List<SEpisode> {
        val responseBody = response.body.string()
        return if ("/tv/" in response.request.url.toString()) {
            val tv = responseBody.parseAs<TvDetailDto>()
            tv.seasons.sortedByDescending { it.seasonNumber }
                .filter { it.seasonNumber > 0 }
                .parallelFlatMap { season ->
                    runCatching {
                        val tvSeasonDetail = client.newCall(
                            GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}", headers),
                        ).awaitSuccess().use { it.parseAs<TvSeasonDetailDto>() }
                        val episodes = tvSeasonDetail.episodes.sortedByDescending { it.episodeNumber }
                        episodes.map { episode ->
                            SEpisode.create().apply {
                                name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                                episode_number = episode.episodeNumber.toFloat()
                                date_upload = parseDate(episode.airDate)
                                url = "/tv/${tv.id}/${season.seasonNumber}/${episode.episodeNumber}"
                            }
                        }
                    }.getOrElse { emptyList() }
                }.ifEmpty {
                    throw Exception("No episodes found.")
                }
        } else {
            val movie = responseBody.parseAs<MovieDetailDto>()
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "/movie/${movie.id}"
                },
            )
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val requestUrl = videoRequestUrl(episode)
        val key = randomHex(32)

        val serverList = fetchSources(requestUrl, key, Headers.headersOf(SERVER_LIST_HEADER, "1"))
        val urls = serverList.sourceUrls()
        val servers = (serverList.sources?.toSourceList().orEmpty().map { it.server } + serverList.servers.keys)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { NATO_SERVERS.indexOf(it).takeIf { i -> i >= 0 } ?: NATO_SERVERS.size }

        return servers.map { server ->
            Hoster(
                hosterName = server,
                hosterUrl = requestUrl,
                internalData = HosterData(requestUrl, server, key, urls[server]).toJsonString(),
            )
        }.ifEmpty {
            throw Exception("No servers found")
        }
    }

    private fun videoRequestUrl(episode: SEpisode): String {
        val path = episode.url.split("/").drop(1)
        return when (path.first()) {
            "movie" -> "$apiUrl/movie/${path[1]}/images"
            "tv" -> "$apiUrl/tv/${path[1]}/season/${path[2]}/episode/${path[3]}/images"
            else -> throw Exception("Invalid media type for video request")
        }
    }

    /**
     * Requests [requestUrl] with the headers the site's source loader sends, then decrypts the
     * response. Each request is signed with [key], which is also the decryption key.
     */
    private suspend fun fetchSources(requestUrl: String, key: String, extraHeaders: Headers): ExtractorResultDto {
        var capToken = capTokenProvider.getToken()
        for (attempt in 1..CAPTCHA_ATTEMPTS) {
            val videoHeaders = headers.newBuilder()
                .addAll(extraHeaders)
                .addAll(signedHeaders(requestUrl, key))
                .set("Accept", "text/plain")
                .set("Referer", "$baseUrl/")
                .add("X-Fingerprint-Lite", FINGERPRINT_LITE)
                .add("X-Cap-Token", capToken)
                .build()

            val (code, body) = client.get(requestUrl, videoHeaders, CacheControl.FORCE_NETWORK, ensureSuccess = false)
                .use { it.code to it.body.string() }

            when {
                code in 200..299 -> {
                    if (body.isBlank()) throw Exception("HexaWatch returned an empty source response")
                    return decryptSources(body, key)
                }
                code == 403 && "captcha_required" in body -> {
                    capTokenProvider.invalidate(capToken)
                    if (attempt < CAPTCHA_ATTEMPTS) capToken = capTokenProvider.getToken()
                }
                else -> throw Exception("HTTP error $code")
            }
        }
        throw Exception("The captcha token was rejected by the server")
    }

    private suspend fun signedHeaders(requestUrl: String, key: String): Headers {
        val timestamp = client.get(TIME_URL, headers, CacheControl.FORCE_NETWORK)
            .use { it.parseAs<ServerTimeDto>() }.timestamp.toString()
        val nonce = Base64.encodeToString(ByteArray(16).also(SECURE_RANDOM::nextBytes), Base64.NO_WRAP)
            .replace(NONCE_STRIP_REGEX, "")
            .take(22)
        val path = requestUrl.toHttpUrl().encodedPath
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val signature = Base64.encodeToString(mac.doFinal("$key:$timestamp:$nonce:$path".toByteArray()), Base64.NO_WRAP)

        return Headers.headersOf(
            "X-Api-Key", key,
            "X-Request-Timestamp", timestamp,
            "X-Request-Nonce", nonce,
            "X-Request-Signature", signature,
            "X-Client-Fingerprint", clientFingerprint,
        )
    }

    private suspend fun decryptSources(encryptedText: String, key: String): ExtractorResultDto {
        val requestBody = DecryptionRequestDto(encryptedText, key).toJsonRequestBody()
        val response = client.newCall(Request.Builder().url(decryptionApiUrl).post(requestBody).build())
            .awaitSuccess().use { it.parseAs<ExtractorResponseDto>() }
        return (response.result as? JsonObject)?.let { it.parseAs<ExtractorResultDto>() }
            ?: throw Exception("Failed to decrypt sources: ${response.error ?: "empty result"}")
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.parseAs<HosterData>()
        val playlistUrl = data.url
            ?: fetchSources(data.requestUrl, data.key, Headers.headersOf("X-Only-Sources", "1", "X-Server", data.server))
                .sourceUrls()
                .let { it[data.server] ?: it.values.firstOrNull() }
            ?: return emptyList()

        val subtitles = getSubtitles(data.requestUrl)
        val videos = playlistUtils.extractFromHls(
            playlistUrl = playlistUrl,
            subtitleList = subtitles,
            referer = "$baseUrl/",
        )
        // Single-rendition playlists carry no resolution, so read it from the first segment.
        val single = videos.singleOrNull()?.takeIf { it.videoTitle == "Video" }
            ?: return videos.sortVideos()
        val quality = probeQuality(playlistUrl, single.headers ?: headers) ?: return videos.sortVideos()
        return listOf(single.copy(videoTitle = quality)).sortVideos()
    }

    private suspend fun probeQuality(playlistUrl: String, videoHeaders: Headers): String? = try {
        val segment = client.get(playlistUrl, videoHeaders).use { it.body.string() }
            .lineSequence()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.let { playlistUrl.toHttpUrl().resolve(it.trim()) }
        segment?.let { url ->
            // The proxy ignores Range requests; read the start and drop the rest.
            val head = client.get(url, videoHeaders, CacheControl.FORCE_NETWORK).use { response ->
                val source = response.body.source()
                source.request(SEGMENT_PROBE_BYTES)
                source.buffer.readByteArray(minOf(source.buffer.size, SEGMENT_PROBE_BYTES))
            }
            H264Resolution.parse(head)?.let { (width, height) -> "${qualityLabel(width, height)} (${width}x$height)" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun qualityLabel(width: Int, height: Int) = when {
        width >= 3840 -> "2160p"
        width >= 2560 -> "1440p"
        width >= 1920 -> "1080p"
        width >= 1280 -> "720p"
        width >= 854 -> "480p"
        width >= 640 -> "360p"
        else -> "${height}p"
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.videoQualityPref
        return map { video ->
            video.copy(preferred = video.videoTitle.contains(preferredQuality))
        }.sortedByDescending { it.preferred }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    @Serializable
    private class HosterData(val requestUrl: String, val server: String, val key: String, val url: String?)

    private fun randomHex(bytes: Int) = ByteArray(bytes).also(SECURE_RANDOM::nextBytes)
        .joinToString("") { "%02x".format(it) }

    // Stands in for the site's hashed screen/UA/canvas fingerprint; only needs to be stable.
    private val clientFingerprint by lazy { randomHex(4).toLong(16).toString(36) }

    private suspend fun getSubtitles(requestUrl: String): List<Track> {
        val match = GET_SUBTITLES_REGEX.find(requestUrl)
            ?: return emptyList()

        val (mediaType, mediaId, season, episode) = match.destructured

        val subtitleRequestUrl = when (mediaType) {
            "movie" -> "$subtitleUrl/search?id=$mediaId"
            "tv" -> "$subtitleUrl/search?id=$mediaId&season=$season&episode=$episode"
            else -> return emptyList()
        }

        return try {
            val preferredSubLang = preferences.subLangPref

            val subLimit = preferences.subLimitPref.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()
            val subtitles = client.newCall(GET(subtitleRequestUrl, headers))
                .awaitSuccess().use { it.parseAs<List<SubtitleDto>>() }
            subtitles
                .take(subLimit)
                .map { sub ->
                    val langLabel = if (sub.isHearingImpaired) "${sub.language} (CC)" else sub.language
                    Track(sub.url, langLabel)
                }
                .sortedByDescending { preferredSubLang.let(it.lang::startsWith) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================== Settings ==============================

    private val SharedPreferences.videoQualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.latestPref by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.subLimitPref by preferences.delegate(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

    companion object {

        private val SECURE_RANDOM by lazy { SecureRandom() }

        // Constant sent by the site's own fetch wrapper on every source request.
        private const val FINGERPRINT_LITE = "e9136c41504646444"
        private const val CAPTCHA_ATTEMPTS = 2
        private const val SEGMENT_PROBE_BYTES = 64 * 1024L

        private const val TIME_URL = "https://theemoviedb.hexa.su/api/time"

        // Base64 "mothafaka"; the site sends it on the server list request only.
        private const val SERVER_LIST_HEADER = "bW90aGFmYWth"

        private val NONCE_STRIP_REGEX by lazy { "[/+=]".toRegex() }

        // Order the site tries servers in.
        private val NATO_SERVERS = listOf(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet", "kilo", "lima",
            "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey",
            "xray", "yankee", "zulu",
        )

        private val GET_SUBTITLES_REGEX by lazy { "/(movie|tv)/(\\d+)(?:/season/(\\d+)/episode/(\\d+))?".toRegex() }

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
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr ?: "")?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages

        val animeList = pageDto.results
            .map(::mediaItemToSAnime)

        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType ?: if (media.title != null) "movie" else "tv"
        url = "/$type/${media.id}"
        thumbnail_url = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    }
}
