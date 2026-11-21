package eu.kanade.tachiyomi.animesource.model

import okhttp3.Headers

/**
 * A sub/dub track.
 */
data class Track(val url: String, val lang: String)

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

/**
 * A class defining a timestamp. Displayed as video chapters in the app.
 *
 * @param start Start of the timestamp, in seconds.
 * @param end End of the timestamp, in seconds.
 * @param name Display name of timestamp.
 * @param type Type of timestamp.
 */
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

/**
 * The instance that contains the data needed to watch a video.
 *
 * @param videoUrl The url of the video that's passed to mpv.
 * @param videoTitle The title of the video displayed in the app.
 * @param resolution The video resolution. Useful for sorting.
 * @param bitrate The video bitrate. Useful for sorting.
 * @param headers The headers of the video.
 * @param preferred Set to preferred to give priority when loading.
 * Note that multiple videos may have this to true.
 * @param subtitleTracks The list of external subtitle tracks.
 * @param audioTracks The list of external audio tracks.
 * @param timestamps The list of timestamps.
 * @param mpvArgs Extra arguments passed to mpv.
 * @param ffmpegStreamArgs Extra arguments passed to the video stream when downloading.
 * @param ffmpegVideoArgs Extra arguments passed to ffmpeg when downloading.
 * @param internalData Internal data used by resolveVideo.
 * @param initialized Whether to call resolveVideo.
 */
@Suppress("unused_parameter")
data class Video(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {
    @Deprecated(
        message = "Use the new Video constructor",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith(
            expression = "Video(videoTitle = quality, videoUrl = videoUrl ?: \"null\", headers = headers, subtitleTracks = subtitleTracks, audioTracks = audioTracks)",
        ),
    )
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoTitle = quality,
        videoUrl = videoUrl ?: "null",
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    )
}
