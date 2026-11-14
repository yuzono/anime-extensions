package eu.kanade.tachiyomi.animeextension.fr.voiranime

import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.get
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Voiranime : AnimeHttpSource() {

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val extractorHeaders by lazy { headers.newBuilder().removeAll("Referer").build() }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=trending", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = catalogueParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s&post_type=wp-manga&m_orderby=new-manga", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogueParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = catalogueParse(response)

    private fun catalogueParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select("div.c-tabs-item__content").map(::animeFromElement)
        return AnimesPage(anime, document.selectFirst("a.nextpostslink") != null)
    }

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

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.get("$baseUrl${anime.url}", headers).useAsJsoup()
        return detailsFromDocument(document)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    private fun detailsFromDocument(document: Document): SAnime = SAnime.create().apply {
        // Info table rows: ".post-content_item" with a ".summary-heading" label and ".summary-content" value
        // (e.g. Status => "EN COURS", Studios => "Toei Animation").
        val info = document.select(".post-content_item").associate { item ->
            item.selectFirst(".summary-heading")?.text()?.lowercase().orEmpty() to
                item.selectFirst(".summary-content")?.text().orEmpty()
        }

        title = document.selectFirst(".post-title h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst(".summary_image img")
            ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        description = document.selectFirst(".description-summary .summary__content, .manga-excerpt")
            ?.text()
        genre = document.select(".genres-content a").joinToString { it.text() }.ifBlank { null }
        author = info["studios"]?.takeIf { it.isNotBlank() }
        status = when (info["status"]?.lowercase()?.trim()) {
            "en cours" -> SAnime.ONGOING
            "terminé", "termine", "complété", "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // Madara serves the chapter list through an XHR endpoint that requires this header.
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val response = client.post("$baseUrl${anime.url}ajax/chapters/", ajaxHeaders)
        return response.useAsJsoup().let(::episodesFromDocument)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    private fun episodesFromDocument(document: Document): List<SEpisode> = document.select("li.wp-manga-chapter").map { element ->
        val link = element.selectFirst("a")!!
        val href = link.attr("abs:href")
        val text = link.text()
        // The slug carries the episode number reliably (e.g. ".../one-piece-0001-vostfr/"),
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
        // The site zero-pads slugs ("0001"); strip the padding for display and parsing.
        val cleanNum = num?.trimStart('0')?.ifEmpty { "0" }
        SEpisode.create().apply {
            name = listOfNotNull("Épisode", cleanNum, subType.ifBlank { null }).joinToString(" ").trim()
            episode_number = cleanNum?.replace(',', '.')?.toFloatOrNull() ?: 0f
            date_upload = element.selectFirst(".chapter-release-date i, span.chapter-release-date")
                ?.text().parseDate()
            setUrlWithoutDomain(href)
        }
    }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get("$baseUrl${episode.url}", headers).useAsJsoup()
        return document.select("select.host-select option").mapNotNull { option ->
            val redirect = option.attr("data-redirect").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = option.attr("value").removePrefix("LECTEUR ").trim()
                .ifBlank { option.text().removePrefix("LECTEUR ").trim() }
            Hoster(hosterName = name, internalData = redirect)
        }.distinctBy { it.internalData }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ========================= Video Extraction ===========================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val pageUrl = "$baseUrl${hoster.internalData}"
        val iframe = client.get(pageUrl, headers).useAsJsoup()
            .selectFirst("iframe")?.attr("abs:src") ?: return emptyList()
        val name = hoster.hosterName

        return when {
            name.contains("MOON", true) -> filemoonExtractor.videosFromUrl(iframe, prefix = "$name - Filemoon - ", referer = pageUrl)
            name.contains("VOE", true) -> voeExtractor.videosFromUrl(iframe, prefix = name)
            name.contains("Stape", true) -> streamtapeExtractor.videosFromUrl(iframe, "$name - Streamtape")
            name.contains("SB", true) -> vidhideExtractor.videosFromUrl(iframe) { quality -> "$name - VidHide - $quality" }
            name.contains("myTV", true) -> vidmolyExtractor.videosFromUrl(iframe, prefix = "$name -")
            name.contains("YU", true) -> yourUploadExtractor.videoFromUrl(iframe, extractorHeaders, prefix = "$name - ")
            else -> universalExtractor.videosFromUrl(iframe, extractorHeaders, prefix = name)
        }
    }

    // =============================== Utils ================================
    private fun String?.parseDate(): Long {
        this ?: return 0L
        val trimmed = trim()
        return runCatching { DATE_FORMAT_EN.parse(trimmed)?.time ?: 0L }
            .recoverCatching { DATE_FORMAT_FR.parse(trimmed)?.time ?: 0L }
            .getOrDefault(0L)
    }

    companion object {
        private val NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
        private val EP_NUM_REGEX = Regex("""-(\d+(?:[.,]\d+)?)-(?:vostfr|vf)""", RegexOption.IGNORE_CASE)
        private val DATE_FORMAT_EN by lazy {
            SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
        }
        private val DATE_FORMAT_FR by lazy {
            SimpleDateFormat("dd MMMM yyyy", Locale.FRENCH)
        }
    }
}
