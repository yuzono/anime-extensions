package aniyomi.lib.anilib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AniListResponse<T>(
    val data: T,
)

@Serializable
data class MediaData(
    @SerialName("Media") val media: MediaSnapshot? = null,
)

@Serializable
data class MediaSnapshot(
    val id: Int = 0,
    @SerialName("idMal") val malId: Int? = null,
    val title: MediaTitle? = null,
    val coverImage: MediaCoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val studios: MediaStudios? = null,
    val nextAiringEpisode: AiringEpisode? = null,
    val airingSchedule: AiringScheduleConnection? = null,
)

@Serializable
data class MediaTitle(
    val userPreferred: String? = null,
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class MediaCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class MediaStudios(
    val edges: List<StudioEdge>? = null,
)

@Serializable
data class StudioEdge(
    val isMain: Boolean = false,
    val node: StudioNode? = null,
)

@Serializable
data class StudioNode(
    val name: String? = null,
)

@Serializable
data class AiringEpisode(
    val episode: Int = 0,
    val airingAt: Long = 0,
)

@Serializable
data class AiringScheduleConnection(
    val nodes: List<AiringScheduleNode>? = null,
)

@Serializable
data class AiringScheduleNode(
    val episode: Int = 0,
    val airingAt: Long = 0,
)

/**
 * Result of fetching airing schedule from AniList.
 * Maps episode number → air date in milliseconds since epoch.
 */
data class AiringScheduleResult(
    val schedule: Map<Float, Long> = emptyMap(),
)
