package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class VideoResponse(
    val success: Boolean,
    val videoUrl: JsonElement? = null,
)

@Serializable
class VideoSource(
    val label: String,
    val file: String,
)
