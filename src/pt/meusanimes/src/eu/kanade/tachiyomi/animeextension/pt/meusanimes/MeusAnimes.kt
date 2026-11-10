package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder

@Source
abstract class MeusAnimes : AnimeHttpLegacySource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Popular
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(".top10_ani a")
            .map { element ->
                SAnime.create().apply {
                    title = element.select(".top10_ani_title").text()
                    url = element.attr("href")
                    thumbnail_url = element.selectFirst("img")
                        ?.attr("abs:src")
                        ?: ""
                }
            }

        return AnimesPage(animes, hasNextPage = false)
    }

    // Latest updates use same parsing as popular
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val episodes = doc.select(".sliderIndex_002 .ultEpsContainerItem a")

        val animes = episodes.map { element ->
            SAnime.create().apply {
                val epNum = element.selectFirst(".epNum")!!.text()
                val epName = element.selectFirst(".epNome")!!.text()

                title = "$epName - $epNum"
                url = element.attr("href")
                thumbnail_url = element.selectFirst("img")
                    ?.attr("abs:src")

                initialized = false
            }
        }

        return AnimesPage(animes, false)
    }

    // Search
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/lista-de-animes?page=$page&search=$q", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(".ultAnisContainerItem a").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".aniNome")?.text() ?: element.attr("title")
                url = element.attr("href")
                thumbnail_url = element.selectFirst(".aniImg img")
                    ?.attr("abs:src")
                    ?: ""
                initialized = false
            }
        }

        val hasNextPage = document.selectFirst(".paginacao a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // No filters implemented
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // Main anime details parser
    override fun animeDetailsParse(response: Response): SAnime {
        val document = getRealAnimeDoc(response.asJsoup())

        return SAnime.create().apply {
            title = document.selectFirst(".anime_titulo")!!.text()
            description = document.selectFirst("#sinopse_content")?.text()
            thumbnail_url = document.selectFirst(".anime_thumb_left > img")?.attr("src")

            val statusString = document.selectFirst(".anime_status > span:last-child")?.text()
            status = when (statusString?.lowercase()) {
                "completo" -> SAnime.COMPLETED
                "em lançamento" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }

            initialized = true
        }
    }

    // Episodes: Parse episode list from JSON data
    private fun extractEpisodeData(response: Response): List<Episode> {
        val animeUrl = getRealAnimeUrl(response)

        val newHeaders = headers.newBuilder()
            .set("x-requested-with", "XMLHttpRequest")
            .set("Referer", baseUrl)
            .build()

        return client.newCall(GET("$animeUrl/data", newHeaders)).execute()
            .parseAs<Anime>()
            .data
            .episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = extractEpisodeData(response)

        return episodes
            .map { episode ->
                SEpisode.create().apply {
                    name = "Temporada ${episode.season} x ${episode.number}"
                    episode_number = episode.number.toFloat()
                    url = episode.url
                    scanlator = "Temporada ${episode.season}"
                }
            }.reversed()
    }

    // Videos: Video list request
    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    // Parse video list from episode page
    override fun videoListParse(response: Response): List<Video> {
        val m3u8Integration by lazy { M3u8Integration(client) }

        val document = response.asJsoup()
        val players = document.select(".abaPlayer")

        val videos = when {
            players.isEmpty() -> {
                val url = document.selectFirst("iframe")?.attr("src") ?: return emptyList()
                getVideos(url)
            }
            else -> {
                players.parallelCatchingFlatMapBlocking { player ->
                    val url = player.attr("data-url").ifEmpty { return@parallelCatchingFlatMapBlocking emptyList() }
                    val prefix = player.text().lowercase().replaceFirstChar { it.uppercase() }

                    getVideos(url, prefix)
                }
            }
        }

        return m3u8Integration.processVideoList(videos)
    }

    private fun getVideos(url: String, prefix: String = "MeusAnimes"): List<Video> {
        val playlistUtils by lazy { PlaylistUtils(client, headers) }

        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let { Unpacker.unpack(it) } ?: return emptyList()

        val playlistPath = script
            .substringAfter("\"videoUrl\":\"")
            .substringBefore("\"")
            .replace("\\", "")

        val playlistUrl = "https://${url.toHttpUrl().host}$playlistPath"

        return playlistUtils.extractFromHls(playlistUrl, videoNameGen = { quality -> "$prefix - $quality" })
    }

    // Utilities
    private val animeMenuSelector = ".episodio_controles_players span#lista_ep"

    private fun getRealAnimeUrl(response: Response): String {
        val url = response.request.url.toString()
        return if ("/ep/" in url) {
            response.asJsoup()
                .selectFirst(animeMenuSelector)!!
                .parent()!!
                .attr("abs:href")
        } else {
            url
        }
    }

    private fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        return if (menu != null) {
            val animeUrl = menu.parent()!!.attr("abs:href")
            val req = client.newCall(GET(animeUrl, headers)).execute()
            req.asJsoup()
        } else {
            document
        }
    }
}
