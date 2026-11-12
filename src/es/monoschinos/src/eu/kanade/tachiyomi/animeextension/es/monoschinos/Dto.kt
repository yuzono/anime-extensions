package eu.kanade.tachiyomi.animeextension.es.monoschinos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class EpisodesDto(
    val eps: List<EpsDto>,
    val perpage: Int? = null,
)

@Serializable
class EpsDto(
    val num: JsonPrimitive,
) {
    val numStr: String get() = num.content
}
