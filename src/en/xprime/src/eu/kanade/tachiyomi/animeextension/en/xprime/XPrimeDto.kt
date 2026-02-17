package eu.kanade.tachiyomi.animeextension.en.xprime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================== TMDB DTOs ===============================
@Serializable
data class PageDto<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
data class MediaItemDto(
    val id: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null, // For Movie
    val name: String? = null, // For TV
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"
}

@Serializable
data class ExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
data class GenreDto(val name: String)

@Serializable
data class CompanyDto(val name: String)

@Serializable
data class NetworkDto(val name: String)

// ============================= Movie Detail =============================
@Serializable
data class MovieDetailDto(
    val id: Int,
    val title: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Float = 0f,
    @SerialName("production_companies") val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("origin_country") val countries: List<String>? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
    val runtime: Int? = null,
)

// ============================== TV Detail ===============================
@Serializable
data class TvDetailDto(
    val id: Int,
    val name: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val networks: List<NetworkDto> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("vote_average") val voteAverage: Float = 0f,
    @SerialName("origin_country") val countries: List<String>? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
)

@Serializable
data class SeasonDto(
    val id: Int,
    val name: String,
    @SerialName("season_number") val seasonNumber: Int,
)

// =========================== TV Season Detail ===========================
@Serializable
data class TvSeasonDetailDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val name: String,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("air_date") val airDate: String? = null,
)

// ============================ Xprime Decryption ===========================
@Serializable
data class XprimeDecryptionDto(
    val result: DecryptedResultDto,
)

@Serializable
data class DecryptedResultDto(
    // For single HLS playlist responses (fox, primenet, primesrc, etc.)
    val url: String? = null,

    // For multi-quality stream responses (primebox)
    val streams: Map<String, String>? = null,

    // For all response types that include subtitles
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class SubtitleDto(
    @SerialName("file") val url: String,
    @SerialName("label") val language: String,
)

// ============================ Xprime Servers ============================
@Serializable
data class ServerListDto(
    val servers: List<ServerDto>,
)

@Serializable
data class ServerDto(
    val name: String,
)
