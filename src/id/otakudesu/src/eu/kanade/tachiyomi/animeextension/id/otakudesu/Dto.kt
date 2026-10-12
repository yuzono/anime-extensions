package eu.kanade.tachiyomi.animeextension.id.otakudesu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class EmbedDto(
    val id: Long,
    @SerialName("i") val mirror: Int,
    val q: String,
)

@Serializable
class AjaxDto(val data: String)

@Serializable
class FiledonDto(val props: FiledonProps) {
    @Serializable
    class FiledonProps(val url: String)
}
