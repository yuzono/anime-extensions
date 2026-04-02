package eu.kanade.tachiyomi.animeextension.all.slothanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class SlothAnimes : AnimeHttpSource() {
    override val name = "Sloth Animes"
    override val baseUrl = "https://graphql.anilist.co"
    override val lang = "all"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = postJson("""query { Page(page: $page, perPage: 24) { media(type: ANIME, sort: TRENDING_DESC) { id title { romaji english } coverImage { large } } } }""")
    override fun popularAnimeParse(response: Response) = parseAniListJson(response)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = postJson("""query { Page(page: $page, perPage: 24) { media(search: "$query", type: ANIME) { id title { romaji english } coverImage { large } } } }""")
    override fun searchAnimeParse(response: Response) = parseAniListJson(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Media")
        val id = media.getInt("id")
        val episodes = media.optInt("episodes", 1)
        return (1..episodes).map { i -> SEpisode.create().apply { url = "$id|$i"; name = "Episode $i"; episode_number = i.toFloat() } }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val parts = response.request.url.toString().substringAfterLast("/").split("|")
        val id = parts[0]
        val ep = parts[1]
        return listOf(
            Video("https://megaplay.buzz/stream/ani/$id/$ep/sub", "MegaPlay", "https://megaplay.buzz/stream/ani/$id/$ep/sub"),
            Video("https://vidsrc.cc/v2/embed/anime/ani$id/$ep/sub", "VidSrc", "https://vidsrc.cc/v2/embed/anime/ani$id/$ep/sub")
        )
    }

    private fun postJson(query: String): Request = Request.Builder().url(baseUrl).post(okhttp3.RequestBody.create(null, JSONObject().put("query", query).toString())).header("Content-Type", "application/json").build()

    private fun parseAniListJson(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage {
        val data = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Page")
        val media = data.getJSONArray("media")
        val results = (0 until media.length()).map { i ->
            val item = media.getJSONObject(i)
            SAnime.create().apply {
                title = item.getJSONObject("title").run { optString("english").takeIf { it.isNotBlank() } ?: getString("romaji") }
                thumbnail_url = item.getJSONObject("coverImage").getString("large")
                url = item.getInt("id").toString()
            }
        }
        return eu.kanade.tachiyomi.animesource.model.AnimesPage(results, data.has("pageInfo"))
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun animeDetailsParse(response: Response) = SAnime.create()
}
