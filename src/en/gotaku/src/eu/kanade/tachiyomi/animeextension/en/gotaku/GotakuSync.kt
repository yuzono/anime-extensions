package eu.kanade.tachiyomi.animeextension.en.gotaku

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GotakuSync(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val headers: Headers,
    private val client: OkHttpClient,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val isSyncing = AtomicBoolean(false)
    private val syncSessionCounter = AtomicInteger(0)

    @Volatile private var activeCompletionLatch: CountDownLatch? = null

    @Volatile private var currentPageUrl: String? = null

    private fun isTrustedPageUrl(url: String?): Boolean {
        return !url.isNullOrEmpty() && try {
            val parsed = url.toHttpUrl()
            parsed.scheme == "https" && parsed.host == trustedHost
        } catch (_: Exception) {
            false
        }
        // Require https and the exact host.
    }

    private val trustedHost: String by lazy {
        try {
            baseUrl.toHttpUrl().host
        } catch (_: Exception) {
            baseUrl.substringAfter("//", baseUrl).substringBefore('/')
        }
    }

    class GotakuCredentials(
        val version: String,
        val seed: ByteArray,
        val info: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GotakuCredentials
            return version == other.version && seed.contentEquals(other.seed) && info == other.info
        }
        override fun hashCode(): Int {
            var result = version.hashCode()
            result = 31 * result + seed.contentHashCode()
            result = 31 * result + info.hashCode()
            return result
        }
    }

    class JsBridge(
        private val sessionId: Int,
        private val currentSessionIdProvider: () -> Int,
        private val trustedPage: () -> Boolean,
        private val onSeed: (Int, String) -> Unit,
        private val onInfo: (Int, String) -> Unit,
        private val onVersion: (Int, String) -> Unit,
    ) {
        @JavascriptInterface
        fun onSeedCaptured(seedJson: String) {
            if (sessionId == currentSessionIdProvider() && trustedPage()) {
                onSeed(sessionId, seedJson)
            }
        }

        @JavascriptInterface
        fun onInfoCaptured(infoStr: String) {
            if (sessionId == currentSessionIdProvider() && trustedPage()) {
                onInfo(sessionId, infoStr)
            }
        }

        @JavascriptInterface
        fun onVersionCaptured(versionStr: String) {
            if (sessionId == currentSessionIdProvider() && trustedPage()) {
                onVersion(sessionId, versionStr)
            }
        }
    }

    /**
     * @param force Run a WebView capture even when stored credentials exist.
     * @param replaceStored Promote policy for the capture: true when the stored
     *   credentials were just proven broken (failure-triggered resync) — the
     *   fresh capture always wins; false for proactive refreshes, where a lower
     *   captured build must not replace newer stored credentials.
     */
    fun sync(force: Boolean = false, replaceStored: Boolean = force): GotakuCredentials? {
        if (!force) {
            readStoredCredentials()?.let { return it }
        }

        var latchToWait: CountDownLatch? = null
        val completionLatch = CountDownLatch(1)

        synchronized(this) {
            if (isSyncing.get()) {
                latchToWait = activeCompletionLatch
            } else {
                isSyncing.set(true)
                activeCompletionLatch = completionLatch
            }
        }

        if (latchToWait != null) {
            Log.d(TAG, "Sync session already running, waiting for completion...")
            try {
                latchToWait.await(TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            return readStoredCredentials()
        }

        val sessionId = syncSessionCounter.incrementAndGet()
        val captureLatch = CountDownLatch(1)

        var capturedSeed: ByteArray? = null
        var capturedVersion: String? = null
        var capturedInfo: String = "api-seal|v1"
        var infoCaptured = false

        val captureLock = Any()

        /** Keeps the GREATEST version seen — never the first-seen. */
        fun acceptVersion(ver: String) {
            val candidate = ver.trim().toIntOrNull() ?: return
            synchronized(captureLock) {
                val current = capturedVersion?.toIntOrNull()
                if (current == null || candidate > current) {
                    capturedVersion = candidate.toString()
                    Log.d(TAG, "Captured API_VERSION: $capturedVersion")
                    checkAndFinish(captureLatch, capturedSeed, capturedVersion, infoCaptured)
                }
            }
        }

        fun acceptSeed(bytes: ByteArray) {
            synchronized(captureLock) {
                capturedSeed = bytes
                Log.d(TAG, "Captured API_SEED (${bytes.size} bytes)")
                checkAndFinish(captureLatch, capturedSeed, capturedVersion, infoCaptured)
            }
        }

        fun acceptInfo(info: String) {
            synchronized(captureLock) {
                capturedInfo = info
                infoCaptured = true
                Log.d(TAG, "Captured HKDF Info: $info")
                checkAndFinish(captureLatch, capturedSeed, capturedVersion, infoCaptured)
            }
        }

        var webView: WebView? = null

        try {
            Log.d(TAG, "Starting WebView sync session #$sessionId for Gotaku crypto...")

            val bridge = JsBridge(
                sessionId = sessionId,
                currentSessionIdProvider = { sessionId },
                trustedPage = { isTrustedPageUrl(currentPageUrl) },
                onSeed = { sId, seedJson ->
                    if (sId == sessionId) {
                        try {
                            val bytes = seedJson.trim('[', ']').split(",")
                                .mapNotNull { it.trim().toIntOrNull()?.toByte() }
                                .toByteArray()
                            if (bytes.size == 32) {
                                acceptSeed(bytes)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse captured seed", e)
                        }
                    }
                },
                onInfo = { sId, infoStr ->
                    if (sId == sessionId && infoStr.isNotBlank()) {
                        acceptInfo(infoStr)
                    }
                },
                onVersion = { sId, ver ->
                    if (sId == sessionId && ver.isNotBlank() && ver.all { it.isDigit() }) {
                        acceptVersion(ver)
                    }
                },
            )

            handler.post {
                try {
                    val view = WebView(applicationContext)
                    webView = view

                    @SuppressLint("SetJavaScriptEnabled")
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = headers["User-Agent"]
                    }

                    view.addJavascriptInterface(bridge, "GotakuBridge")

                    val hookScript = HOOK_SCRIPT_TEMPLATE.replace("%BASE_URL%", baseUrl)
                    val injectHook = {
                        view.evaluateJavascript(hookScript, null)
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(v, url, favicon)
                            currentPageUrl = url
                            if (!isTrustedPageUrl(url)) {
                                v?.stopLoading()
                                return
                            }
                            injectHook()
                        }

                        override fun onLoadResource(v: WebView?, url: String?) {
                            super.onLoadResource(v, url)
                            if (capturedSeed == null && url != null && url.startsWith(baseUrl)) {
                                injectHook()
                            }
                        }

                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            if (isTrustedPageUrl(url)) {
                                injectHook()
                            }
                        }

                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean = // true = WebView must not load it. Only same-origin
                            !isTrustedPageUrl(request?.url?.toString())

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean = !isTrustedPageUrl(url)

                        override fun shouldInterceptRequest(
                            v: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()

                            if (!request!!.isForMainFrame) {
                                when (request.requestHeaders?.get("Sec-Fetch-Dest")) {
                                    "iframe", "frame" ->
                                        if (!isTrustedPageUrl(reqUrl)) {
                                            Log.w(TAG, "Blocked untrusted frame document during sync: $reqUrl")
                                            return WebResourceResponse(
                                                "text/plain",
                                                "utf-8",
                                                403,
                                                "Blocked",
                                                emptyMap(),
                                                java.io.ByteArrayInputStream(ByteArray(0)),
                                            )
                                        }
                                }
                            }

                            if (isTrustedPageUrl(currentPageUrl) && reqUrl.startsWith(baseUrl)) {
                                if (capturedSeed == null) {
                                    handler.post(injectHook)
                                }
                                if (reqUrl.contains("/api/")) {
                                    val kParam = request.url.getQueryParameter("k")
                                    if (!kParam.isNullOrEmpty() && kParam.all { it.isDigit() }) {
                                        acceptVersion(kParam)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(v, request)
                        }
                    }

                    view.loadUrl("$baseUrl/browse")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize WebView for sync session #$sessionId", e)
                    captureLatch.countDown()
                }
            }

            captureLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

            // Read the final capture state under the same lock the callbacks use
            val seed: ByteArray?
            val version: String?
            val info: String
            val infoOk: Boolean
            synchronized(captureLock) {
                seed = capturedSeed
                version = capturedVersion
                info = capturedInfo
                infoOk = infoCaptured
            }

            if (seed != null && version != null && infoOk && seed.size == 32 && version.all { it.isDigit() }) {
                val creds = GotakuCredentials(version, seed, info)
                if (verifyCredentials(creds)) {
                    return promoteCapture(creds, replaceAny = replaceStored)
                }
                Log.w(TAG, "Captured credentials failed the API decrypt oracle — discarded")
                return readStoredCredentials()
            }

            return readStoredCredentials()
        } finally {
            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
            }
            synchronized(this) {
                completionLatch.countDown()
                activeCompletionLatch = null
                isSyncing.set(false)
            }
        }
    }

    /**
     * Cryptographic self-check of freshly captured credentials — the only
     * values that can be persisted are ones that actually decrypt the live
     * site. The site version-tags only its API endpoints (auth/me, episode
     * lists, episode details, embeds), and `/api/v1/auth/me` is callable with
     * just the version, so a captured (version, seed, info) triple must
     * decrypt that envelope or the capture is rejected.
     */
    private fun verifyCredentials(creds: GotakuCredentials): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/me?k=${creds.version}")
            .headers(
                headers.newBuilder()
                    .set("Referer", "$baseUrl/browse")
                    .set("Accept", "application/json")
                    .build(),
            )
            .build()
        return runCatching {
            client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { response ->
                    response.isSuccessful &&
                        Crypto.decryptInfo(
                            response.body.bytes(),
                            creds.version,
                            creds.seed,
                            creds.info,
                        ) != null
                }
        }.getOrDefault(false)
    }

    private fun checkAndFinish(latch: CountDownLatch, seed: ByteArray?, version: String?, infoOk: Boolean) {
        if (seed != null && seed.size == 32 && version != null && version.all { it.isDigit() } && infoOk) {
            latch.countDown()
        }
    }

    /**
     * A fresh capture from the live site replaces stored credentials when:
     *  - there was nothing stored, or
     *  - [replaceAny] — the stored set was just proven broken (426 / decrypt
     *    failure), so the bytes the site is serving right now are the only
     *    truth, even if the stored version number is "newer", or
     *  - the captured build is newer than the stored one, or
     *  - the builds are equal but the captured seed differs.
     */
    private fun promoteCapture(creds: GotakuCredentials, replaceAny: Boolean): GotakuCredentials {
        val stored = readStoredCredentials()
        val better = replaceAny ||
            stored == null ||
            creds.version.toLong() > stored.version.toLong() ||
            (creds.version == stored.version && creds != stored)
        if (better) {
            saveStoredCredentials(creds)
            return creds
        }
        return stored
    }

    fun readStoredCredentials(): GotakuCredentials? {
        val prefs = preferences
        if (!prefs.contains(PREF_API_SEED_KEY) || !prefs.contains(PREF_API_VERSION_KEY)) {
            return null
        }
        val ver = prefs.getString(PREF_API_VERSION_KEY, null) ?: Crypto.DEFAULT_API_VERSION
        val seedStr = prefs.getString(PREF_API_SEED_KEY, null) ?: return null
        val seed = try {
            seedStr.split(",").mapNotNull { it.trim().toIntOrNull()?.toByte() }
                .toByteArray()
                .takeIf { it.size == 32 }
        } catch (_: Exception) {
            null
        } ?: return null
        val info = prefs.getString(PREF_HKDF_INFO_KEY, null) ?: "api-seal|v1"
        return GotakuCredentials(ver, seed, info)
    }

    private fun saveStoredCredentials(creds: GotakuCredentials) {
        val seedStr = creds.seed.joinToString(",") { it.toInt().toString() }
        preferences.edit()
            .putString(PREF_API_SEED_KEY, seedStr)
            .putString(PREF_API_VERSION_KEY, creds.version)
            .putString(PREF_HKDF_INFO_KEY, creds.info)
            .apply()
        Log.d(TAG, "Saved Gotaku credentials: version=${creds.version}, seed=${creds.seed.size} bytes, info=${creds.info}")
    }

    companion object {
        private const val TAG = "GotakuSync"
        private const val TIMEOUT_SEC = 15L

        const val PREF_API_SEED_KEY = "gotaku_api_seed"
        const val PREF_API_VERSION_KEY = "gotaku_api_version"
        const val PREF_HKDF_INFO_KEY = "gotaku_hkdf_info"

        private val HOOK_SCRIPT_TEMPLATE = """
            (function() {
                try {
                    if (window !== window.top) return;
                    if (!window.location.href.startsWith('%BASE_URL%')) return;
                } catch(e) { return; }

                if (window.__gotakuCaptured) return;
                window.__gotakuCaptured = true;

                const origImportKey = window.crypto.subtle.importKey;
                window.crypto.subtle.importKey = async function(fmt, data, alg, ext, uses) {
                    if (fmt === 'raw' && data && uses && uses.includes('deriveKey')) {
                        try {
                            const seedArr = Array.from(new Uint8Array(data));
                            if (window.GotakuBridge && window.GotakuBridge.onSeedCaptured) {
                                window.GotakuBridge.onSeedCaptured(JSON.stringify(seedArr));
                            }
                        } catch(e) {}
                    }
                    return origImportKey.call(this, fmt, data, alg, ext, uses);
                };

                const origDeriveKey = window.crypto.subtle.deriveKey;
                window.crypto.subtle.deriveKey = async function(alg, baseKey, derivedAlg, ext, uses) {
                    if (alg?.name === 'HKDF' && derivedAlg?.name === 'AES-GCM' && uses.includes('decrypt')) {
                        try {
                            const infoStr = alg.info ? new TextDecoder().decode(alg.info) : 'api-seal|v1';
                            if (window.GotakuBridge && window.GotakuBridge.onInfoCaptured) {
                                window.GotakuBridge.onInfoCaptured(infoStr);
                            }
                        } catch(e) {}
                    }
                    return origDeriveKey.call(this, alg, baseKey, derivedAlg, ext, uses);
                };

                const origFetch = window.fetch;
                window.fetch = async function(...args) {
                    try {
                        const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                        if (url && String(url).includes('/api/')) {
                            const match = String(url).match(/[?&]k=(\d+)/);
                            if (match && match[1] && window.GotakuBridge && window.GotakuBridge.onVersionCaptured) {
                                window.GotakuBridge.onVersionCaptured(match[1]);
                            }
                        }
                    } catch(e) {}
                    return origFetch.apply(this, args);
                };

                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                    try {
                        if (url && String(url).includes('/api/')) {
                            const match = String(url).match(/[?&]k=(\d+)/);
                            if (match && match[1] && window.GotakuBridge && window.GotakuBridge.onVersionCaptured) {
                                window.GotakuBridge.onVersionCaptured(match[1]);
                            }
                        }
                    } catch(e) {}
                    return origOpen.call(this, method, url, ...rest);
                };
            })();
        """.trimIndent()
    }
}
