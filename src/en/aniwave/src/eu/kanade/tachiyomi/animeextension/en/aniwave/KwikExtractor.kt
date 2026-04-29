package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.app.Application
import android.util.Log
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class KwikContent(val cookies: String, val html: String, val finalUrl: String)

class KwikExtractor(
    private val client: OkHttpClient,
    private val appContext: Application? = null,
) {
    companion object {
        private const val TAG = "AniWave-Kwik"
        private val M3U8_REGEX = Regex("""(https?://[^\s\\'"]+\.m3u8[^\s\\'"]*?)""")
        private val KWIK_PARAMS_REGEX = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        private val KWIK_FORM_URL = Regex("""action="([^"]+)"""")
        private val KWIK_FORM_TOKEN = Regex("""value="([^"]+)"""")
    }

    private val kwikClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(client.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    private val bypassCache = mutableMapOf<String, Pair<CloudFlareBypassResult, Long>>()
    private val bypassCacheTtl = 5 * 60 * 1000L // 5 minutes

    // ========================= HLS =========================

    fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val html = client.newCall(GET(kwikUrl, buildEPageHeaders(referer))).execute().use { response ->
            if (!response.isSuccessful) throw KwikException.ExtractionException("kwik.cx /e/ returned HTTP ${response.code}")
            response.body.string() ?: throw KwikException.ExtractionException("Empty response body from /e/")
        }
        return parseHlsFromHtml(html, kwikUrl)
    }

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

    // ========================= MP4 =========================

    fun getMp4StreamUrl(kwikEmbedUrl: String, referer: String): String {
        val fUrl = kwikEmbedUrl.replace("/e/", "/f/")
        return getStreamUrlFromFPage(fUrl, referer)
    }

    fun getMp4FromOnline(onlineUrl: String): String {
        val noRedirectClient = kwikClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val bypass = appContext?.let { getOrRefreshBypass(onlineUrl, onlineUrl) }

        var currentUrl = onlineUrl
        var finalHtml = ""
        var combinedCookies = bypass?.cookies ?: ""

        repeat(3) {
            val reqHeaders = Headers.Builder().apply {
                add("referer", currentUrl)
                if (combinedCookies.isNotBlank()) add("cookie", combinedCookies)
                bypass?.let { add("User-Agent", it.userAgent) }
            }.build()

            noRedirectClient.newCall(GET(currentUrl, reqHeaders)).execute().use { response ->
                val respCookies = response.extractCookies()
                combinedCookies = if (combinedCookies.isNotBlank()) "$combinedCookies; $respCookies" else respCookies

                if (response.code in 301..308) {
                    val location = response.header("location") ?: return@repeat
                    currentUrl = if (location.startsWith("http")) {
                        location
                    } else {
                        "https://${currentUrl.toHttpUrl().host}$location"
                    }
                } else {
                    finalHtml = response.body.string() ?: ""
                    return@repeat
                }
            }
        }

        val kwikFRegex = Regex("""["']https://kwik\.cx/f/([^"']+)""")
        val kwikId = kwikFRegex.find(finalHtml)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Could not find kwik.cx/f/ in online response")

        val fUrl = "https://kwik.cx/f/$kwikId"

        return getStreamUrlFromFPage(fUrl, onlineUrl)
    }

    private fun getStreamUrlFromFPage(fUrl: String, referer: String): String {
        val noRedirectClient = kwikClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(fUrl, referer)
        var cloudFlareBypassResult: CloudFlareBypassResult? = null

        val match = KWIK_PARAMS_REGEX.find(fContentString)
            ?: throw KwikException.ExtractionException("Could not find decryption parameters in Kwik HTML.")

        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toIntOrNull() ?: 0, v2.toIntOrNull() ?: 0)

        val uri = KWIK_FORM_URL.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream URI.")
        val tok = KWIK_FORM_TOKEN.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream Token.")

        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        val tryLimit = 5

        while (code != 302 && tries < tryLimit) {
            val headersBuilder = Headers.Builder()
                .add("referer", fContentUrl)
                .add("cookie", fContentCookies)

            cloudFlareBypassResult?.let { headersBuilder.add("User-Agent", it.userAgent) }

            noRedirectClient.newCall(
                POST(uri, headersBuilder.build(), FormBody.Builder().add("_token", tok).build()),
            ).execute().use { response ->
                code = response.code
                kwikLocation = response.header("location")
            }

            if ((code == 403 || code == 419) && cloudFlareBypassResult == null) {
                cloudFlareBypassResult = getOrRefreshBypass(fUrl, referer)
                    ?: throw KwikException.CloudflareBlockedException("Cloudflare bypass failed to return result.")

                fContentCookies = "$fContentCookies; ${cloudFlareBypassResult.cookies}"
                tries = 0
            }
            tries++
        }

        return kwikLocation ?: throw KwikException.ExtractionException("Failed to extract stream URI after $tries attempts.")
    }

    // ========================= Shared Utilities =========================

    private fun fetchKwikHtml(fUrl: String, referer: String): KwikContent {
        val initialResponse = kwikClient.newCall(
            GET(fUrl, Headers.headersOf("referer", referer)),
        ).execute()

        val (html, cookies, finalUrl) = initialResponse.use { resp ->
            Triple(resp.body.string(), resp.extractCookies(), resp.request.url.toString())
        }

        if (html.contains("eval(function(")) {
            return KwikContent(cookies, html, finalUrl)
        }

        getOrRefreshBypass(fUrl, referer)?.let { cfResult ->
            val bypassHeaders = Headers.Builder()
                .add("referer", referer)
                .add("cookie", cfResult.cookies)
                .add("User-Agent", cfResult.userAgent)
                .build()

            kwikClient.newCall(GET(fUrl, bypassHeaders)).execute().use { resp ->
                val bypassHtml = resp.body.string()
                val bypassCookies = resp.extractCookies()

                if (bypassHtml.contains("eval(function(")) {
                    return KwikContent("$bypassCookies; ${cfResult.cookies}", bypassHtml, resp.request.url.toString())
                }
            }
        }

        throw KwikException.CloudflareBlockedException("Cloudflare challenge not solved.")
    }

    private fun getOrRefreshBypass(url: String, referer: String): CloudFlareBypassResult? {
        val host = url.toHttpUrl().host

        synchronized(host) {
            bypassCache[host]?.let { (result, time) ->
                if (System.currentTimeMillis() - time < bypassCacheTtl) return result
            }

            val result = appContext?.let { ctx ->
                CloudflareBypass(ctx).getCookies(url, referer)
            }

            if (result != null) {
                bypassCache[host] = result to System.currentTimeMillis()
            } else {
                bypassCache.remove(host)
            }

            return result
        }
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

    private fun Response.extractCookies(): String = headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1
            val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
            sb.append(decodedChar)
        }

        return sb.toString()
    }
}
