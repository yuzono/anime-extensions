package eu.kanade.tachiyomi.animeextension.en.hexawatch

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Provides the Cap.js proof-of-work token that hexa.su requires (`x-cap-token`) for source requests.
 *
 * The challenge is solved by the site's own Cap.js widget running in a real [WebView] on the
 * `hexa.su` origin. The token is cached in memory and only re-solved when it expires or when the
 * API rejects it.
 */
class CapTokenProvider {

    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    @Volatile
    private var token: String? = null

    @Volatile
    private var expiresAt = 0L

    /**
     * Returns a valid token, solving a new challenge only if none is cached.
     */
    suspend fun getToken(): String = mutex.withLock {
        token?.takeIf { System.currentTimeMillis() < expiresAt } ?: solve().also {
            token = it
            expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
        }
    }

    /**
     * Drops [rejected] from the cache so the next [getToken] call solves a new challenge.
     * A token that was already replaced by a concurrent caller is left untouched.
     */
    fun invalidate(rejected: String) {
        if (token == rejected) {
            token = null
            expiresAt = 0L
        }
    }

    private suspend fun solve(): String {
        val result = CompletableDeferred<String>()
        val webViewRef = AtomicReference<WebView?>()

        handler.post {
            try {
                webViewRef.set(createWebView(result))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        try {
            return withTimeoutOrNull(SOLVE_TIMEOUT_MS) { result.await() }
                ?: throw Exception("Timed out solving the HexaWatch captcha")
        } finally {
            handler.post {
                webViewRef.getAndSet(null)?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(result: CompletableDeferred<String>): WebView {
        val webView = WebView(applicationContext)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        // Cap's instrumentation rejects a focused window whose outer size is 0x0, which is what an
        // off-screen WebView reports until it has been laid out.
        val metrics = applicationContext.resources.displayMetrics
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

        webView.addJavascriptInterface(CapBridge(result), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    result.completeExceptionally(Exception("Captcha page failed to load: ${error?.description}"))
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                result.completeExceptionally(Exception("Captcha WebView process crashed"))
                return true
            }
        }

        webView.loadDataWithBaseURL(PAGE_BASE_URL, PAGE_HTML, "text/html", "utf-8", null)
        return webView
    }

    class CapBridge(private val result: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onToken(token: String) {
            result.complete(token)
        }

        @JavascriptInterface
        fun onError(message: String) {
            result.completeExceptionally(Exception("Captcha failed: $message"))
        }
    }

    private companion object {
        const val BRIDGE_NAME = "HexaCap"

        // Same origin the site's widget runs on, so the Cap server sees the requests it expects.
        const val PAGE_BASE_URL = "https://hexa.su/"
        const val CAP_ENDPOINT = "https://cap.hexa.su/15d2cf0395/"
        const val CAP_WIDGET_URL = "https://cdn.jsdelivr.net/npm/@cap.js/widget"

        const val SOLVE_TIMEOUT_MS = 60_000L

        // The site itself discards its token after 3 hours; refresh a bit earlier.
        const val TOKEN_TTL_MS = 170 * 60 * 1000L

        val PAGE_HTML = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body>
            <script>
            (function () {
                function fail(message) {
                    $BRIDGE_NAME.onError(String(message));
                }

                // hexa.su's Cap server blocks any non-Firefox browser reporting no plugins, which is
                // every Android browser. Report a non-empty PluginArray inside the instrumentation
                // iframe, defined on the prototype since it also rejects own properties on navigator.
                var shim = '<script>(function () {' +
                    'if (navigator.plugins.length) return;' +
                    'var plugins = Object.create(PluginArray.prototype);' +
                    'Object.defineProperty(plugins, "length", { value: 5 });' +
                    'Object.defineProperty(Navigator.prototype, "plugins", { get: function () { return plugins; }, configurable: true, enumerable: true });' +
                    '})();<\/script>';
                var srcdoc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'srcdoc');
                Object.defineProperty(HTMLIFrameElement.prototype, 'srcdoc', {
                    configurable: true,
                    enumerable: srcdoc.enumerable,
                    get: function () { return srcdoc.get.call(this); },
                    set: function (value) { srcdoc.set.call(this, String(value).replace('<head>', '<head>' + shim)); },
                });
                var script = document.createElement('script');
                script.src = '$CAP_WIDGET_URL';
                script.onerror = function () { fail('Failed to load the Cap widget'); };
                script.onload = function () {
                    try {
                        var widget = document.createElement('cap-widget');
                        widget.setAttribute('data-cap-api-endpoint', '$CAP_ENDPOINT');
                        widget.setAttribute('data-cap-disable-haptics', '');
                        widget.addEventListener('solve', function (event) {
                            if (event.detail && event.detail.token) {
                                $BRIDGE_NAME.onToken(event.detail.token);
                            }
                        });
                        widget.addEventListener('error', function (event) {
                            var detail = event.detail || {};
                            fail(detail.code ? detail.code + ': ' + detail.message : detail.message || 'Unknown error');
                        });
                        document.body.appendChild(widget);
                        widget.solve().then(function (solution) {
                            if (solution && solution.token) {
                                $BRIDGE_NAME.onToken(solution.token);
                            }
                        }, function (error) {
                            fail(error && error.message ? error.message : error);
                        });
                    } catch (e) {
                        fail(e && e.message ? e.message : e);
                    }
                };
                document.head.appendChild(script);
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
