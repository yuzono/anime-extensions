package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Base64
import java.net.URLEncoder

internal class VrfCodec {

    fun encrypt(input: String): String {
        val rc4Result = rc4(KEY, input)
        val base64 = Base64.encodeToString(rc4Result.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
        return URLEncoder.encode(base64, "UTF-8")
    }

    fun decrypt(input: String): String {
        val decoded = Base64.decode(input, Base64.DEFAULT)
        return rc4(KEY, String(decoded, Charsets.ISO_8859_1))
    }

    private fun rc4(key: String, data: String): String {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + key[i % key.length].code) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }
        var i = 0
        j = 0
        val result = StringBuilder(data.length)
        for (k in data.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            s[i] = s[j].also { s[j] = s[i] }
            result.append((data[k].code xor s[(s[i] + s[j]) % 256]).toChar())
        }
        return result.toString()
    }

    private companion object {
        const val KEY = "simple-hash"
    }
}
