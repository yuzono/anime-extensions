package eu.kanade.tachiyomi.animeextension.pt.animefire

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID

// Use Android's OkHttp TLS stack for the CDN, including HLS initialization and media segments.
internal class AnimeFireStreamServer(
    private val client: OkHttpClient,
    private val headers: Headers,
) : NanoHTTPD("127.0.0.1", 0) {
    private val path = "/${UUID.randomUUID()}/stream"

    @Synchronized
    fun localUrl(url: String): String {
        val upstream = url.toHttpUrl()
        require(isCdnUrl(upstream)) { "Servidor de vídeo não suportado" }
        if (!isAlive) start()
        return proxyUrl(upstream)
    }

    private fun proxyUrl(url: HttpUrl): String = "http://127.0.0.1:$listeningPort$path"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("url", url.toString())
        .build()
        .toString()

    private fun isCdnUrl(url: HttpUrl): Boolean = url.isHttps && url.host == "akumast.net" && url.encodedPath.startsWith("/i/") && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    override fun handle(session: IHTTPSession): Response {
        if (session.uri != path) return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use GET or HEAD")
        }
        val url = session.parameters["url"]?.firstOrNull()?.toHttpUrlOrNull()
        if (url == null || !isCdnUrl(url)) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid CDN URL")
        }
        val request = Request.Builder().url(url).headers(headers).apply {
            // Byte-range HLS segments and seeking must retain the upstream range semantics.
            session.headers["range"]?.let { header("Range", it) }
            if (session.method == Method.HEAD) head()
        }.build()
        return try {
            serve(request, session.method == Method.HEAD)
        } catch (_: IOException) {
            newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Video CDN unavailable")
        }
    }

    private fun serve(request: Request, head: Boolean): Response {
        val upstream = client.newCall(request).execute()
        try {
            val status = Status.lookup(upstream.code) ?: Status.SERVICE_UNAVAILABLE
            if (!upstream.isSuccessful) {
                upstream.close()
                return newFixedLengthResponse(status, MIME_PLAINTEXT, "Video CDN returned ${upstream.code}")
            }
            if (head) {
                val response = newFixedLengthResponse(status, "application/octet-stream", ByteArrayInputStream(byteArrayOf()), upstream.body.contentLength())
                upstream.close()
                return response.copyRangeHeaders(upstream)
            }
            val source = upstream.body.source()
            if (source.rangeEquals(0, "#EXTM3U".encodeUtf8())) {
                val text = upstream.body.string()
                val parent = upstream.request.url
                upstream.close()
                return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritePlaylist(text, parent))
            }
            // Do not alter the fMP4 bytes, even though the CDN calls these files JPEG images.
            val stream = object : FilterInputStream(upstream.body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        upstream.close()
                    }
                }
            }
            val length = upstream.body.contentLength()
            return if (length >= 0) {
                newFixedLengthResponse(status, "video/mp4", stream, length)
            } else {
                newChunkedResponse(status, "video/mp4", stream)
            }.copyRangeHeaders(upstream)
        } catch (e: Exception) {
            upstream.close()
            throw e
        }
    }

    private fun Response.copyRangeHeaders(upstream: okhttp3.Response): Response = apply {
        listOf("Content-Range", "Accept-Ranges").forEach { name ->
            upstream.header(name)?.let { addHeader(name, it) }
        }
    }

    private fun rewritePlaylist(text: String, parent: HttpUrl): String = text.lineSequence().joinToString("\n") { line ->
        fun rewrite(uri: String): String {
            val resolved = parent.resolve(uri) ?: throw IOException("Invalid HLS URI")
            if (!isCdnUrl(resolved)) throw IOException("Unsupported HLS CDN URL")
            return proxyUrl(resolved)
        }
        when {
            line.isBlank() -> line
            line.startsWith("#") -> line.replace(URI_ATTRIBUTE) { match ->
                "URI=\"${rewrite(match.groupValues[1])}\""
            }
            else -> rewrite(line.trim())
        }
    }

    companion object {
        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
    }
}
