package eu.kanade.tachiyomi.animeextension.en.anikage

import android.net.Uri
import android.util.Base64
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress.getByName
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

class LocalProxy(private val client: okhttp3.OkHttpClient) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(
        maxOf(2, Runtime.getRuntime().availableProcessors() * 2),
    )
    var port: Int = 0
        private set

    // Server is started
    val isAvailable: Boolean get() = port > 0 && serverSocket?.isClosed == false

    init {
        try {
            val ss = ServerSocket(0, 50, getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            executor.execute {
                while (!ss.isClosed) {
                    try {
                        val socket = ss.accept()
                        socket.soTimeout = 30_000
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
    }

    fun getProxyUrl(targetUrl: String, headers: Headers?): String {
        if (!isAvailable) return targetUrl
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        var requestParsed = false
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]
            val queryIndex = rawPath.indexOf('?')
            val queryString = if (queryIndex != -1) rawPath.substring(queryIndex) else ""
            val pathWithoutQuery = if (queryIndex != -1) rawPath.substring(0, queryIndex) else rawPath

            val path = if (pathWithoutQuery.startsWith("http://") || pathWithoutQuery.startsWith("https://")) {
                Uri.parse(pathWithoutQuery).path ?: ""
            } else {
                pathWithoutQuery
            }

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path$queryString").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders[headerParts[0].trim()] = headerParts[1].trim()
                    }
                }
            }

            var clientRange: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        clientRange = value
                    }
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            requestParsed = true
            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders, clientRange)
            }
        } catch (e: Exception) {
            if (requestParsed) {
                try {
                    sendError(socket, 500, e.message ?: "Internal Error")
                } catch (_: Exception) {}
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(
        socket: Socket,
        response: Response,
        targetUrl: String,
        encodedHeaders: String,
        clientRange: String?,
    ) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            val body = modifiedContent.toByteArray()
            out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())
            writeForwardedHeaders(out, response, isM3u8 = true)
            out.write("Content-Length: ${body.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
            return
        }

        val stripped = stripPngHeader(response.body.bytes())
        val range = if (response.isSuccessful) parseRange(clientRange, stripped.size) else null
        val body = range?.let { stripped.copyOfRange(it.first, it.last + 1) } ?: stripped
        val isPartial = range != null
        val status = if (isPartial) 206 else response.code
        val reason = when {
            isPartial -> "Partial Content"
            response.message.isNotBlank() -> response.message
            else -> status.toString()
        }
        val contentType = if (isMpegTs(stripped)) {
            "video/mp2t"
        } else {
            response.header("Content-Type") ?: "application/octet-stream"
        }

        out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        writeForwardedHeaders(out, response, isM3u8 = false)
        if (range != null) {
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Content-Range: bytes ${range.first}-${range.last}/${stripped.size}\r\n".toByteArray())
        }
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Content-Type: $contentType\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeForwardedHeaders(
        out: java.io.OutputStream,
        response: Response,
        isM3u8: Boolean,
    ) {
        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                name.equals("Content-Length", ignoreCase = true) ||
                (!isM3u8 && name.equals("Content-Range", ignoreCase = true)) ||
                (!isM3u8 && name.equals("Accept-Ranges", ignoreCase = true))
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }
    }

    private fun parseRange(rangeHeader: String?, size: Int): IntRange? {
        if (rangeHeader.isNullOrBlank() || size <= 0 || !rangeHeader.startsWith("bytes=")) return null

        val range = rangeHeader.removePrefix("bytes=").substringBefore(",")
        val parts = range.split("-", limit = 2)
        if (parts.size != 2) return null

        val startStr = parts[0].trim()
        val endStr = parts[1].trim()

        return when {
            startStr.isEmpty() -> {
                val end = endStr.toIntOrNull()
                if (end != null && end > 0) maxOf(0, size - end)..<size else null
            }
            else -> {
                val start = startStr.toIntOrNull()
                val end = endStr.toIntOrNull()
                if (start == null || start < 0 || start >= size) return null
                if (end != null && end < start) return null
                start..min(end ?: (size - 1), size - 1)
            }
        }
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun isMpegTs(data: ByteArray): Boolean {
        // Need data[offset], data[offset + 188] and data[offset + 376] in
        // bounds, so the last valid offset is size - 377.
        if (data.size < 377) return false
        val maxOffset = min(data.size - 377, 400)
        return (0..maxOffset).any { offset ->
            data[offset] == 0x47.toByte() &&
                data[offset + 188] == 0x47.toByte() &&
                data[offset + 376] == 0x47.toByte()
        }
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}
