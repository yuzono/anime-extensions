package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AOAPIInterceptor(private val client: OkHttpClient) : Interceptor {

    private var token: String? = null

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

            val responseBody = response.body?.string()

            // If we still get an HTML page (Cloudflare block or wrong endpoint), fail gracefully
            if (responseBody.isNullOrBlank() || responseBody.trimStart().startsWith("<")) {
                return null
            }

            val tokenObject = Json.decodeFromString<JsonObject>(responseBody)
            tokenObject["access_token"]?.jsonPrimitive?.content
        } catch (_: Throwable) {
            // Silently fail so we don't break endpoints that don't require auth (like Search)
            null
        }
    }

    private val host: String = apiUrl.toHttpUrlOrNull()?.host ?: apiUrl

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Fetch token lazily on the first API request
        if (token == null) {
            token = fetchToken()
        }

        // Attach token if we successfully fetched it
        val request = if (token != null) {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        // If we get a 401, the token might be invalid or expired. Try refreshing it once.
        if (response.code == 401) {
            response.close()
            token = fetchToken()

            if (token != null) {
                val newRequest = originalRequest.newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                return chain.proceed(newRequest)
            }
        }

        return response
    }
}
