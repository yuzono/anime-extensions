package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(
        origRequestUrl: String,
        origRequestHeader: Headers,
        name: String? = null,
    ): List<Video> {
        val httpUrl = origRequestUrl.toHttpUrlOrNull() ?: return emptyList()
        Log.d(tag, "Fetching videos from: $origRequestUrl")
        val host = httpUrl.host.removePrefix("www.").substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
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

            newView.clearCache(true)
            newView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(tag, "Page loaded, injecting script")
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    Log.d(tag, "Intercepted URL: $url")
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val prefix = name ?: host

        return when {
            "m3u8" in resultUrl || "txt" in resultUrl -> {
                Log.d(tag, "Extracting HLS from: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix: $it" })
            }

            "mpd" in resultUrl -> {
                Log.d(tag, "Extracting DASH from: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { "$prefix: $it" }, referer = origRequestUrl)
            }

            "mp4" in resultUrl -> {
                Log.d(tag, "Extracting MP4 from: $resultUrl")
                Video(
                    resultUrl,
                    "$prefix: MP4",
                    resultUrl,
                    Headers.headersOf("referer", origRequestUrl),
                ).let(::listOf)
            }

            else -> {
                Log.w(tag, "No valid video URL found for: $origRequestUrl")
                emptyList()
            }
        }
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    companion object {
        const val TIMEOUT_SEC: Long = 15
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd|txt)(\\?.*)?$", RegexOption.IGNORE_CASE) }

        // Script injected after page load (onPageFinished) for additional player interaction
        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                const selectorsToClick = ['#player-button-container', '#overlay', '.captcha-gate__play']
                selectorsToClick.forEach(selector => {
                    const element = document.querySelector(selector)
                    if (element) {
                        element.click()
                    }
                });

                const x = window.innerWidth / 2
                const y = window.innerHeight / 2
                const centerElement = document.elementFromPoint(x, y)

                if (centerElement) {
                    centerElement.click()
                }

                try { jwplayer(0).play(); } catch {}
            }, 2500)
            """.trimIndent()
        }
    }
}
