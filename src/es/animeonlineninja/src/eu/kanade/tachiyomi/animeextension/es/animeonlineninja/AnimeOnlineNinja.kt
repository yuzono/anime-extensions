package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import android.util.Log
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AnimeOnlineNinja :
    DooPlay(
        "es",
        "AnimeOnline.Ninja",
        "https://ww3.animeonline.ninja",
    ) {
    
    override val headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("DNT", "1")
        .add("Connection", "keep-alive")
        .add("Upgrade-Insecure-Requests", "1")
        .build()
    
    override val client by lazy {
        val baseClient = network.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        if (preferences.getBoolean(PREF_VRF_INTERCEPT_KEY, PREF_VRF_INTERCEPT_DEFAULT)) {
            baseClient.newBuilder()
                .addInterceptor(VrfInterceptor())
                .build()
        } else {
            baseClient
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/$page", headers)

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeOnlineNinjaFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("tendencias", "ratings")) {
                    "/" + params.genre
                } else {
                    "/genero/${params.genre}"
                }
            }
            params.language.isNotBlank() -> "/genero/${params.language}"
            params.year.isNotBlank() -> "/release/${params.year}"
            params.movie.isNotBlank() -> {
                if (params.movie == "pelicula") {
                    "/pelicula"
                } else {
                    "/genero/${params.movie}"
                }
            }
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        params.letter.isNotBlank() -> "/letra/${params.letter}/?"
                        else -> "/tendencias/?"
                    },
                )
                append(
                    if (contains("tendencias")) {
                        "&get=${when (params.type) {
                            "serie" -> "TV"
                            "pelicula" -> "movies"
                            else -> "todos"
                        }}"
                    } else {
                        "&tipo=${params.type}"
                    },
                )
                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path", headers)
        } else if (path.startsWith("/letra") || path.startsWith("/tendencias")) {
            val before = path.substringBeforeLast("/")
            val after = path.substringAfterLast("/")
            GET("$baseUrl$before/page/$page/$after", headers)
        } else {
            GET("$baseUrl$path/page/$page", headers)
        }
    }

    // ============================== Episodes ==============================
    override val episodeMovieText = "Película"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val currentUrl = doc.location()
        
        // Extraer ID del post desde la URL actual
        val postId = extractPostIdFromUrl(currentUrl)
        
        // Intentar obtener episodios vía API (más rápido y confiable)
        if (postId.isNotBlank()) {
            val apiEpisodes = getEpisodesFromApiWithRetry(postId)
            if (apiEpisodes.isNotEmpty()) {
                Log.d("AnimeOnlineNinja", "Episodios obtenidos vía API: ${apiEpisodes.size}")
                return apiEpisodes
            }
        }
        
        // Si la API falla, intentar con HTML usando reintentos
        val animeUrl = if (currentUrl.contains("/episodio/")) {
            doc.selectFirst("a[href*=/online/]:not([href*=/episodio/])")?.attr("abs:href")
                ?: doc.selectFirst(".sbox a[href*=/online/]")?.attr("abs:href")
                ?: doc.selectFirst(".content a[href*=/online/]")?.attr("abs:href")
                ?: currentUrl.replace("/episodio/", "/online/")
        } else {
            currentUrl
        }
        
        val finalDoc = if (animeUrl != currentUrl) {
            fetchWithRetry(animeUrl)?.asJsoup() ?: doc
        } else {
            doc
        }
        
        val episodes = parseEpisodesFromHtml(finalDoc)
        
        return if (episodes.isNotEmpty()) {
            episodes
        } else {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(finalDoc.location())
                    episode_number = 1F
                    name = episodeMovieText
                }
            )
        }
    }
    
    private fun fetchWithRetry(url: String, maxRetries: Int = 3): Response? {
        var lastResponse: Response? = null
        for (i in 0 until maxRetries) {
            try {
                val request = GET(url, headers)
                val response = client.newCall(request).execute()
                val bodyString = response.body?.string()
                
                // Verificar si la respuesta es válida (no contiene página de Cloudflare)
                if (bodyString != null && !bodyString.contains("One moment, please") && 
                    !bodyString.contains("Checking your browser")) {
                    return response.newBuilder().body(bodyString.toResponseBody(response.body?.contentType())).build()
                }
                
                // Si es Cloudflare, esperar antes de reintentar
                Log.d("AnimeOnlineNinja", "Intento ${i+1} falló por Cloudflare, reintentando...")
                Thread.sleep(2000 * (i + 1L)) // Espera progresiva: 2s, 4s, 6s
                lastResponse = response
            } catch (e: Exception) {
                Log.e("AnimeOnlineNinja", "Error en intento ${i+1}: ${e.message}")
            }
        }
        return lastResponse
    }
    
    private fun extractPostIdFromUrl(url: String): String {
        // Patrón para /online/titulo/ -> buscar en API
        val pattern = Regex("""/online/([^/]+)/?$""")
        pattern.find(url)?.let {
            val slug = it.groupValues[1]
            return getPostIdBySlug(slug)
        }
        return ""
    }
    
    private fun getPostIdBySlug(slug: String): String {
        try {
            val apiUrl = "$baseUrl/wp-json/wp/v2/posts?slug=$slug&_fields=id"
            val request = GET(apiUrl, headers)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return ""
                val idPattern = Regex("""\[\{"id":(\d+)\}\]""")
                idPattern.find(json)?.let { return it.groupValues[1] }
            }
        } catch (e: Exception) {
            Log.e("AnimeOnlineNinja", "Error obteniendo post ID: ${e.message}")
        }
        return ""
    }
    
    private fun getEpisodesFromApiWithRetry(postId: String): List<SEpisode> {
        repeat(3) { attempt ->
            try {
                val apiUrl = "$baseUrl/wp-json/dooplayer/v1/post/$postId"
                val request = GET(apiUrl, headers)
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: continue
                    // Verificar que no sea página de Cloudflare
                    if (json.contains("One moment, please")) {
                        Thread.sleep(2000)
                        return@repeat
                    }
                    return parseEpisodesFromJson(json)
                }
            } catch (e: Exception) {
                Log.e("AnimeOnlineNinja", "API intento ${attempt + 1} falló: ${e.message}")
                Thread.sleep(2000)
            }
        }
        return emptyList()
    }
    
    private fun parseEpisodesFromJson(json: String): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        
        // Buscar patrones de episodios en el JSON
        val episodePattern = Regex(""""episodes":\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val match = episodePattern.find(json) ?: return emptyList()
        
        val episodesJson = match.groupValues[1]
        
        // Extraer cada episodio individual
        val singleEpisodePattern = Regex("""\{[^}]*"title":"([^"]+)"[^}]*"url":"([^"]+)"[^}]*\}""")
        var index = 1
        
        singleEpisodePattern.findAll(episodesJson).forEach { episodeMatch ->
            val title = episodeMatch.groupValues[1]
            val url = episodeMatch.groupValues[2].replace("\\/", "/")
            
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = title
                episode_number = extractEpisodeNumber(title, url).takeIf { it > 0 } ?: index.toFloat()
            }
            episodes.add(episode)
            index++
        }
        
        return episodes
    }
    
    private fun parseEpisodesFromHtml(doc: Document): List<SEpisode> {
        // Múltiples selectores para máxima compatibilidad
        val selectors = listOf(
            "#seasons .se-c ul.episodios li",
            "#seasons .se-c .episodios li",
            ".se-c ul.episodios li",
            ".episodios li",
            "ul.episodios li",
            ".listing-episodes li",
            "[class*=episode] li a[href*=/episodio/]"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                return elements.mapNotNull { element ->
                    val link = element.selectFirst("a")
                    val url = link?.attr("abs:href") ?: element.attr("abs:href")
                    if (url.isBlank()) return@mapNotNull null
                    
                    val title = link?.text()?.trim()
                        ?: element.selectFirst(".title")?.text()?.trim()
                        ?: element.selectFirst(".episodiotitle")?.text()?.trim()
                        ?: element.text().trim()
                    
                    SEpisode.create().apply {
                        setUrlWithoutDomain(url)
                        name = title
                        episode_number = extractEpisodeNumber(title, url)
                    }
                }.reversed()
            }
        }
        
        return emptyList()
    }

    private fun extractEpisodeNumber(title: String, url: String): Float {
        val patterns = listOf(
            Regex("""Episodio\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""Cap(?:ítulo)?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:\.\d+)?)\s*-\s*\d+"""),
            Regex("""/(\d+(?:\.\d+)?)/?$"""),
            Regex("""[_-](\d+(?:\.\d+)?)$""")
        )
        
        for (pattern in patterns) {
            pattern.find(title)?.let { matchResult ->
                return matchResult.groupValues[1].toFloatOrNull() ?: -1f
            }
            pattern.find(url)?.let { matchResult ->
                return matchResult.groupValues[1].toFloatOrNull() ?: -1f
            }
        }
        
        return -1f
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking { player ->
            val name = player.selectFirst("span.title")!!.text()
            val url = getPlayerUrl(player)
            extractVideos(url, name)
        }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    private suspend fun extractVideos(url: String, lang: String): List<Video> {
        val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() || it.lowercase() in lang.lowercase() } }?.first
        return when (matched) {
            "saidochesto" -> extractFromMulti(url)
            "filemoon" -> filemoonExtractor.videosFromUrl(url, "$lang Filemoon:", headers)
            "doodstream" -> doodExtractor.videosFromUrl(url, "$lang DoodStream", false)
            "streamtape" -> streamTapeExtractor.videosFromUrl(url, "$lang StreamTape")
            "mixdrop" -> mixDropExtractor.videoFromUrl(url, prefix = "$lang ")
            "uqload" -> uqloadExtractor.videosFromUrl(url, prefix = lang)
            "wolfstream" -> {
                client.newCall(GET(url, headers)).awaitSuccess()
                    .useAsJsoup()
                    .selectFirst("script:containsData(sources)")
                    ?.data()
                    ?.let { jsData ->
                        val videoUrl = jsData.substringAfter("{file:\"").substringBefore("\"")
                        listOf(Video(videoUrl, "$lang WolfStream", videoUrl, headers = headers))
                    }
            }
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$lang ")
            "vidhide" -> vidHideExtractor.videosFromUrl(url) { "$lang - VidHide:$it" }
            "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" })
            else -> null
        } ?: emptyList()
    }

    private val conventions = listOf(
        "saidochesto" to listOf("saidochesto", "multiserver"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "bysekoze"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d", "dsvplay"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "mixdrop" to listOf("mixdrop", "mixdroop", "mxdrop"),
        "uqload" to listOf("uqload"),
        "wolfstream" to listOf("wolfstream"),
        "mp4upload" to listOf("mp4upload"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
    )

    private suspend fun extractFromMulti(url: String): List<Video> {
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val langSelector = when {
            prefLang.isBlank() -> "div"
            else -> "div.OD_$prefLang"
        }
        return document.select("div.ODDIV $langSelector > li").flatMap {
            val hosterUrl = it.attr("onclick").toString()
                .substringAfter("('")
                .substringBefore("')")
            val lang = when (langSelector) {
                "div" -> {
                    it.parent()?.attr("class").toString()
                        .substringAfter("OD_", "")
                        .substringBefore(" ")
                }
                else -> prefLang
            }
            extractVideos(hosterUrl, lang)
        }
    }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v1/post/$id?type=$type&source=$num", headers))
            .awaitSuccess()
            .bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String = select("$additionalInfoSelector div.wp-content p")
        .eachText()
        .joinToString("\n")

    override val additionalInfoItems = listOf("Título", "Temporadas", "Episodios", "Duración media")

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodio"

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = AnimeOnlineNinjaFilters.FILTER_LIST

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        val vrfIterceptPref = CheckBoxPreference(screen.context).apply {
            key = PREF_VRF_INTERCEPT_KEY
            title = PREF_VRF_INTERCEPT_TITLE
            summary = PREF_VRF_INTERCEPT_SUMMARY
            setDefaultValue(PREF_VRF_INTERCEPT_DEFAULT)
        }

        screen.addPreference(vrfIterceptPref)
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues
    override val episodeNumberRegex by lazy { Regex("""(\d+(?:\.\d+)?)$""") }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")
        private val SERVER_LIST = arrayOf("Filemoon", "DoodStream", "StreamTape", "MixDrop", "Uqload", "WolfStream", "saidochesto.top", "VidHide", "StreamWish", "Mp4Upload")

        private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
        private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
        private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
        private const val PREF_VRF_INTERCEPT_DEFAULT = false
    }
}
