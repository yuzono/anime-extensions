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
import java.util.concurrent.atomic.AtomicLong

class PlaylistServer(private val client: OkHttpClient) : NanoHTTPD(LOOPBACK_HOSTNAME, 0) {

    @Suppress("OVERRIDE_DEPRECATION")
    val port: Int
        get() = super.getListeningPort()

    private class PlaylistEntry(val text: String, val registeredAt: Long)

    /**
     * All embed m3u8 have a time limit of 3 hours.
     */
    private val playlists = HashMap<String, PlaylistEntry>()
    private val counter = AtomicLong()

    init {
        start(SOCKET_READ_TIMEOUT, true)
    }

    fun register(text: String): String {
        val id = counter.incrementAndGet().toString(Character.MAX_RADIX)
        val now = System.currentTimeMillis()
        synchronized(playlists) {
            playlists.entries.removeAll { now - it.value.registeredAt >= PLAYLIST_TTL_MS }
            while (playlists.size >= MAX_PLAYLISTS) {
                playlists.remove(playlists.minByOrNull { it.value.registeredAt }!!.key)
            }
            playlists[id] = PlaylistEntry(text, now)
        }
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
        val now = System.currentTimeMillis()
        val entry = synchronized(playlists) { playlists[session.uri.substringAfterLast('/')] }
            ?.takeIf { now - it.registeredAt < PLAYLIST_TTL_MS }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", entry.text)
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
                val response = newFixedLengthResponse(
                    if (upstream.code == 206) Status.PARTIAL_CONTENT else Status.OK,
                    upstream.header("Content-Type") ?: "video/mp2t",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
                upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
                upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
                response
            }
        } catch (e: Exception) {
            Log.w(TAG, "seg proxy exception for $url: $e")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Segment fetch failed: $e")
        }
    }

    companion object {
        private const val TAG = "AniWavesSegProxy"

        /** Bounded playlist cache; each entry is a few KB of m3u8 text. */
        private const val PLAYLIST_TTL_MS = 3 * 60 * 60 * 1000L
        private const val MAX_PLAYLISTS = 500
        private const val LOOPBACK_HOSTNAME = "127.0.0.1"
    }
}
