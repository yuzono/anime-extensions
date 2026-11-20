package eu.kanade.tachiyomi.animeextension.en.hstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Hstream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/search?order=view-count&page=$page", headers)

    private fun popularAnimeSelector() = "div.items-center div.w-full > a"

    private fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
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
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search?order=recently-uploaded&page=$page", headers)

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
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = client.get("$baseUrl/hentai/$id", headers)
            return searchAnimeByIdParse(response)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
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

        return GET(url, headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            status = SAnime.COMPLETED

            val mainContent = document.selectFirst("div.flex-1")!!
            title = mainContent.selectFirst("h1 span")?.text() ?: mainContent.selectFirst("h1")!!.text()
            author = mainContent.selectFirst("div.mt-4.flex.flex-wrap a")?.text()

            thumbnail_url = document.selectFirst("div.hidden.shrink-0.md\\:block img")?.absUrl("src")
            genre = document.select("h2:contains(Genres) + div a").eachText().joinToString()

            description = document.selectFirst("h2:contains(Description) + p")?.text()
                ?: document.selectFirst("div.border-t p.leading-relaxed")?.text()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val showPath = response.request.url.encodedPath.trimEnd('/')

        val uploadDate = DATE_FORMATTER.tryParse(
            doc.selectFirst("div.mt-4.flex.flex-wrap.items-center.gap-2.text-sm > div:has(i.fa-upload)")?.text(),
        )

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

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epResponse = client.get(baseUrl + episode.url, headers)
        val doc = epResponse.asJsoup()

        val token = client.cookieJar.loadForRequest(epResponse.request.url)
            .firstOrNull { it.name == "XSRF-TOKEN" }
            ?.value ?: return emptyList()

        val episodeId = doc.selectFirst("input#e_id")?.attr("value") ?: return emptyList()

        val newHeaders = headersBuilder().apply {
            set("Referer", doc.location())
            set("Origin", baseUrl)
            set("X-Requested-With", "XMLHttpRequest")
            set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
        }.build()

        val body = mapOf("episode_id" to episodeId).toJsonRequestBody()
        val data = client.post("$baseUrl/player/api", newHeaders, body).parseAs<PlayerApiResponse>()

        val streamDomains = data.streamDomains.filter { domain ->
            val parsed = domain.toHttpUrlOrNull()
            parsed != null && parsed.isHttps && isValidPublicHost(parsed.host)
        }
        if (streamDomains.isEmpty()) return emptyList()

        val testBase = "${streamDomains.first()}/${data.streamUrl}"

        var forceLegacy = data.legacy != 0
        if (!forceLegacy) {
            try {
                val testUrl = "$testBase/720/manifest.mpd"
                val manifestString = client.get(testUrl, headers, ensureSuccess = false).body.string()
                if (manifestString.contains(".html") || manifestString.contains("\\")) {
                    forceLegacy = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        val resolutions = if (forceLegacy) {
            listOfNotNull(
                "720",
                "1080",
                if (data.resolution == "4k") "2160" else null,
            )
        } else {
            listOfNotNull(
                "720",
                "1080",
                "1080i",
                if (data.resolution == "4k") "2160" else null,
            )
        }

        val videoHeaders = headersBuilder().set("Referer", "$baseUrl/").build()

        return streamDomains.mapIndexed { index, domain ->
            val urlBase = "$domain/${data.streamUrl}"
            val subtitleList = listOf(Track("$urlBase/eng.ass", "English"))

            val videos = resolutions.map { resolution ->
                val path = getVideoUrlPath(forceLegacy, resolution)
                val url = urlBase + path

                val qualityLabel = when (resolution) {
                    "1080i" -> "1080@48"
                    else -> "${resolution}p"
                }

                Video(
                    videoUrl = url,
                    videoTitle = qualityLabel + if (forceLegacy) " (Legacy)" else "",
                    headers = videoHeaders,
                    subtitleTracks = subtitleList,
                )
            }

            Hoster(
                hosterUrl = domain,
                hosterName = "Server ${index + 1}",
                videoList = videos,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String = if (isLegacy) {
        if (resolution == "720") {
            "/x264.720p.mp4"
        } else {
            "/av1.$resolution.webm"
        }
    } else {
        "/$resolution/manifest.mpd"
    }

    @Serializable
    class PlayerApiResponse(
        val legacy: Int = 0,
        val resolution: String? = null,
        @SerialName("stream_url") val streamUrl: String,
        @SerialName("stream_domains") val streamDomains: List<String> = emptyList(),
    )

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
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        }

        private val EPISODE_PARSER = Regex("""\s*(?:[-–—]\s*|\bEpisode\s*)\d+(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE)
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p (HD)", "1080p (FULLHD)", "1080@48 (1080i)", "2160p (4K)")
        private val PREF_QUALITY_VALUES = arrayOf("720p", "1080p", "1080@48", "2160p")

        private fun isValidPublicHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local") || host.endsWith(".internal")) {
                return false
            }
            return !PRIVATE_IP_REGEX.matches(host)
        }

        private val PRIVATE_IP_REGEX = Regex(
            """^(127\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|0\.|169\.254\.|::1|[fF][cCdD]).*""",
        )
    }
}
