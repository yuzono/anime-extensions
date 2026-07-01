package eu.kanade.tachiyomi.multisrc.anikototheme.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
class ResultResponse(
    private val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
class ServerResponseDto(
    val result: ServerResultDto,
)

@Serializable
class ServerResultDto(
    val url: String,
    @SerialName("skip_data") val skipData: SkipDataDto? = null,
)

@Serializable
class SkipDataDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)

@Serializable
class SourceResponseDto(
    @Serializable(with = SourcesSerializer::class) val sources: String,
    val tracks: List<TrackDto>? = null,
)

@Serializable
class TrackDto(
    val file: String,
    val kind: String,
    val label: String = "",
)

@Serializable
class MapperServerDto(
    val sub: MapperLinkDto? = null,
    val dub: MapperLinkDto? = null,
)

@Serializable
class MapperLinkDto(
    val url: String,
)

object SourcesSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String = when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
        is JsonObject -> element["file"]?.jsonPrimitive?.content
        is JsonArray -> element.firstOrNull()?.let {
            when (it) {
                is JsonObject -> it["file"]?.jsonPrimitive?.content
                is JsonPrimitive -> it.content
                else -> null
            }
        }
        is JsonPrimitive -> element.content
    } ?: throw IllegalStateException("No valid m3u8 found in sources")

    override fun serialize(encoder: Encoder, value: String): Unit = throw UnsupportedOperationException("Serialization not supported")
}
