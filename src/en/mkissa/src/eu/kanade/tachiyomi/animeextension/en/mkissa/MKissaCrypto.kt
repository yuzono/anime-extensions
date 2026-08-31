package eu.kanade.tachiyomi.animeextension.en.mkissa

import android.util.Base64
import keiyoushi.utils.toHex
import keiyoushi.utils.toJsonString
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MKissaCrypto {

    private const val TAG_LENGTH = 128
    private const val HASH_ALGO = "SHA-256"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val KEY_TYPE = "AES"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"

    private const val KEY_SIZE = 32
    const val SEED_COUNT = 4
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT

    private const val IV_SIZE = 12
    private const val HEADER_SIZE = 1 + IV_SIZE

    private const val WINDOW_MS = 5 * 60 * 1000L

    private const val EPOCH_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val EPOCH_GRACE_MS = 24 * 60 * 60 * 1000L

    private const val SALT_MUL = 105
    private const val SALT_ADD = 199
    private const val FRAG_MUL = 68
    private const val FRAG_ADD = 109
    private const val BOOT_PREFIX = "3CPUb1AFbS:"

    fun sha256Hex(value: String): String = MessageDigest.getInstance(HASH_ALGO)
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    fun maskCandidates(buildId: String, seeds: List<String>): List<ByteArray> {
        deriveMask(buildId, seeds)?.let { return listOf(it) }
        return emptyList()
    }

    fun deriveMask(buildId: String, seeds: List<String>): ByteArray? {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return null

        val stream = ByteArray(KEY_SIZE) { i ->
            (buildId[i % buildId.length].code xor ((i * SALT_MUL + SALT_ADD) and 0xFF)).toByte()
        }

        val mask = ByteArray(KEY_SIZE)
        for (index in seeds.indices) {
            val bytes = runCatching { Base64.decode(seeds[index], Base64.DEFAULT) }.getOrNull()
                ?: return null
            if (bytes.size < SEED_SIZE) return null
            val base = index * SEED_SIZE
            for (offset in 0 until SEED_SIZE) {
                mask[base + offset] = (
                    (bytes[offset].toInt() and 0xFF) xor
                        (stream[base + offset].toInt() and 0xFF) xor
                        ((index * FRAG_MUL + offset * FRAG_ADD) and 0xFF)
                    ).toByte()
            }
        }
        if (mask.all { it == 0.toByte() }) return null
        return mask
    }

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(KEY_SIZE) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, KEY_TYPE)
    }

    fun bootToken(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "$BOOT_PREFIX$buildId")
        val message = listOf(refererHost, epoch.toString(), keyGroup, lane, buildId).joinToString("|")
        return hmac(inner, message).toHex()
    }

    fun bootTokenCandidates(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): List<String> = listOf(bootToken(mask, buildId, epoch, keyGroup, refererHost, lane))

    fun epochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        val inGrace = now - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0
        return if (inGrace) listOf(current - 1, current) else listOf(current)
    }

    fun skewedEpochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        return listOf(current + 1, current - 1).filter { it > 0 } - epochCandidates(now).toSet()
    }

    fun buildAaReq(key: SecretKeySpec, epoch: Long, buildId: String, queryHash: String, lane: String): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS
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
        val iv = blob.sliceArray(1 until HEADER_SIZE)
        val encryptedData = blob.sliceArray(HEADER_SIZE until blob.size)
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, materialKey, GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(encryptedData), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance(HMAC_ALGO).run {
        init(SecretKeySpec(key, HMAC_ALGO))
        doFinal(message.toByteArray(Charsets.UTF_8))
    }
}
