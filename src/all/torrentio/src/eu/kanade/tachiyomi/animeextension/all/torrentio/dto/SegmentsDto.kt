package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SegmentsDto(
    val intro: Segments? = null,
    val outro: Segments? = null,
    @SerialName("post_credits")
    val postCredits: Segments? = null,
    val recap: Segments? = null,
)

@Serializable
data class Segments(
    @SerialName("start_sec")
    val startSec: Double? = null,
    @SerialName("end_sec")
    val endSec: Double? = null,
)
