package aniyomi.lib

import android.util.Log
import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.unpacker.Unpacker

fun autoUnpacker(packedScript: String): String? = runCatching {
    try {
        val jsUnpacked = JsUnpacker.unpackAndCombine(packedScript)
        if (jsUnpacked.isNullOrBlank()) {
            Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
        } else {
            jsUnpacked
        }
    } catch (e: Exception) {
        Log.w("JsUnpacker", "autoUnpacker: ${e.message}", e)
        Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
    }
}.getOrNull()
