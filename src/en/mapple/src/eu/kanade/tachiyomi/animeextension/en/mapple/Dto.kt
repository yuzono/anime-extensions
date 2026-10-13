package eu.kanade.tachiyomi.animeextension.en.mapple

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
private const val TMDB_POSTER_SIZE = "w500"
private const val TMDB_BACKDROP_SIZE = "w1280"

// ============================== TMDB DTOs ===============================
@Serializable
class PageDto<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
class MediaItemDto(
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

fun MediaItemDto.toSAnime(): SAnime = SAnime.create().apply {
    title = realTitle
    val type = mediaType ?: if (name != null) "tv" else "movie"
    url = "/$type/$id"
    thumbnail_url = posterPath?.let { "$TMDB_IMAGE_BASE/$TMDB_POSTER_SIZE$it" }
}

@Serializable
class ExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
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

fun MovieDetailDto.toSAnime(): SAnime = SAnime.create().apply {
    title = this@toSAnime.title
    url = "/movie/$id"
    thumbnail_url = posterPath?.let { "$TMDB_IMAGE_BASE/$TMDB_POSTER_SIZE$it" }
    author = productionCompanies.joinToString { it.name }
    genre = genres.joinToString { it.name }
    status = parseStatus(this@toSAnime.status)
    initialized = true
    description = buildString {
        overview?.takeIf { it.isNotBlank() }?.let {
            appendLine(it)
            appendLine()
        }
        val details = listOfNotNull(
            "**Type:** Movie",
            voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
            tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
            releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
            countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
            originalTitle?.takeIf { it.isNotBlank() && it.trim() != title.trim() }?.let { "**Original Title:** $it" },
            runtime?.takeIf { it > 0 }?.let {
                val hours = it / 60
                val minutes = it % 60
                "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
            },
            homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
            externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
        )
        if (details.isNotEmpty()) {
            append(details.joinToString("\n"))
        }
        backdropPath?.let {
            if (isNotEmpty()) append("\n\n")
            append("![Backdrop]($TMDB_IMAGE_BASE/$TMDB_BACKDROP_SIZE$it)")
        }
    }
}

// ============================== TV Detail ===============================
@Serializable
class TvDetailDto(
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

fun TvDetailDto.toSAnime(): SAnime = SAnime.create().apply {
    title = name
    url = "/tv/$id"
    thumbnail_url = posterPath?.let { "$TMDB_IMAGE_BASE/$TMDB_POSTER_SIZE$it" }
    author = productionCompanies.joinToString { it.name }
    artist = networks.joinToString { it.name }
    genre = genres.joinToString { it.name }
    status = parseStatus(this@toSAnime.status)
    initialized = true
    description = buildString {
        overview?.also { append(it + "\n\n") }
        val details = listOfNotNull(
            "**Type:** TV Show",
            voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
            tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
            firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
            lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
            countries?.takeIf { it.isNotEmpty() }?.let { "**Country:** ${it.joinToString()}" },
            originalName?.takeIf { it.isNotBlank() && it.trim() != name.trim() }?.let { "**Original Name:** $it" },
            homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
            externalIds?.imdbId?.let { "**[IMDB](https://www.imdb.com/title/$it)**" },
        )
        if (details.isNotEmpty()) {
            append(details.joinToString("\n"))
        }
        backdropPath?.let {
            if (isNotEmpty()) append("\n\n")
            append("![Backdrop]($TMDB_IMAGE_BASE/$TMDB_BACKDROP_SIZE$it)")
        }
    }
}

@Serializable
class SeasonDto(
    val id: Int,
    val name: String,
    @SerialName("season_number") val seasonNumber: Int,
)

// =========================== TV Season Detail ===========================
@Serializable
class TvSeasonDetailDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
class EpisodeDto(
    val name: String,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("air_date") val airDate: String? = null,
)

// ============================= Mapple API ===============================

@Serializable
class RequestTokenResponse(
    val token: String,
)

@Serializable
class PlaybackInitResponse(
    val success: Boolean,
    val requiresPow: Boolean = false,
    val pow: PowChallenge? = null,
    val token: String? = null,
)

@Serializable
class PowChallenge(
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
    val pow: PowSolution? = null,
)

@Serializable
class PowSolution(
    val challengeId: String,
    val nonce: String,
)

@Serializable
class EncryptRequest(
    val data: EncryptPayload,
    val endpoint: String,
    val requestToken: String,
)

@Serializable
class EncryptPayload(
    val mediaId: Int,
    val mediaType: String,
    @SerialName("tv_slug")
    val tvSlug: String,
    val source: String,
)

@Serializable
class EncryptResponse(
    val url: String,
)

@Serializable
class StreamEncryptedResponse(
    val success: Boolean,
    val data: StreamInfo? = null,
)

@Serializable
class StreamInfo(
    @SerialName("stream_url")
    val streamUrl: String,
)

// ============================== Subtitles ===============================

@Serializable
class SubtitleDto(
    val url: String,
    val language: String,
    val isHearingImpaired: Boolean = false,
)

// ============================== Helpers ===============================
private fun parseStatus(status: String?): Int = when (status) {
    "Released", "Ended" -> SAnime.COMPLETED
    "Returning Series", "In Production" -> SAnime.ONGOING
    else -> SAnime.UNKNOWN
}
