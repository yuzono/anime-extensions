package eu.kanade.tachiyomi.animeextension.pt.animeshentaibiz.extractors

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Extrai vídeos de uma URL de iframe, tentando primeiro uma extração direta via OkHttp e Jsoup.
     * Se não encontrar, recorre ao WebView com script de clique.
     */
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
        val prefix = name ?: "Player"

        // 1) Tenta extração direta (funciona para Blogger, por exemplo)
        runCatching {
            val request = Request.Builder()
                .url(origRequestUrl)
                .headers(origRequestHeader)
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val directVideos = extractVideosFromHtml(html, prefix, origRequestUrl, origRequestHeader)
            if (directVideos.isNotEmpty()) {
                return directVideos
            }
        }

        // 2) Fallback para WebView
        return extractVideosWithWebView(origRequestUrl, origRequestHeader, prefix)
    }

    private fun extractVideosFromHtml(
        html: String,
        prefix: String,
        referer: String,
        headers: Headers,
    ): List<Video> {
        val videos = mutableListOf<Video>()

        // Regex para googlevideo (mp4 do Blogger)
        val googlevideoRegex = Regex(
            """https?://[^"'\\s<>]+googlevideo\.com/videoplayback[^"'\\s<>]*""",
            RegexOption.IGNORE_CASE,
        )
        googlevideoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
            val itag = Regex("""[?&]itag=(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: "?"
            val qualityLabel = qualityFromItag(itag)
            val videoHeaders = Headers.headersOf("Referer", referer)
            videos.add(Video(videoUrl, "$prefix - $qualityLabel", videoUrl, videoHeaders))
        }

        // Regex para m3u8
        val m3u8Regex = Regex(
            """https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""",
            RegexOption.IGNORE_CASE,
        )
        m3u8Regex.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
            val videoHeaders = Headers.headersOf("Referer", referer)
            videos.add(Video(videoUrl, "$prefix - HLS", videoUrl, videoHeaders))
        }

        // Regex para mp4 genérico
        if (videos.isEmpty()) {
            val mp4Regex = Regex(
                """https?://[^"'\\s<>]+\.mp4[^"'\\s<>]*""",
                RegexOption.IGNORE_CASE,
            )
            mp4Regex.findAll(html).forEach { match ->
                val videoUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
                val videoHeaders = Headers.headersOf("Referer", referer)
                videos.add(Video(videoUrl, prefix, videoUrl, videoHeaders))
            }
        }

        return videos
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun extractVideosWithWebView(origRequestUrl: String, origRequestHeader: Headers, prefix: String): List<Video> {
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
                    Log.d(tag, "Page loaded: $url")
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    super.onLoadResource(view, url)
                    Log.d(tag, "Resource loaded: $url")
                    if (url != null && VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
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

        if (resultUrl.isEmpty()) {
            Log.w(tag, "Nenhum vídeo encontrado via WebView para $origRequestUrl")
            return emptyList()
        }

        return when {
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                PlaylistUtils(client, origRequestHeader).extractFromHls(
                    resultUrl,
                    origRequestUrl,
                    videoNameGen = { "$prefix: $it" },
                )
            }
            "mpd" in resultUrl -> {
                Log.d(tag, "mpd URL: $resultUrl")
                PlaylistUtils(client, origRequestHeader).extractFromDash(
                    resultUrl,
                    { "$prefix: $it" },
                    referer = origRequestUrl,
                )
            }
            else -> {
                Log.d(tag, "Vídeo encontrado: $resultUrl")
                val videoHeaders = Headers.headersOf("Referer", origRequestUrl)
                listOf(Video(resultUrl, "$prefix: MP4", resultUrl, videoHeaders))
            }
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 20L

        private val VIDEO_REGEX by lazy {
            Regex(
                "(https?://[^\\s\"']*\\.(?:mp4|m3u8|mpd)(?:\\?[^\\s\"']*)?)|(https?://[^\\s\"']*googlevideo\\.com/videoplayback[^\\s\"']*)",
                RegexOption.IGNORE_CASE,
            )
        }

        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                // Clica em qualquer elemento com classes ou ids que contenham play/video
                var all = document.querySelectorAll('[class*="play"], [class*="video"], [id*="play"], [id*="video"]');
                for (var i = 0; i < all.length; i++) {
                    try { all[i].click(); } catch (e) {}
                }

                // Tenta players conhecidos
                try { jwplayer(0).play(); } catch (e) {}
                try { videojs.getPlayers().forEach(p => p.play()); } catch (e) {}

                // Tenta obter src de tags de vídeo
                var videos = document.querySelectorAll('video, source');
                for (var j = 0; j < videos.length; j++) {
                    var src = videos[j].src || videos[j].getAttribute('src');
                    if (src && src.startsWith('http')) {
                        window.location.href = src;
                    }
                }

                // Tenta links de download
                var links = document.querySelectorAll('a[href*=".mp4"], a[href*=".m3u8"], a[href*="videoplayback"]');
                for (var k = 0; k < links.length; k++) {
                    var href = links[k].href;
                    if (href) {
                        window.location.href = href;
                    }
                }
            }, 1500)
            """.trimIndent()
        }
    }

    private fun qualityFromItag(itag: String): String = when (itag) {
        "17" -> "144p"
        "18" -> "360p"
        "22" -> "720p"
        "37" -> "1080p"
        "36" -> "180p"
        "43" -> "360p WebM"
        "44" -> "480p WebM"
        "45" -> "720p WebM"
        else -> "Qualidade $itag"
    }
}
