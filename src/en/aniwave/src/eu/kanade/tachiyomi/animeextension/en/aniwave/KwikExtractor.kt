package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.app.Application
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class KwikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val appContext: Application,
) {
    companion object {
        private const val TAG = "AniWave-Kwik"
        private val M3U8_REGEX = Regex("""(https?://[^\s\\'"]+\.m3u8[^\s\\'"]*?)""")
        private val KWIK_PARAMS_REGEX = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        private val KWIK_FORM_URL = Regex("""action="([^"]+)"""")
        private val KWIK_FORM_TOKEN = Regex("""value="([^"]+)"""")
    }

    // Clone the base client so interceptors, cookie jars, logging, etc. are preserved,
    // and only override redirect behavior.
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

    private val cfBypass by lazy { CloudflareBypass(appContext) }

    @Volatile
    private var cachedBypass: CloudFlareBypassResult? = null
    private var bypassTimestamp = 0L
    private val bypassLock = Any()
    private val bypassCacheTtl = 5 * 60 * 1000L

    suspend fun getHlsVideo(kwikUrl: String, referer: String, quality: String = ""): Video {
        val videoUrl = getHlsStreamUrl(kwikUrl, referer)

        return Video(
            videoUrl,
            quality,
            videoUrl,
            headers = kwikHeaders,
        )
    }

    suspend fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val html = client.newCall(GET(kwikUrl, buildEPageHeaders(referer))).awaitSuccess().use { response ->
            if (!response.isSuccessful) throw KwikException.ExtractionException("kwik.cx /e/ returned HTTP ${response.code}")
            response.body.string().takeIf(String::isNotBlank) ?: throw KwikException.ExtractionException("Empty response body from /e/")
        }
        return parseHlsFromHtml(html, kwikUrl)
    }

    fun getMp4Video(paheUrl: String, referer: String, quality: String = ""): Video {
        val videoUrl = getMp4StreamUrl(paheUrl, referer)

        return Video(
            videoUrl,
            quality,
            videoUrl,
            headers = kwikHeaders,
        )
    }

    fun getMp4StreamUrl(kwikEmbedUrl: String, referer: String): String {
        val fileUrl = kwikEmbedUrl.replace("/e/", "/f/")
        Log.d(TAG, "MP4 extraction: $fileUrl")

        // Visit /e/ first to establish session
        val sessionCookies = try {
            client.newCall(GET(kwikEmbedUrl, buildEPageHeaders(referer))).execute().use { resp ->
                resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session fetch failed: ${e.message}")
            ""
        }

        // Fetch /f/ page
        val fHeaders = buildFPageHeaders(referer, sessionCookies)
        var (html, allCookies) = client.newCall(GET(fileUrl, fHeaders)).execute().use { resp ->
            val fCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
            val combined = buildString {
                if (sessionCookies.isNotBlank()) append(sessionCookies).append("; ")
                append(fCookies)
            }
            resp.body.string() to combined
        }

        // Try CF bypass if decryption params missing
        if (!KWIK_PARAMS_REGEX.containsMatchIn(html) && !html.contains("eval(function(")) {
            Log.w(TAG, "/f/ missing decryption params, trying CF bypass...")
            getOrRefreshBypass(fileUrl)?.let { bypass ->
                val bypassCookies = buildString {
                    if (allCookies.isNotBlank()) append(allCookies).append("; ")
                    append(bypass.cookies)
                }
                client.newCall(GET(fileUrl, buildFPageHeaders(referer, bypassCookies))).execute().use { resp ->
                    html = resp.body.string()
                    val extraCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
                    allCookies = "$bypassCookies; $extraCookies"
                }
            }
        }

        val match = KWIK_PARAMS_REGEX.find(html)
            ?: throw KwikException.ExtractionException("Decryption params not found in /f/ page")

        val (fullString, key, v1Str, v2Str) = match.destructured
        val decrypted = decrypt(fullString, key, v1Str.toIntOrNull() ?: 0, v2Str.toIntOrNull() ?: 0)

        val formAction = KWIK_FORM_URL.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("No form action found")
        val token = KWIK_FORM_TOKEN.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("No form token found")

        // POST form with retry on 403/419

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
                Log.w(TAG, "POST HTTP $code, refreshing CF bypass...")
                synchronized(bypassLock) { cachedBypass = null }
                getOrRefreshBypass(fileUrl)?.let { bypass ->
                    currentCookies = "$currentCookies; ${bypass.cookies}"
                }
                    ?: throw KwikException.CloudflareBlockedException("Cloudflare bypass failed to return result.")
            }
        }

        return kwikLocation ?: throw KwikException.ExtractionException("MP4 extraction failed after $tries tries (HTTP $code)")
    }

    // ========================= Headers =========================

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
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-site")
            .add("Upgrade-Insecure-Requests", "1")
        if (cookies.isNotBlank()) add("Cookie", cookies)
    }.build()

    // ========================= CF Bypass =========================

    private fun getOrRefreshBypass(url: String): CloudFlareBypassResult? {
        synchronized(bypassLock) {
            if (cachedBypass != null && System.currentTimeMillis() - bypassTimestamp < bypassCacheTtl) {
                return cachedBypass
            }
        }
        Log.d(TAG, "Requesting CF bypass for $url")
        return cfBypass.getCookies(url)?.also {
            synchronized(bypassLock) {
                cachedBypass = it
                bypassTimestamp = System.currentTimeMillis()
            }
        }
    }

    // ========================= HLS Parsing =========================

    private fun parseHlsFromHtml(html: String, baseUrl: String): String {
        val document = Jsoup.parse(html, baseUrl)
        val packedScripts = document.select("script:containsData(eval\\(function)")

        if (packedScripts.isEmpty()) {
            throw KwikException.ExtractionException("No packed scripts found (${html.length} bytes)")
        }

        for (script in packedScripts) {
            val scriptData = script.data()
            val evalPositions = mutableListOf<Int>()
            var searchFrom = 0
            while (true) {
                val idx = scriptData.indexOf("eval(function(", searchFrom)
                if (idx == -1) break
                evalPositions.add(idx)
                searchFrom = idx + 1
            }

            for (pos in evalPositions.reversed()) {
                val packedContent = scriptData.substring(pos + "eval(".length)
                if (packedContent.isBlank()) continue
                try {
                    JsUnpacker.unpackAndCombine("eval($packedContent")?.let { unpacked ->
                        extractM3u8FromUnpacked(unpacked)?.let { return it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unpack failed at pos $pos: ${e.message}")
                }
            }
        }

        throw KwikException.ExtractionException("Could not extract m3u8 from /e/ page")
    }

    private fun extractM3u8FromUnpacked(unpacked: String): String? {
        // Try various patterns for source URL
        val patterns = listOf(
            "const source=\\'" to "\\';",
            "var source=\\'" to "\\';",
            "const source='" to "';",
            "var source='" to "';",
            "const source=\"" to "\";",
            "var source=\"" to "\";",
        )
        for ((prefix, suffix) in patterns) {
            unpacked.substringAfter(prefix).substringBefore(suffix).let { url ->
                if (url.startsWith("http") && ".m3u8" in url) return url
            }
        }
        return M3U8_REGEX.find(unpacked)?.groupValues?.get(1)
    }

    // ========================= Decryption =========================

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        if (key.isEmpty() || v2 !in key.indices) return ""

        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)

            // No more found, early return
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
            } catch (_: Exception) {
                // Ignore invalid number formats securely
            }
        }

        return sb.toString()
    }

    sealed class KwikException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ExtractionException(message: String, cause: Throwable? = null) : KwikException(message, cause)
        class CloudflareBlockedException(message: String) : KwikException(message)
    }
}
