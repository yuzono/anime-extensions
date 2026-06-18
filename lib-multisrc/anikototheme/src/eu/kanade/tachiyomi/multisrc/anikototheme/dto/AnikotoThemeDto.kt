package eu.kanade.tachiyomi.multisrc.anikototheme.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
class ServerResponseDto(
    val result: ServerResultDto,
)

@Serializable
class ServerResultDto(
    val url: String,
    @SerialName("skip_data") val skipData: SkipDataDto? = null,
)

@Serializable
class SkipDataDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)

@Serializable
class SourceResponseDto(
    val sources: JsonElement,
    val tracks: List<TrackDto>? = null,
)

@Serializable
class TrackDto(
    val file: String,
    val kind: String,
    val label: String,
)
