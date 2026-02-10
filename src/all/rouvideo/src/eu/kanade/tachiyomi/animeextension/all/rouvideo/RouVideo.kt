package eu.kanade.tachiyomi.animeextension.all.rouvideo

import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoDto.toAnimePage
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.ALL_VIDEOS
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.FEATURED
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LATEST_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LIKE_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.WATCHING
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.categories
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class RouVideo(
    override val lang: String = "all",
) : AnimeHttpSource() {

    override val name = "肉視頻"

    override val baseUrl = "https://rou.video/home"
    private val videoUrl = "https://rou.video"

    private val apiUrl = "https://rou.video/api"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val intl = Intl(
        language = Locale.getDefault().language.takeIf { lang == "all" } ?: lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "zh", "vi"),
        classLoader = this::class.java.classLoader!!,
    )

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", videoUrl)
        add("Referer", "$videoUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", videoUrl.toHttpUrl().host)
    }.build()

    private val videoHeaders by lazy {
        headers.newBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            add("Host", apiUrl.toHttpUrl().host)
            add("Referer", "$videoUrl/")
        }.build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LIKE_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return AnimesPage(emptyList(), false)

        return json.decodeFromString<RouVideoDto.VideoList>(data)
            .props.pageProps.toAnimePage()
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = coroutineScope {
        listOf(
            async {
                client.newCall(relatedAnimeListRequest(anime))
                    .execute()
                    .let { response ->
                        relatedAnimeListParse(response)
                    }
            },
            async {
                runCatching {
                    handleSearchAnime(watchingURL, apiHeaders) {
                        json.decodeFromString<List<RouVideoDto.Video>>(body.string()).toAnimePage()
                    }
                }
                    .getOrNull()
                    ?.animes ?: emptyList()
            },
        ).awaitAll()
            .flatten()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return emptyList()

        return json.decodeFromString<RouVideoDto.VideoDetails>(data)
            .props.pageProps.relatedVideos.map { video -> video.toSAnime() }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LATEST_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Handle direct ID search (no need for tag/hot search fetching)
        if (query.startsWith(PREFIX_ID)) {
            val id = query.removePrefix(PREFIX_ID)
            return handleSearchAnime(animeUrl(id), docHeaders) {
                AnimesPage(listOf(parseAnimeDetails(this)), false)
            }
        }

        // Handle direct Tag search from query (no need for tag/hot search fetching)
        if (query.startsWith(PREFIX_TAG)) {
            val tagValue = query.removePrefix(PREFIX_TAG)
            val url = videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegments("$CATEGORY_SLUG/$tagValue")
                addQueryParameter("page", page.toString())
            }.build()
            return handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
        }

        // For other search/browse types, ensure tags and hot searches are fetched
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        val categoryFilter = filters.filterIsInstance<RouVideoFilter.CategoryFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<RouVideoFilter.SortFilter>().firstOrNull()
        val tagFilter = filters.filterIsInstance<RouVideoFilter.TagFilter>().firstOrNull()
        val hotSearchFilter = filters.filterIsInstance<RouVideoFilter.HotSearchFilter>().firstOrNull()

        val categoryUriPart = categoryFilter?.toUriPart()

        if (query.isBlank() || categoryUriPart == FEATURED) {
            // Browsing scenarios (no text query)
            return when (categoryUriPart) {
                WATCHING -> {
                    handleSearchAnime(watchingURL, apiHeaders) {
                        json.decodeFromString<List<RouVideoDto.Video>>(body.string()).toAnimePage()
                    }
                }

                FEATURED, null, "" -> { // "Featured", "No category", or "All Categories" -> Show featured content
                    handleSearchAnime(featuredURL, docHeaders) {
                        asJsoup().parseFeaturedPage(sortFilter)
                    }
                }

                else -> {
                    // Specific category (e.g., "asian") or "All Videos" (ALL_VIDEOS)
                    val url = buildBrowseUrl(page, categoryUriPart, sortFilter, tagFilter, hotSearchFilter)
                    handleSearchAnime(url, docHeaders, ::popularAnimeParse)
                }
            }
        } else {
            // Text search scenario
            val url = videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("search")
                addQueryParameter("q", query)

                // Add category to search query if it's a specific one (not null or empty string)
                if (!categoryUriPart.isNullOrEmpty()) {
                    addQueryParameter(CATEGORY_SLUG, categoryUriPart)
                }
                addQueryParameter("page", page.toString())
                // Sort filter is not applied for text search
            }.build()
            return handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
        }
    }

    private fun Document.parseFeaturedPage(sortFilter: RouVideoFilter.SortFilter?): AnimesPage = this.selectFirst("script#__NEXT_DATA__")?.data()
        ?.let {
            json.decodeFromString<RouVideoDto.HotVideoList>(it)
                .props.pageProps.toAnimePage(sortFilter?.toUriPart())
        }
        ?: AnimesPage(emptyList(), false)

    private fun buildBrowseUrl(
        page: Int,
        categoryUri: String?, // Expects specific category (e.g. "asian") or ALL_VIDEOS.
        sortFilter: RouVideoFilter.SortFilter?,
        tagFilter: RouVideoFilter.TagFilter?,
        hotSearchFilter: RouVideoFilter.HotSearchFilter?,
    ): String = videoUrl.toHttpUrl().newBuilder().apply {
        when {
            // Specific category (e.g., "asian") is provided
            categoryUri != null && categoryUri != ALL_VIDEOS -> {
                addPathSegments("$CATEGORY_SLUG/$categoryUri")
            }

            // Tag filter is active
            tagFilter?.isEmpty() == false -> {
                if (hotSearchFilter?.isEmpty() == false) {
                    // Hot search filter is active => search within the tag
                    addPathSegment("search")
                    addQueryParameter("q", hotSearchFilter.toUriPart())
                    addQueryParameter(CATEGORY_SLUG, tagFilter.toUriPart())
                } else {
                    // Only tag filter is active => browse by tag
                    addPathSegments("$CATEGORY_SLUG/${tagFilter.toUriPart()}")
                }
            }

            else -> {
                // Default to browsing all videos
                addPathSegment(VIDEO_SLUG)
            }
        }

        // Add sorting and pagination parameters
        sortFilter?.let { addQueryParameter("order", it.toUriPart()) }
        addQueryParameter("page", page.toString())
    }.build().toString()

    private suspend fun handleSearchAnime(url: String, headers: Headers, parse: Response.() -> AnimesPage): AnimesPage = client.newCall(GET(url, headers))
        .awaitSuccess()
        .use(parse)

    private val featuredURL = baseUrl
    private val watchingURL by lazy { "$apiUrl/$VIDEO_SLUG/watching" }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        RouVideoFilter.SortFilter(intl),
        AnimeFilter.Header(intl["sort_filter_note"]),
        RouVideoFilter.CategoryFilter(intl),
        AnimeFilter.Separator(),
        RouVideoFilter.TagFilter(
            intl,
            if (!this::tagsArray.isInitialized && savedTags.isEmpty()) {
                arrayOf(Tag(intl["reset_filter_to_load"], ""))
            } else {
                setOf(Tag(intl["set_video_all_to_filter_tag"], ""))
                    .plus(if (this::tagsArray.isInitialized) tagsArray.toSet() else emptySet())
                    .plus(savedTags.minus(categories(intl)))
                    .toTypedArray()
            },
        ),
        RouVideoFilter.HotSearchFilter(
            intl,
            if (!this::hotSearch.isInitialized || hotSearch.isEmpty()) {
                setOf(Pair(intl["reset_filter_to_load"], ""))
            } else {
                setOf(Pair(intl["set_video_all_to_filter_tag"], ""))
                    .plus(hotSearch.map { it to it })
            },
        ),
    )

    /**
     * Automatically fetched tags from the source to be used in the filters.
     */
    private lateinit var tagsArray: Tags

    /**
     * The request to the page that have the tags list.
     */
    private fun tagsListRequest() = GET("$videoUrl/cat", docHeaders)

    /**
     * Fetch the genres from the source to be used in the filters.
     */
    private fun fetchTagsListOnce() {
        if (!this::tagsArray.isInitialized) {
            runCatching {
                client.newCall(tagsListRequest())
                    .execute()
                    .asJsoup()
                    .let(::tagsListParse)
                    .let { tags ->
                        if (tags.isNotEmpty()) {
                            tagsArray = tags
                        }
                    }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * Get the genres from the document.
     */
    private fun tagsListParse(document: Document): Tags = document.selectFirst("script#__NEXT_DATA__")?.data()
        ?.let {
            json.decodeFromString<RouVideoDto.TagList>(it)
                .props.pageProps.toTagList()
        }
        ?: emptyArray<Tag>()

    private var savedTags: Set<Tag> = loadTagListFromPreferences()
        set(value) {
            preferences.edit().putStringSet(
                TAG_LIST_PREF,
                value.map { it.first }.toSet(),
            ).apply()
            field = value
        }

    private fun loadTagListFromPreferences(): Set<Tag> = preferences.getStringSet(TAG_LIST_PREF, emptySet())
        ?.mapNotNull { Tag(it, it) }
        ?.toSet()
        ?: emptySet()

    private lateinit var hotSearch: Set<String>

    private fun hotSearchRequest() = GET("$videoUrl/search", docHeaders)

    private fun updateHotSearch() {
        runCatching {
            client.newCall(hotSearchRequest())
                .execute()
                .asJsoup()
                .let(::hotSearchParse)
                .let {
                    hotSearch = if (!this::hotSearch.isInitialized) {
                        it
                    } else {
                        hotSearch.plus(it)
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    private fun hotSearchParse(document: Document): Set<String> = document.selectFirst("script#__NEXT_DATA__")?.data()
        ?.let {
            val hotSearches = json.decodeFromString<RouVideoDto.VideoList>(it)
                .props.pageProps.hotSearches
            hotSearches?.toSet()
        }
        ?: emptySet()

    // =========================== Anime Details ============================

    private fun animeUrl(id: String) = "$videoUrl/$VIDEO_SLUG/$id"
    override fun getAnimeUrl(anime: SAnime) = animeUrl(anime.url)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val resolution = anime.description?.let { resolutionRegex.find(it) }
            ?.groupValues?.get(1)
        return client.newCall(animeDetailsRequest(anime))
            .execute()
            .let { response ->
                parseAnimeDetails(response, resolution)
            }
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(getAnimeUrl(anime), docHeaders)

    override fun animeDetailsParse(response: Response): SAnime = parseAnimeDetails(response)

    private fun parseAnimeDetails(response: Response, resolution: String? = null): SAnime {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return SAnime.create()
        val video = json.decodeFromString<RouVideoDto.VideoDetails>(data).props.pageProps.video

        savedTags = savedTags.plus(video.getTagList())

        return video.toSAnime()
            .apply {
                // Search & RelatedVideos doesn't have likeCount while AnimeDetails doesn't have resolution
                val resolutionSet = description?.matches(resolutionRegex) ?: false
                if (!resolutionSet && !resolution.isNullOrBlank()) {
                    description = "${resolutionDesc(resolution)}\n$description"
                }
            }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$videoUrl/$VIDEO_SLUG/${anime.url}", docHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val video = json.decodeFromString<RouVideoDto.VideoDetails>(data).props.pageProps.video

        return listOf(video.toEpisode())
    }

    override fun getEpisodeUrl(episode: SEpisode) = "$videoUrl/$VIDEO_SLUG/${episode.url}"

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/$VIDEO_SLUG/${episode.url}", apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val jsonStr = response.body.string()
        val data = json.decodeFromString<RouVideoDto.VideoData>(jsonStr).video

        return playlistUtils.extractFromHls(
            playlistUrl = data.videoUrl,
            referer = "$videoUrl/",
        )
    }

    // Sorts by quality
    override fun List<Video>.sort(): List<Video> = sortedByDescending { it.quality }

    // ============================= Utilities ==============================

    private val resolutionRegex = Regex("""Resolution: (\d+)p""")
    companion object {
        internal fun resolutionDesc(resolution: String) = "Resolution: ${resolution}p"

        private const val VIDEO_SLUG = "v"
        private const val CATEGORY_SLUG = "t"

        private const val TAG_LIST_PREF = "TAG_LIST"

        const val PREFIX_ID = "$VIDEO_SLUG:"
        const val PREFIX_TAG = "$CATEGORY_SLUG:"
    }
}
