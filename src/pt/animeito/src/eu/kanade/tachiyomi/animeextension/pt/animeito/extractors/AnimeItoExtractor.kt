package eu.kanade.tachiyomi.animeextension.pt.animeito.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AnimeItoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()

        val encodedScript = playerDoc.selectFirst("script:containsData(TextDecoder)")?.data()
        val script = if (encodedScript != null) {
            Log.d(tag, "TextDecoder script found, length=${encodedScript.length}")
            decodeTextDecoderScript(encodedScript).takeIf { it.isNotEmpty() }
        } else {
            null
        } ?: run {
            Log.w(tag, "TextDecoder script not found, falling back to inline player script")
            playerDoc.selectFirst("script:containsData(AniDrivePlayerConfig)")?.data()
                ?: playerDoc.selectFirst("script:containsData(const player)")?.data()
                ?: return videosFromWebView(url).also {
                    Log.e(tag, "No player script found, WebView fallback returned ${it.size} videos")
                }
        }

        val videos = extractVideosFromScript(script)
        if (videos.isNotEmpty()) {
            return videos
        }

        Log.w(tag, "No videos extracted from decoded script, falling back to WebView")
        return videosFromWebView(url)
    }

    private fun extractVideosFromScript(script: String): List<Video> {
        val sourcesBlock = extractSourcesBlock(script)
        if (sourcesBlock != null) {
            val directVideos = videosFromSourceText(sourcesBlock)
            if (directVideos.isNotEmpty()) {
                return directVideos
            }
        }

        if ("videoplayback" in script) {
            return videosFromSourceText(script)
        }

        val masterPlaylistUrl = extractHlsUrl(script)
        if (masterPlaylistUrl != null) {
            Log.d(tag, "HLS master URL: $masterPlaylistUrl")
            return playlistUtils.extractFromHls(masterPlaylistUrl, videoNameGen = { "Animei.to - $it" })
        }

        return emptyList()
    }

    private fun videosFromSourceText(text: String): List<Video> {
        return SOURCE_ENTRY_REGEX.findAll(text)
            .mapNotNull { match ->
                val videoUrl = match.groupValues[1]
                val quality = match.groupValues[2]
                if (!isPlayableUrl(videoUrl)) {
                    return@mapNotNull null
                }
                Log.d(tag, "Found video: $quality - ${videoUrl.take(80)}...")
                Video(videoUrl, "Animei.to - $quality", videoUrl, headers)
            }
            .toList()
    }

    private fun extractSourcesBlock(script: String): String? {
        val markerIndex = script.indexOf(SOURCES_MARKER)
        if (markerIndex < 0) {
            return null
        }

        val arrayStart = markerIndex + SOURCES_MARKER.length - 1
        var depth = 0
        for (index in arrayStart until script.length) {
            when (script[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return script.substring(arrayStart, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun extractHlsUrl(script: String): String? = HLS_FILE_REGEX.find(script)
        ?.groupValues
        ?.get(1)
        ?.let(::normalizeStreamUrl)

    private fun normalizeStreamUrl(url: String): String = if (url.startsWith("//")) {
        "https:$url"
    } else {
        url
    }

    private fun isPlayableUrl(url: String): Boolean = url.contains("videoplayback") || url.contains(".m3u8") || url.contains(".mp4")

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun videosFromWebView(url: String): List<Video> = withContext(Dispatchers.IO) {
        synchronized(WEB_VIEW_LOCK) {
            videosFromWebViewInternal(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun videosFromWebViewInternal(url: String): List<Video> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var jsResult = ""
        val loadHeaders = headers.toMultimap().mapValues { entry -> entry.value.getOrNull(0) ?: "" }
        val jsInterface = PlayerJSInterface(latch) { jsResult = it }

        try {
            handler.post {
                val newView = WebView(applicationContext)
                webView = newView
                with(newView.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = headers["User-Agent"]
                }
                newView.addJavascriptInterface(jsInterface, "Android")
                newView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        Log.d(tag, "WebView page loaded, injecting player script")
                        view?.evaluateJavascript(PLAYER_SCRIPT) {}
                    }
                }
                newView.loadUrl(url, loadHeaders)
            }

            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Log.e(tag, "WebView timed out after ${TIMEOUT_SEC}s")
            }

            return parseWebViewResult(jsResult, url)
        } catch (e: Exception) {
            Log.e(tag, "WebView extraction failed", e)
            return emptyList()
        } finally {
            val viewToDestroy = webView
            handler.post {
                viewToDestroy?.stopLoading()
                viewToDestroy?.destroy()
            }
        }
    }

    private fun parseWebViewResult(json: String, pageUrl: String): List<Video> {
        if (json.isBlank()) {
            Log.e(tag, "WebView returned empty player data")
            return emptyList()
        }

        val items = try {
            Json.parseToJsonElement(json).jsonArray
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse WebView JSON", e)
            return emptyList()
        }

        if (items.isEmpty()) {
            Log.w(tag, "WebView JSON array is empty")
            return emptyList()
        }

        val videoHeaders = buildVideoHeaders(pageUrl)
        val videos = mutableListOf<Video>()
        for (element in items) {
            val item = element.jsonObject
            val videoUrl = item["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (videoUrl == null) {
                Log.w(tag, "WebView item missing valid 'url' key: $item")
                continue
            }
            val label = item["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Video"
            val type = item["type"]?.jsonPrimitive?.content.orEmpty()

            when {
                type == "m3u8" || ".m3u8" in videoUrl -> {
                    videos += playlistUtils.extractFromHls(
                        videoUrl,
                        pageUrl,
                        videoNameGen = { "Animei.to - $it" },
                    )
                }
                else -> {
                    Log.d(tag, "WebView video: $label - ${videoUrl.take(80)}...")
                    videos += Video(videoUrl, "Animei.to - $label", videoUrl, videoHeaders)
                }
            }
        }

        if (videos.isEmpty()) {
            Log.w(tag, "WebView JSON parsed but no valid entries found (expected keys: url, label, type)")
        }

        return videos
    }

    private fun buildVideoHeaders(pageUrl: String): Headers {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return headers
        val siteOrigin = "${httpUrl.scheme}://${httpUrl.host}"
        return headers.newBuilder()
            .set("Referer", "$siteOrigin/")
            .set("Origin", siteOrigin)
            .build()
    }

    private fun decodeTextDecoderScript(script: String): String {
        val mainContent = script.substringBefore("})();")

        val funcCallStart = mainContent.indexOf("([\"")
        val funcCallEnd = mainContent.lastIndexOf("\");")
        if (funcCallStart < 0 || funcCallEnd < 0 || funcCallEnd <= funcCallStart + 1) {
            Log.w(tag, "Could not find function call boundaries: start=$funcCallStart end=$funcCallEnd")
            return ""
        }

        val params = mainContent.substring(funcCallStart + 2, funcCallEnd + 1)

        val array1End = ARRAY_SEP_REGEX.find(params)?.range?.first ?: -1
        if (array1End < 0) {
            Log.w(tag, "Could not find first array boundary")
            return ""
        }

        val array2End = KEY_SEP_REGEX.find(params)?.range?.first ?: -1
        if (array2End < 0) {
            Log.w(tag, "Could not find second array boundary")
            return ""
        }

        val firstArrayStr = params.substring(0, array1End + 1)
        val secondArrayOpen = params.indexOf('[', array1End + 1)
        if (secondArrayOpen < 0 || secondArrayOpen >= array2End) {
            Log.w(tag, "Could not find second array brackets")
            return ""
        }
        val secondArrayStr = params.substring(secondArrayOpen, array2End + 1)

        val keyQuoteStart = params.indexOf('"', array2End + 1)
        if (keyQuoteStart < 0) {
            Log.w(tag, "Could not find key string start")
            return ""
        }
        val keyQuoteEnd = params.indexOf('"', keyQuoteStart + 1)
        if (keyQuoteEnd < 0) {
            Log.w(tag, "Could not find key string end")
            return ""
        }
        val keyStr = params.substring(keyQuoteStart + 1, keyQuoteEnd)

        val strings = STRINGS_REGEX
            .findAll(firstArrayStr)
            .map { it.groupValues[1] }
            .toList()

        val indices = secondArrayStr
            .removeSurrounding("[", "]")
            .split(",")
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) {
                    return@mapNotNull null
                }
                trimmed.toIntOrNull() ?: run {
                    Log.w(tag, "Skipping non-numeric index token while decoding: '$trimmed'")
                    null
                }
            }

        if (indices.isEmpty()) {
            Log.w(tag, "No valid indices found")
            return ""
        }

        val joined = indices.joinToString("") { index -> strings.getOrNull(index).orEmpty() }
        if (joined.isEmpty()) {
            Log.w(tag, "Joined base64 is empty")
            return ""
        }

        val decodedData = runCatching { Base64.decode(joined, Base64.DEFAULT) }.getOrNull() ?: return ""
        val key = runCatching { Base64.decode(keyStr, Base64.DEFAULT) }.getOrNull() ?: return ""

        if (decodedData.isEmpty() || key.isEmpty()) {
            Log.w(tag, "Decoded data or key is empty")
            return ""
        }

        val result = ByteArray(decodedData.size) { i ->
            (decodedData[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return String(result, Charsets.UTF_8)
    }

    private class PlayerJSInterface(
        private val latch: CountDownLatch,
        private val callback: (String) -> Unit,
    ) {
        private val delivered = AtomicBoolean(false)

        @JavascriptInterface
        fun onResult(json: String) {
            if (delivered.compareAndSet(false, true)) {
                callback(json)
                latch.countDown()
            }
        }
    }

    companion object {
        private val WEB_VIEW_LOCK = Any()
        private const val TIMEOUT_SEC: Long = 15
        private const val SOURCES_MARKER = "\"sources\":["
        private val ARRAY_SEP_REGEX = Regex("""\],\s*\[""")
        private val KEY_SEP_REGEX = Regex("""\],\s*"""")
        private val STRINGS_REGEX = Regex("\"([^\"]*?)\"")
        private val SOURCE_ENTRY_REGEX = Regex(""""file":"([^"]+)","label":"([^"]+)"""")
        private val HLS_FILE_REGEX = Regex(""""file":"((?:https?:)?//[^"]+\.m3u8[^"]*)"""")

        private val PLAYER_SCRIPT by lazy {
            """
            var playerIntervalId = setInterval(function() {
                function deliverResults(results) {
                    if (results.length > 0 && window.Android && typeof Android.onResult === 'function') {
                        Android.onResult(JSON.stringify(results));
                        clearInterval(playerIntervalId);
                    }
                }

                try {
                    var config = window.AniDrivePlayerConfig;
                    if (config && config.sources && config.sources.length > 0) {
                        deliverResults(config.sources.map(function(source) {
                            var type = source.type || '';
                            if (type.indexOf('mpegurl') >= 0 || type.indexOf('m3u8') >= 0 || (source.file || '').indexOf('.m3u8') >= 0) {
                                type = 'm3u8';
                            } else {
                                type = 'mp4';
                            }
                            return { url: source.file, label: source.label || '', type: type };
                        }));
                        return;
                    }

                    var player = jwplayer(0);
                    if (player && player.getPlaylistItem) {
                        var item = player.getPlaylistItem();
                        if (item && item.sources && item.sources.length > 0) {
                            deliverResults(item.sources.map(function(source) {
                                var type = source.type || '';
                                if (type.indexOf('mpegurl') >= 0 || type.indexOf('m3u8') >= 0 || (source.file || '').indexOf('.m3u8') >= 0) {
                                    type = 'm3u8';
                                } else {
                                    type = 'mp4';
                                }
                                return { url: source.file, label: source.label || '', type: type };
                            }));
                            return;
                        }
                    }

                    var playButton = document.querySelector('#player-button-container, .jw-display-icon-container, .jw-icon-display');
                    if (playButton) {
                        playButton.click();
                    }
                } catch (error) {}
            }, 2500);
            """.trimIndent()
        }
    }
}
