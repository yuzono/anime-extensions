package eu.kanade.tachiyomi.animeextension.en.mkissa

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Resolves an episode's `sourceUrls` by letting the site's own app request them in a hidden
 * WebView and reading the reply once the app has decrypted it.
 *
 * The streams query is signed and its reply encrypted with material derived from constants
 * spread over the obfuscated bundle, and the site reshuffles those every few days, so a native
 * port keeps breaking. The app itself always matches the current build.
 */
class StreamResolver(
    private val siteUrl: String,
    private val apiHost: String,
    private val userAgent: String?,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val mutex = Mutex()

    suspend fun sourceUrls(episode: EpisodeVariables.Variables): List<SourceUrl> = mutex.withLock {
        val state = LoadState()
        val result = withTimeoutOrNull(TIMEOUT_MS) { loadInWebView(episode, state) }
            ?: Result.failure(state.timeoutError())
        result.getOrThrow().parseAs<List<SourceUrl>>()
    }

    private class LoadState {
        val sawCaptcha = AtomicBoolean(false)
        val apiError = AtomicReference<String?>(null)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS

        fun timeoutError() = IOException(
            when {
                sawCaptcha.get() -> CAPTCHA_MESSAGE
                apiError.get() != null -> "MKissa: ${apiError.get()}"
                else -> TIMEOUT_MESSAGE
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadInWebView(
        episode: EpisodeVariables.Variables,
        state: LoadState,
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        var webView: WebView? = null

        fun finish(result: Result<String>) {
            if (!finished.compareAndSet(false, true)) return
            handler.post { webView?.destroy() }
            cont.resume(result)
        }

        // Third-party frames can see the bridge too, but not this token: it only reaches the
        // top frame, through the script we evaluate there.
        val token = UUID.randomUUID().toString()
        val bridge = Bridge(token) { kind, value ->
            when (kind) {
                Bridge.RESULT -> finish(Result.success(value))
                Bridge.CAPTCHA -> state.sawCaptcha.set(true)
                Bridge.API_ERROR -> if (CAPTCHA_ERROR in value) {
                    state.sawCaptcha.set(true)
                } else if (state.apiError.getAndSet(value) == null) {
                    // The app retries some errors itself, so give it a moment before failing, unless
                    // the retry couldn't land before the timeout anyway.
                    val grace = errorGraceMs(value)
                    if (System.currentTimeMillis() + grace >= state.deadline) {
                        finish(Result.failure(state.timeoutError()))
                    } else {
                        handler.postDelayed({ finish(Result.failure(state.timeoutError())) }, grace)
                    }
                }
            }
        }
        val script = RESOLVE_SCRIPT.replace("__TARGET__", target(episode, token))
        val siteHost = siteUrl.toHttpUrl().host

        handler.post {
            if (finished.get()) return@post
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    userAgent?.let { settings.userAgentString = it }
                    addJavascriptInterface(bridge, BRIDGE_NAME)
                    webViewClient = object : WebViewClient() {
                        // Keeps ad redirects from taking the page off the site.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame && request.url.host != siteHost

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(script, null)
                        }
                    }
                    // Deep links get a Cloudflare challenge the root page doesn't, so start there
                    // and let the app route to the episode itself.
                    loadUrl("$siteUrl/")
                }
            } catch (e: Exception) {
                // Thrown here it would crash the app, e.g. while the WebView provider updates.
                finish(Result.failure(IOException("Could not start a WebView: ${e.message}", e)))
            }
        }

        cont.invokeOnCancellation {
            finished.set(true)
            handler.post { webView?.destroy() }
        }
    }

    // A throttled query says when it may be retried; wait past that before giving up on it.
    private fun errorGraceMs(message: String): Long = RETRY_AFTER_REGEX.find(message)
        ?.groupValues?.get(1)?.toLongOrNull()
        ?.let { (it + 3) * 1000 }
        ?: API_ERROR_GRACE_MS

    private fun target(episode: EpisodeVariables.Variables, token: String): String = mapOf(
        "showId" to episode.showId,
        "episode" to episode.episodeString,
        "path" to "/anime/${episode.showId}/p-${episode.episodeString}-${episode.translationType}",
        "apiHost" to apiHost,
        "token" to token,
    ).toJsonString()

    private class Bridge(
        private val token: String,
        private val onMessage: (kind: String, value: String) -> Unit,
    ) {
        @JavascriptInterface
        fun post(token: String, kind: String, value: String) {
            if (token == this.token) onMessage(kind, value)
        }

        companion object {
            const val RESULT = "result"
            const val CAPTCHA = "captcha"
            const val API_ERROR = "apiError"
        }
    }

    companion object {
        private const val BRIDGE_NAME = "MKissaBridge"
        private const val TIMEOUT_MS = 30_000L
        private const val API_ERROR_GRACE_MS = 5_000L
        private val RETRY_AFTER_REGEX = Regex("""try again in (\d+) seconds""")

        private const val CAPTCHA_ERROR = "NEED_CAPTCHA"

        private const val TIMEOUT_MESSAGE = "Timed out waiting for MKissa to load the episode"
        private const val CAPTCHA_MESSAGE = "MKissa wants a captcha from this network before it " +
            "serves streams. Open the episode in WebView and complete the check, or switch " +
            "networks (e.g. turn a VPN on or off), then try again."

        // Runs on every page load. The episode reply is caught as the app decrypts it through
        // WebCrypto, or as plain JSON if the API ever stops encrypting it. The hook only has to
        // be in place before the app routes to the episode, which it does on our click.
        private val RESOLVE_SCRIPT = """
            (function () {
              // A Cloudflare challenge page reloads into the site once solved; hook that instead.
              if (window.__mkissaHooked || window._cf_chl_opt) return;
              window.__mkissaHooked = true;
              var bridge = window.$BRIDGE_NAME;
              var target = __TARGET__;
              var parse = JSON.parse, stringify = JSON.stringify, done = false;
              function post(kind, value) { bridge.post(target.token, kind, value); }
              function inspect(obj) {
                if (done || !obj || typeof obj !== 'object') return;
                var ep = obj.episode || (obj.data && obj.data.episode);
                if (ep && Array.isArray(ep.sourceUrls) && String(ep.episodeString) === target.episode &&
                    !(ep.show && ep.show._id && ep.show._id !== target.showId)) {
                  done = true;
                  post('${Bridge.RESULT}', stringify(ep.sourceUrls));
                  return;
                }
                (Array.isArray(obj.errors) ? obj.errors : []).forEach(function (e) {
                  if (e && e.message && e.path && e.path[0] === 'episode') post('${Bridge.API_ERROR}', String(e.message));
                });
              }
              function inspectText(text) {
                if (done || typeof text !== 'string' || !/sourceUrls|"errors"/.test(text)) return;
                try { inspect(parse(text)); } catch (e) {}
              }
              var subtle = window.crypto && window.crypto.subtle;
              if (subtle) {
                var decrypt = subtle.decrypt;
                subtle.decrypt = function () {
                  return decrypt.apply(subtle, arguments).then(function (buf) {
                    try { inspectText(new TextDecoder().decode(buf)); } catch (e) {}
                    return buf;
                  });
                };
              }
              var text = Response.prototype.text, json = Response.prototype.json;
              Response.prototype.text = function () {
                return text.call(this).then(function (t) { inspectText(t); return t; });
              };
              Response.prototype.json = function () {
                return json.call(this).then(function (v) { try { inspect(v); } catch (e) {} return v; });
              };
              var captcha = false;
              setInterval(function () {
                if (!captcha && document.querySelector('#captcha-root iframe, #captcha-root [id^="cf-chl-widget"]')) {
                  captcha = true;
                  post('${Bridge.CAPTCHA}', '');
                }
              }, 500);
              if (location.pathname === target.path) return;
              // Clicking before the client router has started falls through to a full page load,
              // which would drop the hook. The app's first API call means the router is up.
              var timer = setInterval(function () {
                var started = performance.getEntriesByType('resource').some(function (e) {
                  return e.name.indexOf(target.apiHost) >= 0;
                });
                if (!started) return;
                clearInterval(timer);
                var a = document.createElement('a');
                a.href = target.path;
                document.body.appendChild(a);
                a.click();
              }, 200);
            })();
        """.trimIndent()
    }
}
