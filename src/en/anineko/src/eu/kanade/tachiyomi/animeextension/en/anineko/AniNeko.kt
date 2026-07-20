package eu.kanade.tachiyomi.animeextension.en.anineko

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

class AniNeko :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniNeko"

    override val baseUrl = "https://anineko.to"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val localProxy by lazy { LocalProxy(client) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browser?sort=recently_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browser".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("keyword", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("genre[]", it)
                    }
                }

                is TypeFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("type[]", it)
                    }
                }

                is StatusFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("status[]", it)
                    }
                }

                is LanguageFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("language[]", it)
                    }
                }

                is YearFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("year[]", it)
                    }
                }

                is SortFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                }

                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val cards = document.select("article.nv-anime-card.nv-browse-card")

        val animes = cards.map { card ->
            SAnime.create().apply {
                val linkEl = card.selectFirst("a.nv-anime-thumb") ?: card.selectFirst("a")!!
                url = linkEl.attr("href")
                title = card.selectFirst("h3.nv-anime-title a")?.text()
                    ?: linkEl.selectFirst("img")?.attr("alt")
                    ?: ""
                thumbnail_url = linkEl.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!
            val mainTitle = document.selectFirst("h1")?.text() ?: ""
            val altTitle = document.selectFirst("div.nv-info-alt-title")?.text() ?: ""
            title = if (titleLang == "Romaji/Japanese" && altTitle.isNotBlank()) {
                altTitle
            } else {
                mainTitle
            }

            genre = document.select("div.nv-info-genres span").joinToString { it.text() }

            val statusStr = document.selectFirst("div.nv-info-list div:contains(Status) strong, div.nv-info-stats div:contains(Status) strong")?.text() ?: ""
            status = when {
                statusStr.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                statusStr.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            author = document.selectFirst("div.nv-info-list div:contains(Studios) strong a")?.text()
            thumbnail_url = document.selectFirst("aside.nv-info-poster img")?.attr("src")

            val baseDesc = document.selectFirst("p.nv-info-desc, div.nv-info-synopsis p")?.text() ?: ""
            description = if (altTitle.isNotBlank()) {
                "$baseDesc\n\nAlternative Title: $altTitle"
            } else {
                baseDesc
            }
        }
    }

    // ============================== Episode List ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select("div.nv-info-episode-grid article.nv-info-episode-item")

        val list = episodes.map { element ->
            SEpisode.create().apply {
                val linkEl = element.selectFirst("a.nv-info-episode-main") ?: element.selectFirst("a")!!
                url = linkEl.attr("href")

                val titleEl = linkEl.selectFirst("strong")
                name = titleEl?.text() ?: linkEl.text()

                episode_number = name.substringAfter("Episode").trim().toFloatOrNull() ?: 1.0f
            }
        }
        return list.reversed()
    }

    // ============================== Video List ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val buttons = document.select("button.server-video")

        val videos = buttons.parallelCatchingFlatMapBlocking { button ->
            val iframeUrl = button.attr("data-video")
            if (iframeUrl.isBlank()) return@parallelCatchingFlatMapBlocking emptyList<Video>()

            val rawType = button.selectFirst("span")?.text() ?: ""
            val versionType = when {
                rawType.contains("Sort Sub", ignoreCase = true) -> "Soft Sub"
                rawType.contains("Hard Sub", ignoreCase = true) -> "Hard Sub"
                rawType.contains("Dub", ignoreCase = true) -> "Dub"
                else -> rawType
            }

            val subtitleTracks = mutableListOf<Track>()
            runCatching {
                val uri = Uri.parse(iframeUrl)
                val subUrl = uri.getQueryParameter("sub")
                    ?: uri.getQueryParameter("caption_1")
                    ?: uri.getQueryParameter("c1_file")
                if (!subUrl.isNullOrBlank()) {
                    val subLabel = uri.getQueryParameter("sub_1")
                        ?: uri.getQueryParameter("c1_label")
                        ?: "English"
                    subtitleTracks.add(Track(subUrl, subLabel))
                }
            }

            when {
                iframeUrl.contains("vivibebe.site") || iframeUrl.contains("vibevibe.workers.dev") || iframeUrl.contains("bibiemb.xyz") -> {
                    val iframeHtml = client.newCall(GET(iframeUrl, headers)).execute().body.string()
                    val m3u8Url = vibeRegex.find(iframeHtml)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        val finalM3u8 = if (iframeUrl.contains("bibiemb.xyz")) {
                            m3u8Url
                        } else {
                            localProxy.getProxyUrl(m3u8Url, headers)
                        }
                        playlistUtils.extractFromHls(
                            finalM3u8,
                            referer = iframeUrl,
                            videoNameGen = { quality -> "$versionType - $quality" },
                            subtitleList = subtitleTracks,
                        )
                    } else {
                        emptyList()
                    }
                }

                iframeUrl.contains("otakuhg.site") || iframeUrl.contains("otakuvid.online") -> {
                    val extractor = VidHideExtractor(client, headers)
                    extractor.videosFromUrl(iframeUrl) { quality -> "$versionType - $quality" }.map { video ->
                        Video(
                            url = video.url,
                            quality = video.quality,
                            videoUrl = video.videoUrl,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                iframeUrl.contains("playmogo.com") || iframeUrl.contains("dood") -> {
                    val extractor = DoodExtractor(client)
                    extractor.videosFromUrl(iframeUrl, quality = versionType).map { video ->
                        Video(
                            url = video.url,
                            quality = video.quality,
                            videoUrl = video.videoUrl,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                else -> emptyList()
            }
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        val host = preferences.getString(PREF_HOST_KEY, PREF_HOST_DEFAULT)!!

        return videos
            .filter { video ->
                val matchesServer = excludedServers.any { video.quality.contains(it, ignoreCase = true) }
                val matchesAudio = excludedAudios.any { video.quality.contains(it, ignoreCase = true) }
                !matchesServer && !matchesAudio
            }
            .sortedWith(
                compareBy(
                    { !it.quality.contains(host, ignoreCase = true) },
                    { !it.quality.contains(quality, ignoreCase = true) },
                    { !it.quality.contains(type, ignoreCase = true) },
                ),
            )
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            entries = listOf("Soft Sub", "Hard Sub", "Dub"),
            entryValues = listOf("Soft Sub", "Hard Sub", "Dub"),
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_HOST_KEY,
            title = "Preferred Host",
            entries = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            entryValues = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            default = PREF_HOST_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Host",
            summary = "Select servers to exclude from the video list",
            entries = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            entryValues = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude Audio Types",
            summary = "Select audio formats to exclude from the video list",
            entries = listOf("Soft Sub", "Hard Sub", "Dub"),
            entryValues = listOf("Soft Sub", "Hard Sub", "Dub"),
        )
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = listOf("English", "Romaji/Japanese"),
            entryValues = listOf("English", "Romaji/Japanese"),
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Fetch and display images in the episode list.",
            default = true,
        )
    }

    // ============================== Filters ==============================

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getCheckedUriParts(): List<String> = state.mapIndexedNotNull { index, checkbox ->
            if (checkbox.state) vals[index].second else null
        }
    }

    class GenreFilter : CheckBoxFilterList("Genres", GENRES)
    class TypeFilter : CheckBoxFilterList("Types", TYPES)
    class StatusFilter : CheckBoxFilterList("Status", STATUSES)
    class LanguageFilter : CheckBoxFilterList("Language/Version", LANGUAGES)
    class YearFilter : CheckBoxFilterList("Years", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORT_BY)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        TypeFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        AnimeFilter.Separator(),
        LanguageFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "Soft Sub"

        private const val PREF_HOST_KEY = "preferred_host"
        private const val PREF_HOST_DEFAULT = "HD-1"

        private const val PREF_EXCLUDE_SERVERS_KEY = "exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "exclude_audio"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private val vibeRegex = Regex("""const src\s*=\s*"([^"]+)"""")

        private val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Cars", "cars"),
            Pair("Comedy", "comedy"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
        )

        private val TYPES = arrayOf(
            Pair("TV", "1"),
            Pair("Movie", "2"),
            Pair("OVA", "3"),
            Pair("ONA", "4"),
            Pair("Special", "5"),
            Pair("Music", "6"),
            Pair("TV_SHORT", "7"),
        )

        private val STATUSES = arrayOf(
            Pair("Ongoing", "Ongoing"),
            Pair("Completed", "Completed"),
            Pair("Upcoming", "info"),
        )

        private val LANGUAGES = arrayOf(
            Pair("Subbed", "sub"),
            Pair("Dubbed", "dub"),
        )

        private val YEARS = (2026 downTo 2000).map { Pair(it.toString(), it.toString()) }.toTypedArray()

        private val SORT_BY = arrayOf(
            Pair("Latest Update", "recently_updated"),
            Pair("Release Date", "release_date"),
            Pair("Recently Added", "recently_added"),
            Pair("Title A-Z", "title_az"),
        )
    }
}

class LocalProxy(private val client: okhttp3.OkHttpClient) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    /** True only when the local proxy server started successfully. */
    val isAvailable: Boolean get() = port > 0

    init {
        try {
            val ss = ServerSocket(0)
            serverSocket = ss
            port = ss.localPort
            executor.execute {
                while (!ss.isClosed) {
                    try {
                        val socket = ss.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // proxy server unavailable; isAvailable will return false
        }
    }

    /** Stop accepting new connections and release all resources. */
    fun shutdown() {
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    /**
     * Returns a local proxy URL for [targetUrl], or [targetUrl] itself when the
     * proxy server failed to start (port == 0).
     */
    fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String {
        if (!isAvailable) return targetUrl
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        var requestParsed = false
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]
            val queryIndex = rawPath.indexOf('?')
            val queryString = if (queryIndex != -1) rawPath.substring(queryIndex) else ""
            val pathWithoutQuery = if (queryIndex != -1) rawPath.substring(0, queryIndex) else rawPath

            val path = if (pathWithoutQuery.startsWith("http://") || pathWithoutQuery.startsWith("https://")) {
                Uri.parse(pathWithoutQuery).path ?: ""
            } else {
                pathWithoutQuery
            }

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path$queryString").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = okhttp3.Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            val request = okhttp3.Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            requestParsed = true
            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
            }
        } catch (e: Exception) {
            // Only attempt to send an HTTP error response when the request was
            // fully parsed; otherwise the socket's streams may be in an
            // inconsistent state and writing to them could throw or produce
            // garbage on the wire.
            if (requestParsed) {
                try {
                    sendError(socket, 500, e.message ?: "Internal Error")
                } catch (_: Exception) {}
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray()
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(modifiedContentBytes)
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val rawBytes = response.body.bytes()
            val stripped = stripPngHeader(rawBytes)
            out.write(stripped)
        }
        out.flush()
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}
