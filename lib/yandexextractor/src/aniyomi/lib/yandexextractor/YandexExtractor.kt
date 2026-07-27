package aniyomi.lib.yandexextractor

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YandexExtractor(private val client: OkHttpClient) {
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(
        origRequestUrl: String,
        origRequestHeader: Headers,
        name: String? = null,
    ): List<Video> {
        val httpUrl = origRequestUrl.toHttpUrlOrNull() ?: return emptyList()
        if (httpUrl.fragment.isNullOrEmpty()) return emptyList()

        Log.d(tag, "Yandex extractor for: $origRequestUrl")
        val host = httpUrl.host.removePrefix("www.").substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var capturedJson = ""
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        try {
            handler.post {
                try {
                    val newView = WebView(applicationContext)
                    webView = newView
                    with(newView.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = origRequestHeader["User-Agent"] ?: DEFAULT_USER_AGENT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = true
                        allowContentAccess = true
                    }

                    newView.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onCaptured(json: String) {
                                if (capturedJson.isEmpty()) {
                                    capturedJson = json
                                    latch.countDown()
                                }
                            }
                        },
                        "Android",
                    )

                    newView.clearCache(true)
                    newView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    newView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(INJECT_SCRIPT) {}
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(tag, "Page loaded: $url")
                            view?.evaluateJavascript(CLICK_PLAY_SCRIPT) {}
                        }
                    }

                    newView.loadUrl(origRequestUrl, headers)
                } catch (e: Exception) {
                    Log.e(tag, "Error creating WebView", e)
                    latch.countDown()
                }
            }

            val captured = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

            if (!captured || capturedJson.isEmpty()) {
                Log.w(tag, "No Yandex config captured for: $origRequestUrl")
                return emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error", e)
            return emptyList()
        } finally {
            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (e: Exception) {
                    Log.w(tag, "Error destroying WebView", e)
                }
                webView = null
            }
        }

        return extractVideosFromJson(capturedJson, origRequestUrl, host, name)
    }

    private fun extractVideosFromJson(
        jsonString: String,
        referer: String,
        host: String,
        name: String?,
    ): List<Video> {
        val json = try {
            org.json.JSONObject(jsonString)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse captured JSON", e)
            return emptyList()
        }

        val prefix = name ?: host

        // Other URLs available in the config for reference:
        // "source"   → direct CDN m3u8 (region-locked, often fails)
        // "cf"       → Cloudflare-proxied .txt playlist (unstable)
        // "cfNative" → native proxy m3u8 (the only reliable one)
        val videoUrl = json.optString("cfNative", "").takeIf { it.isNotBlank() }
            ?: run {
                Log.w(tag, "No cfNative URL found in Yandex config")
                return emptyList()
            }

        Log.d(tag, "Yandex cfNative: $videoUrl")

        val resolvedUrl = videoUrl.toHttpUrlOrNull()
            ?: run {
                Log.w(tag, "Invalid cfNative URL: $videoUrl")
                return emptyList()
            }

        val qualityLabel = "$prefix - Yandex"
        val playlistUtils = PlaylistUtils(client, buildHeaders(resolvedUrl.toString(), referer))

        val videos = when {
            ".m3u8" in resolvedUrl.toString() || ".txt" in resolvedUrl.toString() -> {
                playlistUtils.extractFromHls(
                    resolvedUrl.toString(),
                    referer,
                    videoNameGen = { "$qualityLabel: $it" },
                )
            }
            ".mpd" in resolvedUrl.toString() -> {
                playlistUtils.extractFromDash(
                    resolvedUrl.toString(),
                    { "$qualityLabel: $it" },
                    referer = referer,
                )
            }
            ".mp4" in resolvedUrl.toString() -> {
                listOf(
                    Video(
                        resolvedUrl.toString(),
                        qualityLabel,
                        resolvedUrl.toString(),
                        Headers.headersOf("referer", referer),
                    ),
                )
            }
            else -> {
                Log.w(tag, "Unknown format in cfNative: $videoUrl")
                emptyList()
            }
        }

        if (videos.isEmpty()) {
            Log.w(tag, "No videos extracted from Yandex config")
            return emptyList()
        }

        Log.d(tag, "Yandex extracted ${videos.size} videos")
        return videos
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
    }

    private fun buildHeaders(url: String, referer: String): Headers {
        val httpUrl = url.toHttpUrlOrNull()
        val origin = if (httpUrl != null) "${httpUrl.scheme}://${httpUrl.host}" else referer
        return Headers.Builder()
            .set("Accept", "*/*")
            .set("Referer", "$referer/")
            .set("Origin", origin)
            .build()
    }

    companion object {
        const val TIMEOUT_SEC: Long = 15
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

        /** Override JSON.parse to intercept the Yandex player config (has source + cfNative). */
        private val INJECT_SCRIPT by lazy {
            """
            (function() {
                var origParse = JSON.parse;
                JSON.parse = function() {
                    var result = origParse.apply(this, arguments);
                    try {
                        if (result && typeof result === 'object' && result.source && result.cfNative) {
                            Android.onCaptured(JSON.stringify(result));
                        }
                    } catch(e) {}
                    return result;
                };
            })();
            """.trimIndent()
        }

        /** Try to trigger play after page load. */
        private val CLICK_PLAY_SCRIPT by lazy {
            """
            setInterval(function() {
                var btns = [
                    'button.vds-play-button',
                    '.vjs-big-play-button',
                    'button[aria-label*="play" i]',
                    'button[aria-label*="reproduzir" i]',
                    '#player-button',
                ];
                btns.forEach(function(sel) {
                    var el = document.querySelector(sel);
                    if (el) el.click();
                });
                var video = document.querySelector('video');
                if (video && video.paused) video.play();
            }, 2000);
            """.trimIndent()
        }
    }
}
