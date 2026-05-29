package eu.kanade.tachiyomi.animeextension.es.verpelistop

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.flatMapCatching
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer

class VerPelisTop :
    DooPlay(
        "es",
        "VerPelisTop",
        "https://www1.verpelis.top",
    ) {

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "#featured-titles article > div.poster"

    override fun popularAnimeNextPageSelector() = null

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/online/page/$page")

    override fun latestUpdatesSelector() = "#archive-content article > div.poster"

    override fun latestUpdatesNextPageSelector() = "#nextpagination"

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"

    override val prefQualityTitle = "Calidad preferida"

    override fun Document.getDescription(): String = Jsoup.parse(select("$additionalInfoSelector p").joinToString(" ") { it.text() }).text().trim()

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        val genres = GENRE_LIST.map { it.first }
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }
            genre = sheader.select("div.data > div.sgeneros > a")
                .map { it.text().lowercase().normalize() }
                .filter { genres.contains(it) }
                .joinToString()
            description = Jsoup.parse(doc.select("$additionalInfoSelector p").joinToString(" ") { it.text() }).text().trim()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.catchingFlatMapBlocking { player ->
            serverVideoResolver(player)
        }
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val hexloadExtractor by lazy { HexloadExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun serverVideoResolver(player: Element): List<Video> {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        val iframeSource = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .awaitSuccess().bodyString()
            .substringAfter("src='")
            .substringBefore("'")
            .replace("\\", "").ifEmpty { return emptyList() }

        val frameDoc = client.newCall(GET(iframeSource)).awaitSuccess().useAsJsoup()

        return frameDoc.select(".OD li[onclick]")
            .map { hoster ->
                val server = hoster.select("span").text()
                val lang = hoster.select("p").text().substringBefore("-").trim()
                val url = hoster.attr("onclick").substringAfter("('").substringBefore("')")
                val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
                Pair(url, matched) to Pair(lang, server)
            }
            .partition { it.first.second != null }
            .let { (matched, unmatched) ->
                val extractors = matched.parallelCatchingFlatMap { (hoster, info) ->
                    val (url, matched) = hoster
                    val (lang, _) = info
                    when (matched) {
                        "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$lang - StreamTape")
                        "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$lang - FileMoon: ")
                        "hexload" -> hexloadExtractor.videosFromUrl(url, "$lang - HexLoad")
                        "uqload" -> uqloadExtractor.videosFromUrl(url, "$lang -")
                        "streamwish" -> streamWishExtractor.videosFromUrl(url, "$lang - StreamWish")
                        "vidhide" -> {
                            // Redirecting URLs
                            val redirectUrl = url.redirectHgCloudHgLink()
                                .replace("dintezuvio", "callistanise")
                            vidHideExtractor.videosFromUrl(redirectUrl, videoNameGen = { "$lang - VidHide: $it" })
                        }
                        else -> emptyList()
                    }
                }
                val universal = unmatched.flatMapCatching { (hoster, info) ->
                    val (url, _) = hoster
                    val (lang, server) = info
                    universalExtractor.videosFromUrl(url, headers, prefix = "$lang $server")
                }
                extractors + universal
            }
    }

    private val conventions = listOf(
        "uqload" to listOf("uqload"),
        "hexload" to listOf("hexload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "bysezoxexe"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet", "earn", "hgcloud", "hglink", "minochinos", "movearnpre", "dintezuvio"),
    )

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro de género"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter("Géneros", GENRE_LIST)

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.filterIsInstance<GenreFilter>().firstOrNull()

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query", headers)
            genreFilter?.let { it.state != 0 } == true -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_ENTRIES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private val hgCloudLinkDomains by lazy { listOf("hgcloud.to", "hglink.to") }
    private val hgCloudLinkRedirected by lazy {
        listOf(
            "hanerix.com",
            "vibuxer.com",
            "audinifer.com",
            "masukestin.com",
        )
    }

    private fun String.redirectHgCloudHgLink(): String = hgCloudLinkDomains.firstOrNull(::contains)?.let {
        val redirectingDomain = hgCloudLinkRedirected.random()
        replace(it, redirectingDomain)
    } ?: this

    override fun String.toDate() = 0L

    private fun String.normalize(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang, true) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Idioma preferido"
        private const val PREF_LANG_DEFAULT = "Latino"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Latino", "Castellano")
        private const val PREF_SERVER_TITLE = "Servidor preferido"
        private const val PREF_SERVER_KEY = "preferred_server"
        private val SERVER_LIST = arrayOf("VidHide", "StreamTape", "Uqload", "HexLoad", "StreamWish", "FileMoon")
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()

        val GENRE_LIST = arrayOf(
            Pair("<seleccionar>", ""),
            Pair("accion", "genero/accion"),
            Pair("amazon prime", "genero/amazon-prime"),
            Pair("animacion", "genero/animacion"),
            Pair("aventura", "genero/aventura"),
            Pair("biografia", "genero/biografia"),
            Pair("ciencia ficcion", "genero/ciencia-ficcion"),
            Pair("comedia", "genero/comedia"),
            Pair("corto", "genero/corto"),
            Pair("crimen", "genero/crimen"),
            Pair("deporte", "genero/deporte"),
            Pair("disney", "genero/disney"),
            Pair("documentales", "genero/documentales"),
            Pair("drama", "genero/drama"),
            Pair("familia", "genero/familia"),
            Pair("fantasia", "genero/fantasia"),
            Pair("hbo", "genero/hbo"),
            Pair("historia", "genero/historia"),
            Pair("horror", "genero/horror"),
            Pair("marvel", "genero/marvel"),
            Pair("misterio", "genero/misterio"),
            Pair("musica", "genero/musica"),
            Pair("netflix", "genero/netflix"),
            Pair("reality", "genero/reality"),
            Pair("romance", "genero/romance"),
            Pair("suspenso", "genero/suspenso"),
            Pair("terror", "genero/terror"),
            Pair("thriller", "genero/thriller"),
        )
    }
}
