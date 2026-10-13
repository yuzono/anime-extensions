package aniyomi.lib.megaextractor

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.cipherSource
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** MEGA serves encrypted bytes, so the player needs an HTTP adapter that decrypts each range. */
internal object MegaStreamServer : NanoHTTPD("127.0.0.1", 0) {

    private class RegisteredStream(
        val client: OkHttpClient,
        val url: HttpUrl,
        val size: Long,
        val key: ByteArray,
        val headers: Headers,
    )

    private val files = Collections.synchronizedMap(
        object : LinkedHashMap<String, RegisteredStream>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RegisteredStream>): Boolean = size > 64
        },
    )

    @Synchronized
    fun register(client: OkHttpClient, url: HttpUrl, size: Long, key: ByteArray, headers: Headers): String {
        if (!isAlive) start()
        val token = UUID.randomUUID().toString()
        files[token] = RegisteredStream(client, url, size, key, headers)
        return "http://127.0.0.1:$listeningPort/$token.mp4"
    }

    override fun stop() {
        super.stop()
        files.clear()
    }

    override fun handle(session: IHTTPSession): Response {
        val file = files[session.uri.removePrefix("/").removeSuffix(".mp4")]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "MEGA stream not found")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use GET or HEAD")
        }

        val rangeHeader = session.headers["range"]
        val range = byteRange(rangeHeader, file.size)
            ?: return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range").apply {
                addHeader("Content-Range", "bytes */${file.size}")
            }
        val length = range.last - range.first + 1
        val status = if (rangeHeader == null) Status.OK else Status.PARTIAL_CONTENT
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(status, "video/mp4", ByteArrayInputStream(byteArrayOf()), length)
        } else {
            try {
                stream(file, range, status, length)
            } catch (_: IOException) {
                return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "MEGA stream unavailable")
            } catch (_: GeneralSecurityException) {
                return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "MEGA decryption failed")
            }
        }
        if (response.status != status) return response
        return response.apply {
            addHeader("Accept-Ranges", "bytes")
            if (rangeHeader != null) addHeader("Content-Range", "bytes ${range.first}-${range.last}/${file.size}")
        }
    }

    private fun stream(file: RegisteredStream, range: LongRange, status: Status, length: Long): Response {
        val alignedStart = range.first / 16 * 16
        val url = file.url.newBuilder().addPathSegment("$alignedStart-${range.last}").build()
        val upstream = file.client.newCall(Request.Builder().url(url).headers(file.headers).build()).execute()
        if (!upstream.isSuccessful) {
            upstream.close()
            return newFixedLengthResponse(Status.lookup(upstream.code) ?: Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "MEGA stream unavailable")
        }
        try {
            val expectedLength = range.last - alignedStart + 1
            val actualLength = upstream.body.contentLength()
            if (actualLength >= 0 && actualLength != expectedLength) throw IOException("Incorrect MEGA range length")

            // MEGA's file key contains the AES key halves and the 64-bit counter nonce.
            // https://github.com/meganz/webclient/blob/master/decrypter.js
            val aesKey = ByteArray(16) { (file.key[it].toInt() xor file.key[it + 16].toInt()).toByte() }
            val iv = ByteBuffer.allocate(16).put(file.key, 16, 8).putLong(alignedStart / 16).array()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            }
            val decrypted = upstream.body.source().cipherSource(cipher).buffer().inputStream()
            val stream = object : FilterInputStream(decrypted) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        upstream.close()
                    }
                }
            }
            repeat((range.first - alignedStart).toInt()) {
                if (stream.read() == -1) throw IOException("Truncated MEGA range")
            }
            return newFixedLengthResponse(status, "video/mp4", stream, length)
        } catch (e: Exception) {
            upstream.close()
            throw e
        }
    }

    private fun byteRange(header: String?, size: Long): LongRange? {
        if (header == null) return 0L until size
        if (!header.startsWith("bytes=")) return null
        val parts = header.removePrefix("bytes=").split('-')
        if (parts.size != 2) return null
        if (parts[0].isEmpty()) {
            val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (size - suffix).coerceAtLeast(0)..<size
        }
        val start = parts[0].toLongOrNull()?.takeIf { it >= 0 && it < size } ?: return null
        val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull() ?: return null
        if (end < start) return null
        return start..end.coerceAtMost(size - 1)
    }
}
