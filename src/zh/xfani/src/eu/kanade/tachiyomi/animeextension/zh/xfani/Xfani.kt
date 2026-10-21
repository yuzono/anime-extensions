package eu.kanade.tachiyomi.animeextension.zh.xfani

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Request
import okhttp3.Response

class Xfani :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val baseUrl = "https://next.xifanacg.com"
    override val lang = "zh"
    override val name = "稀饭动漫"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val selectedSourceCode
        get() = SOURCE_CODES.getOrElse(preferences.getString(PREF_KEY_VIDEO_SOURCE, "0")?.toIntOrNull() ?: 0) { SOURCE_CODES[0] }

    private val apiHeaders
        get() = headers.newBuilder()
            .set("apikey", API_KEY)
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

    private inline fun <reified T> apiRequest(path: String, body: T): Request = POST(
        "$API_URL/$path",
        apiHeaders,
        body.toJsonRequestBody(),
    )

    override fun popularAnimeRequest(page: Int): Request = searchAnimeRequest(page, "", AnimeFilterList(SortFilter().apply { state = 1 }))
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent?page=$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val items = VideoParser.recent(response.asJsoup())
        return AnimesPage(VideoParser.recentPage(items, page, PAGE_SIZE).map { it.toAnime() }, page.toLong() * PAGE_SIZE < items.size)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = CatalogueRequest(
            searchTerm = query,
            pageNumber = page,
            itemsPerPage = PAGE_SIZE,
            sortBy = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "release_date",
            sortOrder = "desc",
            typeId = filters.firstInstanceOrNull<TypeFilter>()?.selected?.toIntOrNull(),
            metaTags = filters.firstInstanceOrNull<ClassFilter>()?.selected?.takeIf { it.isNotEmpty() }?.let { listOf(it) },
            format = filters.firstInstanceOrNull<VersionFilter>()?.selected?.takeIf { it.isNotEmpty() },
            releaseYear = filters.firstInstanceOrNull<YearFilter>()?.selected?.toIntOrNull(),
        )
        return apiRequest("rest/v1/rpc/search_animes", body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // The POST response does not carry the request's page number.
        val requestBody = okio.Buffer().also { response.request.body!!.writeTo(it) }.readUtf8()
        val items = response.parseAs<List<AnimeInfo>>()
        return AnimesPage(items.map { it.toAnime() }, VideoParser.hasNextPage(items, requestBody, PAGE_SIZE))
    }

    private fun animePath(anime: SAnime): String {
        require(ANIME_PATH.matches(anime.url)) { "旧番剧链接已失效，请在新站重新搜索番剧。" }
        return anime.url
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + animePath(anime), headers)
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)
    override fun animeDetailsParse(response: Response): SAnime = VideoParser.detail(response.asJsoup()).anime.toAnime().apply { initialized = true }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val detail = VideoParser.detail(response.asJsoup())
        return VideoParser.episodes(detail.sources, selectedSourceCode).map { episode ->
            SEpisode.create().apply {
                url = VideoParser.episodePath(detail.anime.id, episode, detail.sources, selectedSourceCode)
                name = VideoParser.episodeName(episode)
                episode_number = episode.number
            }
        }.reversed()
    }

    override fun hosterListRequest(episode: SEpisode): Request {
        require(EPISODE_PATH.matches(episode.url)) { "旧播放链接已失效，请刷新番剧的剧集列表后重试。" }
        return GET(baseUrl + episode.url, headers)
    }

    override fun hosterListParse(response: Response): List<Hoster> = VideoParser.hosters(VideoParser.playPage(response.asJsoup()), baseUrl)

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val request = requireNotNull(hoster.internalData).parseAs<PlaybackRequest>()
        val playback = client.newCall(apiRequest("functions/v1/issue-web-playback", request))
            .awaitSuccess().parseAs<PlaybackInfo>()
        val candidate = VideoParser.playback(playback, request.sourceId)
        return listOf(
            Video(
                videoUrl = candidate.url,
                videoTitle = candidate.quality ?: hoster.hosterName,
                headers = headers,
            ),
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter(), ClassFilter(), VersionFilter(), YearFilter(), SortFilter())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_VIDEO_SOURCE
                title = "请设置首选视频源线路"
                entries = arrayOf("主线-1", "主线-2", "备用-1")
                entryValues = arrayOf("0", "1", "2")
                setDefaultValue("0")
                summary = "%s"
            },
        )
    }

    companion object {
        private const val API_URL = "https://api.xifanacg.com"

        // Public browser publishable key from the site's Supabase client, not a user credential.
        private const val API_KEY = "sb_publishable_OBIVAWACIX6lPXrO98_z24_HcsmalkA"
        private const val PAGE_SIZE = 24
        private const val PREF_KEY_VIDEO_SOURCE = "PREF_KEY_VIDEO_SOURCE"
        private val SOURCE_CODES = listOf("xfxf1", "AL", "CS")
        private val ANIME_PATH = Regex("^/anime/\\d+$")
        private val EPISODE_PATH = Regex("^/anime/\\d+/play/\\d+(?:\\?.*)?$")
    }
}
