package keiyoushi.source

import android.util.Log
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import java.io.InputStream

internal object AppFeatures {
    private val isLib17: Boolean by lazy {
        runCatching { AnimeHttpSource::class.java.getMethod("createHttpServer") }.isSuccess
    }

    // Animiru release builds strip NanoHTTPD unless they keep fi.iki.elonen.**
    val animiruHttpServer: Boolean by lazy {
        isLib17 && try {
            val loader = AnimeHttpSource::class.java.classLoader
            fun type(name: String) = Class.forName(name, false, loader)
            val nanoHttpd = type("fi.iki.elonen.NanoHTTPD")
            val session = type("fi.iki.elonen.NanoHTTPD\$IHTTPSession")
            val status = type("fi.iki.elonen.NanoHTTPD\$Response\$IStatus")
            listOf("getUri", "getMethod", "getHeaders", "getQueryParameterString").forEach { session.getMethod(it) }
            listOf("getDescription", "getRequestStatus").forEach { status.getMethod(it) }
            type("fi.iki.elonen.NanoHTTPD\$Response").getMethod("addHeader", String::class.java, String::class.java)
            nanoHttpd.getMethod("newChunkedResponse", status, String::class.java, InputStream::class.java)
            nanoHttpd.getMethod("newFixedLengthResponse", status, String::class.java, InputStream::class.java, Long::class.javaPrimitiveType)
            true
        } catch (e: ReflectiveOperationException) {
            Log.w("AniSource", "App HttpServer unusable, using own proxy", e)
            false
        }
    }
}
