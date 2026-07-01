package eu.kanade.tachiyomi.animeextension.es.beatzanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.Normalizer

class BeatZAnime : ParsedAnimeHttpSource() {

    override val name = "BeatZ Anime"

    override val baseUrl = "https://www.beatz-anime.net"

    override val lang = "es"

    override val supportsLatest = true

    // ============================== Popular ===============================
    // "Top 7 most viewed" marquee on the home page.
    // The marquee duplicates every card for CSS infinite-scroll animation,
    // so popularAnimeParse() deduplicates by href.

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeSelector(): String = "article.top-views-card"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("a.top-views-poster")!!
        setUrlWithoutDomain(anchor.attr("href"))
        thumbnail_url = anchor.selectFirst("img")?.attr("abs:src")
        title = element.selectFirst("h3.top-views-title")!!.text()
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
            .select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .distinctBy { it.url }
        return AnimesPage(animes, hasNextPage = false)
    }

    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/index.php?pagina=$page" else "$baseUrl/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = ".row > div:has(a.titulo-largo)"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
        with(element.selectFirst("a.titulo-largo")!!) {
            setUrlWithoutDomain(attr("abs:href"))
            title = text()
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination > li.active + li:not(.disabled)"

    // =============================== Search ===============================

    // The /lista-animes/ page returns all cards in one response. Filtering is
    // applied entirely client-side by JS via data-* attributes on each card.
    // The server ignores any query parameters. We mirror the same logic here.
    //
    // To avoid relying on mutable instance fields (which would break under
    // concurrent source calls), the filter state is encoded into the Request
    // tag as a SearchParams data class and recovered inside searchAnimeParse().
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SearchParams(
            query = query,
            fuente = filters.filterIsInstance<SourceFilter>().firstOrNull()?.getValue() ?: "",
            estado = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getValue() ?: "",
            tipo = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getValue() ?: "",
        )
        return GET("$baseUrl/lista-animes/", headers).newBuilder()
            .tag(SearchParams::class.java, params)
            .build()
    }

    override fun searchAnimeSelector(): String = "div.anime-card"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val anchor = element.selectFirst("a.anime-poster-link")!!
        setUrlWithoutDomain(anchor.attr("href"))
        thumbnail_url = anchor.selectFirst("img.anime-poster")?.attr("abs:src")
        title = element.selectFirst("span.overlay-title-link")?.text()
            ?: anchor.attr("title")
    }

    /**
     * Client-side filtering that mirrors the JS on /lista-animes/.
     *
     * Each div.anime-card carries:
     *   data-name   — lowercase, accent-stripped title
     *   data-fuente — "bdrip" or "webrip"
     *   data-estado — "finalizado" or "en emisión"
     *   data-tipo   — "serie" or "pelicula"
     *
     * Both the filter values and the data attributes are accent-normalised
     * before comparison so that e.g. "En Emision" matches "en emisión".
     * Filter state travels from searchAnimeRequest via the Request tag to
     * avoid any concurrency issues with mutable instance fields.
     */
    override fun searchAnimeParse(response: Response): AnimesPage {
        val params = response.request.tag(SearchParams::class.java) ?: SearchParams()
        val document = response.useAsJsoup()
        val query = params.query.normalizeAccents()
        val fuente = params.fuente.normalizeAccents()
        val estado = params.estado.normalizeAccents()
        val tipo = params.tipo.normalizeAccents()

        val animes = document.select(searchAnimeSelector()).mapNotNull { el ->
            val matchName = query.isEmpty() ||
                el.attr("data-name").normalizeAccents().contains(query)
            val matchFuente = fuente.isEmpty() ||
                el.attr("data-fuente").normalizeAccents() == fuente
            val matchEstado = estado.isEmpty() ||
                el.attr("data-estado").normalizeAccents() == estado
            val matchTipo = tipo.isEmpty() ||
                el.attr("data-tipo").normalizeAccents() == tipo

            if (matchName && matchFuente && matchEstado && matchTipo) {
                searchAnimeFromElement(el)
            } else {
                null
            }
        }
        return AnimesPage(animes, hasNextPage = false)
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
            document.selectFirst("p.post-text")?.textNodes()?.let { node ->
                append(node.joinToString("\n\n") { it.text() })
            }
            append("\n\n")
            document.selectFirst("p.post-text span:has(b:contains(Sinónimos))")?.let { span: Element ->
                append("Sinónimos: ")
                append(span.ownText())
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

    /**
     * Parses the file table embedded in the anime page (#collapseExampled tbody tr).
     *
     * Each row has six cells:
     *   [0] display name | [1] format | [2] type label | [3] size
     *   [4] download anchor (a.btn-descarga-premium) | [5] copy button
     *
     * Only rows whose type cell reads "Video", or whose format is a known
     * playable extension (mkv / mp4), are included. Non-playable files such
     * as checksum archives are skipped — ExoPlayer cannot open them.
     *
     * The full absolute download URL is stored in episode.url so that
     * getVideoList() can return a Video object without an extra HTTP call.
     */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val rows = document.select("#collapseExampled tbody tr")
        if (rows.isEmpty()) return emptyList()

        val episodes = mutableListOf<SEpisode>()

        rows.forEachIndexed { index, row ->
            val cells = row.select("td")
            if (cells.size < 5) return@forEachIndexed

            val format = cells[1].text().lowercase()
            val typeLabel = cells[2].text().lowercase()

            val isPlayable = typeLabel == "video" || PLAYABLE_FORMATS.any { format == it }
            if (!isPlayable) return@forEachIndexed

            val fileUrl = cells[4].selectFirst("a.btn-descarga-premium")
                ?.attr("abs:href")
                ?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed

            // text() is more robust than ownText() for cells whose content is
            // a plain whitespace-padded text node with no child elements.
            val rawName = cells[0].text()
                .takeIf { it.isNotBlank() }
                ?: URLDecoder.decode(
                    fileUrl.substringAfterLast("/").substringBeforeLast("."),
                    "UTF-8",
                ).trim()

            // Try SxxExx first (e.g. "S01E22"), then bare Exx (e.g. "E01"),
            // then a trailing integer (e.g. "Episode 5"), then fall back to
            // the row index so every entry always has a valid episode number.
            val epNumber = EPISODE_SXX_EXX_REGEX.find(rawName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: EPISODE_EXX_REGEX.find(rawName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: EPISODE_TRAILING_INT_REGEX.find(rawName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: (index + 1).toFloat()

            episodes.add(
                SEpisode.create().apply {
                    name = rawName
                    url = fileUrl
                    episode_number = epNumber
                    scanlator = cells[3].text()
                },
            )
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================

    /**
     * episode.url is the full absolute direct-download URL stored during
     * episodeListParse(). No extra HTTP call is required — wrap it in a Video.
     *
     * The Host header is intentionally omitted: OkHttp derives it from the URL
     * automatically, and setting it manually can cause it to appear twice,
     * breaking the request on some CDN configurations.
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val fileUrl = episode.url
        val qualityLabel = RESOLUTION_REGEX.find(episode.name)?.value
            ?: fileUrl.substringAfterLast(".").uppercase()

        val videoHeaders = headersBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            add("Referer", "$baseUrl/")
        }.build()

        return listOf(Video(fileUrl, qualityLabel, fileUrl, videoHeaders))
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

    /**
     * Lowercases and strips combining diacritical marks so that filter values
     * like "En Emision" match HTML data attributes like "en emisión".
     * Mirrors the normalize() helper used by the page's own client-side JS.
     */
    private fun String.normalizeAccents(): String {
        val nfd = Normalizer.normalize(this.lowercase().trim(), Normalizer.Form.NFD)
        return nfd.replace(ACCENTS_REGEX, "")
    }

    // ========================= Data classes / companion ===================

    /** Carries search filter state through the Request tag to avoid mutable instance fields. */
    private data class SearchParams(
        val query: String = "",
        val fuente: String = "",
        val estado: String = "",
        val tipo: String = "",
    )

    companion object {
        private val PLAYABLE_FORMATS = setOf("mp4", "mkv")

        // Episode number extraction — tried in order of specificity.

        /** Matches SxxExx patterns, e.g. "S01E22" → captures "22". */
        private val EPISODE_SXX_EXX_REGEX = Regex("""[Ss]\d+[Ee](\d+)""")

        /** Matches bare Exx patterns, e.g. "E01" → captures "1". */
        private val EPISODE_EXX_REGEX = Regex("""(?<![Ss]\d{1,4})[Ee](\d+)""")

        /** Matches a trailing integer in the name, e.g. "Episode 5" → captures "5". */
        private val EPISODE_TRAILING_INT_REGEX = Regex("""(\d+)\s*$""")

        private val RESOLUTION_REGEX = Regex("""\d{3,4}p""", RegexOption.IGNORE_CASE)

        /** Pre-compiled for reuse in normalizeAccents(); avoids per-call Regex construction. */
        private val ACCENTS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    }
}
