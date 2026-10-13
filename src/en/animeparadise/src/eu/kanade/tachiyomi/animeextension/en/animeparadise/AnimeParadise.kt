package eu.kanade.tachiyomi.animeextension.en.animeparadise

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

class AnimeParadise :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeParadise"

    override val baseUrl = "https://www.animeparadise.moe"

    private val apiUrl = "https://api.animeparadise.moe"

    private val streamUrl = "https://stream.animeparadise.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    private val streamHeaders = headers.newBuilder().apply {
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = searchRequest(page, sort = "POPULARITY")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<AnimeListResponse>()
        return AnimesPage(result.data.map { it.toSAnime(titleLang) }, result.pagination.hasNext)
    }

    // =============================== Latest ===============================

    private val seenLatest = ConcurrentHashMap.newKeySet<String>()

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/ep/recently-added".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "25")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("v", "1")
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        if (page == 1) seenLatest.clear()

        val result = response.parseAs<RecentEpisodesResponse>()
        val animeList = result.data.map { it.origin }
            .filter { seenLatest.add(it.link) }
            .map { it.toSAnime(titleLang) }
        return AnimesPage(animeList, result.pagination.hasNext)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "POPULARITY"
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        return searchRequest(page, query, sort, genre)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun searchRequest(page: Int, query: String = "", sort: String, genre: String = ""): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            } else if (genre.isNotEmpty()) {
                addQueryParameter("genres", genre)
            }
            addQueryParameter("v", "1")
        }.build()
        return GET(url, apiHeaders)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Genre filter is ignored if using search text"),
        SortFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Sort by",
            arrayOf(
                Pair("Popularity", "POPULARITY"),
                Pair("Release date", "RELEASE_DATE"),
                Pair("Post date", "POST_DATE"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("<select>", ""),
                Pair("Action", "Action"),
                Pair("Adventure", "Adventure"),
                Pair("Comedy", "Comedy"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Fantasy", "Fantasy"),
                Pair("Horror", "Horror"),
                Pair("Mecha", "Mecha"),
                Pair("Music", "Music"),
                Pair("Mystery", "Mystery"),
                Pair("Psychological", "Psychological"),
                Pair("Romance", "Romance"),
                Pair("Sci-Fi", "Sci-Fi"),
                Pair("Slice of Life", "Slice of Life"),
                Pair("Sports", "Sports"),
                Pair("Supernatural", "Supernatural"),
                Pair("Thriller", "Thriller"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url.parseAs<LinkData>().slug}"

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = anime.url.parseAs<LinkData>()
        return GET("$apiUrl/anime/${data.slug}", apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<AnimeDetailsResponse>().data.toSAnime(titleLang)

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val data = anime.url.parseAs<LinkData>()
        return GET("$apiUrl/anime/${data.id}/episode", apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = response.parseAs<EpisodeListResponse>().data
        .map { it.toSEpisode() }
        .reversed()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override fun hosterListRequest(episode: SEpisode): Request {
        val watchUrl = (baseUrl + episode.url).toHttpUrl()
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("ep")
            .addPathSegment(watchUrl.pathSegments[1])
            .addQueryParameter("origin", watchUrl.queryParameter("origin"))
            .build()
        return GET(url, apiHeaders)
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val episode = response.parseAs<EpisodeDataResponse>().data.episode
        val streamLink = episode.streamLink ?: return emptyList()
        val playlistUrl = "$streamUrl/m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("url", streamLink)
            .build()
            .toString()
        return listOf(
            Hoster(hosterUrl = playlistUrl, hosterName = "AnimeParadise", internalData = episode.toJsonString()),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episode = hoster.internalData.parseAs<StreamEpisode>()
        val subData = episode.subData.orEmpty()
        val subtitleList = subData.filter { it.type == "ass" || it.type == "vtt" }
            .groupBy { it.label }
            .values
            .map { tracks -> tracks.firstOrNull { it.type == "ass" } ?: tracks.first() }
            .map {
                val subUrl = if (it.src.startsWith("http")) it.src else "$apiUrl/stream/file/${it.src}"
                Track(subUrl, it.label)
            }

        return playlistUtils.extractFromHls(
            playlistUrl = hoster.hosterUrl,
            referer = "$baseUrl/",
            masterHeaders = streamHeaders,
            videoHeaders = streamHeaders,
            subtitleList = subtitleList,
        ).sortVideos()
    }

    // ============================= Utilities ==============================

    private val titleLang get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"

        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred title language"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "english", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s\nFalls back to Romaji when unavailable. Refresh library entries to apply."
        }.also(screen::addPreference)
    }
}
