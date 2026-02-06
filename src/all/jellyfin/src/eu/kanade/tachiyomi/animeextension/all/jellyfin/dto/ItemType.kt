package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ItemTypeSerializer::class)
enum class ItemType {
    BoxSet,
    Movie,
    Season,
    Series,
    Episode,
    Other,
    ;

    companion object {
        fun fromString(value: String): ItemType = ItemType.entries.find { it.name.equals(value, ignoreCase = true) } ?: Other
    }
}

object ItemTypeSerializer : KSerializer<ItemType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ItemType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ItemType) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ItemType = ItemType.fromString(decoder.decodeString())
}
