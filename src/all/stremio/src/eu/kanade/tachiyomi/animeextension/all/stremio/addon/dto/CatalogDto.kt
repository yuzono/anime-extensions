package eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ExtraTypeSerializer::class)
enum class ExtraType {
    GENRE,
    SEARCH,
    SKIP,
    UNKNOWN,

    ;

    companion object {
        fun fromString(value: String): ExtraType = ExtraType.entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

object ExtraTypeSerializer : KSerializer<ExtraType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExtraType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExtraType) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ExtraType = ExtraType.fromString(decoder.decodeString())
}

@Serializable
data class CatalogDto(
    val id: String,
    val type: String,
    val name: String? = null,
    val extra: List<ExtraDto>? = null,

    @Transient
    var transportUrl: String = "",
) {
    @Serializable
    data class ExtraDto(
        @SerialName("name")
        val type: ExtraType,
        val isRequired: Boolean? = null,
        val options: List<String>? = null,
    )

    fun hasRequired(type: ExtraType): Boolean = extra.orEmpty().any {
        it.type == type && it.isRequired == true
    }
}
