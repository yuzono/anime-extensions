package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CinemetaSearchResponse(
    val query: String? = null,
    val metas: List<CinemetaMeta>? = null,
)

@Serializable
data class CinemetaMeta(
    val id: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val releaseInfo: String? = null,
)

@Serializable
data class CinemetaMetaDetailResponse(
    val meta: CinemetaMetaDetail? = null,
)

@Serializable
data class CinemetaMetaDetail(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val writer: List<String>? = null,
    val imdbRating: String? = null,
    val status: String? = null,
    val released: String? = null,
)
