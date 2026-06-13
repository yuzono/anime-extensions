package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.animeextension.all.animeonsen.AnimeOnsen.Companion.AO_USER_AGENT
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AOAPIInterceptor(private val client: OkHttpClient, apiUrl: String) : Interceptor {

    private var token: String? = null

    private val host: String = apiUrl.toHttpUrlOrNull()?.host ?: apiUrl

    // Create a separate client for fetching the token to avoid infinite recursion
    private val tokenClient by lazy {
        client.newBuilder()
            .apply { interceptors().removeAll { it is AOAPIInterceptor } }
            .build()
    }

    @Synchronized
    private fun fetchToken(): String? {
        return try {
            val formBody = FormBody.Builder()
                .add("client_id", "f296be26-28b5-4358-b5a1-6259575e23b7")
                .add("client_secret", "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235")
                .add("grant_type", "client_credentials")
                .build()

            val headers = Headers.headersOf(
                "User-Agent",
                AO_USER_AGENT,
                "Accept",
                "application/json",
                "Origin",
                "https://www.animeonsen.xyz",
                "Referer",
                "https://www.animeonsen.xyz/",
            )

            val response = tokenClient.newCall(
                POST(
                    "https://auth.animeonsen.xyz/oauth/token",
                    headers,
                    formBody,
                ),
            ).execute()

            val responseBody = response.bodyString()

            // If we still get an HTML page (Cloudflare block or wrong endpoint), fail gracefully
            if (responseBody.isBlank() || responseBody.trimStart().startsWith("<")) {
                return null
            }

            val tokenObject = responseBody.parseAs<JsonObject>()
            tokenObject["access_token"]?.jsonPrimitive?.content
        } catch (_: Throwable) {
            // Silently fail so we don't break endpoints that don't require auth (like Search)
            null
        }
    }

    @Synchronized
    private fun getOrRefreshToken(oldToken: String?): String? {
        if (token == oldToken) {
            token = fetchToken()
        }
        return token
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Only apply API token to the API host, let SearchInterceptor handle the search host
        if (originalRequest.url.host != host) {
            return chain.proceed(originalRequest)
        }

        val currentToken = getOrRefreshToken(null)

        val request = if (currentToken != null) {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $currentToken")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        if (response.code == 401) {
            response.close()
            val newToken = getOrRefreshToken(currentToken)

            if (newToken != null) {
                val newRequest = originalRequest.newBuilder()
                    .addHeader("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(newRequest)
            }
        }

        return response
    }
}
