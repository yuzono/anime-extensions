package eu.kanade.tachiyomi.animeextension.es.monoschinos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Answer of the `data-ajax` endpoint, which only points at the real list now. */
@Serializable
class EpisodesDto(
    @SerialName("paginate_url")
    val paginateUrl: String? = null,
    val perpage: Int? = null,
)

@Serializable
class CapListDto(
    val caps: List<CapDto> = emptyList(),
)

@Serializable
class CapDto(
    val episodio: JsonElement,
    val url: String,
) {
    val numStr: String get() = episodio.jsonPrimitive.content
}
