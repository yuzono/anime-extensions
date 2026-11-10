package eu.kanade.tachiyomi.animeextension.en.anv

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.text.Normalizer

@Serializable
class CatalogRequest<T>(
    val operation: String,
    val variables: T,
)

@Serializable
class EmptyVariables

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

@Serializable
class SearchVariables(
    val search: String,
    val page: Int,
    val perPage: Int,
)

@Serializable
class SlugVariables(
    val slug: String,
)

@Serializable
class IdVariables(
    val id: Long,
)

@Serializable
class EpisodeRequest(
    val anilistId: Long,
    val malId: Long?,
    val episode: Int,
    val first: Boolean = true,
    val prefer: String = "sub",
)

@Serializable
class CatalogResponse<T>(
    val data: T? = null,
    val errors: List<CatalogErrorDto>? = null,
)

@Serializable
class CatalogErrorDto(
    val message: String? = null,
)

@Serializable
class CatalogPageData(
    @SerialName("Page") val page: PageDto? = null,
)

@Serializable
class CatalogHomeData(
    val hero: PageDto? = null,
    val trending: PageDto? = null,
)

@Serializable
class CatalogMediaData(
    @SerialName("Media") val media: MediaDto? = null,
)

@Serializable
class PageDto(
    val pageInfo: PageInfoDto? = null,
    val media: List<MediaDto> = emptyList(),
)

@Serializable
class PageInfoDto(
    val hasNextPage: Boolean = false,
)

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

    fun bannerAnilistId(): Long? = bannerImage
        ?.let { BANNER_ID_REGEX.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }

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

    fun bestCoverUrl(): String? = coverImage?.extraLarge?.takeIf { it.isNotBlank() }
        ?: coverImage?.large?.takeIf { it.isNotBlank() }
        ?: bannerImage?.takeIf { it.isNotBlank() }

    fun toSAnime(preferredLanguage: String): SAnime = SAnime.create().apply {
        val resolved = resolveSlug()
        url = "/anime/$resolved"
        title = displayTitle(preferredLanguage)
        thumbnail_url = bestCoverUrl()
    }

    fun toSAnimeDetails(preferredLanguage: String): SAnime = toSAnime(preferredLanguage).apply {
        status = parseAnimeStatus(this@MediaDto.status)
        val studioNames = studios?.nodes.orEmpty()
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        if (studioNames.isNotEmpty()) {
            author = studioNames.joinToString(", ")
        }
        if (genres.isNotEmpty()) {
            genre = genres.joinToString(", ")
        }
        description = buildRichDescription(preferredLanguage)
    }

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
        private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")

        private fun parseAnimeStatus(raw: String?): Int = when (raw?.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "HIATUS" -> SAnime.ON_HIATUS
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

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

        private fun cleanHtmlDescription(rawHtml: String): String {
            val normalized = rawHtml
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
            val text = Jsoup.parseBodyFragment(normalized).wholeText()
            return text
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .replace(MULTI_NEWLINE_REGEX, "\n\n")
                .trim()
        }
    }
}

@Serializable
class MediaTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val color: String? = null,
)

@Serializable
class StudioConnectionDto(
    val nodes: List<StudioNodeDto> = emptyList(),
)

@Serializable
class StudioNodeDto(
    val name: String? = null,
)

@Serializable
class NextAiringDto(
    val episode: Int = 0,
    val airingAt: Long = 0L,
)

@Serializable
class StreamingEpisodeDto(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
    val filler: Boolean = false,
)

@Serializable
class RecommendationConnectionDto(
    val nodes: List<RecommendationNodeDto> = emptyList(),
)

@Serializable
class RecommendationNodeDto(
    val mediaRecommendation: MediaDto? = null,
)

@Serializable
class RecentEpisodesResponse(
    val schedules: List<RecentScheduleDto> = emptyList(),
)

@Serializable
class RecentScheduleDto(
    val id: Long = 0L,
    val episode: Int = 0,
    val airingAt: Long = 0L,
    val media: MediaDto? = null,
)

@Serializable
class EpisodeApiResponse(
    val status: String = "",
    val reason: String? = null,
    val variants: List<EpisodeVariantDto> = emptyList(),
)

@Serializable
class EpisodeVariantDto(
    val id: String = "",
    val label: String = "",
    val sources: List<EpisodeSourceDto> = emptyList(),
)

@Serializable
class EpisodeSourceDto(
    val id: String = "",
    val label: String = "",
    val type: String = "hls",
    val url: String = "",
    val tracks: List<EpisodeTrackDto> = emptyList(),
)

@Serializable
class EpisodeTrackDto(
    val id: String? = null,
    val label: String? = null,
    val language: String? = null,
    val src: String = "",
    val type: String = "captions",
    val default: Boolean = false,
)

@Serializable
class SkipTimesResponse(
    val intro: SkipWindowDto? = null,
    val outro: SkipWindowDto? = null,
)

@Serializable
class SkipWindowDto(
    val start: Double = 0.0,
    val end: Double = 0.0,
)
