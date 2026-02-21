package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Jkanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

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
        explicitNulls = false
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
            "Okru",
            "Voe",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
            "Mixdrop",
            "Streamwish",
            "DoodStream",
            "VidHide",
            "Mediafire",
            "Desuka",
            "Nozomi",
            "Desu",
            "Magi",
        )
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()

        private const val PREF_EPISODES_INFO = "pref_episodes_info"
        private val EPISODES_INFO = mapOf(
            "Población rápida" to "0",
            "Solicitar información de la fuente" to "1",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/directorio?filtro=popularidad&p=$page", headers)

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET(baseUrl, headers)
    } else {
        GET("$baseUrl/directorio?p=${page - 1}", headers)
    }

    private fun homepageAnimesSelector(): String = "div.trending_div div.custom_thumb_home a"

    private fun homepageAnimesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href").trim('/'))
        title = element.select("img").attr("alt")
        thumbnail_url = element.select("img").attr("abs:src")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val location = response.request.url.encodedPath
        return when {
            location.startsWith("/directorio") -> searchAnimeParse(response)

            else -> {
                val document = response.asJsoup()
                val animes = document.select(homepageAnimesSelector()).map(::homepageAnimesFromElement)
                AnimesPage(animes, true)
            }
        }
    }

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
            location.startsWith("/directorio") -> searchAnimeParseDirectory(document)
            location.startsWith("/buscar") -> searchAnimeParseSearch(document)
            location.startsWith("/horario") -> searchAnimeParseSchedule(document)
            else -> AnimesPage(emptyList(), false)
        }
    }

    private fun searchAnimeParseDirectory(document: Document): AnimesPage {
        val animePageJson = document.selectFirst("script:containsData(var animes = )")?.data()
            ?.let { script -> pattern.find(script)?.groups[1]?.value }
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonStr -> json.decodeFromString<AnimePageDto>(jsonStr) }
            ?: return AnimesPage(emptyList(), false)

        val animeList = animePageJson.data.map { animeDto ->
            SAnime.create().apply {
                setUrlWithoutDomain(animeDto.url)
                title = animeDto.title
                description = animeDto.synopsis
                thumbnail_url = animeDto.thumbnailUrl
                author = animeDto.studios
                status = animeDto.status?.let(::parseStatus) ?: SAnime.UNKNOWN
            }
        }
        return AnimesPage(animeList, !animePageJson.nextPageUrl.isNullOrBlank())
    }

    // Try to capture a JSON object assigned to `var animes = {...};` using a DOTALL regex.
    // Use non-greedy matching to get until the first closing brace followed by a semicolon.
    // Exclude possible closing braces followed by semicolons inside strings quoted by " or '.
    val pattern by lazy {
        Regex(
            """var\s+animes\s*=\s*(\{(?:[^"']|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
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
        document.selectFirst("div.anime__details__content div.anime_pic img")?.attr("abs:src")?.let { anime.thumbnail_url = it }
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

    private fun List<EpisodeDto>.toEpisodeList(animeUrl: String): List<SEpisode> = map { ep ->
        SEpisode.create().apply {
            episode_number = ep.number.toFloat()
            name = "Episodio ${ep.number}"
            date_upload = ep.timestamp?.toDate() ?: 0L
            setUrlWithoutDomain("$animeUrl/${ep.number}")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.url.toString().trim('/')
        val pageBody = response.asJsoup()
        val token = pageBody.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return emptyList()
        val xsrfToken = response.headers.filter { it.first == "set-cookie" }
        val formData = FormBody.Builder().add("_token", token).build()
        val animeId = pageBody.selectFirst("div.anime__details__content div.pc div#guardar-anime")
            ?.attr("data-anime")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()
        val cookieHeaders = headers.newBuilder().apply {
            add(
                "Cookie",
                xsrfToken.joinToString(" ") { "${it.second.substringBeforeLast(";")};" },
            )
        }.build()
        val episodesPage = fetchAnimeEpisodes(animeId, 1, cookieHeaders, formData)
        episodesPage.data.toEpisodeList(animeUrl).let(episodes::addAll)

        if (preferences.episodeInfoPref == "0") {
            val firstEp = episodesPage.from
            val lastEp = episodesPage.total + firstEp - 1

            for (i in episodesPage.to + 1..lastEp) {
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain("$animeUrl/$i")
                    name = "Episodio $i"
                    episode_number = i.toFloat()
                }
                episodes.add(episode)
            }
        } else {
            for (currentPage in 2..episodesPage.lastPage) {
                if (currentPage % 10 == 0) {
                    Thread.sleep(1000)
                }

                runCatching {
                    fetchAnimeEpisodes(animeId, currentPage, cookieHeaders, formData)
                }.getOrNull()
                    ?.data?.toEpisodeList(animeUrl)?.let(episodes::addAll)
            }
        }
        return episodes.reversed()
    }

    private fun fetchAnimeEpisodes(
        animeId: String,
        currentPage: Int,
        cookieHeaders: Headers,
        formData: FormBody,
    ) = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/$currentPage", headers = cookieHeaders, body = formData))
        .execute().body.string()
        .let { jsonStr -> json.decodeFromString<EpisodesPageDto>(jsonStr) }

    private val languages = arrayOf(
        Pair(1, "[JAP]"),
        Pair(3, "[LAT]"),
        Pair(4, "[CHIN]"),
    )

    private fun Int?.getLang() = languages.firstOrNull { it.first == this }?.second ?: ""

    private fun getVideoLinks(document: Document): List<Triple<String, String, String>> {
        val scriptServers = document.selectFirst("script:containsData(var video = [];)")?.data() ?: return emptyList()
        val isRemote = scriptServers.contains("= remote+'", true)
        val jsServer = scriptServers.substringAfter("var remote = '").substringBefore("'")
        val jsPath = scriptServers.substringAfter("= remote+'").substringBefore("'")

        val jsLinks = if (isRemote && jsServer.isNotEmpty()) {
            client.newCall(GET(jsServer + jsPath)).execute().body.string()
        } else {
            val regex = Regex("""var servers\s*=\s*(\[.*]);""", RegexOption.UNIX_LINES)
            regex.find(scriptServers)?.groupValues?.get(1)
        }?.parseAs<Array<JsLinks>>()?.map {
            Triple(String(Base64.decode(it.remote, Base64.DEFAULT)), it.lang.getLang(), it.server ?: "")
        } ?: emptyList()

        val htmlLinks = document.select("div.bg-servers a").map {
            val serverId = it.attr("data-id")
            val lang = it.attr("class").substringAfter("lg_").substringBefore(" ").toIntOrNull()?.getLang() ?: ""
            val name = it.text().ifBlank { "" } ?: ""
            val url = scriptServers
                .substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.ag/e/")
                .replace("/jksw.php?u=", "https://sfastwish.com/e/")
                .replace("/jk.php?u=", "$baseUrl/")
            Triple(url, lang, name)
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

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return getVideoLinks(document).parallelCatchingFlatMapBlocking { (url, lang, name) ->
            val matched = serverMatching.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first ?: name.lowercase()
            when (matched) {
                // "mega" -> emptyList() // Skip mega server
                "okru" -> okruExtractor.videosFromUrl(url, lang)

                "voe" -> voeExtractor.videosFromUrl(url, "$lang ")

                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$lang Filemoon:")

                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$lang StreamTape")

                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, prefix = "$lang ", headers = headers)

                "mixdrop" -> mixDropExtractor.videoFromUrl(url, prefix = "$lang ")

                // Removed StreamWish extractor because it is causing timeout errors and significantly increasing load times.
                // "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" }) // Use UniversalExtractor
                "doostream" -> doodExtractor.videosFromUrl(url.replace("d-s.io", "dsvplay.com"), "$lang ${name.ifBlank { "Doodstream" }}")

                "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$lang VidHide:$it" })

                "mediafire" -> jkanimeExtractor.getMediafireFromUrl(url, "$lang ")

                "desuka" -> jkanimeExtractor.getDesukaFromUrl(url, "$lang ")

                "nozomi" -> jkanimeExtractor.getNozomiFromUrl(url, "$lang ")

                "desu" -> jkanimeExtractor.getDesuFromUrl(url, "$lang ")

                "magi" -> jkanimeExtractor.getMagiFromUrl(url, "$lang ")

                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$lang $name")
            }
        }
    }

    private val serverMatching = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "mixdrop" to listOf("mixdrop", "mxdrop", "mdbekjwqa"),
        "streamwish" to listOf("sfastwish", "wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doostream" to listOf("d-s.io", "dsvplay"),
        "desuka" to listOf("stream/jkmedia"),
        "nozomi" to listOf("jkplayer/um2?", "um2.php", "nozomi"),
        "desu" to listOf("jkplayer/um?", "um.php"),
        "magi" to listOf("jkplayer/umv?"),
        "mega" to listOf("mega.nz"),
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

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("Por estrenar") -> SAnime.ONGOING
        statusString.contains("En emision") -> SAnime.ONGOING
        statusString.contains("Concluido") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto no incluye filtros"),
        GenreFilter(),
        LetterFilter(),
        DemographyFilter(),
        CategoryFilter(),
        TypeFilter(),
        StateFilter(),
        AnimeFilter.Header("Busqueda por año"),
        YearFilter(),
        SeasonFilter(),
        AnimeFilter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers(),
        AnimeFilter.Separator(),
        DayFilter(),
    )

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.langPref by preferences.delegate(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
    private val SharedPreferences.episodeInfoPref by preferences.delegate(PREF_EPISODES_INFO, "0")

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

        screen.addListPreference(
            key = PREF_EPISODES_INFO,
            title = "Episode info",
            entries = EPISODES_INFO.keys.toList(),
            entryValues = EPISODES_INFO.values.toList(),
            default = "0",
            summary = "%s",
        )
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
}
