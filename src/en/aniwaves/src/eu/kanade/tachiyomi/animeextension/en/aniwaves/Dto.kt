package eu.kanade.tachiyomi.animeextension.en.aniwaves

import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
class AjaxHtmlResponse(val result: String) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
class SourcesResponse(val result: EmbedResult? = null)

@Serializable
class EmbedResult(val url: String)

@Serializable
class RecommendationsResponse(
    val status: Boolean = false,
    @SerialName("has_more_pages") val hasMorePages: Boolean = false,
    val html: String = "",
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(html)
}

/**
 * getSources payload from the echovideo family (vidplay / mycloud / datsav).
 *  - a single stream URL (string, or {"file": url})
 *  - an array whose first entry is either of those
 *  - a quality-keyed map of direct files, e.g. {"FHD": ["url"], "HD": "url"} (datsav)
 */
@Serializable(with = VideoSources.Serializer::class)
class VideoSources private constructor(
    val streamUrl: String? = null,
    val qualityFiles: Map<String, List<String>> = emptyMap(),
) {
    object Serializer : KSerializer<VideoSources> {
        private val delegate = JsonElement.serializer()

        override val descriptor: SerialDescriptor =
            SerialDescriptor("VideoSources", delegate.descriptor)

        override fun deserialize(decoder: Decoder): VideoSources = fromJson(delegate.deserialize(decoder))
            ?: throw SerializationException("Unrecognized sources payload")

        override fun serialize(encoder: Encoder, value: VideoSources): Unit = throw SerializationException("VideoSources is read-only")
    }

    internal companion object {
        fun fromJson(element: JsonElement?): VideoSources? = when (element) {
            null -> null
            is JsonPrimitive -> VideoSources(streamUrl = element.content.takeIf(String::isNotBlank))
            is JsonArray -> element.firstOrNull()?.let(::fromJson)
            is JsonObject -> if ("file" in element) {
                fromSingle(element)
            } else {
                VideoSources(qualityFiles = element.mapValues { (_, value) -> value.toStringList() })
            }
        }

        private fun fromSingle(element: JsonElement): VideoSources? = when (element) {
            is JsonPrimitive -> fromJson(element)
            is JsonObject -> (element["file"] as? JsonPrimitive)?.content?.let { VideoSources(streamUrl = it) }
            else -> null
        }

        private fun JsonElement.toStringList(): List<String> = when (this) {
            is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.startsWith("http") }
            is JsonPrimitive -> listOfNotNull(content.takeIf { it.startsWith("http") })
            else -> emptyList()
        }
    }
}

@Serializable
class VidplaySourcesResponse(
    val sources: VideoSources? = null,
    val tracks: List<CaptionTrack> = emptyList(),
) {
    fun subtitles(): List<Track> = tracks.toTracks()
}

@Serializable
class CaptionTrack(
    private val file: String? = null,
    private val label: String? = null,
    private val kind: String? = null,
) {
    fun toTrack(): Track? {
        val url = file?.takeIf { it.startsWith("http") } ?: return null
        if (!kind.isNullOrBlank() && !kind.equals("captions", ignoreCase = true)) return null
        return Track(url, label?.takeIf(String::isNotBlank) ?: "Subtitle")
    }
}

fun List<CaptionTrack>.toTracks(): List<Track> = mapNotNull { it.toTrack() }
    .distinctBy { it.url + it.lang }
