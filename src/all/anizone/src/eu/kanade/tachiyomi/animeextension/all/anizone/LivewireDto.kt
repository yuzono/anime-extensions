package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
            val dispatches: List<DispatchDto> = emptyList(),
        )
    }
}

@Serializable
class DispatchDto(
    val name: String,
    val params: DispatchParamsDto? = null,
)

@Serializable
class DispatchParamsDto(
    val items: JsonArray? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

@Serializable
class LivewireCall(
    val path: String = "",
    val method: String,
    val params: List<JsonElement>,
)

@Serializable
class LivewirePayload(
    @SerialName("_token") val token: String,
    val components: List<LivewireComponentPayload>,
)

@Serializable
class LivewireComponentPayload(
    val snapshot: String,
    val updates: JsonObject,
    val calls: List<LivewireCall>,
)
