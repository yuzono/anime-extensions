package eu.kanade.tachiyomi.animeextension.en.anipm

import eu.kanade.tachiyomi.animeextension.en.anipm.AniPM.Companion.parseStatus
import eu.kanade.tachiyomi.animesource.model.SAnime
import keiyoushi.utils.longOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ================================ Catalog =================================
@Serializable
class CatalogResponseDto(
    val items: List<TitleItemDto> = emptyList(),
    val page: Int = 1,
    val lastPage: Int = 1,
    val total: Int = 0,
    val hasNextPage: Boolean = false,
)

@Serializable
class TitleItemDto(
    val id: Long? = null,
    val source: String? = null,
    val routeId: String? = null,
    val title: String? = null,
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val rating: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val duration: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    val malId: Long? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val anilistId: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val synopsis: String? = null,
    val season: String? = null,
    val tags: List<String>? = null,
    val providerTitles: List<String>? = null,
) {
    /** Stable handle: settlar numeric id (catalog always provides it). */
    fun toHandle(): String? = id?.takeIf { it > 0 }?.let { "set-$it" }

    fun toSAnime(baseUrl: String): SAnime? {
        val t = title?.takeIf(String::isNotBlank) ?: return null
        val handle = toHandle() ?: return null
        return SAnime.create().apply {
            url = routeId ?: handle
            title = t
            thumbnail_url = absoluteCover(baseUrl, poster)
            status = parseStatus(this@TitleItemDto.status)
            genre = buildList {
                addAll(genres.filter(String::isNotBlank).sortedBy { it })
                tags?.filter(String::isNotBlank)?.sortedBy { it }?.take(3)?.let { addAll(it) }
            }.distinct().joinToString().takeIf(String::isNotBlank)
            author = studios.filter(String::isNotBlank).joinToString(", ")
                .takeIf(String::isNotBlank)
            description = cleanSynopsis(synopsis)
        }
    }
}

// ================================ Series ==================================
@Serializable
class SeriesResponseDto(
    val id: Long,
    val source: String? = null,
    val title: String = "",
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val rating: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val duration: String? = null,
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val synopsis: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    val malId: Long? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val anilistId: String? = null,
    val status: String? = null,
    val season: String? = null,
    val tags: List<String>? = null,
    val providerTitles: List<String>? = null,
    val episodes: List<SeriesEpisodeDto> = emptyList(),
    val relations: List<RelationDto> = emptyList(),
    val nextAiring: NextAiringDto? = null,
    val startDate: FuzzyDateDto? = null,
    val dubLag: DubLagDto? = null,
    val routeId: String? = null,
) {
    private fun getFancyScore(score: Double): String {
        if (score <= 0.0) return ""
        val percent = (score * 10).roundToInt()
        val stars = (percent / 20.0).roundToInt().coerceIn(1, 5)
        return "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $percent"
    }

    fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
        url = routeId ?: "set-$id"
        title = this@SeriesResponseDto.title
        thumbnail_url = absoluteCover(baseUrl, poster)
        status = parseStatus(this@SeriesResponseDto.status)
        genre = buildList {
            addAll(genres.filter(String::isNotBlank).sortedBy { it })
            tags?.filter(String::isNotBlank)?.sortedBy { it }?.let { addAll(it) }
        }.distinct().joinToString(", ").takeIf(String::isNotBlank)
        author = studios.filter(String::isNotBlank).joinToString(", ")
            .takeIf(String::isNotBlank)

        description = buildString {
            score?.let { s ->
                getFancyScore(s).takeIf(String::isNotEmpty)?.let {
                    append(it)
                    append("\n\n")
                }
            }

            cleanSynopsis(synopsis)?.let {
                append(it)
                append("\n\n")
            }

            providerTitles?.drop(1)?.takeIf { it.isNotEmpty() }?.let {
                append("**Alternative Titles**: ${it.joinToString(" • ")}")
                append("\n\n")
            }

            val info = buildList {
                type?.let { add("**Format**: $it") }
                season?.takeIf(String::isNotBlank)?.let { s ->
                    year?.takeIf { it > 0 }?.let { y ->
                        add("**Season**: ${s.replaceFirstChar(Char::titlecase)} $y")
                    }
                }
                duration?.let { add("**Duration**: $it") }
                rating?.let { add("**Rating**: $it") }
                nextAiring?.let { na ->
                    val whenStr = na.airingAt?.let { sec -> airDateFormat.format(Date(sec * 1000)) }
                    add("**Next Airing**: Episode ${na.episode ?: "?"}${whenStr?.let { w -> " on $w" } ?: ""}")
                }
            }
            if (info.isNotEmpty()) {
                append(info.joinToString("\n"))
                append("\n\n")
            }

            val trackers = buildList {
                anilistId?.toLongOrNull()
                    ?.takeIf { it in 1..<syntheticAniListBase }
                    ?.let { add("[AniList](https://anilist.co/anime/$it)") }
                malId?.takeIf { it > 0 }
                    ?.let { add("[MyAnimeList](https://myanimelist.net/anime/$it)") }
            }
            if (trackers.isNotEmpty()) {
                append("**Trackers**: ${trackers.joinToString(" • ")}")
                append("\n")
            }
            banner?.let {
                append("\n![Banner](")
                append(absoluteCover(baseUrl, it) ?: return@let)
                append(")")
            }
        }.takeIf(String::isNotBlank)
    }
}

/** settlar synthesizes composite ids (80000xxxxx) for items with no real AniList entry. */
val syntheticAniListBase = 800_000_000L

@Serializable
class RecommendResponseDto(val items: List<RecommendItemDto> = emptyList())

@Serializable
class RecommendItemDto(
    val id: Long? = null,
    val source: String? = null,
    val titleId: String? = null,
    val title: String? = null,
    val native: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val synopsis: String? = null,
    val routeId: String? = null,
) {
    /** anime:6275 → set-6275 · ani:151384 → ani-151384 */
    fun toHandle(): String? = when {
        titleId?.startsWith("anime:") == true -> "set-" + titleId.removePrefix("anime:")
        titleId?.startsWith("ani:") == true -> "ani-" + titleId.removePrefix("ani:")
        else -> null
    }

    fun toSAnime(baseUrl: String): SAnime? {
        val t = title?.takeIf(String::isNotBlank) ?: return null
        val handle = toHandle() ?: return null
        return SAnime.create().apply {
            url = routeId ?: handle
            title = t
            thumbnail_url = absoluteCover(baseUrl, poster)
        }
    }
}

@Serializable
class SeriesEpisodeDto(
    val number: Double,
    val sourceNumber: Double? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val sub: Boolean = false,
    val dub: Boolean = false,
    val subhard: Boolean = false,
    val dubhard: Boolean = false,
    val subExact: Boolean = false,
    val dubExact: Boolean = false,
    val runtimeSeconds: Long? = null,
    val aired: String? = null,
    val runtime: Int? = null,
    val rating: String? = null,
    val routeId: String? = null,
)

@Serializable
class FillerResponseDto(
    val ranges: FillerRangesDto? = null,
)

@Serializable
class FillerRangesDto(
    val filler: List<List<Int>> = emptyList(),
    val mixed: List<List<Int>> = emptyList(),
    val canon: List<List<Int>> = emptyList(),
)

@Serializable
class RelationDto(
    val id: Long,
    @Serializable(with = FlexibleStringSerializer::class)
    val anilistId: String? = null,
    val source: String? = null,
    val relation: String? = null,
    val title: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val poster: String? = null,
) {
    fun toHandle(): String? = id.takeIf { it > 0 }?.let { "set-$it" }
}

@Serializable
class NextAiringDto(val episode: Int? = null, val airingAt: Long? = null)

@Serializable
class FuzzyDateDto(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
class DubLagDto(
    val dubThrough: Int = 0,
    val subThrough: Int = 0,
    val behind: Int = 0,
)

// ============================ Playback ============================

@Serializable
class BootstrapDto(
    val version: Int = 1,
    val requestedLanguage: String? = null,
    val effectiveLanguage: String? = null,
    val availability: AvailabilityDto? = null,
    val skip: SkipDto? = null,
    val settlarSelection: String? = null,
    val episodeRouteId: String? = null,
    val anipmPackages: AniPMPackagesDto? = null,
)

@Serializable
class AniPMPackagesDto(
    val episodes: Map<String, AniPMEpisodePackageDto> = emptyMap(),
)

@Serializable
class AniPMEpisodePackageDto(
    val key: String? = null,
    val sub: Boolean = false,
    val dub: Boolean = false,
    val subhard: Boolean = false,
    val dubhard: Boolean = false,
)

@Serializable
class AvailabilityDto(val sub: Boolean = false, val dub: Boolean = false)

@Serializable
class SkipDto(
    val op: RangeDto? = null,
    val ed: RangeDto? = null,
    val dur: Double? = null,
    val source: String? = null,
    val channel: String? = null,
)

@Serializable
class RangeDto(val start: Double = 0.0, val end: Double = 0.0)

@Serializable
class SettlarSessionDto(
    val embedUrl: String? = null,
    val expiresAt: Long? = null,
    val provider: String? = null,
)

@Serializable
class EmbedSessionDto(
    val sessionId: String? = null,
    val title: String? = null,
    val source: String? = null,
    val kind: String? = null,
    val audioLang: String? = null,
    val subtitles: List<EmbedSubtitleDto> = emptyList(),
    val keyProof: String? = null,
)

@Serializable
class EmbedSubtitleDto(
    val url: String? = null,
    val label: String? = null,
    val srclang: String? = null,
)

// ============================ Helpers ============================

private val airDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

@Serializable
class MetaResponseDto(val meta: Map<String, MetaEntryDto> = emptyMap())

@Serializable
class MetaEntryDto(val routeId: String? = null)

@Serializable
class FacetsDto(
    val genres: List<FacetDto> = emptyList(),
    val tags: List<FacetDto> = emptyList(),
    val studios: List<FacetDto> = emptyList(),
    val updatedAt: Long = 0L,
)

@Serializable
class FacetDto(val name: String, val count: Int = 0)

private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val INLINE_TAG_REGEX = Regex("</?(?:i|b|em|strong)>", RegexOption.IGNORE_CASE)

/**
 * Absolute cover URL. Pass paths and URLs through completely untouched,
 * without appending any reformatting or resizing query parameters.
 */
internal fun absoluteCover(baseUrl: String, path: String?): String? {
    val p = path?.takeIf(String::isNotBlank) ?: return null
    return if (p.startsWith("/")) baseUrl + p else p
}

/**
 * Unescapes HTML entities (&eacute; etc.) and strips simple inline tags
 * while PRESERVING paragraph breaks — unlike Jsoup.parse().text(),
 * which collapses all whitespace.
 */
internal fun cleanSynopsis(raw: String?): String? = raw
    ?.replace(BR_REGEX, "\n")
    ?.let { Parser.unescapeEntities(it, false) }
    ?.replace(INLINE_TAG_REGEX, "")
    ?.trim()
    ?.takeIf(String::isNotBlank)

/** Accepts any JSON primitive ("24 min" or 24) as a String; null/arrays/objects → null. */
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleString?", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? = when (val el = (decoder as JsonDecoder).decodeJsonElement()) {
        is JsonNull -> null
        is JsonPrimitive -> el.content.takeIf(String::isNotBlank)
        else -> null
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Accepts number or numeric string (12815 or "12815"); anything else → null. */
object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleLong?", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? = when (val el = (decoder as JsonDecoder).decodeJsonElement()) {
        is JsonNull -> null
        is JsonPrimitive -> el.longOrNull ?: el.content.toLongOrNull()
        else -> null
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}
