package eu.kanade.tachiyomi.animeextension.en.gotaku

import android.util.Base64
import keiyoushi.utils.decodeHex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.toByteArray

/**
 * Four contexts:
 *  1. API responses (?k=1 endpoints): AES-GCM under HKDF-SHA256(API_SEED, ∅, "api-seal|v1");
 *     wire = [0x01][12B IV][ct‖tag]; plaintext = zlib(JSON).
 *  2. Manifest request: AES-GCM under raw MANIFEST_SEED; base64url(IV‖ct) of
 *     JSON{nonce, stamp}, placed in the URL path.
 *  3. Manifest response: AES-GCM under HKDF-SHA256(MANIFEST_SEED, salt=nonce,
 *     "manifest-resp|v1") - the request's nonce salts the response key, so the
 *     client that built the request is the only one that can read the reply.
 *  4. Segments: AES-CTR keystream (key = manifest keySeed, counter derived from
 *     the segment URL id) XORed over only the first `obf.segmentBytes` bytes.
 */
object Crypto {
    private val API_SEED = listOf(
        203, 10, 122, 188, 182, 30, 245, 41, 26, 106, 139, 92, 56, 203, 90, 52,
        167, 208, 189, 122, 150, 242, 60, 1, 70, 177, 253, 150, 158, 124, 253, 47,
    ).map { it.toByte() }.toByteArray()

    private val MANIFEST_SEED = "458e5403c40a73332706df406193dd6b040a2ea97a6bb59b52b294a61006e6ef".decodeHex()

    /** HKDF-SHA256(ikm=API_SEED, salt=∅, info="api-seal|v1", L=32) — derived once. */
    private val apiKeySpec by lazy {
        SecretKeySpec(hkdfSha256(API_SEED, ByteArray(0), "api-seal|v1".toByteArray(), 32), "AES")
    }

    private val manifestKeySpec by lazy { SecretKeySpec(MANIFEST_SEED, "AES") }

    fun newNonce(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    // =========================== API responses ============================

    /**
     * Decrypts a `?k=1` response body (raw bytes). Returns the inflated JSON
     * text, or null on any failure. Defensively accepts a base64-text variant
     * of the wire format in case a proxy layer ever re-encodes the body.
     */
    fun decryptInfo(body: ByteArray): String? {
        val blob = if (body.isNotEmpty() && body[0] == 1.toByte()) {
            body
        } else {
            b64UrlDecode(body.decodeToString()) ?: return null
        }
        // 1 (version) + 12 (IV) + 16 (GCM tag) = 29 = empty payload
        if (blob.size <= 29 || blob[0] != 1.toByte()) return null

        val compressed = gcmDecrypt(
            apiKeySpec,
            blob.copyOfRange(1, 13),
            blob.copyOfRange(13, blob.size),
        ) ?: return null

        return inflate(compressed)
    }

    // ========================= Manifest handshake =========================

    /**
     * One manifest exchange. The nonce generated here becomes the HKDF salt of
     * the RESPONSE key — the same instance must build the request and decrypt
     * the response. This class exists to make a nonce mismatch unrepresentable.
     */
    class ManifestHandshake(val manifestBase: String, private val stamp: String) {
        val nonce: ByteArray = newNonce()

        /** base64url(IV ‖ ciphertext) — append to [manifestBase] with a '/' and GET it. */
        fun requestPath(): String? = encryptManifest(stamp, nonce)

        fun decryptResponse(body: String): String? = decryptManifest(body, nonce)
    }

    /** Encrypts {nonce, stamp} — key order and compact form match the site's JSON.stringify. */
    fun encryptManifest(stamp: String, nonce: ByteArray): String? {
        val payload = buildJsonObject {
            put("nonce", b64UrlEncode(nonce))
            put("stamp", stamp)
        }.toString().toByteArray()

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = gcmEncrypt(manifestKeySpec, iv, payload) ?: return null
        return b64UrlEncode(iv + ct)
    }

    /** Manifest responses are base64url text: [12B IV][GCM ct‖tag], no "data" envelope. */
    fun decryptManifest(body: String, nonce: ByteArray): String? {
        val blob = b64UrlDecode(body) ?: return null
        if (blob.size < 12 + 16) return null // IV + minimum tag

        val key = hkdfSha256(MANIFEST_SEED, nonce, "manifest-resp|v1".toByteArray(), 32)
        val plain = gcmDecrypt(
            SecretKeySpec(key, "AES"),
            blob.copyOfRange(0, 12),
            blob.copyOfRange(12, blob.size),
        ) ?: return null

        return plain.toString(Charsets.UTF_8)
    }

    // ============================= Segments ===============================

    /**
     * Per-segment CTR counter block: SHA-256 of the URL's id (extension
     * stripped), first 8 bytes + 8 zero bytes (= 64-bit big-endian block
     * counter starting at 0).
     *
     * The site's `split("/").drop(2)` is ambiguous — full URL (drops scheme +
     * host) or path only (drops the first two path segments). Both are
     * implemented; the proxy's oracle detects which one is live on the first
     * segment and [SegmentSource] receives the answer via [fullPath].
     */
    fun segmentCounter(url: String, fullPath: Boolean = true): ByteArray {
        val urlId = (
            if (fullPath) {
                url.split("/").filter { it.isNotEmpty() }.drop(2).joinToString("/")
            } else {
                url.toHttpUrl().encodedPath.split("/").filter { it.isNotEmpty() }.drop(2).joinToString("/")
            }
            ).substringBeforeLast('.')
        val digest = MessageDigest.getInstance("SHA-256").digest(urlId.toByteArray())
        return digest.copyOf(8) + ByteArray(8)
    }

    /** Decrypt bytes assumed to start at keystream position 0 — validation only
     *  (proxy oracle). Only the first [encryptedLength] bytes are XORed; the tail
     *  is plaintext on the wire and must be preserved, or TS syncs outside the
     *  prefix break and a valid counter gets falsely rejected. */
    fun decryptPrefix(data: ByteArray, url: String, key: ByteArray, fullPath: Boolean, encryptedLength: Int): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(segmentCounter(url, fullPath)))
        val n = minOf(data.size, encryptedLength)
        val ks = cipher.doFinal(ByteArray(n))
        ByteArray(data.size) { i ->
            if (i < n) (data[i].toInt() xor ks[i].toInt()).toByte() else data[i]
        }
    } catch (_: Exception) {
        null
    }

    /** MPEG-TS: 0x47 sync at byte 0 AND at every 188-byte packet boundary in
     *  the sample. A lone 0x47 is 1/256 on encrypted data — not evidence.
     *  Callers should pass ≥376 bytes (3 packets) when the body allows.
     *  fMP4: 'ftyp'/'styp'/'moof' box magic at offset 4. */
    fun looksLikeMedia(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        if ((b[0].toInt() and 0xFF) == 0x47) {
            var i = 188
            while (i < b.size) {
                if ((b[i].toInt() and 0xFF) != 0x47) return false
                i += 188
            }
            return true
        }
        if (b.size >= 8) {
            val magic = b.copyOfRange(4, 8)
            return magic.contentEquals(FTYP) || magic.contentEquals(STYP) || magic.contentEquals(MOOF)
        }
        return false
    }

    private val MOOF = "moof".toByteArray()
    private val FTYP = "ftyp".toByteArray()
    private val STYP = "styp".toByteArray()

    // ============================= Primitives =============================

    /** RFC 5869 HKDF-SHA256 — Android's javax.crypto has no HKDF, so manual. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // WebCrypto/RFC: absent salt == HashLen zero bytes. (HMAC zero-padding
        // makes an empty key and a 32-zero-byte key identical anyway.)
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(realSalt, ikm)

        val okm = ByteArrayOutputStream()
        var t = ByteArray(0)
        var block = 1
        while (okm.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(block.toByte()))
            okm.write(t)
            block++
        }
        return okm.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // Java and WebCrypto both append the 16-byte tag to the ciphertext with
    // 128-bit tags by default — byte-compatible in both directions.
    private fun gcmDecrypt(key: SecretKeySpec, iv: ByteArray, ct: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }.doFinal(ct)
    } catch (_: Exception) {
        null // includes AEADBadTagException: wrong key/iv/corrupt body
    }

    private fun gcmEncrypt(key: SecretKeySpec, iv: ByteArray, plain: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }.doFinal(plain)
    } catch (_: Exception) {
        null
    }

    /** Hard cap on decompressed API payloads. The largest legitimate response
     *  (episode lists for 1000+ episode shows) is well under 1 MB; anything
     *  beyond this is a bomb or a bug, and gets rejected like any other
     *  decrypt failure. */
    private const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    /** Node's inflateSync = zlib-wrapped stream, which is InflaterInputStream's default.
     *  Aborts (returns null) once [MAX_INFLATED_BYTES] is exceeded — a small
     *  GCM-valid payload can still be a decompression bomb. */
    private fun inflate(data: ByteArray): String? = try {
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream(minOf(data.size * 4, 1 shl 20))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                total += n
                if (total > MAX_INFLATED_BYTES) return null // bomb guard
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        }
    } catch (_: Exception) {
        null
    }

    // Node emits unpadded base64url; decode leniently (padding and either
    // alphabet) since the server's exact output isn't under our control.
    fun b64UrlEncode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun b64UrlDecode(text: String): ByteArray? = try {
        val cleaned = text.trim().trimEnd('=').replace('-', '+').replace('_', '/')
        val padded = if (cleaned.length % 4 == 0) {
            cleaned
        } else {
            cleaned + "=".repeat(4 - cleaned.length % 4)
        }
        Base64.decode(padded, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}
