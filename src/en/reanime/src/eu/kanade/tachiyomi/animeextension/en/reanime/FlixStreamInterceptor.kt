package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
import keiyoushi.source.stream.StreamInterceptor
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Plays flixcloud streams: master playlists go through enc-dec.app, and segments are images whose
 * payload is XOR-encrypted.
 *
 * Handles the enc-dec.app playlist urls and every url listed in a playlist it served, so segments
 * and variant playlists are decrypted whichever host they are on, while other requests to
 * flixcloud (embed page, api) are left alone.
 */
class FlixStreamInterceptor(private val sourceHeaders: () -> Headers) : StreamInterceptor {

    @Volatile
    var segmentMask: ByteArray = FALLBACK_MASK

    @Volatile
    private var lastWPayload = ""

    /** `host/directory/` of every url listed in a served playlist. */
    private val mediaDirectories: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    // Dedicated client: 30s timeout, larger connection pool. DO NOT force HTTP/1.1 (causes 403s)
    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30.seconds)
            .connectTimeout(10.seconds)
            .connectionPool(ConnectionPool(30, 2, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** The url to play a stream from, wrapped in enc-dec.app when it is a master playlist. */
    fun streamUrl(streamUrl: String, wPayload: String): String {
        lastWPayload = wPayload
        val url = if (isMasterPlaylist(streamUrl)) wrapInDecApi(streamUrl, wPayload) else streamUrl
        addMediaDirectory(url.toHttpUrl())
        return url
    }

    override fun handles(url: HttpUrl): Boolean = isDecApiPlaylist(url) || directoryOf(url) in mediaDirectories

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val proxyHeaders = sourceHeaders().newBuilder()
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

        return try {
            if (url.contains(".m3u8")) fetchPlaylist(request, proxyHeaders) else fetchSegment(request, proxyHeaders)
        } catch (e: SocketTimeoutException) {
            // 503 so the player retries the segment instead of failing completely
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body(e.toString().toResponseBody())
                .build()
        }
    }

    /**
     * Streams a segment with on-the-fly XOR decoding: [FlixcloudSegmentSource] skips the fake
     * WebP/PNG header and decrypts the rest, so the player can start before the segment is done.
     */
    private fun fetchSegment(request: Request, proxyHeaders: Headers): Response {
        val response = proxyClient.newCall(request.newBuilder().headers(proxyHeaders).build()).execute()
        if (!response.isSuccessful) return response

        try {
            val source = response.body.source()

            // Peek at the first 13 bytes (max 12-byte WebP header + 1 payload byte)
            val headerBytes = try {
                source.peek().readByteArray(13)
            } catch (_: java.io.EOFException) {
                ByteArray(0)
            }

            val headerSize = detectHeader(headerBytes)
            if (headerSize == 0) return response

            val originalLength = response.body.contentLength()
            val outputLength = if (originalLength > 0) originalLength - headerSize else -1L
            val body = FlixcloudSegmentSource(source, segmentMask, headerSize)
                .buffer()
                .asResponseBody(SEGMENT_TYPE, outputLength)

            return response.newBuilder()
                .removeHeader("Content-Length")
                .header("Content-Type", SEGMENT_TYPE.toString())
                .body(body)
                .build()
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    private fun fetchPlaylist(request: Request, proxyHeaders: Headers): Response {
        val url = request.url.toString()
        if (url.contains(encDecUrl)) request.url.queryParameter("w_payload")?.let { lastWPayload = it }
        val upstream = request.newBuilder().headers(proxyHeaders).build()

        var response: Response? = null
        var attempt = 0
        while (response == null) {
            try {
                response = proxyClient.newCall(upstream).execute()
            } catch (e: SocketTimeoutException) {
                attempt++
                if (attempt >= 3) throw e
                Log.w("ReAnime", "Manifest timeout, retrying... (Attempt $attempt/3)")
            }
        }
        if (!response.isSuccessful) return response

        val bodyText = response.body.string()
        val parentHttpUrl = if (url.contains(encDecUrl)) {
            request.url.queryParameter("url")?.toHttpUrl() ?: request.url
        } else {
            request.url
        }

        val resolve = { uri: String ->
            val resolved = ensureToken(parentHttpUrl.resolve(uri).toString(), url)
            val final = if (isMasterPlaylist(resolved)) wrapInDecApi(resolved, lastWPayload) else resolved
            addMediaDirectory(final.toHttpUrl())
            final
        }

        val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> ""
                trimmed.startsWith("#") -> {
                    val cleanedLine = if (trimmed.startsWith("#EXT-X-STREAM-INF")) fixBandwidth(trimmed) else trimmed
                    val uri = URI_REGEX.find(cleanedLine)?.groupValues?.get(1).orEmpty()
                    if (uri.isNotEmpty()) cleanedLine.replace(URI_REGEX, "URI=\"${resolve(uri)}\"") else cleanedLine
                }
                else -> resolve(trimmed)
            }
        }

        // application/vnd.apple.mpegurl to match the CDN exactly
        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(modifiedText.toResponseBody(PLAYLIST_TYPE))
            .build()
    }

    /** Fix broken BANDWIDTH values so the player doesn't throttle the buffer. */
    private fun fixBandwidth(line: String): String {
        val peakBw = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val avgBw = AVERAGE_BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
        if (peakBw == null || peakBw >= 100_000L) return line

        // Use average bandwidth if it's valid, else assume the provider forgot to convert Kbps to bps
        val finalBw = if (avgBw != null && avgBw > 100_000L) avgBw else peakBw * 1000L
        return line.replace(BANDWIDTH_REGEX, "BANDWIDTH=$finalBw")
    }

    private fun isDecApiPlaylist(url: HttpUrl) = url.host == ENC_DEC_HOST && url.encodedPath.endsWith("/parse-flixcloud")

    private fun addMediaDirectory(url: HttpUrl) {
        if (!isDecApiPlaylist(url)) mediaDirectories.add(directoryOf(url))
    }

    private fun directoryOf(url: HttpUrl) = url.host + url.encodedPath.substringBeforeLast('/') + "/"

    companion object {
        val flixCloudUrl = "https://flixcloud.cc"
        val encDecUrl = "https://enc-dec.app"
        val decApi = "$encDecUrl/api"
        private const val ENC_DEC_HOST = "enc-dec.app"

        private val FALLBACK_MASK = listOf(157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197)
            .map { it.toByte() }
            .toByteArray()

        private val SEGMENT_TYPE = "video/mp2t".toMediaType()
        private val PLAYLIST_TYPE = "application/vnd.apple.mpegurl".toMediaType()

        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val AVERAGE_BANDWIDTH_REGEX = Regex("""AVERAGE-BANDWIDTH=(\d+)""")

        private fun isMasterPlaylist(url: String) = !url.contains(encDecUrl) && url.contains(".m3u8") && (
            url.contains("master.m3u8") ||
                (url.contains("flixcloud.cc") && !url.contains("audio.m3u8") && !url.contains("video.m3u8"))
            )

        fun wrapInDecApi(originalUrl: String, wPayload: String): String {
            if (originalUrl.contains(encDecUrl)) return originalUrl
            val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8").replace("+", "%20")
            val encodedWPayload = URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")
            return "$decApi/parse-flixcloud?url=$encodedUrl&w_payload=$encodedWPayload"
        }

        fun ensureToken(segmentUrl: String, parentUrl: String): String = try {
            val segHttpUrl = segmentUrl.toHttpUrl()
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
 * Okio [Source] that strips a flixcloud segment's fake image header and XOR-decrypts the payload
 * on the fly, in whatever chunks the network provides.
 *
 * @param upstream    The original OkHttp response body source.
 * @param mask        The 16-byte XOR mask (repeating).
 * @param skipBytes   Number of header bytes to skip (8 for PNG, 12 for WebP).
 */
private class FlixcloudSegmentSource(
    upstream: Source,
    private val mask: ByteArray,
    private val skipBytes: Int,
) : ForwardingSource(upstream) {

    private var bytesSkipped = 0
    private var xorIndex = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        // Phase 1: skip the image header bytes (runs only on the first reads)
        while (bytesSkipped < skipBytes) {
            val toSkip = (skipBytes - bytesSkipped).toLong()
            val skipped = super.read(Buffer(), toSkip)
            if (skipped == -1L) return -1L
            bytesSkipped += skipped.toInt()
        }

        // Phase 2: read payload, XOR it, write to sink
        val temp = Buffer()
        val n = super.read(temp, byteCount)
        if (n == -1L) return -1L

        val bytes = temp.readByteArray()
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor mask[xorIndex and 15].toInt()).toByte()
            xorIndex++
        }
        sink.write(bytes)
        return n
    }
}
