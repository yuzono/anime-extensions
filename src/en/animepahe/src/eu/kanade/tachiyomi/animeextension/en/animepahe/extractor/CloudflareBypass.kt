package eu.kanade.tachiyomi.animeextension.en.animepahe.extractor

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
    fun getCookies(pageUrl: String): CloudFlareBypassResult? {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null

        // We MUST jump to the Main Thread because WebView is UI-bound
        Handler(Looper.getMainLooper()).post {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            val defaultUserAgent = webView.settings.userAgentString

            // Release the background thread
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, defaultUserAgent) { bypassResult ->
                        result = bypassResult
                        latch.countDown()
                    }
                }
            }

            CookieManager.getInstance().setCookie(pageUrl, "")
            webView.loadUrl(pageUrl)
        }

        // Wait here for up to 30 seconds
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
        val startTime = android.os.SystemClock.elapsedRealtime()
        val timeoutMillis = 30_000L

        val runnable = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    // Success: stop polling and return the result
                    handler.removeCallbacks(this)
                    val finalResult = CloudFlareBypassResult(cookies, userAgent)
                    onComplete(finalResult)
                    return
                }

                val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                if (elapsed >= timeoutMillis) {
                    // Timeout: stop polling and return an "empty" result to signal failure
                    handler.removeCallbacks(this)
                    val finalResult = CloudFlareBypassResult("", userAgent)
                    onComplete(finalResult)
                } else {
                    // Retry after 500 ms
                    handler.postDelayed(this, 500)
                }
            }
        }

        handler.post(runnable)
    }
}
