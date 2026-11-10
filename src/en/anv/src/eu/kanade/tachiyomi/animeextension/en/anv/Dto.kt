package eu.kanade.tachiyomi.animeextension.en.anv

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.text.Normalizer

/**
 * Request envelope for ANV's `/api/catalog` endpoint.
 */
@Serializable
class CatalogRequest<T>(
    val operation: String,
    val variables: T,
)

/**
 * Empty variables object for catalog operations that take no arguments.
 */
@Serializable
class EmptyVariables

/**
 * Variables for the `BrowseAnime` catalog operation.
 */
@Serializable
class BrowseVariables(
    val page: Int,
    val perPage: Int,
    val sort: List<String>,
    val search: String? = null,
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val genres: List<String>? = null,
    val isAdult: Boolean = false,
)

/**
 * Variables for the `SearchAnime` catalog operation.
 */
@Serializable
class SearchVariables(
    val search: String,
    val page: Int,
    val perPage: Int,
)

/**
 * Variables for looking up an anime detail entry by slug.
 */
@Serializable
class SlugVariables(
    val slug: String,
)

/**
 * Variables for looking up an anime detail entry by numeric catalog ID.
 */
@Serializable
class IdVariables(
    val id: Long,
)

/**
 * Request body for ANV's `/api/episode` stream-resolution endpoint.
 */
@Serializable
class EpisodeRequest(
    val anilistId: Long,
    val malId: Long?,
    val episode: Int,
    val first: Boolean = true,
    val prefer: String = "sub",
)

/**
 * Generic response envelope returned by `/api/catalog`.
 */
@Serializable
class CatalogResponse<T>(
    val data: T? = null,
    val errors: List<CatalogErrorDto>? = null,
)

/**
 * Error item returned inside a `/api/catalog` response.
 */
@Serializable
class CatalogErrorDto(
    val message: String? = null,
)

/**
 * Paged catalog response payload.
 */
@Serializable
class CatalogPageData(
    @SerialName("Page") val page: PageDto? = null,
)

/**
 * Home catalog response payload combining featured hero and trending sections.
 */
@Serializable
class CatalogHomeData(
    val hero: PageDto? = null,
    val trending: PageDto? = null,
)

/**
 * Single media detail response payload.
 */
@Serializable
class CatalogMediaData(
    @SerialName("Media") val media: MediaDto? = null,
)

/**
 * Page container holding pagination metadata and media entries.
 */
@Serializable
class PageDto(
    val pageInfo: PageInfoDto? = null,
    val media: List<MediaDto> = emptyList(),
)

/**
 * Pagination flags for a catalog page.
 */
@Serializable
class PageInfoDto(
    val hasNextPage: Boolean = false,
)

/**
 * Media metadata model returned by ANV catalog and detail endpoints.
 */
@Serializable
class MediaDto(
    val id: Long = 0L,
    val idMal: Long? = null,
    val slug: String? = null,
    val title: MediaTitleDto? = null,
    val description: String? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val isAdult: Boolean = false,
    val studios: StudioConnectionDto? = null,
    val nextAiringEpisode: NextAiringDto? = null,
    val streamingEpisodes: List<StreamingEpisodeDto>? = null,
    val recommendations: RecommendationConnectionDto? = null,
) {
    /**
     * Resolves a canonical URL slug from the explicit slug, titles, or numeric ID.
     */
    fun resolveSlug(): String {
        val raw = slug?.trim()?.trim('/')
        if (!raw.isNullOrEmpty()) return raw

        val rawTitle = title?.romaji?.takeIf { it.isNotBlank() }
            ?: title?.english?.takeIf { it.isNotBlank() }
            ?: title?.native?.takeIf { it.isNotBlank() }
            ?: "anime"

        val normalized = Normalizer.normalize(rawTitle.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .replace(NON_SLUG_REGEX, "-")
            .trim('-')
            .take(80)
            .trim('-')

        return when {
            normalized.isNotEmpty() -> normalized
            id > 0L -> id.toString()
            else -> "anime"
        }
    }

    /**
     * Extracts a fallback AniList ID embedded in the banner image URL when present.
     */
    fun bannerAnilistId(): Long? = bannerImage
        ?.let { BANNER_ID_REGEX.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }

    /**
     * Selects the localized title matching [preferredLanguage] with fallbacks.
     */
    fun displayTitle(preferredLanguage: String): String {
        val t = title ?: return resolveSlug()
        return when (preferredLanguage) {
            "Romaji" -> t.romaji?.takeIf { it.isNotBlank() }
                ?: t.english?.takeIf { it.isNotBlank() }
                ?: t.native?.takeIf { it.isNotBlank() }
            "Native" -> t.native?.takeIf { it.isNotBlank() }
                ?: t.english?.takeIf { it.isNotBlank() }
                ?: t.romaji?.takeIf { it.isNotBlank() }
            else -> t.english?.takeIf { it.isNotBlank() }
                ?: t.romaji?.takeIf { it.isNotBlank() }
                ?: t.native?.takeIf { it.isNotBlank() }
        } ?: resolveSlug()
    }

    /**
     * Chooses the highest-resolution artwork URL available for thumbnails.
     */
    fun bestCoverUrl(): String? = coverImage?.extraLarge?.takeIf { it.isNotBlank() }
        ?: coverImage?.large?.takeIf { it.isNotBlank() }
        ?: bannerImage?.takeIf { it.isNotBlank() }

    /**
     * Maps summary catalog fields to an [SAnime] browse entry.
     */
    fun toSAnime(preferredLanguage: String): SAnime = SAnime.create().apply {
        val resolved = resolveSlug()
        url = "/anime/$resolved"
        title = displayTitle(preferredLanguage)
        thumbnail_url = bestCoverUrl()
    }

    /**
     * Maps full catalog metadata to an initialized [SAnime] details entry.
     */
    fun toSAnimeDetails(preferredLanguage: String): SAnime = toSAnime(preferredLanguage).apply {
        status = parseAnimeStatus(this@MediaDto.status)
        val studioNames = studios?.nodes.orEmpty()
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        if (studioNames.isNotEmpty()) {
            author = studioNames.joinToString()
        }
        if (genres.isNotEmpty()) {
            genre = genres.joinToString()
        }
        description = buildRichDescription(preferredLanguage)
        initialized = true
    }

    /**
     * Formats synopsis HTML and structured metadata into a readable description block.
     */
    private fun buildRichDescription(preferredLanguage: String): String {
        val parts = mutableListOf<String>()

        val cleanSynopsis = description
            ?.takeIf { it.isNotBlank() }
            ?.let(::cleanHtmlDescription)
            ?.takeIf { it.isNotBlank() }
        if (cleanSynopsis != null) {
            parts.add(cleanSynopsis)
        }

        val metaLines = mutableListOf<String>()
        format?.takeIf { it.isNotBlank() }?.let { metaLines.add("Format: ${prettifyFormat(it)}") }
        val seasonLine = listOfNotNull(
            season?.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar(Char::titlecase),
            seasonYear?.takeIf { it > 0 }?.toString(),
        ).joinToString(" ")
        if (seasonLine.isNotEmpty()) {
            metaLines.add("Season: $seasonLine")
        }
        episodes?.takeIf { it > 0 }?.let { metaLines.add("Total Episodes: $it") }
        duration?.takeIf { it > 0 }?.let { metaLines.add("Duration: $it min/ep") }
        averageScore?.takeIf { it > 0 }?.let { metaLines.add("Score: $it%") }
        nextAiringEpisode?.let { next ->
            if (next.episode > 0) {
                metaLines.add("Next Airing: Episode ${next.episode}")
            }
        }

        val mainTitle = displayTitle(preferredLanguage)
        val altTitles = listOfNotNull(title?.english, title?.romaji, title?.native)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(mainTitle, ignoreCase = true) }
            .distinct()
        if (altTitles.isNotEmpty()) {
            metaLines.add("Alt Titles: ${altTitles.joinToString(" / ")}")
        }

        if (metaLines.isNotEmpty()) {
            parts.add(metaLines.joinToString("\n"))
        }

        return parts.joinToString("\n\n")
    }

    companion object {
        private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val NON_SLUG_REGEX = Regex("[^a-z0-9]+")
        private val BANNER_ID_REGEX = Regex("""/anime/banner/(\d+)-""")
        private val BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val CLOSING_P_TAG_REGEX = Regex("""</p>""", RegexOption.IGNORE_CASE)
        private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")

        /**
         * Maps upstream airing status strings to [SAnime] status constants.
         */
        private fun parseAnimeStatus(raw: String?): Int = when (raw?.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "HIATUS" -> SAnime.ON_HIATUS
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        /**
         * Converts raw format codes into human-friendly labels.
         */
        private fun prettifyFormat(raw: String): String = when (raw.uppercase()) {
            "TV" -> "TV"
            "TV_SHORT" -> "TV Short"
            "MOVIE" -> "Movie"
            "SPECIAL" -> "Special"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "MUSIC" -> "Music"
            else -> raw
        }

        /**
         * Strips HTML markup from descriptions while preserving paragraph breaks.
         */
        private fun cleanHtmlDescription(rawHtml: String): String {
            val normalized = rawHtml
                .replace(BR_TAG_REGEX, "\n")
                .replace(CLOSING_P_TAG_REGEX, "\n\n")
            val text = Jsoup.parseBodyFragment(normalized).wholeText()
            return text
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .replace(MULTI_NEWLINE_REGEX, "\n\n")
                .trim()
        }
    }
}

/**
 * Localized anime title variants.
 */
@Serializable
class MediaTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

/**
 * Cover artwork URLs for a media item.
 */
@Serializable
class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val color: String? = null,
)

/**
 * GraphQL-style studio connection wrapper.
 */
@Serializable
class StudioConnectionDto(
    val nodes: List<StudioNodeDto> = emptyList(),
)

/**
 * Individual animation studio node.
 */
@Serializable
class StudioNodeDto(
    val name: String? = null,
)

/**
 * Next scheduled airing episode details.
 */
@Serializable
class NextAiringDto(
    val episode: Int = 0,
    val airingAt: Long = 0L,
)

/**
 * Per-episode streaming metadata including title, thumbnail, and filler flag.
 */
@Serializable
class StreamingEpisodeDto(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
    val filler: Boolean = false,
)

/**
 * GraphQL-style recommendation connection wrapper.
 */
@Serializable
class RecommendationConnectionDto(
    val nodes: List<RecommendationNodeDto> = emptyList(),
)

/**
 * Recommendation node wrapping a recommended [MediaDto].
 */
@Serializable
class RecommendationNodeDto(
    val mediaRecommendation: MediaDto? = null,
)

/**
 * Response payload from `/api/recent-episodes`.
 */
@Serializable
class RecentEpisodesResponse(
    val schedules: List<RecentScheduleDto> = emptyList(),
)

/**
 * Scheduled airing entry inside `/api/recent-episodes`.
 */
@Serializable
class RecentScheduleDto(
    val id: Long = 0L,
    val episode: Int = 0,
    val airingAt: Long = 0L,
    val media: MediaDto? = null,
)

/**
 * Response payload from `/api/episode`.
 */
@Serializable
class EpisodeApiResponse(
    val status: String = "",
    val reason: String? = null,
    val variants: List<EpisodeVariantDto> = emptyList(),
)

/**
 * Audio variant (Sub, Dub, H-Sub) containing stream server sources.
 */
@Serializable
class EpisodeVariantDto(
    val id: String = "",
    val label: String = "",
    val sources: List<EpisodeSourceDto> = emptyList(),
)

/**
 * Individual stream server source entry for an episode variant.
 */
@Serializable
class EpisodeSourceDto(
    val id: String = "",
    val label: String = "",
    val type: String = "hls",
    val url: String = "",
    val tracks: List<EpisodeTrackDto> = emptyList(),
)

/**
 * Subtitle or caption track descriptor attached to a stream source.
 */
@Serializable
class EpisodeTrackDto(
    val id: String? = null,
    val label: String? = null,
    val language: String? = null,
    val src: String = "",
    val type: String = "captions",
    val default: Boolean = false,
)

/**
 * Response payload from `/api/skip-times` containing intro and outro timestamps.
 */
@Serializable
class SkipTimesResponse(
    val intro: SkipWindowDto? = null,
    val outro: SkipWindowDto? = null,
)

/**
 * Start and end timestamps (in seconds) for a skippable chapter window.
 */
@Serializable
class SkipWindowDto(
    val start: Double = 0.0,
    val end: Double = 0.0,
)
