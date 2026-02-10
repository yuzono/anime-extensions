package eu.kanade.tachiyomi.animeextension.en.aniwavese

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ServerResponse(
    val result: Result,
) {

    @Serializable
    data class Result(
        val url: String,
    )
}

@Serializable
data class VideoDto(
    val sources: List<VideoLink>,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val encrypted: Boolean = true,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class VideoLink(val file: String = "")

@Serializable
data class TrackDto(val file: String, val kind: String, val label: String = "")

@Serializable
data class MediaResponseBody(
    val status: Int,
    val result: Result,
) {
    @Serializable
    data class Result(
        val sources: List<Source>,
        val tracks: List<SubTrack> = emptyList(),
    ) {
        @Serializable
        data class Source(
            val file: String,
        )

        @Serializable
        data class SubTrack(
            val file: String,
            val label: String = "",
            val kind: String,
        )
    }
}

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}
