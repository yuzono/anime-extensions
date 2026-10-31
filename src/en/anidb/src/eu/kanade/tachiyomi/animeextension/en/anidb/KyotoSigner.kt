package eu.kanade.tachiyomi.animeextension.en.anidb

import android.os.Build
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

internal const val PLAY_URL = "https://api.kyotoplayer.com"

private const val API_HOST = "api.kyotoplayer.com"
private const val APP_ID = "com.kyotoplayer"
private const val APP_VERSION = "129"

/**
 * Signs requests the same way the official Kyoto Player client does.
 *
 * The app derives a signing key from a constant baked into `libkyoto.so` and the SHA-256 of
 * its own APK signing certificate, then HMAC-SHA256s a canonical string made of the request
 * method, path, body hash, timestamp, nonce and app version, separated by newlines.
 */
internal class KyotoInterceptor : Interceptor {
    @Volatile
    private var timeOffset = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_HOST) return chain.proceed(request)

        val usedOffset = timeOffset
        var response = chain.proceed(request.signed(usedOffset))

        val serverTime = response.header("Date")?.toEpochSeconds()
        if (serverTime != null) {
            val offset = serverTime - System.currentTimeMillis() / 1000
            timeOffset = offset
            // Reject a signature made with a stale clock and retry once with the offset
            // taken from the server's Date header, like the official client.
            if (response.code == 401 && abs(offset - usedOffset) >= 2) {
                response.close()
                response = chain.proceed(request.signed(offset))
            }
        }
        return response
    }

    private fun Request.signed(offset: Long): Request {
        val bodyBytes = Buffer().also { body?.writeTo(it) }.readByteArray()
        val timestamp = System.currentTimeMillis() / 1000 + offset
        val nonce = randomNonce()
        val signature = sign(timestamp, nonce, method, url.encodedPath, bodyBytes)

        return newBuilder()
            .header("Accept", "application/json")
            .header("os-version", Build.VERSION.SDK_INT.toString())
            .header("app-id", APP_ID)
            .header("app-version", APP_VERSION)
            .header("device-model", DEVICE_MODEL)
            .header("X-Kyoto-Ts", timestamp.toString())
            .header("X-Kyoto-Nonce", nonce)
            .header("X-Kyoto-Sig", signature)
            .build()
    }
}

private val DEVICE_MODEL by lazy {
    "${Build.MANUFACTURER} ${Build.MODEL}".filter { it in ' '..'~' }
}

private val secureRandom by lazy { SecureRandom() }

// Deobfuscated from the constant table in libkyoto.so: key[i] = data[i] xor (i * 0x49 + 0x29).
private val KEY_SEED = "c41cdebbe95894b9301a39825db7152c4b9cbabb6b8246724411fe3307a60f0f".hexToBytes()

// SHA-256 of the first certificate in the official APK's v3 signature block.
private val CERT_SHA256 = "482a900b2906766b2e8d3cf19fcf6d06d0b6c9569cb6305c9c43200a8e20979e".hexToBytes()

private val SIGNING_KEY by lazy { hmac(KEY_SEED, CERT_SHA256) }

private fun randomNonce(): String {
    val bytes = ByteArray(16).also(secureRandom::nextBytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun sign(timestamp: Long, nonce: String, method: String, path: String, body: ByteArray): String {
    val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHex()
    val canonical = listOf(method.uppercase(Locale.US), path, bodyHash, timestamp.toString(), nonce, APP_VERSION)
        .joinToString("\n")
    return hmac(SIGNING_KEY, canonical.toByteArray()).toHex()
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(key, "HmacSHA256"))
}.doFinal(data)

private fun String.toEpochSeconds(): Long? = runCatching {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
        isLenient = false
    }.parse(this)?.time?.div(1000)
}.getOrNull()

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF])
        append(HEX_DIGITS[byte.toInt() and 0xF])
    }
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
