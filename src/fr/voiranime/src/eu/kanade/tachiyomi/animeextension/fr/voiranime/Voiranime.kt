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

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")

    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ─── Popular ─────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=trending", headers)

    override fun popularAnimeSelector(): String = "div.c-tabs-item__content"

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a.nextpostslink"

    // ─── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=latest", headers)

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

        // Server list — the host-select options are duplicated for desktop/mobile, so dedupe.
        val servers = document.select("select.host-select option")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()

        if (servers.isEmpty()) return emptyList()

        return servers.parallelCatchingFlatMap { serverName ->
            val hostUrl = episodeUrl.toHttpUrl().newBuilder()
                .addQueryParameter("host", serverName)
                .build()
                .toString()

            val hostDoc = client.newCall(GET(hostUrl, headers)).awaitSuccess().useAsJsoup()
            val iframe = hostDoc.selectFirst(
                ".reading-content iframe, .text-left iframe, .entry-content iframe",
            )?.let { it.absUrl("src").ifEmpty { it.absUrl("data-src") } }
                ?.takeIf { it.isNotBlank() }
                ?: return@parallelCatchingFlatMap emptyList()

            val prefix = serverName.removePrefix("LECTEUR").trim()
            universalExtractor.videosFromUrl(iframe, headers, prefix = "$prefix - ")
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
        private val DATE_FORMAT by lazy {
            java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.FRENCH)
        }
    }
}
