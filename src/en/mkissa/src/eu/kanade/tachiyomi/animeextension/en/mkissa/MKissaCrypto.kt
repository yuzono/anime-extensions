package eu.kanade.tachiyomi.animeextension.en.mkissa

import android.util.Base64
import keiyoushi.utils.toHex
import keiyoushi.utils.toJsonString
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless primitives for the "aaReq" scheme, wire layout `[0x01] + iv(12) + AES-GCM(ct‖tag)`.
 * Request token and response payload are both keyed with `clientMask XOR partB`.
 */
object MKissaCrypto {

    private const val TAG_LENGTH = 128
    private const val HASH_ALGO = "SHA-256"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val KEY_TYPE = "AES"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"

    private const val LEGACY_SECRET = "Xot36i3lK3"

    private const val KEY_SIZE = 32
    const val SEED_COUNT = 4
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT

    private const val IV_SIZE = 12
    private const val HEADER_SIZE = 1 + IV_SIZE

    private const val WINDOW_MS = 5 * 60 * 1000L

    // The server derives partB from a 7-day epoch and keeps the previous alive for a day.
    private const val EPOCH_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val EPOCH_GRACE_MS = 24 * 60 * 60 * 1000L

    /** Identifies a persisted query: the server hashes the query text the same way. */
    fun sha256Hex(value: String): String = MessageDigest.getInstance(HASH_ALGO)
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private data class MaskParams(val saltMul: Int, val saltAdd: Int, val fragMul: Int, val fragAdd: Int)

    private val MASK_PARAMS = listOf(
        MaskParams(211, 222, 200, 176),
        MaskParams(17, 31, 41, 7),
    )

    /** `seeds XOR f(buildId) XOR f(position)`; both inputs change on every site rebuild.
     *  2025-08: site rotated salts from 17/31/41/7 to 211/222/200/176 (see kd={saltMul:211,...} in chunk).
     *  Keep old constants as fallback for a brief grace window.
     */
    fun maskCandidates(buildId: String, seeds: List<String>): List<ByteArray> {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return emptyList()
        val candidates = mutableListOf<ByteArray>()
        for (params in MASK_PARAMS) {
            val stream = ByteArray(KEY_SIZE) { i ->
                (buildId[i % buildId.length].code xor ((i * params.saltMul + params.saltAdd) and 0xFF)).toByte()
            }

            val mask = ByteArray(KEY_SIZE)
            var ok = true
            seeds.forEachIndexed { index, seed ->
                val bytes = runCatching { Base64.decode(seed, Base64.DEFAULT) }.getOrNull()
                if (bytes == null || bytes.size < SEED_SIZE) {
                    ok = false
                    return@forEachIndexed
                }
                val base = index * SEED_SIZE
                for (offset in 0 until SEED_SIZE) {
                    mask[base + offset] = (
                        (bytes[offset].toInt() and 0xFF) xor
                            (stream[base + offset].toInt() and 0xFF) xor
                            ((index * params.fragMul + offset * params.fragAdd) and 0xFF)
                        ).toByte()
                }
            }
            if (ok && mask.any { it != 0.toByte() }) candidates.add(mask)
        }
        return candidates
    }

    fun deriveMask(buildId: String, seeds: List<String>): ByteArray? = maskCandidates(buildId, seeds).firstOrNull()

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(KEY_SIZE) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, KEY_TYPE)
    }

    /** `x-aa-boot`, checked by the bootstrap endpoint before it hands out `partB`.
     *  2025-08: site changed bootPrefix from "aa-boot:" to "kNk1YgwkSI:" and message
     *  from "buildId:group:host:epoch:lane" (":"-joined, buildId first) to
     *  "epoch.group.host.buildId.lane" ("."-joined, epoch first, see kd={join:".",parts:[epoch,group,host,buildId,lane]}).
     *  We keep the legacy format as fallback for the brief window after a site rebuild.
     */
    fun bootTokenNew(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "kNk1YgwkSI:$buildId")
        // kd.parts = ["epoch","group","host","buildId","lane"], kd.join = "."
        // omitEmptyLane = false, so always include lane even if empty
        val message = listOf(epoch.toString(), keyGroup, refererHost, buildId, lane).joinToString(".")
        return hmac(inner, message).toHex()
    }

    fun bootTokenLegacy(
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

    fun bootTokenCandidates(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): List<String> = listOf(
        bootTokenNew(mask, buildId, epoch, keyGroup, refererHost, lane),
        bootTokenLegacy(mask, buildId, epoch, keyGroup, refererHost, lane),
    )

    /** Oldest first: during the grace window the server still mints `partB` for the previous. */
    fun epochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        val inGrace = now - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0
        return if (inGrace) listOf(current - 1, current) else listOf(current)
    }

    /** Fallback for a device clock off by more than the grace window. */
    fun skewedEpochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        return listOf(current + 1, current - 1).filter { it > 0 } - epochCandidates(now).toSet()
    }

    fun buildAaReq(key: SecretKeySpec, epoch: Long, buildId: String, queryHash: String, lane: String): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS

        // Derived, not random: the server recomputes the same one to decrypt.
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
