package eu.kanade.tachiyomi.animeextension.en.animetoki

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

class SessionWarmUpInterceptor : Interceptor {

    private val hasWarmedUp = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!hasWarmedUp.getAndSet(true)) {
            try {
                val baseRequest = Request.Builder()
                    .url("https://animetoki.com")
                    .header("User-Agent", request.header("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0")
                    .build()
                chain.proceed(baseRequest).close()

                val cloudRequest = Request.Builder()
                    .url("https://cloud.animetoki.com")
                    .header("User-Agent", request.header("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0")
                    .build()
                chain.proceed(cloudRequest).close()
            } catch (e: Exception) {
                // Silently ignore warm-up failures, proceed with the actual request
            }
        }

        return chain.proceed(request)
    }
}
