package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class LivewireDto(
    val components: List<ComponentDto>,
) {
    @Serializable
    data class ComponentDto(
        val snapshot: String,
        val effects: EffectsDto,
    ) {
        @Serializable
        data class EffectsDto(
            val html: String,
            val dispatches: List<DispatchDto> = emptyList(),
        )
    }
}

@Serializable
data class DispatchDto(
    val name: String,
    val params: DispatchParamsDto? = null,
)

@Serializable
data class DispatchParamsDto(
    val items: JsonArray? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)
