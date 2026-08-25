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
    // --- Extended fields ---
    val meanScore: Int? = null,
    val trending: Int? = null,
    val favourites: Int? = null,
    val isLicensed: Boolean? = null,
    val hashtag: String? = null,
    val updatedAt: Int? = null,
    val seasonInt: Int? = null,
    val streamingEpisodes: List<MediaStreamingEpisode>? = null,
    val externalLinks: List<MediaExternalLink>? = null,
    val rankings: List<MediaRank>? = null,
    val stats: MediaStats? = null,
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

// ========================== Streaming Episodes ==========================

@Serializable
data class MediaStreamingEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

// ========================== External Links ==========================

@Serializable
data class MediaExternalLink(
    val id: Int = 0,
    val url: String? = null,
    val site: String? = null,
    val siteId: Int? = null,
    val type: String? = null,
    val language: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val isDisabled: Boolean = false,
)

// ========================== Rankings ==========================

@Serializable
data class MediaRank(
    val id: Int = 0,
    val rank: Int = 0,
    val type: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val allTime: Boolean = false,
    val context: String? = null,
)

// ========================== Media Stats ==========================

@Serializable
data class MediaStats(
    val scoreDistribution: List<ScoreDistribution>? = null,
    val statusDistribution: List<StatusDistribution>? = null,
)

@Serializable
data class ScoreDistribution(
    val score: Int = 0,
    val amount: Int = 0,
)

@Serializable
data class StatusDistribution(
    val status: String? = null,
    val amount: Int = 0,
)

// ========================== Media Trend ==========================

@Serializable
data class MediaTrend(
    val mediaId: Int = 0,
    val date: Int = 0,
    val trending: Int = 0,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val inProgress: Int? = null,
    val releasing: Boolean = false,
    val episode: Int? = null,
    val media: MediaSnapshot? = null,
)

@Serializable
data class MediaTrendConnection(
    val edges: List<MediaTrendEdge>? = null,
)

@Serializable
data class MediaTrendEdge(
    val node: MediaTrend? = null,
)

// ========================== Studio Details ==========================

@Serializable
data class StudioData(
    @SerialName("Studio") val studio: Studio? = null,
)

@Serializable
data class Studio(
    val id: Int = 0,
    val name: String? = null,
    val isAnimationStudio: Boolean = false,
    val favourites: Int = 0,
    val siteUrl: String? = null,
    val media: StudioMediaConnection? = null,
)

@Serializable
data class StudioMediaConnection(
    val edges: List<StudioMediaEdge>? = null,
    val pageInfo: PageInfo? = null,
)

@Serializable
data class StudioMediaEdge(
    val node: MediaSnapshot? = null,
    val isMain: Boolean = false,
)

// ========================== Genre/Tag Collections ==========================

@Serializable
data class GenreCollectionData(
    @SerialName("GenreCollection") val genres: List<String>? = null,
)

@Serializable
data class MediaTagCollectionData(
    @SerialName("MediaTagCollection") val tags: List<MediaTag>? = null,
)

// ========================== Ani.zip Episode Data ==========================

@Serializable
data class AnizipResponse(
    val episodes: Map<String, AnizipEpisode>? = null,
)

@Serializable
data class AnizipTitle(
    val en: String? = null,
    @SerialName("x-jat") val xJat: String? = null,
    val ja: String? = null,
)

@Serializable
data class AnizipEpisode(
    val title: AnizipTitle? = null,
    val airDate: String? = null,
    val airdate: String? = null,
    val runtime: Int? = null,
) {
    val resolvedTitle: String?
        get() = title?.en?.ifEmpty { null }
            ?: title?.xJat?.ifEmpty { null }
            ?: title?.ja?.ifEmpty { null }

    val resolvedAirDate: String?
        get() = airDate?.ifEmpty { null }
            ?: airdate?.ifEmpty { null }
}

/**
 * Result of fetching episode titles from ani.zip.
 * Maps episode number → title/air date/runtime data.
 *
 * [airDates] contains pre-parsed epoch milliseconds for episode air dates,
 * resolved from ani.zip `airDate`/`airdate` fields.
 */
@Serializable
data class EpisodeTitlesResult(
    val episodes: Map<Int, AnizipEpisode> = emptyMap(),
    val airDates: Map<Int, Long> = emptyMap(),
)

// ========================== AniFiller Episode Data ==========================

/** Episode classification types from AniFiller. */
@Serializable
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
    @SerialName("mal_id") val malId: Int? = null,
)

@Serializable
data class AniFillerEpisode(
    val episode: Int = 0,
    val title: String = "",
    val type: String = "",
    @SerialName("aired_date") val airedDate: String = "",
)

@Serializable
data class FillerEpisodeData(
    val type: FillerType,
    val airDate: Long = 0L,
    val title: String = "",
)

/**
 * Result of fetching filler classification from AniFiller.
 * Maps episode number → [FillerEpisodeData] containing type, air date, and title.
 */
@Serializable
data class FillerDataResult(
    val episodes: Map<Int, FillerEpisodeData> = emptyMap(),
)

// ========================== MediaSnapshot Extensions ==========================

/**
 * Whether this media is classified as adult/NSFW content.
 * Checks both the `isAdult` GraphQL field (via tags) and genre-based heuristics.
 */
val MediaSnapshot.isAdultContent: Boolean
    get() = genres.any { it.equals("Ecchi", ignoreCase = true) } ||
        tags?.any { it.name.equals("Nudity", ignoreCase = true) || it.name.equals("Ero Guro", ignoreCase = true) } == true

/**
 * Human-readable status label derived from the AniList status string.
 */
val MediaSnapshot.formattedStatus: String
    get() = when (status?.uppercase()) {
        "RELEASING" -> "Airing"
        "FINISHED" -> "Finished"
        "CANCELLED" -> "Cancelled"
        "HIATUS" -> "Hiatus"
        "NOT_YET_RELEASED" -> "Not Yet Aired"
        else -> status?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    }

/**
 * All available title variants (userPreferred, romaji, english, native) excluding blanks.
 */
val MediaSnapshot.allTitleVariants: List<String>
    get() = listOfNotNull(
        title?.userPreferred?.ifBlank { null },
        title?.romaji?.ifBlank { null },
        title?.english?.ifBlank { null },
        title?.native?.ifBlank { null },
    ).distinct()

/**
 * Human-readable format label (e.g. "TV Short", "OVA", "Movie").
 */
val MediaSnapshot.formattedFormat: String
    get() = when (format?.uppercase()) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Short"
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        else -> format?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    }

/**
 * Season label with year (e.g. "Spring 2024"), or null if unavailable.
 */
val MediaSnapshot.seasonLabel: String?
    get() {
        if (season.isNullOrBlank() || seasonYear == null) return null
        val seasonName = when (season.uppercase()) {
            "WINTER" -> "Winter"
            "SPRING" -> "Spring"
            "SUMMER" -> "Summer"
            "FALL" -> "Fall"
            else -> season.replaceFirstChar { it.uppercase() }
        }
        return "$seasonName $seasonYear"
    }

/**
 * Formatted episode count string (e.g. "12 episodes" or "1 episode").
 */
val MediaSnapshot.episodeLabel: String?
    get() {
        if (episodes == null || episodes <= 0) return null
        return if (episodes == 1) "1 episode" else "$episodes episodes"
    }

/**
 * Formatted duration string (e.g. "24 min/ep").
 */
val MediaSnapshot.durationLabel: String?
    get() {
        if (duration == null || duration <= 0) return null
        return "$duration min/ep"
    }

/**
 * Average score as a display string (e.g. "85%" or "N/A").
 */
val MediaSnapshot.scoreLabel: String
    get() = if (averageScore != null && averageScore > 0) "$averageScore%" else "N/A"

/**
 * AniList site URL for this media, or null if not available.
 */
val MediaSnapshot.anilistUrl: String?
    get() = siteUrl?.ifBlank { null }

val MediaSnapshot.meanScoreLabel: String
    get() = if (meanScore != null && meanScore > 0) "$meanScore%" else "N/A"

val MediaSnapshot.favouritesLabel: String
    get() = when {
        favourites == null || favourites <= 0 -> "0"
        favourites >= 1000 -> "${"%.1f".format(favourites / 1000.0)}k"
        else -> favourites.toString()
    }

val MediaSnapshot.sourceLabel: String?
    get() = when (source?.uppercase()) {
        "ORIGINAL" -> "Original"
        "MANGA" -> "Manga"
        "LIGHT_NOVEL" -> "Light Novel"
        "VISUAL_NOVEL" -> "Visual Novel"
        "GAME" -> "Game"
        "OTHER" -> "Other"
        "NOVEL" -> "Novel"
        "DOUJINSHI" -> "Doujinshi"
        "ANIME" -> "Anime"
        else -> source?.replaceFirstChar { it.uppercase() }
    }

val MediaSnapshot.streamingLinks: List<MediaStreamingEpisode>
    get() = streamingEpisodes?.filter { !it.url.isNullOrBlank() } ?: emptyList()

val MediaSnapshot.streamingSites: List<String>
    get() = streamingEpisodes?.mapNotNull { it.site?.ifBlank { null } }?.distinct() ?: emptyList()

val MediaSnapshot.externalStreamingLinks: List<MediaExternalLink>
    get() = externalLinks?.filter { it.type == "STREAMING" && !it.isDisabled } ?: emptyList()

val MediaSnapshot.externalInfoLinks: List<MediaExternalLink>
    get() = externalLinks?.filter { it.type == "INFO" && !it.isDisabled } ?: emptyList()

val MediaSnapshot.topRanking: MediaRank?
    get() = rankings?.filter { it.type == "RATED" }?.minByOrNull { it.rank }

val MediaSnapshot.popularityRanking: MediaRank?
    get() = rankings?.filter { it.type == "POPULAR" }?.minByOrNull { it.rank }

val MediaSnapshot.scoreDistributionMap: Map<Int, Int>
    get() = stats?.scoreDistribution?.associate { it.score to it.amount } ?: emptyMap()

val MediaSnapshot.watchingCount: Int
    get() = stats?.statusDistribution?.firstOrNull { it.status == "CURRENT" }?.amount ?: 0

val MediaSnapshot.completedCount: Int
    get() = stats?.statusDistribution?.firstOrNull { it.status == "COMPLETED" }?.amount ?: 0

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

    /**
     * Builder for constructing [MediaFilter] instances with a fluent API.
     *
     * Usage:
     * ```
     * val filter = MediaFilter.Builder()
     *     .search("Attack on Titan")
     *     .sort("POPULARITY_DESC")
     *     .genre("Action", "Drama")
     *     .excludeGenre("Comedy")
     *     .status("FINISHED")
     *     .season("SPRING", 2023)
     *     .format("TV")
     *     .page(1)
     *     .perPage(20)
     *     .build()
     * ```
     */
    class Builder {
        private var search: String? = null
        private var sort: String? = null
        private var sortList: List<String>? = null
        private var format: String? = null
        private var formatList: List<String>? = null
        private var status: String? = null
        private var season: String? = null
        private var seasonYear: Int? = null
        private val genres = mutableListOf<String>()
        private val excludedGenres = mutableListOf<String>()
        private val tags = mutableListOf<String>()
        private val excludedTags = mutableListOf<String>()
        private var minimumTagRank: Int? = null
        private var yearGreater: Int? = null
        private var yearLesser: Int? = null
        private var countryOfOrigin: String? = null
        private var isAdult: Boolean? = null
        private var onList: Boolean? = null
        private var page: Int? = null
        private var perPage: Int? = null

        fun search(query: String) = apply { this.search = query }
        fun sort(vararg criteria: String) = apply { this.sortList = criteria.toList() }
        fun sort(criteria: String) = apply { this.sort = criteria }
        fun format(vararg formats: String) = apply { this.formatList = formats.toList() }
        fun format(fmt: String) = apply { this.format = fmt }
        fun status(status: String) = apply { this.status = status }
        fun season(season: String, year: Int? = null) = apply {
            this.season = season.uppercase()
            this.seasonYear = year
        }
        fun genre(vararg genres: String) = apply { this.genres.addAll(genres) }
        fun excludeGenre(vararg genres: String) = apply { this.excludedGenres.addAll(genres) }
        fun tag(vararg tags: String) = apply { this.tags.addAll(tags) }
        fun excludeTag(vararg tags: String) = apply { this.excludedTags.addAll(tags) }
        fun minimumTagRank(rank: Int) = apply { this.minimumTagRank = rank }
        fun yearRange(from: Int? = null, to: Int? = null) = apply {
            this.yearGreater = from
            this.yearLesser = to
        }
        fun countryOfOrigin(code: String) = apply { this.countryOfOrigin = code }
        fun isAdult(adult: Boolean?) = apply { this.isAdult = adult }
        fun onList(onList: Boolean?) = apply { this.onList = onList }
        fun page(page: Int) = apply { this.page = page }
        fun perPage(perPage: Int) = apply { this.perPage = perPage }

        fun build() = MediaFilter(
            search = search,
            sort = sort,
            sortList = sortList,
            format = format,
            formatList = formatList,
            status = status,
            season = season,
            seasonYear = seasonYear,
            genres = genres.takeIf { it.isNotEmpty() },
            excludedGenres = excludedGenres.takeIf { it.isNotEmpty() },
            tags = tags.takeIf { it.isNotEmpty() },
            excludedTags = excludedTags.takeIf { it.isNotEmpty() },
            minimumTagRank = minimumTagRank,
            yearGreater = yearGreater,
            yearLesser = yearLesser,
            countryOfOrigin = countryOfOrigin,
            isAdult = isAdult,
            onList = onList,
            page = page,
            perPage = perPage,
        )
    }
}
