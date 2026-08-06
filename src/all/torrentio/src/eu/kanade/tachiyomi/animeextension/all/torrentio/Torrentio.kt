package eu.kanade.tachiyomi.animeextension.all.torrentio

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentio.Torrentio.Companion.DEFAULT_STREAMING_SERVICE
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMeta
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMetaDetail
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaMetaDetailResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.CinemetaSearchResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.EpisodeList
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.EpisodeVideo
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.TheMovieDatabaseResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.TmdbDiscoverPlusMeta
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.TmdbDiscoverPlusResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.TmdbResult
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Torrentio :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Torrentio (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val cinemetaUrl = "https://v3-cinemeta.strem.io"
    private val streamingCatalogUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
    private val tmdbUrl = "https://api.themoviedb.org"
    private val tmdbDiscoverPlusUrl = "https://tmdb-discover-plus.elfhosted.com/t5mDdzCuoL" // rate limited do not abuse
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ============================== Related =====================================
    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    // ============================== Popular =====================================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)

        val contentType = getContentType()
        val service = getStreamingServiceForContentType(contentType)

        val results = coroutineScope {
            val movies = async { fetchStreamingCatalog("movie", service) }
            val series = async { fetchStreamingCatalog("series", service) }
            movies.await() + series.await()
        }

        return AnimesPage(results.map { it.toSAnime() }, false)
    }
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Use getPopularAnime instead")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Use getPopularAnime instead")
    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val contentType = getContentType()

        val url = when (contentType) {
            "anime" -> buildTmdbAnimeDiscoverUrl(page)
            else -> buildTmdbTrendingUrl("tv", page)
        }

        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val contentType = getContentType()

        return runBlocking {
            when (contentType) {
                "anime" -> fetchTmdbAnime(page)
                else -> fetchTrendingMoviesAndTV(page)
            }
        }
    }

    // =========================== Search ====================================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return searchAnimeByIdParse(id)
        }

        if (page > 1) return AnimesPage(emptyList(), false)

        val trimmedQuery = query.trim()
        val types = CatalogFilters.mediaType(filters)
        val contentType = getContentType()

        if (trimmedQuery.isBlank()) {
            val effectiveNetwork = resolveEffectiveNetwork(contentType, filters)

            val results = coroutineScope {
                types.map { type ->
                    async { fetchStreamingCatalog(type, effectiveNetwork) }
                }.awaitAll().flatten()
            }
            return AnimesPage(results.distinctBy { it.id }.map { it.toSAnime() }, false)
        }

        val results = coroutineScope {
            types.map { type ->
                async { searchCinemeta(type, trimmedQuery) }
            }.awaitAll().flatten()
        }

        val distinctResults = results.distinctBy { it.id }.map { it.toSAnime() }
        return AnimesPage(distinctResults, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return GET("$cinemetaUrl/meta/movie/$id.json")
        }

        val trimmedQuery = query.trim()

        if (trimmedQuery.isBlank()) {
            val types = CatalogFilters.mediaType(filters)
            val contentType = getContentType()
            val effectiveNetwork = resolveEffectiveNetwork(contentType, filters)
            val type = types.firstOrNull() ?: "movie"
            return GET("$streamingCatalogUrl/catalog/$type/$effectiveNetwork.json")
        }

        val types = CatalogFilters.mediaType(filters)
        val type = types.firstOrNull() ?: "movie"
        return GET("$cinemetaUrl/catalog/$type/top/search=$trimmedQuery.json")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        val url = response.request.url.toString()

        if (url.contains("/meta/")) {
            val detail = json.decodeFromString<CinemetaMetaDetailResponse>(responseString)
            val meta = detail.meta ?: return AnimesPage(emptyList(), false)
            val type = if (url.contains("/movie/")) "movie" else "series"
            val id = meta.id.orEmpty()

            val anime = SAnime.create().apply {
                this.url = "$id,$type"
                title = meta.name.orEmpty()
                thumbnail_url = meta.poster.orEmpty()
                description = meta.description.orEmpty()
                genre = meta.genres?.joinToString().orEmpty()
            }
            return AnimesPage(listOf(anime), false)
        }

        val searchResponse = json.decodeFromString<CinemetaSearchResponse>(responseString)
        val results = searchResponse.metas.orEmpty()
        return AnimesPage(results.map { it.toSAnime() }, false)
    }

    private fun resolveEffectiveNetwork(contentType: String, filters: AnimeFilterList): String = when (contentType) {
        "anime" -> "cru"
        else -> CatalogFilters.streamingService(filters)
    }

    // =============================== Filters =======================================

    override fun getFilterList(): AnimeFilterList {
        val contentType = getContentType()
        return CatalogFilters.getFilterList(contentType)
    }

    // =========================== Details ====================================

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        val url = response.request.url.toString()

        // Try to parse as TmdbDiscoverPlusResponse first
        val detail = runCatching {
            json.decodeFromString<TmdbDiscoverPlusResponse>(responseString)
        }.getOrNull()

        val meta = detail?.meta

        if (meta == null) {
            val cinemetaDetail = runCatching {
                json.decodeFromString<CinemetaMetaDetailResponse>(responseString)
            }.getOrNull()
            val cinemetaMeta = cinemetaDetail?.meta ?: return SAnime.create()

            return SAnime.create().apply {
                val id = cinemetaMeta.id.orEmpty()
                val type = if (url.contains("/movie/")) "movie" else "series"
                this.url = "$id,$type"
                title = cinemetaMeta.name.orEmpty()
                thumbnail_url = cinemetaMeta.poster.orEmpty()
                description = cinemetaMeta.description.orEmpty()
                genre = cinemetaMeta.genres?.joinToString().orEmpty()
                author = cinemetaMeta.director?.joinToString().orEmpty()
                artist = cinemetaMeta.cast?.take(4)?.joinToString().orEmpty()
                status = mapStatus(cinemetaMeta.status, cinemetaMeta.released)
            }
        }

        val type = if (url.contains("/movie/")) "movie" else "series"
        val id = meta.imdbId ?: meta.imdb_id ?: meta.id.orEmpty()

        return SAnime.create().apply {
            this.url = "$id,$type"
            title = meta.name.orEmpty()
            thumbnail_url = meta.poster.orEmpty()
            description = meta.description.orEmpty()
            genre = meta.genres?.joinToString().orEmpty()
            author = meta.writer ?: meta.director ?: ""
            artist = meta.cast?.take(4)?.joinToString().orEmpty()
            status = mapStatus(meta.status, meta.released)
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val originalUrl = anime.url
        val parts = originalUrl.split(",")
        val id = parts[0]
        val type = parts.getOrNull(1)?.lowercase()?.ifBlank { "movie" } ?: "movie"

        val isTmdbId = id.toIntOrNull() != null

        val meta = if (isTmdbId) {
            fetchMetaDetailViaDiscoverPlus(type, id)
        } else {
            fetchMetaDetail(type, id)?.let { cinemetaMeta ->

                cinemetaMeta.toDiscoverPlusMeta()
            }
        }

        if (meta != null) {
            val imdbId = meta.imdbId ?: meta.imdb_id

            anime.title = meta.name ?: anime.title
            if (!meta.poster.isNullOrBlank()) {
                anime.thumbnail_url = meta.poster
            }
            anime.description = meta.description ?: anime.description
            anime.genre = meta.genres?.joinToString() ?: anime.genre
            anime.author = meta.writer ?: meta.director ?: anime.author
            anime.artist = meta.cast?.take(4)?.joinToString() ?: anime.artist
            anime.status = mapStatus(meta.status, meta.released)

            if (!imdbId.isNullOrBlank()) {
                val newUrl = "$imdbId,$type"
                anime.url = newUrl
            } else {
            }
        } else {
        }

        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val resolvedAnime = if (anime.url.split(",")[0].toIntOrNull() != null) {
            getAnimeDetails(anime)
        } else {
            anime
        }
        return super.getEpisodeList(resolvedAnime)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val parts = anime.url.split(",")
        val type = parts.getOrNull(1)?.lowercase() ?: "movie"
        val id = parts[0]

        if (id.toIntOrNull() != null) {
            return GET("$tmdbDiscoverPlusUrl/meta/$type/tmdb:$id.json")
        }

        return GET("$cinemetaUrl/meta/$type/$id.json")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val url = response.request.url.toString()

        if (url.contains("tmdb-discover-plus")) {
            val discoverPlusResponse = runCatching {
                json.decodeFromString<TmdbDiscoverPlusResponse>(responseString)
            }.getOrNull()

            if (discoverPlusResponse != null) {
                return parseEpisodeListFromTmdbDiscoverPlus(discoverPlusResponse)
            }
        }

        val episodeList = runCatching {
            json.decodeFromString<EpisodeList>(responseString)
        }.getOrNull()

        if (episodeList == null) {
            return emptyList()
        }

        return when (episodeList.meta?.type) {
            "series" -> buildSeriesEpisodes(
                videos = episodeList.meta.videos.orEmpty(),
                episodeUrl = { videoId -> "/stream/series/$videoId.json" },
            )
            "movie" -> {
                listOf(singleMovieEpisode("/stream/movie/${episodeList.meta.id}.json"))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun parseEpisodeListFromTmdbDiscoverPlus(response: TmdbDiscoverPlusResponse): List<SEpisode> {
        val meta = response.meta ?: return emptyList()
        val type = meta.type ?: "movie"

        return when (type) {
            "series" -> {
                val videos = meta.videos.orEmpty()

                if (videos.isEmpty()) {
                    val imdbId = meta.imdbId ?: meta.imdb_id ?: meta.id.orEmpty()
                    return listOf(
                        SEpisode.create().apply {
                            episode_number = 1f
                            this.url = "/stream/series/$imdbId.json"
                            name = "Series - ${meta.name.orEmpty()}"
                        },
                    )
                }

                buildSeriesEpisodes(
                    videos = videos,
                    episodeUrl = { videoId -> "/stream/series/$videoId.json" },
                )
            }
            "movie" -> {
                val imdbId = meta.imdbId ?: meta.imdb_id ?: meta.id.orEmpty()
                listOf(singleMovieEpisode("/stream/movie/$imdbId.json"))
            }
            else -> {
                emptyList()
            }
        }
    }

    private fun buildSeriesEpisodes(
        videos: List<EpisodeVideo>,
        episodeUrl: (String) -> String,
    ): List<SEpisode> {
        val showUpcoming = preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)
        val hideSeasonZero = preferences.getBoolean(HIDE_SEASON_ZERO_KEY, HIDE_SEASON_ZERO_DEFAULT)
        val now = System.currentTimeMillis()

        return videos
            .filter { video ->
                if (hideSeasonZero) video.season != 0 else true
            }
            .mapNotNull { video ->
                val releaseTime = (video.firstAired ?: video.released)
                    ?.let(::parseDate) ?: Long.MAX_VALUE

                val isReleased = releaseTime <= now
                if (!showUpcoming && !isReleased) {
                    return@mapNotNull null
                }

                val episode = SEpisode.create().apply {
                    episode_number = "${video.season}.${video.number}".toFloat()
                    this.url = episodeUrl(video.id.orEmpty())
                    date_upload = if (releaseTime == Long.MAX_VALUE) 0L else releaseTime
                    name = "S${video.season}:E${video.number} - ${video.name.orEmpty()}"
                    scanlator = if (!isReleased) "Upcoming" else ""
                }

                video to episode
            }
            .sortedWith(
                compareByDescending<Pair<EpisodeVideo, SEpisode>> { (video, _) -> (video.season ?: 0) > 0 }
                    .thenByDescending { (video, _) -> video.season ?: 0 }
                    .thenByDescending { (video, _) -> video.number ?: 0 },
            )
            .map { (_, episode) -> episode }
    }

    private fun singleMovieEpisode(url: String): SEpisode = SEpisode.create().apply {
        episode_number = 1f
        this.url = url
        name = "Movie"
    }

    private fun parseDate(dateStr: String): Long {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return 0L

        runCatching { DATE_FORMATTER.parse(trimmed)?.time }
            .getOrNull()
            ?.let { return it }

        runCatching { SIMPLE_DATE_FORMATTER.parse(trimmed)?.time }
            .getOrNull()
            ?.let { return it }

        return 0L
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val mainURL = buildString {
            append("$baseUrl/")

            val appendQueryParam: (String, Set<String>?) -> Unit = { key, values ->
                values?.takeIf { it.isNotEmpty() }?.let {
                    append("$key=${it.filter(String::isNotBlank).joinToString(",")}|")
                }
            }

            appendQueryParam("providers", preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT))
            appendQueryParam("language", preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT))
            appendQueryParam("qualityfilter", preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT))

            val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
            appendQueryParam("sort", sortKey?.let { setOf(it) })

            val token = preferences.getString(PREF_TOKEN_KEY, null)
            val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

            when {
                token.isNullOrBlank() && debridProvider != "none" -> {
                    handler.post {
                        applicationContext.let {
                            Toast.makeText(
                                it,
                                "Kindly input the debrid token in the extension settings.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    throw UnsupportedOperationException()
                }

                !token.isNullOrBlank() && debridProvider != "none" -> append("$debridProvider=$token|")
            }
            append(episode.url)
        }.removeSuffix("|")

        return GET(mainURL)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val streamList = json.decodeFromString<StreamDataTorrent>(responseString)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        val animeTrackers = """http://nyaa.tracker.wf:7777/announce,
            http://anidex.moe:6969/announce,http://tracker.anirena.com:80/announce,
            udp://tracker.uw0.xyz:6969/announce,
            http://share.camoe.cn:8080/announce,
            http://t.nyaatracker.com:80/announce,
            udp://47.ip-51-68-199.eu:6969/announce,
            udp://9.rarbg.me:2940,
            udp://9.rarbg.to:2820,
            udp://exodus.desync.com:6969/announce,
            udp://explodie.org:6969/announce,
            udp://ipv4.tracker.harry.lu:80/announce,
            udp://open.stealth.si:80/announce,
            udp://opentor.org:2710/announce,
            udp://opentracker.i2p.rocks:6969/announce,
            udp://retracker.lanta-net.ru:2710/announce,
            udp://tracker.cyberia.is:6969/announce,
            udp://tracker.dler.org:6969/announce,
            udp://tracker.ds.is:6969/announce,
            udp://tracker.internetwarriors.net:1337,
            udp://tracker.openbittorrent.com:6969/announce,
            udp://tracker.opentrackr.org:1337/announce,
            udp://tracker.tiny-vps.com:6969/announce,
            udp://tracker.torrent.eu.org:451/announce,
            udp://valakas.rollo.dnsabr.com:2710/announce,
            udp://www.torrent.eu.org:451/announce,
             ${fetchTrackers().split("\n").joinToString(",")}
        """.trimIndent()

        return streamList.streams?.map { stream ->
            val urlOrHash =
                if (debridProvider == "none") {
                    val trackerList = animeTrackers.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("&tr=")
                    "magnet:?xt=urn:btih:${stream.infoHash}&dn=${stream.infoHash}&tr=$trackerList&index=${stream.fileIdx}"
                } else {
                    stream.url ?: ""
                }
            Video(urlOrHash, ((stream.name?.replace("Torrentio\n", "") ?: "") + "\n" + stream.title), urlOrHash)
        }.orEmpty()
    }

    override fun List<Video>.sort(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return sortedWith(
            compareBy(
                { Regex("\\[(.+?) download]").containsMatchIn(it.quality) },
                { isDub && !it.quality.contains("dubbed", true) },
                { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.quality.contains(q, true) } },
            ),
        )
    }

    // ============================ TMDB Helpers ==============================

    private fun buildTmdbAnimeDiscoverUrl(page: Int): HttpUrl {
        val (weekStart, weekEnd) = getWeeklyDates()
        return tmdbUrl.toHttpUrl().newBuilder()
            .addPathSegment("3")
            .addPathSegment("discover")
            .addPathSegment("tv")
            .addQueryParameter("api_key", getTmdbApiKey())
            .addQueryParameter("with_genres", "16,10765,10759")
            .addQueryParameter("with_original_language", "ja")
            .addQueryParameter("with_origin_country", "JP")
            .addQueryParameter("air_date.gte", weekStart)
            .addQueryParameter("air_date.lte", weekEnd)
            .addQueryParameter("sort_by", "popularity.desc")
            .addQueryParameter("page", page.toString())
            .build()
    }

    private fun buildTmdbTrendingUrl(mediaType: String, page: Int): HttpUrl = tmdbUrl.toHttpUrl().newBuilder()
        .addPathSegment("3")
        .addPathSegment("trending")
        .addPathSegment(mediaType)
        .addPathSegment("week")
        .addQueryParameter("api_key", getTmdbApiKey())
        .addQueryParameter("page", page.toString())
        .build()

    private suspend fun fetchTmdbAnime(page: Int): AnimesPage {
        val url = buildTmdbAnimeDiscoverUrl(page)

        return try {
            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body.string()
            val discoverResponse = json.decodeFromString<TheMovieDatabaseResponse>(body)

            val animeList = discoverResponse.results.map { result ->
                SAnime.create().apply {
                    this.url = "${result.id},series"
                    title = result.name ?: "Unknown"
                    thumbnail_url = buildImageUrl(result.poster_path)
                    description = result.overview ?: ""
                    genre = result.genre_ids?.joinToString() ?: ""
                    status = mapStatusFromDate(result.first_air_date)
                }
            }

            AnimesPage(
                animeList,
                discoverResponse.page < discoverResponse.total_pages,
            )
        } catch (e: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    private suspend fun fetchTrendingMoviesAndTV(page: Int): AnimesPage = try {
        val results = coroutineScope {
            val tvDeferred = async {
                val url = buildTmdbTrendingUrl("tv", page)
                val response = client.newCall(GET(url)).awaitSuccess()
                json.decodeFromString<TheMovieDatabaseResponse>(response.body.string())
            }

            val movieDeferred = async {
                val url = buildTmdbTrendingUrl("movie", page)
                val response = client.newCall(GET(url)).awaitSuccess()
                json.decodeFromString<TheMovieDatabaseResponse>(response.body.string())
            }

            val tvResults = tvDeferred.await()
            val movieResults = movieDeferred.await()

            val combined = mutableListOf<TmdbResult>()
            combined.addAll(tvResults.results)
            combined.addAll(movieResults.results)
            combined.sortByDescending { it.popularity ?: 0.0 }

            combined to (tvResults.total_pages > page || movieResults.total_pages > page)
        }

        val (combinedResults, hasNextPage) = results

        val animeList = combinedResults.map { result ->
            SAnime.create().apply {
                val isTv = result.first_air_date != null
                this.url = "${result.id},${if (isTv) "series" else "movie"}"
                title = if (isTv) result.name ?: "Unknown" else result.title ?: "Unknown"
                thumbnail_url = buildImageUrl(result.poster_path)
                description = result.overview ?: ""
                genre = result.genre_ids?.joinToString() ?: ""
                status = if (isTv) {
                    mapStatusFromDate(result.first_air_date)
                } else {
                    SAnime.COMPLETED
                }
            }
        }

        AnimesPage(animeList, hasNextPage)
    } catch (e: Exception) {
        AnimesPage(emptyList(), false)
    }

    private fun getWeeklyDates(offsetWeeks: Int = 0): Pair<String, String> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, -offsetWeeks * 7)

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

        val diffToMonday = calendar.get(Calendar.DAY_OF_MONTH) - dayOfWeek + (if (dayOfWeek == 0) -6 else 1)
        calendar.set(Calendar.DAY_OF_MONTH, diffToMonday)
        val monday = calendar.time

        val sundayCalendar = calendar.clone() as Calendar
        sundayCalendar.add(Calendar.DAY_OF_MONTH, 6)
        val sunday = sundayCalendar.time

        return dateFormat.format(monday) to dateFormat.format(sunday)
    }

    private fun buildImageUrl(path: String?): String = if (!path.isNullOrBlank()) {
        "https://image.tmdb.org/t/p/w500$path"
    } else {
        ""
    }

    private fun mapStatusFromDate(date: String?): Int {
        if (date == null) return SAnime.UNKNOWN
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val releaseDate = dateFormat.parse(date)
            val now = System.currentTimeMillis()

            if (releaseDate != null && releaseDate.time > now) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        } catch (e: Exception) {
            SAnime.UNKNOWN
        }
    }

    private fun getTmdbApiKey(): String = preferences.getString(PREF_TMDB_API_KEY, null)?.takeIf { it.isNotBlank() } ?: PREF_TMDB_DEFAULT

    // ============================ Helper Methods ==============================

    private fun getContentType(): String = preferences.getString(PREF_CONTENT_TYPE_KEY, PREF_CONTENT_TYPE_DEFAULT) ?: PREF_CONTENT_TYPE_DEFAULT

    private fun getStreamingServiceForContentType(contentType: String): String = when (contentType) {
        "anime" -> "cru"
        else -> DEFAULT_STREAMING_SERVICE
    }

    private suspend fun fetchStreamingCatalog(mediaType: String, streamingServiceId: String): List<CinemetaMeta> {
        val url = "$streamingCatalogUrl/catalog/$mediaType/$streamingServiceId.json"
        return runCatching {
            val response = client.newCall(GET(url)).awaitSuccess()
            json.decodeFromString<CinemetaSearchResponse>(response.body.string()).metas.orEmpty()
        }.getOrDefault(emptyList())
    }

    private suspend fun searchAnimeByIdParse(imdbId: String): AnimesPage {
        val isTmdbId = imdbId.toIntOrNull() != null

        var meta: TmdbDiscoverPlusMeta? = null
        var type = "movie"

        if (isTmdbId) {
            meta = fetchMetaDetailViaDiscoverPlus("movie", imdbId)
            type = "movie"

            if (meta == null) {
                meta = fetchMetaDetailViaDiscoverPlus("series", imdbId)
                type = "series"
            }
        } else {
            var cinemetaMeta = fetchMetaDetail("movie", imdbId)
            type = "movie"

            if (cinemetaMeta == null) {
                cinemetaMeta = fetchMetaDetail("series", imdbId)
                type = "series"
            }

            meta = cinemetaMeta?.let { cm ->

                cm.toDiscoverPlusMeta()
            }
        }

        if (meta == null) {
            return AnimesPage(emptyList(), false)
        }

        val finalId = meta.imdbId ?: meta.imdb_id ?: imdbId

        val anime = SAnime.create().apply {
            this.url = "$finalId,$type"
            title = meta.name.orEmpty()
            thumbnail_url = meta.poster.orEmpty()
            description = meta.description.orEmpty()
            genre = meta.genres?.joinToString().orEmpty()
        }

        return AnimesPage(listOf(anime), false)
    }

    private suspend fun fetchMetaDetail(type: String, imdbId: String): CinemetaMetaDetail? {
        val url = "$cinemetaUrl/meta/$type/$imdbId.json"

        return runCatching {
            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body.string()
            json.decodeFromString<CinemetaMetaDetailResponse>(body).meta
        }.getOrNull()
    }

    private suspend fun fetchMetaDetailViaDiscoverPlus(type: String, id: String): TmdbDiscoverPlusMeta? {
        // If it's already a tt ID, use cinemeta directly
        if (id.startsWith("tt")) {
            return fetchMetaDetail(type, id)?.let { meta ->
                meta.toDiscoverPlusMeta()
            }
        }

        val url = "$tmdbDiscoverPlusUrl/meta/$type/tmdb:$id.json"

        return try {
            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body.string()
            val detail = json.decodeFromString<TmdbDiscoverPlusResponse>(body)
            detail.meta?.apply {
                val imdb = this.imdbId ?: this.imdb_id
                if (!imdb.isNullOrBlank()) {
                    this.imdbId = imdb
                    this.imdb_id = imdb
                } else {
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun searchCinemeta(type: String, query: String): List<CinemetaMeta> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val url = cinemetaUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addPathSegment(type)
            .addPathSegment("top")
            .addPathSegment("search=$trimmed.json")
            .build()

        return runCatching {
            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body.string()
            json.decodeFromString<CinemetaSearchResponse>(body).metas.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun CinemetaMeta.toSAnime(): SAnime = SAnime.create().apply {
        url = "${id.orEmpty()},${type.orEmpty()}"
        title = name.orEmpty()
        thumbnail_url = poster.orEmpty()
    }

    private fun CinemetaMetaDetail.toDiscoverPlusMeta(): TmdbDiscoverPlusMeta = TmdbDiscoverPlusMeta(
        id = id,
        imdbId = id,
        imdb_id = id,
        type = type,
        name = name,
        poster = poster,
        description = description,
        genres = genres,
        cast = cast,
        director = director?.joinToString(),
        writer = writer?.joinToString(),
        released = released,
        status = status,
    )

    private fun mapStatus(status: String?, released: String?): Int {
        if (status != null) {
            return when (status.trim().lowercase()) {
                "continuing" -> SAnime.ONGOING
                "ended" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        val releaseTime = released?.let(::parseDate) ?: return SAnime.UNKNOWN
        return if (releaseTime > System.currentTimeMillis()) SAnime.ONGOING else SAnime.COMPLETED
    }

    private fun fetchTrackers(): String {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.string().trim()
        }
    }

    // ============================ Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_CONTENT_TYPE_KEY
            title = "Content Type"
            entries = PREF_CONTENT_TYPE_ENTRIES
            entryValues = PREF_CONTENT_TYPE_VALUES
            setDefaultValue(PREF_CONTENT_TYPE_DEFAULT)
            summary = "Filter content: %s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debrid Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("none")
            summary = "Choose 'None' for Torrent. If you select a Debrid provider, enter your token key."

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
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

        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDB API Key"
            summary = "Optional: provide your own TMDB API key. Leave blank to use the bundled default key."

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim()
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

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Exclude Qualities/Resolutions"
            entries = PREF_QUALITY
            entryValues = PREF_QUALITY_VALUE
            setDefaultValue(PREF_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Priority foreign language"
            entries = PREF_LANG
            entryValues = PREF_LANG_VALUE
            setDefaultValue(PREF_LANG_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Sorting"
            entries = PREF_SORT_ENTRIES
            entryValues = PREF_SORT_VALUES
            setDefaultValue("quality")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = UPCOMING_EP_KEY
            title = "Show Upcoming Episodes"
            setDefaultValue(UPCOMING_EP_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_SEASON_ZERO_KEY
            title = "Hide Season 0 Episodes"
            setDefaultValue(HIDE_SEASON_ZERO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_DUB_KEY
            title = "Dubbed Video Priority"
            setDefaultValue(IS_DUB_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_EFFICIENT_KEY
            title = "Efficient Video Priority"
            setDefaultValue(IS_EFFICIENT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Codec: (HEVC / x265) & AV1. High-quality video with less data usage."
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        const val DEFAULT_STREAMING_SERVICE = "nfx"

        private const val PREF_CONTENT_TYPE_KEY = "content_type"
        private const val PREF_CONTENT_TYPE_DEFAULT = "all"
        private val PREF_CONTENT_TYPE_ENTRIES = arrayOf("All Content", "Anime Only (Crunchyroll)", "Movies & TV")
        private val PREF_CONTENT_TYPE_VALUES = arrayOf("all", "anime", "movies_tv")
        private const val PREF_TMDB_DEFAULT = "ea021b3b0775c8531592713ab727f254"
        private const val PREF_TMDB_API_KEY = "tmdb_api_key"

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
            "YTS", "EZTV", "RARBG", "1337x", "ThePirateBay", "KickassTorrents", "TorrentGalaxy", "MagnetDL",
            "HorribleSubs", "NyaaSi", "TokyoTosho", "AniDex", "nekoBT", "🇷🇺 Rutor", "🇷🇺 Rutracker", "🇵🇹 Comando",
            "🇵🇹 BluDV", "🇫🇷 Torrent9", "🇮🇹 ilCorSaRoNero", "🇪🇸 MejorTorrent", "🇪🇸 Wolfmax4k", "🇲🇽 Cinecalidad", "🇵🇱 BestTorrents",
        )
        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts", "eztv", "rarbg", "1337x", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt", "rutor", "rutracker", "comando",
            "bludv", "torrent9", "ilcorsaronero", "mejortorrent", "wolfmax4k", "cinecalidad", "besttorrents",
        )
        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
            "yts", "eztv", "rarbg", "1337x", "thepiratebay", "kickasstorrents", "torrentgalaxy", "magnetdl",
            "horriblesubs", "nyaasi", "tokyotosho", "anidex", "nekobt",
        )
        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

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
        private val PREF_DEFAULT_QUALITY_VALUE = arrayOf("720p", "480p", "other", "scr", "cam", "unknown")
        private val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

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

        private const val UPCOMING_EP_KEY = "upcoming_ep"
        private const val UPCOMING_EP_DEFAULT = false

        private const val HIDE_SEASON_ZERO_KEY = "hide_season_zero"
        private const val HIDE_SEASON_ZERO_DEFAULT = false

        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val SIMPLE_DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
