package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Headers
import okhttp3.Response

abstract class AnimeHttpHosterSource : AnimeHttpSource() {
    open suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode)
        .parallelCatchingFlatMapBlocking(::getVideoList)

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    protected fun legacyHoster(
        hosterUrl: String = "",
        hosterName: String = "",
        videoList: List<Video>? = null,
        internalData: String = "",
    ) = try {
        Hoster(hosterUrl, hosterName, videoList, internalData, lazy = false)
    } catch (_: Throwable) {
        Hoster(hosterUrl, hosterName, videoList, internalData)
    }

    fun legacyVideo(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) = try {
        Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            mpvArgs = mpvArgs,
            ffmpegStreamArgs = ffmpegStreamArgs,
            ffmpegVideoArgs = ffmpegVideoArgs,
            internalData = internalData,
            initialized = initialized,
        )
    } catch (_: Throwable) {
        Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            mpvArgs = mpvArgs,
            ffmpegStreamArgs = ffmpegStreamArgs,
            ffmpegVideoArgs = ffmpegVideoArgs,
            internalData = internalData,
            initialized = initialized,
            videoPageUrl = "",
        )
    }

    fun Video.copyLegacy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video = legacyVideo(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
    )
}
