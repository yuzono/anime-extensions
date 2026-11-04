package eu.kanade.tachiyomi.animeextension.en.shuttletv

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

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
    @SerialName("original_language")
    val originalLanguage: String? = null,
    @SerialName("origin_country")
    val originCountries: List<String> = emptyList(),
    @SerialName("genre_ids")
    val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("first_air_date")
    val firstAirDate: String? = null,
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"
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

// =========================== Cinesrc Challenge ==========================
// POST /api/c/bootstrap -> {v, r, p}
@Serializable
class BootstrapDto(
    val v: Int = 0,
    val r: String,
    val p: String,
)

// GET /api/c/issue -> CSP4 proof-of-work challenge
@Serializable
class Stage1ChallengeDto(
    val b: String? = null,
    val w: String,
    val t: Long = 0,
    val n: String? = null,
    val s: String? = null,
)

// GET /api/c/stage2/issue -> {pack: [target, ?, ?, salt, blob]}
@Serializable
class Stage2ChallengeDto(
    val pack: List<JsonElement> = emptyList(),
)

// ============================ enc-dec.app ===============================
@Serializable
class EncDecResponseDto<T>(
    val status: Int,
    val result: T,
)

@Serializable
class SeedRequestDto(
    val data: SeedDataDto,
    val type: String,
)

@Serializable
class SeedDataDto(
    val url: String,
    @SerialName("user_agent")
    val userAgent: String,
    @SerialName("challenge_data")
    val challengeData: Stage1ChallengeDto,
)

@Serializable
class EncryptRequestDto(
    val data: EncryptDataDto,
    val type: String,
)

@Serializable
class EncryptDataDto(
    val url: String,
    @SerialName("user_agent")
    val userAgent: String,
    @SerialName("challenge_data")
    val challengeData: StageSolutionsDto,
    val state: JsonElement,
)

@Serializable
class StageSolutionsDto(
    val stage1: Stage1SolutionDto,
    val stage2: Stage2SolutionDto,
)

@Serializable
class Stage1SolutionDto(
    val challenge: Stage1ChallengeDto,
    val solution: String,
)

@Serializable
class Stage2SolutionDto(
    val challenge: Stage2ChallengeDto,
    val solution: String,
)

@Serializable
class SeedResultDto(
    val seed: String = "",
    // Opaque blob (an object with a privateKey inside) that must be passed
    // back to enc-dec untouched in the encrypt/decrypt steps.
    val state: JsonElement,
)

@Serializable
class EncryptResultDto(
    val token: String,
    val headers: ActionIdDto,
    val state: JsonElement,
)

// Next.js server-action ids, rotated per deploy and delivered by enc-dec.
@Serializable
class ActionIdDto(
    @SerialName("getProviderList")
    val getProviderList: String,
    @SerialName("getStream")
    val getStream: String,
)

@Serializable
class DecRequestDto(
    val text: String,
    val state: JsonElement,
)

// ========================= getProviderList RSC ==========================
@Serializable
class ProviderDto(
    val id: String,
    val name: String? = null,
    val rank: Int? = null,
)

// =========================== getStream result ===========================
@Serializable
class DecryptedStreamDto(
    // Null when the provider has nothing for the item
    // ({"error":"no_streams"} responses).
    val url: List<DecryptedSourceDto>? = null,
    val error: String? = null,
    val provider: String? = null,
    val captions: List<DecryptedCaptionDto> = emptyList(),
    val thumbnails: JsonElement? = null,
)

@Serializable
class DecryptedSourceDto(
    val url: String? = null,
    val hash: String? = null,
    val height: Int? = null,
    val label: String? = null,
    val source: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class DecryptedCaptionDto(
    val url: String,
    @JsonNames("label", "lang", "name")
    val language: String? = null,
    val display: String? = null,
    val format: String? = null,
)
