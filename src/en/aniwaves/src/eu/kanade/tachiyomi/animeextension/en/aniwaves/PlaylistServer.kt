package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PlaylistServer(private val client: OkHttpClient) : NanoHTTPD(0) {

    @Suppress("OVERRIDE_DEPRECATION")
    val port: Int
        get() = super.getListeningPort()

    private val playlists = ConcurrentHashMap<String, String>()
    private val counter = AtomicLong()

    init {
        start(SOCKET_READ_TIMEOUT, true)
    }

    fun register(text: String): String {
        val id = counter.incrementAndGet().toString(Character.MAX_RADIX)
        playlists[id] = text
        return "http://127.0.0.1:$port/pl/$id"
    }

    fun segmentProxyUrl(url: String, referer: String?, userAgent: String?): String = buildString {
        append("http://127.0.0.1:$port/seg?url=")
        append(java.net.URLEncoder.encode(url, Charsets.UTF_8.name()))
        if (!referer.isNullOrBlank()) {
            append("&referer=").append(java.net.URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            append("&ua=").append(java.net.URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith("/pl/") -> servePlaylist(session)
        session.uri.startsWith("/seg") -> serveSegment(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun servePlaylist(session: IHTTPSession): Response {
        val text = playlists[session.uri.substringAfterLast('/')]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", text)
    }

    private fun serveSegment(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()?.takeIf(String::isNotBlank)
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        val requestBuilder = Request.Builder().url(url)
            .header("Accept", "*/*")
        session.parameters["ua"]?.firstOrNull()?.takeIf(String::isNotBlank)?.let {
            requestBuilder.header("User-Agent", it)
        }
        session.parameters["referer"]?.firstOrNull()?.takeIf(String::isNotBlank)?.let {
            requestBuilder.header("Referer", it)
        }
        session.headers["range"]?.takeIf(String::isNotBlank)?.let {
            requestBuilder.header("Range", it)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { upstream ->
                if (!upstream.isSuccessful) {
                    val errBody = runCatching { upstream.body.string() }.getOrNull().orEmpty().take(200)
                    Log.w(TAG, "seg proxy <- upstream ${upstream.code} for $url | $errBody")
                    return newFixedLengthResponse(
                        Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Upstream ${upstream.code} for $url | $errBody",
                    )
                }
                val bytes = upstream.body.bytes()
                newFixedLengthResponse(
                    Status.OK,
                    upstream.header("Content-Type") ?: "video/mp2t",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "seg proxy exception for $url: $e")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Segment fetch failed: $e")
        }
    }

    companion object {
        private const val TAG = "AniWavesSegProxy"
    }
}
