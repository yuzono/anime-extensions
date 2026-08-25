package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client for "Byse"  behind BYFMS server.
 *
 * Port of lib:filemoonextractor's challenge without embed/details.
 */
class ByseExtractor(private val client: OkHttpClient) {

    class ByseSource(val url: String, val label: String?, val subtitles: List<Track>)

    companion object {
        /**
         * Byse hates Android user agents, so yeah
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }

    suspend fun extract(embedUrl: String, embedParent: String, embedOrigin: String): List<ByseSource> {
        val origin = "https://${embedUrl.toHttpUrl().host}"
        val mediaId = embedUrl.toHttpUrl().pathSegments.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: throw Exception("Byse: could not read media id from $embedUrl")

        val challenge = client.newCall(
            POST("$origin/api/videos/access/challenge", apiHeaders(), emptyBody),
        ).awaitSuccess().parseAs<Challenge>()

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey

        fun padded32(v: ByteArray): ByteArray = when {
            v.size > 32 -> v.copyOfRange(v.size - 32, v.size)
            v.size < 32 -> ByteArray(32 - v.size) + v
            else -> v
        }

        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(challenge.nonce.toByteArray(StandardCharsets.UTF_8))
        }
        val signature = signer.sign()

        val attest = client.newCall(
            POST(
                "$origin/api/videos/access/attest",
                apiHeaders(),
                AttestRequest(
                    viewerId = "",
                    deviceId = "",
                    challengeId = challenge.challengeId,
                    nonce = challenge.nonce,
                    signature = encodeBase64Url(signature),
                    publicKey = EcJwk(
                        alg = "ES256",
                        crv = "P-256",
                        ext = true,
                        keyOps = listOf("verify"),
                        kty = "EC",
                        x = encodeBase64Url(padded32(publicKey.w.affineX.toByteArray())),
                        y = encodeBase64Url(padded32(publicKey.w.affineY.toByteArray())),
                    ),
                    client = buildFingerprint(),
                    storage = emptyMap(),
                    attributes = mapOf("entropy" to "low"),
                ).toJsonRequestBody(),
            ),
        ).awaitSuccess().parseAs<AttestResponse>()

        val fingerprint = Fingerprint(
            token = attest.token,
            viewerId = attest.viewerId,
            deviceId = attest.deviceId,
            confidence = attest.confidence,
        )

        val gateHeaders = Headers.Builder().apply {
            add("Cookie", "byse_viewer_id=${attest.viewerId}; byse_device_id=${attest.deviceId}")
            add("X-Embed-Origin", embedOrigin.toHttpUrl().host)
            add("X-Embed-Referer", embedParent)
            add("X-Embed-Parent", embedParent)
        }.build()

        val captcha = client.newCall(
            POST(
                "$origin/api/videos/$mediaId/embed/captcha",
                apiHeaders(gateHeaders),
                FingerprintPayload(fingerprint).toJsonRequestBody(),
            ),
        ).awaitSuccess().parseAs<CaptchaChallenge>()

        val verify = client.newCall(
            POST(
                "$origin/api/videos/$mediaId/embed/captcha/verify",
                apiHeaders(gateHeaders),
                VerifyRequest(
                    powToken = captcha.powToken,
                    solution = solvePow(captcha.powNonce, captcha.powDifficulty),
                    fingerprint = fingerprint,
                ).toJsonRequestBody(),
            ),
        ).awaitSuccess().parseAs<VerifyResponse>()

        if (verify.status != "ok") throw Exception("Byse: PoW verification failed (${verify.status})")
        val captchaToken = verify.token?.takeIf(String::isNotBlank)
            ?: throw Exception("Byse: missing captcha token")

        val playback = client.newCall(
            POST(
                "$origin/api/videos/$mediaId/embed/playback",
                apiHeaders(gateHeaders).newBuilder().add("X-Captcha-Token", captchaToken).build(),
                FingerprintPayload(fingerprint).toJsonRequestBody(),
            ),
        ).awaitSuccess().parseAs<PlaybackResponse>()

        val decrypted = playback.playback?.let { decrypt(it) }?.parseAs<DecryptedPlayback>()
            ?: throw Exception("Byse: no playback payload")

        val subtitles = decrypted.tracks.orEmpty().mapNotNull { track ->
            val file = track.file ?: track.url ?: return@mapNotNull null
            if (!file.startsWith("http")) return@mapNotNull null
            Track(file, track.label ?: track.language ?: "Subtitle")
        }.distinctBy { it.url + it.lang }

        val sources = decrypted.sources.orEmpty().mapNotNull { source ->
            val url = source.url ?: source.file ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            ByseSource(url, source.label, subtitles)
        }
        return sources
    }

    // ============================ HTTP helpers ============================

    private val emptyBody = "{}".toJsonRequestBody()

    private fun apiHeaders(extra: Headers? = null): Headers = Headers.Builder().apply {
        add("Accept", "*/*")
        add("Accept-Language", "en-US,en;q=0.9")
        add("Cache-Control", "no-cache")
        add("Pragma", "no-cache")
        add("User-Agent", USER_AGENT)
        if (extra != null) for ((name, value) in extra) add(name, value)
    }.build()

    // =============================== Crypto ===============================

    private fun decrypt(input: EncryptedPlayback): String {
        fun List<String>.concatDecoded(): ByteArray = fold(ByteArray(0)) { acc, part -> acc + decodeBase64Url(part) }

        val keyBytes = when {
            input.version == null -> input.keyParts.concatDecoded()
            else -> {
                val version = input.version.toIntOrNull() ?: 1
                if (input.keyParts.size >= version) {
                    listOf(input.keyParts[version - 1], input.keyParts[input.keyParts.size - version]).concatDecoded()
                } else {
                    input.keyParts.concatDecoded()
                }
            }
        }

        val payloadBytes = decodeBase64Url(input.payload)
        if (payloadBytes.size < 16) throw Exception("Byse: payload too short")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, decodeBase64Url(input.iv)),
        )
        return String(cipher.doFinal(payloadBytes), StandardCharsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    private fun encodeBase64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun buildFingerprint() = ClientFingerprint(
        userAgent = USER_AGENT,
        pixelRatio = 1,
        screenWidth = 1920,
        screenHeight = 1080,
        colorDepth = 24,
        languages = listOf("en-US", "en"),
        timezone = "America/New_York",
        hardwareConcurrency = 8,
        touchPoints = 0,
        webglVendor = "Google Inc. (Intel)",
        webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
        canvasHash = randomHash(),
        audioHash = randomHash(),
        webglParamsHash = randomHash(),
        fontsHash = randomHash(),
        codecsHash = randomHash(),
        mediaDevices = "ai0ao0vi0",
        pointerType = "fine,hover",
        extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
    )

    private fun randomHash(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return encodeBase64Url(bytes)
    }

    // ================================ PoW =================================

    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 2_000_000): String {
        val prefix = "$nonce:".toByteArray(Charsets.ISO_8859_1)
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rotl(value: Long, shift: Int): Long = ((value shl shift) or (value ushr (32 - shift))) and mask32

        val buf = LongArray(bufferSize)
        for (counter in 0..maxIterations) {
            val input = prefix + counter.toString().toByteArray(Charsets.ISO_8859_1)

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            fun mix() {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            for (b in input) {
                s0 = (s0 + (b.toInt() and 0xFF)) and mask32
                s0 = rotl(s0, 7)
                mix()
            }
            repeat(8) { mix() }

            for (i in 0 until bufferSize) {
                mix()
                buf[i] = (s0 xor s2) and mask32
            }

            repeat(2) {
                for (si in 0 until bufferSize) {
                    val a = (buf[si] and bufferMask.toLong()).toInt()
                    var c = (buf[si] + buf[a]) and mask32
                    c = rotl(c, 13)
                    c = (c xor ((buf[(si + 1) and bufferMask] * initConst) and mask32)) and mask32
                    buf[si] = c
                    s0 = (s0 xor c) and mask32
                    mix()
                }
            }

            mix()

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rotl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            if (outVal.toInt().countLeadingZeroBits() >= difficulty) {
                return counter.toString()
            }
        }
        throw Exception("Byse: PoW exhausted ($maxIterations iterations, difficulty=$difficulty)")
    }

    // ================================ DTOs ================================

    @Serializable
    class Challenge(@SerialName("challenge_id") val challengeId: String, val nonce: String)

    /** Public-key JWK in the exact shape the attestation endpoint expects. */
    @Serializable
    class EcJwk(
        val alg: String,
        val crv: String,
        val ext: Boolean,
        @SerialName("key_ops") val keyOps: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    class ClientFingerprint(
        @SerialName("user_agent") val userAgent: String,
        @SerialName("pixel_ratio") val pixelRatio: Int,
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("screen_height") val screenHeight: Int,
        @SerialName("color_depth") val colorDepth: Int,
        val languages: List<String>,
        val timezone: String,
        @SerialName("hardware_concurrency") val hardwareConcurrency: Int,
        @SerialName("touch_points") val touchPoints: Int,
        @SerialName("webgl_vendor") val webglVendor: String,
        @SerialName("webgl_renderer") val webglRenderer: String,
        @SerialName("canvas_hash") val canvasHash: String,
        @SerialName("audio_hash") val audioHash: String,
        @SerialName("webgl_params_hash") val webglParamsHash: String,
        @SerialName("fonts_hash") val fontsHash: String,
        @SerialName("codecs_hash") val codecsHash: String,
        @SerialName("media_devices") val mediaDevices: String,
        @SerialName("pointer_type") val pointerType: String,
        val extra: Map<String, String>,
    )

    @Serializable
    class AttestRequest(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: EcJwk,
        val client: ClientFingerprint,
        val storage: Map<String, String>,
        val attributes: Map<String, String>,
    )

    @Serializable
    class AttestResponse(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    class Fingerprint(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    class FingerprintPayload(val fingerprint: Fingerprint)

    @Serializable
    class CaptchaChallenge(
        @SerialName("pow_nonce") val powNonce: String,
        @SerialName("pow_difficulty") val powDifficulty: Int,
        @SerialName("pow_token") val powToken: String,
    )

    @Serializable
    class VerifyRequest(
        @SerialName("pow_token") val powToken: String,
        val solution: String,
        val fingerprint: Fingerprint,
    )

    @Serializable
    class VerifyResponse(val status: String, val token: String? = null)

    @Serializable
    class PlaybackResponse(val playback: EncryptedPlayback? = null)

    @Serializable
    class EncryptedPlayback(
        val iv: String,
        @SerialName("key_parts") val keyParts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    class DecryptedPlayback(
        val sources: List<VideoSource>? = null,
        val tracks: List<Subtitle>? = null,
    )

    @Serializable
    class VideoSource(val url: String? = null, val file: String? = null, val label: String? = null)

    @Serializable
    class Subtitle(
        val url: String? = null,
        val file: String? = null,
        val label: String? = null,
        val language: String? = null,
    )
}
