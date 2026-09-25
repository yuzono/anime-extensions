package eu.kanade.tachiyomi.animeextension.en.anipm

import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * So, we require this local proxy server because,
 * apparently, MPV sends a 'user-agent' header if we
 * inherit app headers, and the website rejects this
 * invalid header. It is possible to remove headers
 * entirely, but I am not willing to exclude headers
 * on segment call in case site eventually blocks
 * requests without headers. Better be safe than sorry.
 */
class SettlarProxy(
    baseHeaders: Headers,
) : NanoHTTPD("127.0.0.1", 0) {

    private val upstreamHeaders: Headers = baseHeaders.newBuilder()
        .apply {
            set("Origin", "https://embed.settlar.io")
        }
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
        .build()

    private val subtitleCache = ConcurrentHashMap<String, String>()

    fun proxyUrl(url: String): String = "http://127.0.0.1:$listeningPort/proxy?url=${URLEncoder.encode(url, "UTF-8")}"

    fun subtitleUrl(url: String): String = "http://127.0.0.1:$listeningPort/subtitle.vtt?url=${URLEncoder.encode(url, "UTF-8")}"

    private fun getOrCacheSubtitle(originalUrl: String): String {
        subtitleCache[originalUrl]?.let { return it }
        return try {
            val text = client.newCall(
                Request.Builder().url(originalUrl).headers(upstreamHeaders).build(),
            ).execute().use { res ->
                if (!res.isSuccessful) return@use ""
                res.body.string()
            }

            val targetUrl = if (text.startsWith("#EXTM3U")) {
                val firstLine = text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (firstLine != null) resolveUrl(originalUrl, firstLine) else originalUrl
            } else {
                originalUrl
            }

            val subText = client.newCall(
                Request.Builder().url(targetUrl).headers(upstreamHeaders).build(),
            ).execute().use { subRes ->
                if (!subRes.isSuccessful) return@use ""
                subRes.body.string()
            }

            if (subText.isBlank()) return "WEBVTT\n\n"

            subtitleCache[originalUrl] = subText
            subText
        } catch (_: Exception) {
            "WEBVTT\n\n"
        }
    }

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (uri.endsWith(".vtt", true) || uri.contains("subtitle", true)) {
            val url = session.parameters["url"]?.firstOrNull()
                ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")

            val vttContent = getOrCacheSubtitle(url)
            return newFixedLengthResponse(Status.OK, "text/vtt", vttContent)
        }

        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")

        return try {
            val response = client.newCall(
                Request.Builder().url(url).headers(upstreamHeaders).build(),
            ).execute()

            if (!response.isSuccessful) {
                val code = response.code
                val snippet = runCatching { response.body.string().take(200) }.getOrDefault("")
                response.close()
                return newFixedLengthResponse(
                    Status.lookup(code) ?: Status.INTERNAL_ERROR,
                    "text/plain",
                    "Upstream $code: $snippet",
                )
            }

            val contentType = response.header("Content-Type") ?: ""
            val parsedUrl = url.toHttpUrl()
            val isManifest = parsedUrl.encodedPath.endsWith(".m3u8", true) || contentType.contains("mpegurl", true)

            if (!isManifest) {
                val body = response.body
                val length = body.contentLength()
                val mime = contentType.ifBlank { "application/octet-stream" }
                val stream = body.byteStream()
                if (length > 0) {
                    newFixedLengthResponse(Status.OK, mime, stream, length)
                } else {
                    newChunkedResponse(Status.OK, mime, stream)
                }
            } else {
                val text = response.body.string()
                response.close()
                newFixedLengthResponse(
                    Status.OK,
                    "application/vnd.apple.mpegurl",
                    rewriteManifest(text, url),
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
    }

    private fun rewriteManifest(text: String, parentUrl: String): String = text.split("\n").joinToString("\n") { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> ""
            line.startsWith("#") ->
                // URI="..." attributes (EXT-X-MEDIA subtitles, EXT-X-MAP init segments)
                URI_REGEX.find(line)?.let { m ->
                    line.replace(URI_REGEX, "URI=\"${proxyUrl(resolveUrl(parentUrl, m.groupValues[1]))}\"")
                } ?: line
            else -> proxyUrl(resolveUrl(parentUrl, line))
        }
    }

    private fun resolveUrl(parent: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { parent.toHttpUrl().resolve(ref).toString() }.getOrDefault(ref)
    }

    companion object {
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
    }
}
