package eu.kanade.tachiyomi.animeextension.es.otakustv

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OtakusTV : ParsedAnimeHttpSource() {

    override val name = "OtakusTV"

    override val baseUrl = "https://www.otakustv.net"

    override val lang = "es"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // ─── Extractors ─────────────────────────────────────────────────────────────

    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val serverExtractor by lazy { OtakusTvServerExtractor() }

    // ─── Popular ─────────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?pag=$page", headers)

    override fun popularAnimeSelector(): String = "article.li"

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "ul.pag a:contains(Siguiente)"

    // ─── Latest ────────────────────────────────────────────────────────────────

    // "Latest" surfaces titles with freshly released episodes — the homepage "Nuevos episodios"
    // grid. Each card links to an episode (/ver/{slug}-{num}); we map it back to its anime.
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesSelector(): String = "article.li:has(a[href*=/ver/])"

    // The same anime can have several freshly released episodes, each rendered as its own card.
    // Collapse them so every title appears once instead of once-per-episode.
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
            .select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/ver/]")!!
        val slug = link.attr("abs:href").substringAfterLast("/ver/").trimEnd('/')
            .replace(EPISODE_SUFFIX_REGEX, "")
        setUrlWithoutDomain("/anime/$slug")
        title = element.selectFirst("img")?.attr("alt").orEmpty()
            .replace(EPISODE_LABEL_REGEX, "")
            .trim()
            .ifBlank { link.attr("title").cleanTitle() }
        thumbnail_url = "$baseUrl/cdn/img/anime/$slug.webp"
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // ─── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/animes".toHttpUrl().newBuilder()
            .addQueryParameter("buscar", query)
            .addQueryParameter("pag", page.toString())
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
        title = img?.attr("alt").orEmpty()
            .ifBlank { link.attr("title").cleanTitle() }
            .ifBlank { link.text().cleanTitle() }
            .trim()
        thumbnail_url = img?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
    }

    // ─── Details ──────────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = (document.selectFirst("h1")?.ownText() ?: document.selectFirst("h1")?.text().orEmpty())
            .cleanTitle().trim()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?: document.selectFirst(".ti img, figure img")?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        // The plot synopsis lives in a ".tx" block. Its ".tx.sp" sibling is SEO boilerplate
        // about the site, and og:description is an SEO-prefixed, truncated copy — so prefer the
        // clean ".tx" text and only fall back to a trimmed og:description.
        description = document.selectFirst(".tx:not(.sp)")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.substringAfter("OtakusTV.net")?.trim()
                ?.takeIf { it.isNotBlank() }
        genre = document.select("a[href*=genero=]").joinToString { it.text() }.ifBlank { null }
        status = when (document.selectFirst("span.st")?.text()?.trim()?.lowercase()) {
            "en emisión", "en emision" -> SAnime.ONGOING
            "finalizado", "concluido", "completado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ─── Episodes ─────────────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val slug = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')
        val prefix = "$slug-"

        // The page also lists global "latest episodes", so we keep only links whose slug is
        // exactly "{thisSlug}-{number}". Requiring a numeric tail prevents a prefix collision
        // (e.g. the "naruto" page must not pick up "naruto-shippuden-5").
        return document.select("a[href*=/ver/]")
            .map { it.attr("abs:href") }
            .distinct()
            .mapNotNull { href ->
                val verSlug = href.substringAfterLast("/ver/").trimEnd('/')
                if (!verSlug.startsWith(prefix)) return@mapNotNull null
                val num = verSlug.removePrefix(prefix)
                val number = num.replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
                SEpisode.create().apply {
                    url = href
                    name = "Episodio $num"
                    episode_number = number
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ─── Videos ───────────────────────────────────────────────────────────────────

    // Episodes are hosted on a different domain (otakustv2.com), so their url is stored absolute.
    override fun getEpisodeUrl(episode: SEpisode): String = episode.absoluteUrl

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // The server list is built by the page's own (obfuscated) JavaScript, so it is harvested
        // through a WebView; each entry is a ready-to-extract hoster embed URL.
        val embedUrls = serverExtractor.getEmbedUrls(episode.absoluteUrl, headers)

        return embedUrls.parallelCatchingFlatMap(::serverVideoResolver)
    }

    private val SEpisode.absoluteUrl: String
        get() = if (url.startsWith("http")) url else baseUrl + url

    private suspend fun serverVideoResolver(url: String): List<Video> {
        val u = url.lowercase()
        return when {
            listOf("voe", "tubeless", "simpulum", "urochs", "nathanfrom", "metagnath", "donaldline", "yip.").any { it in u } ->
                voeExtractor.videosFromUrl(url, "VOE")
            listOf("dood", "doood", "ds2play", "ds2video", "d000d", "d0000d").any { it in u } ->
                doodExtractor.videosFromUrl(url, "DoodStream")
            listOf("luluvdo", "lulustream", "lulu").any { it in u } ->
                luluExtractor.videosFromUrl(url, "Lulu")
            "uqload" in u -> uqloadExtractor.videosFromUrl(url, "Uqload")
            "mp4upload" in u -> mp4uploadExtractor.videosFromUrl(url, headers)
            "mixdrop" in u -> mixDropExtractor.videosFromUrl(url, prefix = "MixDrop")
            listOf("bysesukior", "byse", "streamwish", "strwish", "wish", "filelions", "lion", "swdyu", "iplayerhls").any { it in u } ->
                streamwishExtractor.videosFromUrl(url, "StreamWish")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private fun String.cleanTitle(): String = replace(TITLE_CLEAN_REGEX, "").trim()

    companion object {
        // Strips the trailing "-{episode}" from a watch slug to recover the anime slug.
        private val EPISODE_SUFFIX_REGEX = Regex("""-\d+(?:[.,]\d+)?$""")

        // Strips the trailing "episodio/capítulo/ep N…" tail from a latest-episode card label.
        private val EPISODE_LABEL_REGEX =
            Regex("""\s+(episodios?|cap[ií]tulos?|cap|ep)\s+\d+.*$""", RegexOption.IGNORE_CASE)

        // Strips leading "Ver " / trailing audio-language tags from titles.
        private val TITLE_CLEAN_REGEX = Regex(
            """^Ver\s+|\s+Online$|\s+(Sub\s+Español|Audio\s+Latino|Latino|Castellano)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
