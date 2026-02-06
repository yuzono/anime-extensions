package eu.kanade.tachiyomi.animeextension.en.animekai

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class VideoCode(
    val type: String,
    val serverId: String,
    val serverName: String,
)

data class VideoData(
    val iframe: String,
    val serverName: String,
)

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
data class IframeResponse(
    val result: IframeDto,
)

// {"url":"https:\/\/megaup.site\/e\/0cv1ZHy0WSyJcOLwFrpK6BPpCQ","skip":...}
@Serializable
data class IframeDto(
    val url: String,
    val skip: SkipDto?,
)

// "skip":{"intro":[0,0],"outro":[0,0]}
@Serializable
data class SkipDto(
    val intro: List<Int>?,
    val outro: List<Int>?,
)
