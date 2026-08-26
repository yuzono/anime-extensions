package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class AnimesHentaiBiz : ParsedAnimeHttpSource() {

    override val name = "Animes Hentai Biz"

    override val baseUrl = "https://animeshentai.biz"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    }

    // ============================== Populares ==============================
    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/page/$page/", headers)
        }
    }

    override fun popularAnimeSelector(): String = "article, div.post, div.item, .episodes-list article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val anchor = element.selectFirst("a[href]")
        anime.setUrlWithoutDomain(anchor?.attr("abs:href") ?: "")
        anime.title = element.selectFirst(".entry-title, .title, h2, h3")?.text()
            ?: anchor?.attr("title")
            ?: ""

        val img = element.selectFirst("img")
        anime.thumbnail_url = img?.attr("abs:data-src")
            ?.ifEmpty { img.attr("abs:data-lazy-src") }
            ?.ifEmpty { img.attr("abs:src") }

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next, .pagination .next, .nav-links .next"

    // ============================== Recentes ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Busca ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (page == 1) {
            GET("$baseUrl/?s=$query", headers)
        } else {
            GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Detalhes ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.entry-title, h1.title, h1")?.text() ?: ""

        val genres = document.select("a[rel~=tag], .genres a, .category a").map { it.text() }
        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }

        anime.description = document.select(".entry-content p, .description p, .synopsis p")
            .joinToString("\n") { it.text() }
            .ifEmpty { document.selectFirst(".entry-content, .description")?.text() }

        val img = document.selectFirst(".poster img, .entry-content img, article img")
        anime.thumbnail_url = img?.attr("abs:data-src")
            ?.ifEmpty { img.attr("abs:data-lazy-src") }
            ?.ifEmpty { img.attr("abs:src") }

        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ============================== Episódios ==============================
    override fun episodeListSelector(): String = ".episodes-list a, .entry-content a[href*=/episodio], .list-episodes a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).map { episodeFromElement(it) }

        // Fallback caso a própria página já seja a publicação do episódio
        if (episodes.isEmpty()) {
            val singleEp = SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = document.selectFirst("h1.entry-title, h1.title")?.text() ?: "Episódio 1"
                episode_number = 1f
            }
            return listOf(singleEp)
        }

        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))

        val epText = element.text().ifEmpty { element.attr("title") }
        episode.name = epText.ifEmpty { "Episódio" }

        val epNumber = Regex("""(?i)(?:ep(?:isódio)?|e)\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toFloatOrNull()
        episode.episode_number = epNumber ?: 1f

        return episode
    }

    // ============================== Extração de Vídeos ==============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // 1. Extrai iFrames presentes na página do player
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("abs:data-src").ifEmpty { iframe.attr("abs:src") }
            if (src.isNotEmpty()) {
                videoList.addAll(videosFromUrl(src, headers))
            }
        }

        // 2. Extrai tags diretas <video> ou <source>
        document.select("video source[src], video[src]").forEach { element ->
            val src = element.attr("abs:src")
            if (src.isNotEmpty()) {
                val quality = element.attr("size").let { if (it.isNotEmpty()) "${it}p" else "Player Principal" }
                videoList.add(Video(src, quality, src, headers = headers))
            }
        }

        // 3. Fallback para links de reprodução direta (.mp4/.m3u8)
        if (videoList.isEmpty()) {
            document.select("a[href*=.mp4], a[href*=.m3u8]").forEach { link ->
                val href = link.attr("abs:href")
                if (href.isNotEmpty()) {
                    videoList.add(Video(href, "Link Direto", href, headers = headers))
                }
            }
        }

        return videoList
    }

    // Função síncrona (sem a palavra-chave 'suspend') para resolver o erro do Gradle
    private fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            if (url.contains(".mp4") || url.contains(".m3u8")) {
                videos.add(Video(url, "Player HD", url, headers = headers))
            } else {
                videos.add(Video(url, "Player Web", url, headers = headers))
            }
        } catch (e: Exception) {
            // Previne interrupções na lista caso alguma URL individual falhe
        }
        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Não utilizado")

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Não utilizado")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Não utilizado")
}
