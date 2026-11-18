package eu.kanade.tachiyomi.animesource.model

import fi.iki.elonen.NanoHTTPD
import keiyoushi.annotation.AnimiruOnly

@AnimiruOnly
open class HttpServer : NanoHTTPD(0) {
    val url: String = throw Exception("Stub!")

    companion object {
        const val PLACEHOLDER_URL = "http://localhost:1"
    }
}
