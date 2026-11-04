package eu.kanade.tachiyomi.animeextension.hi.kartoons

import androidx.preference.EditTextPreference
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
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

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
        val url = buildApiUrl("catalog/show?catalog=popular&page=$page&limit=20")
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
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

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = buildApiUrl("catalog/movie?catalog=popular&page=$page&limit=20")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            val encodedQuery = query.trim().replace(" ", "+")
            buildApiUrl("search?q=$encodedQuery&limit=20")
        } else {
            val catalogPath = filters.filterIsInstance<KartoonsFilters.CatalogFilter>()
                .firstOrNull()?.selected ?: "catalog/show?catalog=popular"
            val sep = if (catalogPath.contains("?")) "&" else "?"
            buildApiUrl("$catalogPath${sep}page=$page&limit=20")
        }
        return GET(url, headers)
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
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = SAnime.COMPLETED
            }
        } else {
            val res = response.parseAs<ShowDetailsResponse>()
            val data = res.data
            SAnime.create().apply {
                title = data.title.trim()
                description = data.description
                thumbnail_url = data.image ?: data.coverImage
                genre = data.tags.joinToString(", ")
                status = SAnime.UNKNOWN
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

        for (season in seasons) {
            val seasonSlug = season.slug ?: season.seasonNumber?.toString() ?: continue
            val episodesUrl = buildApiUrl("show/$id/season/$seasonSlug/episodes")
            try {
                val epRes = client.get(episodesUrl, headers).parseAs<EpisodesResponse>()
                for (ep in epRes.data) {
                    val epNum = ep.episodeNumber ?: episodeCount
                    val epTitle = ep.title?.trim().orEmpty()
                    val seasonPrefix = season.title?.let { "[$it] " } ?: ""
                    episodeList.add(
                        SEpisode.create().apply {
                            name = if (epTitle.isNotBlank()) {
                                "$seasonPrefix Episode ${epNum.toInt()}: $epTitle"
                            } else {
                                "$seasonPrefix Episode ${epNum.toInt()}"
                            }
                            episode_number = epNum
                            url = buildApiUrl("episode/${ep.id}/links")
                        },
                    )
                    episodeCount++
                }
            } catch (_: Exception) {
                // Continue with remaining seasons if one fails
            }
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
            if (link.url.contains(".m3u8")) {
                val videos = playlistUtils.extractFromHls(
                    playlistUrl = link.url,
                    videoNameGen = { quality -> "${link.name} - $quality" },
                    subtitleList = subtitleTracks,
                )
                videoList.addAll(videos)
            } else {
                videoList.add(
                    Video(
                        url = link.url,
                        quality = link.name,
                        videoUrl = link.url,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }
        return videoList
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

    override fun getFilterList(): AnimeFilterList = KartoonsFilters.getFilterList()

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl

    companion object {
        private const val API_BASE = "https://api.kartoons.me"
        private const val TOKEN_KEY = "token"
    }
}
