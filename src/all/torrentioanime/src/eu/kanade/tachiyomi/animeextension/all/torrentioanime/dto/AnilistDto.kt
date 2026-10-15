package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Normal Anilst Meta Data
@Serializable
data class AnilistMeta(
    val data: AnilistPageData? = null,
)

@Serializable
data class AnilistPageData(
    @SerialName("Page")
    val page: AnilistPage? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: AnilistPageInfo? = null,
    val media: List<AnilistMedia>? = null,
)

// For Latest
@Serializable
data class AnilistMetaLatest(
    val data: AnilistPageDataLatest? = null,
)

@Serializable
data class AnilistPageDataLatest(
    @SerialName("Page")
    val page: AnilistPageLatest? = null,
)

@Serializable
data class AnilistPageLatest(
    val pageInfo: AnilistPageInfo? = null,
    val airingSchedules: List<AnilistAiringSchedule>? = null, // Change this line
)

@Serializable
data class AnilistAiringSchedule(
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistPageInfo(
    val currentPage: Int = 0,
    val hasNextPage: Boolean = false,
)

// Re-usable Media
@Serializable
data class AnilistMedia(
    val id: Int? = null,
    val siteUrl: String? = null,
    val title: AnilistTitle? = null,
    val coverImage: AnilistCoverImage? = null,
    val description: String? = null,
    val status: String? = null,
    val tags: List<AnilistTag>? = null,
    val genres: List<String>? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val studios: AnilistStudios? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val countryOfOrigin: String? = null,
    val isAdult: Boolean = false,
)

@Serializable
data class AnilistTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistNode>? = null,
)

@Serializable
data class AnilistNode(
    val name: String? = null,
)

@Serializable
data class AnilistTag(
    val name: String? = null,
)

@Serializable
data class AnilistRelationsDto(
    val data: AnilistRelationsData? = null,
)

@Serializable
data class AnilistRelationsData(
    val media: AnilistMediaRelations? = null,
)

@Serializable
data class AnilistMediaRelations(
    val relations: AnilistRelationsEdges? = null,
)

@Serializable
data class AnilistRelationsEdges(
    val edges: List<AnilistRelationEdge>? = null,
)

@Serializable
data class AnilistRelationEdge(
    val relationType: String? = null,
    val node: AnilistRelationNode? = null,
)

@Serializable
data class AnilistRelationNode(
    val id: Int,
    val type: String? = null,
    val title: AnilistTitle? = null,
    val coverImage: AnilistCoverImage? = null,
    val status: String? = null,
)
