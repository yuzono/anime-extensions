package eu.kanade.tachiyomi.animeextension.ru.jutsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerResponse(
    val success: Boolean = false,
    val data: String = "",
)

@Serializable
data class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
data class KodikVideoInfo(val src: String)

@Serializable
data class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
    @SerialName("1080") val full: List<KodikVideoInfo> = emptyList(),
)

@Serializable
data class KodikData(val links: KodikVideoQuality)
