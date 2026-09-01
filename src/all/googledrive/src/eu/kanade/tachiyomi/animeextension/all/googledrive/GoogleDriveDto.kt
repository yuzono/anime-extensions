package eu.kanade.tachiyomi.animeextension.all.googledrive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PostResponse(
    val nextPageToken: String? = null,
    val items: List<ResponseItem>? = null,
) {
    @Serializable
    data class ResponseItem(
        val id: String,
        val title: String,
        val mimeType: String,
        val fileSize: String? = null,
        val parents: List<Parent>? = null,
    ) {
        @Serializable
        data class Parent(
            val id: String,
        )
    }
}

@Serializable
data class LinkData(
    val url: String,
    val type: String,
    val info: LinkDataInfo? = null,
)

@Serializable
data class LinkDataInfo(
    val title: String,
    val size: String,
)

@Serializable
data class DownloadResponse(
    val downloadUrl: String,
)

@Serializable
data class DetailsJson(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: JsonElement? = null,
) {
    fun statusAsInt(): Int? = (status as? JsonPrimitive)?.content?.toIntOrNull()
}

@Serializable
data class EpisodeDetailsJson(
    @SerialName("episode_number")
    val episodeNumber: Float,
    val name: String? = null,
    @SerialName("date_upload")
    val dateUpload: String? = null,
    val scanlator: String? = null,
)
