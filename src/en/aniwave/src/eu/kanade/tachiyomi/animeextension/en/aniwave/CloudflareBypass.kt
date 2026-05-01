package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CloudFlareBypassResult(
    val cookies: String,
    val userAgent: String,
)

class CloudflareBypass(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    fun getCookies(pageUrl: String): CloudFlareBypassResult? {
        if (!pageUrl.startsWith("http")) return null

        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        val completed = AtomicBoolean(false)
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
                val defaultUserAgent = webView.settings.userAgentString

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        pollForClearance(pageUrl, defaultUserAgent, completed) { bypassResult ->
                            if (completed.compareAndSet(false, true)) {
                                result = bypassResult
                                latch.countDown()
                            }
                        }
                    }
                }
                webView.loadUrl(pageUrl)
            } catch (e: Exception) {
                Log.e("AniWave-CF", "WebView failed: ${e.message}")
                if (completed.compareAndSet(false, true)) latch.countDown()
            }
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            return null
        } finally {
            // Destroy WebView securely on the main thread to prevent memory leaks
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
            }, 1000)
        }
        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        completed: AtomicBoolean,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()

        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (completed.get() || System.currentTimeMillis() - startTime > 25_000L) return
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies?.contains("cf_clearance=") == true) {
                        onComplete(CloudFlareBypassResult(cookies, userAgent))
                    } else {
                        handler.postDelayed(this, 500)
                    }
                }
            },
            500,
        )
    }
}
