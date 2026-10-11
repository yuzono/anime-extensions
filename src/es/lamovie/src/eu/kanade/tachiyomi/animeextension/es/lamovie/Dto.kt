package eu.kanade.tachiyomi.animeextension.es.lamovie

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ItemsDto(
    val items: List<ItemDto>,
    val pagination: PaginationDto? = null,
)

@Serializable
class PaginationDto(
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
class ItemResponseDto(
    val item: ItemDto,
)

@Serializable
class ItemDto(
    @SerialName("tmdb_id") private val tmdbId: Long,
    private val kind: String,
    private val title: String,
    @SerialName("original_title") private val originalTitle: String? = null,
    @SerialName("poster_path") private val posterPath: String? = null,
    private val overview: String? = null,
    private val tagline: String? = null,
    private val status: String? = null,
    private val genres: List<GenreDto> = emptyList(),
) {
    fun toSAnime() = SAnime.create().apply {
        url = "/$kind/$tmdbId"
        title = this@ItemDto.title
        thumbnail_url = posterPath?.let { "$TMDB_IMAGE_BASE$it" }
        genre = genres.joinToString { it.title }.ifEmpty { null }
        description = buildString {
            overview?.takeIf { it.isNotBlank() }?.let(::append)
            tagline?.takeIf { it.isNotBlank() }?.let { append("\n\n\"$it\"") }
            originalTitle?.takeIf { it.isNotBlank() && it != this@ItemDto.title }?.let {
                append("\n\nTítulo original: $it")
            }
        }.ifEmpty { null }
        status = when {
            kind == LaMovie.KIND_MOVIE -> SAnime.COMPLETED
            this@ItemDto.status == "Ended" -> SAnime.COMPLETED
            this@ItemDto.status == "Returning Series" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    }
}

@Serializable
class GenreDto(
    val title: String,
)

@Serializable
class SeasonsDto(
    val seasons: List<SeasonSummaryDto>,
)

@Serializable
class SeasonSummaryDto(
    val season: Int,
)

@Serializable
class SeasonResponseDto(
    val season: SeasonDto,
)

@Serializable
class SeasonDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    val season: Int,
    val episode: Int,
    private val title: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val playable: Boolean = true,
) {
    val name: String
        get() = "T${season}x$episode - ${title?.takeIf { it.isNotBlank() } ?: "Episodio $episode"}"
}

@Serializable
class PlaybackDto(
    val embeds: List<EmbedItem> = emptyList(),
)

@Serializable
class EmbedItem(
    val server: String = "",
    val url: String,
    val quality: String? = null,
    @SerialName("lang") val language: String? = null,
)

@Serializable
class EmbedConfigDto(
    val file: String? = null,
    val subtitle: String? = null,
)
