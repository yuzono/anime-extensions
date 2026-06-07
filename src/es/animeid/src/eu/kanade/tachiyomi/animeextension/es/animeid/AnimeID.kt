package eu.kanade.tachiyomi.animeextension.es.animeid

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AnimeID : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    /**
     * Custom OkHttpClient independent from the global `client`.
     * - Mobile Chrome User-Agent
     * - Own ConnectionPool and ConnectionSpec to avoid sharing TLS sessions
     */
    private val customClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .addInterceptor { chain ->
                val original = chain.request()
                val newReq = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .build()
                chain.proceed(newReq)
            }
            .build()
    }

    // Use the same customClient for all extractors to keep behavior consistent
    private val voeExtractor by lazy { VoeExtractor(customClient, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(customClient) }
    private val uqloadExtractor by lazy { UqloadExtractor(customClient) }
    private val streamWishExtractor by lazy { StreamWishExtractor(customClient, headers) }
    private val universalExtractor by lazy { UniversalExtractor(customClient) }

    // Track last host used to evict connection pool when switching servers
    private var lastServerHost: String? = null

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.ul.x5 article.li, div.ul article.li"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series?pag=$page"
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.select("a").first()
        val href = linkElement?.attr("href") ?: ""
        anime.setUrlWithoutDomain(href)
        anime.title = linkElement?.select("span")?.text()?.trim() ?: ""

        val imgElement = element.select("figure.i img").first()
        var imgUrl = imgElement?.attr("data-src")
        if (imgUrl.isNullOrEmpty()) imgUrl = imgElement?.attr("src")
        if (!imgUrl.isNullOrEmpty()) {
            anime.thumbnail_url = if (imgUrl.startsWith("http")) imgUrl else baseUrl + imgUrl
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pag li a:contains(Siguiente)"

    // ============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "div.ul.hm article.li"

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.select("a").first()
        val href = linkElement?.attr("href") ?: ""
        val animeSlug = href.substringAfter("/ver/").substringBeforeLast("-")
        anime.setUrlWithoutDomain("/$animeSlug")
        anime.title = linkElement?.select("span")?.text()?.trim() ?: ""

        val imgElement = element.select("figure.i img").first()
        var imgUrl = imgElement?.attr("data-src")
        if (imgUrl.isNullOrEmpty()) imgUrl = imgElement?.attr("src")
        if (!imgUrl.isNullOrEmpty()) {
            anime.thumbnail_url = if (imgUrl.startsWith("http")) imgUrl else baseUrl + imgUrl
        }
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // ============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter
        val yearFilter = filterList.find { it is YearFilter } as? YearFilter
        val statusFilter = filterList.find { it is StatusFilter } as? StatusFilter
        val orderFilter = filterList.find { it is OrderFilter } as? OrderFilter

        val params = mutableListOf<String>()

        if (query.isNotBlank()) {
            params.add("buscar=$query")
        } else {
            genreFilter?.takeIf { it.state != 0 }?.let {
                params.add("genero=${it.toUriPart()}")
            }
            yearFilter?.takeIf { it.state != 0 }?.let {
                params.add("anio=${it.toUriPart()}")
            }
            statusFilter?.takeIf { it.state != 0 }?.let {
                params.add("estado=${it.toUriPart()}")
            }
        }

        orderFilter?.takeIf { it.state != 0 && query.isBlank() }?.let {
            params.add("sort=${it.toUriPart()}")
        }

        if (page > 1) {
            params.add("pag=$page")
        }

        val url = if (params.isEmpty()) {
            "$baseUrl/series"
        } else {
            "$baseUrl/series?${params.joinToString("&")}"
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        OrderFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("Todos", ""),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Fantasía", "fantasia"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "ciencia-ficcion"),
            Pair("Seinen", "seinen"),
            Pair("Shounen", "shounen"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror")
        )
    )

    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(
            Pair("Todos", ""),
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020")
        )
    )

    private class StatusFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("Todos", ""),
            Pair("En emisión", "en-emision"),
            Pair("Finalizado", "finalizado")
        )
    )

    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Recientes", "newest"),
            Pair("Popularidad", "views"),
            Pair("A-Z", "asc")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = if (state > 0) vals[state].second else ""
    }

    // ============================== Anime Details ===============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.info-a .c h1")?.text()?.trim() ?: ""
        anime.thumbnail_url = document.selectFirst("div.info-a .i img")?.attr("data-src")
            ?: document.selectFirst("div.info-a .i img")?.attr("src")
        anime.description = document.selectFirst("div.info-a .c .tx p")?.text()?.trim()
        anime.genre = document.select("div.info-a .c .gn li a").joinToString { it.text() }
        val statusText = document.selectFirst("div.info-b .dv ul li strong.ee, div.info-b .dv ul li strong.ef")?.text()?.trim()
        anime.status = when {
            statusText?.contains("emision", ignoreCase = true) == true -> SAnime.ONGOING
            statusText?.contains("finalizada", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    // ============================== Episodes  ===============================
    override fun episodeListSelector(): String = "ul.epis li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.select("a").first()
        val href = linkElement?.attr("href") ?: ""
        val episodeNumber = href.substringAfterLast("-").toFloatOrNull() ?: 1f
        episode.setUrlWithoutDomain(href)
        episode.name = "Episodio ${episodeNumber.toInt()}"
        episode.episode_number = episodeNumber
        return episode
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val initialHtml = response.body?.string() ?: return emptyList()
        val doc = Jsoup.parse(initialHtml, baseUrl)
        val animeId = doc.selectFirst("#dt")?.attr("data-i") ?: return emptyList()
        val animeSlug = doc.selectFirst("#dt")?.attr("data-u") ?: return emptyList()

        val allEpisodes = mutableListOf<SEpisode>()
        var currentPage = 1
        while (true) {
            val pageHtml = fetchEpisodesPage(animeId, animeSlug, currentPage)
            if (pageHtml.isBlank()) break
            val pageDoc = Jsoup.parse(pageHtml)
            val episodesOnPage = pageDoc.select(episodeListSelector()).map { episodeFromElement(it) }
            if (episodesOnPage.isEmpty()) break
            allEpisodes.addAll(episodesOnPage)
            currentPage++
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) { /* ignore */ }
        }
        return allEpisodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    private fun fetchEpisodesPage(animeId: String, animeSlug: String, page: Int): String {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/id")
                .post(
                    FormBody.Builder()
                        .add("acc", "episodes")
                        .add("i", animeId)
                        .add("u", animeSlug)
                        .add("p", page.toString())
                        .build()
                )
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", baseUrl)
                .build()
            val response = customClient.newCall(request).execute()
            response.body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ============================== Videos  ==================================
    private fun serverVideoResolverBlocking(url: String): List<Video> {
        // Determine host to detect server switch
        val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()
            ?: url.substringAfter("://").substringBefore("/")
        if (lastServerHost == null || lastServerHost != host) {
            try {
                customClient.connectionPool.evictAll()
            } catch (_: Exception) { /* ignore */ }
            lastServerHost = host
        }
        return runBlocking {
            serverVideoResolver(url)
        }
    }

    private suspend fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return try {
            when {
                embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url, prefix = "Voe")
                embedUrl.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload")
                embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload")
                embedUrl.contains("streamwish") -> streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        } catch (e: Exception) {
            universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string() ?: return emptyList()
        val document = Jsoup.parse(body, baseUrl)
        val videos = mutableListOf<Video>()

        val optElement = document.selectFirst("ul.opt[data-encrypt]")
        if (optElement != null) {
            val encryptedId = optElement.attr("data-encrypt")
            val serversHtml = fetchServerList(encryptedId)
            if (serversHtml.isNotBlank()) {
                val videoUrls = parseServerUrls(serversHtml)
                for ((_, pageUrl) in videoUrls) {
                    videos.addAll(serverVideoResolverBlocking(pageUrl))
                }
                if (videos.isNotEmpty()) return orderVideosByPreferences(videos)
            }
        }

        if (videos.isEmpty()) {
            document.select("#partes div.container li.subtab div.parte").forEach { script ->
                val jsonString = script.attr("data")
                val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
                val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
                if (url.isNotBlank()) {
                    videos.addAll(serverVideoResolverBlocking(url))
                }
            }
        }

        return orderVideosByPreferences(videos)
    }

     
    private fun orderVideosByPreferences(videos: List<Video>): List<Video> {
        val preferredServer = preferences.getString("animeid_preferred_server", "Voe")?.lowercase()
        val preferredQuality = preferences.getString("animeid_preferred_quality", "any")?.lowercase()

        fun detectServer(video: Video): String {
            val urlHost = runCatching { video.url.toHttpUrlOrNull()?.host }.getOrNull() ?: ""
            return when {
                urlHost.contains("voe", ignoreCase = true) -> "voe"
                urlHost.contains("mp4upload", ignoreCase = true) -> "mp4upload"
                urlHost.contains("uqload", ignoreCase = true) -> "uqload"
                urlHost.contains("streamwish", ignoreCase = true) -> "streamwish"
                video.quality.contains("voe", ignoreCase = true) -> "voe"
                video.quality.contains("mp4upload", ignoreCase = true) -> "mp4upload"
                video.quality.contains("uqload", ignoreCase = true) -> "uqload"
                video.quality.contains("streamwish", ignoreCase = true) -> "streamwish"
                else -> "other"
            }
        }

        fun qualityScore(video: Video): Int {
            if (preferredQuality == null || preferredQuality == "any") return 0
            val qToken = "${preferredQuality}p"
            return when {
                video.quality.contains(qToken, ignoreCase = true) -> 3
                video.quality.contains(preferredQuality, ignoreCase = true) -> 2
                video.url.contains("/$qToken", ignoreCase = true) -> 3
                else -> 0
            }
        }

        val grouped = videos.groupBy { detectServer(it) }

        val order = mutableListOf<Video>()

        if (!preferredServer.isNullOrBlank()) {
            grouped[preferredServer]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        val serversOrder = listOf("voe", "mp4upload", "uqload", "streamwish", "other")
        for (srv in serversOrder) {
            if (srv == preferredServer) continue
            grouped[srv]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        val addedUrls = order.map { it.url }.toSet()
        videos.filter { it.url !in addedUrls }.let { leftovers ->
            order.addAll(leftovers.sortedByDescending { qualityScore(it) })
        }

        return order
    }

    // ============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "animeid_preferred_server"
            title = "Servidor preferido"
            entries = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            entryValues = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            setDefaultValue("Voe")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "animeid_preferred_quality"
            title = "Calidad preferida"
            entries = arrayOf("Automático", "480p", "720p", "1080p")
            entryValues = arrayOf("automatic", "480", "720", "1080")
            setDefaultValue("automatic")
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Utilidades ===============================
    private fun fetchServerList(encryptId: String): String {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/id")
                .post(
                    FormBody.Builder()
                        .add("acc", "opt")
                        .add("i", encryptId)
                        .build()
                )
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", baseUrl)
                .build()
            val response = customClient.newCall(request).execute()
            response.body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseServerUrls(html: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val document = Jsoup.parse(html)
        val serverItems = document.select("li")
        for (item in serverItems) {
            val encryptedUrl = item.attr("encrypt")
            if (encryptedUrl.isNotEmpty()) {
                val decodedUrl = hexToString(encryptedUrl)
                val serverName = item.select("span").first()?.text()?.trim() ?: "Servidor"
                if (decodedUrl.isNotEmpty()) {
                    result.add(Pair(serverName, decodedUrl))
                }
            }
        }
        return result
    }

    private fun hexToString(hex: String): String {
        return try {
            val output = StringBuilder()
            var i = 0
            while (i < hex.length) {
                val str = hex.substring(i, minOf(i + 2, hex.length))
                output.append(str.toInt(16).toChar())
                i += 2
            }
            output.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun unescapeJava(escaped: String): String {
        var escapedVar = escaped
        if (escapedVar.indexOf("\\u") == -1) return escapedVar
        var processed = ""
        var position = escapedVar.indexOf("\\u")
        while (position != -1) {
            if (position != 0) processed += escapedVar.substring(0, position)
            val token = escapedVar.substring(position + 2, position + 6)
            escapedVar = escapedVar.substring(position + 6)
            processed += token.toInt(16).toChar()
            position = escapedVar.indexOf("\\u")
        }
        processed += escapedVar
        return processed
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
