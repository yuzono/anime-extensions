package eu.kanade.tachiyomi.multisrc.wcotheme

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

abstract class WcoTheme :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", DESKTOP_USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    open val json: Json by injectLazy()
    open val playlistUtils by lazy { PlaylistUtils(client, headers) }

    open val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div#sidebar_right2 ul.items > li"

    override fun popularAnimeFromElement(element: Element) = gridItemToAnime(element)

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesSelector(): String = "div.recent-release:contains(Recent Releases) + div > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = gridItemToAnime(element)

    /**
     * Builds an [SAnime] from one of the homepage `ul.items > li` grid cards.
     * Cards always have a thumbnail in `.img a img` and a clickable title in
     * `.recent-release-episodes a` (or just the wrapping anchor for the
     * Recently Added Series grid).
     */
    private fun gridItemToAnime(element: Element): SAnime = SAnime.create().apply {
        val titleAnchor = element.selectFirst(".recent-release-episodes a, .img a")
            ?: element.selectFirst("a")!!
        setUrlWithoutDomain(titleAnchor.attr("href"))
        // Prefer the bookmark anchor's own text so trailing badge spans
        // (Dub/Sub/quality) are not included; fall back to the image alt
        // attribute, then to the raw element text.
        title = titleAnchor.ownText().ifBlank {
            element.selectFirst("img[alt]")?.attr("alt").orEmpty().ifBlank {
                element.text()
            }
        }.takeIf { it.isNotBlank() }!!
        thumbnail_url = element.selectFirst("img[src]")?.attr("abs:src")
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return client.newCall(latestUpdatesRequest(page))
            .awaitSuccess()
            .use { response ->
                if (page == 1) {
                    latestUpdatesParse(response)
                } else {
                    val document = response.asJsoup()

                    val animes = document.select(latestUpdatesNextPageSelector())
                        .mapNotNull { element ->
                            runCatching { latestUpdatesFromElement(element) }.getOrNull()
                        }

                    return AnimesPage(animes, false)
                }
            }
    }

    override fun latestUpdatesNextPageSelector() = "div.recent-release:contains(Recently Added) + div > ul > li"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<Filters.GenresFilter>().firstOrNull()

        if (query.isNotBlank()) {
            val formBody = FormBody.Builder()
                .add("catara", query)
                .add("konuara", "series")
                .build()
            return POST("$baseUrl/search", headers, body = formBody)
        } else if (genresFilter != null && !genresFilter.isDefault()) {
            val url = "$baseUrl/search-by-genre/page/${genresFilter.toUriPart()}"
            return GET(url, headers)
        } else {
            return popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchUrl = response.request.url.toString()

        if (searchUrl.contains("/search-by-genre/")) {
            // If the response is from a genre search, use the genre selector
            val document = response.asJsoup()
            return document.select(genreAnimeSelector()).mapNotNull {
                runCatching { genreAnimeFromElement(it) }.getOrNull()
            }
                .let { AnimesPage(it, false) }
        }
        if (searchUrl.contains("/search")) {
            val document = response.asJsoup()
            return document.select(searchAnimeSelector()).mapNotNull {
                runCatching { searchAnimeFromElement(it) }.getOrNull()
            }
                .let { AnimesPage(it, false) }
        }
        return popularAnimeParse(response)
    }

    override fun searchAnimeSelector() = "div#sidebar_right2 li"

    open fun genreAnimeSelector() = "div#sidebar_right4 .ddmcc li a"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    open fun genreAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        // The "anime" URL stored in the user's library may actually point at an
        // episode page (e.g. when the entry was added from the Latest list).
        // On anime pages the title sits in `div.video-title a`; on episode
        // pages we fall back to the linked series name in `div.header-tag h2 a`
        // (both layouts) and finally to the on-page heading so favourites
        // never end up with an empty title after a refresh.
        (
            document.selectFirst("div.video-title a")?.text()
                ?: document.selectFirst("div.header-tag h2 a")?.text()
                ?: document.selectFirst("div.video-title h1")?.text()
                ?: document.selectFirst("div.baslikCell h1")?.text()
            )?.let { title = it }
        description = document.selectFirst("div#sidebar_cat p")?.text()
        thumbnail_url = document.selectFirst("div#sidebar_cat img")?.attr("abs:src")
        genre = document.select("div#sidebar_cat > a").joinToString { it.text() }
            .ifBlank { null }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.cat-eps, div#episodeList a.dark-episode-item, nav#sidebarEpisodeList a.sidebar-episode-item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).mapNotNull {
            runCatching { episodeFromElement(it) }.getOrNull()
        }

        // Subbed first, then dubbed; within each group sort by episode number
        val sorted = episodes.sortedWith(
            compareBy(
                { it.name.contains("dub", ignoreCase = true) },
                { it.episode_number },
            ),
        )

        return sorted.mapIndexed { index, episode ->
            episode.apply {
                episode_number = (sorted.size - index).toFloat()
            }
        }
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        val title = document.select(".video-title").text()
                        val (name, _) = episodeTitleFromElement(title)
                        this.name = name
                    },
                )
            }
    }

    open val episodeTitleRegex by lazy { Regex("(Season (\\d+) )?Episode (\\d+) (.*)") }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val anchor = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(anchor.attr("href"))
        val title = anchor.selectFirst("span")?.text() ?: element.text()
        val (name, epNum) = episodeTitleFromElement(title)
        this.name = name
        this.episode_number = epNum
    }

    open fun episodeTitleFromElement(title: String): Pair<String, Float> {
        val matchResult = episodeTitleRegex.find(title)
        return if (matchResult != null) {
            // Extract season and episode numbers from the title
            val (_, season, episode, episodeTitle) = matchResult.destructured
            val seasonNum = season.toIntOrNull()
            val episodeNum = episode.toIntOrNull()
            val episodeNumber = (((seasonNum ?: 1) - 1) * 100 + (episodeNum ?: 1)).toFloat()
            val name = StringBuilder().apply {
                seasonNum?.let { append("Season $it - ") }
                episodeNum?.let { append("Episode $episodeNum: ") }
                append(episodeTitle.trim())
            }.toString()
            name to episodeNumber
        } else {
            // Fallback for titles that don't match the regex
            title to 1f
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class VideoResponseDto(
        val server: String,
        @SerialName("enc")
        val sd: String?,
        val hd: String?,
        val fhd: String?,
    ) {
        val videos by lazy {
            listOfNotNull(
                sd?.takeIf(String::isNotBlank)?.let { Pair("480p", it) },
                hd?.takeIf(String::isNotBlank)?.let { Pair("720p", it) },
                fhd?.takeIf(String::isNotBlank)?.let { Pair("1080p", it) },
            ).map {
                val videoUrl = "$server/getvid?evid=" + it.second
                Video(videoUrl, it.first, videoUrl)
            }
        }
    }

    open val useOldIframeExtractor = false

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeReferer = response.request.url.toString()
        return if (useOldIframeExtractor) iframeOldExtractor(document, episodeReferer) else iframeExtractor(document, episodeReferer)
    }

    open fun iframeExtractor(document: Document, episodeReferer: String = "$baseUrl/") = document.select("iframe")
        .ifEmpty { throw Exception("No iframe found in the episode page") }
        .parallelCatchingFlatMapBlocking {
            val iframeLink = it.attr("abs:src")
            iframeParse(iframeLink, episodeReferer)
        }

    open fun iframeOldExtractor(document: Document, episodeReferer: String = "$baseUrl/"): List<Video> {
        val script = document.selectFirst("script:containsData(decodeURIComponent)")?.data()
            ?: throw Exception("No script found in the episode page")

        val stringList = json.decodeFromString<List<String>>(
            "[${script.substringAfter("[")
                .substringBefore("]")
                // Handle trailing commas in entries with new lines
                .trim()
                .removeSuffix(",")}]",
        )
        val shiftNumber = script.substringAfterLast("- ").substringBefore(");").toInt()
        val iframeStuff = stringList.joinToString("") {
            (String(Base64.decode(it, Base64.DEFAULT)).replace("""\D""".toRegex(), "").toInt() - shiftNumber).toChar().toString()
        }
        val iframeUrl = Jsoup.parse(iframeStuff)
            .selectFirst("iframe")?.attr("src")
            ?: throw Exception("No iframe found in the episode page")

        return runBlocking { runCatching { iframeParse(iframeUrl, episodeReferer) }.getOrElse { emptyList() } }
    }

    private fun generateNonce(): String = ByteArray(16).also(Random::nextBytes).toHex()

    private fun chromeHeadersBuilder() = Headers.Builder()
        .add("User-Agent", DESKTOP_USER_AGENT)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Dnt", "1")
        .add("Sec-Ch-Ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")
    open suspend fun iframeParse(iframeLink: String, episodeReferer: String = "$baseUrl/"): List<Video> = if (iframeLink.contains("embed.wcostream")) {
        // Dub or Hard-sub
        val iframeDomain = "https://" + iframeLink.toHttpUrl().host

        // 1. Load index.php
        val navHeaders = Headers.Builder()
            .add("User-Agent", DESKTOP_USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("Referer", episodeReferer)
            .add("Sec-Fetch-Dest", "iframe")
            .add("Sec-Fetch-Site", "cross-site")
            .build()

        client.get(
            iframeLink,
            navHeaders,
        ).close()

        // 1b. Load pre-init.js — the server tracks this sub-resource request
        val preInitUrl = "$iframeDomain/inc/embed/pre-init.js?v2"
        runCatching {
            client.newCall(GET(preInitUrl, navHeaders)).execute().close()
        }

        // 1c. Load the bait ad script that pre-init.js would load
        val baitUrl = "$iframeDomain/assets/ads/advertisement.js"
        runCatching {
            client.newCall(GET(baitUrl, navHeaders)).execute().close()
        }

        // 2a. Small delay to simulate pre-init.js execution time
        delay(300.milliseconds)

        // 2b. Create the "clear" beacon
        val pid = iframeLink.toHttpUrl().queryParameter("pid") ?: return emptyList()
        val nonce = generateNonce()
        val beaconBody = """{"nonce":"$nonce","status":"clear","id":"$pid"}"""

        val beaconHeaders = chromeHeadersBuilder()
            .add("Accept", "*/*")
            .add("Origin", iframeDomain)
            .add("Referer", iframeLink)
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-origin")
            .build()

        client.post(
            "$iframeDomain/ad-verify",
            beaconHeaders,
            beaconBody.toRequestBody("application/json".toMediaType()),
        ).close()

        // 3. WAIT — server enforces anti-bot delay after beacon
        val delaySeconds = preferences.getString(PREF_DELAY_KEY, PREF_DELAY_DEFAULT)
            ?.toIntOrNull()
            ?.takeIf { it in 1..60 }
            ?: PREF_DELAY_DEFAULT.toInt()
        delay((delaySeconds * 1000L).milliseconds)

        // 4. Fetch the real player page — try video-js-old.php first, then fall back to video-js.php
        val embedHeaders = chromeHeadersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("Priority", "u=0, i")
            .add("Referer", iframeLink)
            .add("Sec-Fetch-Dest", "iframe")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Storage-Access", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val videoJsOldUrl = iframeLink.toHttpUrl().newBuilder()
            .encodedPath("/inc/embed/video-js-old.php")
            .addQueryParameter("n", nonce)
            .build()
            .toString()

        val oldPlayerResponse = client.newCall(GET(videoJsOldUrl, embedHeaders)).execute()
        val oldPlayerBody = oldPlayerResponse.body.string()
        val useOldPlayer = oldPlayerResponse.isSuccessful && "$.getJSON" in oldPlayerBody

        val videoJsUrl = if (useOldPlayer) {
            videoJsOldUrl
        } else {
            iframeLink.toHttpUrl().newBuilder()
                .encodedPath("/inc/embed/video-js.php")
                .addQueryParameter("n", nonce)
                .build()
                .toString()
        }

        val iframeSoup = if (useOldPlayer) {
            Jsoup.parse(oldPlayerBody, iframeDomain)
        } else {
            client.newCall(GET(videoJsUrl, embedHeaders))
                .awaitSuccess()
                .asJsoup()
        }

        val getVideoLinkScript =
            iframeSoup.selectFirst("script:containsData(getJSON)")?.data()
                ?: return emptyList()

        val getVideoLink =
            getVideoLinkScript.substringAfter("$.getJSON(\"").substringBefore("\"")

        val requestUrl = iframeDomain + getVideoLink

        val requestHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", requestUrl)
            .set("Origin", iframeDomain)
            .build()

        val videoData = client.newCall(GET(requestUrl, requestHeaders))
            .awaitSuccess()
            .parseAs<VideoResponseDto>()

        videoData.videos
    } else if (iframeLink.contains("vhs.watchanimesub")) {
        // Premium videos with high quality, soft-sub and audio tracks
        val body = client.newCall(GET(iframeLink, headers))
            .awaitSuccess().bodyString()

        val matchResult = Regex("""getRedirectedUrl\("(https://[\w-/.]+/index\.m3u8)"""").find(body)
        if (matchResult != null) {
            val playlistUrl = matchResult.groupValues[1]
            playlistUtils.extractFromHls(
                playlistUrl = playlistUrl,
                referer = "$iframeLink/",
                videoNameGen = { quality -> "Premium - $quality" },
            )
        } else {
            emptyList()
        }
    } else {
        emptyList()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains("720") },
            ),
        ).reversed()
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.GenresFilter(),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        val currentDelay = preferences.getString(PREF_DELAY_KEY, PREF_DELAY_DEFAULT)
            ?.toIntOrNull()
            ?.takeIf { it in 1..60 }
            ?.toString()
            ?: PREF_DELAY_DEFAULT

        screen.addEditTextPreference(
            key = PREF_DELAY_KEY,
            title = PREF_DELAY_TITLE,
            default = PREF_DELAY_DEFAULT,
            inputType = android.text.InputType.TYPE_CLASS_NUMBER,
            validate = { it.toIntOrNull() in 1..60 },
            validationMessage = { "Enter a number between 1 and 60" },
            summary = "Current:  ${currentDelay}s",
            getSummary = { "Current: ${it}s" },
        )
    }
    override val supportsRelatedAnimes = false

    // ============================= Utilities ==============================

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_TITLE = "Preferred quality"
        const val PREF_QUALITY_DEFAULT = "720"
        val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p")
        val PREF_QUALITY_VALUES = listOf("1080", "720", "480")

        const val PREF_DELAY_KEY = "preferred_delay"
        const val PREF_DELAY_TITLE = "Anti-bot delay (seconds)"
        const val PREF_DELAY_DEFAULT = "12"

        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    }
}
