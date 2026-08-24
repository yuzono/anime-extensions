package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class AnimesHentaiBiz : AnimeHttpSource() {

    override val name = "AnimesHentaiBiz"
    override val baseUrl = "https://animeshentai.biz"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ===================== UTILITÁRIOS =====================
    private fun absoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*Todos os Episodios Online\s*"""), "")
        .trim()

    // ===================== POPULAR / LATEST / SEARCH =====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/hentai/" else "$baseUrl/hentai/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animes = parseSeriesList(doc)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/episodio/" else "$baseUrl/episodio/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val allEpisodes = parseEpisodeList(doc)
        val latestBySeries = groupLatestBySeries(allEpisodes)
        return AnimesPage(latestBySeries, latestBySeries.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        return GET("$baseUrl/?s=$encodedQuery", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val series = parseSeriesList(doc)
        if (series.isNotEmpty()) return AnimesPage(series, false)
        val episodes = parseEpisodeList(doc)
        return AnimesPage(episodes, false)
    }

    // ===================== DETALHES E EPISÓDIOS =====================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(absoluteUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            url = response.request.url.toString()
            title = doc.selectFirst("div.sheader .data h1")?.text()?.let { cleanTitle(it) }
                ?: doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
                ?: ""
            description = doc.selectFirst("div.resumotemp .wp-content p")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content p")?.text()?.trim()
                ?: ""
            thumbnail_url = doc.selectFirst("div.sheader .poster img")?.attr("src")?.let { absoluteUrl(it) } ?: ""
            genre = doc.select("div.sgeneros a[href*='/genero/']").joinToString(", ") { it.text().trim() }
            status = SAnime.UNKNOWN
        }
    }

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
                    this.episode_number = episodeNumber
                    this.name = episodeName
                    this.url = episodeUrl
                },
            )
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*='/episodio/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !episodes.any { it.url == href }) {
                    episodes.add(
                        SEpisode.create().apply {
                            this.episode_number = (episodes.size + 1).toFloat()
                            this.name = link.text().trim()
                            this.url = href
                        },
                    )
                }
            }
        }
        return episodes
    }

    // ===================== EXTRAÇÃO DE VÍDEOS =====================
    override fun videoListRequest(episode: SEpisode): Request = GET(absoluteUrl(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()

        val videosFromAjax = getVideosFromDooPlayOptions(document)
        if (videosFromAjax.isNotEmpty()) return videosFromAjax

        val iframes = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
        return iframes.parallelCatchingFlatMapBlocking { iframe ->
            val src = iframe.attr("src")
                .ifEmpty { iframe.attr("data-src") }
                .ifEmpty { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) fetchVideosFromEmbedUrl(src, "") else emptyList()
        }
    }

    private fun getVideosFromDooPlayOptions(document: Document): List<Video> {
        val options = document.select("li[data-post][data-type][data-nume], ul#playeroptionsul li, div.optionsbox ul li, ul.options li")

        return options.parallelCatchingFlatMapBlocking { option ->
            val post = option.attr("data-post")
            val type = option.attr("data-type")
            val nume = option.attr("data-nume")
            val language = option.selectFirst("span.title")?.text()?.trim() ?: ""

            if (post.isNotBlank() && type.isNotBlank() && nume.isNotBlank()) {
                val body = FormBody.Builder()
                    .add("action", "doo_player_ajax")
                    .add("post", post)
                    .add("type", type)
                    .add("nume", nume)
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/wp-admin/admin-ajax.php")
                    .post(body)
                    .headers(
                        headersBuilder()
                            .add("X-Requested-With", "XMLHttpRequest")
                            .add("Referer", document.location())
                            .build(),
                    )
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string() ?: return@use emptyList()
                        val embedUrl = extractEmbedUrlFromResponse(responseBody) ?: return@use emptyList()
                        fetchVideosFromEmbedUrl(embedUrl, language)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                val dataUrl = option.attr("data-url")
                if (dataUrl.isNotBlank()) fetchVideosFromEmbedUrl(dataUrl, language) else emptyList()
            }
        }
    }

    private fun extractEmbedUrlFromResponse(responseBody: String): String? = try {
        val json = JSONObject(responseBody)
        val embedHtml = json.optString("embed_url", "")
        if (embedHtml.startsWith("http://") || embedHtml.startsWith("https://") || embedHtml.startsWith("//")) {
            embedHtml
        } else {
            val doc = Jsoup.parse(embedHtml)
            doc.selectFirst("iframe")?.let { it.attr("src").ifEmpty { it.attr("data-src") } } ?: embedHtml.ifBlank { null }
        }
    } catch (e: Exception) {
        val doc = Jsoup.parse(responseBody)
        doc.selectFirst("iframe")?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
    }

    private fun fetchVideosFromEmbedUrl(embedUrl: String, language: String): List<Video> {
        val cleanEmbedUrl = absoluteUrl(embedUrl)

        if ("blogger.com/video.g" in cleanEmbedUrl) {
            return extractBloggerVideos(cleanEmbedUrl, language)
        }

        return try {
            val response = client.newCall(GET(cleanEmbedUrl, headers)).execute()
            val doc = Jsoup.parse(response.body?.string() ?: "")
            val bloggerIframe = doc.selectFirst("iframe[src*='blogger.com/video.g'], iframe[data-src*='blogger.com/video.g']")
            val bloggerUrl = bloggerIframe?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

            if (!bloggerUrl.isNullOrEmpty()) {
                extractBloggerVideos(absoluteUrl(bloggerUrl), language)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    // ===================== EXTRATOR REGEX DO BLOGGER =====================
    private fun extractBloggerVideos(url: String, language: String): List<Video> {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body?.string() ?: return emptyList()

            val videos = mutableListOf<Video>()
            val playUrlRegex = Regex(""" "play_url"\s*:\s*"([^"]+)" """)
            val formatIdRegex = Regex(""" "format_id"\s*:\s*(\d+) """)

            val streamBlocks = body.split("{\"format_id\"").drop(1)

            for (block in streamBlocks) {
                val rawUrl = playUrlRegex.find(block)?.groupValues?.get(1) ?: continue
                val formatId = formatIdRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val cleanUrl = sanitizeUrl(rawUrl)

                val quality = when (formatId) {
                    22 -> "720p"
                    18 -> "360p"
                    37 -> "1080p"
                    else -> "SD"
                }

                val videoHeaders = Headers.headersOf(
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer", "https://www.blogger.com/",
                )

                val label = if (language.isNotBlank()) "$language - Blogger $quality" else "Blogger $quality"
                videos.add(Video(cleanUrl, label, cleanUrl, videoHeaders))
            }

            // Fallback genérico caso a estrutura por blocos mude
            if (videos.isEmpty()) {
                playUrlRegex.findAll(body).forEachIndexed { index, match ->
                    val cleanUrl = sanitizeUrl(match.groupValues[1])
                    val label = if (language.isNotBlank()) "$language - Blogger SD ${index + 1}" else "Blogger SD ${index + 1}"
                    val videoHeaders = Headers.headersOf(
                        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer", "https://www.blogger.com/",
                    )
                    videos.add(Video(cleanUrl, label, cleanUrl, videoHeaders))
                }
            }

            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sanitizeUrl(url: String): String = url
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("%3D", "=")
        .replace("%26", "&")

    // ===================== AUXILIARES DE PARSE =====================
    private fun groupLatestBySeries(episodes: List<SAnime>): List<SAnime> {
        val grouped = episodes.groupBy { extractSeriesName(it.title) }
        return grouped.values.mapNotNull { seriesEpisodes ->
            seriesEpisodes.maxByOrNull { extractEpisodeNumber(it.title) ?: -1 }
        }
    }

    private fun extractSeriesName(title: String): String = title.replace(Regex("""\s*Episodio\s+\d+.*""", RegexOption.IGNORE_CASE), "").trim()

    private fun extractEpisodeNumber(title: String): Int? = Regex("""Episodio\s+(\d+)""", RegexOption.IGNORE_CASE)
        .find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseSeriesList(doc: Document): List<SAnime> {
        return doc.select("article.item.tvshows").mapNotNull { article ->
            val linkEl = article.selectFirst("div.poster a[href*='/hentai/']") ?: return@mapNotNull null
            SAnime.create().apply {
                url = linkEl.attr("href")
                title = article.selectFirst("h3 a")?.text()?.trim() ?: linkEl.attr("title") ?: ""
                thumbnail_url = article.selectFirst("div.poster img")?.attr("src")?.let { absoluteUrl(it) } ?: ""
            }
        }
    }

    private fun parseEpisodeList(doc: Document): List<SAnime> {
        return doc.select("article.item.se.episodes").mapNotNull { article ->
            val linkEl = article.selectFirst("div.poster a[href*='/episodio/']") ?: return@mapNotNull null
            SAnime.create().apply {
                url = linkEl.attr("href")
                title = article.selectFirst("h3 a")?.text()?.trim() ?: linkEl.attr("title") ?: ""
                thumbnail_url = article.selectFirst("div.poster img")?.attr("src")?.let { absoluteUrl(it) } ?: ""
            }
        }
    }
}
