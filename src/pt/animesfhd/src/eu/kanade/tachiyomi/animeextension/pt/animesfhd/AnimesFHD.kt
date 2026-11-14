package eu.kanade.tachiyomi.animeextension.pt.animesfhd

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class AnimesFHD : AnimeHttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun seriesListRequest(page: Int, sort: String, query: String = ""): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("pagination", "1")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
        if (query.isNotBlank()) url.addQueryParameter("q", query)
        return GET(url.build(), headers)
    }

    private fun seriesListParse(response: Response): AnimesPage {
        val page = response.parseAs<SeriesPageDto>()
        return AnimesPage(page.series.map { it.toSAnime() }, page.hasMore)
    }

    override fun popularAnimeRequest(page: Int) = seriesListRequest(page, "popular")

    override fun popularAnimeParse(response: Response) = seriesListParse(response)

    override fun latestUpdatesRequest(page: Int) = seriesListRequest(page, "new_episodes")

    override fun latestUpdatesParse(response: Response) = seriesListParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = seriesListRequest(page, "popular", query.trim())

    override fun searchAnimeParse(response: Response) = seriesListParse(response)

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/serie/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime) = GET("$baseUrl/api/series/${anime.url}?per_page=1", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<SeriesDetailDto>()
        return dto.series.toSAnime(dto.seo?.heading).apply { initialized = true }
    }

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<EpisodeDto>()
        var page = 1
        do {
            val url = "$baseUrl/api/series/${anime.url}?page=$page&per_page=$EPISODES_PAGE_SIZE"
            val dto = client.newCall(GET(url, headers)).awaitSuccess().parseAs<SeriesDetailDto>()
            episodes += dto.episodes
            page++
        } while (dto.hasMore && dto.episodes.isNotEmpty() && page <= MAX_EPISODE_PAGES)

        return episodes
            .distinctBy { it.id }
            .sortedByDescending { it.episodeNumber }
            .map { ep ->
                SEpisode.create().apply {
                    url = ep.id.toString()
                    episode_number = ep.episodeNumber
                    val number = ep.episodeNumber.toString().removeSuffix(".0")
                    name = ep.title?.takeIf { it.isNotBlank() }
                        ?.let { "Episódio $number - $it" }
                        ?: "Episódio $number"
                    date_upload = dateFormat.tryParse(ep.airedAt?.takeIf { it.isNotBlank() } ?: ep.createdAt)
                }
            }
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode) = "$baseUrl/episod/${episode.url}"

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val dto = client.newCall(GET("$baseUrl/api/episodes/${episode.url}", headers))
            .awaitSuccess()
            .parseAs<EpisodeDetailDto>()

        val subtitles = dto.episode.subtitleUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(Track(absolute(it), "Português")) }
            ?: emptyList()

        val videos = dto.sources
            .filter { it.nativeVideo && it.url.isNotBlank() }
            .map { source ->
                Video(
                    videoUrl = absolute(source.url),
                    videoTitle = listOfNotNull(
                        source.label?.takeIf { it.isNotBlank() } ?: "Player",
                        source.audio?.replaceFirstChar { it.uppercase() },
                    ).joinToString(" - "),
                    headers = headers,
                    subtitleTracks = subtitles,
                )
            }

        if (videos.isEmpty()) return emptyList()
        return listOf(Hoster(hosterUrl = "", hosterName = "AnimesFHD", videoList = videos))
    }

    override fun hosterListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override fun videoListParse(response: Response, hoster: Hoster) = throw UnsupportedOperationException()

    private fun absolute(url: String) = if (url.startsWith("http")) url else "$baseUrl$url"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val EPISODES_PAGE_SIZE = 200
        private const val MAX_EPISODE_PAGES = 20
    }
}
