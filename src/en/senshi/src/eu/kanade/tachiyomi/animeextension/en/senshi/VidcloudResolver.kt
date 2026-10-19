package eu.kanade.tachiyomi.animeextension.en.senshi

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Resolves vidcloud sources by running the site's own player runtime
 * (`window.__oct` from vendor.js) in a hidden WebView on the Senshi origin.
 *
 * `__oct.open(remote_source_id)` does an ECDH/HKDF/AES-GCM handshake with
 * s.vidcloud.se (`GET /i/…` PNG bootstrap, `POST /q7m4x9`) whose constants and
 * record layout rotate with each vendor build, so a native port goes stale within
 * days. The runtime always matches the current build, and a WebView clears
 * Cloudflare on s.vidcloud.se like a regular browser.
 */
class VidcloudResolver(
    private val baseUrl: () -> String,
    private val userAgent: String?,
    /** Clears a Cloudflare challenge on the vidcloud host before a retry. */
    private val warmUp: suspend () -> Unit,
) {
    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val cache = LruCache<Long, CachedEntries>(16)

    private class CachedEntries(val entries: List<VidcloudEntryDto>, val createdAt: Long)

    /** Dub and HardSub hosters share one source id, so the second call is served from cache. */
    suspend fun resolve(videoId: Long): List<VidcloudEntryDto> = mutex.withLock {
        cache.get(videoId)
            ?.takeIf { System.currentTimeMillis() - it.createdAt < CACHE_TTL_MS }
            ?.let { return@withLock it.entries }

        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) {
                try {
                    warmUp()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            val result = withTimeoutOrNull(TIMEOUT_MS) { openInWebView(videoId) }
                ?: Result.failure(IOException("timed out after ${TIMEOUT_MS / 1000}s"))
            result.onSuccess { json ->
                val entries = json.parseAs<List<VidcloudEntryDto>>()
                cache.put(videoId, CachedEntries(entries, System.currentTimeMillis()))
                return@withLock entries
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "open($videoId) attempt ${attempt + 1} failed: ${lastError?.message}")
        }
        throw IOException("Could not resolve stream: ${lastError?.message}", lastError)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun openInWebView(videoId: Long): Result<String> = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        var webView: WebView? = null

        fun finish(result: Result<String>) {
            if (!finished.compareAndSet(false, true)) return
            handler.post { webView?.destroy() }
            cont.resume(result)
        }

        handler.post {
            if (finished.get()) return@post
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                userAgent?.let { settings.userAgentString = it }
                addJavascriptInterface(Bridge(::finish), BRIDGE_NAME)
                loadDataWithBaseURL("${baseUrl()}/", buildPage(videoId), "text/html", "UTF-8", null)
            }
        }

        cont.invokeOnCancellation {
            finished.set(true)
            handler.post { webView?.destroy() }
        }
    }

    private class Bridge(private val finish: (Result<String>) -> Unit) {
        @JavascriptInterface
        fun onResult(json: String) = finish(Result.success(json))

        @JavascriptInterface
        fun onError(message: String) = finish(Result.failure(IOException(message)))
    }

    private fun buildPage(videoId: Long): String {
        // Same order as the site: vidcloud CDN first, the site's own copy as fallback.
        val scripts = listOf(VENDOR_CDN_URL, "${baseUrl()}/assets/vendor.js")
            .joinToString(",", "[", "]") { "\"$it\"" }
        val script = RESOLVE_SCRIPT
            .replace("__SCRIPTS__", scripts)
            .replace("__VIDEO_ID__", videoId.toString())
        return "<!doctype html><html><head><script>$script</script></head><body></body></html>"
    }

    companion object {
        private const val TAG = "VidcloudResolver"
        private const val BRIDGE_NAME = "SenshiBridge"
        private const val VENDOR_CDN_URL = "https://cdn.vidcloud.se/vjs/vendor.js"
        private const val ATTEMPTS = 2
        private const val TIMEOUT_MS = 30_000L
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        // Normalizes `open()`'s shapes (source as object, string or a sub/dub/both
        // array) to VidcloudEntryDto. Retries open() itself: the bootstrap/authorize
        // round trip occasionally fails on the first try.
        private val RESOLVE_SCRIPT = """
            (function () {
              var bridge = window.SenshiBridge;
              var scripts = __SCRIPTS__;
              function normalize(entries) {
                return (Array.isArray(entries) ? entries : [entries]).map(function (e) {
                  var s = e.source, sources;
                  if (Array.isArray(s)) sources = s;
                  else if (typeof s === 'string') sources = [{ src: s }];
                  else if (s && s.src) sources = [s];
                  else sources = e.file ? [{ src: e.file }] : [];
                  return {
                    sources: sources.filter(function (x) { return x && (x.src || x.file); })
                      .map(function (x) { return { src: x.src || x.file, label: x.label || null }; }),
                    tracks: (e.tracks || []).map(function (t) {
                      return { url: t.url || t.file || null, vttUrl: t.vtt_url || null, label: t.label || null };
                    }),
                  };
                });
              }
              function open(attempt) {
                window.__oct.open(__VIDEO_ID__).then(function (r) {
                  var entries = normalize(r || []);
                  if (!entries.some(function (e) { return e.sources.length; })) throw new Error('no sources');
                  bridge.onResult(JSON.stringify(entries));
                }).catch(function (e) {
                  if (attempt < 3) setTimeout(function () { open(attempt + 1); }, attempt * 1000);
                  else bridge.onError(String((e && e.message) || e));
                });
              }
              function load(i) {
                if (i >= scripts.length) return bridge.onError('player runtime failed to load');
                var el = document.createElement('script');
                var waited = 0;
                var timer = setInterval(function () {
                  if (window.__oct && typeof window.__oct.open === 'function') {
                    clearInterval(timer);
                    open(1);
                  } else if ((waited += 100) > 10000) {
                    clearInterval(timer);
                    load(i + 1);
                  }
                }, 100);
                el.src = scripts[i];
                el.onerror = function () { clearInterval(timer); load(i + 1); };
                document.head.appendChild(el);
              }
              load(0);
            })();
        """.trimIndent()
    }
}
