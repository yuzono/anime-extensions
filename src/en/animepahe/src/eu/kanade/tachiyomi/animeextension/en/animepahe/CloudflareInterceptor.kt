package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animeextension.en.animepahe.extractor.CloudflareBypass
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class CloudflareInterceptor(
    private val client: OkHttpClient,
    private val cfBypassUserAgentProvider: () -> String = { AnimePahe.UA },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Check if we are blocked by Cloudflare
        if (response.code !in ERROR_CODES) {
            return response
        }

        val isCloudflare = response.header("cf-ray") != null
        response.close()

        if (isCloudflare) {
            val customUA = cfBypassUserAgentProvider()
            val bypassResult = CloudflareBypass().getCookies(
                pageUrl = originalRequest.url.toString(),
                customUserAgent = customUA,
            )

            if (bypassResult != null) {
                return chain.proceed(
                    originalRequest.newBuilder()
                        .header("Cookie", bypassResult.cookies)
                        .header("User-Agent", bypassResult.userAgent) // Use the UA that solved the challenge
                        .build(),
                )
            }
        }

        // If all bypasses fail, proceed with the original request anyway
        return chain.proceed(originalRequest)
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
    }
}
