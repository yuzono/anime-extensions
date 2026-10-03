package aniyomi.lib.dailymotionextractor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
class DailyQuality(
    val qualities: Auto? = null,
    val subtitles: Subtitle? = null,
    val error: Error? = null,
    val id: String? = null,
) {
    @Serializable
    class Error(val type: String)
}

@Serializable
class Auto(val auto: List<Item>) {
    @Serializable
    class Item(val url: String)
}

@Serializable
class Subtitle(
    @Serializable(with = SubtitleListSerializer::class)
    val data: List<SubtitleDto>,
)

@Serializable
class SubtitleDto(val label: String, val urls: List<String>)

object SubtitleListSerializer :
    JsonTransformingSerializer<List<SubtitleDto>>(ListSerializer(SubtitleDto.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonArray(element.values.toList())
        else -> JsonArray(emptyList())
    }
}

@Serializable
class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
)

@Serializable
class ProtectedResponse(val data: DataObject) {
    @Serializable
    class DataObject(val video: VideoObject) {
        @Serializable
        class VideoObject(val xid: String)
    }
}
