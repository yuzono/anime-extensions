package eu.kanade.tachiyomi.animeextension.es.pandrama

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChannelResponse(
    val pagination: Pagination,
) {
    @Serializable
    class Pagination(
        @SerialName("next_page") val nextPage: Int? = null,
        val data: List<TitleDto>,
    )
}

@Serializable
class TitleDto(
    private val id: Int,
    private val name: String,
    private val slug: String,
    private val poster: String? = null,
) {
    fun toSAnime() = SAnime.create().apply {
        title = name
        thumbnail_url = poster
        url = "/titles/$id/$slug"
    }
}

@Serializable
class TitleResponse(
    val title: TitleDetailsDto,
    @SerialName("available_seasons") val availableSeasons: List<Int> = emptyList(),
    val credits: CreditsDto? = null,
)

@Serializable
class TitleDetailsDto(
    private val description: String? = null,
    private val tagline: String? = null,
    private val poster: String? = null,
    private val status: String? = null,
    @SerialName("original_title") private val originalTitle: String? = null,
    @SerialName("alternative_title") private val alternativeTitle: String? = null,
    private val genres: List<GenreDto> = emptyList(),
) {
    @Serializable
    class GenreDto(
        @SerialName("display_name") val displayName: String,
    )

    fun toSAnime(credits: CreditsDto?): SAnime {
        val summary = listOfNotNull(
            description?.takeIf { it.isNotBlank() },
            tagline?.takeIf { it.isNotBlank() },
            originalTitle?.takeIf { it.isNotBlank() }?.let { "Título original: $it" },
            alternativeTitle?.takeIf { it.isNotBlank() }?.let { "Título alternativo: $it" },
        ).joinToString("\n\n")
        val directors = credits?.directing?.joinToString { it.name }
        val actors = credits?.actors?.take(MAX_ACTORS)?.joinToString { it.name }
        val animeStatus = when (status) {
            "ongoing" -> SAnime.ONGOING
            "ended" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        return SAnime.create().apply {
            thumbnail_url = poster
            genre = genres.joinToString { it.displayName }
            author = directors
            artist = actors
            status = animeStatus
            description = summary
        }
    }

    companion object {
        private const val MAX_ACTORS = 10
    }
}

@Serializable
class CreditsDto(
    val directing: List<PersonDto> = emptyList(),
    val actors: List<PersonDto> = emptyList(),
) {
    @Serializable
    class PersonDto(
        val name: String,
    )
}

@Serializable
class EpisodesResponse(
    val pagination: Pagination,
) {
    @Serializable
    class Pagination(
        @SerialName("next_page") val nextPage: Int? = null,
        val data: List<EpisodeDto>,
    )
}

@Serializable
class EpisodeDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
class EpisodeVideosResponse(
    val episode: EpisodeVideos,
) {
    @Serializable
    class EpisodeVideos(
        val videos: List<VideoDto> = emptyList(),
    )

    @Serializable
    class VideoDto(
        val src: String,
    )
}
