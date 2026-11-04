package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
class Flashvars(
    val mediaDefinitions: List<MediaDefinition> = emptyList(),
    @SerialName("video_title")
    val videoTitle: String? = null,
)

@Serializable
class MediaDefinition(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
) {
    val qualityString: String?
        get() = when (val q = quality) {
            is JsonPrimitive -> q.contentOrNull?.let { parseQuality(it) }
            is JsonArray -> (q.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { parseQuality(it) }
            else -> null
        }

    private fun parseQuality(str: String): String? = QUALITY_REGEX.find(str)?.groupValues?.getOrNull(1)?.let { "${it}p" }

    companion object {
        private val QUALITY_REGEX = Regex("""^(\d+)""")
    }
}
