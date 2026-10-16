package eu.kanade.tachiyomi.animeextension.id.kuramanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kuramanime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Kuramanime"

    override val baseUrl = "https://kuramanime.ing"

    override val lang = "id"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?page=$page", headers)

    override fun popularAnimeSelector() = "div.filter__gallery > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("div.set-bg")?.attr("data-setbg")
        title = element.selectFirst("div > h5")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.product__pagination > a:last-child:not([aria-disabled='true'])"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?order_by=updated&page=$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/anime?search=$query&page=$page", headers)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")

        val details = document.selectFirst("div.anime__details__text")!!

        title = details.selectFirst("div > h3")!!.text().replace("Judul: ", "")

        val infos = details.selectFirst("div.anime__details__widget")!!
        artist = infos.select("li:contains(Studio:) > a").eachText().joinToString().takeUnless(String::isEmpty)
        status = parseStatus(infos.selectFirst("li:contains(Status:) > a")?.text())

        genre = infos.select("li:contains(Genre:) > a, li:contains(Tema:) > a, li:contains(Demografis:) > a")
            .eachText()
            .joinToString { it.trimEnd(',', ' ') }
            .takeUnless(String::isEmpty)

        description = buildString {
            details.selectFirst("p#synopsisField")?.text()?.also(::append)

            details.selectFirst("div.anime__details__title > span")?.text()
                ?.also { append("\n\nAlternative names: $it\n") }

            infos.select("ul > li").eachText().forEach { append("\n$it") }
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString) {
        "Sedang Tayang" -> SAnime.ONGOING
        "Selesai Tayang" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()

        val html = document.selectFirst(episodeListSelector())?.attr("data-content")
            ?: return emptyList()

        val newDoc = Jsoup.parse(html)

        val limits = newDoc.select("a.btn-secondary")

        return when {
            limits.isEmpty() -> { // 12 episodes or less
                newDoc.select("a")
                    .filterNot { it.attr("href").contains("batch") }
                    .map(::episodeFromElement)
                    .reversed()
            }

            else -> { // More than 12 episodes
                val (start, end) = limits.eachText().take(2).map {
                    it.filter(Char::isDigit).toInt()
                }

                val location = document.location()

                (end downTo start).map { episodeNumber ->
                    SEpisode.create().apply {
                        name = "Ep $episodeNumber"
                        episode_number = episodeNumber.toFloat()
                        setUrlWithoutDomain("$location/episode/$episodeNumber")
                    }
                }
            }
        }
    }

    override fun episodeListSelector() = "a#episodeLists"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.filter(Char::isDigit).toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun seasonListSelector(): String = throw UnsupportedOperationException()

    override fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // Shall we add "archive", "archive-v2"? archive.org usually returns a beautiful 403 xD
    private val supportedHosters = listOf("kuramadrive", "kuramadrive-v2", "filelions", "filemoon", "mega", "streamwish", "streamtape", "vidguard", "doodstream")

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun hosterListParse(response: Response): List<Hoster> {
        val doc = response.useAsJsoup()
        return doc.select("select#changeServer > option")
            .filter { it.attr("value") in supportedHosters }
            .map {
                val server = it.attr("value")
                val name = it.text().substringBefore(" (")
                Hoster(
                    hosterUrl = doc.location(),
                    hosterName = name,
                    internalData = HosterData(server).toJsonString(),
                )
            }
    }

    @Serializable
    private class HosterData(val server: String)

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val server = hoster.internalData.parseAs<HosterData>().server
        val serverName = hoster.hosterName
        val response = client.newCall(GET(hoster.hosterUrl, headers)).awaitSuccess()
        val episodeUrl = response.request.url
        val doc = response.useAsJsoup()
        val origin = "${episodeUrl.scheme}://${episodeUrl.host}"

        val scriptData = getScriptData(doc, origin) ?: return emptyList()

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?: return emptyList()

        val authorization = doc.selectFirst("#tokenAuthJs")?.attr("value")
            ?.takeUnless(String::isEmpty)
            ?.let { episodeUrl.resolve(it)?.toString() }
            ?.let { getAuthorization(it) }
            ?: return emptyList()

        val sourceHeaders = headersBuilder()
            .set("Referer", episodeUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-CSRF-TOKEN", csrfToken)
            .build()

        val page = doc.selectFirst("#checkEp")?.attr("value")
            ?.takeUnless(String::isEmpty)
            ?.let { episodeUrl.resolve(it) }
            ?.takeIf { it.scheme == episodeUrl.scheme && it.host == episodeUrl.host && it.port == episodeUrl.port }
            ?.let { checkUrl ->
                runCatching {
                    // Keep source credentials on the checked origin, including when it returns a redirect.
                    client.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build()
                        .newCall(GET(checkUrl, sourceHeaders)).awaitSuccess()
                        .bodyString()
                        .trim('"', ' ', '\n')
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
            ?.takeUnless(String::isEmpty)
            ?: "1"

        val authHeaders = sourceHeaders.newBuilder()
            .set("X-Fuck-ID", scriptData.tokenId)
            .set("X-Request-ID", getRandomString())
            .set("X-Request-Index", "0")
            .build()

        val hash = client.newCall(GET("$origin/" + scriptData.authPath, authHeaders))
            .awaitSuccess()
            .bodyString()
            .trim('"')

        val newUrl = episodeUrl.newBuilder()
            .addQueryParameter(scriptData.tokenParam, hash)
            .addQueryParameter(scriptData.serverParam, server)
            .addQueryParameter("page", page)
            .build()

        val body = FormBody.Builder()
            .add("authorization", authorization)
            .build()

        val playerDoc = client.newCall(POST(newUrl.toString(), sourceHeaders, body))
            .awaitSuccess()
            .useAsJsoup()

        val url = playerDoc.selectFirst("div.video-content iframe, iframe")?.attr("abs:src")
        return when (server) {
            "filelions" if url != null -> streamWishExtractor.videosFromUrl(url)
            "filemoon" if url != null -> filemoonExtractor.videosFromUrl(url)
            "streamwish" if url != null -> streamWishExtractor.videosFromUrl(url)
            "streamtape" if url != null -> streamtapeExtractor.videosFromUrl(url)
            "vidguard" if url != null -> vidguardExtractor.videosFromUrl(url)
            "doodstream" if url != null -> doodExtractor.videosFromUrl(url, serverName)
            else -> {
                val hlsUrl = playerDoc.selectFirst("video#player")?.attr("abs:data-hls-src")
                    ?.takeUnless(String::isEmpty)

                val hlsVideos = hlsUrl?.let {
                    runCatching {
                        // extractFromHls returns the URL as a video for any body without variants, error pages included
                        val masterHeaders = playlistUtils.generateMasterHeaders(headers, episodeUrl.toString())
                        val playlist = client.newCall(GET(it, masterHeaders)).awaitSuccess().bodyString()
                        if (!playlist.trimStart().startsWith("#EXTM3U")) return@runCatching emptyList<Video>()

                        playlistUtils.extractFromHls(
                            playlistUrl = it,
                            referer = episodeUrl.toString(),
                            videoNameGen = { quality -> "$quality - $serverName" },
                        )
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }.orEmpty()

                hlsVideos.ifEmpty {
                    playerDoc.select("video#player > source").map {
                        val src = it.attr("abs:src")
                        Video(src, "${it.attr("size")}p - $serverName", src)
                    }
                }
            }
        }.sortVideos()
    }

    private suspend fun getScriptData(doc: Document, origin: String): ScriptDataDto? {
        // The attribute holding the script name (data-kk, previously data-kps) is declared in sizzlyb.js
        val attrName = doc.selectFirst("script[src*=sizzlyb]")?.attr("abs:src")
            ?.takeUnless(String::isEmpty)
            ?.let { src ->
                runCatching {
                    val script = client.newCall(GET(src, headers)).awaitSuccess().bodyString()
                    routeAttrRegex.find(script)?.groupValues?.get(1)
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }

        val scriptName = listOfNotNull(attrName, "data-kk", "data-kps").distinct()
            .firstNotNullOfOrNull { attr -> doc.selectFirst("[$attr]")?.attr(attr) }
            ?.takeUnless(String::isEmpty)
            ?: return null

        return runCatching {
            val script = client.newCall(GET("$origin/assets/js/$scriptName.js", headers)).awaitSuccess()
                .bodyString()

            val envVars = envVarRegex.findAll(script).associate { it.groupValues[1] to it.groupValues[2] }

            ScriptDataDto(
                authPathPrefix = envVars["MIX_PREFIX_AUTH_ROUTE_PARAM"] ?: "",
                authPathSuffix = envVars["MIX_AUTH_ROUTE_PARAM"] ?: "",
                authKey = envVars["MIX_AUTH_KEY"] ?: "",
                authToken = envVars["MIX_AUTH_TOKEN"] ?: "",
                tokenParam = envVars["MIX_PAGE_TOKEN_KEY"] ?: "",
                serverParam = envVars["MIX_STREAM_SERVER_KEY"] ?: "",
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    // The obfuscated script hands a constant token to jQuery's `.load()`, run it against a stubbed `$` to read it.
    private suspend fun getAuthorization(scriptUrl: String): String? = runCatching {
        val script = client.newCall(GET(scriptUrl, headers)).awaitSuccess().bodyString()

        QuickJs.create().use { qjs ->
            qjs.evaluate(AUTH_JS_PREFIX + script + AUTH_JS_SUFFIX) as? String
        }
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeUnless(String::isEmpty)

    private class ScriptDataDto(
        authPathPrefix: String,
        authPathSuffix: String,
        authKey: String,
        authToken: String,
        val tokenParam: String,
        val serverParam: String,
    ) {
        val authPath = authPathPrefix + authPathSuffix
        val tokenId = "$authKey:$authToken"
    }

    private fun getRandomString(length: Int = 8): String {
        val allowedChars = ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private val routeAttrRegex = Regex("""MIX_JS_ROUTE_PARAM_ATTR["']?\s*:\s*["']([^"']+)["']""")
        private val envVarRegex = Regex("""(MIX_\w+)["']?\s*:\s*["']([^"']*)["']""")

        private const val AUTH_JS_PREFIX = "globalThis.window = globalThis; globalThis.self = globalThis; " +
            "globalThis.__auth = ''; " +
            "globalThis.\$ = function () { return { load: function (u, d) { globalThis.__auth = d.authorization; } }; };\n"
        private const val AUTH_JS_SUFFIX = "\n;window.jLoadSecure('.a', 'http://localhost', {}, function () {});\n" +
            "String(globalThis.__auth);"
    }
}
