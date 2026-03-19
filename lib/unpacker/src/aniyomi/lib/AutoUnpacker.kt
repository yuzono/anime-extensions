package aniyomi.lib

import android.util.Log
import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.unpacker.Unpacker

fun autoUnpacker(packedScript: String): String? = runCatching {
    val jsUnpacker = try {
        JsUnpacker.unpackAndCombine(packedScript)
    } catch (e: Exception) {
        Log.w("JsUnpacker", "autoUnpacker: ${e.message}", e)
        null
    }
    jsUnpacker ?: Unpacker.unpack(packedScript).takeIf(String::isNotBlank)
}.getOrNull()
