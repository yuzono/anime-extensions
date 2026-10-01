package eu.kanade.tachiyomi.animeextension.en.senshi

import android.util.Log
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/* vidcloud "Octopus" resolve protocol — reimplements window.__oct (vendor.js).
 * Records are identified WITHOUT magic labels: bootstrap by structural
 * fingerprint (ver/keyLen/point-marker at fixed offsets), response by GCM
 * self-identification (the chunk that decrypts is the record) — chunk tags
 * and record magics rotate per vendor build and are never referenced.
 * Remaining constants: HKDF info string, header magic (pending probe).
 * On failure: "no bootstrap-shaped record" → layout change, dump the envelope;
 * 403 on the authorize POST → info/header magic rotated → recapture the
 * HKDF info line with WebCrypto hooks on a fresh watch tab.
 *
 *   1. GET  {gw}/i/73918463  PNG envelope, record: magic | ver=1 | epoch u64
 *      | expires u64 | server P-256 point | challenge[16]  (= the HKDF salt,
 *      server-issued, echoed in the payload)
 *   2. ECDH(P-256) → HKDF-SHA256(salt=challenge, info) → AES-256-GCM key
 *   3. POST {gw}/q7m4x9 (image/png): header ‖ iv12 ‖ GCM(payload), aad=header
 *      pub=client point, epoch, cap empty · payload: 1|videoId u64|now u64|
 *      nonce16|originLen u16|origin|challenge16
 *   4. response: magic | 1 | iv12 | GCM ct (aad=5-byte head); plain =
 *      u32be seed ‖ xorshift32-obfuscated JSON {p, c, x}
 *
 * videoId = embeds' remote_source_id. CDN (bcdn*.se) serves plain HLS; tokens
 * are bound to the handshaking client — CDN fetches use the same OkHttpClient.
 */
private class OctopusTicket(
    val entries: List<VidcloudEntryDto>,
    val capability: String,
    val expiresAtSec: Long,
)

private class OctopusException(message: String, cause: Throwable? = null) : IOException(message, cause)

class EM3u8KeyStore(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    fun resolve(videoId: Long): List<VidcloudEntryDto> = freshOpen(videoId).entries

    private fun freshOpen(videoId: Long): OctopusTicket {
        val b = bootstrap()
        val kp = newKeyPair()
        val clientPoint = rawPoint(kp)
        val key = SecretKeySpec(
            hkdfSha256(leftPad32(ecdh(kp.private, b.serverPoint)), b.challenge, INFO_BYTES, 32),
            "AES",
        )
        return authorize(key, videoId, epoch = b.epoch, pub = clientPoint, cap = EMPTY, challenge = b.challenge)
    }

    // ============================= Bootstrap ==============================
    private class Bootstrap(val epoch: Long, val serverPoint: ByteArray, val challenge: ByteArray)

    private fun bootstrap(): Bootstrap {
        val png = http(Request.Builder().url(BOOTSTRAP_URL).headers(headers).get().build())
        val chunks = pngChunks(png)
        val rec = chunks.firstOrNull { c ->
            c.data.size >= 23 + 65 + 16 &&
                c.data[4].toInt() == 1 && rdU16(c.data, 21) == 65 && c.data[23].toInt() == 4
        }?.data ?: throw OctopusException(
            "no bootstrap-shaped record (${png.size}B, chunks=${chunks.joinToString(",") { it.tag }})",
        )
        val keyLen = rdU16(rec, 21) // for slicing only — validated by the filter
        val epoch = rdU64(rec, 5)
        val expires = rdU64(rec, 13)
        check(expires > nowSec()) { "bootstrap expired (expires=$expires now=${nowSec()})" }
        Log.i(TAG, "bootstrap: epoch=$epoch expires=$expires challenge=${rec.copyOfRange(23 + keyLen, 23 + keyLen + 16).toHexString()}")
        return Bootstrap(
            epoch = epoch,
            serverPoint = rec.copyOfRange(23, 23 + keyLen),
            challenge = rec.copyOfRange(23 + keyLen, 23 + keyLen + 16),
        )
    }

    // =========================== Authorization ============================
    private fun authorize(
        key: SecretKeySpec,
        videoId: Long,
        epoch: Long,
        pub: ByteArray,
        cap: ByteArray,
        challenge: ByteArray,
    ): OctopusTicket {
        // header / GCM AAD
        val header = ByteArray(18 + pub.size + 2 + cap.size)
        HEADER_MAGIC.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = 1 // version
        header[5] = 0 // fresh
        // header[6..7] = 0
        u64be(epoch).copyInto(header, 8)
        u16be(pub.size).copyInto(header, 16)
        pub.copyInto(header, 18)
        u16be(cap.size).copyInto(header, 18 + pub.size)
        cap.copyInto(header, 20 + pub.size)

        // payload (68 B for a 17-char origin)
        val origin = ORIGIN.toByteArray(Charsets.US_ASCII)
        check(challenge.size == 16)
        val payload = ByteArray(1 + 8 + 8 + 16 + 2 + origin.size + 16)
        var o = 0
        payload[o++] = 1
        u64be(videoId).copyInto(payload, o)
        o += 8
        u64be(nowSec()).copyInto(payload, o)
        o += 8 // client clock — matches the site
        random(16).copyInto(payload, o)
        o += 16
        u16be(origin.size).copyInto(payload, o)
        o += 2
        origin.copyInto(payload, o)
        o += origin.size
        challenge.copyInto(payload, o)

        val iv = random(12)
        val body = header + iv + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(header)
            doFinal(payload)
        }

        val png = http(
            Request.Builder().url(AUTHORIZE_URL).headers(headers)
                .post(body.toRequestBody("image/png".toMediaType()))
                .build(),
        )
        val plain = pngChunks(png).asSequence()
            .filter { it.data.size >= 33 && it.data[4].toInt() == 1 }.firstNotNullOfOrNull { c ->
                runCatching {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, c.data, 5, 12))
                        updateAAD(c.data, 0, 5)
                        doFinal(c.data, 17, c.data.size - 17)
                    }
                }.getOrNull()
            } ?: throw OctopusException("no chunk decrypts as response record — key mismatch or layout change")

        // plain = u32be seed ‖ xorshift-obfuscated JSON
        check(plain.size > 4) { "response plaintext too short (${plain.size}B)" }
        var s = ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
            ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
        if (s == 0) s = 0x9e3779b9.toInt()
        val blob = plain.copyOfRange(4, plain.size)
        for (i in blob.indices) {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            blob[i] = (blob[i].toInt() xor (s ushr 24)).toByte()
        }

        val text = String(blob, Charsets.UTF_8)
        val ticket = try {
            val obj = text.parseAs<JsonObject>()
            val pEl = obj["p"] ?: throw OctopusException("ticket missing 'p'")
            val arr = when (pEl) {
                is JsonArray -> pEl
                is JsonNull -> JsonArray(emptyList())
                else -> JsonArray(listOf(pEl))
            }
            OctopusTicket(
                entries = arr.parseAs<List<VidcloudEntryDto>>(),
                capability = obj["c"]?.jsonPrimitive?.content ?: "",
                expiresAtSec = obj["x"]?.jsonPrimitive?.longOrNull ?: (nowSec() + DEFAULT_TTL_SEC),
            )
        } catch (e: Exception) {
            throw OctopusException(
                "Octopus: response decode failed — head: " +
                    text.take(120).map { if (it.code < 0x20) '?' else it }.joinToString(""),
                e,
            )
        }
        Log.i(TAG, "resolve($videoId): ${ticket.entries.size} entries, expires=${ticket.expiresAtSec}, cap.len=${ticket.capability.length}")
        return ticket
    }

    // =============================== Crypto ===============================
    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun rawPoint(kp: KeyPair): ByteArray {
        val spki = kp.public.encoded
        return spki.copyOfRange(spki.size - 65, spki.size)
    }

    private fun ecdh(priv: PrivateKey, serverPoint: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(priv)
            doPhase(pointToPublicKey(serverPoint), true)
        }
        return ka.generateSecret()
    }

    private fun pointToPublicKey(point: ByteArray): PublicKey {
        require(point.size == 65 && point[0].toInt() == 4) { "bad EC point" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(SPKI_P256 + point))
    }

    /** JCE may strip the leading zero of the P-256 secret; WebCrypto never does. */
    private fun leftPad32(z: ByteArray): ByteArray {
        check(z.size <= 32) { "unexpected ECDH secret size ${z.size}" }
        return ByteArray(32 - z.size) + z
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLen) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun http(request: Request): ByteArray = client.newCall(request).execute().use { res ->
        if (!res.isSuccessful) throw OctopusException("Octopus: HTTP ${res.code} from ${res.request.url}")
        res.body.bytes()
    }

    private class PngChunk(val tag: String, val data: ByteArray)

    private fun pngChunks(png: ByteArray): List<PngChunk> {
        check(png.size > 16 && png[0] == 0x89.toByte() && png[1] == 0x50.toByte()) { "not a PNG envelope" }
        val out = mutableListOf<PngChunk>()
        var i = 8
        while (i + 8 <= png.size) {
            val len = rdU32(png, i)
            if (len < 0 || i + 12 + len > png.size) throw OctopusException("corrupt PNG chunk table")
            val tag = String(png, i + 4, 4, Charsets.US_ASCII)
            out += PngChunk(tag, png.copyOfRange(i + 8, i + 8 + len))
            i += 12 + len
            if (tag == "IEND") break
        }
        return out
    }

    private fun random(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun u16be(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u64be(v: Long): ByteArray = ByteArray(8) { (v ushr ((7 - it) * 8)).toByte() }
    private fun rdU16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun rdU32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun rdU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
    private fun nowSec() = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "Octopus"
        private const val BOOTSTRAP_URL = "https://s.vidcloud.se/i/73918463"
        private const val AUTHORIZE_URL = "https://s.vidcloud.se/q7m4x9"
        private const val ORIGIN = "https://senshi.to"
        private const val HEADER_MAGIC = "RNRG"
        private val INFO_BYTES = "vhost/runtime/56a572f99f".toByteArray(Charsets.US_ASCII)
        private val EMPTY = ByteArray(0)
        private const val DEFAULT_TTL_SEC = 600L
    }
}

private val SPKI_P256 = "3059301306072A8648CE3D020106082A8648CE3D030107034200"
    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
