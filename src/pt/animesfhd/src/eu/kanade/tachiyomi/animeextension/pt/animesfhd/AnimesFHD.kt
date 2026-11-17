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
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.get
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

    /**
     * Fetches every episode page of the series. If the result has fewer episodes than the
     * count reported by the API (an episode was added while paging, shifting the offsets),
     * the listing is read once more and both passes are merged.
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = LinkedHashMap<Long, EpisodeDto>()
        var attempts = 0
        do {
            val expected = fetchEpisodePages(anime.url, episodes)
            attempts++
        } while (expected != null && episodes.size < expected && attempts < MAX_LISTING_ATTEMPTS)

        val dateFormats = DATE_PATTERNS.map { pattern ->
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        return episodes.values
            .sortedByDescending { it.episodeNumber }
            .map { ep ->
                SEpisode.create().apply {
                    url = ep.id.toString()
                    episode_number = ep.episodeNumber
                    val number = ep.episodeNumber.toString().removeSuffix(".0")
                    name = ep.title?.takeIf { it.isNotBlank() }
                        ?.let { "Episódio $number - $it" }
                        ?: "Episódio $number"
                    date_upload = parseDate(ep.airedAt, dateFormats).takeIf { it != 0L }
                        ?: parseDate(ep.createdAt, dateFormats)
                }
            }
    }

    /**
     * Reads pages until the API reports no more results, adding them to [into]. Stops early only
     * if a page repeats episodes already read in this pass, so a misbehaving API cannot loop forever.
     * Returns the episode count reported by the API, if any.
     */
    private suspend fun fetchEpisodePages(seriesId: String, into: MutableMap<Long, EpisodeDto>): Int? {
        val seenInPass = HashSet<Long>()
        var expected: Int? = null
        var page = 1
        do {
            val url = "$baseUrl/api/series/$seriesId?page=$page&per_page=$EPISODES_PAGE_SIZE"
            val dto = client.get(url, headers).parseAs<SeriesDetailDto>()
            if (expected == null) expected = dto.episodeCount
            val newInPass = dto.episodes.count { seenInPass.add(it.id) }
            dto.episodes.forEach { into.putIfAbsent(it.id, it) }
            page++
        } while (dto.hasMore && newInPass > 0)
        return expected
    }

    /**
     * Parses the dates sent by the API ("2025-03-01T12:00:00Z", "2025-03-01 12:00:00" or
     * "2025-03-01"). The formatters are created per call, so they are never shared between threads.
     */
    private fun parseDate(date: String?, formats: List<SimpleDateFormat>): Long {
        val value = date?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        return formats.firstNotNullOfOrNull { format -> format.tryParse(value).takeIf { it != 0L } } ?: 0L
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode) = "$baseUrl/episod/${episode.url}"

    /** Returns a single hoster with the episode's direct video sources and its subtitle, if any. */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val dto = client.get("$baseUrl/api/episodes/${episode.url}", headers)
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

    /** Resolves absolute, protocol-relative ("//cdn/...") and relative URLs against [baseUrl]. */
    private fun absolute(url: String): String = baseUrlHttp.resolve(url.trim())?.toString() ?: url

    private val baseUrlHttp by lazy { "$baseUrl/".toHttpUrl() }

    companion object {
        private const val PAGE_SIZE = 24
        private const val EPISODES_PAGE_SIZE = 200
        private const val MAX_LISTING_ATTEMPTS = 2

        private val DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
    }
}
