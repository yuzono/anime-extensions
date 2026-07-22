package eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

class SendNowExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }

    suspend fun videosFromUrl(url: String, name: String): List<Video> {
        // Use the WebView's native UA so its Chrome version matches the actual engine.
        // Cloudflare Turnstile fails (runs the challenge but never issues a token) when the
        // UA's Chrome version doesn't match the WebView engine version (Mihon #3177).
        // getDefaultUserAgent can throw on devices without the WebView package, so fall back.
        val fallbackUa =
            headers["User-Agent"]
                ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUa
        Log.d(tag, "Using UA: $userAgent")

        val chromeVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "143"
        val isMobile = userAgent.contains("Android") || userAgent.contains("Mobile")

        val secChUa =
            "\"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\", \"Not A(Brand\";v=\"24\""

        val platform = when {
            userAgent.contains("Windows") -> "\"Windows\""
            userAgent.contains("Android") -> "\"Android\""
            userAgent.contains("Mac") -> "\"macOS\""
            userAgent.contains("Linux") -> "\"Linux\""
            else -> "\"Windows\""
        }

        val newHeaders = headers.newBuilder().apply {
            removeAll("Referer")
            set(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            )
            set("Accept-Encoding", "deflate")
            set("accept-language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            set("cache-control", "max-age=600")
            set("Connection", "keep-alive")
            set("Host", url.toHttpUrl().host)
            set("sec-ch-ua", secChUa)
            set("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
            set("sec-ch-ua-platform", platform)
            set("Sec-Fetch-Dest", "document")
            set("Sec-Fetch-Mode", "navigate")
            set("Sec-Fetch-Site", "none")
            set("Sec-Fetch-User", "?1")
            set("Upgrade-Insecure-Requests", "1")
            set("User-Agent", userAgent)
        }.build()

        // Solve Cloudflare Turnstile in a WebView and capture the token + form fields from the
        // same page the WebView loaded (avoids a second OkHttp GET that would re-trigger Cloudflare).
        Log.d(tag, "Opening WebView to solve Cloudflare Turnstile...")
        val result = try {
            withTimeout(45_000) { solveTurnstile(url, userAgent) }
        } catch (_: TimeoutCancellationException) {
            Log.e(tag, "Turnstile solving timed out")
            return emptyList()
        } catch (e: Exception) {
            // Preserve coroutine cancellation; only treat other failures as "no videos".
            if (e is CancellationException) throw e
            Log.e(tag, "Turnstile solving failed", e)
            return emptyList()
        }

        if (result.token.isBlank()) {
            Log.e(tag, "Turnstile token is blank")
            return emptyList()
        }
        Log.d(tag, "Turnstile solved (token length=${result.token.length})")

        val host = url.toHttpUrl().host
        val cookies = webViewCookies("https://$host/")
        Log.d(tag, "Challenge page loaded: id='${result.id}' rand='${result.rand}' refererHost='${result.referer.toHttpUrlOrNull()?.host ?: "unknown"}'")

        val postHeaders = newHeaders.newBuilder().apply {
            set("Origin", "https://$host")
            set("Sec-Fetch-Site", "same-origin")
            if (cookies.isNotBlank()) set("Cookie", cookies)
        }.build()

        val formBody = FormBody.Builder()
            .add("op", "download1")
            .add("id", result.id)
            .add("rand", result.rand)
            .add("referer", result.referer)
            .add("cf-turnstile-response", result.token)
            .add("download_a", "CONTINUE")
            .build()

        val postDoc = client.newCall(POST("https://$host/", postHeaders, formBody))
            .awaitSuccess()
            .useAsJsoup()

        val source = postDoc.selectFirst("source")
        if (source == null) {
            Log.e(tag, "No <source> element in POST response")
            return emptyList()
        }

        val videoUrl = source.attr("src")
        Log.d(tag, "Video source found: ${videoUrl.toHttpUrlOrNull()?.host ?: "unknown"}")

        val videoHeaders = Headers.headersOf("Referer", "https://$host/")

        return listOf(
            Video(videoUrl, name, videoUrl, videoHeaders),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveTurnstile(url: String, userAgent: String): TurnstileResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var injected = false

                val webView = WebView(applicationContext)
                val jsInterface = TurnstileJsInterface { res ->
                    if (continuation.isActive) {
                        handler.post { webView.destroy() }
                        continuation.resume(res)
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent
                // Cloudflare's challenge runs on challenges.cloudflare.com (cross-origin from
                // the target host). Without third-party cookies the challenge state can't be
                // persisted, so Turnstile errors out and never issues a token.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.addJavascriptInterface(jsInterface, "turnstileBridge")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl?.contains("/cdn-cgi/") == true) return
                        if (injected) return
                        injected = true
                        view?.evaluateJavascript(POLL_SCRIPT) {}
                    }
                }
                webView.loadUrl(url)

                continuation.invokeOnCancellation {
                    handler.post { webView.destroy() }
                }
            }
        }
    }

    private suspend fun webViewCookies(url: String): String = withContext(Dispatchers.Main) {
        runCatching {
            CookieManager.getInstance()
                .getCookie(url)
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("; ")
        }.getOrNull().orEmpty()
    }

    private data class TurnstileResult(
        val token: String,
        val id: String,
        val rand: String,
        val referer: String,
    )

    private class TurnstileJsInterface(private val onResult: (TurnstileResult) -> Unit) {
        private var delivered = false

        @JavascriptInterface
        fun onResult(token: String, id: String, rand: String, referer: String) {
            if (delivered) return
            delivered = true
            onResult(TurnstileResult(token, id, rand, referer))
        }
    }

    companion object {
        private val CHROME_REGEX = Regex("""Chrome/(\d+)""")

        private val POLL_SCRIPT = """
            (function() {
                var tries = 0;
                var interval = setInterval(function() {
                    tries++;
                    var input = document.querySelector('input[name="cf-turnstile-response"]');
                    var token = (input && input.value) ? input.value
                        : (document.getElementById('turnstile_callback') ? document.getElementById('turnstile_callback').value : '');
                    if (token) {
                        clearInterval(interval);
                        var idEl = document.querySelector('input[name="id"]');
                        var randEl = document.querySelector('input[name="rand"]');
                        var refEl = document.querySelector('input[name="referer"]');
                        turnstileBridge.onResult(
                            token,
                            idEl ? idEl.value : '',
                            randEl ? randEl.value : '',
                            refEl ? refEl.value : ''
                        );
                    } else if (tries > 120) {
                        clearInterval(interval);
                        turnstileBridge.onResult('', '', '', '');
                    }
                }, 250);
            })();
        """.trimIndent()
    }
}
