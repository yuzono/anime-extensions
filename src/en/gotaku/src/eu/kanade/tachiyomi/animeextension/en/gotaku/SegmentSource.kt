package eu.kanade.tachiyomi.animeextension.en.gotaku

import okio.Buffer
import okio.ForwardingSource
import okio.Source
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Okio [Source] that decrypts the encrypted PREFIX of a Gotaku segment on the fly.
 *
 * The site's obfuscation (manifest `obf.segmentBytes`) encrypts only the first
 * N bytes of each segment with an AES-CTR keystream keyed by the manifest's
 * `keySeed` and a per-URL counter derived from the segment's URL id. Bytes
 * after the prefix are plaintext and pass through untouched.
 *
 * The keystream is strictly positional, which is what makes [rangeStart]
 * work: the cipher's counter is advanced past the skipped bytes once at
 * init, then reads continue in lockstep with the stream.
 *
 * @param upstream        The OkHttp response body source.
 * @param keySeed         32-byte AES key from the manifest (decoded from base64url).
 * @param segmentUrl      Full upstream URL; hashed into the CTR counter block.
 * @param encryptedLength `obf.segmentBytes`; bytes after this are plaintext.
 * @param rangeStart      Byte offset of this response (HTTP Range / 206).
 * @param fullPath        Counter's url-id interpretation (full URL vs path-only)
 */
class SegmentSource(
    upstream: Source,
    keySeed: ByteArray,
    segmentUrl: String,
    private val encryptedLength: Int,
    rangeStart: Long = 0,
    fullPath: Boolean = true,
) : ForwardingSource(upstream) {

    private var position = rangeStart

    private val cipher: Cipher? =
        if (encryptedLength <= 0 || rangeStart >= encryptedLength) {
            null
        } else {
            Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keySeed, "AES"),
                    IvParameterSpec(Crypto.segmentCounter(segmentUrl, fullPath)),
                )
                if (rangeStart > 0) {
                    // Seeks can land far into the segment, so advance in bounded chunks
                    // rather than allocating `rangeStart` bytes in one shot.
                    val scratch = ByteArray(8192)
                    var remaining = rangeStart
                    while (remaining > 0) {
                        val n = minOf(remaining, scratch.size.toLong()).toInt()
                        update(scratch, 0, n)
                        remaining -= n
                    }
                }
            }
        }

    override fun read(sink: Buffer, byteCount: Long): Long {
        val c = cipher
        if (c == null || position >= encryptedLength) {
            return super.read(sink, byteCount) // plaintext tail, or nothing to decrypt
        }

        val temp = Buffer()
        val n = super.read(temp, byteCount)
        if (n == -1L) return -1L

        val toDecrypt = minOf(n, encryptedLength - position).toInt()
        if (toDecrypt > 0) {
            val head = temp.readByteArray(toDecrypt.toLong())
            val keystream = c.update(ByteArray(toDecrypt)) // CTR over zeros == raw keystream
            for (i in head.indices) {
                head[i] = (head[i].toInt() xor keystream[i].toInt()).toByte()
            }
            sink.write(head)
            position += toDecrypt
        }
        if (n > toDecrypt) sink.write(temp, n - toDecrypt)
        return n
    }
}
