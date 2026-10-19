package aniyomi.lib.kodikextractor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Signed parameters the Kodik player embeds in the page as a JSON blob assigned to
 * `urlParams`. They have to be posted back to the player to get the actual stream URLs.
 */
@Serializable
class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(val src: String)

@Serializable
class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
    @SerialName("1080") val full: List<KodikVideoInfo> = emptyList(),
)

@Serializable
class KodikData(val links: KodikVideoQuality)
