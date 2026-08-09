package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class SearchInterceptor(client: OkHttpClient, baseUrl: String, searchUrl: String) : Interceptor {

    private val token: String by lazy {
        runCatching {
            val document = client.newCall(
                GET(baseUrl),
            ).execute().useAsJsoup()

            document.selectFirst("meta[name=ao-search-token]")?.attr("content") ?: ""
        }.getOrElse { "" }
    }

    private val host: String = searchUrl.toHttpUrlOrNull()?.host ?: searchUrl

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (originalRequest.url.host == host) {
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()

            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }
}
