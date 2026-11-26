package eu.kanade.tachiyomi.animeextension.it.animeunity

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.source.model.Related
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.source.model.VideoThumbnails
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

@Source
abstract class AnimeUnity :
    AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val hasRelatedAnime = true

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers
        get() = headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = client
        .get("$baseUrl/api/top-anime?popular=true&page=$page", apiHeaders)
        .parseAs<AnimePageDto>()
        .toAnimesPage()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = client
        .get("$baseUrl/api/home/latest-episodes?page=$page", apiHeaders)
        .parseAs<LatestPageDto>()
        .toAnimesPage()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/api/archive".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) addQueryParameter("query", query)
            AnimeUnityFilters.applyTo(this, filters)
        }.build()

        return client.get(url, apiHeaders)
            .parseAs<AnimePageDto>()
            .toAnimesPage()
    }

    override fun getFilterList(): AnimeFilterList = AnimeUnityFilters.FILTER_LIST

    // =========================== Anime Update =============================

    // Stored urls are "id-slug", without a path
    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    // The anime page holds both the details and the first episodes, so it is fetched only once
    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime, episodes)

        val props = client.get(getAnimeUrl(anime), headers)
            .asJsoup()
            .inertiaProps<AnimeProps>()

        return SAnimeUpdate(
            anime = if (fetchDetails) props.anime.toSAnimeDetails().apply { url = anime.url } else anime,
            episodes = if (fetchEpisodes) episodeList(anime, props.loadedEpisodes) else episodes,
        )
    }

    // =============================== Related ==============================

    override suspend fun fetchRelatedAnime(anime: SAnime): List<Related> {
        val props = client.get(getAnimeUrl(anime), headers)
            .asJsoup()
            .inertiaProps<RelatedProps>()
        val animeId = anime.url.substringBefore("-")

        return listOf(
            // The site lists the anime itself among its related ones
            "Correlati" to props.relatedAnimes.filterNot { it.id.toString() == animeId },
            "Consigliati" to props.recommendedAnimes,
        )
            .filter { (_, animes) -> animes.isNotEmpty() }
            .map { (name, animes) -> Related(name, animes.map { it.toSAnime() }) }
    }

    // ============================== Episodes ==============================

    private suspend fun episodeList(anime: SAnime, loaded: EpisodesDto): List<SEpisode> {
        val animeId = anime.url.substringBefore("-")

        val episodes = loaded.episodes.toMutableList()
        for (start in loaded.pageStarts) {
            if (start == loaded.start) continue
            episodes += client.get("$baseUrl/api/anime/$animeId/episodes?start=$start", apiHeaders)
                .parseAs<EpisodesDto>()
                .episodes
        }

        return episodes
            .mapNotNull { episode ->
                val videos = episode.videos
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

    override fun getEpisodeUrl(episode: SEpisode): String {
        val url = (baseUrl + episode.url).toHttpUrl()
        val videos = url.videos()
        val language = preferredLanguage
        val video = videos.firstOrNull { it.first == language }
            ?: videos.firstOrNull()
            ?: return url.toString()

        return "$baseUrl${url.encodedPath}/${video.second}"
    }

    /** The (language, video id) pairs stored in an episode url. */
    private fun HttpUrl.videos(): List<Pair<String, String>> = queryParameterValues("video")
        .filterNotNull()
        .map { it.substringBefore("-").uppercase() to it.substringAfter("-") }

    // ============================== Hosters ===============================

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val videos = url.videos()
        check(videos.isNotEmpty()) { "Episodio salvato con il vecchio formato: aggiorna la lista episodi" }

        return videos.map { (language, videoId) ->
            Hoster(
                hosterUrl = "$baseUrl${url.encodedPath}/$videoId",
                hosterName = language,
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val language = preferredLanguage

        return sortedByDescending { it.hosterName == language }
    }

    // =============================== Videos ===============================

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val embedUrl = client.get(hoster.hosterUrl, headers)
            .asJsoup()
            .inertiaProps<EmbedProps>()
            .embedUrl

        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Referer", "$baseUrl/")
            .build()

        // One script holds the playlist, the skip times and the thumbnails
        val script = client.get(embedUrl, iframeHeaders)
            .asJsoup()
            .selectFirst("script:containsData(masterPlaylist)")!!
            .data()
            .replace("\n", "\t")
        val playlistUrl = PLAYLIST_REGEX.find(script)!!.groupValues[1]
        val expires = EXPIRES_REGEX.find(script)!!.groupValues[1]
        val token = TOKEN_REGEX.find(script)!!.groupValues[1]
        // Skip times and seek previews are extras: not every video has them
        val timestamps = parseTimestamps(script)
        val thumbnailsUrl = THUMBNAILS_REGEX.find(script)?.groupValues?.get(1).orEmpty()

        val masterPlUrl = buildString {
            append(playlistUrl)
            append(if (playlistUrl.contains('?')) '&' else '?')
            append("h=1&token=")
            append(token)
            append("&expires=")
            append(expires)
        }
        val masterPl = client.get(masterPlUrl, headers).use { it.body.string() }

        return RENDITION_REGEX.findAll(masterPl).map { match ->
            var videoUrl = match.groupValues[0]
            val filename = match.groupValues[1]
            if (!filename.endsWith(".m3u8")) {
                videoUrl = videoUrl.replaceFirst("/playlist/$filename?", "/playlist/$filename.m3u8?")
            }
            val quality = match.groupValues[2]
            Video(
                videoUrl = videoUrl,
                videoTitle = "${quality}p - ${hoster.hosterName}",
                timestamps = timestamps,
                internalData = thumbnailsUrl,
            )
        }.toList()
    }

    // Logged and skipped on failure, so bad skip times don't stop playback
    private fun parseTimestamps(script: String): List<TimeStamp> {
        val json = TIMESTAMPS_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()

        return runCatching { json.parseAs<List<TimestampDto>>().mapNotNull { it.toTimeStamp() } }
            .onFailure { Log.e(name, "Invalid skip times", it) }
            .getOrDefault(emptyList())
    }

    private fun TimestampDto.toTimeStamp(): TimeStamp? {
        if (endTime <= startTime) return null

        return when (kind) {
            "intro" -> TimeStamp(startTime, endTime, "Intro", ChapterType.Opening)
            "outro" -> TimeStamp(startTime, endTime, "Outro", ChapterType.Ending)
            else -> null
        }
    }

    override suspend fun fetchVideoThumbnails(video: Video): VideoThumbnails? {
        val url = video.internalData.toHttpUrlOrNull() ?: return null
        val response = client.get(url, headers, ensureSuccess = false)
        // The player announces a track even for videos that have none
        if (!response.isSuccessful) {
            response.close()
            return null
        }

        return VideoThumbnails.fromVtt(response)
    }

    // ============================= Utilities ==============================

    private inline fun <reified T> Document.inertiaProps(): T = selectFirst("#app[data-page]")!!
        .attr("data-page")
        .parseAs<InertiaDto<T>>()
        .props

    private val preferredLanguage
        get() = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            // The start of the title, since "80p" is also part of "480p" and "1080p"
            compareByDescending<Video> { it.videoTitle.startsWith("${quality}p") }
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
        private val TIMESTAMPS_REGEX = Regex("""timestamps: ?(\[.*?])""")
        private val THUMBNAILS_REGEX = Regex("""thumbnailsUrl ?= ?'(.*?)'""")
        private val RENDITION_REGEX = Regex("""https?://[^/\s]+/playlist/(.+)\?.*type=video&.*rendition=(\d+).*""")
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
