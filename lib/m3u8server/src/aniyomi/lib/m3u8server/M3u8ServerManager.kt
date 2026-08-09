package aniyomi.lib.m3u8server

import android.util.Log
import okhttp3.OkHttpClient

/**
 * M3U8 Server manager to facilitate usage
 */
class M3u8ServerManager(
    private val client: OkHttpClient,
    private val fallbackClient: OkHttpClient? = null,
) {
    private val tag by lazy { javaClass.simpleName }
    private var server: M3u8HttpServer? = null

    /**
     * Starts the M3U8 server on the specified port
     * @param port Port where the server will run (0 for random port, default: 0)
     */
    @Synchronized
    fun startServer(port: Int = 0) {
        if (server != null) {
            Log.d(tag, "Server is already running")
            return
        }

        try {
            server = M3u8HttpServer(client, port, fallbackClient)
            server?.start()
            Log.d(tag, "Server started on port: ${server?.port}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            server = null
            throw e
        }
    }

    /**
     * Stops the M3U8 server
     */
    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    /**
     * Checks if the server is running
     */
    fun isRunning(): Boolean = server?.isRunning() ?: false

    /**
     * Gets the server base URL
     */
    fun getServerUrl(): String? = server?.let { "http://localhost:${it.port}" }

    /**
     * Processes an M3U8 file through the server.
     *
     * @param m3u8Url Original M3U8 file URL
     * @param referer optional Referer to encode into the proxied URL — see
     *   [M3u8HttpServer.createLocalUrl] for why this is the root-cause fix
     *   for Cloudflare-fronted CDNs that 403 on null-Referer requests.
     * @param userAgent optional User-Agent to encode alongside the referer.
     * @return Processed M3U8 content as a local URL string
     */
    fun processM3u8Url(m3u8Url: String, referer: String? = null, userAgent: String? = null): String? = server?.createLocalUrl(m3u8Url, referer, userAgent)

    /**
     * Processes a segment through the server
     * @param segmentUrl Original segment URL
     * @param headers Optional headers to use for the request
     * @return Processed segment data
     */
    suspend fun processSegmentUrl(segmentUrl: String, headers: Map<String, String> = emptyMap()): ByteArray? = server?.processSegmentUrl(segmentUrl, headers)

    /**
     * Gets server information
     */
    fun getServerInfo(): String = if (isRunning()) {
        val serverUrl = getServerUrl() ?: "Unknown"
        """
            M3U8 HTTP Server is running
            Base URL: $serverUrl
            Status: ${server?.getHealthStatus()}
        """.trimIndent()
    } else {
        "M3U8 HTTP Server is not running"
    }
}
