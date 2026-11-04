package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class Flashvars(
    val mediaDefinitions: List<MediaDefinition> = emptyList(),
    @SerialName("video_title")
    val videoTitle: String? = null,
)

@Serializable
data class MediaDefinition(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
) {
    val qualityString: String?
        get() = when (val q = quality) {
            is JsonPrimitive -> q.contentOrNull?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { "${it}p" }
            is JsonArray -> q.firstOrNull()?.let { elem ->
                (elem as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { "${it}p" }
            }
            else -> null
        }
}
