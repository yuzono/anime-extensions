package eu.kanade.tachiyomi.animeextension.en.animetoki

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class SessionWarmUpInterceptor : Interceptor {

    @Volatile
    private var hasWarmedUp = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!hasWarmedUp) {
            synchronized(this) {
                if (!hasWarmedUp) {
                    try {
                        val ua = request.header("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
                        for (endpoint in listOf("https://animetoki.com", "https://cloud.animetoki.com", "https://drive.animetoki.com/")) {
                            try {
                                val req = Request.Builder()
                                    .url(endpoint)
                                    .header("User-Agent", ua)
                                    .build()
                                chain.proceed(req).close()
                            } catch (_: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        // Silently ignore warm-up failures, proceed with the actual request
                    } finally {
                        hasWarmedUp = true
                    }
                }
            }
        }

        return chain.proceed(request)
    }
}
