package eu.kanade.tachiyomi.animeextension.all.animeonsen.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class SearchRequest(val q: String)

@Serializable
class AnimeListResponse(
    val content: List<AnimeListItem>,
    private val cursor: AnimeListCursor,
) {
    val hasNextPage get() = cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
}

@Serializable
class AnimeListItem(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_title") val contentTitle: String? = null,
    @SerialName("content_title_en") val contentTitleEn: String? = null,
    @SerialName("content_title_jp") val contentTitleJp: String? = null,
    @SerialName("content_image") val contentImage: String? = null,
    val thumbnail: String? = null,
)

@Serializable
class AnimeListCursor(val next: JsonArray)

@Serializable
class AnimeDetails(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_title") val contentTitle: String?,
    @SerialName("content_title_en") val contentTitleEn: String?,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("previous_season") val previousSeason: String? = null,
    @SerialName("next_season") val nextSeason: String? = null,
    @SerialName("mal_data")
    @Serializable(with = MalSerializer::class)
    val malData: MalData?,
)

@Serializable
class EpisodeDto(
    @SerialName("contentTitle_episode_en") val nameEn: String? = null,
    @SerialName("contentTitle_episode_jp") val nameJp: String? = null,
)

@Serializable
class MalData(
    val genres: List<Genre>?,
    val status: String?,
    val studios: List<Studio>?,
    val synopsis: String?,
    @SerialName("mean_score") val meanScore: Double? = null,
    val rating: String? = null,
)

@Serializable
class Genre(val name: String)

@Serializable
class Studio(val name: String)

@Serializable
class VideoData(
    val metadata: MetaData,
    val uri: StreamData,
)

@Serializable
class MetaData(val subtitles: Map<String, String>)

@Serializable
class StreamData(
    val stream: String,
    val subtitles: Map<String, String>,
)

@Serializable
class MeilisearchResponse(val hits: List<AnimeListItem>)

@Serializable
class SearchResponse(val result: List<AnimeListItem>)

object MalSerializer : JsonTransformingSerializer<MalData>(MalData.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> JsonObject(emptyMap())
        else -> element
    }
}
