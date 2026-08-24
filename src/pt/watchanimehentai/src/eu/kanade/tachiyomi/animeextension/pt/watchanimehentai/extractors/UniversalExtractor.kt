package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag by lazy { javaClass.simpleName }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""

        Handler(Looper.getMainLooper()).post {
            val newView = WebView(applicationContext)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestHeader["User-Agent"]
            }
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

            newView.loadUrl(origRequestUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        Handler(Looper.getMainLooper()).post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val prefix = name ?: "Player"

        return when {
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                // Para HLS, normalmente retornamos a URL diretamente; o player suporta.
                listOf(Video(resultUrl, "$prefix: HLS", resultUrl))
            }
            "mp4" in resultUrl -> {
                Log.d(tag, "mp4 URL: $resultUrl")
                listOf(Video(resultUrl, "$prefix: MP4", resultUrl))
            }
            "googlevideo" in resultUrl -> {
                Log.d(tag, "googlevideo URL: $resultUrl")
                listOf(Video(resultUrl, "$prefix: MP4", resultUrl))
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 10L
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE) }
        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                var playButton = document.getElementById('player-button-container')
                if (playButton) {
                    playButton.click()
                }
                var downloadButton = document.querySelector(".downloader-button")
                if (downloadButton) {
                    if (downloadButton.href) {
                        location.href = downloadButton.href
                    } else {
                        downloadButton.click()
                    }
                }
                // Default jwplayer instance
                try { jwplayer(0).play(); } catch {}
            }, 2500)
            """.trimIndent()
        }
    }
}
