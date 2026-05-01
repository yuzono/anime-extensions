package eu.kanade.tachiyomi.animeextension.en.hstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.extractEpisodeNumber
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.normalizeHref
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.stripEpisodeSuffix
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesSlug
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesUrl
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Hstream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // URLs from the old extension are invalid now, so we're bumping this to
    // make aniyomi interpret it as a new source, forcing old users to migrate.
    override val versionId = 3

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        HstreamLogger.debug("popularAnimeRequest", "Building request: page=$page")
        return GET("$baseUrl/search?order=view-count&page=$page")
    }

    override fun popularAnimeSelector() = "div.items-center div.w-full > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val rawHref = element.attr("href")
        val episodeUrl = rawHref.normalizeHref()
        if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            setUrlWithoutDomain(episodeUrl.toSeriesUrl())
            title = element.selectFirst("img")!!.attr("alt").stripEpisodeSuffix()
            thumbnail_url = "$baseUrl/images$url/cover-ep-1.webp"
        } else {
            setUrlWithoutDomain(episodeUrl)
            title = element.selectFirst("img")!!.attr("alt")
            val episode = url.substringAfterLast("-").substringBefore("/")
            thumbnail_url = "$baseUrl/images${url.substringBeforeLast("-")}/cover-ep-$episode.webp"
        }
        HstreamLogger.debug("popularAnimeFromElement", "rawHref='$rawHref', url='$url', title='$title'")
    }

    override fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        HstreamLogger.debug("latestUpdatesRequest", "Building request: page=$page")
        return GET("$baseUrl/search?order=recently-uploaded&page=$page")
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HstreamFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
        HstreamLogger.debug("getSearchAnime", "query='$query', page=$page, startsWithPrefix=true")
        val id = query.removePrefix(PREFIX_SEARCH)
        val url = if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            "$baseUrl/hentai/${id.toSeriesSlug()}"
        } else {
            "$baseUrl/hentai/$id"
        }
        client.newCall(GET(url))
            .awaitSuccess()
            .use(::searchAnimeByIdParse)
    } else {
        super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        HstreamLogger.debug("searchAnimeByIdParse", "Parsing detail page: ${response.request.url}")
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = HstreamFilters.getSearchParameters(filters)
        HstreamLogger.debug("searchAnimeRequest", "Building request: query='$query', page=$page, filters=${filters.size}")

        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("order", params.order)
            params.genres.forEachIndexed { index, genre -> addQueryParameter("tags[$index]", genre) }
            params.blacklisted.forEach { addQueryParameter("blacklist[]", it) }
            params.studios.forEach { addQueryParameter("studios[]", it) }
        }.build()

        HstreamLogger.debug("searchAnimeRequest", "Search URL: $url")
        return GET(url.toString())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        HstreamLogger.debug("animeDetailsParse", "Parsing details from: ${document.location()}")
        status = SAnime.COMPLETED

        val detailsSection = document.selectFirst("div.relative > div.justify-between > div")
        if (detailsSection != null) {
            // Episode page: h1 is inside div.justify-between > div
            HstreamLogger.debug("animeDetailsParse", "Page type: episode (has upload-link)")
            title = detailsSection.selectFirst("div > h1")!!.text()
            artist = detailsSection.select("div > a:nth-of-type(3)").text()
        } else {
            // Series page: h1 is a direct child of div.relative
            HstreamLogger.debug("animeDetailsParse", "Page type: series (no upload-link)")
            title = document.selectFirst("div.relative > h1")?.text()
                ?: document.selectFirst("h1")!!.text()
            artist = ""
        }

        thumbnail_url = document.selectFirst("div.float-left > img.object-cover")?.absUrl("src")
        genre = document.select("ul.list-none > li > a").eachText().joinToString()

        description = document.selectFirst("div.relative > p.leading-tight")?.text()
        val genres = genre?.split(", ")?.size ?: 0
        HstreamLogger.debug("animeDetailsParse", "Parsed: title='$title', artist='$artist', genres=$genres, description length=${description?.length ?: 0}")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val requestUrl = response.request.url.toString()
        HstreamLogger.debug("episodeListParse", "called for URL: $requestUrl")

        if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            // Series page is JS-rendered, so episode grid isn't available in JSoup HTML.
            // Instead, use the search endpoint to find all episodes of this series.
            val seriesUrl = requestUrl.normalizeHref()
            HstreamLogger.debug("episodeListParse", "seriesUrl after normalizeHref: '$seriesUrl'")

            // Get series title from the page, strip Japanese name for matching
            val seriesTitle = doc.selectFirst("div.relative > div.justify-between > div > div > h1")
                ?.text()
                ?: doc.selectFirst("div.relative > h1")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: ""
            HstreamLogger.debug("episodeListParse", "seriesTitle: '$seriesTitle'")

            val matchTitle = seriesTitle.substringBefore("(").trim()
            HstreamLogger.debug("episodeListParse", "matchTitle: '$matchTitle'")

            val searchUrl = "$baseUrl/search?search=${URLEncoder.encode(matchTitle, "UTF-8")}"
            HstreamLogger.debug("episodeListParse", "searchUrl: $searchUrl")
            val searchDoc = client.newCall(GET(searchUrl)).execute().asJsoup()

            val searchResults = searchDoc.select(popularAnimeSelector())
            HstreamLogger.debug("episodeListParse", "searchResults count: ${searchResults.size}")

            val episodes = searchResults.mapNotNull { element ->
                val rawHref = element.attr("href")
                HstreamLogger.debug("episodeListParse", "  raw href: '$rawHref'")
                val href = rawHref.normalizeHref()
                HstreamLogger.debug("episodeListParse", "  normalized href: '$href'")

                val prefixCheck = href.startsWith("$seriesUrl-")
                HstreamLogger.debug("episodeListParse", "  startsWith '$seriesUrl-': $prefixCheck")
                if (!prefixCheck) return@mapNotNull null

                val alt = element.selectFirst("img")?.attr("alt")
                HstreamLogger.debug("episodeListParse", "  img alt: '$alt'")
                if (alt == null) return@mapNotNull null

                val titleCheck = alt.contains(matchTitle, ignoreCase = true)
                HstreamLogger.debug("episodeListParse", "  alt contains matchTitle: $titleCheck")
                if (!titleCheck) return@mapNotNull null

                val epNum = href.extractEpisodeNumber()
                HstreamLogger.debug("episodeListParse", "  extractEpisodeNumber: '$epNum'")
                if (epNum == null) return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = epNum.toFloatOrNull() ?: 1F
                    name = "Episode $epNum"
                    date_upload = 0L
                    HstreamLogger.debug("episodeListParse", "  CREATED episode: name='$name', url='$url', epNum=$episode_number")
                }
            }
            HstreamLogger.debug("episodeListParse", "final episode count: ${episodes.size}")
            return episodes.sortedByDescending { it.episode_number }
        }

        // Original behavior: single episode from the page
        HstreamLogger.debug("episodeListParse", "FALLING BACK to ungrouped path (grouping disabled?)")
        val episode = SEpisode.create().apply {
            date_upload = doc.selectFirst("a:has(i.fa-upload)")?.ownText().toDate()
            setUrlWithoutDomain(doc.location())
            val num = url.substringAfterLast("-").substringBefore("/")
            episode_number = num.toFloatOrNull() ?: 1F
            name = "Episode $num"
            HstreamLogger.debug("episodeListParse", "ungrouped episode: name='$name', url='$url'")
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        HstreamLogger.debug("videoListParse", "Parsing video list from: ${response.request.url}")
        val doc = response.asJsoup()

        val token = client.cookieJar.loadForRequest(response.request.url)
            .first { it.name.equals("XSRF-TOKEN") }
            .value
        HstreamLogger.debug("videoListParse", "XSRF token found: ${token.take(10)}...")

        val episodeId = doc.selectFirst("input#e_id")!!.attr("value")
        HstreamLogger.debug("videoListParse", "Episode ID: $episodeId")

        val newHeaders = headersBuilder().apply {
            set("Referer", doc.location())
            set("Origin", baseUrl)
            set("X-Requested-With", "XMLHttpRequest")
            set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
        }.build()

        val body = """{"episode_id": "$episodeId"}""".toRequestBody("application/json".toMediaType())
        val data = client.newCall(POST("$baseUrl/player/api", newHeaders, body)).execute()
            .parseAs<PlayerApiResponse>()
        HstreamLogger.debug("videoListParse", "API response: legacy=${data.legacy}, resolution=${data.resolution}, stream_url=${data.stream_url}, domains=${data.stream_domains.size}")

        val urlBase = data.stream_domains.random() + "/" + data.stream_url
        val subtitleList = listOf(Track("$urlBase/eng.ass", "English"))

        val resolutions = listOfNotNull("720", "1080", if (data.resolution == "4k") "2160" else null)
        val videos = resolutions.map { resolution ->
            val url = urlBase + getVideoUrlPath(data.legacy != 0, resolution)
            Video(url, "${resolution}p", url, subtitleTracks = subtitleList)
        }
        HstreamLogger.debug("videoListParse", "Built ${videos.size} videos")
        return videos
    }

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String {
        HstreamLogger.debug("getVideoUrlPath", "legacy=$isLegacy, resolution=$resolution")
        val path = if (isLegacy) {
            if (resolution.equals("720")) {
                "/x264.720p.mp4"
            } else {
                "/av1.$resolution.webm"
            }
        } else {
            "/$resolution/manifest.mpd"
        }
        HstreamLogger.debug("getVideoUrlPath", "Result path: $path")
        return path
    }

    @Serializable
    data class PlayerApiResponse(
        val legacy: Int = 0,
        val resolution: String = "4k",
        val stream_url: String,
        val stream_domains: List<String>,
    )

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        HstreamLogger.debug("setupPreferenceScreen", "Setting up preferences")
        screen.addSwitchPreference(
            key = PREF_GROUP_BY_SERIES_KEY,
            default = PREF_GROUP_BY_SERIES_DEFAULT,
            title = PREF_GROUP_BY_SERIES_TITLE,
            summary = PREF_GROUP_BY_SERIES_SUMMARY,
        )

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================= Utilities ==============================

    private fun String?.toDate(): Long = runCatching { DATE_FORMATTER.parse(orEmpty().trim(' ', '|'))?.time }
        .getOrNull() ?: 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        HstreamLogger.debug("sort", "Sorting ${this.size} videos, preferred quality: $quality")

        val sorted = sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()

        HstreamLogger.debug("sort", "Sorted order: ${sorted.map { it.quality }}")
        return sorted
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"

        private const val PREF_GROUP_BY_SERIES_KEY = "pref_group_by_series_key"
        private const val PREF_GROUP_BY_SERIES_TITLE = "Group by series"
        private const val PREF_GROUP_BY_SERIES_SUMMARY = "Merge episodes of the same series into a single entry"
        private const val PREF_GROUP_BY_SERIES_DEFAULT = true

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p (HD)", "1080p (FULLHD)", "2160p (4K)")
        private val PREF_QUALITY_VALUES = arrayOf("720p", "1080p", "2160p")
    }
}
