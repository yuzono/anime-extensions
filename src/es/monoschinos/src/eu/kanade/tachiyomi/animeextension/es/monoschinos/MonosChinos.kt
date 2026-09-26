package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MonosChinos :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "MonosChinos"
    override val baseUrl = "https://monoschinos.st"
    override val id = 6957694006954649296
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf(
            "Voe",
            "StreamWish",
            "Okru",
            "Upload",
            "FileLions",
            "Filemoon",
            "DoodStream",
            "MixDrop",
            "Streamtape",
            "Mp4Upload",
            "LuluStream",
        )

        private val EPISODE_SLUG_REGEX = Regex("-episodio-(\\d+|[\\d.]+)$")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val ANIME_CARD_SELECTOR = "a.card-wrap[href*=/anime/]"
        private const val EPISODE_CARD_SELECTOR = "a.card-wrap[href*=/ver/]"
        private const val MAX_EPISODE_PAGES = 200
    }

    private fun Document.hasNextPage() = selectFirst("a[rel=next]") != null

    // ====================== POPULAR ======================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(ANIME_CARD_SELECTOR).mapNotNull { element ->
            SAnime.create().apply {
                title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, document.hasNextPage())
    }

    // ====================== ÚLTIMOS EPISODIOS ======================

    // The front page is the only listing of recent episodes and it does not
    // paginate, so every page but the first would repeat it.
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(EPISODE_CARD_SELECTOR).mapNotNull { element ->
            val episodeSlug = element.attr("abs:href").substringAfter("/ver/").substringBefore("?")
            SAnime.create().apply {
                title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
                setUrlWithoutDomain("/anime/${episodeSlug.replace(EPISODE_SLUG_REGEX, "")}-sub-espanol")
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, document.hasNextPage())
    }

    // ====================== BÚSQUEDA ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====================== DETALLE ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("h1 ~ p")?.text()
            // The page lists the genres twice, in the header and in the info tab.
            genre = document.select("a[href*=/genero/]").map { it.text() }.distinct().joinToString()
            thumbnail_url = document.selectFirst("img.lazy")?.getImageUrl()
            status = when {
                document.selectFirst("div:containsOwn(Finalizado)") != null -> SAnime.COMPLETED
                document.selectFirst("div:containsOwn(En emisión)") != null -> SAnime.ONGOING
                document.selectFirst("div:containsOwn(Estreno)") != null -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ====================== EPISODIOS ======================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val referer = document.location()

        val ajaxUrl = document.selectFirst("section.caplist")?.attr("data-ajax")?.let {
            if (it.startsWith("http")) it else baseUrl + it
        } ?: return emptyList()

        val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""

        fun ajaxPost(url: String, page: Int?): Request {
            val form = FormBody.Builder().add("_token", csrfToken)
            if (page != null) form.add("p", page.toString())
            return Request.Builder()
                .url(url)
                .post(form.build())
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()
        }

        // The old endpoint now always answers with an empty list and points at
        // the real one through `paginate_url`, which pages with `p`.
        val index = try {
            client.newCall(ajaxPost(ajaxUrl, null)).execute().parseAs<EpisodesDto>()
        } catch (_: Exception) {
            return emptyList()
        }
        val listUrl = index.paginateUrl ?: return emptyList()
        val perPage = index.perpage ?: 0

        val episodes = mutableListOf<SEpisode>()
        var currentPage = 1

        while (currentPage <= MAX_EPISODE_PAGES) {
            val caps = try {
                client.newCall(ajaxPost(listUrl, currentPage)).execute().parseAs<CapListDto>().caps
            } catch (_: Exception) {
                break
            }

            caps.forEach { cap ->
                val episodeNumber = cap.numStr.toFloatOrNull() ?: return@forEach
                episodes.add(
                    SEpisode.create().apply {
                        name = if (episodeNumber % 1 == 0f) {
                            "Episodio ${episodeNumber.toInt()}"
                        } else {
                            "Episodio ${cap.numStr}"
                        }
                        episode_number = episodeNumber
                        setUrlWithoutDomain(cap.url)
                    },
                )
            }

            if (perPage == 0 || caps.size < perPage) break
            currentPage++
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ====================== VIDEOS ======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverButtons = document.select("button.play-video[data-player]")
        return serverButtons.mapNotNull { button ->
            val encoded = button.attr("data-player")
            if (encoded.isBlank()) return@mapNotNull null
            val decodedUrl = try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null

            val serverName = button.attr("data-server").takeIf { it.isNotBlank() }
                ?: button.text().takeIf { it.isNotBlank() }
                ?: ""

            serverName to decodedUrl
        }.catchingFlatMapBlocking { (serverName, url) ->
            serverVideoResolver(url, serverName)
        }
    }

    // ====================== EXTRACTORES ======================

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "kswplayer", "swhoi", "multimovies", "uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d"),
        "mixdrop" to listOf("mixdrop"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "lulu" to listOf("luluvdo", "lulu", "lulustream"),
    )

    private suspend fun serverVideoResolver(url: String, serverName: String = ""): List<Video> {
        val source = url.lowercase()
        val serverKey = serverName.lowercase()

        var matched = conventions.firstOrNull { (key, _) -> key == serverKey }?.first

        if (matched == null) {
            matched = conventions.firstOrNull { (_, aliases) ->
                aliases.any { it in source }
            }?.first
        }

        val effectiveMatched = matched ?: when {
            serverKey.contains("dood") -> "doodstream"
            serverKey.contains("filemoon") -> "filemoon"
            serverKey.contains("lulu") -> "lulu"
            else -> null
        }

        return when (effectiveMatched) {
            "voe" -> voeExtractor.videosFromUrl(url)
            "okru" -> okruExtractor.videosFromUrl(url)
            "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            "uqload" -> uqloadExtractor.videosFromUrl(url)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
            "streamwish" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            "doodstream" -> doodExtractor.videosFromUrl(url, "DoodStream:")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url)
            "streamtape" -> streamTapeExtractor.videosFromUrl(url)
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "LuluStream:")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    // ====================== ORDEN ======================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy<Video>(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ).reversed(),
        )
    }

    // ====================== AUXILIARES ======================

    private fun Element.getImageUrl(): String? = when {
        isValidUrl("data-src") -> attr("abs:data-src")
        isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
        isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
        isValidUrl("src") -> attr("abs:src")
        else -> null
    }

    private fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        val url = attr(attrName)
        return url.isNotBlank() && !url.contains("anime.png")
    }

    // ====================== FILTROS ======================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // ====================== PREFERENCIAS ======================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
