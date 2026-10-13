package eu.kanade.tachiyomi.animeextension.en.animetoki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class CloudFileResponse(
    val files: List<CloudFile>,
    @SerialName("node_index") val nodeIndex: JsonElement? = null,
)

@Serializable
class CloudFile(
    val name: String = "",
    @SerialName("mimeType") val mimeType: String = "",
    @SerialName("mime_type") val mime_type: String = "",
    val id: String = "",
) {
    val actualMimeType: String
        get() = mimeType.ifEmpty { mime_type }
}

@Serializable
class WorkerFileResponse(
    val files: List<WorkerFile>,
)

@Serializable
class WorkerFile(
    val name: String,
    val id: String,
)
