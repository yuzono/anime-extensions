package eu.kanade.tachiyomi.animeextension.all.missav

import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder

/**
 * Originally implemented by Programmer-0-0 from AnimePahe
 * HLS only — no AES-128 decryption, no MP4/Range proxying
 */
object MissAvHlsServer : NanoHTTPD(0) {

    val port: Int
        get() = super.getListeningPort()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var baseHeaders: Headers = Headers.Builder().build()

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) {
            start()
            isRunning = true
        }
    }

    fun processVideoList(
        client: OkHttpClient,
        videos: List<Video>,
        baseHeaders: Headers,
    ): List<Video> {
        this.client = client
        this.baseHeaders = baseHeaders
        ensureStarted()
        return videos.map { video ->
            if (video.url.contains(".m3u8", ignoreCase = true)) {
                video.copyWithLocalUrl(createLocalM3u8Url(video.url))
            } else {
                video
            }
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val headers = resolveUpstreamHeaders()
            val playlist = fetchString(url, headers)
            val content = rewritePlaylist(playlist, url)
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val headers = resolveUpstreamHeaders()
            val data = fetchBytes(url, headers)
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(data))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun resolveUpstreamHeaders(): Headers {
        val stored = baseHeaders
        return Headers.Builder().apply {
            for (i in 0 until stored.size) {
                val name = stored.name(i)
                when (name.lowercase()) {
                    "range", "host", "connection", "accept-encoding" -> Unit
                    else -> add(name, stored.value(i))
                }
            }
            if (this["Accept"] == null) add("Accept", "*/*")
        }.build()
    }

    private fun createLocalM3u8Url(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://localhost:$port/m3u8?url=$encodedUrl"
    }

    private fun createLocalSegmentUrl(segmentUrl: String): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        return "http://localhost:$port/segment?url=$encodedUrl&segment=.ts"
    }

    private fun Video.copyWithLocalUrl(localUrl: String): Video = Video(
        url = localUrl,
        quality = quality,
        videoUrl = localUrl,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        headers = headers,
    )

    private fun fetchString(url: String, headers: Headers): String = requireClient().newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch playlist: ${response.code}")
        }
        response.body.string()
    }

    private fun fetchBytes(url: String, headers: Headers): ByteArray = requireClient().newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch segment: ${response.code}")
        }
        response.body.bytes()
    }

    private fun requireClient(): OkHttpClient = client ?: throw IOException("MissAV HLS server is not initialized")

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()

        content.lines().forEach { line ->
            when {
                line.startsWith("#") || line.isBlank() -> modifiedLines.add(line)
                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line)
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        modifiedLines.add(createLocalM3u8Url(resolvedUrl))
                    } else {
                        modifiedLines.add(createLocalSegmentUrl(resolvedUrl))
                    }
                }
            }
        }

        return modifiedLines.joinToString("\n")
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri
}
