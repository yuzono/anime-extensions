package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class CloudFlareBypassResult(
    val cookies: String,
    val userAgent: String,
)

class CloudflareBypass(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    fun getCookies(pageUrl: String, referer: String = ""): CloudFlareBypassResult? {

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            val defaultUserAgent = webView.settings.userAgentString

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, defaultUserAgent) { bypassResult ->
                        result = bypassResult
                        latch.countDown()
                    }
                }
            }

            CookieManager.getInstance().setCookie(pageUrl, "")

            val extraHeaders = mutableMapOf<String, String>()
            if (referer.isNotBlank()) {
                extraHeaders["Referer"] = referer
            }
            webView.loadUrl(pageUrl, extraHeaders)
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            Handler(Looper.getMainLooper()).post {
                webView?.destroy()
            }
        }

        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    val finalResult = CloudFlareBypassResult(cookies, userAgent)
                    onComplete(finalResult)
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(runnable)
    }
}
