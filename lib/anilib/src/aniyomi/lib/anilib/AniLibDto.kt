package aniyomi.lib.anilib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ========================== Response Wrappers ==========================

@Serializable
data class AniListResponse<T>(
    val data: T,
)

@Serializable
@SerialName("Page")
data class PageData(
    val pageInfo: PageInfo? = null,
    val media: List<MediaSnapshot> = emptyList(),
)

@Serializable
data class PageInfo(
    val total: Int = 0,
    val currentPage: Int = 0,
    val lastPage: Int = 0,
    val hasNextPage: Boolean = false,
    val perPage: Int = 0,
)

// ========================== Media Data ==========================

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
    // --- New fields ---
    val format: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val synonyms: List<String> = emptyList(),
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val tags: List<MediaTag>? = null,
    val relations: MediaConnection? = null,
    val characters: MediaConnection? = null,
    val trailer: Trailer? = null,
    val countryOfOrigin: String? = null,
    val source: String? = null,
    val siteUrl: String? = null,
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

// ========================== Studios ==========================

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

// ========================== Airing Schedule ==========================

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

// ========================== Date ==========================

@Serializable
data class FuzzyDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    /** Formats the date as "YYYY-MM-DD" with null parts replaced by "?". */
    fun format(): String = buildString {
        append(year?.toString() ?: "?")
        append('-')
        append(month?.toString()?.padStart(2, '0') ?: "??")
        append('-')
        append(day?.toString()?.padStart(2, '0') ?: "??")
    }
}

// ========================== Tags ==========================

@Serializable
data class MediaTag(
    val name: String? = null,
    val description: String? = null,
    val rank: Int? = null,
    val isGeneralSpoiler: Boolean = false,
    val isMediaSpoiler: Boolean = false,
)

// ========================== Relations ==========================

@Serializable
data class MediaConnection(
    val edges: List<MediaEdge>? = null,
)

@Serializable
data class MediaEdge(
    val relationType: String? = null,
    val node: RelatedMediaNode? = null,
    @SerialName("characterName") val characterName: String? = null,
    val role: String? = null,
)

@Serializable
data class RelatedMediaNode(
    val id: Int = 0,
    val type: String? = null,
    val format: String? = null,
    val title: MediaTitle? = null,
    val coverImage: MediaCoverImage? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val siteUrl: String? = null,
)

// ========================== Characters ==========================

@Serializable
data class CharacterNode(
    val id: Int = 0,
    val name: CharacterName? = null,
    val image: CharacterImage? = null,
)

@Serializable
data class CharacterName(
    val full: String? = null,
    val native: String? = null,
)

@Serializable
data class CharacterImage(
    val large: String? = null,
    val medium: String? = null,
)

// ========================== Trailer ==========================

@Serializable
data class Trailer(
    val id: String? = null,
    val site: String? = null,
    val thumbnail: String? = null,
) {
    /** Returns a watchable URL if the trailer is from YouTube. */
    fun url(): String? {
        if (site != "youtube" || id == null) return null
        return "https://www.youtube.com/watch?v=$id"
    }
}

// ========================== Ani.zip Episode Data ==========================

@Serializable
data class AnizipResponse(
    val episodes: Map<String, AnizipEpisode>? = null,
)

@Serializable
data class AnizipEpisode(
    val title: String? = null,
    val titleJp: String? = null,
    val aired: String? = null,
    val runtime: Int? = null,
)

/**
 * Result of fetching episode titles from ani.zip.
 * Maps episode number → title/air date/runtime data.
 */
data class EpisodeTitlesResult(
    val episodes: Map<Int, AnizipEpisode> = emptyMap(),
)

// ========================== AniFiller Episode Data ==========================

/** Episode classification types from AniFiller. */
enum class FillerType(val label: String) {
    MANGA_CANON("manga-canon"),
    FILLER("filler"),
    MIXED_MANGA("mixed-manga"),
    ANIME_CANON("anime-canon"),
    ;

    companion object {
        /** Parse from the AniFiller JSON string value, defaults to null if unrecognized. */
        fun fromValue(value: String): FillerType? = entries.find { it.label == value }
    }
}

@Serializable
data class AniFillerShow(
    val slug: String = "",
    val title: String = "",
    val mappings: AniFillerMappings = AniFillerMappings(),
    val episodes: List<AniFillerEpisode> = emptyList(),
)

@Serializable
data class AniFillerMappings(
    @SerialName("anilist_id") val anilistId: Int = 0,
    @SerialName("mal_id") val malId: Int = 0,
)

@Serializable
data class AniFillerEpisode(
    val episode: Int = 0,
    val title: String = "",
    val type: String = "",
    @SerialName("aired_date") val airedDate: String = "",
)

/**
 * Result of fetching filler classification from AniFiller.
 * Maps episode number → [FillerType].
 */
data class FillerDataResult(
    val episodes: Map<Int, FillerType> = emptyMap(),
)
