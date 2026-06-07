package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Element

class MonosChinos :
    AnimeHttpSource(),
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
        )
    }

    // ====================== POPULAR / BÚSQUEDA ======================

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/animes?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("li.ficha_efecto a")
        val nextPage = document.selectFirst(".pagination a:has(span:containsOwn(»))") != null
        val animeList = elements.mapNotNull { element ->
            SAnime.create().apply {
                title = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    // ====================== ÚLTIMOS EPISODIOS (RECIENTES) ======================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val episodeItems = document.select("ul.row.row-cols-xl-4.row-cols-lg-4.row-cols-md-3.row-cols-2 > li.col.mb-4")
        val animeList = episodeItems.mapNotNull { item ->
            val episodeLink = item.selectFirst("a") ?: return@mapNotNull null
            val episodeUrl = episodeLink.attr("abs:href")

            val episodeSlug = episodeUrl.substringAfter("/ver/").substringBefore("?")
            val animeSlugBase = episodeSlug.replace(Regex("-episodio-\\d+$"), "")
            val animeUrl = "/anime/${animeSlugBase}-sub-espanol"

            val animeTitle = item.selectFirst("h2.fs-5")?.text()?.trim() ?: return@mapNotNull null
            val episodeNumber = item.selectFirst("span.episode")?.text()?.trim() ?: ""
            val genre = item.selectFirst("span.text-muted")?.text()?.trim() ?: ""

            SAnime.create().apply {
                title = "$animeTitle - Episodio $episodeNumber"
                setUrlWithoutDomain(animeUrl)
                description = genre
                thumbnail_url = item.selectFirst("img.lazy")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, false)
    }

    // ====================== BÚSQUEDA ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = MonosChinosFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====================== DETALLE DEL ANIME ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst("h1.fs-2.text-capitalize.text-light")?.text()?.trim() ?: ""
            description = document.selectFirst("#profile-tab-pane .mb-3 p")?.text()?.trim()
            genre = document.select("#profile-tab-pane .badge.bg-secondary").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".d-none.d-sm-flex img.lazy")?.getImageUrl()
            status = run {
                val estadoElement = document.selectFirst(".col:has(.text-muted:contains(Estado)) div.ms-2 div:last-child")
                when (estadoElement?.text()?.trim()) {
                    "Estreno", "En emisión" -> SAnime.ONGOING
                    "Finalizado" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return animeDetails
    }

    // ====================== LISTA DE EPISODIOS ======================

override fun episodeListParse(response: Response): List<SEpisode> {
    val document = response.asJsoup()
    val referer = document.location()

    val ajaxUrl = document.selectFirst("section.caplist")?.attr("data-ajax")?.let {
        if (it.startsWith("http")) it else baseUrl + it
    } ?: return emptyList()

    val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""

    val episodeSlug = document.selectFirst("a[href^='/ver/']")?.attr("href")
        ?.substringAfter("/ver/")
        ?.substringBefore("-episodio-")
        ?: run {
            val animeSlug = referer.substringAfter("/anime/").substringBefore("?").substringBefore("#")
            animeSlug.replace(Regex("-sub-espanol$"), "")
        }
    if (episodeSlug.isBlank()) return emptyList()

    val episodes = mutableListOf<SEpisode>()
    var currentPage = 1
    var hasMore = true
    val maxPages = 200

    while (hasMore && currentPage <= maxPages) {
        val paginatedUrl = if (currentPage == 1) {
            ajaxUrl
        } else {
            val separator = if (ajaxUrl.contains("?")) "&" else "?"
            "$ajaxUrl${separator}page=$currentPage"
        }

        val formBody = FormBody.Builder()
            .add("_token", csrfToken)
            .build()

        val request = Request.Builder()
            .url(paginatedUrl)
            .post(formBody)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { it.body?.string() ?: "" }
        } catch (_: Exception) {
            break
        }

        if (responseBody.isBlank()) break

        val json = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            break
        }

        val epsArray = try {
            json.getJSONArray("eps")
        } catch (_: Exception) {
            break
        }

        val perpage = json.optInt("perpage", 0)

        for (i in 0 until epsArray.length()) {
            val obj = epsArray.getJSONObject(i)
            val numStr = obj.optString("num", "")
            if (numStr.isBlank()) continue

            val episodeNumber = runCatching { numStr.toFloat() }.getOrNull() ?: continue

            val urlNumber = if (episodeNumber % 1 == 0f) {
                episodeNumber.toInt().toString()
            } else {
                numStr
            }

            episodes.add(SEpisode.create().apply {
                name = if (episodeNumber % 1 == 0f) {
                    "Episodio ${episodeNumber.toInt()}"
                } else {
                    "Episodio $numStr"
                }
                episode_number = episodeNumber
                setUrlWithoutDomain("/ver/$episodeSlug-episodio-$urlNumber")
            })
        }

        if (perpage == 0 || epsArray.length() < perpage) {
            hasMore = false
        } else {
            Thread.sleep(100)
            currentPage++
        }
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
            decodedUrl
        }.catchingFlatMapBlocking { serverVideoResolver(it) }
    }

    // ====================== EXTRACTORES DE VIDEO ======================

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

  private suspend fun serverVideoResolver(url: String): List<Video> {
    val embedUrl = url.lowercase()
    return when {
        embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url)
        embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
        embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
        embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") ||
        embedUrl.contains("moviesm4u") || embedUrl.contains("files.im") ->
            filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
        embedUrl.contains("doodstream.com") || embedUrl.contains("dood.") ->
                doodExtractor.videosFromUrl(url)
        embedUrl.contains("streamtape.com") || embedUrl.contains("stape") ->
            streamTapeExtractor.videosFromUrl(url)
        embedUrl.contains("mp4upload.com") -> mp4uploadExtractor.videosFromUrl(url, headers)
        embedUrl.contains("mixdrop") -> mixdropExtractor.videosFromUrl(url)
        embedUrl.contains("wishembed") || embedUrl.contains("streamwish") ||
        embedUrl.contains("strwish") || embedUrl.contains("wish") ||
        embedUrl.contains("kswplayer") || embedUrl.contains("swhoi") ||
        embedUrl.contains("multimovies") || embedUrl.contains("uqloads") ||
        embedUrl.contains("neko-stream") || embedUrl.contains("swdyu") ||
        embedUrl.contains("iplayerhls") || embedUrl.contains("streamgg") ->
            streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
        else -> universalExtractor.videosFromUrl(url, headers)
    }
}
    // ====================== ORDENAR VIDEOS ======================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ====================== FUNCIONES AUXILIARES ======================

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

    override fun getFilterList(): AnimeFilterList = MonosChinosFilters.FILTER_LIST

    // ====================== PREFERENCIAS ======================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
}
