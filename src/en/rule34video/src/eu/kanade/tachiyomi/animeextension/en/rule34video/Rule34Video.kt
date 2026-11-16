package eu.kanade.tachiyomi.animeextension.en.rule34video

import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Rule34Video :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = false

    override val client = network.client

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private val preferences by getPreferencesLazy()

    private fun configuredUploaderId(): String? {
        if (!preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) return null
        return parseUploaderId(preferences.getString(PREF_UPLOADER_ID_KEY, ""))
    }

    // Accepts either the numeric member id or a member page URL on this source's host
    // (e.g. https://rule34video.com/members/98965/videos/).
    private fun parseUploaderId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        val numeric = value.trimEnd('/')
        if (NUMERIC_ID_REGEX.matches(numeric)) {
            return numeric.toLongOrNull()?.takeIf { it > 0 }?.toString()
        }

        val url = value.toHttpUrlOrNull() ?: return null
        val baseHost = runCatching { baseUrl.toHttpUrl().host }.getOrNull() ?: return null
        if (!url.host.equals(baseHost, ignoreCase = true) && !url.host.endsWith(".$baseHost", ignoreCase = true)) {
            return null
        }
        if (url.pathSegments.firstOrNull() != "members") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { NUMERIC_ID_REGEX.matches(it) } ?: return null
        return id.toLongOrNull()?.takeIf { it > 0 }?.toString()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val uploaderId = configuredUploaderId()
        if (uploaderId != null) {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("members")
                .addPathSegment(uploaderId)
                .addPathSegment("videos")
                .addPathSegment("")
            if (page > 1) urlBuilder.addQueryParameter("from_videos", page.toString())
            return GET(urlBuilder.build(), headers)
        }
        return GET("$baseUrl/latest-updates/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // 404 pages can still contain unrelated "related videos" cards, and the site
        // occasionally serves a bare 200 shell; only parse a response with a real listing block.
        val container = document.findVideoListContainer()
            ?: throw Exception("Failed to load video list: no listing block (HTTP ${response.code})")
        val animeList = container.select(VIDEO_CARD_SELECTOR).mapNotNull { element ->
            // The site has two video card layouts: the classic `.item.thumb` grid (home,
            // search, tag/artist/category pages) and the member page's `.item.ma-v` one.
            val link = element.selectFirst("a.th[href*=\"/video\"], a.ma-thumb[href*=\"/video\"]")
                ?: element.selectFirst("a[href*=\"/video\"]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() }
                ?: link.attr("href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = link.attr("title").ifBlank {
                element.selectFirst("div.thumb_title, a.ma-v__t")?.text().orEmpty()
            }.trim()
            if (title.isBlank()) return@mapNotNull null

            val img = element.selectFirst("div.img img, img.thumb, img")
            val thumb = img?.absUrl("data-rd-jpg")?.takeIf { it.isNotBlank() }
                ?: img?.absUrl("data-rd-src")?.takeIf { it.isNotBlank() }
                ?: img?.absUrl("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.absUrl("src")?.takeIf { it.isNotBlank() && !it.contains("placeholder", true) && !it.contains("blank", true) }

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = thumb
            }
        }
        val hasNext = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animeList, hasNext)
    }

    // The first recognized listing marker locates its card container, so cards from
    // unrelated grids on the same page (related/recommended blocks) are never parsed.
    private fun Document.findVideoListContainer(): Element? {
        val marker = selectFirst(VIDEO_LIST_MARKER_SELECTOR) ?: return null
        val blockId = marker.attr("data-rd-list")
        return (if (blockId.isNotBlank()) getElementById("${blockId}_items") else null)
            ?: marker.selectFirst(VIDEO_LIST_CONTAINER_SELECTOR)
            ?: marker.parent()?.selectFirst(VIDEO_LIST_CONTAINER_SELECTOR)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = runCatching { query.toHttpUrl() }.getOrNull()
            val baseHost = runCatching { baseUrl.toHttpUrl().host }.getOrNull()
            if (url != null && baseHost != null && (url.host.equals(baseHost, ignoreCase = true) || url.host.endsWith(".$baseHost", ignoreCase = true))) {
                // Accepts both https://rule34video.com/video/<id>/... and legacy /videos/<id>/...
                val id = url.pathSegments.dropWhile { it != "video" && it != "videos" }.getOrNull(1)
                if (!id.isNullOrBlank()) {
                    return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
                }
            }
            throw Exception("Unsupported url")
        }
        return super.getSearchAnime(page, query, filters)
    }

    private inline fun <reified R : AnimeFilter.Text> AnimeFilterList.getText(): String = (find { it is R } as? AnimeFilter.Text)?.state?.trim().orEmpty()

    private fun String.toSlug(): String = lowercase().replace(SLUG_REGEX, "-").trim('-')

    private inline fun <reified R : UriPartFilter> AnimeFilterList.getUriPart(): String = (find { it is R } as? UriPartFilter)?.toUriPart().orEmpty()

    private fun AnimeFilterList.getSortParameters(): List<Pair<String, String>> = (find { it is OrderFilter } as? OrderFilter)?.toQueryParameters().orEmpty()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeId = filters.getUriPart<CategoryBy>()

        if (query.startsWith(PREFIX_SEARCH)) {
            val newQuery = query.removePrefix(PREFIX_SEARCH).trim('/')
            return GET("$baseUrl/video/$newQuery/", headers)
        }

        // Tag / Artist / Category pages: https://rule34video.com/{tags|models|categories}/<slug>/<page>/
        val browse = listOf(
            "tags" to filters.getText<TagFilter>(),
            "models" to filters.getText<ArtistFilter>(),
            "categories" to filters.getText<CategoryNameFilter>(),
        ).firstOrNull { it.second.isNotBlank() }

        val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                browse != null -> {
                    addPathSegment(browse.first)
                    addPathSegment(browse.second.toSlug())
                    if (page > 1) addPathSegment(page.toString())
                    addPathSegment("")
                }

                query.isNotBlank() -> {
                    addPathSegment("search")
                    addPathSegment(query.trim().toSlug())
                    addPathSegment("")
                }

                else -> {
                    addPathSegment("search")
                    addPathSegment("")
                }
            }

            filters.getSortParameters().forEach { (key, value) -> addQueryParameter(key, value) }
            if (typeId.isNotBlank()) addQueryParameter("flag1", typeId)
            if (page > 1 && browse == null) addQueryParameter("from_videos", page.toString())
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.startsWith("/video/")) {
            val anime = animeDetailsParse(response).apply {
                setUrlWithoutDomain(response.request.url.encodedPath)
            }
            return AnimesPage(listOf(anime), false)
        }
        return popularAnimeParse(response)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.title_video, h1.vp-title")?.text().orEmpty()

            val detailRows = document.select("div.row, div.vp-group")
            val meta = document.select("div.vp-meta span.item_info > span, div.info.row div.item_info > span").eachText()

            val artists = document.select(ARTIST_SELECTOR)
                .eachText()
                .distinct()
                .joinToString()
                .takeIf { it.isNotBlank() }
            val uploader = document.select(UPLOADER_SELECTOR).firstOrNull()?.text()?.takeIf { it.isNotBlank() }

            artist = artists
            author = artists ?: uploader

            description = buildString {
                document.selectFirst("div.vp-desc, div.row:has(div.label > em) > div.label > em")?.text()?.takeIf { it.isNotBlank() }?.let {
                    append(it)
                    append("\n\n")
                }

                meta.getOrNull(0)?.let { append("Uploaded: $it\n") }

                if (!artists.isNullOrEmpty()) append("Artists: $artists\n")

                val categories = document.select(CATEGORIES_SELECTOR)
                    .eachText()
                    .distinct()
                    .joinToString()
                if (categories.isNotEmpty()) append("Categories: $categories\n")

                if (!uploader.isNullOrEmpty()) append("Uploader: $uploader\n")

                meta.getOrNull(1)?.let { append("Views: $it\n") }
                meta.getOrNull(2)?.let { append("Duration: $it\n") }

                val qualities = document.select(DOWNLOAD_LINK_SELECTOR)
                    .mapNotNull { QUALITY_REGEX.find(it.text())?.value }
                    .distinct()
                    .joinToString()
                if (qualities.isNotEmpty()) append("Quality: $qualities")
            }.trim()

            genre = document.select(TAGS_SELECTOR)
                .eachText()
                .distinct()
                .joinToString()

            val poster = document.selectFirst("video#player_html5_api, video")?.attr("abs:poster")?.takeIf { it.isNotBlank() }
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }
            thumbnail_url = poster ?: ogImage

            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url
    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val uploader = document.select(UPLOADER_SELECTOR).firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = "Video"
                episode_number = 1f
                scanlator = uploader
            },
        )
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.get(baseUrl + anime.url)
        return episodeListParse(response)
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "Rule34Video",
            hosterUrl = episode.url,
        ),
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val requestUrl = if (hoster.hosterUrl.startsWith("http")) {
            hoster.hosterUrl
        } else {
            "$baseUrl${hoster.hosterUrl}"
        }

        val response = client.get(requestUrl)
        val pageUrl = response.request.url
        val baseHost = pageUrl.host

        val cookies = client.cookieJar.loadForRequest(pageUrl)
            .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")

        val document = response.asJsoup()

        // 1) The player's own sources (flashvars in an inline script): video_url, video_alt_url, video_alt_url2...
        // 2) Fallback: the links inside the new "Download" sheet.
        // 3) Additional fallback: <video><source> tags on the page.
        val sources = parseFlashvarsSources(document)
            .ifEmpty { parseDownloadSheetSources(document) }
            .ifEmpty { parseHtmlVideoSources(document) }

        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sources.distinctBy { it.second }.mapNotNull { (quality, originalUrl) ->
            val probeHeaders = headersForUrl(originalUrl, pageUrl, baseHost, cookies, xsrfToken)
            // The file server returns a http 403 when the player (http/1.1) connects to the
            // tokenised url directly, so resolve the redirect target with OkHttp (http/2) first.
            val streamUrl = resolveRedirect(originalUrl, probeHeaders) ?: return@mapNotNull null
            val videoHeaders = headersForUrl(streamUrl, pageUrl, baseHost, cookies, xsrfToken)

            Video(
                videoUrl = streamUrl,
                videoTitle = quality,
                headers = videoHeaders,
            )
        }.sortedWith(
            compareByDescending<Video> { it.videoTitle == preferred }
                .thenByDescending { QUALITY_REGEX.find(it.videoTitle)?.value?.removeSuffix("p")?.toIntOrNull() ?: 0 },
        )
    }

    private fun headersForUrl(
        targetUrl: String,
        pageUrl: HttpUrl,
        baseHost: String,
        cookies: String,
        xsrfToken: String?,
    ): Headers {
        val targetHost = runCatching { targetUrl.toHttpUrl().host }.getOrNull()
        val isTrusted = targetHost != null && (targetHost.equals(baseHost, ignoreCase = true) || targetHost.endsWith(".$baseHost", ignoreCase = true))

        return headers.newBuilder().apply {
            if (isTrusted) {
                xsrfToken?.let { set("X-XSRF-TOKEN", it) }
                if (cookies.isNotBlank()) set("Cookie", cookies)
            }
            set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            set("Referer", pageUrl.toString())
            set("Accept-Language", "en-US,en;q=0.5")
        }.build()
    }

    private fun parseFlashvarsSources(document: Document): List<Pair<String, String>> {
        val script = document.select("script")
            .map { it.data() }
            .firstOrNull { "flashvars" in it && "video_url" in it }
            ?: return emptyList()

        val qualityMap = FLASHVARS_TEXT_REGEX.findAll(script)
            .associate { it.groupValues[1] to it.groupValues[2] }

        return FLASHVARS_URL_REGEX.findAll(script).mapNotNull { match ->
            val key = match.groupValues[1]
            val url = match.groupValues[2].replace("\\/", "/")
            val quality = qualityMap[key]
                ?: QUALITY_REGEX.find(url)?.value
                ?: return@mapNotNull null
            quality to url
        }.toList()
    }

    private fun parseDownloadSheetSources(document: Document): List<Pair<String, String>> {
        return document.select(DOWNLOAD_LINK_SELECTOR).mapNotNull { element ->
            val quality = QUALITY_REGEX.find(element.text())?.value ?: return@mapNotNull null
            val href = element.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = href.toHttpUrl().newBuilder()
                .removeAllQueryParameters("download")
                .removeAllQueryParameters("download_filename")
                .build()
                .toString()
            quality to url
        }
    }

    private fun parseHtmlVideoSources(document: Document): List<Pair<String, String>> {
        return document.select("video source[src], video[src]").mapNotNull { source ->
            val src = source.absUrl("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val quality = source.attr("title").ifBlank { source.attr("label") }
                .let { QUALITY_REGEX.find(it)?.value }
                ?: QUALITY_REGEX.find(src)?.value
                ?: "720p"
            quality to src
        }
    }

    private fun resolveRedirect(url: String, reqHeaders: Headers): String? = runCatching {
        noRedirectClient.newCall(GET(url, reqHeaders)).execute().use { res ->
            when {
                res.isRedirect -> res.header("Location")?.let { res.request.url.resolve(it)?.toString() }
                res.isSuccessful -> url
                else -> null
            }
        }
    }.getOrNull()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = PREF_UPLOADER_FILTER_ENABLED_KEY,
            default = false,
            title = "Filter by Uploader",
            summary = "Load videos only from the specified uploader ID.",
        )

        screen.addEditTextPreference(
            key = PREF_UPLOADER_ID_KEY,
            default = "",
            title = "Uploader ID",
            summary = "Member ID or member page URL (e.g., 98965 or https://rule34video.com/members/98965/). Requires \"Filter by Uploader\" to be enabled.",
            dialogMessage = "Enter Uploader ID",
            validate = { parseUploaderId(it) != null },
            validationMessage = { "Enter the member ID (e.g. 98965) or a member page URL." },
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = PREF_QUALITY_TITLE,
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList {
        if (configuredUploaderId() != null) {
            return AnimeFilterList()
        }
        return AnimeFilterList(
            OrderFilter(),
            CategoryBy(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Browse by name (ignores search text, first filled field wins):"),
            AnimeFilter.Header("Type it as shown on the site, e.g. \"kanae katagiri (bleach)\""),
            TagFilter(),
            ArtistFilter(),
            CategoryNameFilter(),
        )
    }

    private class TagFilter : AnimeFilter.Text("Tag", "")
    private class ArtistFilter : AnimeFilter.Text("Artist", "")
    private class CategoryNameFilter : AnimeFilter.Text("Category (series/game)", "")

    private class CategoryBy :
        UriPartFilter(
            "Type",
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
        AnimeFilter.Select<String>(
            "Sort By",
            arrayOf(
                "Default (Newest / Relevance)",
                "Newest",
                "Most Viewed",
                "Top Rated",
                "Longest",
                "Random",
                "Most Viewed - Past 24 Hours",
                "Most Viewed - Past 2 Days",
                "Most Viewed - Past Week",
                "Most Viewed - Past Month",
                "Most Viewed - Past 3 Months",
                "Most Viewed - Past Year",
            ),
        ) {
        fun toQueryParameters(): List<Pair<String, String>> = when (state) {
            1 -> listOf("sort_by" to "post_date")
            2 -> listOf("sort_by" to "video_viewed")
            3 -> listOf("sort_by" to "rating")
            4 -> listOf("sort_by" to "duration")
            5 -> listOf("sort_by" to "pseudo_rand")
            // The "past ..." sorts reuse the site's date filter, which is submitted as a
            // numeric `post_date_from` preset: 1 = 24 hours, 2 = 2 days, 3 = week,
            // 4 = month, 5 = 3 months, 6 = year. It is not listed in the site's sort menu.
            // They are appended after the pre-existing options so saved searches keep
            // their selected sort when they restore the old filter indices.
            in 6..11 -> listOf("sort_by" to "video_viewed", "post_date_from" to (state - 5).toString())
            else -> emptyList()
        }
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val VIDEO_LIST_MARKER_SELECTOR = "div.fs-ui.fs-list[data-rd-list], div.ma-block[data-rdm-list]"
        private const val VIDEO_LIST_CONTAINER_SELECTOR = "div.ma-grid, div.thumbs"
        private const val VIDEO_CARD_SELECTOR = "div.item.thumb, div.item.ma-v"
        private const val NEXT_PAGE_SELECTOR = "nav.fs-pager a[rel=next], div.item.pager.next a"
        private const val DOWNLOAD_LINK_SELECTOR = "div.vp-sheet[data-sheet=download] a.tag_item_download, div.row:has(div.label:contains(Download)) a.tag_item"
        private const val ARTIST_SELECTOR =
            "div.col:has(div.label:contains(Artist)) a.item span.name, div.vp-group:has(> div.label:contains(Artist)) a.item span.name, div.col a[href*='/models/'] span.name, div.vp-group a[href*='/models/'] span.name, div.col a[href*='/models/'], div.vp-group a[href*='/models/']"
        private const val CATEGORIES_SELECTOR =
            "div.col:has(div.label:contains(Categories)) a.item span, div.vp-group:has(> div.label:contains(Categories)) a.item span, div.col a[href*='/categories/'] span, div.vp-group a[href*='/categories/'] span, div.col a[href*='/categories/'], div.vp-group a[href*='/categories/']"
        private const val UPLOADER_SELECTOR = "div.col:has(div.label:contains(Uploaded by)) a.item, div.vp-group:has(> div.label:contains(Uploaded by)) a.item"
        private const val TAGS_SELECTOR = "div.vp-tags a.tag_item, div.row_spacer:has(div.label:contains(Tags)) a.tag_item:not(:contains(Suggest))"

        private val SLUG_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val NUMERIC_ID_REGEX = Regex("""\d+""")
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
        private val FLASHVARS_URL_REGEX = Regex("""(video(?:_alt)?_url\d*)\s*:\s*['"](https?:[^'"]+)['"]""")
        private val FLASHVARS_TEXT_REGEX = Regex("""(video(?:_alt)?_url\d*)_text\s*:\s*['"]([^'"]+)['"]""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
