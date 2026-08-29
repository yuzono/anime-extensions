package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens hanime.tv "insecure message" tokens used by the
 * `POST /api/v11/handshake` endpoint on `auth.hanime.tv`.
 *
 * ## Wire format
 *
 * A token is a Base64 string wrapping a JSON envelope:
 * ```
 * {
 *   "v": 1,
 *   "alg": "AES-256-GCM",
 *   "iv":   "<base64 of 12 random bytes>",
 *   "tag":  "<base64 of the 16-byte GCM auth tag>",
 *   "data": "<base64 of the ciphertext>"
 * }
 * ```
 *
 * The same envelope format is used for the request token (`{"token": ...}`
 * body field) and for the encrypted payload returned in the `x-token`
 * response header.
 *
 * ## Crypto parameters
 *
 * - Algorithm: AES-256-GCM (128-bit auth tag, 96-bit random IV)
 * - Key: `SHA-256("htv-insecure-handshake-v1")` — the site derives a raw
 *   AES-256 key from this fixed label (the label itself is only 25 bytes,
 *   so it is hashed to obtain the required 32 key bytes)
 * - Additional authenticated data: `"htv-insecure-v1"` UTF-8 bytes
 *
 * ## Encoding
 *
 * Values are encoded as standard Base64 with padding. The server normalizes
 * incoming values from base64url via
 * `t.replace(/-/g, '+').replace(/_/g, '/').padEnd(ceil(t.length / 4) * 4, '=')`,
 * which leaves standard Base64 unchanged — so standard Base64 is accepted
 * both ways. Decoding applies the inverse normalization first, making
 * [open] tolerant of either alphabet.
 */
object HandshakeCipher {

    private const val KEY_SEED = "htv-insecure-handshake-v1"
    private const val AAD = "htv-insecure-v1"

    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

    /** Envelope parser — lenient towards unknown fields added by the server. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Derived AES-256 key. The seed is shorter than 32 bytes, so the site
     * hashes it; we mirror that derivation exactly.
     */
    private val key: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
    }

    /**
     * Seal [payload] into an encrypted token string ready for the
     * `token` body field of the handshake request.
     */
    fun seal(payload: String): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))

        // JCA GCM doFinal() output is ciphertext || tag — split them apart
        // because the envelope carries the tag as its own field.
        val ctWithTag = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val ciphertext = ctWithTag.copyOfRange(0, ctWithTag.size - GCM_TAG_LENGTH_BYTES)
        val tag = ctWithTag.copyOfRange(ctWithTag.size - GCM_TAG_LENGTH_BYTES, ctWithTag.size)

        val envelope = buildJsonObject {
            put("v", 1)
            put("alg", "AES-256-GCM")
            put("iv", encodeBase64(iv))
            put("tag", encodeBase64(tag))
            put("data", encodeBase64(ciphertext))
        }.toString()

        return encodeBase64(envelope.toByteArray(Charsets.UTF_8))
    }

    /**
     * Open a token received from the server (e.g. the `x-token` response
     * header) and return the decrypted JSON payload string.
     *
     * @throws IllegalArgumentException if [token] is not valid Base64/JSON.
     * @throws javax.crypto.AEADBadTagException if decryption fails authentication.
     */
    fun open(token: String): String {
        val envelope = json.parseToJsonElement(decodeToString(token)).jsonObject

        val iv = decodeBase64(envelope.getValue("iv").jsonPrimitive.content)
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Unexpected IV length: ${iv.size}" }
        val tag = decodeBase64(envelope.getValue("tag").jsonPrimitive.content)
        val data = decodeBase64(envelope.getValue("data").jsonPrimitive.content)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))

        return String(cipher.doFinal(data + tag), Charsets.UTF_8)
    }

    // ── Encoding helpers ──────────────────────────────────────────────
    // java.util.Base64 requires API 26+; this extension sets minSdk = 26.

    /** Standard Base64 (padded, no line breaks) — matches NO_WRAP semantics. */
    internal fun encodeBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * Normalize base64url input to standard Base64 (the exact transform the
     * site applies) and decode. Accepts both alphabets, padded or not.
     */
    internal fun decodeBase64(input: String): ByteArray {
        val normalized = normalizeBase64(input)
        return java.util.Base64.getDecoder().decode(normalized)
    }

    /** Decode a token whose payload is UTF-8 text (e.g. the outer envelope). */
    internal fun decodeToString(token: String): String = String(decodeBase64(token), Charsets.UTF_8)

    /**
     * Mirror of the site's normalization:
     * `t.replace(/-/g, '+').replace(/_/g, '/').padEnd(ceil(t.length / 4) * 4, '=')`
     */
    private fun normalizeBase64(input: String): String = input
        .replace('-', '+')
        .replace('_', '/')
        .padEnd(ceilDiv4(input.length) * 4, '=')

    private fun ceilDiv4(length: Int): Int = (length + 3) / 4
}
