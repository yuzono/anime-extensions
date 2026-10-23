package eu.kanade.tachiyomi.animeextension.all.movy

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
import keiyoushi.network.rateLimit
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
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

class Movy :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Movy"

    override val client = network.client.newBuilder()
        .rateLimit(2) { it.host.contains("wecollege.net") }
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override val baseUrl
        get() = preferences.domainPref

    // Movy/Videasy proxy
    private val apiUrl = "https://db.wecollege.net/3"

    private fun apiOrigin(url: String): String = url.toHttpUrl().run { "$scheme://$host" }

    override val disableRelatedAnimesBySearch = true

    override val lang = "all"
    override val supportsLatest = true

    private val extractor by lazy { MovyExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

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

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val sortBy = if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
        val dateField = if (mediaType == "movie") "primary_release_date.lte" else "first_air_date.lte"

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter(dateField, today())
        }.build()
        return GET(url)
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
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val type = url.pathSegments.getOrNull(0)
                ?: throw Exception("Unsupported url")
            val rawId = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            if (type !in listOf("movie", "tv")) throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_ID}$type/$rawId", filters)
        }

        // Deep link, resolved from the URL above: "id:<type>/<id>"
        if (query.startsWith(PREFIX_ID)) {
            val rawPath = query.substringAfter(PREFIX_ID)
            if (!DEEP_LINK_PATH_REGEX.matches(rawPath)) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$rawPath"
            val tempAnime = SAnime.create().apply { this.url = url }
            return runCatching {
                getAnimeDetails(tempAnime).apply { this.url = url }
                    .let(::listOf)
            }.onFailure {
                if (it is CancellationException) throw it
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val typeIndex = filters.filterIsInstance<Filters.TypeFilter>()
            .firstOrNull()?.state ?: Filters.TYPE_ALL
        val isAnimes = typeIndex == Filters.TYPE_ANIMES
        val isAll = typeIndex == Filters.TYPE_ALL

        val rawPages: List<PageDto<MediaItemDto>> = if (query.isNotBlank() && isAll) {
            listOfNotNull(fetchMediaPage(textSearchRequest(page, query, "multi")))
        } else {
            val mediaTypes: List<String> = when (typeIndex) {
                Filters.TYPE_MOVIES -> listOf("movie")
                Filters.TYPE_TV, Filters.TYPE_ANIMES -> listOf("tv")
                else -> if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            }
            mediaTypes.parallelMapNotNull { mediaType ->
                val request = if (query.isNotBlank()) {
                    textSearchRequest(page, query, mediaType)
                } else {
                    discoverRequest(page, mediaType, filters, animesOnly = isAnimes)
                }
                fetchMediaPage(request)
            }
        }

        var items = rawPages.flatMap { it.results }
        if (query.isNotBlank() && isAll) {
            items = items.filter { it.mediaType == "movie" || it.mediaType == "tv" }
        }
        if (isAnimes) items = items.filter(::isLikelyAnime)
        val hasNextPage = rawPages.any { it.page < it.totalPages }

        return AnimesPage(items.map(::mediaItemToSAnime), hasNextPage)
    }

    private suspend fun fetchMediaPage(request: Request): PageDto<MediaItemDto>? = runCatching {
        client.newCall(request).awaitSuccess()
            .parseAs<PageDto<MediaItemDto>>()
    }.getOrNull()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used; getSearchAnime is overridden")

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    private fun textSearchRequest(page: Int, query: String, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
        }.build()
        return GET(url)
    }

    private fun discoverRequest(
        page: Int,
        mediaType: String,
        filters: AnimeFilterList,
        animesOnly: Boolean,
    ): Request {
        val sortIndex = filters.filterIsInstance<Filters.SortFilter>()
            .firstOrNull()?.state ?: Filters.SORT_POPULAR
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
        val userGenres = filters.filterIsInstance<Filters.GenreFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.mapNotNull { genreMap[it.name] }
            .orEmpty()
        val genreIds = if (animesOnly) (listOf("16") + userGenres).distinct() else userGenres
        val genreParam = genreIds.joinToString(",")

        val providers = filters.filterIsInstance<Filters.WatchProviderFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
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
        }.build()
        return GET(url)
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
        val path = if ("/season/" in anime.url) {
            anime.url.substringBefore("/season/")
        } else {
            anime.url
        }
        return "$baseUrl$path"
    }

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
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
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
                val tvResponse = client.get("$apiUrl/tv/$tvId?append_to_response=external_ids")
                val tv = tvResponse.parseAs<TvDetailDto>()
                val extraData = Triple(
                    tv.name,
                    tv.firstAirDate?.take(4) ?: "",
                    tv.externalIds?.imdbId ?: "",
                )
                val extraDataEncoded = extraData.toJsonString()

                val seasonResponse = client.get("$apiUrl/tv/$tvId/season/$seasonNum")
                val seasonDetail = seasonResponse.parseAs<TvSeasonDetailDto>()

                seasonDetail.episodes.reversed().map { episode ->
                    SEpisode.create().apply {
                        name = "S$seasonNum E${episode.episodeNumber} - ${episode.name}"
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
                val tvResponse = client.get("$apiUrl/tv/$tvId?append_to_response=external_ids")
                val tv = tvResponse.parseAs<TvDetailDto>()
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
                                    val seasonDetail = client.get(
                                        "$apiUrl/tv/$tvId/season/${season.seasonNumber}",
                                    ).parseAs<TvSeasonDetailDto>()
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
                val response = client.get(animeDetailsRequest(anime).url.toString())
                val movie = response.parseAs<MovieDetailDto>()
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
        val isMovie = path.startsWith("movie")

        val enabledServers = preferences.enabledServerNames
        val eligibleServers = MovyExtractor.VIDEASY_SERVERS.filter { server ->
            (!server.movieOnly || isMovie) && server.displayName in enabledServers
        }

        return eligibleServers.map { server ->
            val langLabel = server.audioLabel ?: "Original"
            Hoster(
                hosterName = "${server.displayName} ($langLabel)",
                internalData = "${server.displayName}|||$path|||$extraDataEncoded",
            )
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    // ============================ Video Links ============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("|||", limit = 3)
        if (parts.size < 3) return emptyList()

        val serverName = parts[0]
        val path = parts[1]
        val extraDataEncoded = parts[2]

        val server = MovyExtractor.VIDEASY_SERVERS.firstOrNull { it.displayName == serverName }
            ?: return emptyList()

        val (title, year, imdbId) = extraDataEncoded.parseAs<Triple<String, String, String>>()

        val videoList = extractor.videosForServer(
            server = server,
            path = path,
            title = title,
            year = year,
            imdbId = imdbId,
            baseUrl = apiOrigin(baseUrl),
            subLimit = preferences.subLimitPref.toIntOrNull()
                ?: PREF_SUB_LIMIT_DEFAULT.toInt(),
        )

        val preferredQuality = preferences.qualityPref
        val prefInt = preferredQuality.toIntOrNull() ?: 1080

        return videoList.sortedWith(
            compareByDescending<Video> { v ->
                val qVal = qualityRegex.find(v.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (v.videoTitle.contains("4k", ignoreCase = true)) 2160 else 0
                qVal == prefInt
            }.thenByDescending { v ->
                qualityRegex.find(v.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (v.videoTitle.contains("4k", ignoreCase = true)) 2160 else 0
            },
        ).map { v ->
            val qVal = qualityRegex.find(v.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (v.videoTitle.contains("4k", ignoreCase = true)) 2160 else 0
            val isPreferred = qVal == prefInt
            if (isPreferred) v.copy(preferred = true) else v
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val path = episode.url.substringBefore("#")
        return "$baseUrl/$path"
    }

    // ============================== Settings ==============================
    private val SharedPreferences.domainPref by preferences.delegate(
        PREF_DOMAIN_KEY,
        PREF_DOMAIN_DEFAULT,
    )
    private val SharedPreferences.qualityPref by preferences.delegate(
        PREF_QUALITY_KEY,
        PREF_QUALITY_DEFAULT,
    )
    private val SharedPreferences.latestPref by preferences.delegate(
        PREF_LATEST_KEY,
        PREF_LATEST_DEFAULT,
    )
    private val SharedPreferences.subLimitPref by preferences.delegate(
        PREF_SUB_LIMIT_KEY,
        PREF_SUB_LIMIT_DEFAULT,
    )
    private val SharedPreferences.enabledServerNames: Set<String> by preferences.delegate(
        PREF_SERVERS_KEY,
        PREF_SERVERS_DEFAULT,
    )

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            .removePrefix("https://")
        val invalidDomain = domain !in DOMAIN_ENTRIES

        if (invalidDomain) {
            edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
        }

        // Drop server names removed from the catalog; restore defaults
        // if nothing valid remains (catalog pruning happens occasionally).
        val storedServers = getStringSet(PREF_SERVERS_KEY, null)
        if (storedServers != null) {
            val knownServers = MovyExtractor.SERVER_DISPLAY_NAMES.toSet()
            val validServers = storedServers.intersect(knownServers)
            if (validServers != storedServers) {
                val healed = validServers.ifEmpty { PREF_SERVERS_DEFAULT }
                edit().putStringSet(PREF_SERVERS_KEY, healed).apply()
            }
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
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("2160p", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "480", "360"),
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

        // Display "Name (Language)" but persist bare display names so
        // the catalog code can match them as keys.
        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Servers",
            entries = MovyExtractor.VIDEASY_SERVERS.map { server ->
                val lang = server.audioLabel ?: "Unknown"
                "${server.displayName} ($lang)"
            },
            entryValues = MovyExtractor.SERVER_DISPLAY_NAMES,
            default = PREF_SERVERS_DEFAULT,
            summary = "Select servers to enable. Languages shown per server.",
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

    private fun parseDate(dateStr: String?): Long = runCatching {
        synchronized(DATE_FORMATTER) {
            DATE_FORMATTER.parse(dateStr ?: "")?.time ?: 0L
        }
    }.getOrDefault(0L)

    companion object {
        // Deep-link prefix.
        const val PREFIX_ID = "id:"

        private val DEEP_LINK_PATH_REGEX = Regex("""(movie|tv)/\d+""")

        private val animeUrlRegex = Regex("""/(movie|tv)/(\d+)""")

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private const val ANIMATION_GENRE_ID = 16

        // Rating sort uses a higher floor: TMDB has many obscure titles
        // with a handful of perfect-score votes that would dominate.
        private const val MIN_VOTES_FOR_RATING_SORT = "200"
        private const val MIN_VOTES_FOR_RECENT_SORT = "50"

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val DOMAIN_ENTRIES = arrayOf("www.movy.sx")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val PREF_SERVERS_KEY = "pref_servers"
        private val PREF_SERVERS_DEFAULT =
            setOf("Miami", "Boise", "Vegas", "Reno", "Oakland")
    }
}
