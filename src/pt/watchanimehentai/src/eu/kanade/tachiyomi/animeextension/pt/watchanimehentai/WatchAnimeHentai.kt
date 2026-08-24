package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

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
    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ===================== LISTAGEM =====================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/hentai/"
                  else "$baseUrl/hentai/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animes = parseAnimeList(doc)
        val hasNextPage = doc.select("a.next, .pagination .next, ul.pagination li.next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ===================== BUSCA =====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = "$baseUrl/search/$encodedQuery"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animes = parseAnimeList(doc)
        return AnimesPage(animes, false)
    }

    // ===================== DETALHES =====================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body.string())
        val url = response.request.url.toString()

        return SAnime.create().apply {
            this.url = url
            title = doc.selectFirst("div.sheader .data h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title()
                ?: ""

            description = doc.selectFirst("div.wp-content p")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: ""

            thumbnail_url = doc.selectFirst("div.sheader .poster img")?.attr("src")?.let {
                if (it.startsWith("http")) it else baseUrl + it
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

            genre = doc.select("a[href*='/genre/']").joinToString(", ") { it.text().trim() }
            status = SAnime.UNKNOWN
        }
    }

    // ===================== EPISÓDIOS =====================
    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val episodeElements = doc.select("article.item.se.episodes")

        return episodeElements.mapIndexed { index, article ->
            val linkEl = article.selectFirst("a[href^='/episodes/']") ?: return@mapIndexed null
            val episodeUrl = linkEl.attr("href")?.let { if (it.startsWith("http")) it else baseUrl + it } ?: return@mapIndexed null
            val episodeName = article.selectFirst("h3")?.text()?.trim() ?: "Episode ${index + 1}"

            SEpisode.create().apply {
                episode_number = (index + 1).toFloat()
                name = episodeName
                url = episodeUrl
            }
        }.filterNotNull()
    }

    // ===================== VÍDEOS =====================
    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

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

                try {
                    val iframeResponse = client.newCall(GET(iframeSrc, headers)).execute()
                    val iframeBody = iframeResponse.body.string()

                    val googlevideoRegex = Regex(
                        """https?://[^"'\\s<>]+googlevideo\.com/videoplayback[^"'\\s<>]*""",
                        RegexOption.IGNORE_CASE
                    )
                    val matches = googlevideoRegex.findAll(iframeBody).toList()

                    if (matches.isNotEmpty()) {
                        matches.forEach { match ->
                            val videoUrl = match.value
                                .replace("&amp;", "&")
                                .replace("\\/", "/")

                            val itag = Regex("""[?&]itag=(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: "?"
                            val qualityLabel = qualityFromItag(itag)

                            videos.add(Video(videoUrl, "$languageLabel - $qualityLabel", videoUrl))
                        }
                    } else {
                        videos.add(Video(iframeSrc, languageLabel, iframeSrc))
                    }
                } catch (e: Exception) {
                    videos.add(Video(iframeSrc, languageLabel, iframeSrc))
                }
            }
        } else {
            val iframes = doc.select("iframe")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val absoluteSrc = if (src.startsWith("http")) src else baseUrl + src
                    videos.add(Video(absoluteSrc, "Player", absoluteSrc))
                }
            }
        }

        return videos
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    // ===================== UTILITÁRIOS =====================
    private fun parseAnimeList(doc: Document): List<SAnime> {
        val animes = mutableListOf<SAnime>()

        doc.select("article.item.tvshows").forEach { article ->
            val linkEl = article.selectFirst("a[href^='/info/']") ?: return@forEach
            val animeUrl = linkEl.attr("href")?.let { if (it.startsWith("http")) it else baseUrl + it } ?: return@forEach
            val title = article.selectFirst("h3 a")?.text()?.trim() ?: linkEl.attr("title") ?: ""
            val thumbnail = article.selectFirst("div.poster img")?.attr("src")?.let {
                if (it.startsWith("http")) it else baseUrl + it
            } ?: ""

            animes.add(
                SAnime.create().apply {
                    url = animeUrl
                    this.title = title
                    thumbnail_url = thumbnail
                }
            )
        }

        return animes
    }

    private fun qualityFromItag(itag: String): String {
        return when (itag) {
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
}
