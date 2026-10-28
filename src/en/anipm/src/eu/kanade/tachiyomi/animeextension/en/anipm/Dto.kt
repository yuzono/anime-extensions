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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    val id: Long = 0L,
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
    @Serializable(with = FlexibleStringListSerializer::class)
    val genres: List<String> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
    val studios: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val synopsis: String? = null,
    val season: String? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val tags: List<String>? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val providerTitles: List<String>? = null,
) {
    /** Stable handle: settlar numeric id or ani id (catalog always provides it). */
    fun toHandle(): String? = stableMainHandle(source, id, anilistId, routeId)

    fun toSAnime(baseUrl: String): SAnime? {
        val t = title?.takeIf(String::isNotBlank) ?: native?.takeIf(String::isNotBlank) ?: return null
        val handle = toHandle() ?: return null
        return SAnime.create().apply {
            url = handle
            title = t
            thumbnail_url = stableCoverUrl(baseUrl, source, anilistId, routeId, malId, poster)
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
    val title: String,
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val rating: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val duration: String? = null,
    val type: String? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val genres: List<String> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
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
    @Serializable(with = FlexibleStringListSerializer::class)
    val tags: List<String>? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
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
        url = stableMainHandle(source, id, anilistId, routeId) ?: routeId?.takeIf(String::isNotBlank) ?: "anime/$id"
        title = this@SeriesResponseDto.title
        thumbnail_url = stableCoverUrl(baseUrl, source, anilistId, routeId, malId, poster)
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
    val id: Long = 0L,
    val source: String? = null,
    val titleId: String? = null,
    val title: String,
    val native: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val status: String? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val genres: List<String> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
    val studios: List<String> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
    val tags: List<String>? = null,
    val synopsis: String? = null,
    val routeId: String? = null,
) {
    fun toHandle(): String? {
        val aniIdRaw = when {
            titleId?.startsWith("ani:") == true -> titleId.removePrefix("ani:")
            else -> null
        }
        val aniId = stableAnilistId(source, aniIdRaw, routeId)
        return aniId?.let { "ani-$it" }
            ?: id.takeIf { it in 1..<syntheticAniListBase }?.let { "set-$it" }
            ?: routeId?.takeIf(String::isNotBlank)
    }

    fun toThumbnailUrl(baseUrl: String): String? {
        val aniIdRaw = when {
            titleId?.startsWith("ani:") == true -> titleId.removePrefix("ani:")
            else -> null
        }
        return stableCoverUrl(baseUrl, source, aniIdRaw, routeId, null, poster)
    }

    fun toSAnime(baseUrl: String): SAnime? {
        val t = title.takeIf(String::isNotBlank) ?: return null
        val handle = toHandle() ?: return null
        return SAnime.create().apply {
            url = handle
            title = t
            thumbnail_url = toThumbnailUrl(baseUrl)
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
    val id: Long = 0L,
    @Serializable(with = FlexibleStringSerializer::class)
    val anilistId: String? = null,
    val source: String? = null,
    val relation: String? = null,
    val title: String,
    val type: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val routeId: String? = null,
) {
    fun toHandle(): String? {
        val aniId = stableAnilistId(source, anilistId, routeId)
        return aniId?.let { "ani-$it" }
            ?: id.takeIf { it in 1..<syntheticAniListBase }?.let { "set-$it" }
            ?: routeId?.takeIf(String::isNotBlank)
    }

    fun toThumbnailUrl(baseUrl: String): String? = stableCoverUrl(baseUrl, source, anilistId, routeId, null, poster)
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
 * Stable AniList id usable as a lookup key: prefers the parsed anilistId,
 * falls back to the numeric tail of ani-family routeIds. Synthetic ids
 * (800000000+) are site-internal composites and resolve nowhere.
 */
private fun stableAnilistId(source: String?, anilistId: String?, routeId: String?): Long? {
    anilistId?.trim()?.takeIf(String::isNotBlank)?.toLongOrNull()
        ?.takeIf { it in 1..<syntheticAniListBase }
        ?.let { return it }

    if (source != "anilist") return null

    val route = routeId?.takeIf(String::isNotBlank) ?: return null
    val tailStr = route.substringAfterLast('-')
    val tail = tailStr.toLongOrNull()?.takeIf { it in 1..<syntheticAniListBase } ?: return null
    val lowerRoute = route.lowercase()
    if (tail < 1000 && (lowerRoute.contains("season") || lowerRoute.contains("part") || lowerRoute.contains("cour") || lowerRoute.contains("ova") || lowerRoute.contains("special"))) {
        return null
    }
    return tail
}

/**
 * Stable series handle for catalog/series payloads. Only ids that every API
 * endpoint resolves on are encoded ("ani-<anilistId>" / "set-<numeric id>"):
 * routeIds embed title slugs that the site regenerates at any time, so they
 * are consumed for extraction only and never persisted in SAnime.url.
 */
internal fun stableMainHandle(source: String?, id: Long, anilistId: String?, routeId: String?): String? = when {
    source == "anilist" -> stableAnilistId(source, anilistId, routeId)?.let { "ani-$it" } ?: routeId?.takeIf(String::isNotBlank)
    id in 1..<syntheticAniListBase -> "set-$id"
    else -> routeId?.takeIf(String::isNotBlank)
}

/**
 * Episode preview still. The art form is id-parameterized only and keeps
 * serving across slug rotations; ani mirrors do not return a thumbnail at
 * all, while canonical responses carry it in the art form. When no AniList
 * id is available, falls back to the raw thumbnail path.
 */
internal fun episodePreviewUrl(baseUrl: String, source: String?, anilistId: String?, routeId: String?, epNumber: String, thumbnail: String?): String? {
    val aid = stableAnilistId(source, anilistId, routeId)
    if (aid != null) {
        return "$baseUrl/api/anime/art/still/$aid/$epNumber"
    }
    return thumbnail?.takeIf(String::isNotBlank)?.let { absoluteCover(baseUrl, it) }
}

/**
 * Cover URL that depends only on ids. Poster paths arrive with a rotating
 * "?key=" token that dies whenever the site regenerates slugs, while the
 * id-parameterized form (seen in playback-bootstrap responses) keeps
 * serving without any session. Falls back to the raw poster path when no
 * usable id is available.
 */
internal fun stableCoverUrl(baseUrl: String, source: String?, anilistId: String?, routeId: String?, malId: Long?, poster: String?): String? {
    val aid = stableAnilistId(source, anilistId, routeId) ?: return absoluteCover(baseUrl, poster)
    return buildString {
        append(baseUrl)
        append("/api/anime/cover?anilistId=")
        append(aid)
        malId?.takeIf { it > 0 }?.let { mal ->
            append("&malId=")
            append(mal)
        }
    }
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

/** Accepts List of Strings OR List of Objects with name/tag/title/label/genre/studio; anything else → emptyList(). */
object FlexibleStringListSerializer : KSerializer<List<String>> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return emptyList()
        val element = runCatching { jsonDecoder.decodeJsonElement() }.getOrNull()
        if (element !is JsonArray) return emptyList()

        return element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> {
                    (item["name"] ?: item["tag"] ?: item["title"] ?: item["label"] ?: item["genre"] ?: item["studio"])
                        ?.let { if (it is JsonPrimitive) it.contentOrNull else null }
                        ?.takeIf(String::isNotBlank)
                }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
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

@Serializable
class FacetsDto(
    val genres: List<FacetDto> = emptyList(),
    val tags: List<FacetDto> = emptyList(),
    val studios: List<FacetDto> = emptyList(),
    val updatedAt: Long = 0L,
)

@Serializable
class FacetDto(val name: String, val count: Int = 0)
