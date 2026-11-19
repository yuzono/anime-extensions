package eu.kanade.tachiyomi.animeextension.en.hentaitv

import android.annotation.SuppressLint
import android.app.Application
import android.content.res.Resources
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * nhplayer.com, the only player hentai.tv embeds.
 *
 * The embed page (`/v/<id>/`) lists the servers. A `player.php` server only reveals its signed
 * mp4 link after an anti-bot challenge, which [handshake] answers natively (no WebView, ~1 s):
 *  1. `player.php` hides four 8-char parts and a timestamp in randomly named DOM elements
 *     (plus decoys) and loads a per-request `player-core-v2.php` script.
 *  2. That script names the real elements/attributes and holds two server tokens (`sc`, `rid`).
 *  3. Proof of work: the smallest n so that sha256(p1+p2+p3+p4+ts+hex(n)) starts with a zero byte.
 *  4. After at least 700 ms on the page, `get-video-url-v2.php` returns `{"url": ...}`, a
 *     Cloudflare timed-HMAC link (`?verify=<ts>-<mac>`) that stays valid for 300 seconds.
 *
 * If the site changes the challenge, [webViewResolve] lets the site's own script solve it in a
 * WebView and captures the answer from the network layer (no JavaScript bridge, which the site detects).
 */
class NhPlayerExtractor(
    private val client: OkHttpClient,
    /** Same client without the Cloudflare interceptor, for calls whose 403 is a plain answer. */
    private val apiClient: OkHttpClient,
    private val headers: Headers,
) {

    private val context: Application by injectLazy()

    class Server(val name: String, val dataId: String)

    // ============================== Embed ===============================

    suspend fun servers(embedUrl: String): List<Server> {
        val embedHeaders = headers.newBuilder()
            .set("Referer", "$SITE_BASE/")
            .set("Accept", HTML_ACCEPT)
            .set("Sec-Fetch-Dest", "iframe")
            .build()
        val doc = client.get(embedUrl, embedHeaders, CacheControl.FORCE_NETWORK).useAsJsoup()
        val servers = doc.select(".servers li[data-id], [data-id*='player.php']")
            .mapNotNull { element ->
                val dataId = element.attr("data-id").trim().ifEmpty { return@mapNotNull null }
                Server(element.text().trim(), dataId)
            }
            .distinctBy { it.dataId }
            .mapIndexed { i, server -> if (server.name.isEmpty()) Server("Server ${i + 1}", server.dataId) else server }
        if (servers.isNotEmpty()) return servers

        // Some embeds put the player straight into the page.
        return listOfNotNull(
            doc.selectFirst("iframe[src]")?.absUrl("src")?.ifEmpty { null }?.let { Server("Main Player", it) },
            doc.selectFirst("video source[src], video[src]")?.absUrl("src")?.ifEmpty { null }?.let { Server("Main Player", it) },
        ).take(1)
    }

    fun absolute(dataId: String): String = when {
        dataId.startsWith("http") -> dataId
        dataId.startsWith("//") -> "https:$dataId"
        else -> "$NH_BASE/${dataId.removePrefix("/")}"
    }

    /** English subtitles, base64-encoded in the `s` parameter of the player URL. */
    fun subtitleOf(playerUrl: String): String? = runCatching {
        val encoded = playerUrl.toHttpUrlOrNull()?.queryParameter("s")?.ifBlank { null } ?: return null
        String(Base64.decode(encoded, Base64.DEFAULT)).trim().takeIf { it.startsWith("http") }
    }.getOrNull()

    // ============================= Handshake ============================

    suspend fun handshake(embedUrl: String, playerUrl: String): String {
        val startedAt = System.currentTimeMillis()
        val pageHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("Accept", HTML_ACCEPT)
            .set("Sec-Fetch-Dest", "iframe")
            .build()
        val html = client.get(playerUrl, pageHeaders, CacheControl.FORCE_NETWORK).bodyString()
        val doc = Jsoup.parse(html, playerUrl)

        val pvBlock = PV_REGEX.find(html)?.groupValues?.get(1) ?: throw Exception("nhplayer: player data not found")
        val pv = PV_ENTRY_REGEX.findAll(pvBlock).associate { it.groupValues[1] to it.groupValues[2] }
        val vid = pv["vid"]?.ifEmpty { null } ?: throw Exception("nhplayer: video token not found")

        val coreUrl = doc.selectFirst("script[src*=player-core]")?.absUrl("src")?.ifEmpty { null }
            ?: CORE_REGEX.find(html)?.groupValues?.get(1)?.let { absolute(it) }
            ?: throw Exception("nhplayer: challenge script not found")
        val scriptHeaders = headers.newBuilder()
            .set("Referer", playerUrl)
            .set("Accept", "*/*")
            .set("Sec-Fetch-Dest", "script")
            .build()
        val js = client.get(coreUrl, scriptHeaders, CacheControl.FORCE_NETWORK).bodyString()

        val challenge = Challenge.parse(js)
        val parts = challenge.readParts(doc)
        val pow = solveProofOfWork(parts.p1 + parts.p2 + parts.p3 + parts.p4 + parts.ts)

        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < MIN_DWELL_MS) delay(MIN_DWELL_MS - elapsed)

        val url = "$NH_BASE/get-video-url-v2.php".toHttpUrl().newBuilder()
            .addQueryParameter("vid", vid)
            .addQueryParameter("c", pv["ct"].orEmpty())
            .addQueryParameter("p1", parts.p1)
            .addQueryParameter("p2", parts.p2)
            .addQueryParameter("p3", parts.p3)
            .addQueryParameter("p4", parts.p4)
            .addQueryParameter("t", parts.ts)
            .addQueryParameter("sc", challenge.sc)
            .addQueryParameter("rid", challenge.rid)
            .addQueryParameter("fp", fingerprint(System.currentTimeMillis() - startedAt))
            .addQueryParameter("df", "")
            .addQueryParameter("pow", pow)
            .addQueryParameter("pid", pv["pid"].orEmpty())
            .addQueryParameter("st", pv["st"].orEmpty())
            .build()
        val xhrHeaders = headers.newBuilder()
            .set("Referer", playerUrl)
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
        val response = apiClient.get(url, xhrHeaders, CacheControl.FORCE_NETWORK, ensureSuccess = false)
        val code = response.code
        val body = response.bodyString()
        val result = runCatching { body.parseAs<VideoUrlDto>() }.getOrNull()
        return result?.url?.takeIf { it.startsWith("http") }
            ?: throw Exception("nhplayer: ${result?.error ?: "HTTP $code"}")
    }

    private class Parts(val p1: String, val p2: String, val p3: String, val p4: String, val ts: String)

    /** Where `player-core-v2.php` reads each challenge part from. */
    private class Challenge(
        val sc: String,
        val rid: String,
        private val ids: Map<Int, String>,
        private val p1Attr: String,
        private val p3Attr: String,
        private val tsAttr: String,
    ) {
        fun readParts(doc: Document): Parts {
            fun element(n: Int) = ids[n]?.let { doc.getElementById(it) } ?: throw Exception("nhplayer: challenge element $n missing")
            val parts = Parts(
                p1 = element(1).attr(p1Attr),
                p2 = element(2).attr("value"),
                p3 = element(3).attr(p3Attr),
                p4 = element(4).text().trim().ifEmpty { element(4).wholeText().trim() },
                ts = element(5).attr(tsAttr),
            )
            if (listOf(parts.p1, parts.p2, parts.p3, parts.p4).any { it.length != 8 }) {
                throw Exception("nhplayer: unexpected challenge format")
            }
            return parts
        }

        companion object {
            fun parse(js: String): Challenge {
                fun stringVar(name: String?): String? = name?.let {
                    Regex("""var ${Regex.escape(it)}\s*=\s*'([^']*)'""").find(js)?.groupValues?.get(1)
                }
                val strings = STRING_VAR_REGEX.findAll(js).map { it.groupValues[1] }.toList()
                val sc = stringVar(SC_REGEX.find(js)?.groupValues?.get(1)) ?: strings.getOrNull(0)
                    ?: throw Exception("nhplayer: challenge token missing")
                val rid = stringVar(RID_REGEX.find(js)?.groupValues?.get(1))
                    ?: PC_ID_REGEX.find(js)?.groupValues?.get(1)
                    ?: strings.getOrNull(1)
                    ?: throw Exception("nhplayer: request id missing")

                val functionIds = ELEMENT_FN_REGEX.findAll(js).associate { it.groupValues[1] to it.groupValues[2] }
                val ordered = ELEMENT_ORDER_REGEX.findAll(js)
                    .mapNotNull { m -> functionIds[m.groupValues[2]]?.let { m.groupValues[1].toInt() to it } }
                    .toMap()
                val positional = functionIds.values.toList().ifEmpty {
                    GET_BY_ID_REGEX.findAll(js).map { it.groupValues[1] }.toList()
                }
                val ids = (1..5).mapNotNull { n -> (ordered[n] ?: positional.getOrNull(n - 1))?.let { n to it } }.toMap()

                val attrs = GET_ATTR_REGEX.findAll(js).map { it.groupValues[1] }.toList()
                fun attrOf(part: String, element: Int, fallback: Int): String = Regex("""$part\s*=\s*e$element\.getAttribute\('([^']+)'\)""").find(js)?.groupValues?.get(1)
                    ?: attrs.getOrNull(fallback)
                    ?: throw Exception("nhplayer: challenge attribute missing")

                return Challenge(sc, rid, ids, attrOf("p1", 1, 0), attrOf("p3", 3, 1), attrOf("ts", 5, 2))
            }
        }
    }

    private fun solveProofOfWork(challenge: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (n in 0 until 10_000_000) {
            val candidate = Integer.toHexString(n)
            if (digest.digest((challenge + candidate).toByteArray()).first() == 0.toByte()) return candidate
        }
        throw Exception("nhplayer: proof of work failed")
    }

    /** Same shape the player script sends, filled with this device's real screen/locale values. */
    private fun fingerprint(elapsedMs: Long): String {
        val metrics = Resources.getSystem().displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 2f
        val width = (metrics.widthPixels / density).roundToInt().takeIf { it > 0 } ?: 412
        val height = (metrics.heightPixels / density).roundToInt().takeIf { it > 0 } ?: 915
        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag()
        val timezoneOffset = -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val json = buildJsonObject {
            put("t", elapsedMs)
            for (key in listOf("mm", "tm", "cl", "kp", "sc")) put(key, JsonArray(emptyList()))
            put("i", 0)
            put("mc", 0)
            put("tc", 0)
            put("cc", 0)
            put("kc", 0)
            putJsonObject("b") {
                put("sw", width)
                put("sh", height)
                put("aw", width)
                put("ah", height)
                put("cd", 24)
                put("pd", 24)
                put("tz", timezoneOffset)
                put("hc", Runtime.getRuntime().availableProcessors())
                put("dm", 8)
                put("pl", "Linux armv81")
                put("lang", languageTag)
                put("langs", listOf(languageTag, locale.language).distinct().joinToString(","))
                put("dpr", density)
                put("ww", width)
                put("wh", (width * 9 / 16))
                put("touch", true)
                put("pdf", false)
                put("fonts", 0)
            }
        }
        return Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    // ========================== WebView fallback ========================

    /**
     * Loads `player.php` in a WebView so the site's own script solves the challenge. The answer is
     * read in [WebViewClient.shouldInterceptRequest]: the call is executed with OkHttp (sharing the
     * WebView's cookies) and handed back to the page unchanged.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun webViewResolve(embedUrl: String, playerUrl: String): String {
        val result = CompletableDeferred<String>()
        var webView: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                webView = WebView(context).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = true
                        headers["User-Agent"]?.let { userAgentString = it }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (!url.contains("get-video-url")) return null
                            return runCatching { forward(url, request.requestHeaders, playerUrl, result) }.getOrNull()
                        }
                    }
                    loadUrl(playerUrl, mapOf("Referer" to embedUrl))
                }
            }
            return withTimeout(WEBVIEW_TIMEOUT_MS) { result.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                webView?.run {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    private fun forward(
        url: String,
        requestHeaders: Map<String, String>,
        playerUrl: String,
        result: CompletableDeferred<String>,
    ): WebResourceResponse {
        val builder = Headers.Builder()
        requestHeaders.forEach { (name, value) -> runCatching { builder.set(name, value) } }
        headers["User-Agent"]?.let { builder.set("User-Agent", it) }
        if (builder["Referer"] == null) builder.set("Referer", playerUrl)
        val request = Request.Builder().url(url).headers(builder.build()).cacheControl(CacheControl.FORCE_NETWORK).build()
        apiClient.newCall(request).execute().use { response ->
            val bytes = response.body.bytes()
            runCatching { String(bytes).parseAs<VideoUrlDto>().url }.getOrNull()
                ?.takeIf { it.startsWith("http") }
                ?.let { result.complete(it) }
            return WebResourceResponse(
                "application/json",
                "utf-8",
                response.code,
                response.message.ifBlank { "OK" },
                mapOf("Access-Control-Allow-Origin" to NH_BASE),
                ByteArrayInputStream(bytes),
            )
        }
    }

    companion object {
        const val NH_BASE = "https://nhplayer.com"
        private const val SITE_BASE = "https://hentai.tv"
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        /** The server refuses answers sooner than 700 ms after the page load. */
        private const val MIN_DWELL_MS = 800L
        private const val WEBVIEW_TIMEOUT_MS = 20_000L

        private val PV_REGEX = Regex("""window\._pV\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
        private val PV_ENTRY_REGEX = Regex("""(\w+)\s*:\s*["']([^"']*)["']""")
        private val CORE_REGEX = Regex("""src=["']([^"']*player-core[^"']*)["']""")
        private val STRING_VAR_REGEX = Regex("""var _\w+\s*=\s*'([^']*)'\s*;""")
        private val SC_REGEX = Regex("""\bsc\s*:\s*(_\w+)\s*[,}\n]""")
        private val RID_REGEX = Regex("""\brid\s*:\s*(_\w+)\s*[,}\n]""")
        private val PC_ID_REGEX = Regex("""_pC\s*=\s*\{[^}]*\bid\s*:\s*'([^']+)'""")
        private val ELEMENT_FN_REGEX = Regex("""var (_\w+)\s*=\s*function\(\)\s*\{\s*return d\.getElementById\('([^']+)'\)""")
        private val ELEMENT_ORDER_REGEX = Regex("""\be([1-5])\s*=\s*(_\w+)\(\)""")
        private val GET_BY_ID_REGEX = Regex("""getElementById\('([^']+)'\)""")
        private val GET_ATTR_REGEX = Regex("""getAttribute\('([^']+)'\)""")
    }
}
