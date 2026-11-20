package eu.kanade.tachiyomi.animeextension.en.cinejoy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================== TMDB DTOs ===============================
@Serializable
class PageDto<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages")
    val totalPages: Int,
)

@Serializable
class MediaItemDto(
    val id: Int,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("first_air_date")
    val firstAirDate: String? = null,
    val popularity: Float = 0f,
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"

    // Comparable date used to merge movie and TV pages onto one ordering.
    val mediaSortDate: String
        get() = releaseDate ?: firstAirDate ?: ""
}

@Serializable
class ExternalIdsDto(
    @SerialName("imdb_id")
    val imdbId: String? = null,
)

@Serializable
class GenreDto(val name: String)

@Serializable
class CompanyDto(val name: String)

@Serializable
class NetworkDto(val name: String)

// ============================= Movie Detail =============================
@Serializable
class MovieDetailDto(
    val id: Int,
    val title: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("backdrop_path")
    val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
    @SerialName("production_companies")
    val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("origin_country")
    val countries: List<String>? = null,
    @SerialName("original_title")
    val originalTitle: String? = null,
    @SerialName("external_ids")
    val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
    val runtime: Int? = null,
)

// ============================== TV Detail ==============================
@Serializable
class TvDetailDto(
    val id: Int,
    val name: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("backdrop_path")
    val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("first_air_date")
    val firstAirDate: String? = null,
    @SerialName("last_air_date")
    val lastAirDate: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val networks: List<NetworkDto> = emptyList(),
    @SerialName("production_companies")
    val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
    @SerialName("origin_country")
    val countries: List<String>? = null,
    @SerialName("original_name")
    val originalName: String? = null,
    @SerialName("external_ids")
    val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
)

@Serializable
class SeasonDto(
    val id: Int,
    val name: String,
    @SerialName("season_number")
    val seasonNumber: Int,
    @SerialName("poster_path")
    val posterPath: String? = null,
    val overview: String? = null,
)

// =========================== TV Season Detail ===========================
@Serializable
class TvSeasonDetailDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
class EpisodeDto(
    val name: String,
    @SerialName("episode_number")
    val episodeNumber: Int,
    @SerialName("air_date")
    val airDate: String? = null,
    val overview: String? = null,
    @SerialName("still_path")
    val stillPath: String? = null,
)

// ============================ wing.st flow ==============================
// GET https://api.wing.st/servers
@Serializable
class WingServersDto(
    val servers: List<WingServerDto> = emptyList(),
)

@Serializable
class WingServerDto(
    val name: String,
    val status: String? = null,
    @SerialName("4k")
    val fourK: Boolean = false,
    val description: String? = null,
)

// Runtime info for a wing.st server, from the /servers call or the
// hardcoded fallback. `name` is the raw API name sent as ?server=,
// `displayName` is the user-facing label.
data class WingServerInfo(
    val name: String,
    val mayHave4K: Boolean = false,
) {
    val displayName: String
        get() = name.replaceFirstChar { it.uppercase() }
}

// GET https://enc-dec.app/api/enc-cinejoy?url=<encoded wing url>
@Serializable
class EncCinejoyDto(
    val status: Int,
    val result: EncCinejoyResultDto,
)

@Serializable
class EncCinejoyResultDto(
    val data: String,
    val state: DecStateDto,
)

// POST https://enc-dec.app/api/dec-cinejoy {"text", "state"}
@Serializable
class DecCinejoyRequestDto(
    val text: String,
    val state: DecStateDto,
)

@Serializable
class DecStateDto(
    @SerialName("responseKey")
    val responseKey: String,
    val aad: String,
)

@Serializable
class DecCinejoyDto(
    val status: Int,
    val result: DecCinejoyResultDto,
)

// Decrypted wing.st payload: {data: {stream: [...]}, status} or
// {data: {error: "..."}, status} when a server fails upstream.
@Serializable
class DecCinejoyResultDto(
    val data: DecDataDto? = null,
    val status: Int = 0,
) {
    val error: String?
        get() = data?.error
}

@Serializable
class DecDataDto(
    val stream: List<StreamDto> = emptyList(),
    val error: String? = null,
)

@Serializable
class StreamDto(
    val type: String? = null,
    val id: String? = null,
    val playlist: String? = null,
    val captions: List<CaptionDto> = emptyList(),
)

@Serializable
class CaptionDto(
    val type: String? = null,
    val id: String? = null,
    val url: String? = null,
    val language: String? = null,
)
