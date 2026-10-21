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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

class Rule34Video :
    ParsedAnimeHttpLegacySource(),
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
    override fun popularAnimeRequest(page: Int): Request {
        // Preload the tag catalog so the filter sheet and tapping a tag on a video are responsive.
        ensureTagsLoaded()
        if (!preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
            return GET("$baseUrl/latest-updates/$page/")
        }
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.e("Rule34Video", "Loading popular videos from uploader ID: $uploaderId, page: $page, URL: $url")
            return GET(url)
        }
        Log.e("Rule34Video", "Uploader filter enabled but ID is blank, loading latest updates.")
        return GET("$baseUrl/latest-updates/$page/")
    }

    override fun popularAnimeSelector() = "div.item.thumb"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.th")!!.attr("href"))
        title = element.selectFirst("a.th div.thumb_title")!!.text()
        thumbnail_url = element.selectFirst("a.th div.img img")?.let { img ->
            listOf("data-rd-jpg", "data-rd-src", "src", "data-original")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
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
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val slug = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$slug", filters)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val orderFilter = filters.getUriPart<OrderFilter>()
        val sortType = when (orderFilter) {
            "latest-updates" -> "post_date"
            "most-popular" -> "video_viewed"
            "top-rated" -> "rating"
            "longest" -> "duration"
            "random" -> "pseudo_rand"
            else -> ""
        }

        val includedTags = linkedSetOf<String>()
        val includedCategories = linkedSetOf<String>()
        val excludedTokens = linkedSetOf<String>()
        filters.forEach { collectFilter(it, includedTags, includedCategories, excludedTokens) }
        (filters.list as? DynamicFilterList)?.collectSelectedTags(includedTags, excludedTokens)
        // A tag can be selected in two places (Categories and the A-Z list); if it ends up both
        // included and excluded, let the exclusion win so we never send a contradictory query.
        val excludedTagIds = excludedTokens.filter { it.startsWith("tag:") }.map { it.substringAfter(":") }.toSet()
        includedTags.removeAll(excludedTagIds)
        val duration = filters.find { it is DurationFilterGroup } as? DurationFilterGroup

        val params = mutableListOf<Pair<String, String>>()
        if (sortType.isNotBlank()) params += "sort_by" to sortType
        params += "from_videos" to page.toString()
        if (includedTags.isNotEmpty()) params += "tag_ids" to "all,${includedTags.joinToString(",")}"
        if (includedCategories.isNotEmpty()) params += "category_ids" to includedCategories.joinToString(",")
        duration?.min?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { params += "duration_from" to it }
        duration?.max?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { params += "duration_to" to it }
        if (excludedTokens.isNotEmpty()) params += "temp_skip_items" to excludedTokens.joinToString(",")

        if (query.isNotEmpty() && query.startsWith(PREFIX_SEARCH)) {
            val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
            return GET("$baseUrl/search/$newQuery")
        }

        val requestUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .apply {
                if (query.isNotEmpty()) {
                    addPathSegment(query.replace(Regex("\\s"), "-"))
                }
            }
            .addPathSegment("")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

        return GET(requestUrl)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")?.text().toString()

        val artistElement = document.select("[data-suggest-type=model] a.item").firstOrNull()
        author = artistElement?.text().orEmpty()

        description = buildString {
            (document.selectFirst("div.vp-desc em") ?: document.selectFirst("div.vp-desc"))?.html()
                .orEmpty()
                .replace("<br>", "\n") // Ensure single <br> tags are followed by a newline
                .let { text ->
                    append(text)
                }
            append("\n\n") // Add extra spacing

            val metaItems = document.select("div.vp-meta span.item_info > span")
            metaItems.getOrNull(0)?.text()?.let { append("Uploaded: $it\n") }

            val artist = document.select("[data-suggest-type=model] a.item")
                .eachText()
                .joinToString()
            if (artist.isNotEmpty()) {
                append("Artists: $artist\n")
            }

            val categories = document.select("[data-suggest-type=category] a.item")
                .eachText()
                .joinToString()
            if (categories.isNotEmpty()) {
                append("Categories: $categories\n")
            }

            val uploader = document.selectFirst("div.vp-credits a.video_meta_pill")?.text().orEmpty()
            if (uploader.isNotEmpty()) {
                append("Uploader: $uploader\n")
            }

            metaItems.getOrNull(1)?.text()?.let { append("Views: ${it.substringBefore(" ")}\n") }
            metaItems.getOrNull(2)?.text()?.let { append("Duration: $it\n") }
            document.select("a.tag_item_download")
                .map { qualityOf(it) }
                .joinToString()
                .also { append("Quality: $it") }
        }

        genre = document.select("[data-suggest-type=tag] a.tag_item:not(.tag_item_suggest)")
            .eachText()
            .joinToString()

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Video"
        },
    )

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private fun qualityOf(element: Element): String {
        val href = element.attr("href")
        return QUALITY_REGEX.find(href)?.groupValues?.get(1)
            ?: element.ownText().substringAfter(" ").trim()
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

        return document.select("a.tag_item_download")
            .mapNotNull { element ->
                val originalUrl = element.attr("href")
                // We need to do that because this url returns a http 403 error
                // if you try to connect using http/1.1, which is the protocol
                // that the player uses. OkHttp uses http/2 by default, so we
                // fetch the video url first via okhttp and then pass it for the player.
                val url = noRedirectClient.newCall(GET(originalUrl, headers)).execute()
                    .use { it.headers["location"] }
                    ?: return@mapNotNull null
                val quality = qualityOf(element)
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p") ?: return this
        return sortedWith(compareByDescending { it.videoTitle == quality })
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var allTags: List<Pair<String, String>>? = null

    @Volatile private var tagsRetryAfter = 0L

    private val tagsLoading = AtomicBoolean(false)

    private fun ensureTagsLoaded() {
        if (
            allTags != null ||
            System.currentTimeMillis() < tagsRetryAfter ||
            !tagsLoading.compareAndSet(false, true)
        ) {
            return
        }
        scope.launch {
            val loaded = runCatching {
                client.newCall(GET("$baseUrl/search_ajax.php?tag=.", headers)).execute().use { response ->
                    response.asJsoup().select("div.item").mapNotNull { element ->
                        val id = element.selectFirst("input")?.attr("value")
                        val name = element.selectFirst("label")?.text()
                        if (id.isNullOrBlank() || name.isNullOrBlank()) null else name to id
                    }
                }
            }.getOrNull()
            if (loaded.isNullOrEmpty()) {
                tagsRetryAfter = System.currentTimeMillis() + TAG_RETRY_COOLDOWN_MS
                tagsLoading.set(false)
            } else {
                allTags = loaded
            }
        }
    }

    private fun letterOf(name: String): String {
        val first = name.firstOrNull()?.uppercaseChar() ?: return "#"
        return if (first in 'A'..'Z') first.toString() else "#"
    }

    private fun collectFilter(
        filter: AnimeFilter<*>,
        includedTags: MutableSet<String>,
        includedCategories: MutableSet<String>,
        excludedTokens: MutableSet<String>,
    ) {
        when (filter) {
            is TagTriState -> when {
                filter.isIncluded() -> includedTags += filter.id
                filter.isExcluded() -> excludedTokens += "tag:${filter.id}"
            }
            is CategoryTriState -> when {
                filter.isIncluded() -> if (filter.token.startsWith("cat:")) includedCategories += filter.id else includedTags += filter.id
                filter.isExcluded() -> excludedTokens += filter.token
            }
            is AnimeFilter.Group<*> ->
                filter.state
                    .filterIsInstance<AnimeFilter<*>>()
                    .forEach { collectFilter(it, includedTags, includedCategories, excludedTokens) }
            else -> {}
        }
    }

    override fun getFilterList(): AnimeFilterList {
        if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
            preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
        ) {
            return AnimeFilterList() // If uploader filter is enabled and ID is set, show no other filters
        }

        ensureTagsLoaded()

        return AnimeFilterList(DynamicFilterList())
    }

    private class TagQueryFilter : AnimeFilter.Text("Tag search")

    private class TagTriState(name: String, val id: String) : AnimeFilter.TriState(name)

    private class TagLetterGroup(letter: String, tags: List<TagTriState>) : AnimeFilter.Group<TagTriState>(letter, tags)

    // Backing list of the filter dialog, rebuilt on each recomposition so the tag search filters live.
    // Tags stay at the top level so the app's searchGenre() can find a tag tapped on a video.
    private inner class DynamicFilterList : AbstractList<AnimeFilter<*>>() {
        private val queryFilter = TagQueryFilter()
        private val staticPart: List<AnimeFilter<*>> = listOf(
            OrderFilter(),
            CategoryFilter(CATEGORIES.map { (name, id, kind) -> CategoryTriState(name, id, "$kind:$id", AnimeFilter.TriState.STATE_IGNORE) }),
            DurationFilterGroup(),
            AnimeFilter.Separator(),
            queryFilter,
        )

        private var cacheAllTags: List<Pair<String, String>>? = null
        private var cacheQuery: String? = null
        private var cache: List<AnimeFilter<*>> = staticPart
        private var itemsById: Map<String, TagTriState> = emptyMap()
        private var letterGroups: List<TagLetterGroup> = emptyList()

        @Synchronized
        private fun compute(): List<AnimeFilter<*>> {
            val tags = allTags
            val query = queryFilter.state.trim()
            if (tags === cacheAllTags && query == cacheQuery) return cache

            if (tags !== cacheAllTags) {
                itemsById = buildMap { tags.orEmpty().forEach { (name, id) -> put(id, TagTriState(name, id)) } }
                letterGroups = tags.orEmpty()
                    .groupBy { letterOf(it.first) }
                    .toSortedMap()
                    .map { (letter, group) -> TagLetterGroup(letter, group.map { itemsById.getValue(it.second) }) }
            }

            cacheAllTags = tags
            cacheQuery = query
            val tagSection = when {
                tags == null -> listOf(AnimeFilter.Header("Loading the full tag list… reopen in a moment."))
                query.length < TAG_SEARCH_MIN_CHARS -> letterGroups
                else -> {
                    val matchedIds = HashSet<String>()
                    val matched = tags.filter { it.first.contains(query, ignoreCase = true) }
                        .onEach { matchedIds += it.second }
                        .map { itemsById.getValue(it.second) }
                    val selected = itemsById.values.filter { !it.isIgnored() && it.id !in matchedIds }
                    selected + matched
                }
            }
            cache = staticPart + tagSection
            return cache
        }

        override val size: Int get() = compute().size

        override fun get(index: Int): AnimeFilter<*> = compute()[index]

        fun collectSelectedTags(includedTags: MutableSet<String>, excludedTokens: MutableSet<String>) {
            compute()
            itemsById.values.forEach { tag ->
                when {
                    tag.isIncluded() -> includedTags += tag.id
                    tag.isExcluded() -> excludedTokens += "tag:${tag.id}"
                }
            }
        }
    }

    private class CategoryFilter(categories: List<CategoryTriState>) : AnimeFilter.Group<CategoryTriState>("Categories", categories)

    private class CategoryTriState(name: String, val id: String, val token: String, state: Int) : AnimeFilter.TriState(name, state)

    private class DurationEntry(name: String) : AnimeFilter.Text(name)

    private class DurationFilterGroup :
        AnimeFilter.Group<DurationEntry>(
            "Duration (seconds)",
            listOf(
                DurationEntry("Min"),
                DurationEntry("Max"),
            ),
        ) {
        val min: String get() = state[0].state.trim()
        val max: String get() = state[1].state.trim()
    }

    private class OrderFilter :
        UriPartFilter(
            "Sort By ",
            arrayOf(
                Pair("Latest", "latest-updates"),
                Pair("Most Viewed", "most-popular"),
                Pair("Top Rated", "top-rated"),
                Pair("Longest", "longest"),
                Pair("Random", "random"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private val QUALITY_REGEX = Regex("_(\\d+p)\\.mp4")

        private const val TAG_SEARCH_MIN_CHARS = 2

        private const val TAG_RETRY_COOLDOWN_MS = 30_000L

        private val CATEGORIES = listOf(
            Triple("Straight", "2109", "tag"),
            Triple("Futa", "15", "tag"),
            Triple("Gay", "192", "tag"),
            Triple("Music", "2111", "tag"),
            Triple("Iwara", "1617", "cat"),
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
