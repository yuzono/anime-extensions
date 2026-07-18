package aniyomi.lib.m3u8server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real HTTP server for M3U8 processing using NanoHTTPD
 * Compatible with Android and provides actual HTTP endpoints
 *
 * @param client the OkHttpClient used for all upstream fetches. May carry a
 *   Cloudflare-solve interceptor; if so, supply [fallbackClient] so a failed
 *   WebView solve does not hard-crash the m3u8 path.
 * @param fallbackClient optional secondary client consulted when [client]
 *   throws an IOException that looks like a Cloudflare-solve failure (the
 *   canonical case is `lib/cloudflareinterceptor` raising "Cloudflare
 *   WebView solve produced no cookies"). The fallback is expected to have
 *   no CF interceptor and to use HTTP/1.1; it lets the request flow
 *   through with whatever browser-fingerprint headers the caller attached
 *   so a header-gated CDN can still be served. May be null — when null,
 *   solve failures bubble up as `UpstreamStatusException(503, …)`.
 */
class M3u8HttpServer(
    private val client: OkHttpClient,
    port: Int = 0, // 0 means random port
    private val fallbackClient: OkHttpClient? = null,
) : NanoHTTPD(port) {

    val port: Int
        get() = super.getListeningPort()

    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var isRunning = false

    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(tag, "Received request: $method $uri from ${session.remoteIpAddress}")

        val response = when {
            uri.startsWith("/m3u8") -> handleM3u8Request(session)
            uri.startsWith("/segment") -> handleSegmentRequest(session)
            uri.startsWith("/health") -> handleHealthRequest()
            else -> {
                Log.w(tag, "Unknown endpoint: $uri")
                newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        Log.d(tag, "Response status: ${response.status}")
        return response
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent)

        Log.d(tag, "Processing M3U8 request for URL: $url")
        Log.d(tag, "Headers: $headers")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in M3U8 request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            Log.d(tag, "Starting M3U8 processing for: $url")
            val processedContent = runBlocking { processM3u8Content(url, headers) }
            Log.d(tag, "M3U8 processing completed successfully, content length: ${processedContent.length}")
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", processedContent)
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val keyUrl = session.parameters["key"]?.first()
        val iv = session.parameters["iv"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent)

        Log.d(tag, "Processing segment request for URL: $url (key=${keyUrl != null}, iv=${iv != null})")
        Log.d(tag, "Headers: $headers")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in segment request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        val hasAes = keyUrl != null && iv != null
        return try {
            Log.d(tag, "Starting segment processing for: $url (aes=$hasAes)")
            val segmentData = runBlocking {
                processSegmentUrl(url, headers, keyUrl, iv)
            }
            Log.d(tag, "Segment processing completed successfully, data size: ${segmentData.size} bytes")
            val inputStream = ByteArrayInputStream(segmentData)
            newChunkedResponse(Status.OK, "video/mp2t", inputStream)
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream segment HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /**
     * Wraps the [UpstreamStatusException] code in an HTTP response that
     * surfaces the upstream's status (403, 503, …) to the player instead of
     * collapsing every failure into [Status.INTERNAL_ERROR]. The body carries
     * the upstream URL + code so logcat / mpv diagnostics can pin the cause
     * without re-reading the chain.
     */
    private fun passThroughStatus(e: UpstreamStatusException): Response {
        val nanoStatus = when (e.code) {
            401 -> Status.UNAUTHORIZED
            403 -> Status.FORBIDDEN
            404 -> Status.NOT_FOUND
            429 -> Status.TOO_MANY_REQUESTS
            in 400..499 -> Status.BAD_REQUEST
            500 -> Status.INTERNAL_ERROR
            503 -> Status.SERVICE_UNAVAILABLE
            else -> Status.INTERNAL_ERROR
        }
        val body = "Upstream ${e.code} for ${e.url}\n${e.message}"
        return newFixedLengthResponse(nanoStatus, MIME_PLAINTEXT, body)
    }

    private fun handleHealthRequest(): Response {
        Log.d(tag, "Health check requested")
        val status = getHealthStatus()
        Log.d(tag, "Health status: $status")
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, status)
    }

    /**
     * Build the upstream-fetch header map. Session headers (forwarded by the
     * media player when it opens `http://localhost:…/m3u8?url=…`) seed the map;
     * per-URL attachments from [fallbackReferer]/[fallbackUserAgent] then
     * OVERRIDE / add their values, so the original extension's `video.headers`
     * (encoded into the proxied URL by [createLocalUrl]) always win over
     * whatever ExoPlayer/mpv copied to the localhost request.
     *
     * This prioritisation is the root-cause fix for `vault-99.owocdn.top` and
     * similar Miruro-CDN hosts: the m3u8 server was previously fetching the
     * upstream with no Referer (mpv does not carry the original `Referer`
     * through to localhost), and the CDN returned a Cloudflare-signed 403 —
     * the CloudflareInterceptor then kicked in, attempted a WebView solve, and
     * when it failed (CDN sets no `cf_clearance` for null-Referer requests),
     * the m3u8 path crashed with HTTP 500. Encoding the extension's Referer
     * directly in the proxied URL cuts that whole branch off.
     */
    private fun extractHeadersFromSession(
        session: IHTTPSession,
        fallbackReferer: String? = null,
        fallbackUserAgent: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "connection", "cache-control", "pragma",
                -> {
                    headers[key.lowercase()] = value
                }
            }
        }

        if (!fallbackUserAgent.isNullOrBlank()) {
            headers["user-agent"] = fallbackUserAgent
        }
        if (!fallbackReferer.isNullOrBlank()) {
            headers["referer"] = fallbackReferer
        }

        Log.d(tag, "Extracted headers (referer=${headers["referer"]?.take(80) ?: "none"}, ua=${headers["user-agent"]?.take(40) ?: "none"})")
        return headers
    }

    /**
     * Thrown by `fetchM3u8Content` / `fetchSegmentBytes` when the upstream
     * returns a non-2xx HTTP code, OR when both the primary [client] and the
     * secondary [fallbackClient] (CF-stripped) fail to retrieve the resource.
     *
     * The [code] carries the upstream's HTTP status when known; for
     * transform-layer failures (e.g., a Cloudflare-solve crash with no
     * response yet observed) it defaults to 503 so callers see a
     * service-unavailable NanoHTTPD response instead of opaque INTERNAL_ERROR.
     *
     * Caught by [handleM3u8Request]/[handleSegmentRequest] → [passThroughStatus]
     * so mpv receives a meaningful 403/503 (not a `500 Error: …` wrapper).
     */
    private class UpstreamStatusException(
        val code: Int,
        val url: String,
        message: String,
        cause: Throwable? = null,
    ) : IOException("$code for $url: $message", cause)

    /**
     * Process M3U8 content through the server
     */
    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching M3U8 content from: $url with headers: $headers")
            val m3u8Content = fetchM3u8Content(url, headers)
            Log.d(tag, "Original M3U8 content length: ${m3u8Content.length}")

            val referer = headers["referer"]
            val userAgent = headers["user-agent"]
            val modifiedContent = modifyM3u8Content(m3u8Content, url, port, referer, userAgent)
            Log.d(tag, "Modified M3U8 content length: ${modifiedContent.length}")
            Log.d(tag, "M3U8 processing completed successfully")

            modifiedContent
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream ${e.code} propagating from processM3u8Content for $url")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing m3u8: ${e.message}", e)
        }
    }

    /**
     * Process segment with automatic detection. When [keyUrl] and [iv] are
     * both supplied, the segment is treated as AES-128-CBC encrypted (the
     * `#EXT-X-KEY:METHOD=AES-128` playlist path) and the bytes are decrypted
     * BEFORE junk-byte interleaving is stripped (ChillX-style obfuscation
     * may stack on top of AES-128; decryption first produces the raw TS that
     * AutoDetector then scans for image-magic-junk blocks).
     */
    suspend fun processSegmentUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        keyUrl: String? = null,
        iv: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching segment from: $url with headers: $headers (aes=${keyUrl != null})")
            val rawSegment = fetchSegmentBytes(url, headers)
            val plaintext = if (keyUrl != null && iv != null) {
                val keyBytes = fetchSegmentBytes(keyUrl, headers)
                decryptAes128Cbc(rawSegment, keyBytes, iv).also {
                    Log.d(tag, "AES-128 decrypted segment: ${rawSegment.size} → ${it.size} bytes")
                }
            } else {
                rawSegment
            }
            val stripped = stripInterleavedJunk(plaintext)
            Log.d(tag, "Segment processing completed, final size: ${stripped.size} bytes")
            stripped
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Segment fetch upstream ${e.code} for $url")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing segment: ${e.message}", e)
        }
    }

    private suspend fun fetchSegmentBytes(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch segment with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            Log.d(tag, "Segment HTTP response code: ${response.code}")
            if (!response.isSuccessful) {
                Log.e(tag, "Failed to fetch segment, HTTP code: ${response.code}")
                throw UpstreamStatusException(response.code, url, "Failed to fetch segment")
            }
            response.body.bytes()
        }
    }

    private fun stripInterleavedJunk(fullData: ByteArray): ByteArray {
        val skipRanges = AutoDetector.detectInterleavedSkips(fullData)
        if (skipRanges.isEmpty()) return fullData
        val strippedBytes = skipRanges.sumOf { it.last - it.first + 1 }
        val finalSize = fullData.size - strippedBytes
        Log.d(tag, "Stripping ${skipRanges.size} interleaved junk block(s) ($strippedBytes bytes total), final size: $finalSize bytes")
        val stripped = ByteArrayOutputStream(finalSize.coerceAtLeast(0))
        var cursor = 0
        for (range in skipRanges) {
            if (range.first > cursor) {
                stripped.write(copyRegion(fullData, cursor, range.first - cursor))
            }
            cursor = range.last + 1
        }
        if (cursor < fullData.size) {
            stripped.write(copyRegion(fullData, cursor, fullData.size - cursor))
        }
        return stripped.toByteArray()
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }
        val normalizedIv = iv.normalizeHlsIv()
        if (normalizedIv.length != 32) {
            throw IOException("Invalid AES-128 IV length: ${normalizedIv.length}")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(normalizedIv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x").removePrefix("0X").padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    /**
     * Health check
     */
    fun getHealthStatus(): String = if (isRunning) {
        "M3U8 HTTP Server is running on port $port"
    } else {
        "M3U8 HTTP Server is not running"
    }

    private suspend fun fetchM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch M3U8 content with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d(tag, "M3U8 HTTP response code: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(tag, "Failed to fetch M3U8 content, HTTP code: ${response.code}")
                    throw UpstreamStatusException(response.code, url, "Failed to fetch m3u8")
                }

                val content = response.body.string()
                if (content.isBlank()) {
                    Log.e(tag, "Empty M3U8 response body")
                    throw UpstreamStatusException(502, url, "Empty response body")
                }

                Log.d(tag, "Successfully fetched M3U8 content")
                content
            }
        } catch (primary: UpstreamStatusException) {
            throw primary
        } catch (primary: Exception) {
            // Primary client threw; if it was a CF-solve failure we are not
            // a typed exception here (lib stays agnostic of cloudflareinterceptor).
            // Use the fallback client when supplied — header-gated CDNs may
            // return 200 once the WebView detour is bypassed.
            Log.w(tag, "Primary client failed for $url: ${primary.javaClass.simpleName}: ${primary.message}; ${if (fallbackClient != null) "attempting fallback" else "no fallback configured"}")
            val fb = fallbackClient ?: throw UpstreamStatusException(503, url, "Primary client failed: ${primary.message}", primary)

            try {
                fb.newCall(request).execute().use { response ->
                    Log.d(tag, "M3U8 fallback HTTP response code: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.e(tag, "Fallback also failed for M3U8, HTTP code: ${response.code}")
                        throw UpstreamStatusException(response.code, url, "Fallback failed: ${primary.message}", primary)
                    }
                    val content = response.body.string()
                    if (content.isBlank()) {
                        throw UpstreamStatusException(502, url, "Empty fallback body", primary)
                    }
                    Log.d(tag, "Fallback fetch succeeded, content length: ${content.length}")
                    content
                }
            } catch (fb: UpstreamStatusException) {
                throw fb
            } catch (fb: Exception) {
                Log.e(tag, "Fallback client threw: ${fb.javaClass.simpleName}: ${fb.message}", fb)
                throw UpstreamStatusException(503, url, "Both primary and fallback failed (primary=${primary.message}; fallback=${fb.message})", primary)
            }
        }
    }

    private fun copyRegion(src: ByteArray, off: Int, len: Int): ByteArray {
        if (off == 0 && len == src.size) return src
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        System.arraycopy(src, off, out, 0, len)
        return out
    }

    /**
     * Creates a local M3U8 URL that re-enters this server at `/m3u8`. The
     * caller can attach the original extension's [referer] and [userAgent]
     * so the upstream-fetch path can re-issue them even when the media
     * player (mpv / ExoPlayer) does not carry them through to localhost.
     *
     * This is the root-cause fix for Cloudflare-fronted CDNs that reject
     * null-Referer (or wrong-Referer) requests with a 403 — the m3u8 server
     * would otherwise hit the upstream with whatever headers the player
     * copied to localhost, which on most MPV integrations is "no Referer".
     */
    fun createLocalUrl(
        m3u8Url: String,
        referer: String? = null,
        userAgent: String? = null,
    ): String {
        val sb = StringBuilder("http://localhost:$port/m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        if (!referer.isNullOrBlank()) {
            sb.append("&referer=").append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            sb.append("&useragent=").append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        return sb.toString()
    }

    private fun modifyM3u8Content(
        content: String,
        originalUrl: String,
        serverPort: Int,
        referer: String? = null,
        userAgent: String? = null,
    ): String {
        Log.d(tag, "Modifying M3U8 content for server port: $serverPort (referer=${referer?.take(80) ?: "none"})")
        val lines = content.lines()
        val modifiedLines = mutableListOf<String>()
        var segmentCount = 0
        var mediaSequence = 0L
        var segmentSequence = mediaSequence
        var currentKey: HlsKey? = null

        val baseHttpUrl = originalUrl.toHttpUrlOrNull()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseHlsAttributes(line)
                    when (attributes["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                currentKey = null
                                modifiedLines.add(line)
                            } else {
                                val resolvedKeyUrl = resolveHlsUrl(baseHttpUrl, keyUri)
                                val iv = attributes["IV"]
                                currentKey = HlsKey(
                                    url = resolvedKeyUrl,
                                    iv = iv?.let { it.normalizeHlsIv() } ?: segmentSequence.toHlsIv(),
                                )
                                Log.d(tag, "AES-128 detected, intercepting key at proxy: $resolvedKeyUrl (iv=${currentKey!!.iv})")
                            }
                        }
                        "NONE" -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                        else -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                    }
                }
                line.startsWith("#") || line.isBlank() -> {
                    modifiedLines.add(line)
                }
                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line)
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        val encodedUrl = URLEncoder.encode(resolvedUrl, Charsets.UTF_8.name())
                        // Forward the caller's referer / UA to sub-playlists so
                        // the redirect chain keeps browser-fingerprint headers.
                        modifiedLines.add(buildChildM3u8Url(serverPort, resolvedUrl, referer, userAgent))
                    } else {
                        modifiedLines.add(
                            createLocalSegmentUrl(serverPort, resolvedUrl, currentKey, referer, userAgent),
                        )
                        segmentCount++
                    }
                    segmentSequence++
                }
            }
        }

        Log.d(tag, "Modified M3U8 content: $segmentCount segments redirected, ${if (currentKey != null) "AES-128 keys proxied" else "no AES keys"}")
        return modifiedLines.joinToString("\n")
    }

    private fun buildChildM3u8Url(
        serverPort: Int,
        m3u8Url: String,
        referer: String?,
        userAgent: String?,
    ): String {
        val sb = StringBuilder("http://localhost:$serverPort/m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        if (!referer.isNullOrBlank()) {
            sb.append("&referer=").append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            sb.append("&useragent=").append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        return sb.toString()
    }

    private data class HlsKey(val url: String, val iv: String)

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun parseHlsAttributes(line: String): Map<String, String> = hlsAttributeRegex.findAll(line.substringAfter(":")).associate {
        it.groupValues[1] to it.groupValues[2].trim('"')
    }

    private fun resolveHlsUrl(baseHttpUrl: okhttp3.HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    private fun createLocalSegmentUrl(
        serverPort: Int,
        segmentUrl: String,
        key: HlsKey?,
        referer: String? = null,
        userAgent: String? = null,
    ): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        return buildString {
            append("http://localhost:$serverPort/segment?url=$encodedUrl")
            if (key != null) {
                append("&key=")
                append(URLEncoder.encode(key.url, Charsets.UTF_8.name()))
                append("&iv=")
                append(URLEncoder.encode(key.iv, Charsets.UTF_8.name()))
            }
            if (!referer.isNullOrBlank()) {
                append("&referer=")
                append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
            }
            if (!userAgent.isNullOrBlank()) {
                append("&useragent=")
                append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
            }
        }
    }
}
