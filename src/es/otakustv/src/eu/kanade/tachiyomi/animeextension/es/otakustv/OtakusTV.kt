package eu.kanade.tachiyomi.animeextension.es.otakustv

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OtakusTV : AnimeHttpSource() {

    override val name = "OtakusTV"

    override val baseUrl = "https://www.otakustv.net"

    override val lang = "es"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val backendUrl = "$baseUrl/backend"

    private val baseHost = baseUrl.toHttpUrl().host

    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?pag=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("article.li").map(::animeFromElement)
        val hasNextPage = document.selectFirst("ul.pag a:contains(Siguiente)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // "Latest" is the homepage "Nuevos episodios" grid. Each card links to an episode
    // (/ver/{slug}-{num}), which is mapped back to its anime.
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    // A title with several new episodes has a card for each of them.
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
            .select("article.li:has(a[href*=/ver/])")
            .map(::latestAnimeFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    private fun latestAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/ver/]")!!
        val slug = link.absUrl("href").substringAfterLast("/ver/").trimEnd('/')
            .replace(EPISODE_SUFFIX_REGEX, "")
        url = "/anime/$slug"
        title = element.selectFirst("img")?.attr("alt").orEmpty()
            .replace(EPISODE_LABEL_REGEX, "")
            .ifEmpty { link.attr("title").cleanTitle() }
        thumbnail_url = "$baseUrl/cdn/img/anime/$slug.webp"
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/animes".toHttpUrl().newBuilder()
            .addQueryParameter("buscar", query)
            .addQueryParameter("pag", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/anime/]") ?: element.selectFirst("a")!!
        // Listing links are relative ("./anime/…"), so they are resolved before the domain is dropped.
        setUrlWithoutDomain(link.absUrl("href"))
        val img = element.selectFirst("img")
        title = img?.attr("alt").orEmpty()
            .ifEmpty { link.attr("title").cleanTitle() }
            .ifEmpty { link.text().cleanTitle() }
        thumbnail_url = img?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            // The heading is <h1><strong>Title <i>Sub Español</i></strong></h1>, so the plain
            // title is the own text of <strong>.
            val h1 = document.selectFirst("h1")
            title = h1?.selectFirst("strong")?.ownText().orEmpty()
                .ifEmpty { h1?.text().orEmpty().cleanTitle() }
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.absUrl("content")
                ?: document.selectFirst(".ti img, figure img")?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            // ".tx.sp" is SEO text about the site, and og:description is an SEO-prefixed,
            // truncated copy of the synopsis.
            description = document.selectFirst(".tx:not(.sp)")?.text()?.ifEmpty { null }
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                    ?.substringAfter("OtakusTV.net")?.trim()
                    ?.ifEmpty { null }
            genre = document.select("a[href*=genero=]").joinToString { it.text() }.ifEmpty { null }
            status = when (document.selectFirst("span.st")?.text()?.lowercase()) {
                "en emisión", "en emision" -> SAnime.ONGOING
                "finalizado", "concluido", "completado" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // The anime page shows the newest EPISODES_PER_PAGE episodes; older ones come from the
    // backend in pages of the same size, numbered from the newest.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url, headers).useAsJsoup()

        // "#dt" names the watch-URL base ("…/ver/{slug}"), which is what the episode links use.
        val info = document.selectFirst("#dt")
        val watchUrl = info?.attr("data-u").orEmpty()
        val slug = watchUrl.substringAfterLast("/ver/", "").trimEnd('/')
            .ifEmpty { anime.url.trimEnd('/').substringAfterLast('/') }
        val total = info?.attr("data-e")?.toIntOrNull()?.coerceIn(0, MAX_EPISODES) ?: 0
        val pageCount = (total + EPISODES_PER_PAGE - 1) / EPISODES_PER_PAGE
        if (info == null || pageCount <= 1) return parseEpisodes(document, slug).sortedByDescending { it.episode_number }

        val animeId = info.attr("data-i")
        val ajaxHeaders = ajaxHeaders(baseUrl + anime.url)
        val gate = Semaphore(EPISODE_PAGE_CONCURRENCY)
        suspend fun fetchPages(pages: List<Int>): List<SEpisode> = pages.parallelFlatMap { page ->
            gate.withPermit {
                val body = FormBody.Builder()
                    .add("acc", "episodes")
                    .add("i", animeId)
                    .add("u", watchUrl)
                    .add("p", page.toString())
                    .build()
                val fragment = client.post(backendUrl, ajaxHeaders, body).useAsJsoup()
                parseEpisodes(fragment, slug)
            }
        }

        // A failed page fails the whole list on purpose: the app deletes episodes (and their
        // watch progress) that a returned list leaves out.
        var episodes = (parseEpisodes(document, slug) + fetchPages((2..pageCount).toList()))
            .distinctBy { it.url }
        // Pages are counted from the newest episode, so one published while they load shifts
        // the oldest episodes onto the next page.
        if (episodes.size < total) {
            episodes = (episodes + fetchPages(listOf(pageCount + 1))).distinctBy { it.url }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // The anime page also lists the site's latest episodes, so only links named exactly
    // "{slug}-{number}" are kept. The numeric tail stops the "naruto" page from picking up
    // "naruto-shippuden-5".
    private fun parseEpisodes(document: Document, slug: String): List<SEpisode> {
        val prefix = "$slug-"
        return document.select("a[href*=/ver/]")
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
            .filter { it.host == baseHost }
            .distinct()
            .mapNotNull { url ->
                val watchSlug = url.encodedPath.substringAfter("/ver/", "").trimEnd('/')
                if (!watchSlug.startsWith(prefix)) return@mapNotNull null
                val num = watchSlug.removePrefix(prefix)
                val number = num.replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
                SEpisode.create().apply {
                    this.url = url.encodedPath
                    name = "Episodio $num"
                    episode_number = number
                }
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // The watch page carries the episode id as hex in ".opt[data-encrypt]"; the backend answers
    // with one "li[encrypt]" per server, whose attribute is the hex-encoded embed URL.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeUrl = baseUrl + episode.url
        val document = client.get(episodeUrl, headers).useAsJsoup()
        val episodeId = document.selectFirst(".opt[data-encrypt]")?.attr("data-encrypt")
            ?: return emptyList()

        val body = FormBody.Builder()
            .add("acc", "opt")
            .add("i", episodeId)
            .build()
        val servers = client.post(backendUrl, ajaxHeaders(episodeUrl), body).useAsJsoup()

        // The same embed is often listed under several names ("filemoon 1", "byse").
        return servers.select("li[encrypt]")
            .mapNotNull { server ->
                val url = runCatching { server.attr("encrypt").decodeHexToString() }.getOrNull()
                    ?.toHttpUrlOrNull()
                    ?.takeIf { it.isPublicEmbed() }
                    ?.toString()
                    ?: return@mapNotNull null
                val name = server.selectFirst("span")?.text().orEmpty().ifEmpty { server.attr("title") }
                Hoster(hosterUrl = url, hosterName = name)
            }
            .distinctBy { it.hosterUrl }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        // Matched on the host only: a random embed ID can contain a token such as "voe".
        val u = url.toHttpUrlOrNull()?.host?.lowercase() ?: url.lowercase()
        return when {
            listOf("voe", "tubeless", "simpulum", "urochs", "nathanfrom", "metagnath", "donaldline", "yip.").any { it in u } ->
                voeExtractor.videosFromUrl(url, "VOE")
            // These extractors name the host in the video title themselves.
            listOf("dood", "doood", "ds2play", "ds2video", "d000d", "d0000d").any { it in u } ->
                doodExtractor.videosFromUrl(url)
            listOf("luluvdo", "lulustream", "lulu").any { it in u } ->
                luluExtractor.videosFromUrl(url, "")
            "uqload" in u -> uqloadExtractor.videosFromUrl(url)
            "mp4upload" in u -> mp4uploadExtractor.videosFromUrl(url, headers)
            "mixdrop" in u -> mixDropExtractor.videosFromUrl(url)
            // Filemoon runs on rotating "byse…" hosts, so "byse" also covers "bysesukior".
            listOf("filemoon", "byse").any { it in u } ->
                filemoonExtractor.videosFromUrl(url)
            listOf("streamwish", "strwish", "wish", "filelions", "lion", "swdyu", "iplayerhls").any { it in u } ->
                streamwishExtractor.videosFromUrl(url, "StreamWish")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    private fun ajaxHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun String.cleanTitle(): String = replace(TITLE_CLEAN_REGEX, "").trim()

    // Embeds are opened by the extractors, some in a WebView, so only public HTTPS hosts are
    // accepted: no IP literals and no local names.
    private fun HttpUrl.isPublicEmbed(): Boolean = isHttps &&
        '.' in host &&
        !host.endsWith(".local") &&
        !host.endsWith(".localhost") &&
        ':' !in host &&
        !host.all { it.isDigit() || it == '.' }

    companion object {
        private const val EPISODES_PER_PAGE = 16
        private const val EPISODE_PAGE_CONCURRENCY = 4

        // Far above any real series; bounds the pages requested for a corrupt "data-e".
        private const val MAX_EPISODES = 10_000

        // The trailing "-{episode}" of a watch slug.
        private val EPISODE_SUFFIX_REGEX = Regex("""-\d+(?:[.,]\d+)?$""")

        // The trailing "episodio/capítulo/ep N…" of a latest-episode card label.
        private val EPISODE_LABEL_REGEX =
            Regex("""\s+(episodios?|cap[ií]tulos?|cap|ep)\s+\d+.*$""", RegexOption.IGNORE_CASE)

        // A leading "Ver " and trailing "Online"/audio-language tags.
        private val TITLE_CLEAN_REGEX = Regex(
            """^Ver\s+(Anime\s+)?|\s+Online(\s+Gratis)?$|\s+(Sub\s+Español|Audio\s+Latino|Latino|Castellano)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
