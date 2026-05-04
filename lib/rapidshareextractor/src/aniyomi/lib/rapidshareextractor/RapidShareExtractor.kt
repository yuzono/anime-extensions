package aniyomi.lib.rapidshareextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

class RapidShareExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val context: Application? = null,
) {
    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }

    private val tag by lazy { javaClass.simpleName }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun encDecHeaders(url: String): Headers {
        val referer = headers["Referer"] ?: url
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        return headers.newBuilder().apply {
            set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            set("Accept", "application/json, text/plain, */*")
            origin?.let { set("Origin", it) }
            set("Referer", referer)
            set("Sec-Fetch-Dest", "empty")
            set("Sec-Fetch-Mode", "cors")
            set("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private suspend fun unwrapIframeUrl(url: String): String {
        try {
            val parsedUrl = url.toHttpUrl()
            val iframeHeaders = headers.newBuilder().apply {
                set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                set("Referer", url)
            }.build()

            val html = client.newCall(GET(url, iframeHeaders))
                .awaitSuccess()
                .bodyString()

            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']*(?:/e/|rapidshare)[^"']*)["']""", RegexOption.IGNORE_CASE)
            var realUrl = iframeRegex.find(html)?.groupValues?.getOrNull(1)

            if (!realUrl.isNullOrBlank()) {
                val baseUrl = "${parsedUrl.scheme}://${parsedUrl.host}"
                realUrl = UrlUtils.fixUrl(realUrl, baseUrl)

                if (!realUrl.isNullOrBlank()) {
                    Log.d(tag, "Unwrapped iframe via OkHttp: $realUrl")
                    return realUrl
                }
            }
        } catch (_: Exception) {
            Log.d(tag, "OkHttp unwrap failed (Cloudflare block), falling back to WebView...")
        }

        if (context != null) {
            Log.d(tag, "Launching background WebView to bypass Cloudflare...")
            return withTimeout(15_000) {
                unwrapWithWebView(url)
            }
        }

        Log.e(tag, "Failed to unwrap iframe. Blocked by Turnstile.")
        throw Exception("Server is protected by Cloudflare Turnstile. Cannot extract video.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun unwrapWithWebView(url: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context!!).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = headers["User-Agent"]
                        ?: DEFAULT_USER_AGENT

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (loadedUrl.contains("/cdn-cgi/")) return

                            view.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        var iframe = document.querySelector('iframe[src]');
                                        if (iframe && (iframe.src.includes('/e/') || iframe.src.includes('rapidshare'))) {
                                            return iframe.src;
                                        }
                                    } catch(e) {}
                                    return '';
                                })();
                                """.trimIndent(),
                            ) { result ->
                                val extractedUrl = result?.replace("\\\"", "\"")?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }

                                if (extractedUrl != null) {
                                    view.destroy()
                                    if (continuation.isActive) {
                                        continuation.resume(extractedUrl)
                                    }
                                }
                            }
                        }
                    }
                    loadUrl(url)
                }

                continuation.invokeOnCancellation {
                    try {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun videosFromUrl(url: String, prefix: String, preferredLang: String): List<Video> {
        val parsedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT

        // ==========================================
        // 1. UNWRAP IFRAME (if needed)
        // ==========================================
        if (parsedUrl.pathSegments.firstOrNull() == "iframe") {
            Log.d(tag, "Detected iframe wrapper. Attempting to unwrap...")
            val unwrappedUrl = unwrapIframeUrl(url)
            Log.d(tag, "Unwrapped real RapidShare URL: $unwrappedUrl")
            return videosFromUrl(unwrappedUrl, prefix, preferredLang)
        }

        // ==========================================
        // 2. NORMAL RAPIDSHARE LOGIC
        // ==========================================
        val rapidUrl = url.toHttpUrl()
        val token = rapidUrl.pathSegments.last()
        val subtitleUrl = rapidUrl.queryParameter("sub.list")
        // Dynamic base URL
        val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
        val mediaUrl = "$baseUrl/media/$token"

        val mediaHeaders = headers.newBuilder().apply {
            set("User-Agent", userAgent)
            set("Accept", "application/json, text/plain, */*")
            set("X-Requested-With", "XMLHttpRequest")
            set("Referer", url)
        }.build()

        val encryptedResult = try {
            client.newCall(GET(mediaUrl, mediaHeaders))
                .awaitSuccess().use {
                    it.parseAs<EncryptedRapidResponse>().result
                }
        } catch (_: Exception) {
            return emptyList()
        }

        // Uses keiyoushi.utils.toRequestBody exactly like MegaUpExtractor
        val decryptionBody = buildJsonObject {
            put("text", encryptedResult)
            put("agent", userAgent)
        }.toRequestBody()

        val rapidResult = try {
            client.newCall(POST("https://enc-dec.app/api/dec-rapid", body = decryptionBody, headers = encDecHeaders(url)))
                .awaitSuccess().use {
                    it.parseAs<RapidDecryptResponse>().result
                }
        } catch (_: Exception) {
            return emptyList()
        }

        val subtitleList = try {
            if (subtitleUrl != null) {
                getSubtitles(subtitleUrl, baseUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { Track(it.file, it.label!!) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val videoSources = rapidResult.sources
        return videoSources.flatMap { source ->
            val videoUrl = source.file
            when {
                videoUrl.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "$baseUrl/",
                        videoNameGen = { quality -> "$prefix - $quality" },
                        subtitleList = subLangSelect(subtitleList, preferredLang),
                    )
                }

                else -> emptyList()
            }
        }
    }

    private suspend fun getSubtitles(url: String, baseUrl: String): List<Track> {
        val subHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        return try {
            client.newCall(GET(url, subHeaders))
                .awaitSuccess().use {
                    it.parseAs<List<RapidShareTrack>>()
                }
                .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                .map { Track(it.file, it.label!!) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Puts the preferred language subtitle first in the list.
     * The player will likely default to the first subtitle.
     */
    private fun subLangSelect(tracks: List<Track>, language: String): List<Track> = tracks.sortedByDescending { it.lang.contains(language, true) }
}

// ============================== RapidShare Responses ==============================

@Serializable
data class EncryptedRapidResponse(
    val result: String,
)

@Serializable
data class RapidDecryptResponse(
    val status: Int,
    val result: RapidShareResult,
)

@Serializable
data class RapidShareResult(
    val sources: List<RapidShareSource> = emptyList(),
    val tracks: List<RapidShareTrack> = emptyList(),
)

@Serializable
data class RapidShareSource(
    val file: String,
)

@Serializable
data class RapidShareTrack(
    val file: String,
    val label: String? = null,
    val kind: String,
)
