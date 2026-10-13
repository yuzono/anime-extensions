package eu.kanade.tachiyomi.animeextension.en.hexawatch

import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ============================== General ===============================

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
    val overview: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null, // For Movie
    val name: String? = null, // For TV
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"
}

@Serializable
data class GenreDto(val name: String)

@Serializable
data class CompanyDto(val name: String)

@Serializable
data class NetworkDto(val name: String)

@Serializable
data class ExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
)

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

// ============================ Video Extractor ===========================

@Serializable
class ServerTimeDto(val timestamp: Long)

@Serializable
class DecryptionRequestDto(val text: String, val key: String)

@Serializable
class ExtractorResponseDto(
    val result: JsonElement = JsonNull,
    val error: String? = null,
)

/**
 * Decrypted source payload. The server list response carries `sources` as a list (URLs may be
 * empty) and/or a `servers` map; a per-server response carries `sources` as a list or as an
 * object with `file`/`url`.
 */
@Serializable
class ExtractorResultDto(
    val sources: JsonElement? = null,
    val servers: Map<String, JsonElement> = emptyMap(),
) {
    fun sourceUrls(): Map<String, String> {
        val list = sources?.toSourceList()
        if (list != null) return list.filter { it.url.isNotBlank() }.associate { it.server to it.url }
        val obj = sources as? JsonObject ?: return emptyMap()
        val url = (obj["file"] ?: obj["url"])?.jsonPrimitive?.contentOrNull
        return if (url.isNullOrBlank()) emptyMap() else mapOf("" to url)
    }
}

@Serializable
class ExtractorSourceDto(
    val server: String = "",
    val url: String = "",
)

fun JsonElement.toSourceList(): List<ExtractorSourceDto>? = (this as? JsonArray)?.map { it.parseAs<ExtractorSourceDto>() }

// ============================== Subtitles ===============================

@Serializable
data class SubtitleDto(
    val url: String,
    val language: String,
    @SerialName("isHearingImpaired") val isHearingImpaired: Boolean = false,
)
