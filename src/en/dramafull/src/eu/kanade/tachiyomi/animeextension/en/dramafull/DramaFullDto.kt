package eu.kanade.tachiyomi.animeextension.en.dramafull

import eu.kanade.tachiyomi.animesource.model.SAnime
import keiyoushi.utils.UrlUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FilterResponse(
    val data: List<DramaDto>,
    @SerialName("next_page_url")
    val nextPageUrl: String?,
)

@Serializable
data class DramaDto(
    val id: Int,
    val name: String,
    val slug: String,
    @SerialName("type_id")
    val typeId: Int,
    @SerialName("image")
    val thumbnail: String?,
) {
    fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
        url = "/film/$slug"
        title = name
        thumbnail_url = thumbnail?.let { UrlUtils.fixUrl(it, baseUrl) }
    }
}

@Serializable
data class VideoResponse(
    @SerialName("video_source")
    val videoSource: Map<String, String?>,
    val sub: JsonElement? = null,
)
