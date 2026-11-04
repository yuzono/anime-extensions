package eu.kanade.tachiyomi.animeextension.en.shuttletv

import android.content.SharedPreferences
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ShuttleTv :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val client = network.client.newBuilder()
        .rateLimit(4, 1.seconds) { it.host == "cinesrc.st" }
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Public TMDB API — the same key shuttletv.su ships in its own bundle.
    private val apiUrl = "https://api.themoviedb.org/3"

    private fun tmdbUrl(builder: okhttp3.HttpUrl.Builder.() -> Unit): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", TMDB_API_KEY)
            .apply(builder)
            .build()
        return GET(url)
    }

    override val disableRelatedAnimesBySearch = true

    override val supportsLatest = true

    private val extractor by lazy { ShuttleTvExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = tmdbUrl {
        addPathSegment("trending")
        addPathSegment("all")
        addPathSegment("week")
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

        return types.parallelMapNotNull { mediaType ->
            client.newCall(latestUpdatesRequest(page, mediaType))
                .awaitSuccess()
                .use { latestUpdatesParse(it) }
        }.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val sortBy = if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
        val dateField = if (mediaType == "movie") "primary_release_date.lte" else "first_air_date.lte"

        return tmdbUrl {
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
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host.removePrefix("www.") !in BASE_HOSTS) {
                throw Exception("Unsupported url")
            }
            val rawId = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            if (url.pathSegments.getOrNull(0) != "watch") throw Exception("Unsupported url")
            // Movie links have no s/e
            val season = url.queryParameter("s")?.toIntOrNull()
            val episode = url.queryParameter("e")?.toIntOrNull()
            val idQuery = if (season != null && episode != null) {
                "$PREFIX_ID$rawId:$season:$episode"
            } else {
                "$PREFIX_ID$rawId"
            }
            return getSearchAnime(page, idQuery, filters)
        }

        // Deep link, resolved from the URL above: "id:<tmdbId>[:<season>:<episode>]".
        // Season/episode params imply TV; without them the id is usually a
        // movie, but IDs can collide — fall back to the other type.
        if (query.startsWith(PREFIX_ID)) {
            val parts = query.substringAfter(PREFIX_ID).split(":")
            if (parts.size !in 1..3 || parts[0].toIntOrNull() == null ||
                (parts.size == 3 && (parts[1].toIntOrNull() == null || parts[2].toIntOrNull() == null))
            ) {
                return AnimesPage(emptyList(), false)
            }
            val rawId = parts[0]
            val isTv = parts.size == 3

            suspend fun details(animeUrl: String): List<SAnime> {
                val tempAnime = SAnime.create().apply { this.url = animeUrl }
                return getAnimeDetails(tempAnime).apply { this.url = animeUrl }.let(::listOf)
            }

            return runCatching {
                if (isTv) details("/tv/$rawId") else details("/movie/$rawId")
            }.recoverCatching { e ->
                if (e is CancellationException) throw e
                if (isTv) details("/movie/$rawId") else details("/tv/$rawId")
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val typeIndex = filters.firstInstanceOrNull<Filters.TypeFilter>()
            ?.state ?: Filters.TYPE_ALL
        val isAnimes = typeIndex == Filters.TYPE_ANIMES
        val isAll = typeIndex == Filters.TYPE_ALL

        val rawPages: List<PageDto<MediaItemDto>> = if (query.isNotBlank() && isAll) {
            listOf(fetchMediaPage(textSearchRequest(page, query, "multi")))
        } else {
            val mediaTypes: List<String> = when (typeIndex) {
                Filters.TYPE_MOVIES -> listOf("movie")
                Filters.TYPE_TV -> listOf("tv")
                Filters.TYPE_ANIMES -> listOf("movie", "tv")
                else -> if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            }
            mediaTypes.parallelMapNotNull { mediaType ->
                val request = when {
                    query.isNotBlank() && isAnimes ->
                        discoverRequest(page, mediaType, filters, animesOnly = true, textQuery = query)
                    query.isNotBlank() -> textSearchRequest(page, query, mediaType)
                    else -> discoverRequest(page, mediaType, filters, animesOnly = isAnimes)
                }
                request?.let { fetchMediaPage(it) }
            }
        }

        var items = if (rawPages.size > 1 && query.isBlank()) {
            // Sort applies only to browse
            val sortIndex = filters.firstInstanceOrNull<Filters.SortFilter>()
                ?.state ?: Filters.SORT_POPULAR
            mergeBySort(rawPages.flatMap { it.results }, sortIndex)
        } else {
            rawPages.flatMap { it.results }
        }
        if (query.isNotBlank() && isAll) {
            items = items.filter { it.mediaType == "movie" || it.mediaType == "tv" }
        }
        if (isAnimes) items = items.filter(::isLikelyAnime)
        val hasNextPage = rawPages.any { it.page < it.totalPages }

        return AnimesPage(items.map(::mediaItemToSAnime), hasNextPage)
    }

    private suspend fun fetchMediaPage(request: Request): PageDto<MediaItemDto> = client.newCall(request).awaitSuccess()
        .parseAs<PageDto<MediaItemDto>>()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used; getSearchAnime is overridden")

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    private fun textSearchRequest(page: Int, query: String, mediaType: String): Request = tmdbUrl {
        addPathSegment("search")
        addPathSegment(mediaType)
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
        addQueryParameter("query", query)
    }

    private fun discoverRequest(
        page: Int,
        mediaType: String,
        filters: AnimeFilterList,
        animesOnly: Boolean,
        textQuery: String? = null,
    ): Request? {
        val isTextSearch = textQuery != null
        val sortIndex = if (isTextSearch) {
            Filters.SORT_POPULAR
        } else {
            filters.firstInstanceOrNull<Filters.SortFilter>()
                ?.state ?: Filters.SORT_POPULAR
        }
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
        val selectedGenres = if (isTextSearch) {
            emptyList()
        } else {
            filters.firstInstanceOrNull<Filters.GenreFilter>()
                ?.state
                ?.filter { it.state }
                .orEmpty()
        }
        val mappedGenres = selectedGenres.mapNotNull { genreMap[it.name] }
        if (selectedGenres.isNotEmpty() && mappedGenres.size < selectedGenres.size) return null
        val genreIds = if (animesOnly) (listOf("16") + mappedGenres).distinct() else mappedGenres
        val genreParam = genreIds.joinToString(",")

        val providers = if (isTextSearch) {
            ""
        } else {
            filters.firstInstanceOrNull<Filters.WatchProviderFilter>()
                ?.state
                ?.filter { it.state }
                ?.joinToString("|") { it.id }
                .orEmpty()
        }

        return tmdbUrl {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (textQuery != null) addQueryParameter("with_text_query", textQuery)
            if (genreParam.isNotBlank()) addQueryParameter("with_genres", genreParam)
            if (animesOnly) addQueryParameter("with_original_language", "ja")
            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US")
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

    private fun today(): String = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.format(Date())
    }

    private fun isLikelyAnime(item: MediaItemDto): Boolean {
        val isJapanese = item.originalLanguage == "ja" || "JP" in item.originCountries
        return isJapanese && ANIMATION_GENRE_ID in item.genreIds
    }

    // ============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== Details ==============================
    override fun getAnimeUrl(anime: SAnime): String {
        // Details page format: https://shuttletv.su/?media=movie-1294189
        // (TV uses "series", not "tv" — the site ignores unknown media
        // params and falls back to the homepage hero.)
        val (type, id) = animeUrlToId(anime)
        val mediaType = if (type == "tv") "series" else "movie"
        return "$baseUrl/?media=$mediaType-$id"
    }

    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        return tmdbUrl {
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
        return tmdbUrl {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = parseMediaPage(response).animes

    // ============================== Seasons ===============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val response = client.get(animeDetailsRequest(anime).url.toString())
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

                val seasonResponse = client.get(
                    animeDetailsRequest(anime).url.newBuilder()
                        .removeAllQueryParameters("append_to_response")
                        .addPathSegments("season/$seasonNum")
                        .build()
                        .toString(),
                )
                val seasonDetail = seasonResponse.parseAs<TvSeasonDetailDto>()

                seasonDetail.episodes.reversed().map { episode ->
                    SEpisode.create().apply {
                        name = "E${episode.episodeNumber} - ${episode.name}"
                        episode_number = episode.episodeNumber.toFloat()
                        scanlator = "Season $seasonNum"
                        date_upload = parseDate(episode.airDate)
                        summary = episode.overview?.takeIf { it.isNotBlank() }
                        preview_url = episode.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        url = "tv/$tvId/$seasonNum/${episode.episodeNumber}"
                    }
                }
            }
            parts.firstOrNull() == "tv" -> {
                val tvId = parts[1]
                val tvResponse = client.get(animeDetailsRequest(anime).url.toString())
                val tv = tvResponse.parseAs<TvDetailDto>()

                coroutineScope {
                    tv.seasons
                        .filter { it.seasonNumber > 0 }
                        .map { season ->
                            async {
                                val seasonDetail = client.get(
                                    "$apiUrl/tv/$tvId/season/${season.seasonNumber}?api_key=$TMDB_API_KEY",
                                ).parseAs<TvSeasonDetailDto>()
                                seasonDetail.episodes.reversed().map { episode ->
                                    SEpisode.create().apply {
                                        name = "E${episode.episodeNumber} - ${episode.name}"
                                        episode_number = episode.episodeNumber.toFloat()
                                        scanlator = "Season ${season.seasonNumber}"
                                        date_upload = parseDate(episode.airDate)
                                        summary = episode.overview?.takeIf { it.isNotBlank() }
                                        preview_url = episode.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                                        url = "tv/$tvId/${season.seasonNumber}/${episode.episodeNumber}"
                                    }
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
                val response = client.get(animeDetailsRequest(anime).url.toString())
                val movie = response.parseAs<MovieDetailDto>()
                listOf(
                    SEpisode.create().apply {
                        name = "Movie"
                        episode_number = 1.0f
                        date_upload = parseDate(movie.releaseDate)
                        summary = movie.overview?.takeIf { it.isNotBlank() }
                        preview_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        url = "movie/${movie.id}"
                    },
                )
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================= Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val path = episode.url.substringBefore("#")
        val enabledServers = preferences.enabledServerIds
        val preferredServer = preferences.preferredServerId

        // One hoster per server. The preferred server moves first; the rest
        // keep the player's own order.
        return extractor.getProviders(path)
            .filter { it.id in enabledServers }
            .sortedByDescending { it.id == preferredServer }
            .map { provider ->
                Hoster(
                    hosterName = ShuttleTvExtractor.cleanName(provider),
                    internalData = "$path|||${provider.id}",
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    // ============================ Video Links ============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val (path, providerId) = hoster.internalData.split("|||", limit = 2)
        val videos = extractor.videosFor(path, providerId)
        if (videos.isEmpty()) return emptyList()

        // Sort by the preferred quality; flag the best match on the
        // preferred server — the player auto-plays the first completed
        // hoster that carries a preferred-flagged video.
        val isPreferredServer = providerId == preferences.preferredServerId
        val prefQuality = preferences.qualityPref.toIntOrNull() ?: PREF_QUALITY_DEFAULT.toInt()

        return videos
            .sortedWith(
                compareByDescending<Video> { qualityOf(it) == prefQuality }
                    .thenByDescending(::qualityOf),
            )
            .mapIndexed { index, video ->
                if (isPreferredServer && index == 0) video.copy(preferred = true) else video
            }
    }

    private fun qualityOf(video: Video): Int {
        if (video.videoTitle.contains("4k", ignoreCase = true)) return 2160
        return QUALITY_REGEX.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val parts = episode.url.substringBefore("#").split('/')
        return if (parts.firstOrNull() == "tv") {
            "$baseUrl/watch/${parts[1]}?s=${parts[2]}&e=${parts[3]}"
        } else {
            "$baseUrl/watch/${parts[1]}"
        }
    }

    // ============================== Settings ==============================
    private val SharedPreferences.latestPref by preferences.delegate(
        PREF_LATEST_KEY,
        PREF_LATEST_DEFAULT,
    )
    private val SharedPreferences.qualityPref by preferences.delegate(
        PREF_QUALITY_KEY,
        PREF_QUALITY_DEFAULT,
    )
    private val SharedPreferences.preferredServerId by preferences.delegate(
        PREF_SERVER_PREF_KEY,
        PREF_SERVER_PREF_DEFAULT,
    )
    private val SharedPreferences.enabledServerIds: Set<String> by preferences.delegate(
        PREF_SERVERS_KEY,
        DEFAULT_SERVER_IDS,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverNames = ShuttleTvExtractor.SERVER_CATALOG.map { ShuttleTvExtractor.cleanName(it) }
        val serverIds = ShuttleTvExtractor.SERVER_CATALOG.map { it.id }

        // Drop servers removed from the catalog; restore defaults if nothing
        // valid remains (catalog pruning happens occasionally).
        val storedServers = preferences.getStringSet(PREF_SERVERS_KEY, null)
        if (storedServers != null) {
            val knownServers = serverIds.toSet()
            val validServers = storedServers.intersect(knownServers)
            if (validServers != storedServers) {
                val healed = validServers.ifEmpty { DEFAULT_SERVER_IDS }
                preferences.edit().putStringSet(PREF_SERVERS_KEY, healed).apply()
            }
        }

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
            entries = listOf("2160p", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_PREF_KEY,
            title = "Preferred Server",
            entries = serverNames,
            entryValues = serverIds,
            default = PREF_SERVER_PREF_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Servers",
            entries = serverNames,
            entryValues = serverIds,
            default = DEFAULT_SERVER_IDS,
            summary = "Select servers to show for each episode",
        )
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results
            .filter { it.mediaType == null || it.mediaType == "movie" || it.mediaType == "tv" }
            .map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mergeBySort(items: List<MediaItemDto>, sortIndex: Int): List<MediaItemDto> = when (sortIndex) {
        Filters.SORT_RATING -> items.sortedByDescending { it.voteAverage }
        Filters.SORT_RECENT -> items.sortedByDescending { itemSortDate(it) }
        else -> items.sortedByDescending { it.popularity }
    }

    private fun itemSortDate(item: MediaItemDto): Long = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.tryParse(item.releaseDate ?: item.firstAirDate)
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

        private val animeUrlRegex = Regex("""/(movie|tv)/(\d+)""")

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private val BASE_HOSTS = setOf(
            "shuttletv.su",
            "shuttletv.pk",
            "shuttletv.me",
            "shuttletv.cr",
        )

        private const val ANIMATION_GENRE_ID = 16

        // Rating sort uses a higher floor: TMDB has many obscure titles
        // with a handful of perfect-score votes that would dominate.
        private const val MIN_VOTES_FOR_RATING_SORT = "200"
        private const val MIN_VOTES_FOR_RECENT_SORT = "50"

        // Same key the ShuttleTV site bundle uses for its own TMDB calls.
        private const val TMDB_API_KEY = "ea021b3b0775c8531592713ab727f254"

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_PREF_KEY = "pref_server"
        private const val PREF_SERVER_PREF_DEFAULT = "nebula"

        private val QUALITY_REGEX = Regex("""(\d{3,4})[pP]?""")

        private const val PREF_SERVERS_KEY = "pref_servers"
        private val DEFAULT_SERVER_IDS = ShuttleTvExtractor.SERVER_CATALOG.map { it.id }.toSet()
    }
}
