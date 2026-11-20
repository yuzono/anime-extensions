package eu.kanade.tachiyomi.animeextension.it.animeunity

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class AnimeUnity :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers
        get() = headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/top-anime?popular=true&page=$page", headers = apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<AnimePageDto>()
        return AnimesPage(data.toAnimeList(), data.hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/home/latest-episodes?page=$page", headers = apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<LatestPageDto>()
        return AnimesPage(data.toAnimeList(), data.hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url = "$baseUrl/api/archive".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) addQueryParameter("query", query)
            AnimeUnityFilters.applyTo(this, filters)
        }.build()

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeUnityFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}", headers = headers)

    override fun animeDetailsParse(response: Response): SAnime = response.asJsoup()
        .inertiaProps<AnimeProps>()
        .anime
        .toSAnimeDetails()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.substringBefore("-")

        val first = fetchEpisodes(animeId, 1)
        val episodes = first.episodes.toMutableList()
        for (start in (1 + first.tabSize)..first.total step first.tabSize) {
            episodes += fetchEpisodes(animeId, start).episodes
        }

        return episodes
            .mapNotNull { episode ->
                val videos = episode.videos.filterNot { it.isHidden }
                if (videos.isEmpty()) return@mapNotNull null

                SEpisode.create().apply {
                    name = "Episode ${episode.number}"
                    episode_number = episode.number.substringBefore("-").toFloatOrNull() ?: 0F
                    scanlator = videos.joinToString { it.language.uppercase() }
                    url = "/anime/${anime.url}?" + videos.joinToString("&") { "video=${it.language}-${it.id}" }
                }
            }
            .sortedByDescending { it.episode_number }
    }

    private suspend fun fetchEpisodes(animeId: String, start: Int): EpisodesDto = client
        .newCall(GET("$baseUrl/api/anime/$animeId/episodes?start=$start", headers = apiHeaders))
        .awaitSuccess()
        .use { it.parseAs<EpisodesDto>() }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val animePath = url.encodedPath

        val videoList = url.queryParameterValues("video").filterNotNull().flatMap { entry ->
            val language = entry.substringBefore("-").uppercase()
            val videoId = entry.substringAfter("-")
            runCatching { extractVideos("$baseUrl$animePath/$videoId", language) }.getOrDefault(emptyList())
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList
    }

    private suspend fun extractVideos(episodeUrl: String, language: String): List<Video> {
        val embedUrl = client
            .newCall(GET(episodeUrl, headers))
            .awaitSuccess()
            .use { it.asJsoup() }
            .inertiaProps<EmbedProps>()
            .embedUrl

        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Referer", "$baseUrl/")
            .build()

        val iframe = client
            .newCall(GET(embedUrl, headers = iframeHeaders))
            .awaitSuccess()
            .use { it.asJsoup() }

        val script = iframe.select("script").find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")
        val playlistUrl = PLAYLIST_REGEX.find(script)!!.groupValues[1]
        val expires = EXPIRES_REGEX.find(script)!!.groupValues[1]
        val token = TOKEN_REGEX.find(script)!!.groupValues[1]

        val masterPlUrl = buildString {
            append(playlistUrl)
            append(if (playlistUrl.contains('?')) '&' else '?')
            append("h=1&token=")
            append(token)
            append("&expires=")
            append(expires)
        }
        val masterPl = client
            .newCall(GET(masterPlUrl, headers))
            .awaitSuccess()
            .use { it.body.string() }

        return RENDITION_REGEX.findAll(masterPl).map { match ->
            var videoUrl = match.groupValues[0]
            val filename = match.groupValues[1]
            if (!filename.endsWith(".m3u8")) {
                videoUrl = videoUrl.replaceFirst("/playlist/$filename?", "/playlist/$filename.m3u8?")
            }
            val quality = match.groupValues[2]
            Video(videoUrl = videoUrl, videoTitle = "$quality - $language")
        }.toList()
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private inline fun <reified T> Document.inertiaProps(): T = selectFirst("#app[data-page]")!!
        .attr("data-page")
        .parseAs<InertiaDto<T>>()
        .props

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.endsWith(language) }
                .thenByDescending { it.videoTitle.contains(quality) }
                .thenByDescending { it.videoTitle.substringBefore("p").toIntOrNull() ?: 0 },
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "ITA"

        private val PLAYLIST_REGEX = Regex("""url: ?'(.*?)'""")
        private val EXPIRES_REGEX = Regex("""'expires': ?'(\d+)'""")
        private val TOKEN_REGEX = Regex("""'token': ?'([\w-]+)'""")
        private val RENDITION_REGEX = Regex("""https?://[^/\s]+/playlist/(.+)\?.*type=video&.*rendition=(\d+p?).*""")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Preferred quality"
                entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
                entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_LANGUAGE_KEY
                title = "Preferred language"
                entries = arrayOf("Italiano", "Giapponese", "Inglese", "Cinese", "Coreano")
                entryValues = arrayOf("ITA", "JPN", "ENG", "CHN", "KOR")
                setDefaultValue(PREF_LANGUAGE_DEFAULT)
                summary = "%s"
            }.also(screen::addPreference)
    }
}
