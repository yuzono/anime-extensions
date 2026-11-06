package eu.kanade.tachiyomi.animeextension.all.kartoons

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

@Source
abstract class Kartoons :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val syncExecutor = Executors.newSingleThreadExecutor()

    private val apiUrl: String
        get() = baseUrl.replace("://", "://api.")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val defaultCatalog = preferences.getString(PREF_HOME_CATALOG_KEY, PREF_HOME_CATALOG_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: PREF_HOME_CATALOG_DEFAULT

        val url = if (defaultCatalog.startsWith("stremio:")) {
            buildStremioCatalogUrl(defaultCatalog.removePrefix("stremio:"), page)
        } else {
            val sep = if (defaultCatalog.contains("?")) "&" else "?"
            buildApiUrl("$defaultCatalog${sep}page=$page&limit=20")
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/api/stremio/")) {
            return parseStremioCatalog(response)
        }
        val catalog = response.parseAs<CatalogResponse>()
        if (!catalog.success) {
            throw IOException(catalog.message ?: catalog.error ?: "Failed to fetch catalog")
        }
        val animeList = catalog.data.map { item ->
            createAnime(item.id, item.title.trim(), item.image ?: item.coverImage, item.type)
        }
        return AnimesPage(animeList, catalog.data.size >= 20)
    }

    private fun parseStremioCatalog(response: Response): AnimesPage {
        val catalog = response.parseAs<StremioCatalogResponse>()
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

    override fun latestUpdatesRequest(page: Int): Request {
        val url = buildApiUrl("catalog/show?catalog=latest&page=$page&limit=20")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            buildApiUrl("search?q=$encodedQuery&page=$page&limit=20")
        } else {
            val catalogPath = filters.filterIsInstance<Filters.CatalogFilter>()
                .firstOrNull()?.selected ?: PREF_HOME_CATALOG_DEFAULT
            if (catalogPath.startsWith("stremio:")) {
                buildStremioCatalogUrl(catalogPath.removePrefix("stremio:"), page)
            } else {
                val sep = if (catalogPath.contains("?")) "&" else "?"
                buildApiUrl("$catalogPath${sep}page=$page&limit=20")
            }
        }
        return GET(url, headers)
    }

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
                    val details = getAnimeDetails(SAnime.create().apply { this.url = animeUrl })
                    return AnimesPage(listOf(details), false)
                }
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()
        return if (requestUrl.contains("/search?")) {
            val search = response.parseAs<SearchResponse>()
            if (!search.success) {
                throw IOException(search.message ?: search.error ?: "Search failed")
            }
            val animeList = search.data.map { item ->
                createAnime(item.id, item.title.trim(), item.image, item.type)
            }
            AnimesPage(animeList, search.data.size >= 20)
        } else {
            popularAnimeParse(response)
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.startsWith("/movie/")
        val isSingleSeason = anime.url.contains("/season/")
        val id = if (isSingleSeason) {
            anime.url.substringAfter("/show/").substringBefore("/season/")
        } else {
            anime.url.substringAfterLast("/")
        }
        val showApiUrl = if (isMovie) {
            buildApiUrl("movie/$id")
        } else {
            buildApiUrl("show/$id")
        }

        val response = client.get(showApiUrl, headers)
        return if (isMovie) {
            val res = response.parseAs<MovieDetailsResponse>()
            if (!res.success) {
                throw IOException(res.message ?: res.error ?: "Failed to fetch movie details")
            }
            val data = res.data ?: throw IOException("Movie data not found")
            SAnime.create().apply {
                this.url = anime.url
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = parseStatus(data.status).takeIf { it != SAnime.UNKNOWN } ?: SAnime.COMPLETED
                fetch_type = FetchType.Episodes
                initialized = true
            }
        } else {
            val res = response.parseAs<ShowDetailsResponse>()
            if (!res.success) {
                throw IOException(res.message ?: res.error ?: "Failed to fetch show details")
            }
            val data = res.data ?: throw IOException("Show data not found")
            SAnime.create().apply {
                this.url = anime.url
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = parseStatus(data.status)
                fetch_type = if (isSingleSeason) FetchType.Episodes else FetchType.Seasons
                initialized = true
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val isMovie = anime.url.startsWith("/movie/")
        if (isMovie) return emptyList()

        val id = if (anime.url.contains("/season/")) {
            anime.url.substringAfter("/show/").substringBefore("/season/")
        } else {
            anime.url.substringAfterLast("/")
        }

        val showUrl = buildApiUrl("show/$id")
        val showRes = client.get(showUrl, headers).parseAs<ShowDetailsResponse>()
        if (!showRes.success) {
            throw IOException(showRes.message ?: showRes.error ?: "Failed to fetch show details")
        }
        val showData = showRes.data ?: return emptyList()
        val seasons = showData.seasons
        if (seasons.isEmpty()) return emptyList()

        return seasons.mapNotNull { season ->
            val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: return@mapNotNull null
            val seasonNum = season.seasonNumber ?: 1
            val cleanTitle = cleanSeasonTitle(season.title)
            val seasonTitle = if (cleanTitle.isNotBlank()) {
                String.format(Locale.US, "Season %02d: %s", seasonNum, cleanTitle)
            } else {
                String.format(Locale.US, "Season %02d", seasonNum)
            }

            SAnime.create().apply {
                url = "/show/$id/season/$seasonSlug"
                title = seasonTitle
                thumbnail_url = anime.thumbnail_url
                description = anime.description
                genre = anime.genre
                status = parseStatus(showData.status).takeIf { it != SAnime.UNKNOWN } ?: anime.status
                season_number = seasonNum.toDouble()
                fetch_type = FetchType.Episodes
                initialized = true
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val isMovie = anime.url.startsWith("/movie/")
        val id = anime.url.substringAfterLast("/")

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "/movie/$id"
                    preview_url = anime.thumbnail_url
                    scanlator = "Movie"
                },
            )
        }

        if (anime.url.contains("/season/")) {
            val showId = anime.url.substringAfter("/show/").substringBefore("/season/")
            val seasonSlug = anime.url.substringAfter("/season/").substringBefore("?")
            val episodesUrl = buildApiUrl("show/$showId/season/$seasonSlug/episodes")
            val epRes = client.get(episodesUrl, headers).parseAs<EpisodesResponse>()
            if (!epRes.success) {
                throw IOException(epRes.message ?: epRes.error ?: "Failed to fetch episodes for season $seasonSlug")
            }
            var episodeCount = 1F
            return epRes.data.map { ep ->
                createEpisode(ep, showId, seasonSlug, fallbackNumber = episodeCount++)
            }.reversed()
        }

        val showUrl = buildApiUrl("show/$id")
        val showRes = client.get(showUrl, headers).parseAs<ShowDetailsResponse>()
        if (!showRes.success) {
            throw IOException(showRes.message ?: showRes.error ?: "Failed to fetch show details")
        }
        val showData = showRes.data ?: throw IOException("Show data is null")
        val seasons = showData.seasons

        val recordedErrors = ConcurrentLinkedQueue<Throwable>()
        val seasonResults = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: return@async null
                    val episodesUrl = buildApiUrl("show/$id/season/$seasonSlug/episodes")
                    try {
                        val epRes = client.get(episodesUrl, headers).parseAs<EpisodesResponse>()
                        if (!epRes.success) {
                            throw IOException(epRes.message ?: epRes.error ?: "Failed to fetch season $seasonSlug")
                        }
                        Pair(season, epRes.data)
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

        val episodeList = mutableListOf<SEpisode>()
        var episodeCount = 1F

        for ((season, episodes) in seasonResults) {
            val seasonNum = season.seasonNumber ?: 1
            val cleanTitle = cleanSeasonTitle(season.title)
            val scanlatorName = if (cleanTitle.isNotBlank()) {
                String.format(Locale.US, "Season %02d: %s", seasonNum, cleanTitle)
            } else {
                String.format(Locale.US, "Season %02d", seasonNum)
            }
            val seasonSlug = season.slug ?: "season-$seasonNum"

            for (ep in episodes) {
                episodeList.add(createEpisode(ep, id, seasonSlug, scanlatorName, episodeCount++))
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterUrl = episode.url,
            hosterName = name,
        ),
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        syncWatchHistory(hoster.hosterUrl)
        val fakeEpisode = SEpisode.create().apply { url = hoster.hosterUrl }
        return extractVideos(fakeEpisode)
    }

    private suspend fun extractVideos(episode: SEpisode): List<Video> {
        val linkUrl = when {
            episode.url.contains("/episode/") -> {
                val epId = episode.url.substringAfter("/episode/").substringBefore("/").substringBefore("?")
                buildApiUrl("episode/$epId/links")
            }
            episode.url.contains("/movie/") -> {
                val movieId = episode.url.substringAfter("/movie/").substringBefore("/").substringBefore("?")
                buildApiUrl("movie/$movieId/links")
            }
            else -> buildApiUrl(episode.url)
        }
        val response = client.get(linkUrl, headers)
        val linksRes = response.parseAs<LinksResponse>()
        if (!linksRes.success) {
            throw IOException(linksRes.message ?: linksRes.error ?: "Failed to fetch stream links")
        }
        val data = linksRes.data ?: throw IOException("Stream links data is null")

        val subtitleTracks = data.subtitles.mapNotNull { sub ->
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

        val preferredAudio = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT)
            ?: PREF_AUDIO_LANG_DEFAULT
        val preferredAudioName = when (preferredAudio) {
            "hin" -> "Hindi"
            "tam" -> "Tamil"
            "tel" -> "Telugu"
            "eng" -> "English"
            else -> "Unknown"
        }
        val audioLangRegex = Regex("""(?i)(?:^|[^a-zA-Z])${Regex.escape(preferredAudio)}(?:[^a-zA-Z]|$)""")

        val sortedLinks = data.links.sortedByDescending { link ->
            when {
                link.name.contains(preferredAudioName, ignoreCase = true) -> 2
                audioLangRegex.containsMatchIn(link.name) -> 2
                else -> 1
            }
        }

        val videoList = mutableListOf<Video>()
        for (link in sortedLinks) {
            if (link.url.isBlank()) continue
            try {
                if (link.url.contains(".m3u8")) {
                    videoList.add(
                        Video(
                            videoUrl = link.url,
                            videoTitle = link.name,
                            headers = headers,
                            subtitleTracks = subtitleTracks,
                        ),
                    )

                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = link.url,
                            videoNameGen = { quality -> "${link.name} - $quality" },
                            subtitleList = subtitleTracks,
                        ).map { video ->
                            val fixedUrl = if (!video.videoUrl.contains(".m3u8")) {
                                "${video.videoUrl}#.m3u8"
                            } else {
                                video.videoUrl
                            }
                            Video(
                                videoUrl = fixedUrl,
                                videoTitle = video.videoTitle,
                                headers = headers,
                                subtitleTracks = (video.subtitleTracks.orEmpty() + subtitleTracks).distinctBy { it.url },
                                audioTracks = video.audioTracks,
                            )
                        }
                    }.getOrNull()?.let { subVideos ->
                        videoList.addAll(subVideos.filterNot { it.videoUrl == link.url })
                    }
                } else {
                    videoList.add(
                        Video(
                            videoUrl = link.url,
                            videoTitle = link.name,
                            headers = headers,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            } catch (_: Exception) {
                // Skip failed link and continue with remaining links
            }
        }
        return videoList.distinctBy { it.videoUrl }.sortedByDescending { video ->
            when {
                video.videoTitle.contains(preferredAudioName, ignoreCase = true) -> 2
                audioLangRegex.containsMatchIn(video.videoTitle) -> 2
                else -> 1
            }
        }
    }

    private fun cleanSeasonTitle(title: String?): String {
        val raw = title?.trim().orEmpty()
        return raw.replace(SEASON_PREFIX_REGEX, "").trim()
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

    private fun syncWatchHistory(episodeUrl: String) {
        val syncEnabled = preferences.getBoolean(PREF_SYNC_WATCH_HISTORY_KEY, true)
        if (!syncEnabled) return

        val token = getCleanToken()
        if (token.isBlank()) return

        val syncUrl = when {
            episodeUrl.contains("movie/") -> {
                val movieId = episodeUrl.substringAfter("movie/").substringBefore("/links").substringBefore("/").substringBefore("?")
                if (movieId.isBlank()) return
                "$apiUrl/api/stremio/stream/movie/kartoons:$movieId.json?token=$token"
            }
            episodeUrl.contains("episode/") -> {
                val episodeId = episodeUrl.substringAfter("episode/").substringBefore("/links").substringBefore("/").substringBefore("?")
                if (episodeId.isBlank()) return
                "$apiUrl/api/stremio/stream/series/kartoons:$episodeId.json?token=$token"
            }
            else -> null
        } ?: return

        syncExecutor.execute {
            try {
                client.newCall(GET(syncUrl, headers)).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Watch history sync returned HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Watch history sync failed: ${e.message}")
            }
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
                "3. Copy the token from the Manifest URL (after ?token=)\n" +
                "4. Paste it here"
            setOnPreferenceChangeListener { preference, newValue ->
                val str = (newValue as String).trim()
                preferences.edit().putString(key, str).commit()
                preference.summary = if (str.isNotBlank()) "Token set (length: ${str.length})" else "Tap to enter token"
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_LANG_KEY
            title = "Preferred Audio Language"
            entries = arrayOf("Hindi", "Tamil", "Telugu", "English")
            entryValues = arrayOf("hin", "tam", "tel", "eng")
            setDefaultValue(PREF_AUDIO_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SYNC_WATCH_HISTORY_KEY
            title = "Sync Watch Activity to Website"
            summary = "Updates Continue Watching on $baseUrl when you play a title"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private fun getCleanToken(): String {
        val raw = preferences.getString(TOKEN_KEY, "")?.trim().orEmpty()
        if (raw.isBlank()) return ""
        return if (raw.contains("token=")) {
            raw.substringAfter("token=").substringBefore("&").trim()
        } else {
            raw
        }
    }

    private fun buildApiUrl(pathWithQuery: String): String {
        val token = getCleanToken()
        if (token.isBlank()) {
            throw IOException(
                "User token is required. Configure your token in extension settings (Settings > Browse > Anime extensions > $name).",
            )
        }
        val sep = if (pathWithQuery.contains("?")) "&" else "?"
        return "$apiUrl/api/cloudstream/$pathWithQuery${sep}token=$token"
    }

    private fun buildStremioCatalogUrl(path: String, page: Int): String {
        val token = getCleanToken()
        if (token.isBlank()) {
            throw IOException(
                "User token is required to view your watchlist and continue-watching. Set your token in the extension settings (Settings > Browse > Anime extensions > $name).",
            )
        }
        val cleanPath = path.removePrefix("/")
        return if (page <= 1) {
            "$apiUrl/api/stremio/$cleanPath.json?token=$token"
        } else {
            val skip = (page - 1) * 20
            "$apiUrl/api/stremio/$cleanPath/skip=$skip.json?token=$token"
        }
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${if (anime.url.startsWith("/")) "" else "/"}${anime.url}"

    override fun getEpisodeUrl(episode: SEpisode): String = if (episode.url.startsWith("http://") || episode.url.startsWith("https://")) {
        episode.url
    } else {
        val path = if (episode.url.startsWith("/")) episode.url else "/${episode.url}"
        "$baseUrl$path"
    }

    companion object {
        private const val TAG = "Kartoons"
        private val SEASON_PREFIX_REGEX = Regex("""^Season\s+\d+\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
        private const val TOKEN_KEY = "token"
        private const val PREF_AUDIO_LANG_KEY = "pref_audio_lang"
        private const val PREF_AUDIO_LANG_DEFAULT = "hin"
        private const val PREF_HOME_CATALOG_KEY = "pref_home_catalog"
        private const val PREF_HOME_CATALOG_DEFAULT = "catalog/show?catalog=popular"
        private const val PREF_SYNC_WATCH_HISTORY_KEY = "pref_sync_watch_history"
    }
}
