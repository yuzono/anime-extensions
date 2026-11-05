package eu.kanade.tachiyomi.animeextension.en.cinejoy

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Cinejoy :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val client = network.client.newBuilder()
        .rateLimit(3, 1.seconds) { it.host.endsWith("wing.st") }
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Cinejoy catalogs through TMDB directly (same API key the site uses).
    private val apiUrl = "https://api.themoviedb.org/3"

    override val disableRelatedAnimesBySearch = true

    override val supportsLatest = true

    private val extractor by lazy { CinejoyExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = tmdbRequest {
        addPathSegment("trending")
        addPathSegment("all")
        addPathSegment("week")
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = listOf("movie", "tv")

        val rawPages = types.parallelMap { mediaType ->
            client.newCall(latestUpdatesRequest(page, mediaType))
                .awaitSuccess()
                .parseAs<PageDto<MediaItemDto>>()
        }

        val animes = rawPages.flatMap { it.results }
            .sortedByDescending { it.mediaSortDate }
        val hasNextPage = rawPages.any { it.page < it.totalPages }
        return AnimesPage(animes.map(::mediaItemToSAnime), hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val sortBy = if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
        val dateField = if (mediaType == "movie") "primary_release_date.lte" else "first_air_date.lte"

        return tmdbRequest {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter(dateField, today())
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) {
                throw Exception("Unsupported url")
            }
            val deepLink = cinejoyUrlToQuery(url)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, deepLink, filters)
        }

        // Deep link, resolved from the URLs above: "id:<type>/<id>[/season/<n>]"
        if (query.startsWith(PREFIX_ID)) {
            val rawPath = query.substringAfter(PREFIX_ID)
            if (!DEEP_LINK_PATH_REGEX.matches(rawPath)) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$rawPath"
            val tempAnime = SAnime.create().apply { this.url = url }
            return runCatching {
                getAnimeDetails(tempAnime).apply {
                    this.url = url
                    if ("/season/" in url) {
                        fetch_type = FetchType.Episodes
                    }
                }.let(::listOf)
            }.onFailure {
                if (it is CancellationException) throw it
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val typeIndex = filters.filterIsInstance<Filters.TypeFilter>()
            .firstOrNull()?.state ?: Filters.TYPE_ALL
        val isAll = typeIndex == Filters.TYPE_ALL
        val sortIndex = filters.filterIsInstance<Filters.SortFilter>()
            .firstOrNull()?.state ?: Filters.SORT_POPULAR

        val isMultiSearch = query.isNotBlank() && isAll
        val rawPages: List<PageDto<MediaItemDto>> = if (isMultiSearch) {
            listOf(fetchMediaPage(textSearchRequest(page, query, "multi")))
        } else {
            val mediaTypes: List<String> = when (typeIndex) {
                Filters.TYPE_MOVIES -> listOf("movie")
                Filters.TYPE_TV -> listOf("tv")
                else -> listOf("movie", "tv")
            }
            val requests = mediaTypes.mapNotNull { mediaType ->
                val request = if (query.isNotBlank()) {
                    textSearchRequest(page, query, mediaType)
                } else {
                    discoverRequest(page, mediaType, filters, sortIndex)
                        ?: return@mapNotNull null
                }
                request
            }

            var lastError: Throwable? = null
            val pages = requests.parallelMapNotNull { request ->
                try {
                    fetchMediaPage(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    synchronized(requests) {
                        lastError = e
                    }
                    null
                }
            }
            if (pages.isEmpty() && requests.isNotEmpty()) {
                lastError?.let { throw it }
            }
            pages
        }

        val results = rawPages.flatMap { it.results }
            .let { if (isMultiSearch) it.filter { media -> media.mediaType == "movie" || media.mediaType == "tv" } else it }
            .let { if (query.isBlank()) it.sortedWith(discoverComparator(sortIndex)) else it }
        val hasNextPage = rawPages.any { it.page < it.totalPages }

        return AnimesPage(results.map(::mediaItemToSAnime), hasNextPage)
    }

    private suspend fun fetchMediaPage(request: Request): PageDto<MediaItemDto> = client.newCall(request).awaitSuccess().parseAs<PageDto<MediaItemDto>>()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used; getSearchAnime is overridden")

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    private fun textSearchRequest(page: Int, query: String, mediaType: String): Request = tmdbRequest {
        addPathSegment("search")
        addPathSegment(mediaType)
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
        if (mediaType == "multi") addQueryParameter("include_adult", "false")
        addQueryParameter("query", query)
    }

    private fun discoverRequest(
        page: Int,
        mediaType: String,
        filters: AnimeFilterList,
        sortIndex: Int,
    ): Request? {
        val sortBy = when (sortIndex) {
            Filters.SORT_RATING -> "vote_average.desc"
            Filters.SORT_RECENT -> if (mediaType == "movie") {
                "primary_release_date.desc"
            } else {
                "first_air_date.desc"
            }
            else -> "popularity.desc"
        }

        val genreMap = if (mediaType == "movie") {
            Filters.MOVIE_GENRE_MAP
        } else {
            Filters.TV_GENRE_MAP
        }
        val selectedGenres = filters.filterIsInstance<Filters.GenreFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            .orEmpty()
        if (selectedGenres.any { genreMap[it.name] == null }) return null
        val genreIds = selectedGenres.map { genreMap[it.name]!! }
        val genreParam = genreIds.joinToString(",")

        val year = filters.filterIsInstance<Filters.YearFilter>()
            .firstOrNull()?.state?.trim().orEmpty()
            .takeIf { it.matches(YEAR_REGEX) }

        return tmdbRequest {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genreParam.isNotBlank()) addQueryParameter("with_genres", genreParam)
            if (year != null) {
                addQueryParameter(
                    if (mediaType == "movie") "primary_release_year" else "first_air_date_year",
                    year,
                )
            }
            when (sortIndex) {
                Filters.SORT_RATING -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RATING_SORT)
                }
                Filters.SORT_RECENT -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RECENT_SORT)
                    val dateField = if (mediaType == "movie") {
                        "primary_release_date.lte"
                    } else {
                        "first_air_date.lte"
                    }
                    addQueryParameter(dateField, today())
                }
            }
        }
    }

    /** Mirrors the `sort_by` request in [discoverRequest] for merging pages. */
    private fun discoverComparator(sortIndex: Int): Comparator<MediaItemDto> = when (sortIndex) {
        Filters.SORT_RATING -> compareByDescending { it.voteAverage }
        Filters.SORT_RECENT -> compareByDescending { it.mediaSortDate }
        else -> compareByDescending { it.popularity }
    }

    private fun today(): String = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.format(Date())
    }

    /**
     * Maps a Cinejoy url to a deep-link query:
     *   /movie/{id}-{slug}-{year}      → id:movie/{id}
     *   /series/{id}-{slug}-{year}     → id:tv/{id}
     *   /watch/movie/{id}              → id:movie/{id}
     *   /watch/tv/{id}/{season}/{ep}   → id:tv/{id}/season/{season}
     */
    private fun cinejoyUrlToQuery(url: HttpUrl): String? {
        val segs = url.pathSegments
        return when (segs.getOrNull(0)) {
            "movie" -> segs.getOrNull(1)?.let { "${PREFIX_ID}movie/${it.substringBefore("-")}" }
            "series" -> segs.getOrNull(1)?.let { "${PREFIX_ID}tv/${it.substringBefore("-")}" }
            "watch" -> when (segs.getOrNull(1)) {
                "movie" -> segs.getOrNull(2)?.let { "${PREFIX_ID}movie/$it" }
                "tv" -> segs.getOrNull(2)?.let { id ->
                    "${PREFIX_ID}tv/$id/season/${segs.getOrNull(3) ?: "1"}"
                }
                else -> null
            }
            else -> null
        }
    }

    // ============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== Details ==============================
    override fun getAnimeUrl(anime: SAnime): String {
        val path = if ("/season/" in anime.url) {
            anime.url.substringBefore("/season/")
        } else {
            anime.url
        }
        // The site uses /series/ for TV.
        return "$baseUrl${path.replaceFirst("/tv/", "/series/")}"
    }

    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        return tmdbRequest {
            addPathSegment(type)
            addPathSegment(id)
            addQueryParameter("append_to_response", "external_ids")
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = try {
        if ("/movie/" in response.request.url.toString()) {
            movieDetailsParse(response)
        } else {
            tvDetailsParse(response)
        }
    } catch (e: Exception) {
        throw Exception("Failed to parse details.", e)
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
            background_url = movie.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            fetch_type = FetchType.Episodes
            initialized = true
            description = buildString {
                movie.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    movie.originalTitle?.takeIf {
                        it.isNotBlank() && it.trim() != movie.title.trim()
                    }?.let { "**Original Title:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
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
            background_url = tv.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            fetch_type = FetchType.Seasons
            initialized = true
            description = buildString {
                tv.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf {
                        it.isNotBlank() && it.trim() != tv.name.trim()
                    }?.let { "**Original Name:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
            }
        }
    }

    // ========================== Related Titles ============================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        return tmdbRequest {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = parseMediaPage(response).animes

    // ============================== Seasons ===============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val tv = response.parseAs<TvDetailDto>()

        return tv.seasons
            .filter { it.seasonNumber > 0 }
            .map { season ->
                SAnime.create().apply {
                    title = season.name.ifBlank { "Season ${season.seasonNumber}" }
                    url = "/tv/${tv.id}/season/${season.seasonNumber}"
                    thumbnail_url = season.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        ?: tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    description = season.overview?.takeIf { it.isNotBlank() } ?: tv.overview
                    season_number = season.seasonNumber.toDouble()
                    fetch_type = FetchType.Episodes
                    status = parseStatus(tv.status)
                    genre = tv.genres.joinToString { it.name }
                    author = tv.productionCompanies.joinToString { it.name }
                    artist = tv.networks.joinToString { it.name }
                    initialized = true
                }
            }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val path = anime.url.trimStart('/')
        val parts = path.split('/')

        return when {
            parts.firstOrNull() == "tv" && "season" in parts -> {
                val tvId = parts[1]
                val seasonNum = parts.last()
                val tv = client.newCall(
                    tmdbRequest {
                        addPathSegment("tv")
                        addPathSegment(tvId)
                        addQueryParameter("append_to_response", "external_ids")
                    },
                ).awaitSuccess().parseAs<TvDetailDto>()
                val extraData = Triple(
                    tv.name,
                    tv.firstAirDate?.take(4) ?: "",
                    tv.externalIds?.imdbId ?: "",
                )
                val extraDataEncoded = extraData.toJsonString()

                val seasonDetail = client.newCall(
                    tmdbRequest {
                        addPathSegment("tv")
                        addPathSegment(tvId)
                        addPathSegment("season")
                        addPathSegment(seasonNum)
                    },
                ).awaitSuccess().parseAs<TvSeasonDetailDto>()

                seasonDetail.episodes.reversed().map { episode ->
                    SEpisode.create().apply {
                        name = "E${episode.episodeNumber} - ${episode.name}"
                        episode_number = episode.episodeNumber.toFloat()
                        scanlator = "Season $seasonNum"
                        date_upload = parseDate(episode.airDate)
                        summary = episode.overview?.takeIf { it.isNotBlank() }
                        preview_url = episode.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        url = "tv/$tvId/$seasonNum/${episode.episodeNumber}#$extraDataEncoded"
                    }
                }
            }
            parts.firstOrNull() == "tv" -> {
                val tvId = parts[1]
                val tv = client.newCall(
                    tmdbRequest {
                        addPathSegment("tv")
                        addPathSegment(tvId)
                        addQueryParameter("append_to_response", "external_ids")
                    },
                ).awaitSuccess().parseAs<TvDetailDto>()
                val extraData = Triple(
                    tv.name,
                    tv.firstAirDate?.take(4) ?: "",
                    tv.externalIds?.imdbId ?: "",
                )
                val extraDataEncoded = extraData.toJsonString()

                coroutineScope {
                    tv.seasons
                        .filter { it.seasonNumber > 0 }
                        .map { season ->
                            async {
                                try {
                                    val seasonDetail = client.newCall(
                                        tmdbRequest {
                                            addPathSegment("tv")
                                            addPathSegment(tvId)
                                            addPathSegment("season")
                                            addPathSegment(season.seasonNumber.toString())
                                        },
                                    ).awaitSuccess().parseAs<TvSeasonDetailDto>()
                                    seasonDetail.episodes.reversed().map { episode ->
                                        SEpisode.create().apply {
                                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                                            episode_number = episode.episodeNumber.toFloat()
                                            scanlator = "Season ${season.seasonNumber}"
                                            date_upload = parseDate(episode.airDate)
                                            summary = episode.overview?.takeIf { it.isNotBlank() }
                                            preview_url = episode.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                                            url = "tv/$tvId/${season.seasonNumber}/${episode.episodeNumber}#$extraDataEncoded"
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                }.sortedWith(
                    compareByDescending<SEpisode> {
                        it.scanlator?.substringAfter(" ")?.toIntOrNull()
                    }.thenByDescending { it.episode_number },
                )
            }
            else -> {
                val movie = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
                    .parseAs<MovieDetailDto>()
                val extraData = Triple(
                    movie.title,
                    movie.releaseDate?.take(4) ?: "",
                    movie.externalIds?.imdbId ?: "",
                )
                val extraDataEncoded = extraData.toJsonString()
                listOf(
                    SEpisode.create().apply {
                        name = "Movie"
                        episode_number = 1.0f
                        date_upload = parseDate(movie.releaseDate)
                        summary = movie.overview?.takeIf { it.isNotBlank() }
                        preview_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        url = "movie/${movie.id}#$extraDataEncoded"
                    },
                )
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    // ============================= Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (path, extraDataEncoded) = episode.url.split("#", limit = 2)
        val enabledServers = preferences.enabledServerNames
        val preferredServer = preferences.preferredServerName

        return wingServers()
            .filter { it.name in enabledServers }
            // Stable sort: the preferred server moves first, the rest keep
            // their API order.
            .sortedByDescending { it.name == preferredServer }
            .map { server ->
                Hoster(
                    hosterName = server.displayName,
                    internalData = "${server.name}|||$path|||$extraDataEncoded",
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    /**
     * Fetches the wing.st server list, cached with a TTL; falls back to the
     * hardcoded catalog when the call fails (servers rarely change).
     */
    private suspend fun wingServers(): List<WingServerInfo> {
        val now = System.currentTimeMillis()
        synchronized(serverCacheLock) {
            serverCache?.takeIf { it.expiresAtMillis > now }?.let { return it.servers }
        }

        // Only the fallback catalog is cached on genuine failures; a
        // canceled call rethrows instead of poisoning the cache for ten
        // minutes.
        val servers = try {
            client.get("$WING_API_BASE/servers", headers)
                .parseAs<WingServersDto>()
                .servers
                .filter { it.status == null || it.status == "ok" }
                .map { WingServerInfo(it.name, it.fourK) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DEFAULT_SERVERS
        }

        synchronized(serverCacheLock) {
            serverCache = ServerCache(servers, now + SERVER_CACHE_TTL_MS)
        }
        return servers
    }

    private data class ServerCache(
        val servers: List<WingServerInfo>,
        val expiresAtMillis: Long,
    )

    @Volatile
    private var serverCache: ServerCache? = null
    private val serverCacheLock = Any()

    // ============================ Video Links ============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("|||", limit = 3)
        if (parts.size < 3) return emptyList()

        val serverName = parts[0]
        val path = parts[1]
        val extraDataEncoded = parts[2]

        val (title, year, imdbId) = extraDataEncoded.parseAs<Triple<String, String, String>>()

        val videoList = extractor.videosForServer(
            server = WingServerInfo(serverName),
            path = path,
            title = title,
            year = year,
            imdbId = imdbId,
            subLimit = preferences.subLimitPref.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: PREF_SUB_LIMIT_DEFAULT.toInt(),
        )

        val prefInt = preferences.qualityPref.toIntOrNull() ?: 1080
        val isPreferredServer = preferences.preferredServerName == serverName

        return videoList.sortedWith(
            compareByDescending<Video> { qualityOf(it) == prefInt }
                .thenByDescending { qualityOf(it) },
        ).map { v ->
            if (isPreferredServer && qualityOf(v) == prefInt) v.copy(preferred = true) else v
        }
    }

    private fun qualityOf(video: Video): Int = qualityRegex.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
        ?: if (video.videoTitle.contains("4k", ignoreCase = true)) 2160 else 0

    override fun getEpisodeUrl(episode: SEpisode): String {
        val path = episode.url.substringBefore("#")
        val parts = path.split("/")
        return when (parts.firstOrNull()) {
            "movie" -> "$baseUrl/movie/${parts[1]}"
            "tv" -> "$baseUrl/watch/tv/${parts[1]}/${parts[2]}/${parts[3]}"
            else -> baseUrl
        }
    }

    // ============================== Settings ==============================
    private val SharedPreferences.qualityPref by preferences.delegate(
        PREF_QUALITY_KEY,
        PREF_QUALITY_DEFAULT,
    )
    private val SharedPreferences.preferredServerName by preferences.delegate(
        PREF_SERVER_KEY,
        PREF_SERVER_DEFAULT,
    )
    private val SharedPreferences.enabledServerNames: Set<String> by preferences.delegate(
        PREF_SERVERS_KEY,
        PREF_SERVERS_DEFAULT,
    )
    private val SharedPreferences.subLimitPref by preferences.delegate(
        PREF_SUB_LIMIT_KEY,
        PREF_SUB_LIMIT_DEFAULT,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("2160p", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        // Display capitalized names; persist bare API names so the hoster
        // code can match them as keys.
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = listOf("None") + SERVER_DISPLAY_NAMES,
            entryValues = listOf("") + SERVER_NAMES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Servers",
            entries = SERVER_DISPLAY_NAMES,
            entryValues = SERVER_NAMES,
            default = PREF_SERVERS_DEFAULT,
            summary = "Select servers to enable.",
        )

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = "Limit subtitle count. Current: ${preferences.subLimitPref}",
            getSummary = { "Limit subtitle count. Current: $it" },
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
            onChange = { _, newValue ->
                val n = newValue.toIntOrNull()
                (n != null && n >= 0)
            },
        )
    }

    // ============================= Utilities ==============================
    private fun tmdbRequest(block: HttpUrl.Builder.() -> Unit): Request = GET(
        apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", TMDB_API_KEY)
            .apply(block)
            .build(),
        headers,
    )

    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results
            .filter { it.mediaType != "person" }
            .map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType
            ?: if (media.title != null) "movie" else "tv"
        url = "/$type/${media.id}"
        thumbnail_url = media.posterPath
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        fetch_type = if (type == "tv") FetchType.Seasons else FetchType.Episodes
    }

    private fun parseStatus(status: String?): Int = when (status) {
        "Released", "Ended" -> SAnime.COMPLETED
        "Returning Series", "In Production" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.tryParse(dateStr)
    }

    companion object {
        // Deep-link prefix.
        const val PREFIX_ID = "id:"

        private val DEEP_LINK_PATH_REGEX = Regex("""(movie|tv)/\d+(/season/\d+)?""")

        private val animeUrlRegex = Regex("""/(movie|tv)/(\d+)""")

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        private val YEAR_REGEX = Regex("""\d{4}""")

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

        // Rating sort uses a higher floor: TMDB has many obscure titles
        // with a handful of perfect-score votes that would dominate.
        private const val MIN_VOTES_FOR_RATING_SORT = "200"
        private const val MIN_VOTES_FOR_RECENT_SORT = "50"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = ""

        private const val PREF_SERVERS_KEY = "pref_servers"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val WING_API_BASE = "https://api.wing.st"
        private const val SERVER_CACHE_TTL_MS = 10 * 60_000L

        // Fallback when https://api.wing.st/servers is unreachable.
        private val DEFAULT_SERVERS = listOf(
            WingServerInfo("secret", mayHave4K = true),
            WingServerInfo("Nebula"),
            WingServerInfo("Lisbon", mayHave4K = true),
            WingServerInfo("Solara"),
            WingServerInfo("Athens"),
        )

        // Catalog backing the server preferences; names the dynamic
        // /servers list is expected to match.
        private val SERVER_NAMES = DEFAULT_SERVERS.map(WingServerInfo::name)
        private val SERVER_DISPLAY_NAMES = DEFAULT_SERVERS.map(WingServerInfo::displayName)
        private val PREF_SERVERS_DEFAULT = SERVER_NAMES.toSet()

        // The TMDB v3 key Cinejoy itself uses (public in the site's JS).
        private const val TMDB_API_KEY = "8476a7ab80ad76f0936744df0430e67c"
    }
}
