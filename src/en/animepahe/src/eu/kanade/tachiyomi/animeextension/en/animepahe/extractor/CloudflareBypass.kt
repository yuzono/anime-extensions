package eu.kanade.tachiyomi.animeextension.en.animepahe.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
        // Only clear cookies for the target domain instead of hardcoding unrelated domains.
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        // We MUST jump to the Main Thread because WebView is UI-bound
        Handler(Looper.getMainLooper()).post {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            val defaultUserAgent = webView.settings.userAgentString ?: ""

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, defaultUserAgent, cancelled) { bypassResult ->
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
            // Signal the polling runnable to stop rescheduling itself.
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post {
                webView?.destroy()
            }
        }

        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        cancelled: AtomicBoolean,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val maxDurationMs = 30_000L // Matches the CountDownLatch timeout
        val pollIntervalMs = 500L

        val runnable = object : Runnable {
            override fun run() {
                // Stop if getCookies has already returned / timed out.
                if (cancelled.get()) return

                // Hard upper bound so we never poll indefinitely.
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= maxDurationMs) return

                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    val finalResult = CloudFlareBypassResult(cookies, userAgent)
                    onComplete(finalResult)
                } else {
                    handler.postDelayed(this, pollIntervalMs)
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * Clear cookies only for the host of the given URL, avoiding disruption
     * to sessions on unrelated domains.
     */
    private fun clearCookiesForUrl(pageUrl: String) {
        val domain = Uri.parse(pageUrl).host ?: return
        val cookieManager = CookieManager.getInstance()

        listOf("https://$domain", "https://www.$domain").forEach { url ->
            cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                val cookieName = cookieStr.substringBefore("=").trim()
                if (cookieName.isNotEmpty()) {
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/; domain=.$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
