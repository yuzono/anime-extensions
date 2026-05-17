package eu.kanade.tachiyomi.multisrc.anikototheme

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme.Companion.UA_MOBILE
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
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = UA_MOBILE
                }
                val defaultUserAgent = webView?.settings?.userAgentString ?: UA_MOBILE

                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        pollForClearance(pageUrl, defaultUserAgent, cancelled) { bypassResult ->
                            if (cancelled.compareAndSet(false, true)) {
                                result = bypassResult
                                latch.countDown()
                            }
                        }
                    }
                }

                CookieManager.getInstance().setCookie(pageUrl, "")
                webView?.loadUrl(pageUrl)
            } catch (e: Exception) {
                Log.e("AnikotoTheme-CF", "WebView failed: ${e.message}")
                if (cancelled.compareAndSet(false, true)) latch.countDown()
            }
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            return null
        } finally {
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
        cancelled: AtomicBoolean,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val maxDurationMs = 30_000L
        val pollIntervalMs = 500L

        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (cancelled.get()) return
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
            },
            pollIntervalMs,
        )
    }

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
