package eu.kanade.tachiyomi.animeextension.en.gotaku

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

/**
 * STATELESS local proxy — every request carries its own crypto params in the
 * query string, so multiple episodes / audio types can stream through one
 * server without racing each other.
 *
 *  ?url=...   upstream URL (https only)
 *  &key=...   keySeed from the manifest (base64url) — absent => passthrough
 *  &sb=N      obf.segmentBytes; 0 => passthrough
 *  &m3u8=1    url is a playlist: rewrite, don't decrypt
 *  &plain=1   fetch with site headers, byte-exact passthrough (HLS keys)
 *  &sub=1     subtitle file
 */
class ProxyServer(
    siteHeaders: Headers,
    refererRoot: String,
) : NanoHTTPD("127.0.0.1", 0) {

    // Mimic the site's player. Accept-Encoding stripped: byte-level surgery
    // needs identity encoding and a trustworthy Content-Length.
    private val cdnHeaders: Headers = siteHeaders.newBuilder()
        .set("Accept", "*/*")
        .set("Referer", "$refererRoot/")
        .removeAll("Origin")
        .removeAll("Accept-Encoding")
        .build()
    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30.seconds)
            .connectTimeout(10.seconds)
            .connectionPool(ConnectionPool(30, 2, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ============================ URL builders ============================
    fun createProxyUrl(
        originalUrl: String,
        keySeed: String,
        segmentBytes: Int,
        isManifest: Boolean = false,
    ): String {
        val params = buildString {
            append("url=").append(URLEncoder.encode(originalUrl, "UTF-8"))
            if (keySeed.isNotBlank()) {
                append("&key=").append(URLEncoder.encode(keySeed, "UTF-8"))
                append("&sb=").append(segmentBytes)
            }
            if (isManifest) append("&m3u8=1")
        }
        val path = if (isManifest) "/proxy/playlist.m3u8" else "/proxy/segment.ts"
        return "http://$hostname:$listeningPort$path?$params"
    }

    fun createPlainProxyUrl(originalUrl: String): String = "http://$hostname:$listeningPort/proxy/key.bin?url=${URLEncoder.encode(originalUrl, "UTF-8")}&plain=1"

    fun createSubtitleProxyUrl(originalUrl: String): String = "http://$hostname:$listeningPort/proxy/subtitles.vtt?url=${URLEncoder.encode(originalUrl, "UTF-8")}&sub=1"

    // ============================== Dispatch ==============================
    override fun handle(session: IHTTPSession): Response {
        val params = session.parameters
        val url = params["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")
        if (!url.startsWith("https://")) {
            return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "https only")
        }

        return try {
            when {
                params["sub"]?.firstOrNull() == "1" -> serveSubtitle(url)
                params["m3u8"]?.firstOrNull() == "1" -> serveManifest(
                    url,
                    params["key"]?.firstOrNull().orEmpty(),
                    params["sb"]?.firstOrNull()?.toIntOrNull() ?: 0,
                )
                params["plain"]?.firstOrNull() == "1" -> servePassthrough(url)
                else -> serveSegment(
                    url,
                    params["key"]?.firstOrNull(),
                    params["sb"]?.firstOrNull()?.toIntOrNull() ?: 0,
                    session.headers["range"]
                        ?.let { RANGE_START_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L,
                )
            }
        } catch (_: SocketTimeoutException) {
            // 503: the player retries the segment instead of failing playback
            newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Upstream timeout")
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
    }

    // ============================= Manifests ==============================
    private fun serveManifest(
        url: String,
        keySeed: String,
        segmentBytes: Int,
    ): Response {
        val rawText = run {
            var response: okhttp3.Response? = null
            var attempt = 0
            while (response == null) {
                try {
                    response = proxyClient.newCall(
                        Request.Builder().url(url).headers(cdnHeaders).build(),
                    ).execute()
                } catch (e: SocketTimeoutException) {
                    attempt++
                    if (attempt >= 3) throw e
                    Log.w(TAG, "Manifest timeout, retrying ($attempt/3)")
                }
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return newFixedLengthResponse(
                    Status.lookup(code) ?: Status.INTERNAL_ERROR,
                    "text/plain",
                    "Manifest Error: $code",
                )
            }

            val bodyBytes = response.body.bytes()
            response.close()

            try {
                if (keySeed.isNotBlank()) {
                    val keySeedBytes = Crypto.b64UrlDecode(keySeed) ?: throw Exception("bad keySeed")
                    val httpUrl = url.toHttpUrl()
                    val token = httpUrl.queryParameter("token") ?: httpUrl.pathSegments.getOrNull(3).orEmpty()
                    val playlistKey = Crypto.hkdfSha256(keySeedBytes, token.toByteArray(), "pl|v1".toByteArray(), 32)
                    val iv = bodyBytes.copyOfRange(0, 12)
                    val ct = bodyBytes.copyOfRange(12, bodyBytes.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(playlistKey, "AES"),
                        GCMParameterSpec(128, iv),
                    )
                    String(cipher.doFinal(ct), Charsets.UTF_8)
                } else {
                    String(bodyBytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Playlist GCM decrypt failed: len=${bodyBytes.size} key=${keySeed.isNotBlank()} " +
                        "token=${runCatching { url.toHttpUrl().pathSegments.getOrNull(3) }.getOrNull()} - ${e.message}",
                )
                val asText = String(bodyBytes, Charsets.UTF_8)
                if (asText.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) {
                    // Body was plaintext after all — serve it as-is.
                    asText
                } else {
                    // Not decryptable and not a playlist: fail loudly instead of feeding
                    // the player rewritten ciphertext as a 200 "playlist".
                    return newFixedLengthResponse(
                        Status.INTERNAL_ERROR,
                        "text/plain",
                        "Playlist decrypt failed: ${e.message}",
                    )
                }
            }
        }

        val rewritten = rewriteManifestText(rawText, url, keySeed, segmentBytes)
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritten)
    }

    private fun rewriteManifestText(text: String, parentUrl: String, keySeed: String, segmentBytes: Int): String {
        val isMaster = text.contains("#EXT-X-STREAM-INF")
        val parent = parentUrl.toHttpUrl()
        return text.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> rewriteTagLine(trimmed, parent, keySeed, segmentBytes)
                else -> {
                    val resolved = parent.resolve(trimmed)?.toString() ?: trimmed
                    createProxyUrl(resolved, keySeed, segmentBytes, isManifest = isMaster)
                }
            }
        }
    }

    private fun rewriteTagLine(line: String, parent: HttpUrl, keySeed: String, segmentBytes: Int): String {
        if (!line.contains("URI=\"")) return line
        val uri = URI_REGEX.find(line)?.groups?.get(1)?.value?.takeIf { it.isNotEmpty() } ?: return line
        val resolved = parent.resolve(uri)?.toString() ?: uri

        val replacement = when {
            line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") ->
                createPlainProxyUrl(resolved)

            line.startsWith("#EXT-X-MEDIA") -> {
                if (line.contains("TYPE=SUBTITLES")) {
                    createProxyUrl(resolved, "", 0, isManifest = true)
                } else {
                    createProxyUrl(resolved, keySeed, segmentBytes, isManifest = true)
                }
            }

            line.startsWith("#EXT-X-I-FRAME-STREAM-INF") ->
                createProxyUrl(resolved, keySeed, segmentBytes, isManifest = true)

            else -> createProxyUrl(resolved, keySeed, segmentBytes)
        }
        // Lambda form: $ characters in URLs must not be read as group refs.
        return URI_REGEX.replace(line) { "URI=\"$replacement\"" }
    }

    // ============================== Segments ==============================

    /**
     * Streaming decrypt of a segment's encrypted prefix.
     */
    private fun serveSegment(url: String, keySeed: String?, segmentBytes: Int, rangeStart: Long): Response {
        val key = keySeed?.takeIf { it.isNotBlank() }?.let { Crypto.b64UrlDecode(it) }
        if (key == null || segmentBytes <= 0) return servePassthrough(url)

        val request = Request.Builder().url(url).headers(cdnHeaders).apply {
            if (rangeStart > 0) header("Range", "bytes=$rangeStart-")
        }.build()

        val response = proxyClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "CDN Error: $code",
            )
        }

        val body = response.body
        val source = body.source()
        val actualStart = if (response.code == 206) rangeStart else 0L

        // 3 TS packets = 3 sync bytes (~24 bits) of plaintext evidence
        val head = try {
            val peek = source.peek()
            peek.request(384)
            if (peek.buffer.size > 0) peek.readByteArray(minOf(peek.buffer.size, 384L)) else ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        }

        val isPlaintext = head.isNotEmpty() && Crypto.looksLikeMedia(head)

        // Oracle: only judge candidates on a sample big enough to verify boundaries —
        // an 8-byte peek can't distinguish a right answer from a 1/256 coincidence.
        if (!isPlaintext && actualStart == 0L && head.size >= 189 && counterFullPath == null) {
            for (candidate in booleanArrayOf(true, false)) {
                val decrypted = Crypto.decryptPrefix(head, url, key, candidate, segmentBytes) ?: continue
                if (Crypto.looksLikeMedia(decrypted)) {
                    counterFullPath = candidate
                    Log.i(TAG, "Counter interpretation: ${if (candidate) "full URL" else "path only"}")
                    break
                }
            }
        }

        val stream = if (isPlaintext) {
            source.inputStream()
        } else {
            SegmentSource(source, key, url, segmentBytes, actualStart, counterFullPath ?: true)
                .buffer().inputStream()
        }

        val length = body.contentLength()
        val type = body.contentType()?.toString() ?: "video/mp2t"
        val status = Status.lookup(response.code) ?: Status.OK

        val resp = if (length > 0) {
            newFixedLengthResponse(status, type, stream, length)
        } else {
            newChunkedResponse(status, type, stream)
        }
        if (response.code == 206) {
            response.headers["Content-Range"]?.let { resp.addHeader("Content-Range", it) }
        }
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    /**
     * Runtime oracle for the drop(2) ambiguity in the site's counter code.
     * Learns once, on the first segment whose head doesn't already look like
     * media, then sticks for the proxy's lifetime. Null = undetected.
     */
    @Volatile
    private var counterFullPath: Boolean? = null

    // ====================== Passthrough & Subtitles =======================
    private fun servePassthrough(url: String): Response {
        val response = proxyClient.newCall(
            Request.Builder().url(url).headers(cdnHeaders).build(),
        ).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "CDN Error: $code",
            )
        }

        val body = response.body
        val type = body.contentType()?.toString() ?: "application/octet-stream"
        val stream = body.byteStream()
        return if (body.contentLength() > 0) {
            newFixedLengthResponse(Status.OK, type, stream, body.contentLength())
        } else {
            newChunkedResponse(Status.OK, type, stream)
        }
    }

    private fun serveSubtitle(url: String): Response = proxyClient.newCall(Request.Builder().url(url).headers(cdnHeaders).build()).execute().use { response ->
        if (!response.isSuccessful) {
            val code = response.code
            return@use newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "Subtitle Error: $code",
            )
        }
        val body = response.body
        val contentType = when {
            url.endsWith(".ass", true) -> "text/x-ass"
            url.endsWith(".srt", true) -> "application/x-subrip"
            url.endsWith(".vtt", true) -> "text/vtt"
            else -> body.contentType()?.toString() ?: "text/plain"
        }
        val maxBytes = 512 * 1024
        val bytes = try {
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > maxBytes) error("too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            return@use newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Subtitle too large / read error")
        }
        newFixedLengthResponse(Status.OK, contentType, bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "*")
        }
    }

    companion object {
        private const val TAG = "GotakuProxy"
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val RANGE_START_REGEX = Regex("""bytes=(\d+)-""")
    }
}
