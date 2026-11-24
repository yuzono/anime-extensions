package eu.kanade.tachiyomi.animeextension.all.kartoons

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.source.model.SAnimeSeasonUpdate
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

@Source
abstract class Kartoons :
    AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val apiUrl: String
        get() = baseUrl.replace("://", "://api.")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val catalog = preferences.getString(PREF_HOME_CATALOG_KEY, PREF_HOME_CATALOG_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: PREF_HOME_CATALOG_DEFAULT
        return fetchCatalog(catalog, page)
    }

    private suspend fun fetchCatalog(catalog: String, page: Int): AnimesPage {
        if (catalog.startsWith("stremio:")) {
            return fetchStremioCatalog(catalog.removePrefix("stremio:"), page)
        }
        val sep = if (catalog.contains("?")) "&" else "?"
        val res = client.get(buildApiUrl("$catalog${sep}page=$page&limit=20"), headers)
            .parseAs<CatalogResponse>()
        if (!res.success) {
            throw IOException(res.message ?: res.error ?: "Failed to fetch catalog")
        }
        val animeList = res.data.map { item ->
            createAnime(item.id, item.title.trim(), item.image ?: item.coverImage, item.type)
        }
        return AnimesPage(animeList, res.data.size >= 20)
    }

    private suspend fun fetchStremioCatalog(path: String, page: Int): AnimesPage {
        val catalog = client.get(buildStremioCatalogUrl(path, page), headers)
            .parseAs<StremioCatalogResponse>()
        val animeList = catalog.metas.mapNotNull { meta ->
            val rawId = meta.id?.removePrefix("kartoons:")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = meta.name?.trim()?.takeIf { it.isNotBlank() }
                ?: meta.title?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            createAnime(rawId, name, meta.poster, meta.type, meta.description)
        }
        return AnimesPage(animeList, catalog.metas.size >= 20)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchCatalog("catalog/show?catalog=latest", page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val uri = query.toHttpUrlOrNull()
            if (uri != null && uri.host.contains("kartoons", ignoreCase = true)) {
                val segments = uri.pathSegments.filter { it.isNotEmpty() }
                val type = segments.getOrNull(0)
                val id = segments.getOrNull(1)
                if ((type == "show" || type == "movie") && !id.isNullOrBlank()) {
                    val seasonSlug = uri.queryParameter("season")
                        ?: if (segments.getOrNull(2) == "season") segments.getOrNull(3) else null
                    val animeUrl = if (type == "show" && !seasonSlug.isNullOrBlank()) {
                        "/show/$id/season/$seasonSlug"
                    } else {
                        "/$type/$id"
                    }
                    val details = fetchDetails(SAnime.create().apply { this.url = animeUrl })
                        .apply { initialized = true }
                    return AnimesPage(listOf(details), false)
                }
            }
        }

        if (query.isBlank()) {
            val catalog = filters.firstInstanceOrNull<Filters.CatalogFilter>()?.selected ?: PREF_HOME_CATALOG_DEFAULT
            return fetchCatalog(catalog, page)
        }

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val res = client.get(buildApiUrl("search?q=$encodedQuery&page=$page&limit=20"), headers)
            .parseAs<SearchResponse>()
        if (!res.success) {
            throw IOException(res.message ?: res.error ?: "Search failed")
        }
        val animeList = res.data.map { item ->
            createAnime(item.id, item.title.trim(), item.image, item.type)
        }
        return AnimesPage(animeList, res.data.size >= 20)
    }

    // =========================== Anime Details ============================

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        val updated = if (fetchDetails) fetchDetails(anime) else anime
        return SAnimeUpdate(
            anime = updated,
            episodes = if (fetchEpisodes) fetchEpisodes(updated) else episodes,
        )
    }

    override suspend fun fetchAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        if (!fetchDetails && !fetchSeasons) return SAnimeSeasonUpdate(anime, seasons)
        if (anime.url.startsWith("/movie/")) {
            return SAnimeSeasonUpdate(if (fetchDetails) fetchDetails(anime) else anime, emptyList())
        }

        val id = showId(anime.url)
        val show = fetchShow(id)
        val details = if (fetchDetails) show.toSAnime(anime.url) else anime
        return SAnimeSeasonUpdate(
            anime = details,
            seasons = if (fetchSeasons) show.toSeasonList(id, details, anime.status) else seasons,
        )
    }

    private suspend fun fetchDetails(anime: SAnime): SAnime {
        if (!anime.url.startsWith("/movie/")) {
            return fetchShow(showId(anime.url)).toSAnime(anime.url)
        }

        val res = client.get(buildApiUrl("movie/${anime.url.substringAfterLast("/")}"), headers)
            .parseAs<MovieDetailsResponse>()
        if (!res.success) {
            throw IOException(res.message ?: res.error ?: "Failed to fetch movie details")
        }
        val data = res.data ?: throw IOException("Movie data not found")
        return SAnime.create().apply {
            url = anime.url
            title = data.title.trim()
            description = data.description
            thumbnail_url = data.image ?: data.coverImage
            genre = data.tags.joinToString()
            status = parseStatus(data.status).takeIf { it != SAnime.UNKNOWN } ?: SAnime.COMPLETED
            fetch_type = FetchType.Episodes
        }
    }

    private suspend fun fetchShow(id: String): ShowData {
        val res = client.get(buildApiUrl("show/$id"), headers).parseAs<ShowDetailsResponse>()
        if (!res.success) {
            throw IOException(res.message ?: res.error ?: "Failed to fetch show details")
        }
        return res.data ?: throw IOException("Show data not found")
    }

    private fun ShowData.toSAnime(animeUrl: String): SAnime {
        val show = this
        return SAnime.create().apply {
            url = animeUrl
            title = show.title.trim()
            description = show.description
            thumbnail_url = show.image ?: show.coverImage
            genre = show.tags.joinToString()
            status = parseStatus(show.status)
            fetch_type = if (animeUrl.contains("/season/")) FetchType.Episodes else FetchType.Seasons
        }
    }

    // ============================== Seasons ===============================

    private fun ShowData.toSeasonList(id: String, anime: SAnime, fallbackStatus: Int): List<SAnime> {
        val showStatus = parseStatus(status).takeIf { it != SAnime.UNKNOWN } ?: fallbackStatus
        return seasons.mapNotNull { season ->
            val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: return@mapNotNull null
            val seasonNum = season.seasonNumber ?: 1
            SAnime.create().apply {
                url = "/show/$id/season/$seasonSlug"
                title = seasonTitle(seasonNum, season.title)
                thumbnail_url = anime.thumbnail_url
                description = anime.description
                genre = anime.genre
                status = showStatus
                season_number = seasonNum.toDouble()
                fetch_type = FetchType.Episodes
                initialized = true
            }
        }
    }

    // ============================== Episodes ==============================

    private suspend fun fetchEpisodes(anime: SAnime): List<SEpisode> {
        if (anime.url.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "/movie/${anime.url.substringAfterLast("/")}"
                    preview_url = anime.thumbnail_url
                    scanlator = "Movie"
                },
            )
        }

        val id = showId(anime.url)
        if (anime.url.contains("/season/")) {
            val seasonSlug = anime.url.substringAfter("/season/").substringBefore("?")
            var episodeCount = 1F
            return fetchSeasonEpisodes(id, seasonSlug).map { ep ->
                createEpisode(ep, id, seasonSlug, fallbackNumber = episodeCount++)
            }.reversed()
        }

        val seasons = fetchShow(id).seasons
        val recordedErrors = ConcurrentLinkedQueue<Throwable>()
        val seasonResults = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: return@async null
                    try {
                        season to fetchSeasonEpisodes(id, seasonSlug)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordedErrors.add(e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (seasonResults.isEmpty() && recordedErrors.isNotEmpty()) {
            throw IOException("Failed to load seasons", recordedErrors.peek())
        }

        var episodeCount = 1F
        return seasonResults.flatMap { (season, episodes) ->
            val seasonNum = season.seasonNumber ?: 1
            val scanlatorName = seasonTitle(seasonNum, season.title)
            val seasonSlug = season.slug ?: "season-$seasonNum"
            episodes.map { ep -> createEpisode(ep, id, seasonSlug, scanlatorName, episodeCount++) }
        }.reversed()
    }

    private suspend fun fetchSeasonEpisodes(showId: String, seasonSlug: String): List<EpisodeItem> {
        val res = client.get(buildApiUrl("show/$showId/season/$seasonSlug/episodes"), headers)
            .parseAs<EpisodesResponse>()
        if (!res.success) {
            throw IOException(res.message ?: res.error ?: "Failed to fetch episodes for season $seasonSlug")
        }
        return res.data
    }

    // ============================ Video Links =============================

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterUrl = episode.url,
            hosterName = name,
        ),
    )

    // Streams come from the Stremio addon API; fetching one also records it in the account's watch history.
    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.hosterUrl
        val stremioId = when {
            episodeUrl.contains("/episode/") -> {
                "series/kartoons:" + episodeUrl.substringAfter("/episode/").substringBefore("/").substringBefore("?")
            }
            episodeUrl.contains("/movie/") -> {
                "movie/kartoons:" + episodeUrl.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            }
            else -> throw IOException("Unsupported episode url: $episodeUrl")
        }

        val streams = client.get(buildStremioUrl("stream/$stremioId"), headers)
            .parseAs<StremioStreamResponse>()
            .streams
            .filterNot { it.url.isNullOrBlank() }
        if (streams.isEmpty()) return emptyList()

        val subtitles = try {
            client.get(buildStremioUrl("subtitles/$stremioId"), headers)
                .parseAs<StremioSubtitlesResponse>()
                .subtitles
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val subtitleTracks = subtitles.mapNotNull { sub ->
            if (sub.url.isBlank()) return@mapNotNull null
            val lang = when (sub.lang?.lowercase()?.trim()) {
                "eng", "en" -> "English"
                "hin", "hi" -> "Hindi"
                "tam", "ta" -> "Tamil"
                "tel", "te" -> "Telugu"
                "jpn", "ja" -> "Japanese"
                "mal", "ml" -> "Malayalam"
                "kan", "kn" -> "Kannada"
                "ben", "bn" -> "Bengali"
                "mar", "mr" -> "Marathi"
                null, "" -> "Unknown"
                else -> sub.lang.replaceFirstChar { it.uppercase() }
            }
            Track(sub.url, lang)
        }

        // Each stream is a master playlist with every dub as an audio rendition. Its variant and
        // audio playlists are served as text/plain without an .m3u8 extension, which ffmpeg only
        // accepts when it reaches them through the master, so the master is played as is and the
        // app picks the dub from its own preferred audio language.
        return streams.map { stream ->
            Video(
                videoUrl = stream.url!!,
                videoTitle = stream.title ?: stream.name ?: name,
                headers = headers,
                subtitleTracks = subtitleTracks,
            )
        }
    }

    private fun seasonTitle(seasonNum: Int, title: String?): String {
        val cleanTitle = title?.trim().orEmpty().replace(SEASON_PREFIX_REGEX, "").trim()
        return if (cleanTitle.isNotBlank()) {
            String.format(Locale.US, "Season %02d: %s", seasonNum, cleanTitle)
        } else {
            String.format(Locale.US, "Season %02d", seasonNum)
        }
    }

    private fun showId(url: String): String = if (url.contains("/season/")) {
        url.substringAfter("/show/").substringBefore("/season/")
    } else {
        url.substringAfterLast("/")
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "ended", "completed", "finished" -> SAnime.COMPLETED
        "ongoing", "returning series", "in production" -> SAnime.ONGOING
        "cancelled", "canceled" -> SAnime.CANCELLED
        "on hiatus", "hiatus" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private fun createAnime(
        id: String,
        title: String,
        thumbnailUrl: String?,
        type: String? = null,
        description: String? = null,
    ): SAnime {
        val mediaType = if (type == "movie") "movie" else "show"
        return SAnime.create().apply {
            url = "/$mediaType/$id"
            this.title = title
            thumbnail_url = thumbnailUrl
            this.description = description
            fetch_type = if (mediaType == "movie") FetchType.Episodes else FetchType.Seasons
        }
    }

    private fun createEpisode(
        ep: EpisodeItem,
        showId: String,
        seasonSlug: String,
        scanlatorName: String? = null,
        fallbackNumber: Float = 1F,
    ): SEpisode {
        val epNum = ep.episodeNumber ?: fallbackNumber
        val epTitle = ep.title?.trim().orEmpty()
        val epNumStr = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()
        return SEpisode.create().apply {
            name = if (epTitle.isNotBlank()) "Episode $epNumStr: $epTitle" else "Episode $epNumStr"
            episode_number = epNum
            url = "/show/$showId/season/$seasonSlug/episode/${ep.id}"
            preview_url = ep.image
            scanlator = scanlatorName
        }
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = TOKEN_KEY
            title = "$name Token"
            summary = preferences.getString(TOKEN_KEY, null)?.let {
                "Token set (length: ${it.length})"
            } ?: "Tap to enter your $name token from $baseUrl/profile"
            dialogTitle = "$name Token"
            dialogMessage = "1. Sign in to $baseUrl\n" +
                "2. Go to Profile -> Stremio tab\n" +
                "3. Copy the whole Manifest URL\n" +
                "4. Paste it here"
            setOnPreferenceChangeListener { preference, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(key, str).commit()
                preference.summary = if (str.isNotBlank()) "Token set (length: ${str.length})" else "Tap to enter token"
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOME_CATALOG_KEY
            title = "Default Browse Catalog"
            entries = Filters.CATALOG_OPTIONS.map { it.first }.toTypedArray()
            entryValues = Filters.CATALOG_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(PREF_HOME_CATALOG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private fun getCleanToken(): String {
        val raw = preferences.getString(TOKEN_KEY, "")?.trim().orEmpty()
        if (raw.isBlank()) return ""
        return if (raw.contains("token=")) {
            // Taken from a URL, so it is still percent-encoded; addQueryParameter encodes it again.
            val encoded = raw.substringAfter("token=").substringBefore("&").substringBefore("#").trim()
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        } else {
            raw
        }
    }

    private fun requireToken(): String = getCleanToken().ifBlank {
        throw IOException(
            "User token is required. Configure your token in extension settings (Settings > Browse > Anime extensions > $name).",
        )
    }

    private fun String.withToken(token: String): String = toHttpUrl().newBuilder()
        .addQueryParameter("token", token)
        .build()
        .toString()

    private fun buildApiUrl(pathWithQuery: String): String = "$apiUrl/api/cloudstream/$pathWithQuery".withToken(requireToken())

    private fun buildStremioUrl(path: String): String = "$apiUrl/api/stremio/$path.json".withToken(requireToken())

    private fun buildStremioCatalogUrl(path: String, page: Int): String {
        val token = getCleanToken()
        if (token.isBlank()) {
            throw IOException(
                "User token is required to view your watchlist and continue-watching. Set your token in the extension settings (Settings > Browse > Anime extensions > $name).",
            )
        }
        val cleanPath = path.removePrefix("/")
        val url = if (page <= 1) {
            "$apiUrl/api/stremio/$cleanPath.json"
        } else {
            val skip = (page - 1) * 20
            "$apiUrl/api/stremio/$cleanPath/skip=$skip.json"
        }
        return url.withToken(token)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    companion object {
        private val SEASON_PREFIX_REGEX = Regex("""^Season\s+\d+\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
        private const val TOKEN_KEY = "token"
        private const val PREF_HOME_CATALOG_KEY = "pref_home_catalog"
        private const val PREF_HOME_CATALOG_DEFAULT = "catalog/show?catalog=popular"
    }
}
