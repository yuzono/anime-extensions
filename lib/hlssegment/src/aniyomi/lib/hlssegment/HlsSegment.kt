package aniyomi.lib.hlssegment

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.cipherSource
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/**
 * An HLS media segment opened for streaming. Closing [stream] also closes the upstream response.
 *
 * @property length the number of bytes [stream] yields, or `-1` when it is not known up front:
 * decrypted segments, or an upstream response without a `Content-Length`.
 */
class HlsSegment(val stream: InputStream, val length: Long)

/**
 * Executes [request] and returns its body as an [HlsSegment], decrypted on the fly with [cipher]
 * when one is given, so the segment is never held in memory as a whole. Decryption errors raised
 * while reading are rethrown as [IOException].
 *
 * @throws IOException if the request fails or the response is not successful
 */
fun OkHttpClient.openHlsSegment(request: Request, cipher: Cipher?): HlsSegment {
    val upstream = newCall(request).execute()
    try {
        if (!upstream.isSuccessful) {
            throw IOException("Failed to fetch segment: ${upstream.code}")
        }
        val source = upstream.body.source()
        val plaintext = cipher?.let { source.cipherSource(it).rethrowCipherErrors().buffer() } ?: source
        val stream = object : FilterInputStream(plaintext.inputStream()) {
            override fun close() {
                upstream.use { _ ->
                    super.close()
                }
            }
        }
        return HlsSegment(stream, if (cipher == null) upstream.body.contentLength() else -1L)
    } catch (e: Throwable) {
        upstream.close()
        throw e
    }
}

private fun Source.rethrowCipherErrors(): Source = object : ForwardingSource(this) {
    override fun read(sink: Buffer, byteCount: Long): Long = try {
        super.read(sink, byteCount)
    } catch (e: GeneralSecurityException) {
        throw IOException("Failed to decrypt AES-128 segment", e)
    }
}
