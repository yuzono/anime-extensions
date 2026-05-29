package eu.kanade.tachiyomi.animeextension.es.hentaila

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class Uses(
    @SerialName("search_params") val searchParams: List<String>? = null,
    val params: List<String>? = null,
    val dependencies: List<String>? = null,
)

@Serializable
data class Node(
    val type: String,
    val data: JsonArray? = null,
    val uses: Uses? = null,
)

@Serializable
data class HentailaJsonDto(
    val type: String,
    val nodes: List<Node?>,
)

data class VideoData(
    val name: String,
    val url: String,
)
