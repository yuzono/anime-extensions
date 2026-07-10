package eu.kanade.tachiyomi.animeextension.en.allanime

import android.util.Base64
import keiyoushi.utils.toJsonString
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless AES-GCM primitives for AllAnime's stream-auth ("aaReq") scheme.
 *
 * The key is `clientMask (partA) XOR partB`; the same key encrypts the request token and
 * decrypts the `tobeparsed` response payload. Both use the wire layout
 * `[0x01] + iv(12) + AES-GCM(ciphertext‖tag)`.
 */
object AllAnimeCrypto {

    private const val TAG_LENGTH = 128
    private const val HASH_ALGO = "SHA-256"
    private const val KEY_TYPE = "AES"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"

    // aaReq time bucket: the token is valid for its rounded-down 5-minute window.
    const val WINDOW_MS = 5 * 60 * 1000L

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(32) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, KEY_TYPE)
    }

    fun buildAaReq(key: SecretKeySpec, epoch: Long, buildId: String, queryHash: String): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS

        val iv = MessageDigest.getInstance(HASH_ALGO)
            .digest("$epoch:$buildId:$queryHash:$ts".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 12)

        val payload = AaReqPayload(v = 1, ts = ts, epoch = epoch, buildId = buildId, qh = queryHash).toJsonString()

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val blob = ByteArray(13 + ciphertext.size)
        blob[0] = 1
        System.arraycopy(iv, 0, blob, 1, 12)
        System.arraycopy(ciphertext, 0, blob, 13, ciphertext.size)

        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, key: SecretKeySpec): String? = runCatching {
        val blob = Base64.decode(base64Payload, Base64.DEFAULT)
        if (blob.size < 13 || blob[0].toInt() != 1) return null

        val iv = blob.sliceArray(1 until 13)
        val encryptedData = blob.sliceArray(13 until blob.size)

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        String(cipher.doFinal(encryptedData), Charsets.UTF_8)
    }.getOrNull()

    fun hexToBytesOrNull(hex: String): ByteArray? = runCatching {
        require(hex.isNotEmpty() && hex.length % 2 == 0)
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
