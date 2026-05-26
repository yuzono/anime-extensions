package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class KwikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    companion object {
        private const val TAG = "AnikotoTheme-Kwik"
        private val KWIK_PARAMS_REGEX = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        private val KWIK_FORM_URL = Regex("""action="([^"]+)"""")
        private val KWIK_FORM_TOKEN = Regex("""value="([^"]+)"""")
    }

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val kwikHeaders by lazy {
        headers.newBuilder()
            .set("Origin", "https://kwik.cx")
            .set("Referer", "https://kwik.cx/")
            .build()
    }

    fun getMp4Video(kwikUrl: String, referer: String, quality: String = ""): Video {
        val videoUrl = getMp4StreamUrl(kwikUrl, referer)
        return Video(videoUrl, quality, videoUrl, headers = kwikHeaders)
    }

    fun getMp4StreamUrl(kwikUrl: String, referer: String): String {
        val isDirectFileUrl = kwikUrl.contains("/f/")
        val fileUrl = if (isDirectFileUrl) kwikUrl else kwikUrl.replace("/e/", "/f/")
        Log.d(TAG, "MP4 extraction: $fileUrl")

        val sessionCookies = if (isDirectFileUrl) {
            ""
        } else {
            try {
                client.newCall(GET(kwikUrl, buildEPageHeaders(referer))).execute().use { resp ->
                    resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Session fetch failed: ${e.message}")
                ""
            }
        }

        val fHeaders = buildFPageHeaders(referer, sessionCookies)
        val (html, allCookies) = client.newCall(GET(fileUrl, fHeaders)).execute().use { resp ->
            val fCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
            val combined = buildString {
                if (sessionCookies.isNotBlank()) append(sessionCookies).append("; ")
                append(fCookies)
            }
            (resp.body?.string().orEmpty()) to combined
        }

        val match = KWIK_PARAMS_REGEX.find(html)
            ?: throw KwikException.ExtractionException("Decryption params not found in /f/ page")

        val (fullString, key, v1Str, v2Str) = match.destructured
        val decrypted = decrypt(fullString, key, v1Str.toIntOrNull() ?: 0, v2Str.toIntOrNull() ?: 0)

        val formAction = KWIK_FORM_URL.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("No form action found")
        val token = KWIK_FORM_TOKEN.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("No form token found")

        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        val tryLimit = 5
        var currentCookies = allCookies

        while (code != 302 && tries < tryLimit) {
            tries++
            val postHeaders = Headers.Builder()
                .add("Referer", fileUrl)
                .add("Cookie", currentCookies)
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("Origin", "https://kwik.cx")
                .build()

            noRedirectClient.newCall(
                Request.Builder()
                    .url(formAction)
                    .headers(postHeaders)
                    .post(FormBody.Builder().add("_token", token).build())
                    .build(),
            ).execute().use { resp ->
                code = resp.code
                kwikLocation = resp.header("location")
                val respCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
                if (respCookies.isNotBlank()) currentCookies = "$currentCookies; $respCookies"
            }

            if (code == 403 || code == 419) {
                Log.w(TAG, "POST HTTP $code, refreshing cookies via CF client...")
                client.newCall(GET(fileUrl, buildFPageHeaders(referer, currentCookies))).execute().use { resp ->
                    val freshCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
                    if (freshCookies.isNotBlank()) currentCookies = "$currentCookies; $freshCookies"
                }
            }
        }

        return kwikLocation
            ?: throw KwikException.ExtractionException("MP4 extraction failed after $tries tries (HTTP $code)")
    }

    private fun buildEPageHeaders(referer: String): Headers = Headers.Builder()
        .add("Referer", referer)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Fetch-Dest", "iframe")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "cross-site")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    private fun buildFPageHeaders(referer: String, cookies: String = ""): Headers = Headers.Builder().apply {
        add("Referer", referer)
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        add("Accept-Language", "en-US,en;q=0.9")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-site")
        add("Upgrade-Insecure-Requests", "1")
        if (cookies.isNotBlank()) add("Cookie", cookies)
    }.build()

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        if (key.isEmpty() || v2 !in key.indices) return ""
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            if (nextIndex == -1) break

            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1

            try {
                val charCode = decodedCharStr.toInt(v2) - v1
                if (charCode in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
                    sb.append(charCode.toChar())
                }
            } catch (_: Exception) { }
        }
        return sb.toString()
    }

    sealed class KwikException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ExtractionException(message: String, cause: Throwable? = null) : KwikException(message, cause)
    }
}
