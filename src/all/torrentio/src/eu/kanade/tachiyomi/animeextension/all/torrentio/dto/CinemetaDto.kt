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
    val popularity: Double? = null,
)

@Serializable
data class CinemetaMetaDetailResponse(
    val meta: CinemetaMetaDetail? = null,
)

@Serializable
data class CinemetaMetaDetail(
    val id: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val slug: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val country: String? = null,
    val year: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    val released: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val writer: List<String>? = null,
    val videos: List<EpisodeVideo>? = null,
    val behaviorHints: BehaviorHints? = null,
)

@Serializable
data class EpisodeVideo(
    val id: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val episode: Int? = null,
    @SerialName("firstAired") val firstAired: String? = null,
    val rating: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val description: String? = null,
)

@Serializable
data class BehaviorHints(
    @SerialName("defaultVideoId") val defaultVideoId: String? = null,
    @SerialName("hasScheduledVideos") val hasScheduledVideos: Boolean = false,
)
