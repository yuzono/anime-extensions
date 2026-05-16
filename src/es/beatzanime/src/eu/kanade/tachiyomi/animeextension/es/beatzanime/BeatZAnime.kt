package eu.kanade.tachiyomi.animeextension.es.beatzanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BeatZAnime : ParsedAnimeHttpSource() {

    override val name = "BeatZ Anime"

    override val baseUrl = "https://www.beatz-anime.net"

    private val ddlHost = "bz.beatz-anime.net"

    override val lang = "es"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/emision/pagina=$page"
        } else {
            "$baseUrl/emision/"
        }

        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".row > div:has(a.titulo-largo)"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
        with(element.selectFirst("a.titulo-largo")!!) {
            setUrlWithoutDomain(attr("abs:href"))
            title = text()
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active + li:not(.disabled)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/index.php?pagina=$page"
        } else {
            "$baseUrl/"
        }

        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val source = filters.filterIsInstance<SourceFilter>().first().getValue()
        val status = filters.filterIsInstance<StatusFilter>().first().getValue()
        val type = filters.filterIsInstance<TypeFilter>().first().getValue()

        val url = "$baseUrl/lista-animes/index.php"

        val formBody = FormBody.Builder().apply {
            add("buscar", query)
            add("fuente", source)
            add("estado", status)
            add("tipo-anime", type)
        }.build()

        val formHeaders = headersBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", baseUrl.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", url)
        }.build()

        return POST(url, formHeaders, formBody)
    }

    override fun searchAnimeSelector(): String = ".row > div:has(span.titulo)"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
        with(element.selectFirst("a:has(span)")!!) {
            setUrlWithoutDomain(attr("abs:href"))
            title = text()
        }
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SourceFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst(".row > div > img")?.imgAttr()
        genre = document.selectFirst("p.post-text span:has(b:contains(Generos))")?.ownText()
        status = document.selectFirst("div:has(>h5:contains(Estado)) a").parseStatus()
        description = buildString {
            document.selectFirst("p.post-text")?.textNodes()?.let {
                append(it.joinToString("\n\n") { it.text() })
            }
            append("\n\n")
            document.selectFirst("p.post-text span:has(b:contains(Sinónimos))")?.let {
                append("Sinónimos: ")
                append(it.ownText())
            }
        }.trim()
    }

    private fun Element?.parseStatus(): Int = when (this?.text()?.lowercase()) {
        "finalizado" -> SAnime.COMPLETED
        "en emisión", "en emsión" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return document.select("table tr:has(a[href*=$ddlHost/d/])")
            .filter { row ->
                row.select("td").getOrNull(2)?.text()?.equals("Video", ignoreCase = true) == true
            }
            .map { row ->
                val cells = row.select("td")
                val name = cells[0].text().trim()
                val size = cells[3].text().trim()
                val downloadUrl = cells[4].selectFirst("a")!!.attr("abs:href")

                SEpisode.create().apply {
                    this.name = name
                    url = downloadUrl
                    scanlator = size
                }
            }
            .reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url

        val videoHeaders = headersBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.5,*/*;q=0.4")
            add("Referer", baseUrl)
        }.build()

        return listOf(Video(url, "Video", url, videoHeaders))
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
