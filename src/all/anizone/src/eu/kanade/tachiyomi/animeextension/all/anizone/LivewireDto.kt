package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
class LivewireDto(
    val components: List<ComponentDto>,
) {
    @Serializable
    class ComponentDto(
        val snapshot: String,
        val effects: EffectsDto,
    ) {
        @Serializable
        class EffectsDto(
            val html: String,
        )
    }
}

@Serializable
class LivewireRequestDto(
    @SerialName("_token") val token: String,
    val components: List<LivewireComponentRequestDto>,
)

@Serializable
class LivewireComponentRequestDto(
    val calls: JsonArray,
    val snapshot: String,
    val updates: JsonObject,
)
