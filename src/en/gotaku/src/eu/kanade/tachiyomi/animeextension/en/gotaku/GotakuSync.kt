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

class GotakuSync(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val headers: Headers,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val isSyncing = AtomicBoolean(false)

    @Volatile private var capturedSeed: ByteArray? = null

    @Volatile private var capturedVersion: String? = null

    @Volatile private var capturedInfo: String? = null

    class JsBridge(
        private val onSeed: (String) -> Unit,
        private val onInfo: (String) -> Unit,
        private val onVersion: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun onSeedCaptured(seedJson: String) {
            onSeed(seedJson)
        }

        @JavascriptInterface
        fun onInfoCaptured(infoStr: String) {
            onInfo(infoStr)
        }

        @JavascriptInterface
        fun onVersionCaptured(versionStr: String) {
            onVersion(versionStr)
        }
    }

    /**
     * Spawns background WebView to capture API_SEED and API_VERSION.
     * @param force If true, forces a fresh capture even if values are stored.
     */
    fun sync(force: Boolean = false): Boolean {
        if (!force && hasValidStoredCredentials()) {
            return true
        }

        if (!isSyncing.compareAndSet(false, true)) {
            return hasValidStoredCredentials()
        }

        try {
            Log.d(TAG, "Starting WebView sync for Gotaku crypto parameters...")
            capturedSeed = null
            capturedVersion = null
            capturedInfo = null

            val latch = CountDownLatch(1)
            var webView: WebView? = null

            val bridge = JsBridge(
                onSeed = { seedJson ->
                    try {
                        val bytes = seedJson.trim('[', ']').split(",")
                            .mapNotNull { it.trim().toIntOrNull()?.toByte() }
                            .toByteArray()
                        if (bytes.size == 32) {
                            capturedSeed = bytes
                            Log.d(TAG, "Captured API_SEED (${bytes.size} bytes)")
                            checkAndFinish(latch)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse captured seed", e)
                    }
                },
                onInfo = { infoStr ->
                    if (infoStr.isNotBlank()) {
                        capturedInfo = infoStr
                        Log.d(TAG, "Captured HKDF Info: $infoStr")
                    }
                },
                onVersion = { ver ->
                    if (ver.isNotBlank()) {
                        capturedVersion = ver
                        Log.d(TAG, "Captured API_VERSION: $ver")
                        checkAndFinish(latch)
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

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(v, url, favicon)
                            v?.evaluateJavascript(HOOK_SCRIPT, null)
                        }

                        override fun shouldInterceptRequest(
                            v: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val kParam = request?.url?.getQueryParameter("k")
                            if (!kParam.isNullOrEmpty() && kParam.all { it.isDigit() }) {
                                capturedVersion = kParam
                                Log.d(TAG, "Captured API_VERSION from URL parameter k: $kParam")
                                checkAndFinish(latch)
                            }
                            return super.shouldInterceptRequest(v, request)
                        }
                    }

                    view.loadUrl("$baseUrl/browse")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize WebView for sync", e)
                    latch.countDown()
                }
            }

            val completed = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
            Log.d(
                TAG,
                "WebView sync finished (completed=$completed, seed=${capturedSeed != null}, version=$capturedVersion)",
            )

            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
                webView = null
            }

            val seed = capturedSeed
            val version = capturedVersion
            val info = capturedInfo ?: "api-seal|v1"

            if (seed != null && version != null) {
                saveCredentials(seed, version, info)
                return true
            }

            return hasValidStoredCredentials()
        } finally {
            isSyncing.set(false)
        }
    }

    private fun checkAndFinish(latch: CountDownLatch) {
        if (capturedSeed != null && capturedVersion != null) {
            latch.countDown()
        }
    }

    private fun hasValidStoredCredentials(): Boolean = preferences.contains(PREF_API_SEED_KEY) && preferences.contains(PREF_API_VERSION_KEY)

    private fun saveCredentials(seed: ByteArray, version: String, info: String) {
        val seedStr = seed.joinToString(",") { it.toInt().toString() }
        preferences.edit()
            .putString(PREF_API_SEED_KEY, seedStr)
            .putString(PREF_API_VERSION_KEY, version)
            .putString(PREF_HKDF_INFO_KEY, info)
            .apply()
        Log.d(TAG, "Saved Gotaku credentials: version=$version, seed=${seed.size} bytes, info=$info")
    }

    companion object {
        private const val TAG = "GotakuSync"
        private const val TIMEOUT_SEC = 15L

        const val PREF_API_SEED_KEY = "gotaku_api_seed"
        const val PREF_API_VERSION_KEY = "gotaku_api_version"
        const val PREF_HKDF_INFO_KEY = "gotaku_hkdf_info"

        private val HOOK_SCRIPT = """
            (function() {
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
                        if (url) {
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
                        if (url) {
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
