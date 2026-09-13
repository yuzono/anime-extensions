package eu.kanade.tachiyomi.animeextension.zh.oletv

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OLeWebView(private val headers: Headers) {
    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var cachedSignature: String? = null

    @Volatile private var signatureTime = 0L

    fun signature(): String {
        cachedSignature?.takeIf { System.currentTimeMillis() - signatureTime < SIGNATURE_TTL }?.let { return it }
        return intercept("https://www.olevod.tv/") { request ->
            if (request.url.host == "api.olelive.com") request.url.getQueryParameter("_vv") else null
        }.also {
            require(it.isNotBlank()) { "无法生成 OLeTV API 签名，请确认 Android System WebView 可用" }
            cachedSignature = it
            signatureTime = System.currentTimeMillis()
        }
    }

    fun hls(playerUrl: String): String = intercept(playerUrl) { request ->
        request.url.toString().takeIf { ".m3u8" in it }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun intercept(url: String, matcher: (WebResourceRequest) -> String?): String {
        val latch = CountDownLatch(1)
        var result = ""
        var webView: WebView? = null

        handler.post {
            val view = WebView(context)
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.databaseEnabled = true
            view.settings.mediaPlaybackRequiresUserGesture = false
            view.settings.userAgentString = headers["User-Agent"]
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (result.isEmpty()) {
                        matcher(request)?.let {
                            result = it
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            view.loadUrl(url, mapOf("Referer" to "https://www.olevod.tv/"))
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }
        return result
    }

    companion object {
        private const val TIMEOUT_SECONDS = 25L
        private const val SIGNATURE_TTL = 20_000L
    }
}
