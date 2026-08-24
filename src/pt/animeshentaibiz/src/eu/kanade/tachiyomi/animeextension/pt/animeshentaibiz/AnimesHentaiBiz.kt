package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesHentaiBiz : ParsedAnimeHttpSource() {

    override val name = "AnimesHentaiBiz"
    override val baseUrl = "https://animeshentai.biz"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.item.tvshows, article.item.movies"

    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/hentai/" else "$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        title = element.selectFirst("h3")?.text()
            ?: element.selectFirst("span")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            ?.ifEmpty { element.selectFirst("img")?.attr("abs:data-src") }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination a.next, div.nav-links a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = "article.item.se.episodes"

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/episodio/" else "$baseUrl/episodio/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query", headers)

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.poster img")?.attr("abs:src")
        genre = document.select("div.sgeneros a").joinToString { it.text() }
        description = document.selectFirst("div.wp-content p")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episodios li"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        name = element.selectFirst("div.episodiotitle a")?.text()
            ?: element.selectFirst("a")?.text() ?: "Episódio"
        episode_number = REGEX_EPISODE.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoNextPageSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("iframe[src*='blogger.com'], iframe[data-src*='blogger.com']")

        val videosFromIframes = players.parallelCatchingFlatMapBlocking { iframe ->
            val url = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if ("blogger.com" in url) {
                bloggerExtractor.videosFromUrl(url, headers)
            } else {
                emptyList()
            }
        }

        if (videosFromIframes.isNotEmpty()) return videosFromIframes

        // Fallback para player AJAX do tema DooPlay
        return document.select("ul#playeroptionsul li")
            .parallelCatchingFlatMapBlocking { option ->
                val post = option.attr("data-post")
                val type = option.attr("data-type")
                val nume = option.attr("data-nume")
                if (post.isNotEmpty() && nume.isNotEmpty()) {
                    val body = FormBody.Builder()
                        .add("action", "doo_player_ajax")
                        .add("post", post)
                        .add("type", type)
                        .add("nume", nume)
                        .build()
                    val req = POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)
                    val res = client.newCall(req).execute().body.string()
                    val embedUrl = REGEX_EMBED.find(res)?.groupValues?.get(1) ?: ""
                    if ("blogger.com" in embedUrl) {
                        bloggerExtractor.videosFromUrl(embedUrl, headers)
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(quality.lowercase()) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_EPISODE by lazy { Regex("""(\d+)""") }
        private val REGEX_EMBED by lazy { Regex("""src=["']([^"']+)["']""") }
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
    }
}
