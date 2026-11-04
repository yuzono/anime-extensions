package eu.kanade.tachiyomi.animeextension.all.kartoons

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

@Source
abstract class Kartoons :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

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
        val animeList = catalog.data.map { item ->
            SAnime.create().apply {
                val type = item.type?.ifBlank { "show" } ?: "show"
                this.url = "/$type/${item.id}"
                title = item.title.trim()
                thumbnail_url = item.image ?: item.coverImage
            }
        }
        return AnimesPage(animeList, catalog.data.size >= 20)
    }

    private fun parseStremioCatalog(response: Response): AnimesPage {
        val catalog = response.parseAs<StremioCatalogResponse>()
        val animeList = catalog.metas.mapNotNull { meta ->
            val rawId = meta.id?.removePrefix("kartoons:")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = if (meta.type == "movie") "movie" else "show"
            SAnime.create().apply {
                this.url = "/$type/$rawId"
                title = meta.name?.trim().orEmpty()
                thumbnail_url = meta.poster
                description = meta.description
            }
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
            buildApiUrl("search?q=$encodedQuery&limit=20")
        } else {
            val catalogPath = filters.filterIsInstance<KartoonsFilters.CatalogFilter>()
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
            if (uri != null) {
                val segments = uri.pathSegments.filter { it.isNotEmpty() }
                val type = segments.getOrNull(0)
                val id = segments.getOrNull(1)
                if ((type == "show" || type == "movie") && !id.isNullOrBlank()) {
                    val fakeAnime = SAnime.create().apply { this.url = "/$type/$id" }
                    val details = getAnimeDetails(fakeAnime)
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
            val animeList = search.data.map { item ->
                SAnime.create().apply {
                    val type = item.type?.ifBlank { "show" } ?: "show"
                    this.url = "/$type/${item.id}"
                    title = item.title.trim()
                    thumbnail_url = item.image
                }
            }
            AnimesPage(animeList, false)
        } else {
            popularAnimeParse(response)
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.startsWith("/movie/")
        val id = anime.url.substringAfterLast("/")
        val apiUrl = if (isMovie) {
            buildApiUrl("movie/$id")
        } else {
            buildApiUrl("show/$id")
        }

        val response = client.get(apiUrl, headers)
        return if (isMovie) {
            val res = response.parseAs<MovieDetailsResponse>()
            val data = res.data
            SAnime.create().apply {
                this.url = anime.url
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = SAnime.COMPLETED
                initialized = true
            }
        } else {
            val res = response.parseAs<ShowDetailsResponse>()
            val data = res.data
            SAnime.create().apply {
                this.url = anime.url
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = SAnime.UNKNOWN
                initialized = true
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val isMovie = anime.url.startsWith("/movie/")
        val id = anime.url.substringAfterLast("/")

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = buildApiUrl("movie/$id/links")
                },
            )
        }

        val showUrl = buildApiUrl("show/$id")
        val showRes = client.get(showUrl, headers).parseAs<ShowDetailsResponse>()
        val seasons = showRes.data.seasons

        val episodeList = mutableListOf<SEpisode>()
        var episodeCount = 1F
        var firstException: Exception? = null

        for (season in seasons) {
            val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: continue
            val episodesUrl = buildApiUrl("show/$id/season/$seasonSlug/episodes")
            try {
                val epRes = client.get(episodesUrl, headers).parseAs<EpisodesResponse>()
                for (ep in epRes.data) {
                    val epNum = ep.episodeNumber ?: episodeCount
                    val epTitle = ep.title?.trim().orEmpty()
                    val seasonPrefix = season.title?.let { "[$it] " } ?: ""
                    val epNumStr = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()
                    episodeList.add(
                        SEpisode.create().apply {
                            name = if (epTitle.isNotBlank()) {
                                "$seasonPrefix Episode $epNumStr: $epTitle"
                            } else {
                                "$seasonPrefix Episode $epNumStr"
                            }
                            episode_number = epNum
                            url = buildApiUrl("episode/${ep.id}/links")
                        },
                    )
                    episodeCount++
                }
            } catch (e: Exception) {
                if (firstException == null) firstException = e
            }
        }

        if (episodeList.isEmpty() && firstException != null) {
            throw firstException
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val videos = extractVideos(episode)
        return listOf(
            Hoster(
                hosterName = name,
                videoList = videos,
            ),
        )
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    private suspend fun extractVideos(episode: SEpisode): List<Video> {
        syncWatchHistory(episode.url)
        val response = client.get(episode.url, headers)
        val linksRes = response.parseAs<LinksResponse>()
        val data = linksRes.data ?: return emptyList()

        val subtitleTracks = data.subtitles.mapNotNull { sub ->
            if (sub.url.isBlank()) return@mapNotNull null
            Track(sub.url, sub.lang ?: "English")
        }

        val videoList = mutableListOf<Video>()
        for (link in data.links) {
            if (link.url.isBlank()) continue
            try {
                if (link.url.contains(".m3u8")) {
                    // Always add the direct proxy master playlist so ExoPlayer/MPV
                    // plays the full stream seamlessly with native HLS buffering
                    videoList.add(
                        Video(
                            videoUrl = link.url,
                            videoTitle = link.name,
                            headers = headers,
                            subtitleTracks = subtitleTracks,
                        ),
                    )

                    // Also extract variant streams if available, ensuring child URLs are tagged with .m3u8
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
                                subtitleTracks = subtitleTracks,
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
        return videoList.distinctBy { it.videoUrl }
    }

    private fun syncWatchHistory(episodeUrl: String) {
        val syncEnabled = preferences.getBoolean(PREF_SYNC_WATCH_HISTORY_KEY, true)
        if (!syncEnabled) return

        val token = preferences.getString(TOKEN_KEY, "")?.trim().orEmpty()
        if (token.isBlank()) return

        val syncUrl = when {
            episodeUrl.contains("/movie/") -> {
                val movieId = episodeUrl.substringAfter("/movie/").substringBefore("/links").substringBefore("?")
                if (movieId.isBlank()) return
                "$API_BASE/api/stremio/stream/movie/kartoons:$movieId.json?token=$token"
            }
            episodeUrl.contains("/episode/") -> {
                val episodeId = episodeUrl.substringAfter("/episode/").substringBefore("/links").substringBefore("?")
                if (episodeId.isBlank()) return
                "$API_BASE/api/stremio/stream/series/kartoons:$episodeId.json?token=$token"
            }
            else -> null
        } ?: return

        client.newCall(GET(syncUrl, headers)).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Non-critical background sync
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = TOKEN_KEY
            title = "Kartoons Token"
            summary = preferences.getString(TOKEN_KEY, null)?.let {
                "Token set (length: ${it.length})"
            } ?: "Tap to enter your Kartoons token from kartoons.to/profile"
            dialogTitle = "Kartoons Token"
            dialogMessage = "1. Sign in to https://kartoons.to\n" +
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
            key = PREF_HOME_CATALOG_KEY
            title = "Default Browse Catalog"
            entries = KartoonsFilters.CATALOG_OPTIONS.map { it.first }.toTypedArray()
            entryValues = KartoonsFilters.CATALOG_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(PREF_HOME_CATALOG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SYNC_WATCH_HISTORY_KEY
            title = "Sync Watch Activity to Website"
            summary = "Updates Continue Watching on kartoons.to when you play a title"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private fun buildApiUrl(pathWithQuery: String): String {
        val token = preferences.getString(TOKEN_KEY, "")?.trim().orEmpty()
        if (token.isBlank()) {
            throw Exception(
                "Kartoons token is required. Sign in at https://kartoons.to, copy your token from Profile -> Stremio tab (Manifest URL ?token=...), and paste it in extension settings.",
            )
        }
        val sep = if (pathWithQuery.contains("?")) "&" else "?"
        return "$API_BASE/api/cloudstream/$pathWithQuery${sep}token=$token"
    }

    private fun buildStremioCatalogUrl(path: String, page: Int): String {
        val token = preferences.getString(TOKEN_KEY, "")?.trim().orEmpty()
        if (token.isBlank()) {
            throw Exception(
                "Kartoons token is required to view your watchlist and continue-watching. Set your token in the extension settings (Kartoons -> Settings).",
            )
        }
        val cleanPath = path.removePrefix("/")
        return if (page <= 1) {
            "$API_BASE/api/stremio/$cleanPath.json?token=$token"
        } else {
            val skip = (page - 1) * 20
            "$API_BASE/api/stremio/$cleanPath/skip=$skip.json?token=$token"
        }
    }

    override fun getFilterList(): AnimeFilterList = KartoonsFilters.getFilterList()

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl

    companion object {
        private const val API_BASE = "https://api.kartoons.me"
        private const val TOKEN_KEY = "token"
        private const val PREF_HOME_CATALOG_KEY = "pref_home_catalog"
        private const val PREF_HOME_CATALOG_DEFAULT = "catalog/show?catalog=popular"
        private const val PREF_SYNC_WATCH_HISTORY_KEY = "pref_sync_watch_history"
    }
}
