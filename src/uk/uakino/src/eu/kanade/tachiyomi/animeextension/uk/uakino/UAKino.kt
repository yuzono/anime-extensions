package eu.kanade.tachiyomi.animeextension.uk.uakino

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UAKino : ParsedAnimeHttpSource() {

    override val lang = "uk"
    override val name = "UAKino"
    override val supportsLatest = true
    private val animeSelector = "div.movie-item"
    private val nextPageSelector = "a:contains(Далі)"

    override val baseUrl = "https://uakino.best"
    private val animeUrl = "/animeukr"
    private val popularUrl = "/f/c.year=1921,2026/sort=rating;desc"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("h1 span.solototle").text()

        val posterUrl = document.select("a[data-fancybox=gallery]").attr("abs:href")
        thumbnail_url = UrlUtils.fixUrl(posterUrl, baseUrl)
        description = document.select("div.full-text[itemprop=description]").text()
    }

    // ============================== Popular ===============================

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.movie-title").attr("href"))
        title = element.select("a.movie-title").text()

        val posterUrl = element.select("div.movie-img img").attr("abs:src")
        thumbnail_url = UrlUtils.fixUrl(posterUrl, baseUrl)
    }

    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl$animeUrl$popularUrl/page/$page")

    override fun popularAnimeSelector(): String = animeSelector

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl + animeUrl)

    override fun latestUpdatesSelector() = animeSelector

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", query)
            .build()

        return POST(
            "$baseUrl/ua/",
            headers = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .set("User-Agent", "Mozilla/5.0")
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            body = body,
        )
    }

    override fun searchAnimeSelector() = "div.movie-item.short-item"

    // ============================== Episode ===============================

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animePage = response.asJsoup()

        // Get ID title
        val titleID = animePage.selectFirst("input[id=post_id]")
            ?.attr("value")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("engine")
            addPathSegment("ajax")
            addPathSegment("playlists.php")
            addQueryParameter("news_id", titleID)
            addQueryParameter("xfield", "playlist")
        }.build().toString()

        // Do call
        val episodesList = client.newCall(
            GET(
                url,
                headers = Headers.Builder()
                    .set("Referer", "$baseUrl/")
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("User-Agent", "Mozilla/5.0")
                    .build(),
            ),
        )
            .execute()
            .bodyString()

        // Parse JSON
        val parsed = try {
            JSONTokener(episodesList).nextValue()
        } catch (e: Exception) {
            Log.e("UAKino", "JSON parse error: ${e.message}")
            return emptyList()
        }

        if (parsed !is JSONObject) {
            Log.e("UAKino", "Invalid JSON response")
            return emptyList()
        }

        // List episodes
        val episodeList = mutableListOf<SEpisode>()

        // If "success" is false - is not anime serial (or another player)
        if (parsed.optBoolean("success")) {
            Jsoup.parse(parsed.getString("response")).select("div.playlists-videos li").forEach {
                val episodeUrl = UrlUtils.fixUrl(it.attr("data-file"), baseUrl) ?: return@forEach
                val episode = SEpisode.create().apply {
                    this.url = episodeUrl
                    name = it.text() + " " + it.attr("data-voice")
                }
                episodeList.add(episode)
            }
        } else {
            val playerUrl = animePage.select("iframe#pre").attr("src")
            // Another player
            if (playerUrl.contains("/serial/")) {
                val playerScript = client.newCall(GET(playerUrl))
                    .execute()
                    .useAsJsoup()
                    .select("script")
                    .html()

                // Get m3u8 url
                val m3u8JSONString = regexM3u8.find(playerScript)?.groupValues?.getOrNull(1)
                    ?: return emptyList()
                val episodesJSON = m3u8JSONString.parseAs<List<AshdiModel>>()
                for (itemVoice in episodesJSON) { // Voice
                    for (itemSeason in itemVoice.folder) { // Season
                        for (itemVideo in itemSeason.folder) { // Video
                            val episode = SEpisode.create().apply {
                                name = "${itemSeason.title} ${itemVideo.title} ${itemVoice.title}" // "Сезон 1 Серія 1 Озвучення"
                                this.url = itemVideo.file
                            }
                            episodeList.add(episode)
                        }
                    }
                }
            } else { // Search as one video
                val episode = SEpisode.create().apply {
                    this.url = playerUrl
                    name = animePage.select("span.solototle").text() + " Фільм"
                }
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video ===============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        var m3u8Episode = episode.url
        if (!episode.url.contains(".m3u8")) { // If not from another player
            // Get player script
            val document = client.newCall(GET(episode.url))
                .awaitSuccess()
                .useAsJsoup()

            val scripts = document.select("script")

            // Get m3u8 url
            for (script in scripts) {
                val data = script.data()
                val match = regexM3u8.find(data)
                if (match != null) {
                    m3u8Episode = match.groupValues[1]
                    break
                }
            }
        }

        // Parse m3u (480p/720p/1080p)
        // GET Calll m3u8 url
        if (!m3u8Episode.contains(".m3u8")) {
            return emptyList()
        }

        return playlistUtils.extractFromHls(
            m3u8Episode,
            referer = baseUrl,
        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private val regexM3u8 by lazy { Regex("""file\s*:\s*["']([^"']+)["']""") }
    }
}
