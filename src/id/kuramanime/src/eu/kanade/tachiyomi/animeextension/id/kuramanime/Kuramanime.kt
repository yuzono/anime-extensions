package eu.kanade.tachiyomi.animeextension.id.kuramanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kuramanime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Kuramanime"

    override val baseUrl = "https://v19.kuramanime.ing"

    override val lang = "id"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?order_by=popular&page=$page")

    override fun popularAnimeSelector() = "div.product__item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("div.set-bg")?.attr("data-setbg")
        title = element.selectFirst("h5 > a, h5")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.product__pagination > a:last-child:not([aria-disabled='true'])"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?order_by=latest&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/anime".toHttpUrl().newBuilder()
            url.addQueryParameter("search", query)
            url.addQueryParameter("page", page.toString())
            return GET(url.build().toString(), headers)
        } else {
            var url = "$baseUrl/anime".toHttpUrl().newBuilder()

            var orderBy = ""
            var statusPath = ""
            var typePath = ""
            var typeName = ""
            var genrePath = ""

            for (filter in filters) {
                when (filter) {
                    is KuramanimeFilters.OrderByFilter -> orderBy = filter.toUriPart()
                    is KuramanimeFilters.StatusFilter -> statusPath = filter.toUriPart()
                    is KuramanimeFilters.TypeFilter -> {
                        typePath = filter.toUriPart()
                        typeName = filter.toNamePart()
                    }
                    is KuramanimeFilters.GenreFilter -> genrePath = filter.toUriPart()
                    else -> {}
                }
            }

            when {
                statusPath.isNotEmpty() -> url = "$baseUrl/quick/$statusPath".toHttpUrl().newBuilder()
                typePath.isNotEmpty() -> {
                    url = "$baseUrl/properties/type/$typePath".toHttpUrl().newBuilder()
                    url.addQueryParameter("name", typeName)
                }
                genrePath.isNotEmpty() -> url = "$baseUrl/properties/genre/$genrePath".toHttpUrl().newBuilder()
            }

            if (orderBy.isNotEmpty()) {
                url.addQueryParameter("order_by", orderBy)
            }
            url.addQueryParameter("page", page.toString())

            return GET(url.build().toString(), headers)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")

        val details = document.selectFirst("div.anime__details__text")!!

        title = details.selectFirst("div.anime__details__title > h3, div > h3")!!.text().replace("Judul: ", "")

        val infos = details.selectFirst("div.anime__details__widget")!!
        artist = infos.select("li:contains(Studio:) a").eachText().joinToString().takeUnless(String::isEmpty)
        status = parseStatus(infos.selectFirst("li:contains(Status:) a")?.text())

        genre = infos.select("li:contains(Genre:) a, li:contains(Tema:) a, li:contains(Demografis:) a")
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
    override fun videoListSelector() = "video#player > source"

    private val supportedHosters = listOf("kuramadrive", "kuramadrive-v2", "filelions", "filemoon", "mega", "streamwish", "streamtape", "vidguard", "doodstream")

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val bodyStr = response.body.string()
        val doc = Jsoup.parse(bodyStr)

        val scriptData = getScriptData(bodyStr, doc) ?: return emptyList()

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?: return emptyList()

        val servers = doc.select("select#changeServer > option")
            .map { it.attr("value") to it.text().substringBefore(" (") }
            .filter { supportedHosters.contains(it.first) }

        val episodeUrl = response.request.url

        val headers = headersBuilder()
            .set("Referer", episodeUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val originalKuramadriveSources = doc.select("video#player > source").map {
            val src = it.attr("src")
            Video(src, "${it.attr("size")}p - kuramadrive", src)
        }.ifEmpty {
            val video = doc.selectFirst("video#player")
            val src = video?.attr("src") ?: video?.attr("data-hls-src")
            if (!src.isNullOrEmpty()) {
                listOf(Video(src, "kuramadrive", src))
            } else {
                emptyList()
            }
        }

        return servers.parallelCatchingFlatMapBlocking { (server, serverName) ->
            if (server == "kuramadrive" && originalKuramadriveSources.isNotEmpty()) {
                return@parallelCatchingFlatMapBlocking originalKuramadriveSources
            }

            val newHeaders = headers.newBuilder()
                .set("X-CSRF-TOKEN", csrfToken)
                .set("X-Fuck-ID", scriptData.tokenId)
                .set("X-Request-ID", getRandomString())
                .set("X-Request-Index", "0")
                .build()

            val hash = client.newCall(GET("$baseUrl/" + scriptData.authPath, newHeaders))
                .awaitSuccess()
                .bodyString()
                .trim('"')

            val newUrl = episodeUrl.newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter(scriptData.tokenParam, hash)
                .addQueryParameter(scriptData.serverParam, server)
                .build()

            val requestBody = okhttp3.FormBody.Builder()
                .add("authorization", "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur")
                .build()

            val playerHeaders = headers.newBuilder()
                .set("X-CSRF-TOKEN", csrfToken)
                .build()

            val playerDoc = client.newCall(POST(newUrl.toString(), playerHeaders, requestBody))
                .awaitSuccess()
                .useAsJsoup()

            var url = playerDoc.selectFirst("iframe")?.attr("src")
            if (url != null && url.startsWith("/")) {
                url = "$baseUrl$url"
            }

            if (url != null && url.contains("/stream")) {
                runCatching {
                    val streamDoc = client.newCall(GET(url!!, headers)).execute().useAsJsoup()
                    url = streamDoc.selectFirst("iframe")?.attr("src") ?: url
                }
            }

            when (server) {
                "filelions" if url != null -> streamWishExtractor.videosFromUrl(url!!)
                "filemoon" if url != null -> filemoonExtractor.videosFromUrl(url!!)
                "doodstream" if url != null -> doodExtractor.videosFromUrl(url!!)

                // mega.nz source
                // server == "mega" && url != null -> streamtapeExtractor.videosFromUrl(url!!)
                "streamwish" if url != null -> streamWishExtractor.videosFromUrl(url!!)
                "streamtape" if url != null -> streamtapeExtractor.videosFromUrl(url!!)
                "vidguard" if url != null -> vidguardExtractor.videosFromUrl(url!!)
                else -> {
                    val sources = playerDoc.select("video#player > source")
                    if (sources.isNotEmpty()) {
                        sources.map {
                            val src = it.attr("src")
                            Video(src, "${it.attr("size")}p - $serverName", src)
                        }
                    } else {
                        val video = playerDoc.selectFirst("video#player")
                        val src = video?.attr("src") ?: video?.attr("data-hls-src")
                        if (!src.isNullOrEmpty()) {
                            listOf(Video(src, serverName, src))
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }
    }

    private fun getScriptData(html: String, doc: Document): ScriptDataDto? {
        val processEnvRegex = Regex("""window\.process\s*=\s*\{[\s\S]*?env:\s*\{([\s\S]*?)\}[\s\S]*?\}""")
        val envMatch = processEnvRegex.find(html)

        val envContent = if (envMatch != null) {
            envMatch.groupValues[1]
        } else {
            val sizzlybUrl = "$baseUrl/assets/js/sizzlyb.js"
            val sizzlybRes = client.newCall(GET(sizzlybUrl, headers)).execute()
            if (!sizzlybRes.isSuccessful) return null

            val sizzlybStr = sizzlybRes.body.string()
            val attrMatch = Regex("""MIX_JS_ROUTE_PARAM_ATTR:\s*["']([^"']+)["']""").find(sizzlybStr)
            val attrName = attrMatch?.groupValues?.get(1) ?: return null

            val scriptId = doc.selectFirst("[$attrName]")?.attr(attrName) ?: return null

            val varJsUrl = "$baseUrl/assets/js/$scriptId.js"
            val varJsRes = client.newCall(GET(varJsUrl, headers)).execute()
            if (!varJsRes.isSuccessful) return null

            varJsRes.body.string()
        }

        val envVars = mutableMapOf<String, String>()
        val varRegex = Regex("""(\w+):\s*['"]([^'"]+)['"]""")

        varRegex.findAll(envContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            envVars[key] = value
        }

        return ScriptDataDto(
            authPathPrefix = envVars["MIX_PREFIX_AUTH_ROUTE_PARAM"] ?: "",
            authPathSuffix = envVars["MIX_AUTH_ROUTE_PARAM"] ?: "",
            authKey = envVars["MIX_AUTH_KEY"] ?: "",
            authToken = envVars["MIX_AUTH_TOKEN"] ?: "",
            tokenParam = envVars["MIX_PAGE_TOKEN_KEY"] ?: "",
            serverParam = envVars["MIX_STREAM_SERVER_KEY"] ?: "",
        )
    }

    @Serializable
    internal data class ScriptDataDto(
        @SerialName("MIX_PREFIX_AUTH_ROUTE_PARAM")
        private val authPathPrefix: String,

        @SerialName("MIX_AUTH_ROUTE_PARAM")
        private val authPathSuffix: String,

        @SerialName("MIX_AUTH_KEY") private val authKey: String,
        @SerialName("MIX_AUTH_TOKEN") private val authToken: String,

        @SerialName("MIX_PAGE_TOKEN_KEY") val tokenParam: String,
        @SerialName("MIX_STREAM_SERVER_KEY") val serverParam: String,
    ) {
        val authPath = "$authPathPrefix$authPathSuffix"
        val tokenId = "$authKey:$authToken"
    }

    private fun getRandomString(length: Int = 8): String {
        val allowedChars = ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList() = KuramanimeFilters.FILTER_LIST

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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
