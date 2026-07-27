package eu.kanade.tachiyomi.animeextension.en.allanime

import android.util.Base64
import keiyoushi.utils.toHex
import keiyoushi.utils.toJsonString
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless primitives for AllAnime's "aaReq" scheme (wire layout
 * `[0x01] + iv(12) + AES-GCM(ciphertext‖tag)`). Both the request token and the response payload
 * are keyed with `clientMask XOR partB`; the mask itself is folded out of the JS build's
 * `buildId` and four base64 seeds ([deriveMask]), and `partB` comes from the crypto bootstrap
 * endpoint, which is in turn gated by an HMAC token ([bootToken]).
 */
object AllAnimeCrypto {

    private const val TAG_LENGTH = 128
    private const val HASH_ALGO = "SHA-256"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val KEY_TYPE = "AES"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"

    private const val LEGACY_SECRET = "Xot36i3lK3"

    private const val KEY_SIZE = 32
    const val SEED_COUNT = 4
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT

    // Wire layout: [version(1)] + iv(12) + ciphertext‖tag.
    private const val IV_SIZE = 12
    private const val HEADER_SIZE = 1 + IV_SIZE

    // aaReq time bucket: the token is valid for its rounded-down 5-minute window.
    private const val WINDOW_MS = 5 * 60 * 1000L

    // The server derives partB from a 3-day epoch and keeps the previous one alive for a day.
    private const val EPOCH_WINDOW_MS = 3 * 24 * 60 * 60 * 1000L
    private const val EPOCH_GRACE_MS = 24 * 60 * 60 * 1000L

    /**
     * The 32-byte client mask, obfuscated in the bundle as `seeds XOR f(buildId) XOR f(position)`.
     * Both inputs change on every site rebuild, so they are scraped rather than baked in.
     */
    fun deriveMask(buildId: String, seeds: List<String>): ByteArray? {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return null

        val stream = ByteArray(KEY_SIZE) { i ->
            (buildId[i % buildId.length].code xor ((i * 17 + 31) and 0xFF)).toByte()
        }

        val mask = ByteArray(KEY_SIZE)
        seeds.forEachIndexed { index, seed ->
            val bytes = runCatching { Base64.decode(seed, Base64.DEFAULT) }.getOrNull() ?: return null
            if (bytes.size < SEED_SIZE) return null

            val base = index * SEED_SIZE
            for (offset in 0 until SEED_SIZE) {
                mask[base + offset] = (
                    (bytes[offset].toInt() and 0xFF) xor
                        (stream[base + offset].toInt() and 0xFF) xor
                        ((index * 41 + offset * 7) and 0xFF)
                    ).toByte()
            }
        }
        return mask
    }

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(KEY_SIZE) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, KEY_TYPE)
    }

    /** `x-aa-boot`, the token the bootstrap endpoint checks before handing out `partB`. */
    fun bootToken(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "aa-boot:$buildId")
        val message = buildString {
            append(buildId).append(':').append(keyGroup).append(':')
            append(refererHost).append(':').append(epoch)
            if (lane.isNotEmpty()) append(':').append(lane)
        }
        return hmac(inner, message).toHex()
    }

    /**
     * Ordered oldest first, because during the grace window the *previous* epoch is the one the
     * server is still minting `partB` for; the new one only goes live once the grace expires.
     * Only the server knows which side of that boundary it is on, so both are tried in turn.
     */
    fun epochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        val inGrace = now - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0
        return if (inGrace) listOf(current - 1, current) else listOf(current)
    }

    /**
     * Neighbouring epochs to fall back on once every normal candidate is rejected. The epoch is
     * derived from the device clock, so a clock off by more than the grace window would otherwise
     * fail permanently with an error pointing at the bundle parser instead.
     */
    fun skewedEpochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        return listOf(current + 1, current - 1).filter { it > 0 } - epochCandidates(now).toSet()
    }

    fun buildAaReq(key: SecretKeySpec, epoch: Long, buildId: String, queryHash: String, lane: String): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS

        // Deriving the IV rather than randomising it is required: the server recomputes the same
        // one to decrypt. Reuse is inert because the plaintext is fixed within a `ts` bucket.
        val iv = MessageDigest.getInstance(HASH_ALGO)
            .digest("$epoch:$buildId:$queryHash:$ts:$lane".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, IV_SIZE)

        val payload = AaReqPayload(v = 1, ts = ts, epoch = epoch, buildId = buildId, qh = queryHash, k = lane).toJsonString()

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val blob = ByteArray(HEADER_SIZE + ciphertext.size)
        blob[0] = 1
        System.arraycopy(iv, 0, blob, 1, IV_SIZE)
        System.arraycopy(ciphertext, 0, blob, HEADER_SIZE, ciphertext.size)

        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, materialKey: SecretKeySpec): String? {
        val blob = runCatching { Base64.decode(base64Payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (blob.size < HEADER_SIZE) return null

        val version = blob[0].toInt() and 0xFF
        val iv = blob.sliceArray(1 until HEADER_SIZE)
        val encryptedData = blob.sliceArray(HEADER_SIZE until blob.size)

        // The GCM tag guarantees only the correct key yields output, so trying both is safe.
        for (key in listOf(materialKey, legacyKey(version))) {
            runCatching {
                val cipher = Cipher.getInstance(CIPHER_ALGO)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
                String(cipher.doFinal(encryptedData), Charsets.UTF_8)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance(HMAC_ALGO).run {
        init(SecretKeySpec(key, HMAC_ALGO))
        doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun legacyKey(version: Int): SecretKeySpec {
        val bytes = MessageDigest.getInstance(HASH_ALGO)
            .digest("$LEGACY_SECRET:v$version".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, KEY_TYPE)
    }
}
