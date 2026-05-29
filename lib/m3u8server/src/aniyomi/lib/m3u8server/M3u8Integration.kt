package aniyomi.lib.m3u8server

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

/**
 * M3U8 Server integration with Q1N extension
 */
class M3u8Integration(
    client: OkHttpClient,
    private val serverManager: M3u8ServerManager = M3u8ServerManager(client),
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
     * Processes an M3U8 video through the local server
     * @param originalVideo Original video with M3U8 URL
     * @return Processed video with local URL
     */
    private fun processM3u8Video(originalVideo: Video): Video {
        val processedUrl = serverManager.processM3u8Url(originalVideo.url)
        return Video(
            videoUrl = processedUrl ?: originalVideo.url,
            url = originalVideo.url,
            quality = originalVideo.quality,
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
            if (isM3u8Url(video.url)) {
                processM3u8Video(video)
            } else {
                video
            }
        }
    }

    /**
     * Checks if a URL is an M3U8 file
     * @param url URL to check
     * @return true if it's an M3U8
     */
    private fun isM3u8Url(url: String): Boolean {
        val m3u8Regex = Regex("""\.m3u8($|\?|#)""", RegexOption.IGNORE_CASE)
        return m3u8Regex.containsMatchIn(url) ||
            url.contains("application/vnd.apple.mpegurl", ignoreCase = true)
    }

    /**
     * Gets server information
     * @return String with server information
     */
    fun getServerInfo(): String = serverManager.getServerInfo()

    /**
     * Stops the server
     */
    fun stopServer() {
        serverManager.stopServer()
    }

    /**
     * Checks if the server is running
     * @return true if it's running
     */
    fun isServerRunning(): Boolean = serverManager.isRunning()
}
