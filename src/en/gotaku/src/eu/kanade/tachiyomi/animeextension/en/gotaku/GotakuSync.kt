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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GotakuSync(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val headers: Headers,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val isSyncing = AtomicBoolean(false)
    private val syncSessionCounter = AtomicInteger(0)

    @Volatile private var activeCompletionLatch: CountDownLatch? = null

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
        private val onSeed: (Int, String) -> Unit,
        private val onInfo: (Int, String) -> Unit,
        private val onVersion: (Int, String) -> Unit,
    ) {
        @JavascriptInterface
        fun onSeedCaptured(seedJson: String) {
            if (sessionId == currentSessionIdProvider()) {
                onSeed(sessionId, seedJson)
            }
        }

        @JavascriptInterface
        fun onInfoCaptured(infoStr: String) {
            if (sessionId == currentSessionIdProvider()) {
                onInfo(sessionId, infoStr)
            }
        }

        @JavascriptInterface
        fun onVersionCaptured(versionStr: String) {
            if (sessionId == currentSessionIdProvider()) {
                onVersion(sessionId, versionStr)
            }
        }
    }

    fun sync(force: Boolean = false): GotakuCredentials? {
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

        var webView: WebView? = null

        try {
            Log.d(TAG, "Starting WebView sync session #$sessionId for Gotaku crypto...")

            val bridge = JsBridge(
                sessionId = sessionId,
                currentSessionIdProvider = { sessionId },
                onSeed = { sId, seedJson ->
                    if (sId == sessionId) {
                        try {
                            val bytes = seedJson.trim('[', ']').split(",")
                                .mapNotNull { it.trim().toIntOrNull()?.toByte() }
                                .toByteArray()
                            if (bytes.size == 32) {
                                capturedSeed = bytes
                                Log.d(TAG, "Captured API_SEED (${bytes.size} bytes)")
                                checkAndFinish(captureLatch, capturedSeed, capturedVersion)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse captured seed", e)
                        }
                    }
                },
                onInfo = { sId, infoStr ->
                    if (sId == sessionId && infoStr.isNotBlank()) {
                        capturedInfo = infoStr
                        Log.d(TAG, "Captured HKDF Info: $infoStr")
                    }
                },
                onVersion = { sId, ver ->
                    if (sId == sessionId && ver.isNotBlank() && ver.all { it.isDigit() }) {
                        capturedVersion = ver
                        Log.d(TAG, "Captured API_VERSION: $ver")
                        checkAndFinish(captureLatch, capturedSeed, capturedVersion)
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
                            if (url != null && url.startsWith(baseUrl)) {
                                injectHook()
                            }
                        }

                        override fun onLoadResource(v: WebView?, url: String?) {
                            super.onLoadResource(v, url)
                            if (capturedSeed == null && url != null && url.startsWith(baseUrl)) {
                                injectHook()
                            }
                        }

                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            if (url != null && url.startsWith(baseUrl)) {
                                injectHook()
                            }
                        }

                        override fun shouldInterceptRequest(
                            v: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.startsWith(baseUrl)) {
                                if (capturedSeed == null) {
                                    handler.post(injectHook)
                                }
                                // Only capture API version 'k' from actual API endpoint requests (containing /api/)
                                if (reqUrl.contains("/api/")) {
                                    val kParam = request?.url?.getQueryParameter("k")
                                    if (!kParam.isNullOrEmpty() && kParam.all { it.isDigit() }) {
                                        capturedVersion = kParam
                                        Log.d(TAG, "Captured API_VERSION from API request URL: $kParam")
                                        checkAndFinish(captureLatch, capturedSeed, capturedVersion)
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

            val seed = capturedSeed
            val version = capturedVersion
            val info = capturedInfo

            if (seed != null && version != null && seed.size == 32 && version.all { it.isDigit() }) {
                val creds = GotakuCredentials(version, seed, info)
                saveStoredCredentials(creds)
                return creds
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

    private fun checkAndFinish(latch: CountDownLatch, seed: ByteArray?, version: String?) {
        if (seed != null && seed.size == 32 && version != null && version.all { it.isDigit() }) {
            latch.countDown()
        }
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
