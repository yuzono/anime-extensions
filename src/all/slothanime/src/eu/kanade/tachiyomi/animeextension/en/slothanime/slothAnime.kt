package eu.kanade.tachiyomi.animeextension.all.slothanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class SlothAnime : AnimeHttpSource() {

    override val name = "slothanime"
    override val baseUrl = "https://graphql.anilist.co"
    override val lang = "all"
    override val supportsLatest = true

    // ============================== POPULAR / TRENDING ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val query = """
            query {
              Page(page: $page, perPage: 24) {
                pageInfo { hasNextPage }
                media(type: ANIME, sort: TRENDING_DESC, status_in: [RELEASING, FINISHED]) {
                  id
                  title { romaji english }
                  coverImage { large }
                  format
                }
              }
            }
        """.trimIndent()
        return aniListPost(query)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAniListPage(response)

    // =============================== SEARCH ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val gqlQuery = """
            query {
              Page(page: $page, perPage: 50) {
                pageInfo { hasNextPage }
                media(search: "$query", type: ANIME) {
                  id
                  title { romaji english }
                  coverImage { large }
                  format
                }
              }
            }
        """.trimIndent()
        return aniListPost(gqlQuery)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAniListPage(response)

    // ============================== EPISODES ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val query = """
            query {
              Media(id: ${anime.url}) {
                id
                episodes
                nextAiringEpisode { episode }
              }
            }
        """.trimIndent()
        return aniListPost(query)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Media")
        val nextAiring = media.optJSONObject("nextAiringEpisode")
        
        val totalEp = if (nextAiring != null) {
            nextAiring.getInt("episode") - 1
        } else {
            media.optInt("episodes", 1)
        }

        return (1..totalEp).map { i ->
            SEpisode.create().apply {
                url = "${media.getInt("id")}|$i"
                name = "Episode $i"
                episode_number = i.toFloat()
            }
        }.reversed()
    }

    // ============================ VIDEO LINKS =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|")
        return Request.Builder()
            .url("https://vidsrc.cc/v2/embed/anime/ani${parts[0]}/${parts[1]}/sub")
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return listOf(Video(url, "VidSrc (External)", url))
    }

    // ============================= UTILITIES ==============================
    private fun aniListPost(query: String): Request {
        val json = JSONObject().put("query", query)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl)
            .post(body)
            .build()
    }

    private fun parseAniListPage(response: Response): AnimesPage {
        val data = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Page")
        val mediaArray = data.getJSONArray("media")
        val animeList = mutableListOf<SAnime>()

        for (i in 0 until mediaArray.length()) {
            val item = mediaArray.getJSONObject(i)
            animeList.add(SAnime.create().apply {
                val titles = item.getJSONObject("title")
                title = titles.optString("english").takeIf { it.isNotBlank() } ?: titles.getString("romaji")
                thumbnail_url = item.getJSONObject("coverImage").getString("large")
                url = item.getInt("id").toString()
            })
        }
        return AnimesPage(animeList, data.getJSONObject("pageInfo").getBoolean("hasNextPage"))
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun animeDetailsParse(response: Response) = SAnime.create()
}
