package aniyomi.lib

import android.util.Log
import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.unpacker.Unpacker

fun autoUnpacker(packedScript: String): String? = runCatching {
    try {
        JsUnpacker.unpackAndCombine(packedScript)
            ?: Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
    } catch (e: Exception) {
        Log.w("JsUnpacker", "autoUnpacker: ${e.message}", e)
        Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
    }
}.getOrNull()
