package eu.kanade.tachiyomi.animeextension.all.anizone

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import keiyoushi.utils.tryParse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale

private val DOMAIN_REGEX = Regex("^https?://[^/]+")
private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

internal fun String.toRelativeUrl(): String = this.replace("\\/", "/")
    .replace(DOMAIN_REGEX, "")
    .trimStart('/')

internal fun parseDate(dateStr: String): Long = DATE_FORMAT.tryParse(dateStr)

private fun Map<String, String>.preferredTitle(preferredTitleLang: String): String? = this[preferredTitleLang] ?: this["1"] ?: this["5"]

fun AnimeXData.toSAnime(preferredTitleLang: String): SAnime? {
    val cleanUrl = url.toRelativeUrl()
    if (cleanUrl.isBlank()) return null
    val resolvedTitle = titleList.preferredTitle(preferredTitleLang)
        ?: mainTitle.takeIf { it.isNotBlank() }
        ?: return null

    return SAnime.create().apply {
        url = "/$cleanUrl"
        title = resolvedTitle
        thumbnail_url = cover
    }
}

fun AnimeXDataWrapped.toSAnime(preferredTitleLang: String): SAnime? {
    val cleanUrl = anime.url.toRelativeUrl()
    if (cleanUrl.isBlank()) return null
    val resolvedTitle = anime.titleList.preferredTitle(preferredTitleLang)
        ?: anime.mainTitle.takeIf { it.isNotBlank() }
        ?: return null

    return SAnime.create().apply {
        url = "/$cleanUrl"
        title = resolvedTitle
        thumbnail_url = snapshot ?: teaser ?: ""
    }
}

fun EpisodeXData.toSEpisode(preferredTitleLang: String): SEpisode? {
    val cleanUrl = url.toRelativeUrl()
    if (cleanUrl.isBlank()) return null

    val baseName = "Episode $slug"
    val episodeTitle = titleList.preferredTitle(preferredTitleLang)

    return SEpisode.create().apply {
        url = "/$cleanUrl"

        name = if (!episodeTitle.isNullOrBlank() && episodeTitle != "Unknown") {
            "$baseName - $episodeTitle"
        } else {
            baseName
        }

        episode_number = slug.toFloatOrNull() ?: -1f
        date_upload = airDate?.let { parseDate(it) } ?: 0L
    }
}

@Serializable
class AnimeXData(
    val url: String,
    val cover: String,
    @SerialName("main_title") val mainTitle: String,
    @SerialName("title_list")
    @Serializable(with = TitleListSerializer::class)
    val titleList: Map<String, String> = emptyMap(),
)

@Serializable
class AnimeXDataWrapped(
    val anime: AnimeXDataAlt,
    val snapshot: String? = null,
    val teaser: String? = null,
)

@Serializable
class AnimeXDataAlt(
    val url: String,
    @SerialName("main_title") val mainTitle: String,
    @SerialName("title_list")
    @Serializable(with = TitleListSerializer::class)
    val titleList: Map<String, String> = emptyMap(),
)

@Serializable
class EpisodeXData(
    val slug: String,
    val url: String,
    @SerialName("title_list")
    @Serializable(with = TitleListSerializer::class)
    val titleList: Map<String, String> = emptyMap(),
    @SerialName("air_date")
    val airDate: String? = null,
)

object TitleListSerializer : KSerializer<Map<String, String>> {
    override val descriptor = buildClassSerialDescriptor("TitleList")

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: error("Only JSON supported")
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> emptyMap()
            is JsonObject -> element.mapValues { it.value.jsonPrimitive.content }
            else -> emptyMap()
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        error("Serialization not needed")
    }
}

@Serializable
class VidstackConfig(
    val src: String,
    val subtitles: List<VidstackSubtitle> = emptyList(),
)

@Serializable
class VidstackSubtitle(
    val title: String,
    val file: String,
)

class VideoData(
    val url: String,
    val name: String,
    val subtitles: List<Track>,
)
