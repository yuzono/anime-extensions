package eu.kanade.tachiyomi.animeextension.all.moviebox

import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP server that keeps MovieBox streams playable for as long as the player is open.
 *
 * MovieBox CDN links are signed (`?sign=&t=`) and stop working after a while, but mpv opens a new
 * HTTP request on every seek, every reconnect and whenever it resumes after a long pause. The
 * player is therefore given `http://127.0.0.1:<port>/v/<key>.mp4`; each request is forwarded
 * (with its `Range`) to the CDN using a link that is always fresh, re-signing on the fly when the
 * link is about to expire or the CDN rejects it. Bytes are streamed through, nothing is stored.
 */
class KeepWarmProxy(baseClient: OkHttpClient) {

    /** Returns a currently valid upstream URL; the argument asks for a newly signed one. */
    private class Entry(val headers: Headers, val provider: suspend (forceRefresh: Boolean) -> String) {
        @Volatile var lastAccess: Long = System.currentTimeMillis()
    }

    private val client = baseClient.newBuilder()
        .cache(null)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val entries = ConcurrentHashMap<String, Entry>()

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MovieBox-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    fun register(key: String, headers: Headers, provider: suspend (forceRefresh: Boolean) -> String): String {
        entries[key] = Entry(headers, provider)
        return "http://127.0.0.1:${ensureServer()}/v/$key.mp4"
    }

    /** Last time the player asked for bytes of [key], or null if it never did. */
    fun lastAccess(key: String): Long? = entries[key]?.lastAccess

    fun forgetOlderThan(cutoff: Long) {
        entries.entries.removeIf { it.value.lastAccess < cutoff }
    }

    @Synchronized
    private fun ensureServer(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        executor.execute { acceptLoop(socket) }
        return socket.localPort
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = 30_000
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val head = readHead(input) ?: return
                val requestLine = head.first().split(' ')
                val method = requestLine.getOrNull(0).orEmpty()
                val path = requestLine.getOrNull(1).orEmpty()
                val requestHeaders = head.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }.toMap()

                val key = path.substringAfter("/v/", "").substringBefore('.').substringBefore('?')
                val entry = entries[key]
                if (entry == null || (method != "GET" && method != "HEAD")) {
                    writeStatus(output, 404, "Not Found")
                    return
                }
                entry.lastAccess = System.currentTimeMillis()

                val response = openUpstream(entry, requestHeaders["range"])
                response.use { upstream ->
                    writeHead(output, upstream)
                    if (method == "GET") {
                        upstream.body.byteStream().use { body -> pipe(body, output, entry) }
                    }
                }
            }
        }
    }

    private fun openUpstream(entry: Entry, range: String?): Response {
        var last: Response? = null
        for (attempt in 0..2) {
            val url = runBlocking { entry.provider(attempt > 0) }
            val request = Request.Builder()
                .url(url)
                .headers(entry.headers)
                .header("Accept-Encoding", "identity")
                .apply { if (range != null) header("Range", range) }
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            val response = client.newCall(request).execute()
            if (response.code !in RESIGN_CODES) return response
            last?.close()
            last = response
        }
        return last!!
    }

    private fun pipe(body: InputStream, output: OutputStream, entry: Entry) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                entry.lastAccess = System.currentTimeMillis()
            }
            output.flush()
        } catch (e: SocketException) {
            // The player closed the connection (seek, stop or a full cache): nothing to do.
        }
    }

    private fun readHead(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (total < MAX_HEAD_BYTES) {
            val byte = input.read()
            if (byte < 0) return null
            total++
            when (byte) {
                '\r'.code -> continue
                '\n'.code -> {
                    if (line.isEmpty()) return lines.ifEmpty { null }
                    lines += line.toString()
                    line.setLength(0)
                }
                else -> line.append(byte.toChar())
            }
        }
        return null
    }

    private fun writeHead(output: OutputStream, upstream: Response) {
        val head = buildString {
            append("HTTP/1.1 ${upstream.code} ${upstream.message.ifBlank { reason(upstream.code) }}\r\n")
            for (name in PASSED_HEADERS) {
                upstream.header(name)?.let { append("$name: $it\r\n") }
            }
            if (upstream.header("Accept-Ranges") == null && upstream.isSuccessful) append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun writeStatus(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        else -> "Status"
    }

    companion object {
        private val RESIGN_CODES = setOf(401, 403, 410, 426)
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEAD_BYTES = 16 * 1024
        private val PASSED_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "Last-Modified",
            "ETag",
        )
    }
}
