package eu.kanade.tachiyomi.animeextension.en.cineby

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

// ============================== TMDB DTOs ===============================
@Serializable
data class PageDto<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages")
    val totalPages: Int,
)

@Serializable
data class MediaItemDto(
    val id: Int,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_language")
    val originalLanguage: String? = null,
    @SerialName("origin_country")
    val originCountries: List<String> = emptyList(),
    @SerialName("genre_ids")
    val genreIds: List<Int> = emptyList(),
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"
}

@Serializable
data class ExternalIdsDto(
    @SerialName("imdb_id")
    val imdbId: String? = null,
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
data class TvDetailDto(
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
data class SeasonDto(
    val id: Int,
    val name: String,
    @SerialName("season_number")
    val seasonNumber: Int,
)

// =========================== TV Season Detail ===========================
@Serializable
data class TvSeasonDetailDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val name: String,
    @SerialName("episode_number")
    val episodeNumber: Int,
    @SerialName("air_date")
    val airDate: String? = null,
)

// ============================ Videasy Decryption ============================
@Serializable
data class VideasyDecryptionDto(
    val status: Int,
    val result: VideasyDecryptedResult,
)

@Serializable
data class VideasyDecryptedResult(
    // Current shape (mb-flix, cdn, myflixerzupcloud, 1movies, lamovie, ...):
    // each source is one playable URL with its own quality label.
    val sources: List<VideasySourceDto>? = null,
    // Legacy: single HLS playlist (some old server paths).
    val url: String? = null,
    // Legacy: multi-quality stream map (primebox, etc.).
    val streams: Map<String, String>? = null,
    // Subtitles for all response types
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class VideasySourceDto(
    val url: String,
    val quality: String? = null,
)

// Subtitle field names vary by Videasy server. Observed/expected variants:
//   {file, label}        — legacy JWPlayer-style
//   {url, lang|language} — most current servers
//   {src, name}          — occasional fallback
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SubtitleDto(
    @JsonNames("file", "src")
    val url: String? = null,
    @JsonNames("label", "lang", "name")
    val language: String? = null,
)

// ======================== Videasy Server ========================
data class VideasyServer(
    val displayName: String,
    val apiBase: String,
    val path: String,
    // Sent as the ?language= query param to Videasy (some backends filter on it).
    val language: String? = null,
    val movieOnly: Boolean = false,
    val mayHave4K: Boolean = false,
    // Display-only audio-language hint shown in the video picker AND
    // alongside each entry in the server preference list.
    val audioLabel: String? = null,
)
