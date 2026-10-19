package eu.kanade.tachiyomi.animeextension.en.anidb

import android.util.LruCache
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
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

class AniDB :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniDB"

    override val baseUrl = "https://anilab2.amdapi.click"

    override val lang = "en"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val playHeaders by lazy {
        headersBuilder()
            .set("Referer", "https://play.app/")
            .set("X-Requested-With", "PLAY")
            .build()
    }

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val postCache by lazy { LruCache<Long, PostDto>(POST_CACHE_SIZE) }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSectionAnime(POPULAR_SECTION)

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/home", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPostsPage(latestUpdatesRequest(page))

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = query.toHttpUrlOrNull()
        if (url != null && url.host == LEGACY_HOST && url.pathSegments.firstOrNull() == "anime") {
            val anime = fetchPost(url.encodedPath.toPostId()).toSAnime()
            return AnimesPage(listOf(anime), false)
        }

        if (query.isBlank()) {
            val section = filters.firstInstanceOrNull<Filters.SectionFilter>()
            if (section != null && !section.isDefault()) return getSectionAnime(section.toUriPart())
        }

        return getPostsPage(searchAnimeRequest(page, query, filters))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        val categoryId = filters.firstInstanceOrNull<Filters.GenreFilter>()?.takeUnless { it.isDefault() }?.toUriPart()
            ?: filters.firstInstanceOrNull<Filters.ThemeFilter>()?.takeUnless { it.isDefault() }?.toUriPart()
            ?: return latestUpdatesRequest(page)

        return GET("$baseUrl/api/category?id=$categoryId&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored when searching by text"),
        AnimeFilter.Header("Only one applies: Section, then Genre, then Theme"),
        Filters.SectionFilter(),
        Filters.GenreFilter(),
        Filters.ThemeFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = postRequest(anime.url.toPostId())

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<PostDto>().toSAnime()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$PLAY_URL/api/anime/${anime.url.toPostId()}/episodes", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = response.parseAs<EpisodeListDto>().list

        val minEpNumber = episodes.mapNotNull { it.number.toFloatOrNull() }.minOrNull() ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        return episodes.map { it.toSEpisode(offset) }.reversed()
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun hosterListRequest(episode: SEpisode): Request = GET("$PLAY_URL/api/episode/${episode.url}/servers", headers)

    // Server ids are "<episodeId>/<language code>", and each one is listed twice ("Server #1", "Server #2")
    override fun hosterListParse(response: Response): List<Hoster> = response.parseAs<ServerListDto>().list
        .distinctBy { it.id }
        .map { server ->
            val langCode = server.id.substringAfterLast('/')
            Hoster(
                hosterUrl = "$PLAY_URL/api/episode/${server.id}/iframe",
                hosterName = LANGUAGES[langCode] ?: langCode.uppercase(),
                internalData = langCode,
            )
        }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedByDescending { it.internalData == langPref }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val link = client.get(hoster.hosterUrl, playHeaders).parseAs<IframeDto>().link

        return playlistUtils.extractFromHls(
            playlistUrl = link,
            masterHeaders = headers,
            videoHeaders = headers,
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedByDescending { it.videoTitle.contains(qualityPref) }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES.toTypedArray()
            entryValues = PREF_QUALITY_ENTRIES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            screen.addPreference(this)
        }

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES.toTypedArray()
            entryValues = PREF_LANG_VALUES.toTypedArray()
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            screen.addPreference(this)
        }
    }

    // ============================= Utilities ==============================

    private suspend fun getSectionAnime(name: String): AnimesPage {
        val posts = client.get(popularAnimeRequest(1).url)
            .parseAs<HomeDto>().sections
            .firstOrNull { it.name == name }
            ?.posts.orEmpty()
        return AnimesPage(posts.toSAnimeList(), false)
    }

    private suspend fun getPostsPage(request: Request): AnimesPage {
        val posts = client.get(request.url).parseAs<PostListDto>().posts
        return AnimesPage(posts.toSAnimeList(), posts.size >= PAGE_SIZE)
    }

    // List endpoints only return ids and posters, so titles come from each post
    private suspend fun List<PostItemDto>.toSAnimeList(): List<SAnime> = parallelCatchingMapNotNull { fetchPost(it.id).toSAnime() }

    private suspend fun fetchPost(id: Long): PostDto = postCache[id]
        ?: client.get(postRequest(id).url).parseAs<PostDto>()
            .also { postCache.put(id, it) }

    private fun postRequest(id: Long): Request = GET("$baseUrl/api/post?id=$id", headers)

    // Entries saved from the old site use "/anime/<slug>-<id>", where post id = id + 1e9
    private fun String.toPostId(): Long {
        val id = POST_ID_REGEX.find(trimEnd('/'))?.value?.toLong()
            ?: throw IllegalArgumentException("Invalid AniDB URL: $this")
        return if (id < POST_ID_OFFSET) id + POST_ID_OFFSET else id
    }

    companion object {
        private const val PLAY_URL = "https://play.anidb.app"
        private const val LEGACY_HOST = "anidb.app"
        private const val POPULAR_SECTION = "Most Popular"
        private const val POST_ID_OFFSET = 1_000_000_000L
        private const val POST_CACHE_SIZE = 300
        private const val PAGE_SIZE = 30

        private val POST_ID_REGEX = Regex("""\d+$""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred Language"
        private const val PREF_LANG_DEFAULT = "jpn"
        private val LANGUAGES = mapOf(
            "jpn" to "Japanese",
            "eng" to "English",
            "chi" to "Chinese",
            "kor" to "Korean",
        )
        private val PREF_LANG_ENTRIES = LANGUAGES.values.toList()
        private val PREF_LANG_VALUES = LANGUAGES.keys.toList()
    }
}
