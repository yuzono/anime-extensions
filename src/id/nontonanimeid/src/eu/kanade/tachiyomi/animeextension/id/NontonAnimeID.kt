package eu.kanade.tachiyomi.animeextension.id.nontonanimeid

import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class NontonAnimeID : AnimeHttpSource() {

    override val name = "Nonton Anime ID"
    override val baseUrl = "https://s11.nontonanimeid.boats"
    override val lang = "id"
    override val supportsLatest = true

    override val headers: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0")
        .add("Referer", baseUrl)
        .build()

    // ==============================
    // POPULAR
    // ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/popular-series/page/$page/", headers)
    }

    override fun popularAnimeSelector(): String {
        return ".as-anime-card"
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.selectFirst(".as-anime-title")?.text() ?: ""
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url =
            element.selectFirst("img")?.attr("abs:src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // ==============================
    // LATEST
    // ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/ongoing-list/page/$page/", headers)
    }

    override fun latestUpdatesSelector(): String {
        return ".latestepisodes li a"
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.selectFirst(".lefts")?.text() ?: ""
        anime.setUrlWithoutDomain(element.attr("href"))
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // ==============================
    // SEARCH
    // ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query", headers)
    }

    override fun searchAnimeSelector(): String {
        return ".as-anime-card"
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // ==============================
    // DETAILS
    // ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst("h1.entry-title")?.text() ?: ""

        anime.thumbnail_url =
            document.selectFirst(".featuredimgs img")?.attr("abs:src")

        anime.description =
            document.selectFirst(".tagpst p")?.text()

        anime.genre =
            document.select(".as-genre-tag")
                .joinToString { it.text() }

        return anime
    }

    // ==============================
    // EPISODES
    // ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    override fun episodeListParse(response: okhttp3.Response): List<SEpisode> {
        val document = response.asObservableSuccess().blockingFirst().body!!.string()
        val doc = org.jsoup.Jsoup.parse(document)

        val episodes = mutableListOf<SEpisode>()

        doc.select("#navigation-episode a[title]")
            .forEach { element ->
                val ep = SEpisode.create()
                ep.name = element.attr("title")
                ep.setUrlWithoutDomain(element.attr("href"))
                episodes.add(ep)
            }

        return episodes.reversed()
    }

    // ==============================
    // VIDEO
    // ==============================

    override fun videoListParse(document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        val iframe = document.selectFirst("#videoku iframe")
        val videoUrl = iframe?.attr("data-src")

        if (!videoUrl.isNullOrBlank()) {
            videos.add(
                Video(
                    videoUrl,
                    "KotakAnime Server",
                    videoUrl
                )
            )
        }

        return videos
    }
}
