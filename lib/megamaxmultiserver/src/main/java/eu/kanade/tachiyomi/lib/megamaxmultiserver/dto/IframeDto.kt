package eu.kanade.tachiyomi.lib.megamaxmultiserver.dto

import kotlinx.serialization.Serializable

@Serializable
data class IframeResponse(
    val props: Props,
)

@Serializable
data class Props(
    val streams: Streams
)

@Serializable
data class Streams(
    val data: List<Data>,
    val msg: String,
    val status: String,
)

@Serializable
data class Data(
    val mirrors: List<Mirror>,
    val resolution: String,
    val size: Long
)

@Serializable
data class Mirror(
    val driver: String,
    val link: String,
)
