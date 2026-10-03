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
class MapperServerDto(
    val sub: MapperLinkDto? = null,
    val dub: MapperLinkDto? = null,
)

@Serializable
class MapperLinkDto(
    val url: String? = null, // URL can be missing ill let Alpha-782 fix this
)

// ---------- MegaPlay response ----------

@Serializable
class MegaPlaySourcesDto(
    val enc: String? = null,
    @Serializable(with = MegaPlaySourcesFieldSerializer::class)
    val sources: String? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
    val intro: MegaPlaySkipDto? = null,
    val outro: MegaPlaySkipDto? = null,
)

@Serializable
class MegaPlayTrackDto(
    val file: String,
    val label: String = "",

)

@Serializable
class MegaPlaySkipDto(
    val start: Int = 0,
    val end: Int = 0,
)

object MegaPlaySourcesFieldSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonObject -> element["file"]?.jsonPrimitive?.content
            is JsonArray -> element.firstOrNull()?.let {
                when (it) {
                    is JsonObject -> it["file"]?.jsonPrimitive?.content
                    is JsonPrimitive -> it.content
                    else -> null
                }
            }
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?): Unit = throw UnsupportedOperationException("Serialization not supported")
}

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
