package eu.kanade.tachiyomi.animeextension.fr.voiranime

import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Voiranime : ParsedAnimeHttpSource() {

    override val name = "Voiranime"

    override val baseUrl = "https://voir-anime.to"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    private val universalExtractor by lazy { UniversalExtractor(client) }

    // Without a Referer: UniversalExtractor adds one itself, and a duplicate Referer
    // makes some CDNs (e.g. yourupload's) reject the video request with a 500.
    private val extractorHeaders by lazy { headers.newBuilder().removeAll("Referer").build() }

    // ─── Popular ─────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=trending", headers)

    override fun popularAnimeSelector(): String = "div.c-tabs-item__content"

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a.nextpostslink"

    // ─── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=new-manga", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/anime/]") ?: element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val img = element.selectFirst("img")
        title = link.attr("title")
            .ifBlank { img?.attr("alt").orEmpty() }
            .ifBlank { element.selectFirst(".post-title")?.text().orEmpty() }
            .ifBlank { link.text() }
            .trim()
        thumbnail_url = img?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        // Info table rows: ".post-content_item" with a ".summary-heading" label and ".summary-content" value
        // (e.g. Status => "EN COURS", Studios => "Toei Animation").
        val info = document.select(".post-content_item").associate { item ->
            item.selectFirst(".summary-heading")?.text()?.trim()?.lowercase().orEmpty() to
                item.selectFirst(".summary-content")?.text()?.trim().orEmpty()
        }

        title = document.selectFirst(".post-title h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst(".summary_image img")
            ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        description = document.selectFirst(".description-summary .summary__content, .manga-excerpt")
            ?.text()?.trim()
        genre = document.select(".genres-content a").joinToString { it.text() }.ifBlank { null }
        author = info["studios"]?.takeIf { it.isNotBlank() }
        status = when (info["status"]?.lowercase()?.trim()) {
            "en cours" -> SAnime.ONGOING
            "terminé", "termine", "complété", "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ─── Episodes ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request {
        // Madara serves the chapter list through an XHR endpoint that requires this header.
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        return POST("$baseUrl${anime.url}ajax/chapters/", ajaxHeaders)
    }

    override fun episodeListSelector(): String = "li.wp-manga-chapter"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("a")!!
        val href = link.attr("abs:href")
        setUrlWithoutDomain(href)
        val text = link.text()
        // The slug carries the episode number reliably (e.g. ".../one-piece-1167-vostfr/"),
        // unlike the visible text which may also contain numbers from the title.
        val slug = href.trimEnd('/').substringAfterLast('/')
        val num = EP_NUM_REGEX.find(slug)?.groupValues?.get(1)
            ?: NUMBER_REGEX.findAll(slug).lastOrNull()?.value
            ?: NUMBER_REGEX.find(text)?.value
        val subType = when {
            slug.contains("vostfr", ignoreCase = true) || text.contains("VOSTFR", ignoreCase = true) -> "VOSTFR"
            slug.contains("-vf", ignoreCase = true) || text.contains("VF", ignoreCase = true) -> "VF"
            else -> ""
        }
        name = listOfNotNull("Épisode", num, subType.ifBlank { null }).joinToString(" ").trim()
        episode_number = num?.replace(',', '.')?.toFloatOrNull() ?: 0f
        date_upload = element.selectFirst(".chapter-release-date i, span.chapter-release-date")
            ?.text().parseDate()
    }

    // ─── Videos ───────────────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = baseUrl + episode.url
        val document = client.newCall(GET(episodeUrl, headers)).awaitSuccess().useAsJsoup()

        // All servers and their iframes are embedded in a single inline script object.
        val sources = document.selectFirst("script:containsData(thisChapterSources)")
            ?.data()
            ?.substringAfter("thisChapterSources = ")
            ?.substringBefore(";")
            ?.let { runCatching { it.parseAs<Map<String, String>>() }.getOrNull() }

        if (sources.isNullOrEmpty()) return emptyList()

        return sources.entries.parallelCatchingFlatMap { (serverName, iframeHtml) ->
            val iframe = IFRAME_SRC_REGEX.find(iframeHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMap emptyList()

            val prefix = serverName.removePrefix("LECTEUR").trim()
            universalExtractor.videosFromUrl(iframe, extractorHeaders, prefix = "$prefix - ")
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun String?.parseDate(): Long {
        this ?: return 0L
        return runCatching { DATE_FORMAT.parse(trim())?.time ?: 0L }.getOrDefault(0L)
    }

    companion object {
        private val NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
        private val EP_NUM_REGEX = Regex("""-(\d+(?:[.,]\d+)?)-(?:vostfr|vf)""", RegexOption.IGNORE_CASE)
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""")
        private val DATE_FORMAT by lazy {
            java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.FRENCH)
        }
    }
}
