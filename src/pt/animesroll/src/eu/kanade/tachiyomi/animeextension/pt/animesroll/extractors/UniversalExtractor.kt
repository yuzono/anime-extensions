package eu.kanade.tachiyomi.animeextension.pt.animesroll.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    @Synchronized
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
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
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix: ${it}p" })
            }
            "mpd" in resultUrl -> {
                Log.d(tag, "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { it -> "$prefix: ${it}p" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d(tag, "mp4 URL: $resultUrl")
                Video(
                    resultUrl,
                    "$prefix: MP4",
                    resultUrl,
                    Headers.headersOf("referer", origRequestUrl),
                ).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase(
                Locale.getDefault(),
            )
        } else {
            it.toString()
        }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE) }
        private val CHECK_SCRIPT by lazy {
            """
            const selectors = ['#player-button-container', '#overlay']
            setInterval(() => {
                // Click the elements with the selectors
                selectors.forEach(selector => {
                    const element = document.querySelector(selector)
                    if (element) {
                        element.click()
                    }
                });

                // Click the center of the screen
                const x = window.innerWidth / 2
                const y = window.innerHeight / 2
                const centerElement = document.elementFromPoint(x, y)

                if (centerElement) {
                    centerElement.click()
                }

                try { jwplayer(0).play(); } catch {} // Default jwplayer instance
            }, 2500)
            """.trimIndent()
        }
    }
}
