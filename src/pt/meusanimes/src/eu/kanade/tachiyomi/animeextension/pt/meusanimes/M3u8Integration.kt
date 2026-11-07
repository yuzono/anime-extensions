package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import android.util.Log
import aniyomi.lib.m3u8server.M3u8ServerManager
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

/**
 * M3U8 Server integration with Q1N extension
 *
 * @param client the primary OkHttp client used for upstream fetches; may
 *   carry a Cloudflare-solve interceptor.
 * @param fallbackClient optional secondary OkHttp client consulted when
 *   the primary client throws on a Cloudflare-solve failure. Should be
 *   CF-interceptor-free; supplies the header-gated retry leg.
 */
class M3u8Integration(
    client: OkHttpClient,
    fallbackClient: OkHttpClient? = null,
    private val serverManager: M3u8ServerManager = M3u8ServerManager(client, fallbackClient),
) {

    private val tag by lazy { javaClass.simpleName }

    private fun initializeServer() {
        if (!serverManager.isRunning()) {
            try {
                serverManager.startServer() // Uses random port by default
                Log.d(tag, "M3U8 server initialized on port: ${serverManager.getServerUrl()}")
            } catch (e: Exception) {
                // Log error but don't crash
                Log.e(tag, "Failed to start M3U8 server: ${e.message}")
            }
        }
    }

    /**
     * Processes an M3U8 video through the local server. The original
     * [Video.headers] is consulted to derive `Referer` and `User-Agent`,
     * which are then re-encoded into the proxied URL so the m3u8 server
     * can re-issue them on the upstream fetch even if the media player
     * (mpv / ExoPlayer) does not carry them through to localhost.
     */
    private fun processM3u8Video(originalVideo: Video): Video {
        val referer = originalVideo.headers?.get("Referer")
        val userAgent = originalVideo.headers?.get("User-Agent")
        val processedUrl = serverManager.processM3u8Url(originalVideo.videoUrl, referer, userAgent)
        return Video(
            videoUrl = processedUrl ?: originalVideo.videoUrl,
            url = originalVideo.videoUrl,
            quality = originalVideo.videoTitle,
            subtitleTracks = originalVideo.subtitleTracks,
            audioTracks = originalVideo.audioTracks,
            headers = originalVideo.headers,
        )
    }

    /**
     * Processes a list of videos, identifying and processing only M3U8 files.
     * The M3U8 files should be a direct link to the M3U8 file which consists of segments, not a playlist.
     * @param videos Original video list
     * @return Processed video list
     */
    fun processVideoList(videos: List<Video>): List<Video> {
        initializeServer()
        return videos.map { video ->
            processM3u8Video(video)
        }
    }
}
