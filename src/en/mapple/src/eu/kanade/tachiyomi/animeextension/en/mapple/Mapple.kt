package eu.kanade.tachiyomi.animeextension.en.mapple

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val mappleApi = "https://mapple.uk" // for payload only
    private val decryptApi = "https://enc-dec.app/api"
    private val subtitleApi = "https://sub.wyzie.ru"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

        return types.parallelMapNotNull { mediaType ->
            runCatching {
                client.newCall(latestUpdatesRequest(page, mediaType))
                    .awaitSuccess()
                    .use { latestUpdatesParse(it) }
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
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter("primary_release_date.lte", date)
        }.build()
        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

            val pageDtos = types.parallelMapNotNull { mediaType ->
                runCatching {
                    client.newCall(searchAnimeRequest(page, query, mediaType))
                        .awaitSuccess()
                        .use { it.parseAs<PageDto<MediaItemDto>>() }
                }.getOrNull()
            }

            // Combine, sort by popularity, then convert to SAnime
            val animes = pageDtos.flatMap { it.results }
                .sortedByDescending { it.popularity ?: 0.0 }
                .map(::mediaItemToSAnime)

            val hasNextPage = pageDtos.any { it.page < it.totalPages }

            return AnimesPage(animes, hasNextPage)
        } else {
            return super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeRequest(page: Int, query: String, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
        }.build()
        return GET(url)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.filterIsInstance<MappleFilters.TypeFilter>().first().state.let {
            if (it == 0) "movie" else "tv"
        }
        val sortFilter = filters.filterIsInstance<MappleFilters.SortFilter>().first()
        val sortBy = sortFilter.state?.run {
            when (index) {
                0 -> "popularity"
                1 -> "vote_average"
                else -> if (type == "movie") "primary_release_date" else "first_air_date"
            } + if (ascending) ".asc" else ".desc"
        } ?: "popularity.desc"

        val genreMap = if (type == "movie") MappleFilters.MOVIE_GENRE_MAP else MappleFilters.TV_GENRE_MAP
        val genres = filters.filterIsInstance<MappleFilters.GenreFilter>().first()
            .state.filter { it.state }.mapNotNull { genreMap[it.name] }.joinToString(",")

        val providers = filters.filterIsInstance<MappleFilters.WatchProviderFilter>()
            .firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(type)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genres.isNotBlank()) addQueryParameter("with_genres", genres)
            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US")
            }
        }.build()
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = MappleFilters.getFilterList()

    // ============================== Details ===============================
    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url)
    }

    override fun animeDetailsParse(response: Response): SAnime = try {
        if ("/movie/" in response.request.url.toString()) {
            movieDetailsParse(response)
        } else {
            tvDetailsParse(response)
        }
    } catch (e: Exception) {
        throw Exception("Failed to parse details. The API might have returned an error page.", e)
    }

    private fun movieDetailsParse(response: Response): SAnime {
        val movie = response.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/movie/${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            genre = movie.genres.joinToString { it.name }
            status = parseStatus(movie.status)
            description = buildString {
                movie.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
                    movie.originalTitle?.takeIf { it.isNotBlank() && it.trim() != movie.title.trim() }?.let { "**Original Title:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                movie.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    private fun tvDetailsParse(response: Response): SAnime {
        val tv = response.parseAs<TvDetailDto>()
        return SAnime.create().apply {
            title = tv.name
            url = "/tv/${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            genre = tv.genres.joinToString { it.name }
            status = parseStatus(tv.status)
            description = buildString {
                tv.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf { it.isNotBlank() && it.trim() != tv.name.trim() }?.let { "**Original Name:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                tv.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    // ========================== Related Titles ============================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
    }

    // ============================== Episodes ==============================
    @Serializable
    private data class EpisodeData(
        val title: String,
        val year: String,
        val tmdbId: String,
        val season: String? = null,
        val episode: String? = null,
        val type: String,
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, tmdbId) = animeUrlToId(anime)
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return if (type == "tv") {
            val tv = response.parseAs<TvDetailDto>()
            tv.seasons
                .filter { it.seasonNumber > 0 }
                .parallelCatchingFlatMap { season ->
                    val seasonDetail = client.newCall(
                        GET(
                            "$apiUrl/tv/${tv.id}/season/${season.seasonNumber}".toHttpUrl()
                                .newBuilder()
                                .addQueryParameter("api_key", TMDB_API_KEY)
                                .build(),
                        ),
                    ).awaitSuccess().parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes.map { episode ->
                        val extraData = EpisodeData(
                            title = tv.name,
                            year = tv.firstAirDate?.take(4) ?: "",
                            tmdbId = tv.id.toString(),
                            season = season.seasonNumber.toString(),
                            episode = episode.episodeNumber.toString(),
                            type = "tv",
                        )
                        val extraDataEncoded = json.encodeToString(extraData)
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                            episode_number = episode.episodeNumber.toFloat()
                            scanlator = "Season ${season.seasonNumber}"
                            date_upload = parseDate(episode.airDate)
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
            val extraDataEncoded = json.encodeToString(extraData)
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "movie/$tmdbId#$extraDataEncoded"
                },
            )
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val extraDataEncoded = episode.url.split("#").last()
        val episodeData = json.decodeFromString<EpisodeData>(extraDataEncoded)

        val session = try {
            client.newCall(GET("$decryptApi/enc-mapple")).awaitSuccess()
                .parseAs<SessionResponseDto>().result
        } catch (_: Exception) {
            throw Exception("Failed to fetch session data")
        }

        val hosterSelection = preferences.hostersPref
        val preferredServer = preferences.serverPref

        val serversToQuery = if (preferredServer in hosterSelection) {
            val priorityServer = HOSTERS.first { it.name == preferredServer }
            val otherServers = HOSTERS.filter { it.name != preferredServer && it.name in hosterSelection }
            listOf(priorityServer) + otherServers
        } else {
            HOSTERS.filter { it.name in hosterSelection }
        }

        val videoList = serversToQuery.parallelCatchingFlatMap { hoster ->
            val payload = listOf(
                VideoRequestDto(
                    mediaId = episodeData.tmdbId,
                    mediaType = episodeData.type,
                    tvSlug = if (episodeData.type == "tv") "${episodeData.season}-${episodeData.episode}" else "",
                    source = hoster.key,
                    sessionId = session.sessionId,
                ),
            )

            val requestBody = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
            val requestUrl = "$mappleApi/watch/${episodeData.type}/${episodeData.tmdbId}"

            val headers = headers.newBuilder()
                .add("Next-Action", session.nextAction) // Necessary header
                .build()

            val responseText = client.newCall(POST(requestUrl, headers, requestBody))
                .awaitSuccess()
                .use { it.body.string() }

            // Handle JSONP-like response: 1:{...}
            val dataLine = responseText.lines().find { it.startsWith("1:") }?.substringAfter("1:")
                ?: return@parallelCatchingFlatMap emptyList()

            val videoResponse = runCatching {
                json.decodeFromString<VideoResponseDto>(dataLine)
            }.getOrNull()

            if (videoResponse == null || !videoResponse.success || videoResponse.data == null) {
                return@parallelCatchingFlatMap emptyList()
            }

            val streamUrl = videoResponse.data.streamUrl
            val masterHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/") // Necessary header
                .build()

            // Fetch subtitles from Wyzie
            val subtitles = getSubtitles(episodeData)

            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                videoNameGen = { quality -> "${hoster.name} - $quality" },
                subtitleList = subtitles,
                masterHeaders = masterHeaders,
                videoHeaders = masterHeaders,
            )
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val qualityValues = QUALITY_VALUES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server) }
                .thenByDescending { video -> qualityValues.indexOfFirst { video.quality.contains(it) } },
        )
    }

    private suspend fun getSubtitles(data: EpisodeData): List<Track> {
        val url = if (data.type == "movie") {
            "$subtitleApi/search?id=${data.tmdbId}"
        } else {
            "$subtitleApi/search?id=${data.tmdbId}&season=${data.season}&episode=${data.episode}"
        }

        return try {
            val subLimit = preferences.subLimitPref.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()
            val preferredSubLang = preferences.subLangPref

            val subtitles = client.newCall(GET(url, headers))
                .awaitSuccess().use { it.parseAs<List<SubtitleDto>>() }
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

    // ============================== Settings ==============================
    private val SharedPreferences.domainPref by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    private val SharedPreferences.latestPref by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.subLimitPref by preferences.delegate(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.hostersPref by preferences.delegate(PREF_HOSTERS_KEY, DEFAULT_ENABLED_HOSTERS)

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
        val animeList = pageDto.results.map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType ?: if (media.title != null) "movie" else "tv"
        url = "/$type/${media.id}"
        thumbnail_url = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun parseStatus(status: String?): Int = when (status) {
        "Released", "Ended" -> SAnime.COMPLETED
        "Returning Series", "In Production" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr ?: "")?.time ?: 0L
    }.getOrDefault(0L)

    companion object {
        private val animeUrlRegex = Regex("""/(tv|movie)/(\d+)""")

        private const val TMDB_API_KEY = BuildConfig.TMDB_API

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://mapple.uk"
        private val DOMAIN_ENTRIES = arrayOf("mapple.uk", "mappl.tv", "mapple.mov")
        private val DOMAIN_VALUES = arrayOf("https://mapple.uk", "https://mappl.tv", "https://mapple.mov")

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

        private val HOSTERS = listOf(
            Hoster("Mapple", "mapple"),
            Hoster("Sakura", "sakura"),
            Hoster("Pinecone", "alfa"),
            Hoster("Oak", "oak"),
            Hoster("Willow", "wiggles"),
        )

        // First 3: Mapple, Sakura, Pinecone
        private val DEFAULT_ENABLED_HOSTERS = HOSTERS.take(3).map { it.name }.toSet()

        private data class Hoster(val name: String, val key: String)

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_DEFAULT = HOSTERS.first().name // "Mapple"

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
