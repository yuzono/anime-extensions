package eu.kanade.tachiyomi.animeextension.en.animeverse

import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val authLock = Any()
private var sessionCookie = ""
private var authKey = ""
private var authExpires = 0L

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun getAuth(networkClient: OkHttpClient, fingerprint: String): Pair<String, String> = synchronized(authLock) {
    if (authKey.isNotEmpty() && System.currentTimeMillis() / 1000 < authExpires) {
        return authKey to sessionCookie
    }

    val body = """{"fp":$fingerprint}""".toJsonBody()

    val sessionReq = Request.Builder()
        .url("https://animeverse.to/api/v1/session")
        .post(body)
        .header("Content-Type", "application/json")
        .build()

    val sessionResp = networkClient.newCall(sessionReq).execute()
    val respBody = sessionResp.bodyString()

    if (!sessionResp.isSuccessful) {
        invalidateAuth()
        sessionResp.close()
        throw Exception("Session failed (${sessionResp.code}): $respBody")
    }

    sessionResp.headers("Set-Cookie")
        .firstOrNull { it.startsWith("av_session=") }
        ?.substringAfter("=")?.substringBefore(";")
        ?.let { sessionCookie = it }

    val obj = try {
        respBody.parseAs<JsonElement>(json).jsonObject
    } catch (_: Exception) {
        invalidateAuth()
        sessionResp.close()
        throw Exception("Invalid session JSON: $respBody")
    }

    val key = obj["clientAuthKey"]?.jsonPrimitive?.contentOrNull
    if (key.isNullOrEmpty()) {
        invalidateAuth()
        sessionResp.close()
        throw Exception("No clientAuthKey in response: $respBody")
    }

    authKey = key
    authExpires = obj["expiresAt"]?.jsonPrimitive?.longOrNull
        ?: (System.currentTimeMillis() / 1000 + 3600)
    sessionResp.close()

    authKey to sessionCookie
}

fun invalidateAuth() = synchronized(authLock) {
    authKey = ""
    authExpires = 0L
    sessionCookie = ""
}

fun authInterceptor(chain: Interceptor.Chain, networkClient: OkHttpClient, fingerprint: String): Response {
    val req = chain.request()
    if (!req.url.encodedPath.startsWith("/api/v1/")) return chain.proceed(req)

    val signed = sign(req, networkClient, fingerprint)
    val resp = chain.proceed(signed)
    if (resp.code != 401) return resp
    resp.close()

    invalidateAuth()
    return chain.proceed(sign(req, networkClient, fingerprint))
}

private fun sign(request: Request, networkClient: OkHttpClient, fingerprint: String): Request {
    val (key, cookie) = getAuth(networkClient, fingerprint)
    if (key.isEmpty()) return request

    val ts = System.currentTimeMillis().toString()
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(base64UrlDecode(key), "HmacSHA256"))
    }
    val sig = base64UrlEncode(
        mac.doFinal("${request.method}|${request.url.encodedPath}|$ts".toByteArray()).copyOf(16),
    )
    return request.newBuilder()
        .header("x-av-ts", ts)
        .header("x-av-sig", sig)
        .header("Cookie", "av_session=$cookie")
        .build()
}
