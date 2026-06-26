package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Base64
import java.net.URLEncoder

class AniWavesUtils {

    private val key = "simple-hash"

    fun vrfEncrypt(input: String): String {
        val rc4Result = rc4(key, input)
        val bytes = rc4Result.toByteArray(Charsets.ISO_8859_1)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return URLEncoder.encode(base64, "UTF-8")
    }

    fun vrfDecrypt(input: String): String {
        val decoded = Base64.decode(input, Base64.DEFAULT)
        val rc4Input = String(decoded, Charsets.ISO_8859_1)
        return rc4(key, rc4Input)
    }

    /**
     * RC4 stream cipher matching the JavaScript implementation:
     *   function a(key, data) { KSA + PRGA-XOR }
     */
    private fun rc4(key: String, data: String): String {
        val s = IntArray(256) { it }
        var j = 0
        // Key Scheduling Algorithm
        for (i in 0..255) {
            j = (j + s[i] + key[i % key.length].code) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }
        // PRGA
        var i2 = 0
        j = 0
        val result = StringBuilder()
        for (k in data.indices) {
            i2 = (i2 + 1) % 256
            j = (j + s[i2]) % 256
            s[i2] = s[j].also { s[j] = s[i2] }
            result.append((data[k].code xor s[(s[i2] + s[j]) % 256]).toChar())
        }
        return result.toString()
    }
}
