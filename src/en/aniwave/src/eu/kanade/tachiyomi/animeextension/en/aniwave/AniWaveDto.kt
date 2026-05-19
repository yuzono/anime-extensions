package eu.kanade.tachiyomi.animeextension.en.aniwave

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ServerResponseDto(val result: ServerResultDto)

@Serializable
data class ServerResultDto(val url: String)

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val tracks: List<TrackDto>? = null,
    val intro: IntroOutroDto? = null,
    val outro: IntroOutroDto? = null,
    val server: Int? = null,
)

@Serializable
data class TrackDto(val file: String, val kind: String, val label: String = "")

@Serializable
data class IntroOutroDto(val start: Int, val end: Int)

@Serializable
data class ResultResponse(val result: String) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}
