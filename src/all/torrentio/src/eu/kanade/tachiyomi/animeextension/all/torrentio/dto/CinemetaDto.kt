package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CinemetaSearchResponse(
    val metas: List<CinemetaMeta>? = null,
)

@Serializable
data class CinemetaMeta(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val popularity: Double? = null,
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
    val status: String? = null,
    val released: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val writer: List<String>? = null,
    val videos: List<EpisodeVideo>? = null,
    @SerialName("app_extras")
    val appExtras: AppExtras? = null,
)

@Serializable
data class AppExtras(
    val cast: List<CastMember>? = null,
)

@Serializable
data class CastMember(
    val name: String? = null,
)

@Serializable
data class EpisodeVideo(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val episode: Int? = null,
    @SerialName("firstAired")
    val firstAired: String? = null,
    val released: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
)
