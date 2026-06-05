package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.webkit.CookieManager
import eu.kanade.tachiyomi.animeextension.en.animepahe.extractor.CloudflareBypass
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class DdosGuardInterceptor(
    private val client: OkHttpClient,
    private val cfBypassUserAgentProvider: () -> String = { AnimePahe.UA },
) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Check if we are blocked (DDos-GUARD or Cloudflare)
        if (response.code !in ERROR_CODES) {
            return response
        }

        val isDdosGuard = response.header("Server") in SERVER_CHECK
        val isCloudflare = response.header("cf-ray") != null // Detect Cloudflare

        response.close()

        // Try standard DDoS-Guard bypass first if it's a DDos-Guard 403
        if (isDdosGuard) {
            val cookies = cookieManager.getCookie(originalRequest.url.toString())
            val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
                cookies.split(";").mapNotNull { Cookie.parse(originalRequest.url, it) }
            } else {
                emptyList()
            }
            val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
            if (!ddg2Cookie?.value.isNullOrEmpty()) {
                return chain.proceed(originalRequest)
            }

            val newCookie = getNewCookie(originalRequest.url)
            if (newCookie != null) {
                val newCookieHeader = (oldCookie + newCookie).joinToString("; ") {
                    "${it.name}=${it.value}"
                }
                return chain.proceed(originalRequest.newBuilder().addHeader("Cookie", newCookieHeader).build())
            }
        }

        // Fallback to WebView Cloudflare/DDos-Guard bypass using the custom User-Agent
        if (isCloudflare || isDdosGuard) {
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

    fun getNewCookie(url: HttpUrl): Cookie? {
        val cookies = cookieManager.getCookie(url.toString())
        val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return ddg2Cookie
        }
        val wellKnown = client.newCall(GET(WELL_KNOWN_URL))
            .execute().bodyString()
            .substringAfter("'", "")
            .substringBefore("'", "")
        val checkUrl = "${url.scheme}://${url.host + wellKnown}"
        return client.newCall(GET(checkUrl)).execute()
            .use { it.header("set-cookie") }
            ?.let { Cookie.parse(url, it) }
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = listOf("ddos-guard")
    }
}
