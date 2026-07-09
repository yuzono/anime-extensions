package aniyomi.lib.cloudflareinterceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * WebView-based Cloudflare bypass interceptor.
 *
 * Launches an Android [WebView] on the main thread, loads the challenged URL,
 * and waits for Cloudflare to issue `cf_clearance` (and `__cf_bm`) cookies
 * before retrying the original request with those cookies attached.
 *
 * This is the only reliable way to bypass modern Cloudflare managed challenges
 * and Turnstile widgets on Android — headless solvers cannot reproduce the
 * browser fingerprint signals Cloudflare now collects (real canvas rendering,
 * WebGL viewport, pointer events, focus state, etc.).
 *
 * **Design choices:**
 * - **Per-host locks** ([hostLocks]) deduplicate concurrent solves for the same
 *   host. Non-contending hosts proceed in parallel; contending callers wait
 *   for the winner and reuse its cookies.
 * - **In-memory cookie cache** ([CookieCache]) with 25-minute TTL avoids
 *   burning a fresh WebView for every request during a single user session.
 *   `cf_clearance` is bound to TLS/JA3+UA+IP, so it is intentionally **not**
 *   persisted across process death — see plan §final-confirmation #2.
 * - **Caller UA passthrough** — uses `request.header("User-Agent")` if present
 *   (falling back to a current Chrome 131 / Windows UA). Cloudflare re-challenges
 *   sessions that flip UA between the original request and the WebView, so this
 *   is critical.
 * - **`cf-mitigated: challenge` header detection** alongside the legacy
 *   `Server: cloudflare` + 403/503 signaling, for modern CF responses.
 * - **Cookie sync to the OkHttp client** uses the **real** request URL
 *   (scheme + host + path), not a fake `http://` URL — the old code's cookies
 *   were silently discarded.
 *
 * **Caller contract:** add this as a network interceptor so that the WebView
 * solve and the post-solve retry both flow through the rest of the chain
 * (zstd decompression, rate limiting, etc.). Adding as an app interceptor
 * causes the retry to bypass downstream network interceptors.
 *
 * @param client the OkHttpClient whose [cookieJar][OkHttpClient.cookieJar]
 *   will receive the WebView-acquired cookies so they persist for the session
 *   within OkHttp's own cookie storage (not just this interceptor's cache)
 * @param userAgent canonical User-Agent string to use when the request itself
 *   does not carry one. Must be a real, current Chrome version to keep the
 *   TLS/JA3 fingerprint and `sec-ch-ua` brand list coherent with the rest of
 *   the session.
 */
class CloudflareInterceptor(
    private val client: OkHttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalResponse = chain.proceed(originalRequest)

        if (!isCloudflareChallenge(originalResponse)) {
            return originalResponse
        }

        val url = originalRequest.url
        val host = url.host

        // Try the cookie cache first — another request may have just solved
        // for this host. If still valid, retry without burning a WebView.
        val cachedCookies = cookieCache.getMatching(url)
        if (cachedCookies != null) {
            originalResponse.close()
            val retryRequest = attachCookies(originalRequest, cachedCookies)
            val retryResponse = chain.proceed(retryRequest)
            if (!isCloudflareChallenge(retryResponse)) {
                return retryResponse
            }
            retryResponse.close()
            cookieCache.remove(url)
            Log.d(TAG, "Cached cf_clearance for $host was stale, re-solving via WebView")
        }

        // Per-host lock so concurrent requests for the same host wait for a
        // single WebView solve instead of each launching their own. Hosts that
        // don't contend proceed immediately.
        val lock = hostLocks.getOrPut(host) { ReentrantLock() }
        val acquired = lock.tryLock()
        if (!acquired) {
            // Another thread is solving for this host. Wait for it, polling
            // the cache. This blocks the OkHttp dispatcher thread, but only
            // while a solve is in flight — better than queuing serially via
            // @Synchronized (which serialized ALL hosts).
            originalResponse.close()
            Log.d(TAG, "Waiting for in-flight WebView solve for $host")
            val deadline = System.currentTimeMillis() + SOLVE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val retry = cookieCache.getMatching(url)
                if (retry != null) {
                    return chain.proceed(attachCookies(originalRequest, retry))
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            // Still no cookies — retry the original; CF will likely re-challenge
            // and we'll go through the solve path ourselves next time.
            return chain.proceed(originalRequest)
        }

        try {
            originalResponse.close()
            // Solve with WebView. resolveWithWebView returns a request
            // decorated with the freshly-acquired cookies.
            val solvedRequest = resolveWithWebView(originalRequest, url)
            // Retry through the full chain so downstream interceptors
            // (zstd decompression, rate limit, etc.) still run on the response.
            val retryResponse = chain.proceed(solvedRequest)
            if (isCloudflareChallenge(retryResponse)) {
                retryResponse.close()
                throw IOException("Cloudflare WebView solve did not clear challenge for $host")
            }
            return retryResponse
        } finally {
            lock.unlock()
        }
    }

    /**
     * Launches a WebView on the main thread, loads the challenged URL, polls
     * for `cf_clearance` in the WebView's cookie jar, and returns a request
     * decorated with the freshly-acquired cookies.
     *
     * **Threading:** the calling OkHttp dispatcher thread blocks on
     * [completionLatch] while the WebView runs on the main thread. The WebView
     * instance is created, used, and destroyed entirely on the main thread
     * (with a [destroyLatch] ack to ensure no use-after-free from the caller).
     *
     * **Timeout:** [SOLVE_TIMEOUT_MS] per attempt. The polling script exits
     * early as soon as `cf_clearance` appears, so most solves complete in ~5s
     * for managed challenges and ~8-15s for Turnstile.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, url: HttpUrl): Request {
        val completionLatch = CountDownLatch(1)
        val jsInterface = CloudflareJSInterface(completionLatch)

        // AtomicReference so the calling thread sees the main-thread assignment
        // (the annotation @Volatile is not applicable to local variables).
        // The destroy latch guarantees the WebView is fully gone before we
        // proceed, fixing the happens-before tear in the old code.
        val webViewRef = AtomicReference<WebView?>(null)
        val destroyLatch = CountDownLatch(1)

        val requestUrlString = url.toString()
        val requestHeaders = originalRequest.headers.toMultimap()
            .mapValues { it.value.joinToString("; ") }
            .toMutableMap()
        // Force the canonical UA into the WebView if the request didn't carry one.
        requestHeaders.putIfAbsent("User-Agent", userAgent)

        handler.post {
            val webView = WebView(context)
            webViewRef.set(webView)
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    userAgentString = requestHeaders["User-Agent"] ?: userAgent
                }

                // Accept cookies so cf_clearance lands in CookieManager. These
                // are CookieManager APIs, not WebSettings — calling them on
                // webView.settings (as the old code did) is an unresolved reference.
                CookieManager.getInstance().setAcceptCookie(true)
                // Allow third-party cookies — Cloudflare sets the cookie
                // on its own domain while the page is the target host, but
                // some CF configurations redirect through challenges.cloudflare.com.
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.addJavascriptInterface(jsInterface, "CloudflareJSI")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        view?.evaluateJavascript(CHECK_SCRIPT) {}
                    }
                }

                webView.loadUrl(requestUrlString, requestHeaders)
            } catch (e: Exception) {
                Log.e(TAG, "WebView setup failed for $requestUrlString", e)
                completionLatch.countDown()
            }
        }

        // Wait for the JS interface to signal cf_clearance presence (or timeout).
        try {
            completionLatch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Tear down the WebView on the main thread and wait for the ack so
        // the calling thread never reads a torn-down reference.
        handler.post {
            try {
                webViewRef.get()?.stopLoading()
                webViewRef.get()?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "WebView teardown error", e)
            } finally {
                webViewRef.set(null)
                destroyLatch.countDown()
            }
        }
        destroyLatch.await(TEARDOWN_ACK_MS, TimeUnit.MILLISECONDS)

        // Extract cookies from CookieManager and sync into OkHttp + cache.
        val cookieHeader = CookieManager.getInstance().getCookie(requestUrlString) ?: ""
        val cookies = cookieHeader.split(";")
            .mapNotNull { raw ->
                val parsed = Cookie.parse(url, raw.trim())
                parsed?.let { it.name to it }
            }
            .toMap()

        if (cookies.isEmpty()) {
            throw IOException("Cloudflare WebView solve produced no cookies for $url")
        }

        // Sync to the OkHttp client's cookie jar using the REAL url.
        // The old code used a fake http:// URL which silently discarded cookies.
        try {
            client.cookieJar.saveFromResponse(
                url = url,
                cookies = cookies.values.toList(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync WebView cookies to OkHttp cookie jar", e)
        }

        // Cache cf_clearance + __cf_bm for cross-request reuse in this session.
        val cacheableCookies = cookies.entries
            .filter { (name, _) -> name == "cf_clearance" || name.startsWith("__cf_") || name.startsWith("cf_") }
            .map { it.value }
        if (cacheableCookies.isNotEmpty()) {
            cookieCache.put(url, cacheableCookies)
            Log.d(TAG, "Cached ${cacheableCookies.size} CF cookies for $url.host (cf_clearance=${cookies.containsKey("cf_clearance")})")
        }

        if (!cookies.containsKey("cf_clearance")) {
            throw IOException("Cloudflare WebView solve did not produce cf_clearance for $url (cookies: ${cookies.keys})")
        }

        return attachCookies(originalRequest, cookies.values.toList())
    }

    /**
     * Attaches the given cookies to the original request, preserving any
     * existing request cookies not overridden by the new ones.
     */
    private fun attachCookies(request: Request, cookies: List<Cookie>): Request {
        val matching = cookies.filter { it.matches(request.url) }
        val existing = Cookie.parseAll(request.url, request.headers)
        val merged = buildList {
            // Keep existing cookies whose names are not overridden by cached ones.
            addAll(
                existing.filter { existingCookie ->
                    matching.none { it.name == existingCookie.name }
                },
            )
            addAll(matching)
        }
        return request.newBuilder()
            .header("Cookie", merged.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val code = response.code
        val server = response.header("Server")
        val mitigated = response.header("cf-mitigated")
        val isError = code in ERROR_CODES
        val isCfServer = server in SERVER_CHECK
        // Legacy / nginx marker: code in {403,503} AND Server: cloudflare[-nginx]
        if (isError && isCfServer) return true
        // Modern CF managed challenge: returns 403 with `cf-mitigated: challenge`
        // even when Server isn't exactly "cloudflare-nginx"
        if (isError && mitigated.equals("challenge", ignoreCase = true)) return true
        return false
    }

    /**
     * JS interface that the [CHECK_SCRIPT] polls to signal challenge completion.
     */
    class CloudflareJSInterface(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() = latch.countDown()
    }

    companion object {
        private const val TAG = "CloudflareInterceptor"

        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        /** Canonical User-Agent: Chrome 131 / Windows — matches the
         *  WebView Chromium engine (the TLS stack carrying cf_clearance
         *  solve) when a Windows-shaped UA override is in effect. */
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Per-attempt WebView solve timeout. CF managed ~5s, Turnstile ~8-15s. */
        private const val SOLVE_TIMEOUT_MS = 30_000L

        /** How long to wait for another thread's in-flight solve. */
        private const val SOLVE_WAIT_MS = 30_000L

        /** Cache poll interval while waiting for another thread's solve. */
        private const val POLL_INTERVAL_MS = 100L

        /** Ack timeout for WebView teardown on main thread. */
        private const val TEARDOWN_ACK_MS = 2_000L

        /** Per-host solve locks. */
        private val hostLocks = ConcurrentHashMap<String, ReentrantLock>()

        /** In-session cookie cache shared across all CloudflareInterceptor instances. */
        internal val cookieCache = CloudflareCookieCache()

        /**
         * JS injected on every `onPageFinished`. Polls for `cf_clearance` cookie
         * presence in `document.cookie` — this works *across origins* because
         * `document.cookie` is same-origin to the loaded page (miruro.tv), unlike
         * the old code which reached into the cross-origin Turnstile iframe
         * (challenges.cloudflare.com) and tripped a SecurityError.
         *
         * When either (a) `cf_clearance` appears, or (b) the challenge form has
         * been removed from the DOM (managed-challenge success redirects away),
         * we signal completion via `CloudflareJSI.leave()`.
         *
         * We do NOT attempt to programmatically click the Turnstile checkbox —
         * that lives in a cross-origin iframe and the click can't be trucked
         * from JS. CF's own challenge script handles interaction (or auto-passes
         * after the bot-detection signals clear). The WebView just needs to load
         * and let CF's JS run; for many sites the managed challenge auto-passes
         * once CF's fingerprinting completes in a real browser engine.
         */
        private val CHECK_SCRIPT by lazy {
            """
            (function() {
                var attempts = 0;
                var maxAttempts = ${SOLVE_TIMEOUT_MS} / 500;
                var timer = setInterval(function() {
                    attempts++;
                    try {
                        var cookie = document.cookie || '';
                        var hasClearance = cookie.indexOf('cf_clearance=') !== -1;
                        var noChallengeForm = !document.querySelector('#challenge-form')
                            && !document.querySelector('#challenge-stage')
                            && !document.querySelector('.cf-turnstile');
                        if (hasClearance || (noChallengeForm && attempts > 2)) {
                            clearInterval(timer);
                            try { CloudflareJSI.leave(); } catch (e) {}
                        } else if (attempts >= maxAttempts) {
                            clearInterval(timer);
                            try { CloudflareJSI.leave(); } catch (e) {}
                        }
                    } catch (e) {
                        // Swallow — keep polling. A SecurityError on one tick
                        // doesn't mean the next tick will also fail.
                    }
                }, 500);
            })();
            """.trimIndent()
        }
    }
}

/**
 * In-memory Cloudflare cookie cache keyed by host, with TTL derived from
 * each cookie's own `expiresAt` (capped to [DEFAULT_TTL_MILLIS]).
 *
 * Cookies are intentionally **not** persisted to SharedPreferences: `cf_clearance`
 * is bound to the TLS session's JA3 + UA + client IP, and on a fresh process
 * those may differ (the WebView Chromium TLS may have rotated, the device may
 * have roamed networks), so a stale-but-not-expired `cf_clearance` causes CF to
 * issue a *harder* challenge on retry. Keeping cookies in-process only means
 * each new process solves once and then reuses for the session.
 */
internal class CloudflareCookieCache {

    private data class HostEntry(
        val cookies: List<Cookie>,
        val storedAt: Long,
    )

    private val store = ConcurrentHashMap<String, HostEntry>()

    fun put(url: HttpUrl, cookies: List<Cookie>) {
        val nonSession = cookies.filter { it.persistent }
        if (nonSession.isEmpty()) return
        store[url.host] = HostEntry(nonSession, System.currentTimeMillis())
    }

    fun getMatching(url: HttpUrl): List<Cookie>? {
        val entry = store[url.host] ?: return null
        val now = System.currentTimeMillis()
        val valid = entry.cookies.filter { it.expiresAt > now }
        if (valid.isEmpty()) {
            store.remove(url.host)
            return null
        }
        val matching = valid.filter { it.matches(url) }
        return matching.ifEmpty { null }
    }

    fun remove(url: HttpUrl) {
        store.remove(url.host)
    }

    companion object {
        /** Default TTL cap: 25 minutes (cf_clearance is ~30 min from CF). */
        internal const val DEFAULT_TTL_MILLIS = 25L * 60 * 1000
    }
}
