package eu.kanade.tachiyomi.animeextension.en.animetoki

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(private val client: OkHttpClient) {

    private val context: Application by injectLazy()

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(url: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        mainHandler.post {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val cookies = CookieManager.getInstance().getCookie(url)

                        // Check if Cloudflare clearance cookie is present, or if the URL no longer contains challenge markers
                        if (cookies != null && (cookies.contains("cf_clearance") || (url != null && !url.contains("challenge")))) {
                            success = true
                            injectCookies(url, cookies)
                            latch.countDown()
                            view?.destroy()
                        }
                    }
                }
                loadUrl(url)
            }
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            // Timeout reached: destroy the WebView to prevent memory leaks
            mainHandler.post {
                webView?.destroy()
            }
        }

        return success
    }

    private fun injectCookies(url: String?, cookies: String?) {
        if (url == null || cookies == null) return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookieJar = client.cookieJar ?: return

        // Parse the WebView cookies and save them to the OkHttp client
        val cookieList = cookies.split(";").mapNotNull { cookieStr ->
            Cookie.parse(httpUrl, cookieStr.trim())
        }

        cookieJar.saveFromResponse(httpUrl, cookieList)
    }
}
