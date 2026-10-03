package eu.kanade.tachiyomi.animeextension.en.xanime

import android.util.Base64
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Crypto : Interceptor {
    private val secretKeyString = "xanime-ph25-obfuscation-secret-key-2026"
    private val keyBytes: ByteArray = MessageDigest.getInstance("SHA-256").digest(secretKeyString.toByteArray())
    private val secretKey = SecretKeySpec(keyBytes, "AES")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val originalBody = request.body
        var encryptedPayload: String? = null

        if (originalBody != null) {
            val buffer = Buffer()
            originalBody.writeTo(buffer)
            val plaintextBytes = buffer.readByteArray()
            encryptedPayload = encrypt(plaintextBytes)
        }

        val newRequest = request.newBuilder().apply {
            if (encryptedPayload != null) {
                post(encryptedPayload.toRequestBody("application/json".toMediaType()))
            }
        }.build()

        val originalResponse = chain.proceed(newRequest)

        val encryptedResponseStr = originalResponse.body?.string() ?: return originalResponse

        try {
            val jsonElement = encryptedResponseStr.parseAs<CryptoWrapper>()
            val ivStr = jsonElement.iv
            val ctStr = jsonElement.ct

            if (ivStr != null && ctStr != null) {
                val iv = Base64.decode(ivStr, Base64.DEFAULT)
                val ct = Base64.decode(ctStr, Base64.DEFAULT)
                val decryptedBytes = decrypt(ct, iv)

                return originalResponse.newBuilder()
                    .body(decryptedBytes.toString(Charsets.UTF_8).toResponseBody(originalResponse.body?.contentType()))
                    .build()
            }
        } catch (e: Exception) {
            // Ignore
        }

        return originalResponse.newBuilder()
            .body(encryptedResponseStr.toResponseBody(originalResponse.body?.contentType()))
            .build()
    }

    private fun encrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(data)

        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return """{"v":1,"iv":"$ivB64","ct":"$ctB64"}"""
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    @Serializable
    class CryptoWrapper(
        val iv: String? = null,
        val ct: String? = null,
    )
}
