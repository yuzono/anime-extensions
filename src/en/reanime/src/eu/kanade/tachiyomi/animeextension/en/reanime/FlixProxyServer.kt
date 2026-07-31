package eu.kanade.tachiyomi.animeextension.en.reanime

import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class FlixProxyServer(
    private val headers: Headers,
    private val client: OkHttpClient,
) : NanoHTTPD(0) {

    val decApi = "https://enc-dec.app/api"

    // Dedicated client: 30s timeout, larger connection pool. DO NOT force HTTP/1.1 (causes 403s)
    private val proxyClient by lazy {
        client.newBuilder()
            .readTimeout(10.seconds)
            .connectTimeout(5.seconds)
            .connectionPool(okhttp3.ConnectionPool(30, 2, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createProxyUrl(originalUrl: String, wPayload: String): String {
        val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload=${URLEncoder.encode(wPayload, "UTF-8")}"
        // Do not append fake extensions. MPV handles the stream better when it relies
        // on the MIME type and the HLS demuxer handles the timestamps correctly.
        return "http://127.0.0.1:$listeningPort/proxy?$params"
    }

    fun wrapInDecApi(originalUrl: String, wPayload: String): String {
        if (originalUrl.contains("enc-dec.app")) return originalUrl
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

    override fun serve(session: IHTTPSession): Response? {
        val params = session.parameters
        val url = params["url"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing url")
        val wPayload = params["w_payload"]?.firstOrNull() ?: ""

        return try {
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
                    if (url.contains("enc-dec.app")) {
                        add("Origin", "https://enc-dec.app")
                        add("Referer", "https://enc-dec.app/")
                    } else {
                        add("Origin", "https://flixcloud.cc")
                        add("Referer", "https://flixcloud.cc/")
                        add("Sec-Fetch-Dest", "empty")
                        add("Sec-Fetch-Mode", "cors")
                        add("Sec-Fetch-Site", "same-site")
                    }
                }.build()

            if (!isManifest) {
                // Segment Request
                val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                val response = proxyClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return newFixedLengthResponse(Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "CDN Error")
                }

                // Read full segment to safely bypass large image headers (>4KB)
                val rawData = response.body.bytes()
                response.close()

                if (rawData.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Empty Segment")
                }

                // Decode flixcloud segment: strip fake image header + XOR-decrypt with 16-byte mask.
                val decoded = decodeFlixcloudSegment(rawData)

                // MPEG-TS — explicit MIME type helps ExoPlayer skip sniffing
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "video/mp2t",
                    ByteArrayInputStream(decoded),
                    decoded.size.toLong(),
                )
            } else {
                // Manifest Request
                val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                val response = proxyClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    response.close()
                    return newFixedLengthResponse(Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "Manifest Error: $errorBody")
                }

                val bodyText = response.body.string()
                response.close()

                val parentHttpUrl = if (url.contains("enc-dec.app")) {
                    url.toHttpUrl().queryParameter("url")?.toHttpUrl() ?: url.toHttpUrl()
                } else {
                    url.toHttpUrl()
                }

                // Simplified parser: just resolve URLs and pass them to the proxy.
                // The proxy will dynamically route them to enc-dec.app.
                val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@joinToString ""

                    if (trimmed.startsWith("#")) {
                        if (trimmed.contains("URI=\"")) {
                            val uri = URI_REGEX.find(trimmed)?.groupValues?.get(1) ?: ""
                            if (uri.isNotEmpty()) {
                                var resolvedUri = parentHttpUrl.resolve(uri).toString()
                                resolvedUri = ensureToken(resolvedUri, url)
                                val newUri = createProxyUrl(resolvedUri, wPayload)
                                trimmed.replace(URI_REGEX, "URI=\"$newUri\"")
                            } else {
                                trimmed
                            }
                        } else {
                            trimmed
                        }
                    } else {
                        var resolvedUrl = parentHttpUrl.resolve(trimmed).toString()
                        resolvedUrl = ensureToken(resolvedUrl, url)
                        createProxyUrl(resolvedUrl, wPayload)
                    }
                }

                // Use application/vnd.apple.mpegurl to match the CDN exactly
                newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedText)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
    }

    companion object {
        private val URI_REGEX = Regex("URI=\"(.*?)\"")

        /**
         * Flixcloud segment XOR mask (16 bytes, repeating).
         *
         * If segments stop decoding (logcat shows "first byte = 0x?? (expected 0x47)"):
         *   1. Open ReAnime video in a browser
         *   2. Search in debugger for: {for(var f=[
         *   3. Copy the 16 numbers from the array literal
         *   4. Convert to hex (Python: bytes([157,42,241,...]).hex())
         *   5. Update FLIXCLOUD_SEGMENT_MASK_HEX below
         *
         * Last verified: 2026-08-01
         */
        private const val FLIXCLOUD_SEGMENT_MASK_HEX = "9D2AF147B38E5C70A619E43BD8620FC5"

        private val flixcloudSegmentMask: ByteArray by lazy {
            FLIXCLOUD_SEGMENT_MASK_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        /**
         * Decode a flixcloud segment.
         *
         * Segments are disguised as WebP/PNG images: a real image magic header
         * (12 bytes for WebP, 8 for PNG) followed by the actual MPEG-TS bytes
         * XOR'd with a 16-byte repeating mask. After XOR, the first byte should
         * be 0x47 (MPEG-TS sync).
         *
         * If the response doesn't match this pattern, returns the original bytes
         * unchanged (rare case — happens if the CDN ever serves raw MPEG-TS).
         */
        private fun decodeFlixcloudSegment(data: ByteArray): ByteArray {
            val headerSize = when {
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
                else -> return data // Not a flixcloud-wrapped segment, serve as-is
            }

            // Quick check: if byte after header is already 0x47, segment wasn't encrypted
            if (data[headerSize] == 0x47.toByte()) {
                return data.copyOfRange(headerSize, data.size)
            }

            // XOR-decrypt in-place
            val decoded = data.copyOfRange(headerSize, data.size)
            for (i in decoded.indices) {
                decoded[i] = (decoded[i].toInt() xor flixcloudSegmentMask[i and 15].toInt()).toByte()
            }

            // Verify: first byte should now be 0x47 (MPEG-TS sync)
            if (decoded.isEmpty() || decoded[0] != 0x47.toByte()) {
                // Restore: return original data minus the image header (best-effort)
                return data.copyOfRange(headerSize, data.size)
            }

            return decoded
        }
    }
}
