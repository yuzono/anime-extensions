package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.watchanimehentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class WatchAnimeHentai : AnimeHttpSource() {

    override val name = "WatchAnimeHentai"
    override val baseUrl = "https://www.watchanimehentai.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ===================== UTILITÁRIO =====================
    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else baseUrl + url

    private fun hasNextPage(doc: Document): Boolean = doc.select(
        "a.next, a.nextpostslink, a[rel=next], " +
            ".pagination a.next, .pagination .next, " +
            "ul.pagination li.next, div.pagination a.next, " +
            "a[href*='/page/']:contains(Next), a[href*='/page/']:contains(Próximo)",
    ).isNotEmpty()

    // ===================== LISTAGEM =====================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

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
        val animes = parseAnimeList(doc)
        return AnimesPage(animes, hasNextPage(doc))
    }

    // ===================== BUSCA =====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/search/$encodedQuery"
        } else {
            "$baseUrl/search/$encodedQuery/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animes = parseSearchResults(doc)
        if (animes.isNotEmpty()) {
            return AnimesPage(animes, hasNextPage(doc))
        }
        val fallback = parseAnimeList(doc)
        return AnimesPage(fallback, hasNextPage(doc))
    }

    // ===================== DETALHES =====================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(absoluteUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body.string())
        val url = response.request.url.toString()

        return SAnime.create().apply {
            this.url = url
            title = doc.selectFirst("div.sheader .data h1")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title()
                ?: ""

            description = doc.selectFirst("div.wp-content p")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content")?.text()?.trim()
                ?: doc.selectFirst("div.info1 .wp-content")?.text()?.trim()
                ?: doc.selectFirst("div.sinopse")?.text()?.trim()
                ?: doc.selectFirst("div.descricao")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: ""

            thumbnail_url = doc.selectFirst("div.sheader .poster img")?.attr("src")?.let { absoluteUrl(it) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

            genre = doc.select("a[href*='/genre/']").joinToString(", ") { it.text().trim() }
            status = SAnime.UNKNOWN
        }
    }

    // ===================== EPISÓDIOS =====================
    override fun episodeListRequest(anime: SAnime): Request = GET(absoluteUrl(anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val episodes = mutableListOf<SEpisode>()

        val episodeElements = doc.select("article.item.se.episodes")
        episodeElements.forEachIndexed { index, article ->
            val linkEl = article.selectFirst("a[href^='/episodes/']") ?: return@forEachIndexed
            val episodeUrl = linkEl.attr("href")
            val episodeName = article.selectFirst("h3")?.text()?.trim() ?: "Episode ${index + 1}"
            episodes.add(
                SEpisode.create().apply {
                    episode_number = (index + 1).toFloat()
                    name = episodeName
                    url = episodeUrl
                },
            )
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*='/episodes/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !episodes.any { it.url == href }) {
                    val name = link.selectFirst("h3")?.text()?.trim()
                        ?: link.text().trim()
                        ?: "Episode ${episodes.size + 1}"
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

        return episodes
    }

    // ===================== VÍDEOS =====================
    override fun videoListRequest(episode: SEpisode): Request = GET(absoluteUrl(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = Jsoup.parse(response.body.string())
        val videos = mutableListOf<Video>()

        val optionLinks = doc.select("ul.idTabs.sourceslist a[href^=#option-]")

        if (optionLinks.isNotEmpty()) {
            for (link in optionLinks) {
                val optionId = link.attr("href").removePrefix("#")
                val languageLabel = link.text().trim()

                val iframe = doc.selectFirst("div#$optionId iframe") ?: continue
                var iframeSrc = iframe.attr("src")
                if (iframeSrc.isBlank()) continue
                if (iframeSrc.startsWith("/")) {
                    iframeSrc = baseUrl + iframeSrc
                }

                // 1. Tenta decodificar o parâmetro padrao (Base64)
                val padrao = iframeSrc.substringAfter("padrao=", "")
                if (padrao.isNotBlank()) {
                    val decoded = decodePadrao(padrao)
                    if (decoded != null) {
                        val videosFromDecoded = extractVideosFromText(decoded, languageLabel, iframeSrc)
                        if (videosFromDecoded.isNotEmpty()) {
                            videos.addAll(videosFromDecoded)
                            continue
                        }
                    }
                }

                // 2. Tenta extração direta no HTML do iframe
                try {
                    val iframeResponse = client.newCall(GET(iframeSrc, headers)).execute()
                    val iframeBody = iframeResponse.body.string()
                    val directVideos = extractVideosFromHtml(iframeBody, languageLabel, iframeSrc)
                    if (directVideos.isNotEmpty()) {
                        videos.addAll(directVideos)
                        continue
                    }

                    // 3. Fallback para UniversalExtractor
                    val universalVideos = universalExtractor.videosFromUrl(iframeSrc, headers, languageLabel)
                    videos.addAll(universalVideos)
                } catch (e: Exception) {
                    val universalVideos = universalExtractor.videosFromUrl(iframeSrc, headers, languageLabel)
                    videos.addAll(universalVideos)
                }
            }
        } else {
            val iframes = doc.select("iframe")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val absoluteSrc = absoluteUrl(src)
                    try {
                        val iframeResponse = client.newCall(GET(absoluteSrc, headers)).execute()
                        val iframeBody = iframeResponse.body.string()
                        val directVideos = extractVideosFromHtml(iframeBody, "Player", absoluteSrc)
                        if (directVideos.isNotEmpty()) {
                            videos.addAll(directVideos)
                        } else {
                            videos.addAll(universalExtractor.videosFromUrl(absoluteSrc, headers, "Player"))
                        }
                    } catch (e: Exception) {
                        videos.addAll(universalExtractor.videosFromUrl(absoluteSrc, headers, "Player"))
                    }
                }
            }
        }

        return videos
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    // ===================== UTILITÁRIOS =====================
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun decodePadrao(padrao: String): String? = try {
        val decodedBytes = Base64.decode(padrao, Base64.DEFAULT)
        String(decodedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun extractVideosFromText(text: String, prefix: String, referer: String): List<Video> = extractVideosFromHtml(text, prefix, referer)

    private fun extractVideosFromHtml(html: String, prefix: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()

        val googlevideoRegex = Regex(
            """https?://[^"'\\s<>]+googlevideo\.com/videoplayback[^"'\\s<>]*""",
            RegexOption.IGNORE_CASE,
        )
        googlevideoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
            val itag = Regex("""[?&]itag=(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: "?"
            val qualityLabel = qualityFromItag(itag)
            val videoHeaders = Headers.headersOf(
                "Referer",
                referer,
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            )
            videos.add(Video(videoUrl, "$prefix - $qualityLabel", videoUrl, videoHeaders))
        }

        val m3u8Regex = Regex(
            """https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""",
            RegexOption.IGNORE_CASE,
        )
        m3u8Regex.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
            val videoHeaders = Headers.headersOf(
                "Referer",
                referer,
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            )
            videos.add(Video(videoUrl, "$prefix - HLS", videoUrl, videoHeaders))
        }

        if (videos.isEmpty()) {
            val mp4Regex = Regex(
                """https?://[^"'\\s<>]+\.mp4[^"'\\s<>]*""",
                RegexOption.IGNORE_CASE,
            )
            mp4Regex.findAll(html).forEach { match ->
                val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
                val videoHeaders = Headers.headersOf(
                    "Referer",
                    referer,
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
                )
                videos.add(Video(videoUrl, prefix, videoUrl, videoHeaders))
            }
        }

        return videos
    }

    private fun parseAnimeList(doc: Document): List<SAnime> {
        val animes = mutableListOf<SAnime>()

        doc.select("article.item.tvshows, article.item.se.episodes").forEach { article ->
            val linkEl = article.selectFirst("a[href*='/info/']") ?: return@forEach
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

    private fun parseSearchResults(doc: Document): List<SAnime> = doc.select("article.search-result").mapNotNull { article ->
        val linkEl = article.selectFirst("a[href*='/info/']") ?: return@mapNotNull null
        val animeUrl = linkEl.attr("href")
        val title = article.selectFirst("h2.search-result-title a")?.text()?.trim()
            ?: article.selectFirst("h2 a")?.text()?.trim()
            ?: linkEl.attr("title") ?: ""
        val thumbnail = article.selectFirst("img")?.attr("src")?.let { absoluteUrl(it) } ?: ""

        SAnime.create().apply {
            url = animeUrl
            this.title = title
            thumbnail_url = thumbnail
        }
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
