package eu.kanade.tachiyomi.animeextension.en.senshi

import android.util.Base64
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

/* vidcloud "Octopus" resolve protocol — reimplements window.__oct (vendor.js)
 * + subtitle-octopuss.wasm. All constants (gateway, paths, HKDF info string)
 * are baked into the current site build; if the handshake starts failing,
 * re-derive with `npx webcrack vendor.js`.
 *
 *   1. GET  {gw}/i/73918463   PNG, chunk "pUAK" = BTGG record:
 *      ver | epoch u64 | expires u64 | server P-256 point | challenge[16]
 *   2. ECDH(P-256) → HKDF-SHA256(salt = challenge, info = "vhost/runtime/…")
 *      → AES-256-GCM key. The salt is server-issued and rotates per bootstrap
 *      — it is echoed in the payload, never generated client-side.
 *   3. POST {gw}/q7m4x9  (Content-Type: image/png)
 *      body = "RKAJ" header ‖ iv12 ‖ GCM(payload), aad = header
 *      fresh:  pub = client point, epoch, cap empty, challenge echoed
 *      resume: epoch 0, no pub, cap = prev ticket `c` (b64url), key reused
 *      payload = 1 | videoId u64 | now u64 | nonce16 | origin | challenge16
 *   4. pUAK = "SFRZ" | 1 | iv12 | GCM ct (aad = 5-byte magic)
 *      plain = u32be seed ‖ xorshift32-obfuscated JSON {p, c, x}
 *      p = sources (old /_v1/sources shape), c = capability, x = expiry
 *
 * videoId = embeds' remote_source_id. CDN (bcdn*.se) serves plain HLS
 * (.txt playlists, .jpg-named MPEG-TS segments); tokens are bound to the
 * handshaking client, so CDN fetches must use the same OkHttpClient.
 * The wasm's FNV origin allowlist + policy check are client-side only.
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
    private class Session(val key: SecretKeySpec, val capability: String, val expiresAtSec: Long)

    @Volatile
    private var session: Session? = null
    private val lock = Any()
    private val resolveCache = HashMap<Long, Pair<List<VidcloudEntryDto>, Long>>()

    /** Blocking; called from playback threads. Dub & HardSub share one
     *  remote_source_id, so the cache absorbs the twin call. */
    fun resolve(videoId: Long): List<VidcloudEntryDto> = synchronized(lock) {
        resolveCache[videoId]?.takeIf { nowSec() < it.second }?.first ?: run {
            val entries = open(videoId)
            if (entries.isNotEmpty()) resolveCache[videoId] = entries to (nowSec() + CACHE_TTL_SEC)
            entries
        }
    }

    /** Mirrors __oct.open(): resume the session when valid, else fresh handshake. */
    private fun open(videoId: Long): List<VidcloudEntryDto> {
        val s = session
        if (s != null && s.expiresAtSec > nowSec() + EXPIRY_MARGIN_SEC) {
            try {
                return authorize(s.key, videoId, resume = true, epoch = 0L, pub = EMPTY, cap = b64urlDecode(s.capability), challenge = Z16)
                    .also { session = Session(s.key, it.capability, it.expiresAtSec) }
                    .entries
            } catch (_: Exception) {
                session = null // stale key or rejected ticket — one fresh retry below
            }
        }
        return freshOpen(videoId).entries
    }

    private fun freshOpen(videoId: Long): OctopusTicket {
        val b = bootstrap()
        val kp = newKeyPair()
        val clientPoint = rawPoint(kp)
        val key = SecretKeySpec(
            hkdfSha256(leftPad32(ecdh(kp.private, b.serverPoint)), b.challenge, INFO_BYTES, 32),
            "AES",
        )
        return authorize(key, videoId, resume = false, epoch = b.epoch, pub = clientPoint, cap = EMPTY, challenge = b.challenge)
            .also { session = Session(key, it.capability, it.expiresAtSec) }
    }

    // ============================= Bootstrap ==============================
    private class Bootstrap(val epoch: Long, val serverPoint: ByteArray, val challenge: ByteArray)

    private fun bootstrap(): Bootstrap {
        val png = http(Request.Builder().url(BOOTSTRAP_URL).headers(headers).get().build())
        val rec = pngChunk(png, "pUAK")
        check(rec.size >= 23) { "truncated bootstrap record (${rec.size}B)" }
        check(String(rec, 0, 4, Charsets.US_ASCII) == "BTGG" && rec[4].toInt() == 1) {
            "unsupported bootstrap record"
        }
        val keyLen = rdU16(rec, 21)
        check(keyLen == 65 && rec.size >= 23 + keyLen + 16 && rec[23].toInt() == 4) {
            "unexpected bootstrap point (keyLen=$keyLen, rec=${rec.size}B)"
        }
        check(rec.size >= 23 + keyLen + 16 && keyLen == 65 && rec[23].toInt() == 4) { "unexpected bootstrap point" }
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
        resume: Boolean,
        epoch: Long,
        pub: ByteArray,
        cap: ByteArray,
        challenge: ByteArray,
    ): OctopusTicket {
        // header / GCM AAD
        val header = ByteArray(18 + pub.size + 2 + cap.size)
        "RKAJ".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = 1 // version
        header[5] = if (resume) 1 else 0 // resume
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
        val rec = pngChunk(png, "pUAK")
        check(rec.size >= 33 && String(rec, 0, 4, Charsets.US_ASCII) == "SFRZ" && rec[4].toInt() == 1) {
            "unsupported response record (${rec.size}B)"
        }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, rec, 5, 12))
            updateAAD(rec, 0, 5)
            doFinal(rec, 17, rec.size - 17)
        }

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

    private fun pngChunk(png: ByteArray, type: String): ByteArray {
        check(png.size > 16 && png[0] == 0x89.toByte() && png[1] == 0x50.toByte()) { "not a PNG envelope" }
        var i = 8
        while (i + 8 <= png.size) {
            val len = rdU32(png, i)
            if (len < 0 || i + 12 + len > png.size) throw OctopusException("corrupt PNG chunk table")
            val t = String(png, i + 4, 4, Charsets.US_ASCII)
            if (t == type) return png.copyOfRange(i + 8, i + 8 + len)
            i += 12 + len
            if (t == "IEND") break
        }
        throw OctopusException("no '$type' chunk in PNG envelope")
    }

    private fun b64urlDecode(s: String): ByteArray = if (s.isEmpty()) {
        ByteArray(0)
    } else {
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
        private val INFO_BYTES = "vhost/runtime/6fc0a1073b".toByteArray(Charsets.US_ASCII)
        private val EMPTY = ByteArray(0)
        private val Z16 = ByteArray(16)
        private const val EXPIRY_MARGIN_SEC = 10L
        private const val DEFAULT_TTL_SEC = 600L
        private const val CACHE_TTL_SEC = 120L
    }
}

private val SPKI_P256 = "3059301306072A8648CE3D020106082A8648CE3D030107034200"
    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
