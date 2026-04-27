package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// WebView-based signature extraction for hanime.tv
// ---------------------------------------------------------------------------
// Loads hanime.tv in a headless WebView, waits for the WASM signature
// generator to initialise, and extracts `window.ssignature` and
// `window.stime` via [WebView.evaluateJavascript]. If the WASM module
// has not yet produced a signature, the 'e' DOM event is dispatched to
// trigger the generation pipeline, and extraction is retried until a
// timeout is reached.
//
// Follows the same architecture as CloudflareInterceptor and
// VidGuardExtractor: Application context via Injekt, WebView created on
// the main thread via Handler(Looper.getMainLooper()), CountDownLatch
// for thread synchronisation, and @JavascriptInterface for JS→Kotlin
// callbacks.
// ---------------------------------------------------------------------------

/**
 * Extracts hanime.tv request signatures by loading the site in a WebView
 * and reading the `window.ssignature` / `window.stime` values that the
 * client-side WASM binary produces.
 *
 * Flow:
 * 1. Create a [WebView] on the main thread via [Handler].
 * 2. Navigate to `https://hanime.tv`.
 * 3. On [WebViewClient.onPageFinished], wait for WASM initialisation.
 * 4. Poll `window.ssignature` / `window.stime` via [evaluateJavascript].
 * 5. If not yet available, dispatch the `'e'` event to trigger WASM
 *    signature generation.
 * 6. Deliver the [Signature] through the [JavascriptInterface] callback.
 * 7. Destroy the WebView on the main thread.
 */
class WebViewSignatureProvider : SignatureProvider {

    override val name: String = "WebView"

    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())

    /** Active WebView reference for [close] cleanup. */
    @Volatile
    private var activeWebView: WebView? = null

    // ──────────────────────────────────────────────────────────────────
    // SignatureProvider — public API
    // ──────────────────────────────────────────────────────────────────

    override suspend fun getSignature(): Signature = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
        Log.d(TAG, "getSignature: entry — timeout = ${TOTAL_TIMEOUT_MS}ms")
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null

            handler.post {
                try {
                    Log.d(TAG, "getSignature: creating WebView on main thread")
                    val wv = createWebView()
                    webView = wv
                    activeWebView = wv
                    configureWebView(wv, continuation)
                    Log.d(TAG, "getSignature: loading URL https://hanime.tv")
                    wv.loadUrl("https://hanime.tv")
                } catch (e: Exception) {
                    Log.e(TAG, "getSignature: failed to create WebView — ${e.javaClass.simpleName}: ${e.message}")
                    continuation.resumeWithException(
                        SignatureException("Failed to create WebView: ${e.message}", e),
                    )
                }
            }

            continuation.invokeOnCancellation {
                handler.post {
                    webView?.destroy()
                    if (activeWebView === webView) activeWebView = null
                }
            }
        }
    } ?: throw SignatureException("WebView signature extraction timed out after ${TOTAL_TIMEOUT_MS}ms")

    override fun close() {
        val wv = activeWebView
        Log.d(TAG, "close: called — activeWebView present = ${wv != null}")
        if (wv != null) {
            handler.post {
                wv.destroy()
                if (activeWebView === wv) activeWebView = null
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // WebView creation and configuration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a new [WebView] with JavaScript and DOM storage enabled.
     *
     * The caller is responsible for setting a [WebViewClient] and loading
     * a URL — this method only configures the [WebSettings].
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context)
        Log.d(TAG, "createWebView: WebView instance created")

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = USER_AGENT
        }
        Log.d(TAG, "createWebView: JS enabled, UA = ${USER_AGENT.take(30)}…")

        return wv
    }

    /**
     * Attaches a [WebViewClient] and [JavascriptInterface] to [webView],
     * then starts the signature extraction pipeline once the page loads.
     */
    private fun configureWebView(
        webView: WebView,
        continuation: CancellableContinuation<Signature>,
    ) {
        val jsInterface = SignatureJsInterface()
        webView.addJavascriptInterface(jsInterface, JS_INTERFACE_NAME)
        Log.d(TAG, "configureWebView: JS interface '$JS_INTERFACE_NAME' added")

        // CancellableContinuation lacks an isResumed property, so we track
        // it ourselves to guard against double-resume (which throws
        // IllegalStateException).
        var resumed = false

        fun isResumable() = !continuation.isCancelled && !resumed

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "configureWebView: onPageFinished — url: $url")

                // Give the WASM module time to initialise before polling
                Log.d(TAG, "configureWebView: scheduling first poll after ${WASM_INIT_DELAY_MS}ms WASM init delay")
                handler.postDelayed(
                    { pollForSignature(webView, jsInterface, continuation) },
                    WASM_INIT_DELAY_MS,
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                sslHandler: SslErrorHandler?,
                error: SslError?,
            ) {
                Log.e(TAG, "configureWebView: SSL error — ${error?.toString() ?: "unknown"}")
                sslHandler?.cancel()
                if (isResumable()) {
                    resumed = true
                    resumeOrDestroy(webView, continuation) {
                        continuation.resumeWithException(
                            SignatureException("SSL error loading hanime.tv: ${error?.toString() ?: "unknown"}"),
                        )
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "configureWebView: page load error — ${error?.description ?: "unknown"} (errorCode=${error?.errorCode}), isForMainFrame=${request?.isForMainFrame}")
                // Only fast-fail for main frame requests — subresource errors are non-fatal
                if (request?.isForMainFrame == true && isResumable()) {
                    resumed = true
                    resumeOrDestroy(webView, continuation) {
                        continuation.resumeWithException(
                            SignatureException(
                                "Page load failed: ${error?.description ?: "unknown error"} " +
                                    "(errorCode=${error?.errorCode})",
                            ),
                        )
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Signature polling
    // ──────────────────────────────────────────────────────────────────

    /**
     * Repeatedly checks whether `window.ssignature` and `window.stime`
     * have been set by the WASM module. If the values are not yet
     * available, dispatches the `'e'` event to trigger signature
     * generation and retries after a short delay.
     */
    private fun pollForSignature(
        webView: WebView,
        jsInterface: SignatureJsInterface,
        continuation: CancellableContinuation<Signature>,
    ) {
        val deadline = System.currentTimeMillis() + SIGNATURE_POLL_TIMEOUT_MS
        Log.d(TAG, "pollForSignature: starting — deadline in ${SIGNATURE_POLL_TIMEOUT_MS}ms")

        fun poll() {
            if (continuation.isCancelled) return
            val now = System.currentTimeMillis()
            if (now > deadline) {
                Log.w(TAG, "pollForSignature: timeout reached — ${SIGNATURE_POLL_TIMEOUT_MS}ms elapsed, no signature")
                resumeOrDestroy(
                    webView,
                    continuation,
                ) {
                    continuation.resumeWithException(
                        SignatureException("Signature not available after ${SIGNATURE_POLL_TIMEOUT_MS}ms of polling"),
                    )
                }
                return
            }

            // Check result from any PREVIOUS evaluateJavascript call first.
            // evaluateJavascript is asynchronous — the JS callback fires on the
            // next main-thread Looper iteration, so the result of the current
            // call won't be available until after poll() returns.
            val result = jsInterface.getResult()
            if (result != null) {
                Log.d(TAG, "pollForSignature: result obtained from jsInterface — signature = ${result.signature.take(8)}…")
                resumeOrDestroy(webView, continuation) {
                    continuation.resume(result)
                }
                return
            }

            // No result yet — execute the polling script and schedule a
            // follow-up check after POLL_INTERVAL_MS. The script's JS callback
            // will have fired by then.
            Log.d(TAG, "pollForSignature: no result yet — calling evaluateJavascript (time remaining: ${deadline - now}ms)")
            webView.evaluateJavascript(POLL_SCRIPT, null)
            handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
        }

        poll()
    }

    /**
     * Executes [action] to resume the continuation, then destroys the
     * WebView on the main thread to release resources.
     */
    private fun resumeOrDestroy(
        webView: WebView,
        continuation: CancellableContinuation<Signature>,
        action: () -> Unit,
    ) {
        action()
        handler.post {
            webView.destroy()
            if (activeWebView === webView) activeWebView = null
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // JavaScript interface
    // ──────────────────────────────────────────────────────────────────

    /**
     * Receives signature values from the JavaScript environment via
     * [WebView.addJavascriptInterface].
     *
     * The JS polling script calls [onSignatureReady] when both
     * `window.ssignature` and `window.stime` are available, and
     * [onSignatureNotReady] when they are not yet set.
     */
    inner class SignatureJsInterface {

        @Volatile
        private var signatureResult: Signature? = null

        /** Called from JS when both `window.ssignature` and `window.stime` are set. */
        @JavascriptInterface
        fun onSignatureReady(signature: String, time: String) {
            Log.d(TAG, "SignatureJsInterface: onSignatureReady — signature = ${signature.take(8)}…, time = $time")
            signatureResult = Signature(signature, time)
        }

        /** Called from JS when the signature values are not yet available. */
        @JavascriptInterface
        fun onSignatureNotReady() {
            Log.d(TAG, "SignatureJsInterface: onSignatureNotReady — WASM signature not yet available")
            // No-op — the poll loop will retry after POLL_INTERVAL_MS
        }

        /** Returns the captured signature, or `null` if not yet available. */
        fun getResult(): Signature? = signatureResult
    }

    // ──────────────────────────────────────────────────────────────────
    // Constants and JavaScript scripts
    // ──────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "WebViewSigProvider"

        /** Maximum time to wait for the page to finish loading. */
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L

        /** Maximum time to poll for the signature after the page loads. */
        private const val SIGNATURE_POLL_TIMEOUT_MS = 15_000L

        /** Interval between signature availability checks. */
        private const val POLL_INTERVAL_MS = 500L

        /** Delay after onPageFinished before the first poll — gives WASM time to initialise. */
        private const val WASM_INIT_DELAY_MS = 2_000L

        /** Combined timeout for the entire operation. */
        private const val TOTAL_TIMEOUT_MS = PAGE_LOAD_TIMEOUT_MS + SIGNATURE_POLL_TIMEOUT_MS

        /** Name exposed to JavaScript via `addJavascriptInterface`. */
        private const val JS_INTERFACE_NAME = "AndroidInterface"

        /**
         * User agent string mimicking a recent Chrome on Android device.
         * Must be mobile-class so hanime.tv serves the correct WASM payload.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /**
         * JavaScript executed on each poll iteration.
         *
         * 1. If `window.ssignature` and `window.stime` are already set,
         *    delivers them to [SignatureJsInterface.onSignatureReady].
         * 2. If the WASM exports object exists but no signature yet,
         *    dispatches the `'e'` event to trigger generation, then
         *    polls again after a 1-second delay.
         * 3. If WASM has not loaded at all, signals
         *    [SignatureJsInterface.onSignatureNotReady] so the Kotlin
         *    side can retry.
         */
        private val POLL_SCRIPT = """
            (function() {
                if (window.ssignature && window.stime) {
                    $JS_INTERFACE_NAME.onSignatureReady(
                        window.ssignature,
                        window.stime.toString()
                    );
                } else if (window.wasmExports) {
                    window.dispatchEvent(new Event('e'));
                    setTimeout(function() {
                        if (window.ssignature && window.stime) {
                            $JS_INTERFACE_NAME.onSignatureReady(
                                window.ssignature,
                                window.stime.toString()
                            );
                        }
                    }, 1000);
                } else {
                    $JS_INTERFACE_NAME.onSignatureNotReady();
                }
            })();
        """.trimIndent()
    }
}
