package eu.kanade.tachiyomi.animeextension.pt.animesfhd

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.AniSource
import keiyoushi.source.model.Related
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class AnimesFHD : AniSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Catalog ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = seriesList(page, "popular")

    override suspend fun getLatestUpdates(page: Int): AnimesPage = seriesList(page, "new_episodes")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = seriesList(page, "popular", query.trim())

    private suspend fun seriesList(page: Int, sort: String, query: String = ""): AnimesPage {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("pagination", "1")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
        if (query.isNotBlank()) url.addQueryParameter("q", query)
        val dto = client.get(url.build().toString(), headers).parseAs<SeriesPageDto>()
        return AnimesPage(dto.series.map { it.toSAnime() }, dto.hasMore)
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/serie/${anime.url}"

    /** Details and episodes are fetched only when asked: Anikku requests them separately. */
    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        val details = if (fetchDetails) seriesDetail(anime.url).let { it.series.toSAnime(it.seo?.heading) } else anime
        val episodeList = if (fetchEpisodes) fetchEpisodes(anime.url) else episodes
        return SAnimeUpdate(details, episodeList)
    }

    private suspend fun seriesDetail(seriesId: String): SeriesDetailDto = client.get("$baseUrl/api/series/$seriesId?per_page=1", headers).parseAs()

    // ============================== Related ===============================

    override val hasRelatedAnime = true

    /** The site's other seasons and audio versions of this title, then the rest of its franchise. */
    override suspend fun fetchRelatedAnime(anime: SAnime): List<Related> {
        val dto = seriesDetail(anime.url)
        val current = anime.url
        val versions = dto.versions
            .filter { it.seriesId.toString() != current }
            .map { it.toSAnime() }
        val franchise = dto.collection
            .filter { it.id.toString() != current }
            .map { it.toSAnime() }
        return listOfNotNull(
            versions.takeIf { it.isNotEmpty() }?.let { Related("Temporadas e versões", it) },
            franchise.takeIf { it.isNotEmpty() }?.let { Related("Da mesma franquia", it) },
        )
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode) = "$baseUrl/episod/${episode.url}"

    /**
     * Fetches every episode page of the series. If the result has fewer episodes than the
     * count reported by the API (an episode was added while paging, shifting the offsets),
     * the listing is read once more and both passes are merged.
     */
    private suspend fun fetchEpisodes(seriesId: String): List<SEpisode> {
        val episodes = LinkedHashMap<Long, EpisodeDto>()
        var attempts = 0
        do {
            val expected = fetchEpisodePages(seriesId, episodes)
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

    // ============================== Videos ================================

    /** Returns a single hoster with the episode's direct video sources and its subtitle, if any. */
    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
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

    /** The videos are already resolved in [fetchHosterList]. */
    override suspend fun fetchVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

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
