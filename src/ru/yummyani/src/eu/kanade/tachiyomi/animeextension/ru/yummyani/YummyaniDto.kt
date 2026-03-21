package eu.kanade.tachiyomi.animeextension.ru.yummyani

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeListResponse(
    val items: List<AnimeData> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class AnimeData(
    val id: Int,
    @SerialName("russian") val rusName: String? = null,
    val name: String? = null,
    @SerialName("slug_url") val url: String? = null,
    val status: AnimeStatus? = null,
    @SerialName("poster") val poster: PosterInfo? = null,
    val genres: List<GenreInfo>? = null,
    val description: String? = null,
    @SerialName("is_licensed") val licensed: Boolean? = null,
    val kind: String? = null,
    @SerialName("episodes_count") val episodesCount: Int? = null,
    @SerialName("airing_start") val airingStart: String? = null,
    @SerialName("rating") val rating: Float? = null,
)

@Serializable
data class AnimeStatus(
    val id: Int,
    val name: String? = null,
    @SerialName("russian") val rusName: String? = null,
)

@Serializable
data class PosterInfo(
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class GenreInfo(
    val id: Int,
    val name: String? = null,
    @SerialName("russian") val rusName: String? = null,
)

@Serializable
data class SearchResponse(
    val items: List<AnimeData> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class VideosResponse(
    val items: List<VideoInfo> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class VideoInfo(
    val id: Int,
    val title: String? = null,
    @SerialName("episode") val episodeNumber: Int? = null,
    val url: String? = null,
    @SerialName("poster") val poster: PosterInfo? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val status: VideoStatus? = null,
    @SerialName("dubbing") val dubbingInfo: DubbingInfo? = null,
)

@Serializable
data class VideoStatus(
    val id: Int,
    val name: String? = null,
)

@Serializable
data class DubbingInfo(
    val id: Int,
    val name: String? = null,
    @SerialName("russian") val rusName: String? = null,
)

@Serializable
data class GenresResponse(
    val items: List<GenreInfo> = emptyList(),
)

@Serializable
data class CatalogResponse(
    val kinds: List<CatalogItem>? = null,
    val statuses: List<CatalogItem>? = null,
    val seasons: List<CatalogItem>? = null,
    val ratings: List<CatalogItem>? = null,
)

@Serializable
data class CatalogItem(
    val id: Int,
    val name: String? = null,
    @SerialName("russian") val rusName: String? = null,
)

@Serializable
data class FeedResponse(
    val latest: List<AnimeData>? = null,
    val popular: List<AnimeData>? = null,
    @SerialName("ongoing") val ongoing: List<AnimeData>? = null,
)
