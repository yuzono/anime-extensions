package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz

import android.util.Base64
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class AnimesHentaiBiz : AnimeHttpSource() {

    override val name = "AnimesHentaiBiz"
    override val baseUrl = "https://animeshentai.biz"
    override val lang = "pt"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ===================== UTILITÁRIO =====================
    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else baseUrl + url

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*Todos os Episodios Online\s*"""), "")
        .trim()

    // ===================== LISTAGEM DE SÉRIES (POPULAR) =====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/hentai/"
        } else {
            "$baseUrl/hentai/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animes = parseSeriesList(doc)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ===================== EPISÓDIOS RECENTES (LATEST) =====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/episodio/"
        } else {
            "$baseUrl/episodio/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val allEpisodes = parseEpisodeList(doc)
        val latestBySeries = groupLatestBySeries(allEpisodes)
        return AnimesPage(latestBySeries, latestBySeries.isNotEmpty())
    }

    // ===================== BUSCA =====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = "$baseUrl/?s=$encodedQuery"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val series = parseSeriesList(doc)
        if (series.isNotEmpty()) {
            return AnimesPage(series, false)
        }
        val episodes = parseEpisodeList(doc)
        return AnimesPage(episodes, false)
    }

    // ===================== DETALHES =====================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(absoluteUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body.string())
        val url = response.request.url.toString()

        return SAnime.create().apply {
            this.url = url
            title = doc.selectFirst("div.sheader .data h1")?.text()?.let { cleanTitle(it) }
                ?: doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
                ?: doc.title()?.let { cleanTitle(it) }
                ?: ""

            description = doc.selectFirst("div.resumotemp .wp-content p")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content p")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: ""

            thumbnail_url = doc.selectFirst("div.sheader .poster img")?.attr("src")?.let { absoluteUrl(it) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ""

            genre = doc.select("div.sgeneros a[href*='/genero/']").joinToString(", ") { it.text().trim() }
            status = SAnime.UNKNOWN
        }
    }

    // ===================== EPISÓDIOS =====================
    override fun episodeListRequest(anime: SAnime): Request = GET(absoluteUrl(anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val episodes = mutableListOf<SEpisode>()

        doc.select("div.tempep ul.episodios li").forEach { li ->
            val linkEl = li.selectFirst("div.episodiotitle a[href*='/episodio/']") ?: return@forEach
            val episodeUrl = linkEl.attr("href")
            val episodeName = linkEl.text().trim()
            val episodeNumber = Regex("""Episodio (\d+)""").find(episodeName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: (episodes.size + 1).toFloat()

            episodes.add(
                SEpisode.create().apply {
                    episode_number = episodeNumber
                    name = episodeName
                    url = episodeUrl
                },
            )
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*='/episodio/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !episodes.any { it.url == href }) {
                    val name = link.text().trim()
                    if (name.isNotEmpty()) {
                        episodes.add(
                            SEpisode.create().apply {
                                episode_number = (episodes.size + 1).toFloat()
                                this.name = name
                                url = href
                            },
                        )
                    }
                }
            }
        }

        return episodes
    }

    // ===================== VÍDEOS =====================
    override fun videoListRequest(episode: SEpisode): Request = GET(absoluteUrl(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sources = doc.select("[data-embed-id]").mapNotNull { element ->
            val encoded = element.attr("data-embed-id")
            val parts = encoded.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val label = decode(parts[0]) ?: "Servidor"
            val payload = decode(parts[1])
            PlayerSource(label, payload, element.text())
        }.distinctBy { it.label to it.payload }

        val playerHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .build()
        val preparedSources = sources.map(::prepareSource)
        val fastSources = preparedSources.filter { it.type == SourceType.BLOGGER }
        val slowSources = preparedSources.filter { it.type in setOf(SourceType.IFRAME, SourceType.URL) }
        val fastVideos = extractSources(fastSources, playerHeaders).distinctBy { it.videoUrl }
        if (fastVideos.isNotEmpty()) {
            return fastVideos
        }

        return extractSources(slowSources, playerHeaders).distinctBy { it.videoUrl }
    }

    private data class PlayerSource(val label: String, val payload: String?, val visibleText: String)

    private data class PreparedSource(
        val source: PlayerSource,
        val payload: String,
        val kind: PayloadKind,
        val embedUrl: String?,
        val type: SourceType,
    )

    private enum class PayloadKind { URL, IFRAME_HTML, HTML_OTHER, JSON, EMPTY, UNKNOWN }

    private enum class SourceType { BLOGGER, IFRAME, URL, OTHER }

    private fun prepareSource(source: PlayerSource): PreparedSource {
        val payload = source.payload?.let(::normalizePayload).orEmpty()
        val kind = payloadKind(payload)
        val embedUrl = extractEmbedUrl(payload, kind)
        val type = when {
            isBloggerUrl(embedUrl) -> SourceType.BLOGGER
            kind == PayloadKind.IFRAME_HTML -> SourceType.IFRAME
            kind == PayloadKind.URL -> SourceType.URL
            else -> SourceType.OTHER
        }
        return PreparedSource(source, payload, kind, embedUrl, type)
    }

    private fun extractSources(sources: List<PreparedSource>, playerHeaders: Headers): List<Video> = sources.parallelCatchingFlatMapBlocking { prepared ->
        when (prepared.type) {
            SourceType.BLOGGER -> bloggerExtractor.videosFromUrl(prepared.embedUrl.orEmpty(), playerHeaders).map { video ->
                Video(video.url, "${prepared.source.label} - ${video.quality}", video.videoUrl, video.headers ?: Headers.headersOf())
            }
            SourceType.IFRAME,
            SourceType.URL,
            -> universalExtractor.videosFromUrl(prepared.embedUrl.orEmpty(), playerHeaders, prepared.source.label)
            SourceType.OTHER -> emptyList()
        }
    }

    private fun normalizePayload(payload: String) = Jsoup.parseBodyFragment(payload).text().trim().ifBlank { payload.trim() }

    private fun payloadKind(payload: String): PayloadKind = when {
        payload.isBlank() -> PayloadKind.EMPTY
        Jsoup.parseBodyFragment(payload).selectFirst("iframe[src]") != null -> PayloadKind.IFRAME_HTML
        payload.startsWith("http://", true) || payload.startsWith("https://", true) || payload.startsWith("//") -> PayloadKind.URL
        payload.startsWith("{") || payload.startsWith("[") -> PayloadKind.JSON
        payload.startsWith("<") -> PayloadKind.HTML_OTHER
        else -> PayloadKind.UNKNOWN
    }

    private fun extractEmbedUrl(payload: String, kind: PayloadKind): String? = when (kind) {
        PayloadKind.IFRAME_HTML -> Jsoup.parseBodyFragment(payload).selectFirst("iframe[src]")?.attr("src")?.let(::normalizeUrl)
        PayloadKind.URL -> normalizeUrl(payload)
        else -> null
    }

    private fun normalizeUrl(url: String) = if (url.startsWith("//")) "https:$url" else url

    private fun isBloggerUrl(url: String?) = url?.contains("blogger.com/video", true) == true

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ===================== FUNÇÕES AUXILIARES =====================
    private fun groupLatestBySeries(episodes: List<SAnime>): List<SAnime> {
        val grouped = episodes.groupBy { extractSeriesName(it.title) }
        return grouped.values.mapNotNull { seriesEpisodes ->
            seriesEpisodes.maxByOrNull { extractEpisodeNumber(it.title) ?: -1 }
        }
    }

    private fun extractSeriesName(title: String): String = title.replace(Regex("""\s*Episodio\s+\d+.*""", RegexOption.IGNORE_CASE), "").trim()

    private fun extractEpisodeNumber(title: String): Int? = Regex("""Episodio\s+(\d+)""", RegexOption.IGNORE_CASE)
        .find(title)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    private fun parseSeriesList(doc: Document): List<SAnime> {
        val animes = mutableListOf<SAnime>()

        doc.select("article.item.tvshows").forEach { article ->
            val linkEl = article.selectFirst("div.poster a[href*='/hentai/']") ?: return@forEach
            val animeUrl = linkEl.attr("href")
            val title = article.selectFirst("h3 a")?.text()?.trim() ?: linkEl.attr("title") ?: ""
            val thumbnail = article.selectFirst("div.poster img")?.attr("src")?.let { absoluteUrl(it) } ?: ""

            animes.add(
                SAnime.create().apply {
                    url = animeUrl
                    this.title = title
                    thumbnail_url = thumbnail
                },
            )
        }

        return animes
    }

    private fun parseEpisodeList(doc: Document): List<SAnime> {
        val episodes = mutableListOf<SAnime>()

        doc.select("article.item.se.episodes").forEach { article ->
            val linkEl = article.selectFirst("div.poster a[href*='/episodio/']") ?: return@forEach
            val episodeUrl = linkEl.attr("href")
            val title = article.selectFirst("h3 a")?.text()?.trim() ?: linkEl.attr("title") ?: ""
            val thumbnail = article.selectFirst("div.poster img")?.attr("src")?.let { absoluteUrl(it) } ?: ""

            episodes.add(
                SAnime.create().apply {
                    url = episodeUrl
                    this.title = title
                    thumbnail_url = thumbnail
                },
            )
        }

        return episodes
    }
}
