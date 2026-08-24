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
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""

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

            newView.loadUrl(origRequestUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val prefix = name ?: "Player"

        return when {
            "googlevideo" in resultUrl -> {
                Log.d(tag, "googlevideo URL: $resultUrl")
                val videoHeaders = Headers.headersOf("Referer", origRequestUrl)
                listOf(Video(resultUrl, "$prefix: MP4", resultUrl, videoHeaders))
            }
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                val videoHeaders = Headers.headersOf("Referer", origRequestUrl)
                listOf(Video(resultUrl, "$prefix: HLS", resultUrl, videoHeaders))
            }
            "mpd" in resultUrl -> {
                Log.d(tag, "mpd URL: $resultUrl")
                val videoHeaders = Headers.headersOf("Referer", origRequestUrl)
                listOf(Video(resultUrl, "$prefix: DASH", resultUrl, videoHeaders))
            }
            "mp4" in resultUrl -> {
                Log.d(tag, "mp4 URL: $resultUrl")
                val videoHeaders = Headers.headersOf("Referer", origRequestUrl)
                listOf(Video(resultUrl, "$prefix: MP4", resultUrl, videoHeaders))
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 15L

        // Regex ampliado para capturar googlevideo e formatos comuns
        private val VIDEO_REGEX by lazy {
            Regex(
                "(https?://[^\\s\"']*\\.(?:mp4|m3u8|mpd)(?:\\?[^\\s\"']*)?)|(https?://[^\\s\"']*googlevideo\\.com/videoplayback[^\\s\"']*)",
                RegexOption.IGNORE_CASE,
            )
        }

        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                // Clica em qualquer botão que possa iniciar o vídeo
                document.querySelectorAll('button, .play-button, .play, .jw-play, .vjs-play-control, .player-button-container, .downloader-button').forEach(el => {
                    try { el.click(); } catch (e) {}
                });

                // Tenta iniciar players conhecidos
                try { jwplayer(0).play(); } catch (e) {}
                try { videojs.getPlayers().forEach(p => p.play()); } catch (e) {}

                // Tenta obter src de tags de vídeo
                document.querySelectorAll('video, source').forEach(el => {
                    var src = el.src || el.getAttribute('src');
                    if (src) {
                        window.location.href = src;
                    }
                });

                // Tenta links de download
                document.querySelectorAll('a[href*=".mp4"], a[href*=".m3u8"], a[href*="videoplayback"]').forEach(el => {
                    var href = el.href;
                    if (href) {
                        window.location.href = href;
                    }
                });
            }, 1500)
            """.trimIndent()
        }
    }
}
