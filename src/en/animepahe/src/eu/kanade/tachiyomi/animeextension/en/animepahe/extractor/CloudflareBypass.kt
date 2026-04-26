package eu.kanade.tachiyomi.animeextension.en.animepahe.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Cannot call getCookies on the Main Thread")
        }

        clearCookiesForDomains("kwik.cx", "pahe.win")

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val defaultUserAgent = settings.userAgentString

                webViewClient = object : WebViewClient() {

                    // Fires on page loads AND after CF challenge completes
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        val cookies = CookieManager.getInstance().getCookie(pageUrl)
                        if (cookies?.contains("cf_clearance=") == true) {
                            result = CloudFlareBypassResult(cookies, defaultUserAgent)
                            latch.countDown()
                        }
                        // If no cf_clearance, do nothing. CF JS is still working.
                        // It will trigger onPageFinished again when it's done.
                    }

                    // Catch network/load errors so we don't wait 30s for nothing
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) {
                            result = CloudFlareBypassResult("", view.settings.userAgentString)
                            latch.countDown()
                        }
                    }
                }
            }
            webView.loadUrl(pageUrl)
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            Handler(Looper.getMainLooper()).post {
                webView?.stopLoading()
                webView?.destroy()
            }
        }

        return result
    }

    private fun clearCookiesForDomains(vararg domains: String) {
        val cookieManager = CookieManager.getInstance()
        for (domain in domains) {
            listOf("https://$domain", "https://www.$domain").forEach { url ->
                cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                    val cookieName = cookieStr.split("=").firstOrNull()?.trim() ?: return@forEach
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/; domain=.$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
