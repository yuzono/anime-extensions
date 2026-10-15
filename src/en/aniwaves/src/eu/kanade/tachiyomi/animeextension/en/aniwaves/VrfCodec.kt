package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Base64
import keiyoushi.utils.rc4
import java.net.URLEncoder

internal class VrfCodec {

    fun encrypt(input: String): String {
        val rc4Result = rc4(KEY, input.toByteArray(Charsets.ISO_8859_1))
        val base64 = Base64.encodeToString(rc4Result, Base64.NO_WRAP)
        return URLEncoder.encode(base64, "UTF-8")
    }

    private companion object {
        val KEY = "simple-hash".toByteArray()
    }
}
