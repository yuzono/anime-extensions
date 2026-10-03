package eu.kanade.tachiyomi.animeextension.en.mapple

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.comparisons.thenByDescending
import kotlin.coroutines.cancellation.CancellationException

class Mapple :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Mapple"

    private val preferences: SharedPreferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override val baseUrl
        get() = preferences.domainPref

    private val apiUrl = "https://api.themoviedb.org/3"
    private val subtitleApi = "https://sub.wyzie.io"

    override val lang = "en"

    override val supportsLatest = true

    private val customJson by lazy { Json { explicitNulls = false } }

    private val EpisodeData.tvSlug: String
        get() = if (type == "tv") "$season-$episode" else ""

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val apiKey: String
        get() = preferences.tmdbKeyPref.trim().ifBlank { TMDB_API_KEY }

    private val wyzieApiKey: String
        get() = preferences.wyzieKeyPref.trim().ifBlank { WYZIE_API_KEY }

    @Serializable
    private class HosterInternalData(
        val hosterKey: String,
        val requestToken: String,
        val playbackToken: String,
        val episodeData: EpisodeData,
    )

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("api_key", apiKey)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return parseMediaPage(client.get(url))
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

        val results = types.parallelMap { mediaType ->
            runCatching {
                latestUpdatesParse(client.get(latestUpdatesUrl(page, mediaType)))
            }
        }
        val animePages = results.mapNotNull { it.getOrNull() }
        if (animePages.isEmpty()) {
            results.first().exceptionOrNull()?.let { throw it }
        }
        return animePages.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    private fun latestUpdatesUrl(page: Int, mediaType: String): HttpUrl {
        val date = dateFormat.format(Date())
        return apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("api_key", apiKey)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter("primary_release_date.lte", date)
        }.build()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

            val pageDtos = types.parallelMapNotNull { mediaType ->
                runCatching {
                    client.get(searchAnimeUrl(page, query, mediaType)).parseAs<PageDto<MediaItemDto>>()
                }.getOrNull()
            }

            // Combine, sort by popularity, then convert to SAnime
            val animes = pageDtos.flatMap { it.results }
                .sortedByDescending { it.popularity ?: 0.0 }
                .map { it.toSAnime() }

            val hasNextPage = pageDtos.any { it.page < it.totalPages }

            return AnimesPage(animes, hasNextPage)
        } else {
            return parseMediaPage(client.get(filterSearchUrl(page, filters)))
        }
    }

    private fun searchAnimeUrl(page: Int, query: String, mediaType: String): HttpUrl = apiUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("search")
        addPathSegment(mediaType)
        addQueryParameter("api_key", apiKey)
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
        addQueryParameter("query", query)
    }.build()

    private fun filterSearchUrl(page: Int, filters: AnimeFilterList): HttpUrl {
        val type = filters.firstInstanceOrNull<Filters.TypeFilter>()?.state?.let {
            if (it == 0) "movie" else "tv"
        } ?: "movie"
        val sortFilter = filters.firstInstance<Filters.SortFilter>()
        val sortBy = sortFilter.state?.run {
            when (index) {
                0 -> "popularity"
                1 -> "vote_average"
                else -> if (type == "movie") "primary_release_date" else "first_air_date"
            } + if (ascending) ".asc" else ".desc"
        } ?: "popularity.desc"

        val genreMap = if (type == "movie") Filters.MOVIE_GENRE_MAP else Filters.TV_GENRE_MAP
        val genres = filters.firstInstance<Filters.GenreFilter>()
            .state.filter { it.state }.mapNotNull { genreMap[it.name] }.joinToString(",")

        val providers = filters.firstInstanceOrNull<Filters.WatchProviderFilter>()
            ?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        return apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(type)
            addQueryParameter("api_key", apiKey)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genres.isNotBlank()) addQueryParameter("with_genres", genres)
            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US")
            }
        }.build()
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, id) = animeUrlToId(anime)
        val response = client.get(animeDetailsUrl(type, id))

        return try {
            if (type == "movie") movieDetailsParse(response) else tvDetailsParse(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Failed to parse details. The API might have returned an error page.", e)
        }
    }
    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    private fun animeDetailsUrl(type: String, id: String): HttpUrl = apiUrl.toHttpUrl().newBuilder().apply {
        addPathSegment(type)
        addPathSegment(id)
        addQueryParameter("api_key", apiKey)
        addQueryParameter("append_to_response", "external_ids")
    }.build()
    private fun movieDetailsParse(response: Response): SAnime = response.parseAs<MovieDetailDto>().toSAnime()

    private fun tvDetailsParse(response: Response): SAnime = response.parseAs<TvDetailDto>().toSAnime()

    // ========================== Related Titles ============================

    override suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
        try {
            val animes = client.get(relatedAnimeUrl(anime))
                .parseAs<PageDto<MediaItemDto>>()
                .results
                .map { it.toSAnime() }
            pushResults("Related" to animes, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exceptionHandler(e)
        }
    }

    private fun relatedAnimeUrl(anime: SAnime): HttpUrl {
        val (type, id) = animeUrlToId(anime)
        return apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("api_key", apiKey)
            addQueryParameter("page", "1")
        }.build()
    }

    // ============================== Episodes ==============================
    @Serializable
    private class EpisodeData(
        val title: String,
        val year: String,
        val tmdbId: String,
        val season: String? = null,
        val episode: String? = null,
        val type: String,
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, tmdbId) = animeUrlToId(anime)
        val response = client.get(animeDetailsUrl(type, tmdbId))
        return if (type == "tv") {
            val tv = response.parseAs<TvDetailDto>()
            tv.seasons
                .filter { it.seasonNumber > 0 }
                .parallelCatchingFlatMap { season ->
                    val seasonUrl = "$apiUrl/tv/${tv.id}/season/${season.seasonNumber}".toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("api_key", apiKey)
                        .build()
                    val seasonDetail = client.get(seasonUrl).parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes.map { episode ->
                        val extraData = EpisodeData(
                            title = tv.name,
                            year = tv.firstAirDate?.take(4) ?: "",
                            tmdbId = tv.id.toString(),
                            season = season.seasonNumber.toString(),
                            episode = episode.episodeNumber.toString(),
                            type = "tv",
                        )
                        val extraDataEncoded = jsonInstance.encodeToString(extraData)
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                            episode_number = episode.episodeNumber.toFloat()
                            scanlator = "Season ${season.seasonNumber}"
                            date_upload = dateFormat.tryParse(episode.airDate)
                            url = "tv/$tmdbId/${season.seasonNumber}/${episode.episodeNumber}#$extraDataEncoded"
                        }
                    }
                }
                .sortedWith(
                    compareByDescending<SEpisode> { it.scanlator?.substringAfter(" ")?.toIntOrNull() }
                        .thenByDescending { it.episode_number },
                )
        } else {
            val movie = response.parseAs<MovieDetailDto>()
            val extraData = EpisodeData(
                title = movie.title,
                year = movie.releaseDate?.take(4) ?: "",
                tmdbId = movie.id.toString(),
                type = "movie",
            )
            val extraDataEncoded = jsonInstance.encodeToString(extraData)
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = dateFormat.tryParse(movie.releaseDate)
                    url = "movie/$tmdbId#$extraDataEncoded"
                },
            )
        }
    }
    private fun buildApiHeaders(): Headers = headers.newBuilder()
        .add("Content-Type", "application/json")
        .add("Accept", "*/*")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .build()

    private fun buildStreamHeaders(): Headers = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("User-Agent", DEFAULT_USER_AGENT)
        .build()

    // ============================ Video Links =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = jsonInstance.decodeFromString<HosterInternalData>(hoster.internalData)
        val apiHeaders = buildApiHeaders()
        val streamHeaders = buildStreamHeaders()

        val streamUrl = resolveStreamUrl(data, apiHeaders, streamHeaders) ?: return emptyList()
        val subtitles = getSubtitles(data.episodeData)

        return extractVideos(hoster, streamUrl, streamHeaders, subtitles)
    }

    private suspend fun resolveStreamUrl(
        data: HosterInternalData,
        apiHeaders: Headers,
        streamHeaders: Headers,
    ): String? {
        val encryptPayload = EncryptRequest(
            data = EncryptPayload(
                mediaId = data.episodeData.tmdbId.toInt(),
                mediaType = data.episodeData.type,
                tvSlug = data.episodeData.tvSlug,
                source = data.hosterKey,
            ),
            endpoint = "stream-encrypted",
            requestToken = data.requestToken,
        )

        val encryptResponse = runCatching {
            client.post(
                "$baseUrl/api/encrypt",
                apiHeaders,
                encryptPayload.toJsonRequestBody(),
            ).parseAs<EncryptResponse>()
        }.onFailure {
            if (it is CancellationException) throw it
        }.getOrNull() ?: return null

        val streamRequestUrl = (
            baseUrl.toHttpUrl().resolve(encryptResponse.url) ?: return null
            ).newBuilder()
            .addQueryParameter("requestToken", data.requestToken)
            .addQueryParameter("token", data.playbackToken)
            .build()

        val streamResponse = runCatching {
            client.get(streamRequestUrl, streamHeaders).parseAs<StreamEncryptedResponse>()
        }.getOrNull()

        return streamResponse?.takeIf { it.success && it.data != null }?.data?.streamUrl
    }
    private fun extractVideos(
        hoster: Hoster,
        streamUrl: String,
        streamHeaders: Headers,
        subtitles: List<Track>,
    ): List<Video> = playlistUtils.extractFromHls(
        playlistUrl = streamUrl,
        videoNameGen = { quality -> "${hoster.hosterName} - $quality" },
        subtitleList = subtitles,
        masterHeaders = streamHeaders,
        videoHeaders = streamHeaders,
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val extraDataEncoded = episode.url.substringAfter('#', "")
        val episodeData = jsonInstance.decodeFromString<EpisodeData>(extraDataEncoded)

        val requestToken = getRequestToken()
        val playbackToken = getPlaybackToken(episodeData, requestToken)

        return HOSTERS.map { hosterEntry ->
            val internalData = jsonInstance.encodeToString(
                HosterInternalData(hosterEntry.key, requestToken, playbackToken, episodeData),
            )
            Hoster(
                hosterName = hosterEntry.name,
                internalData = internalData,
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val hosterSelection = preferences.hostersPref
        val preferredServer = preferences.serverPref

        val enabled = filter { it.hosterName in hosterSelection }

        return if (preferredServer in hosterSelection) {
            val priority = enabled.firstOrNull { it.hosterName == preferredServer }
            val others = enabled.filter { it.hosterName != preferredServer }
            listOfNotNull(priority) + others
        } else {
            enabled
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.qualityPref
        val qualityValues = QUALITY_VALUES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video -> qualityValues.indexOfFirst { video.videoTitle.contains(it) } },
        )
    }

    private suspend fun getSubtitles(data: EpisodeData): List<Track> {
        val url = if (data.type == "movie") {
            "$subtitleApi/search?id=${data.tmdbId}"
        } else {
            "$subtitleApi/search?id=${data.tmdbId}&season=${data.season}&episode=${data.episode}"
        }

        val subUrl = url.toHttpUrl().newBuilder().apply {
            addQueryParameter("key", wyzieApiKey)
        }.build()

        return try {
            val subLimit = preferences.subLimitPref.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()
            val preferredSubLang = preferences.subLangPref

            val subtitles = client.get(subUrl).parseAs<List<SubtitleDto>>()
            subtitles
                .take(subLimit)
                .map { sub ->
                    val langLabel = if (sub.isHearingImpaired) "${sub.language} (CC)" else sub.language
                    Track(sub.url, langLabel)
                }
                .sortedByDescending { preferredSubLang.let(it.lang::startsWith) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getPlaybackToken(
        episodeData: EpisodeData,
        requestToken: String,
    ): String {
        val initPayload = PlaybackInitRequest(
            mediaId = episodeData.tmdbId.toInt(),
            mediaType = episodeData.type,
            tvSlug = episodeData.tvSlug,
            requestToken = requestToken,
        )

        val apiHeaders = headers.newBuilder()
            .set("Content-Type", "application/json")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        // First call: get challenge
        val challengeResponse = client.post(
            "$baseUrl/api/playback-init",
            apiHeaders,
            initPayload.toJsonRequestBody(customJson),
        ).parseAs<PlaybackInitResponse>()

        if (challengeResponse.success && challengeResponse.token != null) {
            // No PoW required
            return challengeResponse.token
        }

        if (!challengeResponse.requiresPow || challengeResponse.pow == null) {
            throw Exception("Playback init failed: ${challengeResponse.success}")
        }

        // Solve PoW
        val pow = challengeResponse.pow
        val nonce = solvePow(pow.challenge, pow.difficulty)

        // Second call: with solved PoW
        val powPayload = initPayload.copy(
            pow = PowSolution(
                challengeId = pow.challengeId,
                nonce = nonce,
            ),
        )

        val tokenResponse = client.post(
            "$baseUrl/api/playback-init",
            apiHeaders,
            powPayload.toJsonRequestBody(customJson),
        ).parseAs<PlaybackInitResponse>()

        if (!tokenResponse.success || tokenResponse.token == null) {
            throw Exception("Playback init (with PoW) failed")
        }

        return tokenResponse.token
    }

    /**
     * Calls the /api/request-token API and extracts the requestToken from the token response.
     */
    private suspend fun getRequestToken(): String = client.post(
        "$baseUrl/api/request-token",
        headers,
        "".toJsonRequestBody(),
    ).parseAs<RequestTokenResponse>().token

    private suspend fun solvePow(challenge: String, difficulty: Int): String = withContext(Dispatchers.Default) {
        val digest = MessageDigest.getInstance("SHA-256")
        val challengeBytes = challenge.toByteArray(Charsets.UTF_8)
        val hash = ByteArray(32)
        var nonce = 0L
        val maxAttempts = 50_000_000L // Matched to Web Worker cap

        while (nonce < maxAttempts) {
            if (nonce % 10_000L == 0L) {
                currentCoroutineContext().ensureActive()
            }

            digest.reset()
            digest.update(challengeBytes)
            digest.update(nonce.toString().toByteArray(Charsets.UTF_8))
            digest.digest(hash, 0, 32) // Writes into buffer without allocating

            if (countLeadingZeroBits(hash) >= difficulty) {
                return@withContext nonce.toString()
            }
            nonce++
        }
        throw Exception("PoW solve exceeded max attempts (difficulty=$difficulty)")
    }

    private fun countLeadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned == 0) {
                count += 8
            } else {
                for (bit in 7 downTo 0) {
                    if (unsigned and (1 shl bit) != 0) {
                        return count
                    }
                    count++
                }
            }
        }
        return count
    }

    // ============================== Settings ==============================
    private val SharedPreferences.domainPref by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    private val SharedPreferences.latestPref by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.subLimitPref by preferences.delegate(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.hostersPref by preferences.delegate(PREF_HOSTERS_KEY, DEFAULT_ENABLED_HOSTERS)
    private val SharedPreferences.tmdbKeyPref by preferences.delegate(PREF_TMDB_KEY_KEY, PREF_TMDB_KEY_DEFAULT)
    private val SharedPreferences.wyzieKeyPref by preferences.delegate(PREF_WYZIE_KEY_KEY, PREF_WYZIE_KEY_DEFAULT)

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!.removePrefix("https://")
        val invalidDomain = domain !in DOMAIN_ENTRIES

        val hosterNames = HOSTERS.map { it.name }
        val hostToggle = getStringSet(PREF_HOSTERS_KEY, DEFAULT_ENABLED_HOSTERS)!!
        val invalidHosters = hostToggle.any { it !in hosterNames }

        if (invalidDomain || invalidHosters) {
            edit().also { editor ->
                if (invalidDomain) {
                    editor.putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
                }
                if (invalidHosters) {
                    editor.putStringSet(PREF_HOSTERS_KEY, DEFAULT_ENABLED_HOSTERS)
                    editor.putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                }
            }.apply()
        }
        return this
    }

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
            key = PREF_LATEST_KEY,
            title = "Preferred 'Latest' Page",
            entries = listOf("Movies", "TV Shows"),
            entryValues = listOf("movie", "tv"),
            default = PREF_LATEST_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
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

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = "Limit the number of subtitles fetched.\nCurrent: ${preferences.subLimitPref}",
            getSummary = { "Limit the number of subtitles fetched.\nCurrent: $it" },
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
            onChange = { _, newValue ->
                val newAmount = newValue.toIntOrNull()
                (newAmount != null && newAmount >= 0)
            },
        )

        screen.addEditTextPreference(
            key = PREF_TMDB_KEY_KEY,
            title = "Custom TMDB API Key",
            summary = "Overrides the built-in TMDB API key. Leave blank to use the default.\n" +
                "Status: ${if (preferences.tmdbKeyPref.isBlank()) "Using default key" else "Using custom key"}",
            getSummary = {
                "Overrides the built-in TMDB API key. Leave blank to use the default.\n" +
                    "Status: ${if (it.isBlank()) "Using default key" else "Using custom key"}"
            },
            default = PREF_TMDB_KEY_DEFAULT,
            inputType = InputType.TYPE_CLASS_TEXT,
            onChange = { _, _ -> true },
        )

        screen.addEditTextPreference(
            key = PREF_WYZIE_KEY_KEY,
            title = "Custom Wyzie API Key",
            summary = "Overrides the built-in subtitle API key. Leave blank to use the default.\n" +
                "Status: ${if (preferences.wyzieKeyPref.isBlank()) "Using default key" else "Using custom key"}",
            getSummary = {
                "Overrides the built-in subtitle API key. Leave blank to use the default.\n" +
                    "Status: ${if (it.isBlank()) "Using default key" else "Using custom key"}"
            },
            default = PREF_WYZIE_KEY_DEFAULT,
            inputType = InputType.TYPE_CLASS_TEXT,
            onChange = { _, _ -> true },
        )

        val hosterNames = HOSTERS.map { it.name }

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = hosterNames,
            entryValues = hosterNames,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTERS_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = hosterNames,
            entryValues = hosterNames,
            default = DEFAULT_ENABLED_HOSTERS,
        )
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results.map { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")
    override fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used")
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    override fun relatedAnimeListRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException("Not used")

    companion object {
        private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val animeUrlRegex = Regex("""/(tv|movie)/(\d+)""")

        private const val TMDB_API_KEY = BuildConfig.TMDB_API

        private const val WYZIE_API_KEY = BuildConfig.WYZIE_API

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://mapple.fun"
        private val DOMAIN_ENTRIES = arrayOf("mapple.fun")
        private val DOMAIN_VALUES = arrayOf("https://mapple.fun")
        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_ENTRIES = listOf("2160p (4k)", "1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = listOf("2160", "1080", "720", "480", "360")

        private const val PREF_SUB_KEY = "pref_sub"
        private const val PREF_SUB_DEFAULT = "en"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "35"
        private const val PREF_TMDB_KEY_KEY = "pref_tmdb_key"
        private const val PREF_TMDB_KEY_DEFAULT = ""

        private const val PREF_WYZIE_KEY_KEY = "pref_wyzie_key"
        private const val PREF_WYZIE_KEY_DEFAULT = ""
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        private class HosterEntry(val name: String, val key: String)
        private val HOSTERS = listOf(
            HosterEntry("Zeus", "mapple"),
            HosterEntry("Poseidon", "s25"),
            HosterEntry("Athena", "s2"),
            HosterEntry("Apollo", "s19"),
            HosterEntry("Artemis", "s13"),
            HosterEntry("Hermes", "s26"),
            HosterEntry("Hera", "s4"),
            HosterEntry("Ares", "s24"),
            HosterEntry("Aphrodite", "s6"),
            HosterEntry("Hephaestus", "s15"),
            HosterEntry("Demeter", "s7"),
            HosterEntry("Dionysus", "s8"),
            HosterEntry("Hestia", "s3"),
            HosterEntry("Hades", "s16"),
            HosterEntry("Persephone", "s12"),
            HosterEntry("Nike", "s5"),
            HosterEntry("Atlas", "s1"),
            HosterEntry("Prometheus", "s10"),
        )

        private val DEFAULT_ENABLED_HOSTERS = HOSTERS.take(3).map { it.name }.toSet()

        private const val PREF_SERVER_KEY = "preferred_server_v1"
        private val PREF_SERVER_DEFAULT = HOSTERS.first().name

        private const val PREF_HOSTERS_KEY = "hoster_selection"

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
    }
}
