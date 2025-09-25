package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.AnimePageDto
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.EpisodesPageDto
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.JsLinks
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.lowercase

class Jkanime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: return@addInterceptor response

                val originalParams = request.url.queryParameterNames
                    .associateWith { request.url.queryParameter(it) }

                val redirectUrl = location.toHttpUrl().newBuilder().apply {
                    originalParams.forEach { (key, value) ->
                        if (value != null) addQueryParameter(key, value)
                    }
                }.build()

                val newRequest = request.newBuilder()
                    .url(redirectUrl)
                    .build()

                response.close()

                return@addInterceptor chain.proceed(newRequest)
            }
            response
        }.build()

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.pathSegments.first() == "buscar") {
                return@addInterceptor noRedirectClient.newCall(request).execute()
            }
            chain.proceed(request)
        }.build()

    private val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[JAP]"
        private val LANGUAGE_LIST = listOf("[JAP]", "[LAT]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private val SERVER_LIST = listOf(
            "Desu",
            "Okru",
            "Voe",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
            "Mixdrop",
            "DoodStream",
            "VidHide",
            "Mediafire",
            "Desuka",
            "Magi",
            "Nozomi",
        )
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/directorio?filtro=popularidad&p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(var animes =)")?.data().orEmpty()
        val regex = Regex("""var animes\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
        val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return AnimesPage(emptyList(), false)

        val json = json.decodeFromString<PopularAnimeModel>(jsonString)

        val hasNext = !json.nextPageUrl.isNullOrBlank()
        val animeList = json.data.map {
            SAnime.create().apply {
                title = it.title.orEmpty()
                thumbnail_url = it.image
                description = it.synopsis
                author = it.studios
                setUrlWithoutDomain(it.url.orEmpty())
            }
        }
        return AnimesPage(animeList, hasNext)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/directorio?estado=emision&p=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val dayFilter = filters.find { it is DayFilter } as DayFilter
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                dayFilter.state != 0 -> {
                    addPathSegment("horario")
                    addPathSegment("")
                    fragment(dayFilter.toValue())
                }
                query.isNotBlank() -> {
                    addPathSegment("buscar")
                    addPathSegment(query.replace(" ", "_"))
                }
                else -> {
                    addPathSegment("directorio")
                    addQueryParameter("p", page.toString())
                    filterList.filterIsInstance<UriPartFilterInterface>()
                        .mapNotNull { it.toQueryParam() }
                        .forEach { (name, value) -> addQueryParameter(name, value) }
                }
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val location = document.location().toHttpUrl().encodedPath
        return when {
            location.startsWith("/directorio") -> {
                val scriptData = document.selectFirst("script:containsData(var animes =)")?.data().orEmpty()
                val regex = Regex("""var animes\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
                val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return AnimesPage(emptyList(), false)

                val json = json.decodeFromString<PopularAnimeModel>(jsonString)

                val hasNext = !json.nextPageUrl.isNullOrBlank()
                val animeList = json.data.map {
                    SAnime.create().apply {
                        title = it.title.orEmpty()
                        thumbnail_url = it.image
                        description = it.synopsis
                        author = it.studios
                        setUrlWithoutDomain(it.url.orEmpty())
                    }
                }
                return AnimesPage(animeList, hasNext)
            }//searchAnimeParseDirectory(document)
            location.startsWith("/buscar") -> {
                val animeList = document.select(".anime__item").mapNotNull {
                    SAnime.create().apply {
                        title = it.select("h5 a").text()
                        thumbnail_url = it.selectFirst(".set-bg")?.attr("abs:data-setbg")
                        setUrlWithoutDomain(it.select("a").attr("abs:href"))
                    }
                }
                return AnimesPage(animeList, false)
            }//searchAnimeParseSearch(document)
            location.startsWith("/horario") -> searchAnimeParseSchedule(document)
            else -> AnimesPage(emptyList(), false)
        }
    }

    private fun searchAnimeParseDirectory(document: Document): AnimesPage {
        val animePageJson = document.selectFirst("script:containsData(var animes = )")?.data()
            ?.let(::parseAnimeJsonString)
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonStr -> json.decodeFromString<AnimePageDto>(jsonStr) }
            ?: return AnimesPage(emptyList(), false)

        val animeList = animePageJson.data.map { animeDto ->
            SAnime.create().apply {
                setUrlWithoutDomain(animeDto.url)
                title = animeDto.title
                description = animeDto.description
                thumbnail_url = animeDto.thumbnailUrl
                author = animeDto.author
                status = parseStatus(animeDto.status)
            }
        }
        return AnimesPage(animeList, !animePageJson.nextPageUrl.isNullOrBlank())
    }

    private fun parseAnimeJsonString(script: String): String? {
        // Try to capture a JSON object assigned to `var animes = {...};` using a DOTALL regex.
        // Use non-greedy matching to get until the first closing brace followed by a semicolon.
        // Exclude possible closing braces followed by semicolons inside strings quoted by " or '.
        val pattern = Regex(
            """var\s+animes\s*=\s*(\{(?:[^"']|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return pattern.find(script)?.groups[1]?.value
    }

    private fun searchAnimeParseSearch(document: Document): AnimesPage {
        val animes = document.select("div.row div.row.page_directorio div.anime__item")
            .mapNotNull { element ->
                val itemText = element.selectFirst("div.anime__item__text a") ?: return@mapNotNull null
                SAnime.create().apply {
                    title = itemText.text()
                    thumbnail_url = element.selectFirst("div.g-0")?.attr("abs:data-setbg")
                    setUrlWithoutDomain(itemText.attr("href"))
                }
            }
        return AnimesPage(animes, false)
    }

    private fun searchAnimeParseSchedule(document: Document): AnimesPage {
        val day = document.location().substringAfterLast("#")
        val animeBox = document.selectFirst("h2:contains($day) ~ div.cajas")

        val animeList = animeBox?.select("div.boxx")?.mapNotNull { elm ->
            SAnime.create().apply {
                val url = elm.selectFirst("a")?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                title = elm.selectFirst("img")?.attr("title") ?: return@mapNotNull null
                thumbnail_url = elm.selectFirst("img")?.attr("abs:src")
            }
        } ?: emptyList()

        return AnimesPage(animeList, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        document.selectFirst("div.anime__details__content div.anime_info img")?.getImageUrl()?.let { anime.thumbnail_url = it }
        document.selectFirst("div.anime__details__content div.anime_info h3")?.text()?.let { anime.title = it }
        document.selectFirst("div.anime__details__content div.anime_info p.scroll")?.text()?.let { anime.description = it }
        document.select("div.anime__details__content div.anime_data.pc li").forEach { animeData ->
            val data = animeData.select("span").text()
            if (data.contains("Generos:")) {
                anime.genre = animeData.select("a").joinToString { it.text() }
            }
            if (data.contains("Estado")) {
                anime.status = parseStatus(animeData.select("div").text())
            }
            if (data.contains("Studios")) {
                anime.author = animeData.select("a").text()
            }
        }

        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.url.toString().trim('/')
        val pageBody = response.asJsoup()
        val token = pageBody.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return emptyList()
        val formData = FormBody.Builder().add("_token", token).build()
        val animeId = pageBody.selectFirst("div.anime__details__content div.pc div#guardar-anime")
            ?.attr("data-anime")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val episodesPage = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/1", headers, formData))
            .execute().body.string()
            .let { jsonStr -> json.decodeFromString<EpisodesPageDto>(jsonStr) }

        val firstEp = episodesPage.data.firstOrNull()?.number ?: 1
        val lastEp = if (firstEp == 0) (episodesPage.total - 1) else episodesPage.total

        val episodes = mutableListOf<SEpisode>()
        for (i in firstEp..lastEp) {
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain("$animeUrl/$i")
                name = "Episodio $i"
                episode_number = i.toFloat()
            }
            episodes.add(episode)
        }

        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        try {
            val animeId = doc.selectFirst("[data-anime]")?.attr("data-anime").orEmpty()
            val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
            val episodePath = doc.selectFirst("[property=\"og:url\"]")?.attr("content")
            val xsrfToken = response.headers.filter { it.first == "set-cookie" }
            val referer = doc.location()
            var currentPage = 1
            var requestCount = 0
            var animePage = fetchAnimeEpisodes(referer, token, animeId, currentPage, xsrfToken)
            while (animePage != null) {
                animePage.data.forEach {
                    episodes.add(
                        SEpisode.create().apply {
                            episode_number = it.number?.toFloat() ?: 0F
                            name = "Episodio ${it.number}"
                            date_upload = it.timestamp?.toDate() ?: 0L
                            setUrlWithoutDomain("$episodePath${it.number}/")
                        },
                    )
                }

                // pause every 15 request
                requestCount++
                if (requestCount % 10 == 0) {
                    println("Esperando para evitar 429... ($requestCount requests realizadas)")
                    Thread.sleep(5000) // wait 5 seconds
                }

                currentPage++
                animePage = if (!animePage.nextPageUrl.isNullOrEmpty()) {
                    fetchAnimeEpisodes(referer, token, animeId, currentPage, xsrfToken)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.i("bruh getEpisodes", "Error: ${e.message}", e)
        }
        return episodes.reversed()
    }

    private fun fetchAnimeEpisodes(referer: String, token: String, animeId: String, page: Int, cookies: List<Pair<String, String>>): EpisodeAnimeModel? {
        return runCatching {
            val body = "_token=$token".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val newHeaders = mapOf("Cookie" to cookies.joinToString(" ") { "${it.second.substringBeforeLast(";")};" }) + mapOf("Referer" to referer)
            val response = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/$page", body = body, headers = newHeaders.toHeaders())).execute()
            return json.decodeFromString<EpisodeAnimeModel>(response.body.string())
        }.getOrNull()
    }

    private val languages = arrayOf(
        Pair(1, "[JAP]"),
        Pair(3, "[LAT]"),
        Pair(4, "[CHIN]"),
    )

    private fun Int?.getLang(): String = languages.firstOrNull { it.first == this }?.second ?: ""

    private fun getVideoLinks(document: Document): List<Pair<String, String>> {
        val scriptServers = document.selectFirst("script:containsData(var video = [];)")?.data() ?: return emptyList()
        val isRemote = scriptServers.contains("= remote+'", true)
        val jsServer = scriptServers.substringAfter("var remote = '").substringBefore("'")
        val jsPath = scriptServers.substringAfter("= remote+'").substringBefore("'")

        val jsLinks = if (isRemote && jsServer.isNotEmpty()) {
            client.newCall(GET(jsServer + jsPath)).execute().body.string()
        } else {
            scriptServers.substringAfter("var servers = ").substringBefore(";").substringBefore("var")
        }.parseAs<Array<JsLinks>>().map {
            Pair(String(Base64.decode(it.remote, Base64.DEFAULT)), "${it.lang}".getLang())
        }

        val htmlLinks = document.select("div.bg-servers a").map {
            val serverId = it.attr("data-id")
            val lang = it.attr("class").substringAfter("lg_").substringBefore(" ").getLang()
            val url = scriptServers
                .substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.ag/e/")
                .replace("/jksw.php?u=", "https://sfastwish.com/e/")
                .replace("/jk.php?u=", "$baseUrl/")
            Pair(url, lang)
        }

        return jsLinks + htmlLinks
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    // Removed StreamWish extractor because it is causing timeout errors and significantly increasing load times.
    // private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun String.containsAny(vararg substrings: String): Boolean = substrings.any { it in this }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val scriptData = doc.selectFirst("script:containsData(var servers)")?.data().orEmpty()
        val regex = Regex("""var servers\s*=\s*(\[.*\]);""", RegexOption.UNIX_LINES)
        val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return emptyList()

        return json.decodeFromString<List<ServerAnimeModel>>(jsonString).parallelCatchingFlatMapBlocking {
            val url = String(Base64.decode(it.remote.orEmpty(), Base64.DEFAULT))
            val prefix = it.lang.getLang()
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                "mixdrop" -> mixDropExtractor.videoFromUrl(url, prefix = "$prefix ")
                "desuka" -> jkanimeExtractor.getDesukaFromUrl(url, "$prefix ")
                "nozomi" -> jkanimeExtractor.getNozomiFromUrl(url, "$prefix ")
                "desu" -> jkanimeExtractor.getDesuFromUrl(url, "$prefix ")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "mp4upload" to listOf("mp4upload"),
        "mixdrop" to listOf("mixdrop", "mxdrop"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "desuka" to listOf("stream/jkmedia"),
        "nozomi" to listOf("um2.php", "nozomi"),
        "desu" to listOf("um.php"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val lang = preferences.langPref
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Por estrenar") -> SAnime.ONGOING
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Concluido") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto no incluye filtros"),
        DayFilter(),
        GenreFilter(),
        TypeFilter(),
        StateFilter(),
        SeasonFilter(),
        AnimeFilter.Header("Busqueda por año"),
        YearFilter(),
        AnimeFilter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers(),
    )

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.langPref by preferences.delegate(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val server = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        if (server !in SERVER_LIST) {
            edit()
                .putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                .apply()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LANGUAGE_KEY,
            title = "Preferred language",
            entries = LANGUAGE_LIST,
            entryValues = LANGUAGE_LIST,
            default = PREF_LANGUAGE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }

    private fun Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("anime.png")
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
}
