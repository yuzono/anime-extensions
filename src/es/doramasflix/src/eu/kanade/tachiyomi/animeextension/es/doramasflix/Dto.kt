package eu.kanade.tachiyomi.animeextension.es.doramasflix

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ----------------------- Server action requests ------------------------//

@Serializable
class PaginationRequest(
    val page: Int,
    val limit: Int,
    val sort: String,
    val filter: FilterRequest,
    val brandHost: String,
)

@Serializable
class MoviesRequest(
    val limit: Int,
    val sort: String,
    val filter: FilterRequest,
    val brandHost: String,
)

@Serializable
class FilterRequest(
    val isTVShow: Boolean? = null,
)

@Serializable
class SearchRequest(
    val q: String,
    val limit: Int,
)

@Serializable
class EpisodesRequest(
    @SerialName("serie_id") val serieId: String,
    @SerialName("season_number") val seasonNumber: Int,
    val page: Int,
    val limit: Int,
    val sort: String,
    val brandHost: String,
)

@Serializable
class EpisodeLinksRequest(
    @SerialName("episode_id") val episodeId: String,
)

@Serializable
class MovieLinksRequest(
    @SerialName("movie_id") val movieId: String,
)

// ----------------------- Listings ------------------------//

@Serializable
class PaginationDto(
    val pageInfo: PageInfoDto,
    val items: List<MediaDto>,
)

@Serializable
class PageInfoDto(
    val hasNextPage: Boolean,
)

@Serializable
class SearchDto(
    val data: SearchDataDto,
)

@Serializable
class SearchDataDto(
    val doramas: List<MediaDto>,
    val movies: List<MediaDto>,
)

@Serializable
class MediaDto(
    private val slug: String,
    private val name: String,
    @SerialName("name_es") private val nameEs: String? = null,
    @SerialName("poster_path") private val posterPath: String? = null,
    private val poster: String? = null,
) {
    fun toSAnime(path: String) = SAnime.create().apply {
        title = if (nameEs.isNullOrEmpty() || nameEs == name) name else "$name ($nameEs)"
        thumbnail_url = (posterPath?.takeIf { it.isNotEmpty() } ?: poster?.takeIf { it.isNotEmpty() })
            ?.let { if (it.startsWith("http")) it else "$TMDB_IMAGE_URL$it" }
        url = "/$path/$slug"
    }

    companion object {
        private const val TMDB_IMAGE_URL = "https://image.tmdb.org/t/p/w500"
    }
}

// ----------------------- Details and episodes ------------------------//

@Serializable
class SeriesDto(
    @SerialName("serie_id") val serieId: String,
    val seasons: List<SeasonDto>,
)

@Serializable
class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int,
)

@Serializable
class EpisodesDto(
    val items: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    @SerialName("_id") val id: String,
    val slug: String,
    @SerialName("episode_number") val episodeNumber: Float,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
class MoviePageDto(
    val movie: IdDto,
)

@Serializable
class EpisodePageDto(
    val episode: IdDto,
)

@Serializable
class IdDto(
    @SerialName("_id") val id: String,
)

// ----------------------- Videos ------------------------//

@Serializable
class LinkDto(
    val server: String,
    val lang: String? = null,
    val link: String,
)

@Serializable
class EmbedTokenDto(
    val link: String,
)
