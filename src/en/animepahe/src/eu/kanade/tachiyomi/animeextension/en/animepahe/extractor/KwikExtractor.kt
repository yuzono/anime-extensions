package eu.kanade.tachiyomi.animeextension.en.animepahe.extractor

import android.app.Application
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

data class KwikContent(val cookies: String, val html: String, val finalUrl: String)

class KwikExtractor(
    private val client: OkHttpClient,
) {
    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

    // Clone the base client so interceptors, cookie jars, logging, etc. are preserved,
    // and only override redirect behavior.
    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun getHlsVideo(kwikUrl: String, referer: String, quality: String = ""): Video {
        val videoUrl = getHlsStreamUrl(kwikUrl, referer)

        return Video(
            videoUrl,
            quality,
            videoUrl,
            headers = Headers.headersOf("referer", "https://kwik.cx/"),
        )
    }

    fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val eContent = client.newCall(GET(kwikUrl, Headers.headersOf("referer", referer)))
            .execute().asJsoup()
        val script = eContent.selectFirst("script:containsData(eval\\(function)")!!.data().substringAfterLast("eval(function(")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function($script)")
            ?: throw KwikException.ExtractionException("JsUnpacker failed to unpack Kwik script.")
        return unpacked.substringAfter("const source=\\'").substringBefore("\\';")
    }

    fun getStreamVideo(context: Application, paheUrl: String, quality: String = ""): Video {
        val videoUrl = getStreamUrlFromKwik(context, paheUrl)

        return Video(
            videoUrl,
            quality,
            videoUrl,
            headers = Headers.headersOf("referer", "https://kwik.cx/"),
        )
    }

    fun getStreamUrlFromKwik(context: Application, paheUrl: String): String {
        val kwikUrl = noRedirectClient.newCall(GET("$paheUrl/i")).execute().use { response ->
            val location = response.header("location")
                ?: throw KwikException.ExtractionException("Pahe redirect failed: No location header found.")
            "https://" + location.substringAfterLast("https://")
        }

        var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(context, kwikUrl)
        var cloudFlareBypassResult: CloudFlareBypassResult? = null

        val match = kwikParamsRegex.find(fContentString)
            ?: throw KwikException.ExtractionException("Could not find decryption parameters in Kwik HTML.")

        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toIntOrNull() ?: 0, v2.toIntOrNull() ?: 0)

        val uri = kwikDUrl.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream URI.")
        val tok = kwikDToken.find(decrypted)?.groupValues?.get(1)
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

            if (code == 403 || code == 419) {
                cloudFlareBypassResult = CloudflareBypass(context).getCookies(kwikUrl)
                    ?: throw KwikException.CloudflareBlockedException("Cloudflare bypass failed to return result.")

                // Prevent stacking multiple cf_clearance cookies
                val cleanedCookies = fContentCookies.split("; ")
                    .filter { !it.trimStart().startsWith("cf_clearance=") }
                    .joinToString("; ")

                fContentCookies = "$cleanedCookies; ${cloudFlareBypassResult.cookies}"
                tries = 0
            }
            tries++
        }

        return kwikLocation ?: throw KwikException.ExtractionException("Failed to extract stream URI after $tries attempts.")
    }

    private fun fetchKwikHtml(context: Application, kwikUrl: String): KwikContent {
        fun attemptKwikFetch(cfResult: CloudFlareBypassResult?): KwikContent? {
            val headers = Headers.Builder()
                .add("referer", "https://kwik.cx/")
                .apply {
                    if (cfResult != null) {
                        add("cookie", cfResult.cookies)
                        add("User-Agent", cfResult.userAgent)
                    }
                }
                .build()

            // Use the base client directly so all interceptors are preserved.
            return client.newCall(GET(kwikUrl, headers)).execute().use { resp ->
                val html = resp.body.string()
                if (html.contains("eval(function(")) {
                    val respCookies = resp.extractCookies()
                    val finalCookies = listOfNotNull(respCookies.ifBlank { null }, cfResult?.cookies?.ifBlank { null }).joinToString("; ")
                    KwikContent(finalCookies, html, resp.request.url.toString())
                } else {
                    null
                }
            }
        }

        // 1. Try standard fetch without bypass
        attemptKwikFetch(null)?.let { return it }

        // 2. Try Cloudflare Bypass (Always fresh)
        val cfResult = CloudflareBypass(context).getCookies(kwikUrl)
            ?: throw KwikException.CloudflareBlockedException("Bypass returned null result.")

        attemptKwikFetch(cfResult)?.let { return it }

        throw KwikException.CloudflareBlockedException("Cloudflare challenge not solved.")
    }

    private fun Response.extractCookies(): String = headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
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
                val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
                sb.append(decodedChar)
            } catch (_: NumberFormatException) {
                break
            }
        }

        return sb.toString()
    }

    sealed class KwikException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ExtractionException(message: String, cause: Throwable? = null) : KwikException(message, cause)
        class CloudflareBlockedException(message: String) : KwikException(message)
    }
}
