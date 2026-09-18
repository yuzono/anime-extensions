package eu.kanade.tachiyomi.animeextension.id.oploverz

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class SeriesListResponseDto(
    private val data: List<SeriesDto>,
    private val meta: MetaDto,
) {
    fun toAnimesPage(): Pair<List<SAnime>, Boolean> = Pair(data.map { it.toSAnime() }, meta.hasNextPage)
}

@Serializable
class MetaDto(
    private val currentPage: Int,
    private val lastPage: Int,
) {
    val hasNextPage get() = currentPage < lastPage
}

@Serializable
class SeriesResponseDto(private val data: SeriesDto) {
    fun toSAnime(): SAnime = data.toSAnime()
}

@Serializable
class SeriesDto(
    private val slug: String,
    private val title: String,
    private val poster: String? = null,
    private val description: String? = null,
    private val status: String? = null,
    private val genres: List<GenreDto>? = null,
    private val studio: StudioDto? = null,
) {
    fun toSAnime(): SAnime {
        val anime = SAnime.create()
        anime.url = "/series/$slug"
        anime.title = title
        anime.thumbnail_url = poster
        anime.description = description
        anime.genre = genres?.joinToString { it.name }
        anime.author = studio?.name
        anime.status = when (status?.lowercase()) {
            "ongoing" -> SAnime.ONGOING
            "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        return anime
    }
}

@Serializable
class LatestEpisodesResponseDto(
    private val data: List<LatestEpisodeDto>,
    private val meta: MetaDto,
) {
    fun toAnimesPage(): Pair<List<SAnime>, Boolean> = Pair(
        data.map { it.series.toSAnime() }.distinctBy { it.url },
        meta.hasNextPage,
    )
}

@Serializable
class LatestEpisodeDto(val series: SeriesDto)

@Serializable
class GenreDto(val name: String)

@Serializable
class StudioDto(val name: String)

@Serializable
class EpisodeListResponseDto(private val data: List<EpisodeDto>) {
    fun toSEpisodeList(slug: String): List<SEpisode> = data.map { it.toSEpisode(slug) }
}

@Serializable
class EpisodeDto(
    private val episodeNumber: String,
    private val releasedAt: String? = null,
) {
    fun toSEpisode(slug: String): SEpisode {
        val episode = SEpisode.create()
        episode.url = "/series/$slug/episode/$episodeNumber"
        episode.name = "Episode $episodeNumber"
        episode.episode_number = episodeNumber.toFloatOrNull() ?: 1F
        episode.date_upload = DATE_FORMATTER.tryParse(releasedAt)
        return episode
    }

    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}

@Serializable
class EpisodeDetailResponseDto(private val data: EpisodeDetailDto) {
    val streams get() = data.streamUrl ?: emptyList()
}

@Serializable
class EpisodeDetailDto(val streamUrl: List<StreamDto>? = null)

@Serializable
class StreamDto(val source: String, val url: String)

@Serializable
class FiledonPageDto(private val props: FiledonPropsDto) {
    val videoUrl get() = props.url
}

@Serializable
class FiledonPropsDto(val url: String)
