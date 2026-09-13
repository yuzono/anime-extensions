package eu.kanade.tachiyomi.animeextension.id.oploverz

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Oploverz :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name: String = "Oploverz"
    override val baseUrl: String = "https://oploverz.site"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val apiUrl: String = "https://backapi.oploverz.ac"

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/api/series?page=$page&pageSize=$ANIME_PAGE_SIZE", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (animes, hasNextPage) = response.parseAs<SeriesListResponseDto>().toAnimesPage()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/episodes?page=$page&pageSize=$ANIME_PAGE_SIZE&sort=latest", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val (animes, hasNextPage) = response.parseAs<LatestEpisodesResponseDto>().toAnimesPage()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", ANIME_PAGE_SIZE.toString())
            .apply {
                if (query.isNotEmpty()) addQueryParameter("q", query)
                OploverzFilters.getGenreParam(filters).takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("genres", it) }
            }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = OploverzFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/api/series/${anime.slug()}", headers)

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<SeriesResponseDto>().toSAnime()

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/api/series/${anime.slug()}/episodes?pageSize=$EPISODE_PAGE_SIZE", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.pathSegments[2]
        return response.parseAs<EpisodeListResponseDto>().toSEpisodeList(slug)
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    // ============================ Video Links =============================

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val videoHeaders by lazy { headersBuilder().set("Referer", "$baseUrl/").build() }

    override fun videoListRequest(episode: SEpisode): Request {
        val (slug, number) = episode.slugAndNumber()
        return GET("$apiUrl/api/series/$slug/episodes/$number", headers)
    }

    override fun videoListParse(response: Response): List<Video> = response.parseAs<EpisodeDetailResponseDto>().streams
        .parallelCatchingFlatMapBlocking { getVideosFromStream(it) }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.videoTitle.contains(quality) })
    }

    private fun getVideosFromStream(stream: StreamDto): List<Video> {
        val url = stream.url
        val prefix = stream.source
        return when {
            "dailymotion" in url -> dailymotionExtractor.videosFromUrl(url, "$prefix Dailymotion - ")
            "filedon.co" in url -> getFiledonVideo(url, prefix)
            else -> getXFileSharingVideos(url, prefix).ifEmpty {
                universalExtractor.videosFromUrl(url, videoHeaders, prefix = prefix)
            }
        }
    }

    private fun getFiledonVideo(url: String, quality: String): List<Video> {
        val doc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()
        val dataPage = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
        val videoUrl = dataPage.parseAs<FiledonPageDto>().videoUrl
        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }

    // Handles XFileSharing-style hosts (e.g. upbolt.to): the embed page auto-submits
    // a form to /dl, whose response contains a (usually packed) player script with the source.
    private fun getXFileSharingVideos(url: String, quality: String): List<Video> = runCatching {
        val embedUrl = url.toHttpUrl()
        val code = embedUrl.pathSegments.last()
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val form = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .add("referer", "")
            .build()
        val dlHeaders = videoHeaders.newBuilder().set("Referer", url).build()
        val body = client.newCall(POST("$origin/dl", dlHeaders, form)).execute().body.string()
        val unpacked = autoUnpacker(body) ?: body
        val videoUrl = XFS_SOURCE_REGEX.find(unpacked)?.groupValues?.get(1) ?: return@runCatching emptyList()
        if ("m3u8" in videoUrl) {
            playlistUtils.extractFromHls(
                playlistUrl = videoUrl,
                referer = url,
                masterHeaders = dlHeaders,
                videoHeaders = dlHeaders,
                videoNameGen = { "$quality - $it" },
            )
        } else {
            listOf(Video(videoUrl, quality, videoUrl, dlHeaders))
        }
    }.getOrDefault(emptyList())

    // ============================= Utilities ==============================

    private fun SAnime.slug(): String = url.substringAfter("/series/").substringBefore("/")

    private fun SEpisode.slugAndNumber(): Pair<String, String> {
        val segments = url.trim('/').split("/")
        return segments[1] to segments[3]
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val ANIME_PAGE_SIZE = 20
        private const val EPISODE_PAGE_SIZE = 2000

        private val XFS_SOURCE_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
