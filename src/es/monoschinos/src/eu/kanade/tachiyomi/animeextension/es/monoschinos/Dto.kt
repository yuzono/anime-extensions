package eu.kanade.tachiyomi.animeextension.es.monoschinos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class EpisodesDto(
    val eps: List<EpsDto>,
    val perpage: Int? = null,
)

@Serializable
class EpsDto(
    val num: JsonElement,
) {
    val numStr: String get() = num.jsonPrimitive.content
}
