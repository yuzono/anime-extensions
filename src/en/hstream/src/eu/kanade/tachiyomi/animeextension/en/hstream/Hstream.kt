package eu.kanade.tachiyomi.animeextension.en.hstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Hstream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "en"

    override val supportsLatest = true

    // URLs from the old extension are invalid now, so we're bumping this to
    // make aniyomi interpret it as a new source, forcing old users to migrate.
    override val versionId = 2

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/search?order=view-count&page=$page")

    override fun popularAnimeSelector() = "div.items-center div.w-full > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val href = element.attr("href")
        val showUrl = href.trimEnd('/').substringBeforeLast("-") + "/"
        val epNum = href.trimEnd('/').substringAfterLast("-")

        setUrlWithoutDomain(showUrl)

        val imgElement = element.selectFirst("img")!!
        val rawTitle = imgElement.attr("alt")
        title = EPISODE_PARSER.replace(rawTitle, "").trim()

        val imageBasePath = imgElement.absUrl("src").substringBeforeLast("/")
        thumbnail_url = "$imageBasePath/cover-ep-$epNum.webp"
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = super.popularAnimeParse(response)
        return AnimesPage(page.animes.distinctBy { it.url }, page.hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = super.latestUpdatesParse(response)
        return AnimesPage(page.animes.distinctBy { it.url }, page.hasNextPage)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = super.searchAnimeParse(response)
        return AnimesPage(page.animes.distinctBy { it.url }, page.hasNextPage)
    }

    override fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search?order=recently-uploaded&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HstreamFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/hentai/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = HstreamFilters.getSearchParameters(filters)

        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("order", params.order)
            params.genres.forEachIndexed { index, genre -> addQueryParameter("tags[$index]", genre) }
            params.blacklisted.forEach { addQueryParameter("blacklist[]", it) }
            params.studios.forEach { addQueryParameter("studios[]", it) }
        }.build()

        return GET(url.toString())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        status = SAnime.COMPLETED

        val mainContent = document.selectFirst("div.flex-1")!!
        title = mainContent.selectFirst("h1 span")?.text() ?: mainContent.selectFirst("h1")!!.text()
        author = mainContent.selectFirst("div.mt-4.flex.flex-wrap a")?.text()

        thumbnail_url = document.selectFirst("div.hidden.shrink-0.md\\:block img")?.absUrl("src")
        genre = document.select("h2:contains(Genres) + div a").eachText().joinToString()

        description = document.selectFirst("h2:contains(Description) + p")?.text()
            ?: document.selectFirst("div.border-t p.leading-relaxed")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val showPath = response.request.url.encodedPath.trimEnd('/')

        val uploadDate = runCatching {
            val dateText = doc.selectFirst("div.mt-4.flex.flex-wrap.items-center.gap-2.text-sm > div:has(i.fa-upload)")
                ?.text()
            DATE_FORMATTER.parse(dateText.orEmpty())?.time
        }.getOrNull() ?: 0L

        return doc.select("a[href*='$showPath-']")
            .mapNotNull { element ->
                val href = element.attr("href")
                val path = element.absUrl("href").toHttpUrl().encodedPath.trimEnd('/')
                if (!path.startsWith("$showPath-")) return@mapNotNull null
                val numStr = path.removePrefix("$showPath-")
                val num = numStr.toFloatOrNull() ?: return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = num
                    name = "Episode $numStr"
                    date_upload = uploadDate
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.episode_number }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val token = client.cookieJar.loadForRequest(response.request.url)
            .first { it.name.equals("XSRF-TOKEN") }
            .value

        val episodeId = doc.selectFirst("input#e_id")!!.attr("value")

        val newHeaders = headersBuilder().apply {
            set("Referer", doc.location())
            set("Origin", baseUrl)
            set("X-Requested-With", "XMLHttpRequest")
            set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
        }.build()

        val body = mapOf("episode_id" to episodeId).toJsonRequestBody()
        val data = client.newCall(POST("$baseUrl/player/api", newHeaders, body)).execute()
            .parseAs<PlayerApiResponse>()

        val urlBase = data.stream_domains.random() + "/" + data.stream_url
        val subtitleList = listOf(Track("$urlBase/eng.ass", "English"))

        val resolutions = listOfNotNull("720", "1080", if (data.resolution == "4k") "2160" else null)

        // Determine if we need to force legacy mode based on manifest inspection to handle html video chunks
        var forceLegacy = data.legacy != 0
        if (!forceLegacy) {
            try {
                val testUrl = urlBase + "/720/manifest.mpd"
                val manifestString = client.newCall(GET(testUrl, headers)).execute().body?.string() ?: ""
                if (manifestString.contains(".html")) {
                    forceLegacy = true
                }
            } catch (_: Exception) {}
        }

        return resolutions.map { resolution ->
            val path = getVideoUrlPath(forceLegacy, resolution)
            val url = urlBase + path
            Video(url, "${resolution}p" + if (forceLegacy) " (Legacy)" else "", url, subtitleTracks = subtitleList)
        }
    }

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String = if (isLegacy) {
        if (resolution.equals("720")) {
            "/x264.720p.mp4"
        } else {
            "/av1.$resolution.webm"
        }
    } else {
        "/$resolution/manifest.mpd"
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
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private val EPISODE_PARSER = Regex("""\s*(?:[-–—]\s*|\bEpisode\s*)\d+(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE)
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p (HD)", "1080p (FULLHD)", "2160p (4K)")
        private val PREF_QUALITY_VALUES = arrayOf("720p", "1080p", "2160p")
    }
}
