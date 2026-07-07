package aniyomi.lib.filemoonextractor

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class FilemoonExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json = Json { ignoreUnknownKeys = true }

    // Credit: https://github.com/skoruppa/docchi-players/blob/b03fb310aba1d73c6c97ed62a7db2569a49f8d79/filemoon.py
    fun videosFromUrl(
        url: String,
        prefix: String = "Filemoon - ",
        headers: Headers? = null,
        referer: String? = null,
    ): List<Video> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = extractMediaId(httpUrl)

            val userAgent = headers?.get("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val baseHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("User-Agent", userAgent)
                set("Accept", "*/*")
                set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
            }.build()

            // Get embed URL from details endpoint to resolve the actual host
            val embedUrl = getEmbedUrl(host, mediaId, baseHeaders)
                ?: return emptyList()

            val embedHost = embedUrl.toHttpUrl().host
            val pageReferer = referer ?: embedUrl
            val origin = "https://$embedHost"

            val apiHeaders = baseHeaders.newBuilder().apply {
                set("Referer", pageReferer)
                set("Origin", origin)
            }.build()

            // Use only the new challenge flow
            val data = challengeFlow(embedHost, mediaId, apiHeaders, embedUrl, origin, pageReferer, userAgent, url)

            if (data == null) {
                Log.w("FilemoonExtractor", "No data from challenge flow for $url")
                return emptyList()
            }

            val sources = extractSources(data)
            if (sources.isNullOrEmpty()) {
                Log.w("FilemoonExtractor", "No sources found in response for $url")
                return emptyList()
            }

            val videoHeaders = baseHeaders.newBuilder().apply {
                set("Referer", origin)
                removeAll("Origin")
            }.build()

            sources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}" },
                )
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to extract video from $url", e)
            emptyList()
        }
    }

    private fun getEmbedUrl(host: String, mediaId: String, headers: Headers): String? {
        val detailsUrl = "https://$host/api/videos/$mediaId/embed/details"
        try {
            val response = client.newCall(GET(detailsUrl, headers)).execute()
            val body = response.bodyString()
            val embedFrameUrl = body
                .substringAfter("embed_frame_url", "")
                .substringAfter(":")
                .substringAfter('"')
                .substringBefore('"')
            if (embedFrameUrl.isBlank()) {
                Log.w("FilemoonExtractor", "Empty embed_frame_url from details for $host/$mediaId")
                return null
            }
            return embedFrameUrl
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to get embed URL from details", e)
            return null
        }
    }

    private fun extractMediaId(httpUrl: okhttp3.HttpUrl): String {
        val segments = httpUrl.pathSegments
        if (segments.size > 1 && (segments[0] == "e" || segments[0] == "eyi" || segments[0] == "d" || segments[0] == "download" || segments[0].startsWith("j"))) {
            return segments[1]
        }
        return segments.lastOrNull { it.isNotEmpty() } ?: return ""
    }

    private fun challengeFlow(
        host: String,
        mediaId: String,
        apiHeaders: Headers,
        embedUrl: String,
        origin: String,
        pageReferer: String,
        userAgent: String,
        pageUrl: String,
    ): PlaybackResponse? {
        val base = origin

        // Step 1: Challenge
        val challengeUrl = "$base/api/videos/access/challenge"
        val challengeData = try {
            val response = client.newCall(POST(challengeUrl, apiHeaders, "{}".toRequestBody("application/json".toMediaType()))).execute()
            response.parseAs<ChallengeResponse>()
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Challenge request failed", e)
            return null
        }

        val challengeId = challengeData.challenge_id
        val nonce = challengeData.nonce

        // Step 2: Attest (ECDSA sign nonce + fingerprint)
        val (privateKey, publicKeyJwk) = generateEcKeyPair()
        val signature = signNonce(privateKey, nonce)
        val clientFingerprint = generateClientFingerprint(userAgent)

        val attestUrl = "$base/api/videos/access/attest"
        val attestPayload = AttestRequest(
            viewer_id = "",
            device_id = "",
            challenge_id = challengeId,
            nonce = nonce,
            signature = signature,
            public_key = publicKeyJwk,
            client = clientFingerprint,
            storage = emptyMap(),
            attributes = mapOf("entropy" to "low"),
        )

        val attestData = try {
            val response = client.newCall(POST(attestUrl, apiHeaders, attestPayload.toJsonRequestBody())).execute()
            response.parseAs<AttestResponse>()
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Attest request failed", e)
            return null
        }

        val token = attestData.token
        val viewerId = attestData.viewer_id
        val deviceId = attestData.device_id
        val confidence = attestData.confidence

        val fingerprintPayload = FingerprintPayload(
            fingerprint = FingerprintData(
                token = token,
                viewer_id = viewerId,
                device_id = deviceId,
                confidence = confidence,
            ),
        )

        // Step 3: Captcha (get PoW challenge)
        val captchaUrl = "$base/api/videos/$mediaId/embed/captcha"
        val embedExtraHeaders = Headers.Builder().apply {
            set("X-Embed-Origin", "anikyuu.to")
            set("X-Embed-Referer", "https://anikyuu.to/")
            set("X-Embed-Parent", pageUrl)
            set("Cookie", "byse_viewer_id=$viewerId; byse_device_id=$deviceId")
        }.build()

        val captchaHeaders = apiHeaders.newBuilder().apply {
            embedExtraHeaders.forEach { (k, v) -> set(k, v) }
        }.build()

        val captchaData = try {
            val response = client.newCall(POST(captchaUrl, captchaHeaders, fingerprintPayload.toJsonRequestBody())).execute()
            response.parseAs<CaptchaResponse>()
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Captcha request failed", e)
            return null
        }

        val powNonce = captchaData.pow_nonce
        val powDifficulty = captchaData.pow_difficulty
        val powToken = captchaData.pow_token

        // Step 4: Solve PoW and verify
        val solution = solvePow(powNonce, powDifficulty)

        val verifyUrl = "$base/api/videos/$mediaId/embed/captcha/verify"
        val verifyPayload = VerifyRequest(
            pow_token = powToken,
            solution = solution,
            fingerprint = fingerprintPayload.fingerprint,
        )

        val verifyData = try {
            val response = client.newCall(POST(verifyUrl, captchaHeaders, verifyPayload.toJsonRequestBody())).execute()
            response.parseAs<VerifyResponse>()
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Captcha verify failed", e)
            return null
        }

        if (verifyData.status != "ok") {
            Log.e("FilemoonExtractor", "PoW verification failed: ${verifyData.status}")
            return null
        }

        val captchaToken = verifyData.token

        // Step 5: Get playback with captcha token
        val playbackUrl = "$base/api/videos/$mediaId/embed/playback"
        val playbackHeaders = captchaHeaders.newBuilder().apply {
            set("X-Captcha-Token", captchaToken!!)
        }.build()

        return try {
            val response = client.newCall(POST(playbackUrl, playbackHeaders, fingerprintPayload.toJsonRequestBody())).execute()
            response.parseAs<PlaybackResponse>()
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Playback request failed", e)
            null
        }
    }

    private fun extractSources(data: PlaybackResponse): List<VideoSource>? {
        if (!data.sources.isNullOrEmpty()) {
            return data.sources
        }
        if (data.playback != null) {
            try {
                val decrypted = decrypt(data.playback)
                val decryptedJson = decrypted.parseAs<PlaybackResponse>()
                return decryptedJson.sources
            } catch (e: Exception) {
                Log.e("FilemoonExtractor", "Decryption failed", e)
            }
        }
        return null
    }

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = when (input.version) {
            null -> input.key_parts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            else -> {
                val v = input.version.toIntOrNull() ?: 1
                val parts = input.key_parts
                if (parts.size >= v) {
                    val selected = listOf(parts[v - 1], parts[parts.size - v])
                    selected.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                } else {
                    input.key_parts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                }
            }
        }

        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        // AES-GCM: last 16 bytes are the authentication tag
        val ciphertext = payloadBytes.copyOfRange(0, payloadBytes.size - 16)
        val tag = payloadBytes.copyOfRange(payloadBytes.size - 16, payloadBytes.size)
        val encrypted = ciphertext + tag

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(encrypted)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    private fun encodeBase64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // --- Crypto helpers ---

    private fun generateEcKeyPair(): Pair<java.security.PrivateKey, EcJwk> {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val x = publicKey.w.getAffineX().toByteArray()
        val y = publicKey.w.getAffineY().toByteArray()
        // Ensure 32 bytes each (P-256)
        val xPadded = if (x.size < 32) ByteArray(32 - x.size) + x else x.copyOfRange(max(0, x.size - 32), x.size)
        val yPadded = if (y.size < 32) ByteArray(32 - y.size) + y else y.copyOfRange(max(0, y.size - 32), y.size)
        val jwk = EcJwk(
            alg = "ES256",
            crv = "P-256",
            ext = true,
            key_ops = listOf("verify"),
            kty = "EC",
            x = encodeBase64Url(xPadded),
            y = encodeBase64Url(yPadded),
        )
        return keyPair.private to jwk
    }

    private fun signNonce(privateKey: java.security.PrivateKey, nonce: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(nonce.toByteArray(StandardCharsets.UTF_8))
        val signed = signature.sign()
        return encodeBase64Url(signed)
    }

    private fun generateClientFingerprint(userAgent: String): ClientFingerprint {
        val random = SecureRandom()
        fun randHash(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }
        return ClientFingerprint(
            user_agent = userAgent,
            pixel_ratio = 1,
            screen_width = 1920,
            screen_height = 1080,
            color_depth = 24,
            languages = listOf("pt-BR", "pt", "en-US", "en"),
            timezone = "America/Sao_Paulo",
            hardware_concurrency = 8,
            touch_points = 0,
            webgl_vendor = "Google Inc. (Intel)",
            webgl_renderer = "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
            canvas_hash = randHash(),
            audio_hash = randHash(),
            webgl_params_hash = randHash(),
            fonts_hash = randHash(),
            codecs_hash = randHash(),
            media_devices = "ai0ao0vi0",
            pointer_type = "fine,hover",
            extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
        )
    }

    // --- PoW Solver (Memory-hard hash from Python) ---
    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 200000): String {
        val prefix = (nonce + ":").toByteArray(Charsets.ISO_8859_1)
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rotl(value: Long, shift: Int): Long = ((value shl shift) or (value ushr (32 - shift))) and mask32

        for (counter in 0..maxIterations) {
            val counterBytes = counter.toString().toByteArray(Charsets.ISO_8859_1)
            val input = prefix + counterBytes

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            for (b in input) {
                s0 = (s0 + (b.toInt() and 0xFF)) and mask32
                s0 = rotl(s0, 7)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            repeat(8) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            val buf = LongArray(bufferSize)
            for (i in 0 until bufferSize) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
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
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 16)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 12)
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 8)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 7)
                }
            }

            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 16)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 12)
            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 8)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 7)

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rotl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            val leading = if (outVal == 0L) 32 else 32 - outVal.toString(2).length
            if (leading >= difficulty) {
                return counter.toString()
            }
        }
        throw RuntimeException("PoW solver: no solution found in $maxIterations iterations")
    }

    // --- DTOs ---

    @Serializable
    data class ChallengeResponse(
        val challenge_id: String,
        val nonce: String,
    )

    @Serializable
    data class AttestRequest(
        val viewer_id: String,
        val device_id: String,
        val challenge_id: String,
        val nonce: String,
        val signature: String,
        val public_key: EcJwk,
        val client: ClientFingerprint,
        val storage: Map<String, String>,
        val attributes: Map<String, String>,
    )

    @Serializable
    data class AttestResponse(
        val token: String,
        val viewer_id: String,
        val device_id: String,
        val confidence: Double,
    )

    @Serializable
    data class EcJwk(
        val alg: String,
        val crv: String,
        val ext: Boolean,
        val key_ops: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    data class ClientFingerprint(
        val user_agent: String,
        val pixel_ratio: Int,
        val screen_width: Int,
        val screen_height: Int,
        val color_depth: Int,
        val languages: List<String>,
        val timezone: String,
        val hardware_concurrency: Int,
        val touch_points: Int,
        val webgl_vendor: String,
        val webgl_renderer: String,
        val canvas_hash: String,
        val audio_hash: String,
        val webgl_params_hash: String,
        val fonts_hash: String,
        val codecs_hash: String,
        val media_devices: String,
        val pointer_type: String,
        val extra: Map<String, String>,
    )

    @Serializable
    data class FingerprintPayload(
        val fingerprint: FingerprintData,
    )

    @Serializable
    data class FingerprintData(
        val token: String,
        val viewer_id: String,
        val device_id: String,
        val confidence: Double,
    )

    @Serializable
    data class CaptchaResponse(
        val pow_nonce: String,
        val pow_difficulty: Int,
        val pow_token: String,
    )

    @Serializable
    data class VerifyRequest(
        val pow_token: String,
        val solution: String,
        val fingerprint: FingerprintData,
    )

    @Serializable
    data class VerifyResponse(
        val status: String,
        val token: String? = null,
    )

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )
}

fun String.encodeUrlPath(): String {
    val uri = java.net.URI(this)

    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else {
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            }
        }

    return java.net.URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
