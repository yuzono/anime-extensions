package eu.kanade.tachiyomi.animeextension.es.otakustv

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The watch page (otakustv2.com/ver/…) ships the server list as an encrypted blob that is
 * decoded entirely client-side by a heavily obfuscated script, which then injects one
 * `li[encrypt]` per server (the `encrypt` attribute is the hex-encoded embed URL).
 *
 * Replicating that obfuscated cipher is brittle, so instead we let the page's own JavaScript
 * run inside a WebView and simply harvest the resulting `encrypt` attributes once they appear.
 */
class OtakusTvServerExtractor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun getEmbedUrls(pageUrl: String, requestHeaders: Headers): List<String> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var result = ""

        val headers = requestHeaders.toMultimap()
            .mapValues { it.value.getOrNull(0) ?: "" }
            .toMutableMap()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
            webview.webViewClient = object : WebViewClient() {
                private var finished = false
                private var polling = false
                private var attempts = 0

                override fun onPageFinished(view: WebView, url: String) {
                    // onPageFinished may fire repeatedly (redirects/iframes); poll only once.
                    if (polling) return
                    polling = true
                    poll(view)
                }

                private fun poll(view: WebView) {
                    if (finished) return
                    view.evaluateJavascript(EXTRACT_JS) { value ->
                        val decoded = value
                            ?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            .orEmpty()
                            .trim()
                        if (decoded.isNotBlank() && decoded != "null" && decoded.contains("http")) {
                            result = decoded
                            finished = true
                            latch.countDown()
                        } else if (attempts++ < MAX_ATTEMPTS) {
                            handler.postDelayed({ poll(view) }, POLL_MS)
                        } else {
                            latch.countDown()
                        }
                    }
                }
            }
            webview.loadUrl(pageUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return result.split("|")
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()
    }

    companion object {
        private const val TIMEOUT_SEC = 30L
        private const val POLL_MS = 600L
        private const val MAX_ATTEMPTS = 40

        // Hex-decodes every `encrypt` attribute into its embed URL and joins them with "|".
        private val EXTRACT_JS = """
            (function(){
              function hx(h){h=(h||'').replace(/[^0-9a-fA-F]/g,'');var s='';
                for(var i=0;i+1<h.length;i+=2){s+=String.fromCharCode(parseInt(h.substr(i,2),16));}return s;}
              var els=document.querySelectorAll('[encrypt]');
              var out=[];
              for(var i=0;i<els.length;i++){var u=hx(els[i].getAttribute('encrypt'));if(u.indexOf('http')===0)out.push(u);}
              return out.join('|');
            })();
        """.trimIndent()
    }
}
