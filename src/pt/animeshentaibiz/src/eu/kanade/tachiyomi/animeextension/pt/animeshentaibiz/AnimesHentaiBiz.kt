package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz

import android.util.Base64
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesHentaiBiz : AnimeHttpSource() {

    override val name = "AnimesHentaiBiz"
    override val baseUrl = "https://animeshentai.biz"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
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

        // Seletor específico da lista de episódios na página da série
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

        // Fallback: tenta links genéricos para /episodio/
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
        val document = response.useAsJsoup()
        val iframes = document.select("iframe")
        return iframes.parallelCatchingFlatMapBlocking { iframe ->
            getPlayerVideos(iframe)
        }
    }

    private suspend fun getPlayerVideos(iframe: Element): List<Video> {
        val src = iframe.attr("src")
        if (src.isBlank()) return emptyList()

        val absoluteSrc = if (src.startsWith("http")) src else baseUrl + src
        val id = iframe.parent()?.attr("id") ?: ""
        val language = iframe.ownerDocument()!!
            .selectFirst("a.options[href=\"#$id\"]")
            ?.text()
            ?.trim()
            ?.takeIf { it.equals("Legendado", true) || it.equals("Dublado", true) }
            ?: ""

        return when {
            "blogger.com/video.g" in absoluteSrc -> {
                bloggerExtractor.videosFromUrl(absoluteSrc, headers, language)
            }
            "googlevideo.com" in absoluteSrc || absoluteSrc.endsWith(".mp4") -> {
                listOf(Video(absoluteSrc, "Player", absoluteSrc))
            }
            else -> emptyList()
        }
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    // ===================== UTILITÁRIOS =====================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private fun decodePadrao(padrao: String): String? = try {
        val decodedBytes = Base64.decode(padrao, Base64.DEFAULT)
        String(decodedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

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

    private fun qualityFromItag(itag: String): String = when (itag) {
        "17" -> "144p"
        "18" -> "360p"
        "22" -> "720p"
        "37" -> "1080p"
        "36" -> "180p"
        "43" -> "360p WebM"
        "44" -> "480p WebM"
        "45" -> "720p WebM"
        else -> "Qualidade $itag"
    }
}
