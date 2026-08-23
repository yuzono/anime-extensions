package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FlixProxyServer(
    private val headers: Headers,
    private var segmentMask: ByteArray,
) : NanoHTTPD("127.0.0.1", 0) {

    fun updateSegmentMask(newMask: ByteArray) {
        if (!newMask.contentEquals(segmentMask)) {
            segmentMask = newMask
        }
    }

    // Dedicated client: 30s timeout, larger connection pool. DO NOT force HTTP/1.1 (causes 403s)
    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(30, 2, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createProxyUrl(originalUrl: String, wPayload: String): String {
        val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload=${URLEncoder.encode(wPayload, "UTF-8")}"
        // Do not append fake extensions. MPV handles the stream better when it relies
        // on the MIME type and the HLS demuxer handles the timestamps correctly.
        return "http://127.0.0.1:$listeningPort/proxy?$params"
    }

    fun createSubtitleProxyUrl(originalUrl: String): String {
        val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload="
        return "http://127.0.0.1:$listeningPort/proxy?$params"
    }

    fun wrapInDecApi(originalUrl: String, wPayload: String): String {
        if (originalUrl.contains(encDecUrl)) return originalUrl
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8").replace("+", "%20")
        val encodedWPayload = URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")
        return "$decApi/parse-flixcloud?url=$encodedUrl&w_payload=$encodedWPayload"
    }

    fun ensureToken(segmentUrl: String, parentUrl: String): String = try {
        val segHttpUrl = segmentUrl.toHttpUrl()

        // Extract token from parent URL or its nested 'url' parameter if wrapped in enc-dec.app
        // If segment URL has no token, walk up to 3 levels of nested `url=` params
        // looking for one. The enc-dec.app wrapper carries the token in its top-level
        // query string, so this finds it.
        var token: String? = segHttpUrl.queryParameter("token")
        if (token == null) {
            var currentUrl = parentUrl
            repeat(3) {
                val httpUrl = currentUrl.toHttpUrl()
                if (token == null) token = httpUrl.queryParameter("token")
                if (token == null) {
                    val nestedUrl = httpUrl.queryParameter("url")
                    if (nestedUrl != null) currentUrl = nestedUrl
                }
            }
        }

        segHttpUrl.newBuilder().apply {
            if (token != null && segHttpUrl.queryParameter("token") == null) {
                addQueryParameter("token", token)
            }
        }.build().toString()
    } catch (_: Exception) {
        segmentUrl
    }

    override fun handle(session: IHTTPSession): Response {
        val params = session.parameters
        val url = params["url"]?.firstOrNull() ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")
        val wPayload = params["w_payload"]?.firstOrNull() ?: ""

        return try {
            val isSubtitle = url.contains("/subtitles/")
            if (isSubtitle) {
                if (!url.startsWith("https://")) {
                    return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Subtitle URL must be https")
                }
                val proxyHeaders = headers.newBuilder()
                    .set("Accept", "*/*")
                    .removeAll("Origin").removeAll("Referer")
                    .apply {
                        add("Origin", flixCloudUrl)
                        add("Referer", "$flixCloudUrl/")
                    }.build()
                return serveSubtitle(url, proxyHeaders)
            }
            val isManifest = url.contains(".m3u8")
            val isMasterManifest = isManifest && (
                url.contains("master.m3u8") ||
                    (url.contains("flixcloud.cc") && !url.contains("audio.m3u8") && !url.contains("video.m3u8"))
                )
            val finalUrl = if (isMasterManifest) wrapInDecApi(url, wPayload) else url

            val proxyHeaders = headers.newBuilder()
                .set("Accept", "*/*")
                .removeAll("Origin").removeAll("Referer")
                .removeAll("Sec-Fetch-Dest").removeAll("Sec-Fetch-Mode")
                .removeAll("Sec-Fetch-Site").removeAll("Accept-Encoding")
                .apply {
                    if (url.contains(encDecUrl)) {
                        add("Origin", encDecUrl)
                        add("Referer", "$encDecUrl/")
                    } else {
                        add("Origin", flixCloudUrl)
                        add("Referer", "$flixCloudUrl/")
                        add("Sec-Fetch-Dest", "empty")
                        add("Sec-Fetch-Mode", "cors")
                        add("Sec-Fetch-Site", "same-site")
                    }
                }.build()

            if (!isManifest) {
                serveSegment(finalUrl, proxyHeaders)
            } else {
                serveManifest(url, finalUrl, wPayload, proxyHeaders)
            }
        } catch (e: Exception) {
            // Return 503 for timeouts so the player retries the segment instead of failing completely
            val status = if (e is java.net.SocketTimeoutException) {
                Status.SERVICE_UNAVAILABLE
            } else {
                Status.INTERNAL_ERROR
            }

            newFixedLengthResponse(status, "text/plain", e.toString())
        }
    }

    /**
     * Stream a flixcloud segment with on-the-fly XOR decoding.
     *
     * Wrap the OkHttp response body's [Source] in a
     * [FlixcloudSegmentSource] that:
     *  1. Skips the fake WebP/PNG image header (8 or 12 bytes)
     *  2. XOR-decrypts each subsequent byte with the 16-byte mask
     *
     * The transformed bytes flow directly to the player via NanoHTTPD's
     * InputStream-based response.
     */
    private fun serveSegment(
        finalUrl: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
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

        // Peek at the first 13 bytes (max 12-byte WebP header + 1 payload byte)
        // to determine the header type and whether XOR is needed. peek() does
        // not consume the bytes, so the FlixcloudSegmentSource still sees them.
        val headerBytes = try {
            source.peek().readByteArray(13)
        } catch (_: java.io.EOFException) {
            ByteArray(0)
        }

        val headerSize = detectHeader(headerBytes)
        val shouldXor = headerSize > 0 &&
            headerBytes.size > headerSize &&
            (headerBytes[headerSize].toInt() and 0xFF) != 0x47

        // --- XOR KEY VALIDATION ---
        if (shouldXor) {
            val firstPayloadByte = headerBytes[headerSize].toInt() and 0xFF
            val decryptedByte = firstPayloadByte xor (segmentMask[0].toInt() and 0xFF)

            if (decryptedByte != 0x47) {
                throw IllegalStateException("XOR key could not be found.")
            }
        }

        // Output length = original length minus the stripped image header.
        val originalLength = body.contentLength()
        val outputLength = if (originalLength > 0 && headerSize > 0) {
            originalLength - headerSize
        } else {
            originalLength
        }

        // Wrap the upstream source with our XOR-decoding ForwardingSource.
        val xorSource = FlixcloudSegmentSource(source, segmentMask, headerSize, shouldXor)
        val inputStream = xorSource.buffer().inputStream()

        return if (outputLength > 0) {
            newFixedLengthResponse(Status.OK, "video/mp2t", inputStream, outputLength)
        } else {
            newChunkedResponse(Status.OK, "video/mp2t", inputStream)
        }
    }

    private fun serveSubtitle(
        subtitleUrl: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(subtitleUrl).headers(proxyHeaders).build()
        return proxyClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = response.code
                val errorSnippet = try {
                    response.peekBody(1024).string().take(300)
                } catch (_: Exception) {
                    ""
                }
                return@use newFixedLengthResponse(
                    Status.lookup(code) ?: Status.INTERNAL_ERROR,
                    "text/plain",
                    "Subtitle Error: $code $errorSnippet",
                )
            }
            val body = response.body
            val contentType = when {
                subtitleUrl.endsWith(".ass") -> "text/x-ass"
                subtitleUrl.endsWith(".srt") -> "application/x-subrip"
                subtitleUrl.endsWith(".vtt") -> "text/vtt"
                else -> body.contentType()?.toString() ?: "text/plain"
            }
            val maxSubtitleBytes = 512 * 1024
            val contentLength = body.contentLength()
            if (contentLength != -1L && contentLength > maxSubtitleBytes) {
                return@use newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Subtitle too large")
            }
            val bytes: ByteArray = try {
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        total += n
                        if (total > maxSubtitleBytes) throw IllegalStateException("too large")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            } catch (e: IllegalStateException) {
                return@use newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Subtitle too large")
            } catch (_: Exception) {
                return@use newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Subtitle read error")
            }
            val resp = newFixedLengthResponse(Status.OK, contentType, bytes.inputStream(), bytes.size.toLong())
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Headers", "*")
            resp
        }
    }

    private fun serveManifest(
        url: String,
        finalUrl: String,
        wPayload: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()

        var response: okhttp3.Response? = null
        var attempt = 0
        while (response == null) {
            try {
                response = proxyClient.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                attempt++
                if (attempt >= 3) throw e
                Log.w("ReAnime", "Manifest timeout, retrying... (Attempt $attempt/3)")
            }
        }

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            response.close()
            return newFixedLengthResponse(
                Status.lookup(response.code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "Manifest Error: $errorBody",
            )
        }

        val bodyText = response.body.string()
        response.close()

        val parentHttpUrl = if (url.contains(encDecUrl)) {
            url.toHttpUrl().queryParameter("url")?.toHttpUrl() ?: url.toHttpUrl()
        } else {
            url.toHttpUrl()
        }

        // Simplified parser: just resolve URLs and pass them to the proxy.
        val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@joinToString ""

            if (trimmed.startsWith("#")) {
                // Fix broken BANDWIDTH values so the player doesn't throttle the buffer
                val cleanedLine = if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    val peakBw = BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
                    val avgBw = AVERAGE_BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()

                    if (peakBw != null && peakBw < 100_000L) {
                        // Peak bandwidth is suspiciously low (< 100 Kbps)
                        val finalBw = if (avgBw != null && avgBw > 100_000L) {
                            // Use average bandwidth if it's valid
                            avgBw
                        } else {
                            // Assume the provider forgot to convert Kbps to bps
                            peakBw * 1000L
                        }
                        trimmed.replace(BANDWIDTH_REGEX, "BANDWIDTH=$finalBw")
                    } else {
                        trimmed
                    }
                } else {
                    trimmed
                }

                if (cleanedLine.contains("URI=\"")) {
                    val uri = URI_REGEX.find(cleanedLine)?.groupValues?.get(1) ?: ""
                    if (uri.isNotEmpty()) {
                        var resolvedUri = parentHttpUrl.resolve(uri).toString()
                        resolvedUri = ensureToken(resolvedUri, url)
                        val newUri = createProxyUrl(resolvedUri, wPayload)
                        cleanedLine.replace(URI_REGEX, "URI=\"$newUri\"")
                    } else {
                        cleanedLine
                    }
                } else {
                    cleanedLine
                }
            } else {
                var resolvedUrl = parentHttpUrl.resolve(trimmed).toString()
                resolvedUrl = ensureToken(resolvedUrl, url)
                createProxyUrl(resolvedUrl, wPayload)
            }
        }

        // Use application/vnd.apple.mpegurl to match the CDN exactly
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", modifiedText)
    }

    companion object {
        val flixCloudUrl = "https://flixcloud.cc"
        val encDecUrl = "https://enc-dec.app"
        val decApi = "$encDecUrl/api"
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val AVERAGE_BANDWIDTH_REGEX = Regex("""AVERAGE-BANDWIDTH=(\d+)""")

        /**
         * Detect the fake image header type from the first bytes of a segment.
         *
         * Returns 12 for WebP (RIFF....WEBP), 8 for PNG signature, or 0 if
         * the data doesn't match either pattern (raw MPEG-TS passthrough).
         */
        private fun detectHeader(data: ByteArray): Int = when {
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> 12 // RIFF....WEBP
            data.size >= 8 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() &&
                data[4] == 0x0D.toByte() && data[5] == 0x0A.toByte() &&
                data[6] == 0x1A.toByte() && data[7] == 0x0A.toByte() -> 8 // PNG sig
            else -> 0
        }
    }
}

/**
 * Okio [Source] that strips a flixcloud segment's fake image header and
 * XOR-decrypts the payload on the fly.
 *
 * Data flows through in small chunks (whatever the network provides per read,
 * typically 4-16KB).
 * This lets the player start decoding as soon as the first bytes arrive.
 *
 * @param upstream    The original OkHttp response body source.
 * @param mask        The 16-byte XOR mask (repeating).
 * @param skipBytes   Number of header bytes to skip (8 for PNG, 12 for WebP, 0 for passthrough).
 * @param shouldXor   Whether to XOR-decrypt the payload (false if segment is already plaintext).
 */
private class FlixcloudSegmentSource(
    upstream: Source,
    private val mask: ByteArray,
    private val skipBytes: Int,
    private val shouldXor: Boolean,
) : ForwardingSource(upstream) {

    private var bytesSkipped = 0
    private var xorIndex = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        // Phase 1: skip the image header bytes (runs only on the first reads)
        while (bytesSkipped < skipBytes) {
            val toSkip = (skipBytes - bytesSkipped).toLong()
            val temp = Buffer()
            val skipped = super.read(temp, toSkip)
            if (skipped == -1L) return -1L
            bytesSkipped += skipped.toInt()
            // temp (header bytes) is discarded when it goes out of scope
        }

        // Phase 2: read payload, XOR if needed, write to sink.
        // OkHttp's source already returns what's available from the network
        // (typically 4-16KB chunks), so no artificial cap is needed.
        val temp = Buffer()
        val n = super.read(temp, byteCount)
        if (n == -1L) return -1L

        if (shouldXor) {
            val bytes = temp.readByteArray()
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() xor mask[xorIndex and 15].toInt()).toByte()
                xorIndex++
            }
            sink.write(bytes)
        } else {
            sink.write(temp, n)
        }

        return n
    }
}
