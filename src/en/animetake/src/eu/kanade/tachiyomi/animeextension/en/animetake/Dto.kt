package eu.kanade.tachiyomi.animeextension.en.animetake

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers

@Serializable
class VidaraStreamRequestDto(
    private val filecode: String,
    private val device: String = "web",
)

@Serializable
class VidaraStreamDto(
    @SerialName("streaming_url") private val streamingUrl: String,
    private val subtitles: List<VidaraSubtitleDto>? = null,
) {
    fun toVideo(headers: Headers): Video {
        val subtitleTracks = subtitles.orEmpty()
            .filter { it.type == 0 }
            .map { Track(it.filePath, it.language ?: "Subtitle") }
        return Video(videoUrl = streamingUrl, videoTitle = "Vidara", headers = headers, subtitleTracks = subtitleTracks)
    }
}

@Serializable
class VidaraSubtitleDto(
    val type: Int,
    val language: String? = null,
    @SerialName("file_path") val filePath: String,
)
