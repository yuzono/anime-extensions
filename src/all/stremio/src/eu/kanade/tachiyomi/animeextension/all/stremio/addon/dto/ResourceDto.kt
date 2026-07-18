package eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonArray

@Serializable(with = AddonResourceSerializer::class)
enum class AddonResource {
    META,
    STREAM,
    SUBTITLES,
    UNSUPPORTED,
    ;

    companion object {
        fun fromString(value: String): AddonResource = AddonResource.entries.find { it.name.equals(value, ignoreCase = true) } ?: UNSUPPORTED
    }
}

object AddonResourceSerializer : KSerializer<AddonResource> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AddonResource", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AddonResource) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): AddonResource = AddonResource.fromString(decoder.decodeString())
}

@Serializable
data class ResourceDto(
    val name: AddonResource,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

object ResourceListSerializer : JsonTransformingSerializer<List<ResourceDto>>(
    ListSerializer(ResourceDto.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = JsonArray(
        element.jsonArray.map { jsonElement ->
            when (jsonElement) {
                is JsonPrimitive -> JsonObject(
                    mapOf(
                        "name" to jsonElement,
                    ),
                )

                else -> jsonElement
            }
        },
    )
}
