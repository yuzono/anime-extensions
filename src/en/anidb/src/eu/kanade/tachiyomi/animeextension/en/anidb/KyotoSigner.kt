package eu.kanade.tachiyomi.animeextension.en.anidb

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import kotlin.math.abs

internal const val PLAY_URL = "https://api.kyotoplayer.com"

private const val API_HOST = "api.kyotoplayer.com"
private const val APP_ID = "com.kyotoplayer"
private const val APP_VERSION = "129"

// Android 16, which every device in DEVICE_MODELS runs
private const val OS_VERSION = "36"

/**
 * Signs requests the same way the official Kyoto Player client does.
 *
 * The app derives a signing key from a constant baked into `libkyoto.so` and the SHA-256 of
 * its own APK signing certificate, then HMAC-SHA256s a canonical string made of the request
 * method, path, body hash, timestamp, nonce and app version, separated by newlines.
 */
internal class KyotoInterceptor(private val deviceModel: () -> String) : Interceptor {
    @Volatile
    private var timeOffset = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_HOST) return chain.proceed(request)

        val usedOffset = timeOffset
        val response = chain.proceed(request.signed(usedOffset))
        val serverTime = response.headers.getDate("Date") ?: return response
        val offset = serverTime.time / 1000 - System.currentTimeMillis() / 1000
        timeOffset = offset

        // Like the official client, retry a rejected signature once with the server's clock
        if (response.code != 401 || abs(offset - usedOffset) < 2) return response
        response.close()
        return chain.proceed(request.signed(offset))
    }

    private fun Request.signed(offset: Long): Request {
        val timestamp = (System.currentTimeMillis() / 1000 + offset).toString()
        val nonce = ByteArray(16).also(secureRandom::nextBytes).toByteString().base64Url().trimEnd('=')
        val bodyHash = Buffer().also { body?.writeTo(it) }.sha256().hex()
        val canonical = listOf(method, url.encodedPath, bodyHash, timestamp, nonce, APP_VERSION).joinToString("\n")

        return newBuilder()
            .header("Accept", "application/json")
            .header("os-version", OS_VERSION)
            .header("app-id", APP_ID)
            .header("app-version", APP_VERSION)
            .header("device-model", deviceModel())
            .header("X-Kyoto-Ts", timestamp)
            .header("X-Kyoto-Nonce", nonce)
            .header("X-Kyoto-Sig", canonical.encodeUtf8().hmacSha256(SIGNING_KEY).hex())
            .build()
    }
}

/**
 * The official client sends the real "<Build.MANUFACTURER> <Build.MODEL>". Send a recent device picked once
 * per install instead, so the user's own device isn't reported.
 */
internal fun SharedPreferences.kyotoDeviceModel(): String = getString(PREF_DEVICE_KEY, null)
    ?: DEVICE_MODELS.random().also { edit().putString(PREF_DEVICE_KEY, it).apply() }

private const val PREF_DEVICE_KEY = "kyoto_device_model"

private val DEVICE_MODELS = listOf(
    "Google Pixel 8",
    "Google Pixel 8 Pro",
    "Google Pixel 8a",
    "Google Pixel 9",
    "Google Pixel 9 Pro",
    "Google Pixel 9 Pro XL",
    "Google Pixel 9a",
    "Google Pixel 10",
    "Google Pixel 10 Pro",
    "samsung SM-S921B",
    "samsung SM-S926B",
    "samsung SM-S928B",
    "samsung SM-S931B",
    "samsung SM-S936B",
    "samsung SM-S938B",
    "samsung SM-A356B",
    "samsung SM-A556B",
    "samsung SM-A566B",
    "OnePlus CPH2581",
)

private val secureRandom by lazy { SecureRandom() }

// HMAC-SHA256 keyed with the constant table in libkyoto.so (key[i] = data[i] xor (i * 0x49 + 0x29)),
// over the SHA-256 of the first certificate in the official APK's v3 signature block.
private val SIGNING_KEY by lazy {
    "482a900b2906766b2e8d3cf19fcf6d06d0b6c9569cb6305c9c43200a8e20979e".decodeHex()
        .hmacSha256("c41cdebbe95894b9301a39825db7152c4b9cbabb6b8246724411fe3307a60f0f".decodeHex())
}
