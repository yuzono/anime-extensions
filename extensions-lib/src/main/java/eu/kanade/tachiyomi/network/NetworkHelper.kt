package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.OkHttpClient

@Suppress("unused_parameter")
class NetworkHelper(context: Context) {

    val client: OkHttpClient = throw Exception("Stub!")

    /**
     * @deprecated Since extension-lib 14
     */
    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient = throw Exception("Stub!")

    /**
     * @since extension-lib v14
     */
    fun defaultUserAgentProvider(): String = throw Exception("Stub!")
}
