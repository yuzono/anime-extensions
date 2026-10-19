package eu.kanade.tachiyomi.animeextension.en.rule34video
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Rule34Video :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.e("Rule34Video", "Loading popular videos from uploader ID: $uploaderId, page: $page, URL: $url")
            GET(url, headers)
        } else {
            Log.e("Rule34Video", "Uploader filter enabled but ID is blank, loading latest updates.")
            GET("$baseUrl/latest-updates/$page/", headers)
        }
    } else {
        GET("$baseUrl/latest-updates/$page/", headers)
    }

    // Ad cards share the "item thumb" markup but have no video id.
    // Listing pages use "item thumb" cards, uploader pages use "item ma-v" cards.
    override fun popularAnimeSelector() = "div.item[data-video-card-id]"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.th, a.ma-thumb")!!.absUrl("href"))
        title = element.selectFirst("div.thumb_title, a.ma-v__t")!!.text()
        // Lazy-loaded cards keep a placeholder data: URI in src and the real image in data-rd-src
        thumbnail_url = element.selectFirst("img.thumb")?.let { img ->
            THUMBNAIL_ATTRS.map(img::absUrl).firstOrNull { it.startsWith("http") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() = (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val segments = url.pathSegments
            if (url.host != baseUrl.toHttpUrl().host || segments.size < 3 || segments[0] !in VIDEO_PATHS) {
                throw Exception("Unsupported url")
            }
            val anime = SAnime.create().apply { this.url = "/video/${segments[1]}/${segments[2]}/" }
            val details = getAnimeDetails(anime).apply { this.url = anime.url }
            return AnimesPage(listOf(details), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val orderFilter = filters.getUriPart<OrderFilter>()
        val categoryFilter = filters.getUriPart<CategoryBy>()
        val sortType = when (orderFilter) {
            "latest-updates" -> "post_date"
            "most-popular" -> "video_viewed"
            "top-rated" -> "rating"
            else -> ""
        }

        val tagFilter = (filters.find { it is TagFilter } as? TagFilter)?.state ?: ""

        val url = "$baseUrl/search_ajax.php?tag=${tagFilter.ifBlank { "." }}"
        val response = client.newCall(GET(url, headers)).execute()
        tagDocument = response.asJsoup()

        val tagSearch = filters.getUriPart<TagSearch>()

        return if (query.isNotEmpty()) {
            GET("$baseUrl/search/${query.replace(WHITESPACE_REGEX, "-")}/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch", headers)
        } else {
            GET("$baseUrl/search/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch", headers)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")!!.text()

        val videoObject = document.videoObject()
        val meta = document.select("div.vp-meta span.item_info > span").eachText()
        val artists = document.select("div.vp-group:has(> div.label:contains(Artist)) a.item span.name").eachText().joinToString()

        author = artists
        thumbnail_url = videoObject?.thumbnailUrl

        description = buildString {
            val about = videoObject?.description?.replace("\r\n", "\n")
                ?: document.selectFirst("div.vp-desc")?.wholeText()
            if (!about.isNullOrBlank()) {
                append(about.trim())
                append("\n\n")
            }

            (videoObject?.uploadDate ?: meta.getOrNull(0))?.let { append("Uploaded: $it\n") }

            if (artists.isNotEmpty()) {
                append("Artists: $artists\n")
            }

            val categories = document.select("div.vp-group:has(> div.label:contains(Categories)) a.item span")
                .eachText()
                .joinToString()
            if (categories.isNotEmpty()) {
                append("Categories: $categories\n")
            }

            val uploader = document.selectFirst("div.col:has(> div.label:contains(Uploaded by)) a.item")?.text()
            if (!uploader.isNullOrEmpty()) {
                append("Uploader: $uploader\n")
            }

            meta.getOrNull(1)?.let { append("Views: $it\n") }
            meta.getOrNull(2)?.let { append("Duration: $it\n") }
            document.select(DOWNLOAD_SELECTOR)
                .joinToString { it.ownText().substringAfter(" ") }
                .also { append("Quality: $it") }
        }

        // Characters and tags both link to /tags/; categories and artists are listed above
        genre = document.select("div.vp-tags a.tag_item[href*=/tags/]")
            .eachText()
            .joinToString()

        status = SAnime.COMPLETED
    }

    private fun Document.videoObject(): VideoObjectDto? = selectFirst("script[type=application/ld+json]")?.data()?.parseAs<VideoObjectDto>()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(episodeListRequest(anime)).awaitSuccess().asJsoup()
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Video"
                date_upload = dateFormat.tryParse(document.videoObject()?.uploadDate)
            },
        )
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val headers = headersBuilder()
            .apply {
                val cookies = client.cookieJar.loadForRequest(response.request.url)
                    .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
                    .map { "${it.name}=${it.value}" }
                    .joinToString("; ")
                val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")
                xsrfToken?.let { add("X-XSRF-TOKEN", it) }
                add("Cookie", cookies)
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Referer", response.request.url.toString())
                add("Accept-Language", "en-US,en;q=0.5")
            }.build()

        val document = response.asJsoup()

        return document.select(DOWNLOAD_SELECTOR)
            .mapNotNull { element ->
                val originalUrl = element.attr("href")
                // We need to do that because this url returns a http 403 error
                // if you try to connect using http/1.1, which is the protocol
                // that the player uses. OkHttp uses http/2 by default, so we
                // fetch the video url first via okhttp and then pass it for the player.
                val url = noRedirectClient.newCall(GET(originalUrl, headers)).execute()
                    .use { it.headers["location"] }
                    ?: return@mapNotNull null
                val quality = element.ownText().substringAfter(" ")
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: return this
        return sortedWith(compareByDescending { it.quality == quality })
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPLOADER_FILTER_ENABLED_KEY
            title = "Filter by Uploader"
            summary = "Load videos only from the specified uploader ID."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_UPLOADER_ID_KEY
            title = "Uploader ID"
            summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
            dialogTitle = "Enter Uploader ID"
            setOnPreferenceChangeListener { _, newValue ->
                newValue?.toString().isNullOrBlank().not()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = entries
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================
    private var tagDocument = Document("")

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList.addAll(
            document.select("div.item").map {
                val tagValue = it.selectFirst("input")!!.attr("value")
                val tagName = it.selectFirst("label")!!.text()
                Pair(tagName, tagValue)
            },
        )
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
        preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
    ) {
        AnimeFilterList() // If uploader filter is enabled and ID is set, show no other filters
    } else {
        AnimeFilterList(
            OrderFilter(),
            CategoryBy(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Entered a \"tag\", click on \"filter\" then Click \"reset\" to load tags."),
            TagFilter(),
            TagSearch(tagsResults(tagDocument)),
        )
    }

    private class TagFilter : AnimeFilter.Text("Click \"reset\" without any text to load all A-Z tags.", "")

    private class TagSearch(results: Array<Pair<String, String>>) :
        UriPartFilter(
            "Tag Filter ",
            results,
        )

    private class CategoryBy :
        UriPartFilter(
            "Category Filter ",
            arrayOf(
                Pair("All", ""),
                Pair("Straight", "2109"),
                Pair("Futa", "15"),
                Pair("Gay", "192"),
                Pair("Music", "4747"),
                Pair("Iwara", "1821"),
            ),
        )

    private class OrderFilter :
        UriPartFilter(
            "Sort By ",
            arrayOf(
                Pair("Latest", "latest-updates"),
                Pair("Most Viewed", "most-popular"),
                Pair("Top Rated", "top-rated"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        private val THUMBNAIL_ATTRS = listOf("data-rd-src", "src", "data-rd-jpg", "data-original")
        private val VIDEO_PATHS = listOf("video", "videos")
        private val WHITESPACE_REGEX = Regex("\\s")
        private const val DOWNLOAD_SELECTOR = "a.tag_item_download[href]"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
