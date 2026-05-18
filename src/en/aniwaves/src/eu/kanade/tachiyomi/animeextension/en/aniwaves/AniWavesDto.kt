package eu.kanade.tachiyomi.animeextension.en.aniwaves

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Old /ajax/server response (VRF-encrypted URL)
@Serializable
data class ServerResponse(
    val result: Result,
) {
    @Serializable
    data class Result(
        val url: String,
    )
}

// New /ajax/sources response (plaintext URL + skip data)
@Serializable
data class SourcesResponse(
    val status: Int? = 0,
    val result: SourcesResult? = null,
)

@Serializable
data class SourcesResult(
    val url: String = "",
    val server: Int? = null,
    val skip_data: SkipData? = null,
    val sources: JsonElement? = null,
    val tracks: JsonElement? = null,
    val htmlGuide: String? = null,
)

@Serializable
data class SkipData(
    val intro: List<Int> = emptyList(),
    val outro: List<Int> = emptyList(),
)

@Serializable
data class VidplaySourcesResponse(
    val sources: JsonElement? = null,
    val intro: IntroOutro? = null,
    val outro: IntroOutro? = null,
    val tracks: JsonElement? = null,
)

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val tracks: List<TrackDto>? = null,
    val intro: IntroOutro? = null,
    val outro: IntroOutro? = null,
    val server: Int? = null,
)

@Serializable
data class TrackDto(val file: String, val kind: String, val label: String = "")

@Serializable
data class IntroOutro(val start: Int, val end: Int)

// Legacy
@Serializable
data class MediaResponseBody(
    val status: Int,
    val result: MediaResult,
) {
    @Serializable
    data class MediaResult(
        val sources: ArrayList<MediaSource>,
        val tracks: ArrayList<SubTrack> = ArrayList(),
    ) {
        @Serializable
        data class MediaSource(val file: String)

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

@Serializable
data class RecommendationsResponse(
    val status: Boolean = false,
    val has_more_pages: Boolean = false,
    val html: String = "",
)
