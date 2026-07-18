package eu.kanade.tachiyomi.animeextension.all.animeonsen.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class AnimeListResponse(
    val content: List<AnimeListItem>,
    val cursor: AnimeListCursor,
)

@Serializable
data class AnimeListItem(
    val content_id: String,
    val content_title: String? = null,
    val content_title_en: String? = null,
    val content_title_jp: String? = null,
    val content_image: String? = null,
    val thumbnail: String? = null,
)

@Serializable
data class AnimeListCursor(val next: JsonArray)

@Serializable
data class AnimeDetails(
    val content_id: String,
    val content_title: String?,
    val content_title_en: String?,
    val mal_id: Int? = null,
    val subtitle_support: Boolean? = null,
    @Serializable(with = MalSerializer::class)
    val mal_data: MalData?,
)

@Serializable
data class EpisodeDto(
    @SerialName("contentTitle_episode_en")
    val nameEn: String? = null,
    @SerialName("contentTitle_episode_jp")
    val nameJp: String? = null,
)

@Serializable
data class MalData(
    val genres: List<Genre>?,
    val status: String?,
    val studios: List<Studio>?,
    val synopsis: String?,
    val mean_score: Double? = null,
    val rating: String? = null,
)

@Serializable
data class Genre(val name: String)

@Serializable
data class Studio(val name: String)

@Serializable
data class VideoData(
    val metadata: MetaData,
    val uri: StreamData,
)

@Serializable
data class MetaData(val subtitles: Map<String, String>)

@Serializable
data class StreamData(
    val stream: String,
    val subtitles: Map<String, String>,
)

@Serializable
data class MeilisearchResponse(
    val hits: List<AnimeListItem>,
)

@Serializable
data class SearchResponse(
    val status: Int,
    val result: List<AnimeListItem>,
)

object MalSerializer : JsonTransformingSerializer<MalData>(MalData.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> JsonObject(emptyMap())
        else -> element
    }
}
