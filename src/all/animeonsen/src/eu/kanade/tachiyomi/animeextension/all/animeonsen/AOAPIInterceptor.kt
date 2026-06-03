package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AOAPIInterceptor(client: OkHttpClient, apiUrl: String) : Interceptor {

    private val token: String by lazy {
        runCatching {
            val body = """
            {
                "client_id": "f296be26-28b5-4358-b5a1-6259575e23b7",
                "client_secret": "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235",
                "grant_type": "client_credentials"
            }
            """.trimIndent().toJsonRequestBody()

            val headers = Headers.headersOf("user-agent", AO_USER_AGENT)

            val tokenObject = client.newCall(
                POST(
                    "https://auth.animeonsen.xyz/oauth/token",
                    headers,
                    body,
                ),
            ).execute().parseAs<JsonObject>()

            tokenObject["access_token"]!!.jsonPrimitive.content
        }.getOrElse { "" }
    }

    private val host: String = apiUrl.toHttpUrlOrNull()?.host ?: apiUrl

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
