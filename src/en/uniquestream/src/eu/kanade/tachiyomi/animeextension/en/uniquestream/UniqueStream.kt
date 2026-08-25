package eu.kanade.tachiyomi.animeextension.en.uniquestream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlin.math.ceil

class UniqueStream : AnimeHttpSource() {

    override val name = "UniqueStream"

    override val lang = "en"

    override val baseUrl = "https://anime.uniquestream.net"

    override val supportsLatest = true

    private val apiUrl get() = "$baseUrl/api/v1"

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/videos/popular?page=$page&limit=$PAGE_SIZE&type=all")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<List<BrowseItemDto>>().map { it.toSAnime() }
        return AnimesPage(animeList, animeList.size >= PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/videos/new?page=$page&limit=$PAGE_SIZE&type=all")

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
            addQueryParameter("t", "all")
            addQueryParameter("limit", PAGE_SIZE.toString())
        }
        return GET(url.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<SearchResponseDto>()
        val animeList = (result.series.orEmpty() + result.movies.orEmpty()).map { it.toSAnime() }
        // Totals stay constant on every page while lists paginate per type, so
        // comparing the total against the current page loops forever past the end.
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totals = result.totals
        val hasNextPage = if (totals == null) {
            animeList.size >= PAGE_SIZE
        } else {
            val lastPage = maxOf(
                ceil((totals.series ?: 0) / PAGE_SIZE.toDouble()),
                ceil((totals.movies ?: 0) / PAGE_SIZE.toDouble()),
            ).toInt()
            animeList.isNotEmpty() && page < lastPage
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(apiUrl + anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.parseAs<DetailsDto>()
        return SAnime.create().apply {
            title = details.title
            description = details.description
            genre = details.genre.orEmpty().joinToString { it.title }.ifBlank { null }
            author = details.studio
            thumbnail_url = details.images.orEmpty()
                .firstOrNull { it.type == "poster_tall" }?.url
                ?: details.images.orEmpty().firstOrNull()?.url
            status = when {
                details.seasons == null -> SAnime.COMPLETED // movies
                else -> SAnime.UNKNOWN // details API exposes no airing status
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val details = response.parseAs<DetailsDto>()

        if (details.seasons == null) {
            // Movies are streamed through the same media endpoint under /movie/.
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "movie/${details.contentId}|${details.audioLocales?.firstOrNull() ?: DEFAULT_AUDIO}"
                },
            )
        }

        val seasonPages = details.seasons.filter { it.episodeCount > 0 }
            .flatMap { season ->
                (1..ceil(season.episodeCount / PAGE_SIZE.toDouble()).toInt()).map { season to it }
            }

        return seasonPages.parallelFlatMapBlocking { (season, page) ->
            client.newCall(GET("$apiUrl/season/${season.contentId}/episodes?page=$page&limit=$PAGE_SIZE"))
                .awaitSuccess()
                .parseAs<List<EpisodeDto>>()
                .map { it.toEpisode(season.displayNumber) }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("|")
        require(parts.size == 2) { "Outdated episode entry, refresh the entry" }
        val (mediaPath, locale) = parts
        val media = client.newCall(
            GET("$apiUrl/$mediaPath/media/hls/$locale"),
        ).awaitSuccess().parseAs<MediaResponse>()

        val hls = requireNotNull(media.hls) { "No HLS media available for this episode" }
        val entries = mutableListOf(Pair(SUB_LABEL, hls))
        hls.hardSubs.orEmpty().forEach { sub ->
            entries.add(hardSubLabel(sub.locale) to sub)
        }
        media.versions.orEmpty().forEach { version ->
            val label = version.locale?.let { localeNames[it] } ?: "Dub"
            entries.add(label to version)
        }

        UniqueStreamHlsServer.setUp(client)

        val videos = entries.flatMap { (label, dto) ->
            val masterUrl = requireNotNull(dto.playlist) { "Missing playlist for $label" }
            val master = fetchText(masterUrl)
            VARIANT_REGEX.findAll(master).mapNotNull { match ->
                val height = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val variantUrl = masterUrl.toHttpUrl().resolve(match.groupValues[2])?.toString()
                    ?: return@mapNotNull null
                Video(
                    url = variantUrl,
                    quality = "$label - ${height}p",
                    videoUrl = UniqueStreamHlsServer.localPlaylistUrl(masterUrl, media.mediaId, height),
                )
            }
        }

        require(videos.isNotEmpty()) { "Failed to fetch videos" }

        return videos.sortedWith(
            compareByDescending<Video> { it.quality.startsWith(SUB_LABEL) }
                .thenByDescending { it.quality.substringAfterLast(" ").removeSuffix("p").toIntOrNull() ?: 0 },
        )
    }

    private fun fetchText(url: String): String = client.newCall(GET(url)).execute().use { response ->
        check(response.isSuccessful) { "Failed to fetch playlist: ${response.code}" }
        response.body.string()
    }

    private fun hardSubLabel(locale: String?): String = when (locale) {
        "en-US" -> "English Hard Sub"
        null -> "Hard Sub"
        else -> "${localeNames[locale] ?: locale} Hard Sub"
    }

    // ============================= Utilities ==============================

    @Serializable
    data class BrowseItemDto(
        @SerialName("content_id") val contentId: String,
        val title: String,
        val image: String? = null,
        val type: String,
        val subbed: Boolean? = null,
        val dubbed: Boolean? = null,
        val status: String? = null,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            val audio = listOfNotNull(
                if (subbed == true) "Subbed" else null,
                if (dubbed == true) "Dubbed" else null,
            ).joinToString()
            url = if (type == "movie") "/movie/$contentId" else "/series/$contentId"
            title = this@BrowseItemDto.title
            thumbnail_url = image
            genre = audio.ifBlank { null }
            status = when (this@BrowseItemDto.status) {
                "FINISHED", "RELEASED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    @Serializable
    data class SearchResponseDto(
        val series: List<BrowseItemDto>? = null,
        val movies: List<BrowseItemDto>? = null,
        val totals: TotalsDto? = null,
    ) {
        @Serializable
        data class TotalsDto(
            val series: Int? = null,
            val movies: Int? = null,
        )
    }

    @Serializable
    data class DetailsDto(
        @SerialName("content_id") val contentId: String,
        val title: String,
        val description: String? = null,
        val images: List<ImageDto>? = null,
        val studio: String? = null,
        val genre: List<GenreDto>? = null,
        val seasons: List<SeasonDto>? = null,
        @SerialName("audio_locales") val audioLocales: List<String>? = null,
    ) {
        @Serializable
        data class ImageDto(
            val url: String,
            val type: String? = null,
        )

        @Serializable
        data class GenreDto(
            val title: String,
        )
    }

    @Serializable
    data class SeasonDto(
        @SerialName("content_id") val contentId: String,
        val title: String? = null,
        @SerialName("display_number") val displayNumber: String? = null,
        @SerialName("episode_count") val episodeCount: Int,
    )

    @Serializable
    data class EpisodeDto(
        val title: String,
        val episode: String = "",
        @SerialName("episode_number") val episodeNumber: Double = 0.0,
        @SerialName("content_id") val contentId: String,
        @SerialName("audio_locales") val audioLocales: List<String>? = null,
    ) {
        fun toEpisode(seasonNumber: String?): SEpisode = SEpisode.create().apply {
            name = buildString {
                if (!seasonNumber.isNullOrBlank()) append("S$seasonNumber ")
                if (episode.isNotBlank()) append("E$episode - ")
                append(title)
            }
            episode_number = episodeNumber.toFloat()
            url = "episode/$contentId|${audioLocales?.firstOrNull() ?: DEFAULT_AUDIO}"
        }
    }

    @Serializable
    data class MediaResponse(
        @SerialName("media_id") val mediaId: String,
        val hls: HlsDto? = null,
        val versions: List<HlsDto>? = null,
    ) {
        @Serializable
        data class HlsDto(
            val locale: String? = null,
            val playlist: String? = null,
            @SerialName("hard_subs") val hardSubs: List<HlsDto>? = null,
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val DEFAULT_AUDIO = "ja-JP"
        private const val SUB_LABEL = "Sub"

        // Master playlists carry one line of metadata followed by the variant URL.
        private val VARIANT_REGEX = Regex("""RESOLUTION=\d+x(\d+)[^\n]*\n([^\n#]+\.m3u8[^\n]*)""")

        private val localeNames = mapOf(
            "en-US" to "Dub",
            "es-419" to "Spanish (LatAm)",
            "es-ES" to "Spanish (Spain)",
            "pt-BR" to "Portuguese",
            "fr-FR" to "French",
            "de-DE" to "German",
            "it-IT" to "Italian",
            "ar-SA" to "Arabic",
            "ru-RU" to "Russian",
            "hi-IN" to "Hindi",
            "ta-IN" to "Tamil",
        )
    }
}
