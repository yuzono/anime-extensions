package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SuggestResponse(
    val list: List<SuggestInfo> = emptyList(),
    val page: Int = 1,
    @SerialName("pagecount") val pageCount: Int = 1,
)

@Serializable
class SuggestInfo(
    val id: Int,
    val name: String,
    val pic: String,
)
