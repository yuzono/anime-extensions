package aniyomi.lib.anilib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ========================== Response Wrappers ==========================
// After parseGraphQLAs unwraps the {"data": ..., "errors": ...} envelope,
// the remaining JSON has the GraphQL root operation type as a key.
// These wrappers map that key to a property via @SerialName.

@Serializable
data class PageDataWrapper(
    @SerialName("Page") val page: PageData = PageData(),
)

@Serializable
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

// ========================== Media Filter ==========================

/**
 * Universal filter parameters for AniList media queries.
 *
 * All fields are nullable strings (or lists of strings) to allow flexible
 * composition without requiring enum conversions at the boundary.
 * The [toVariables] method builds a GraphQL variables JSON object containing
 * only the non-null fields, so the fixed query template can include all
 * parameters as optional variables.
 *
 * @property search Text search query.
 * @property sort Comma-separated sort criteria (e.g. "TRENDING_DESC", "POPULARITY_DESC").
 *   AniList accepts `[MediaSort]` as a JSON array — pass multiple values in [sortList].
 * @property sortList Explicit list of sort values; takes precedence over [sort] if both set.
 * @property format Media format (e.g. "TV", "MOVIE", "OVA").
 * @property status Media status (e.g. "RELEASING", "FINISHED", "NOT_YET_RELEASED").
 * @property season Media season (e.g. "WINTER", "SPRING", "SUMMER", "FALL").
 * @property seasonYear Year of the season.
 * @property genres Included genres list.
 * @property excludedGenres Excluded genres list.
 * @property tags Included tags list.
 * @property excludedTags Excluded tags list.
 * @property minimumTagRank Minimum rank for tags (0–100).
 * @property yearGreater Start year for FuzzyDateInt range (inclusive).
 * @property yearLesser End year for FuzzyDateInt range (inclusive).
 * @property countryOfOrigin Country code (e.g. "JP", "KR", "CN").
 * @property isAdult Whether to filter adult content.
 * @property onList Whether the media is on the user's list.
 * @property page Page number (1-indexed).
 * @property perPage Results per page (max 50).
 */
data class MediaFilter(
    val search: String? = null,
    val sort: String? = null,
    val sortList: List<String>? = null,
    val format: String? = null,
    val formatList: List<String>? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val genres: List<String>? = null,
    val excludedGenres: List<String>? = null,
    val tags: List<String>? = null,
    val excludedTags: List<String>? = null,
    val minimumTagRank: Int? = null,
    val yearGreater: Int? = null,
    val yearLesser: Int? = null,
    val countryOfOrigin: String? = null,
    val isAdult: Boolean? = null,
    val onList: Boolean? = null,
    val page: Int? = null,
    val perPage: Int? = null,
) {
    /**
     * Build a GraphQL variables JSON object from the non-null filter fields.
     * Only includes fields that have a value, so the fixed query template can
     * safely declare all parameters as optional GraphQL variables.
     */
    fun toVariables(): JsonObject = buildJsonObject {
        search?.let { put("search", it) }
        // sortList takes precedence; fallback: split single sort string
        val effectiveSort = sortList ?: sort?.let { listOf(it) }
        effectiveSort?.let {
            put("sort", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        // formatList takes precedence; fallback: single format string
        val effectiveFormat = formatList ?: format?.let { listOf(it) }
        effectiveFormat?.let {
            put("format", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        status?.let { put("status", it) }
        season?.let { put("season", it) }
        seasonYear?.let { put("seasonYear", it) }
        genres?.let {
            put("genres", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        excludedGenres?.let {
            put("excludedGenres", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        tags?.let {
            put("tags", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        excludedTags?.let {
            put("excludedTags", JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        minimumTagRank?.let { put("minimumTagRank", it) }
        yearGreater?.let { put("yearGreater", it) }
        yearLesser?.let { put("yearLesser", it) }
        countryOfOrigin?.let { put("countryOfOrigin", it) }
        isAdult?.let { put("isAdult", it) }
        onList?.let { put("onList", it) }
        page?.let { put("page", it) }
        perPage?.let { put("perPage", it) }
        // type is always ANIME for this library
        put("type", "ANIME")
    }
}
