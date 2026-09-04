package eu.kanade.tachiyomi.animeextension.en.mapple

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
    val popularity: Double? = null,
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

// ============================= Mapple API ===============================

@Serializable
data class SessionResponseDto(
    val result: SessionResultDto,
)

@Serializable
data class SessionResultDto(
    val sessionId: String,
    val nextAction: String,
)

@Serializable
data class VideoRequestDto(
    val mediaId: String,
    val mediaType: String,
    @SerialName("tv_slug") val tvSlug: String,
    val source: String,
    val sessionId: String,
)

@Serializable
data class VideoResponseDto(
    val success: Boolean,
    val data: VideoDataDto? = null,
    val error: String? = null,
)

@Serializable
data class VideoDataDto(
    @SerialName("stream_url") val streamUrl: String,
    val source: String,
)

// ============================== New Data Classes ==============================

@Serializable
data class PlaybackInitResponse(
    val success: Boolean,
    val requiresPow: Boolean = false,
    val pow: PowData? = null,
    val token: String? = null,
    val expiresIn: Int? = null,
)

@Serializable
data class PowData(
    val challengeId: String,
    val challenge: String,
    val difficulty: Int,
)

@Serializable
data class PlaybackInitRequest(
    val mediaId: Int,
    val mediaType: String,
    @SerialName("tv_slug")
    val tvSlug: String,
    val requestToken: String,
    val pow: PowRequest? = null,
)

@Serializable
data class PowRequest(
    val challengeId: String,
    val nonce: String,
)

@Serializable
data class EncryptRequest(
    val data: EncryptData,
    val endpoint: String,
    val requestToken: String,
)

@Serializable
data class EncryptData(
    val mediaId: Int,
    val mediaType: String,
    @SerialName("tv_slug")
    val tvSlug: String,
    val source: String,
)

@Serializable
data class EncryptResponse(
    val url: String,
    val encrypted: String,
)

@Serializable
data class StreamEncryptedResponse(
    val success: Boolean,
    val data: StreamData? = null,
)

@Serializable
data class StreamData(
    @SerialName("stream_url")
    val streamUrl: String,
    val title: String,
    @SerialName("tmdb_id")
    val tmdbId: String,
    @SerialName("media_type")
    val mediaType: String,
    val source: String,
)

// ============================== Subtitles ===============================

@Serializable
data class SubtitleDto(
    val url: String,
    val language: String,
    @SerialName("isHearingImpaired") val isHearingImpaired: Boolean = false,
)
