package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Extractor for the Alloha player embedded on yummyani.me (`alloha.yani.tv`).
 *
 * The modern Alloha player builds its HLS URL client-side and protects segment
 * requests with rotating signed values, so there is nothing useful in the static
 * iframe HTML. A headless [WebView] loads the player, a JavaScript probe finds the
 * player instance, enumerates available qualities and switches through them one by
 * one, while [WebViewClient.shouldInterceptRequest] captures the resulting HLS
 * request URLs. The probe script is kept in sync with the YummyTV app's
 * AllohaExtractor, which is the reference implementation.
 *
 * If the player object cannot be reached (the embed may redirect the iframe to
 * another origin, making it cross-origin from the wrapper page), the extractor
 * falls back to the Playerjs postMessage API, which works across origins.
 *
 * This extractor is intended to be called lazily from `fetchVideoUrl` (i.e. when
 * the user actually starts playback), not for every dubbing while building the
 * video list — a WebView round-trip takes 5-25 seconds. Up to
 * [MAX_PARALLEL_EXTRACTIONS] extractions run concurrently (the app batch-resolves
 * all dubbings of the episode at player open); concurrent requests for the SAME
 * dubbing are de-duplicated via [inFlight].
 */
@SuppressLint("SetJavaScriptEnabled")
class AllohaExtractor(private val client: OkHttpClient) {

    private val context: Application by injectLazy()

    // Resolved streams per player URL. The app re-resolves every unresolved video of
    // the episode each time the player opens, so without a cache each reopen costs a
    // full WebView round-trip per dubbing.
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()

    // Verification must mirror the video player's conditions. Aniyomi's OkHttp client
    // shares the WebView cookie jar, so right after an extraction it silently sends the
    // CDN cookies the WebView just received — while mpv plays with nothing but the URL
    // and the static headers. A cookie-less client keeps the check honest.
    private val verifyClient: OkHttpClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    // De-duplicates concurrent extractions of the same dubbing: the app resolves all
    // episode videos in one batch, and two parallel WebView runs for one dubbing would
    // waste time and consume the short-lived token twice.
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<List<Video>>>()

    fun videosFromUrl(
        iframeUrl: String,
        @Suppress("UNUSED_PARAMETER") siteUrl: String,
        prefix: String = "Alloha",
        episodePlaybackIdentity: String? = null,
        cacheKey: String? = null,
    ): List<Video> {
        val playerUrl = normalizeUrl(iframeUrl)
        // The iframe URL carries a fresh short-lived token on every call, so it can no
        // longer serve as the cache key — the caller passes a stable per-episode/dubbing
        // key instead, otherwise every playback start costs a full WebView round-trip.
        val key = cacheKey ?: playerUrl
        cachedVideos(key)?.let { return it }

        val future = CompletableFuture<List<Video>>()
        inFlight.putIfAbsent(key, future)?.let { running ->
            // The same dubbing is already being extracted — wait for that result
            // instead of starting a second WebView for it.
            return runCatching { running.get(TIMEOUT_MS * 3, TimeUnit.MILLISECONDS) }
                .getOrDefault(emptyList())
        }
        try {
            // Up to MAX_PARALLEL_EXTRACTIONS WebViews run at once — enough to speed up
            // multi-dubbing episodes without exhausting memory.
            EXTRACTION_SEMAPHORE.acquire()
            val videos = try {
                cachedVideos(key)
                    ?: extract(playerUrl, prefix, episodePlaybackIdentity).also {
                        if (it.isNotEmpty()) {
                            cache[key] = System.currentTimeMillis() to it
                        }
                    }
            } finally {
                EXTRACTION_SEMAPHORE.release()
            }
            future.complete(videos)
            return videos
        } catch (e: Throwable) {
            future.complete(emptyList())
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    private fun cachedVideos(playerUrl: String): List<Video>? = cache[playerUrl]?.let { (timestamp, videos) ->
        videos.takeIf { System.currentTimeMillis() - timestamp < CACHE_TTL_MS }
    }

    private fun extract(
        playerUrl: String,
        prefix: String,
        episodePlaybackIdentity: String?,
    ): List<Video> {
        val playbackHeaders = playbackHeaders(playerUrl)

        val future = CompletableFuture<List<String>>()
        val handler = Handler(Looper.getMainLooper())

        var webView: WebView? = null
        var delivered = false
        var probeAttempts = 0
        var qualityProbeFinished = false
        var emptyGraceScheduled = false
        lateinit var timeoutRunnable: Runnable

        // Every .m3u8 URL in order of appearance. The first one is usually the master
        // playlist, which the CDN rejects outside the WebView; the playlists requested
        // after choosing a quality are the playable media playlists. Candidates are
        // verified back-to-front in [pickWorkingUrl].
        val capturedUrls = LinkedHashSet<String>()

        // Subtitle files (.vtt/.srt) the player requests — attached to the resulting
        // Video as selectable subtitle tracks.
        val capturedSubs = LinkedHashSet<String>()
        var settleRunnable: Runnable? = null

        fun deliver(urls: List<String>) {
            if (delivered) return
            delivered = true
            val wv = webView
            webView = null
            settleRunnable?.let(handler::removeCallbacks)
            handler.removeCallbacks(timeoutRunnable)
            handler.post {
                wv?.run {
                    runCatching { stopLoading() }
                    runCatching { destroy() }
                }
            }
            future.complete(urls)
        }

        fun deliverCaptured() {
            if (capturedUrls.isEmpty()) return
            deliver(capturedUrls.toList())
        }

        // Deliver only after the quality probe has finished its full cycle: the first
        // captured URL is the master playlist, which the CDN rejects outside the
        // WebView (HTTP 403) — delivering on the first capture would hand out exactly
        // that URL. When the probe has finished but nothing was captured, give
        // playback one more grace period and then fail fast instead of holding the
        // caller until the full timeout.
        fun scheduleDelivery() {
            if (!qualityProbeFinished) return
            settleRunnable?.let(handler::removeCallbacks)
            val runnable = Runnable {
                if (capturedUrls.isNotEmpty()) {
                    deliverCaptured()
                } else if (qualityProbeFinished && !emptyGraceScheduled) {
                    emptyGraceScheduled = true
                    handler.postDelayed(
                        { if (!delivered && capturedUrls.isEmpty()) deliver(emptyList()) },
                        EMPTY_GRACE_MS,
                    )
                }
            }
            settleRunnable = runnable
            handler.postDelayed(runnable, STREAM_SETTLE_DELAY_MS)
        }

        fun captureStream(url: String) {
            capturedUrls.add(url)
            scheduleDelivery()
        }

        timeoutRunnable = Runnable {
            deliverCaptured()
            deliver(emptyList())
        }

        val bridge = object {
            @JavascriptInterface
            fun quality(@Suppress("UNUSED_PARAMETER") label: String) {
                // The probe reports the label of each quality it switches to. Only a
                // single (best) stream is delivered here, so labels are not used, but
                // the method must exist because the shared probe script calls it.
            }

            @JavascriptInterface
            fun done() {
                handler.post {
                    qualityProbeFinished = true
                    scheduleDelivery()
                }
            }
        }

        fun runQualityProbe(view: WebView) {
            if (delivered || probeAttempts >= MAX_PROBE_ATTEMPTS) return
            probeAttempts += 1
            view.evaluateJavascript(qualityProbeScript()) { result ->
                if (delivered || !result.contains("no-player")) return@evaluateJavascript
                if (probeAttempts < MAX_PROBE_ATTEMPTS) {
                    handler.postDelayed({ runQualityProbe(view) }, PROBE_RETRY_DELAY_MS)
                } else {
                    // The player lives in a cross-origin (redirected) iframe or never
                    // exposed a JS object. Playerjs still accepts postMessage commands
                    // across origins, so start playback and cycle qualities blindly.
                    view.evaluateJavascript(postMessageFallbackScript(), null)
                }
            }
        }

        handler.post {
            val newView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    allowFileAccess = false
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = CHROME_UA
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        when {
                            isStreamUrl(url) -> handler.post { captureStream(url) }
                            isSubtitleUrl(url) -> handler.post { capturedSubs.add(url) }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (probeAttempts == 0) {
                            runQualityProbe(view)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // Sub-resource errors are common (ads, analytics) — ignore and
                        // let the timeout decide.
                    }
                }

                addJavascriptInterface(bridge, "AllohaBridge")

                // Wrap in an iframe so the Alloha page sees isFramed=true and doesn't
                // remove itself.
                loadDataWithBaseURL(
                    ALLOHA_ORIGIN,
                    wrapperHtml(playerUrl),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            webView = newView
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        }

        return try {
            val candidates = future.get(TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS)
            val streamUrl = pickWorkingUrl(candidates, playbackHeaders)
            if (streamUrl == null) {
                emptyList()
            } else {
                listOf(
                    Video(
                        url = episodePlaybackIdentity ?: playerUrl,
                        quality = "$prefix (Alloha)",
                        videoUrl = streamUrl,
                        headers = playbackHeaders,
                        subtitleTracks = capturedSubs.mapIndexed { index, subUrl ->
                            Track(subUrl, subtitleLabel(subUrl, index))
                        },
                    ),
                )
            }
        } catch (e: Exception) {
            future.cancel(true)
            handler.post { webView?.destroy() }
            emptyList()
        }
    }

    // ============================= Helpers ================================

    /**
     * Picks a playlist the video player will actually be able to play, checking
     * candidates from the most recent one backwards.
     *
     * All checks run without cookies and with the exact static headers the player
     * sends. A candidate counts as playable only when its media playlist AND its first
     * segment respond successfully under those conditions; a master playlist is first
     * resolved to its best (highest-bandwidth) variant, since the master itself is
     * often rejected by the CDN outside the WebView.
     */
    private fun pickWorkingUrl(candidates: List<String>, headers: Headers): String? {
        Log.i(TAG, "verifying ${candidates.size} captured playlist(s)")
        for (url in candidates.asReversed()) {
            resolvePlayable(url, headers, depth = 0)?.let {
                Log.i(TAG, "picked verified stream: $it")
                return it
            }
        }
        // Nothing verified — return the most recent capture anyway. A single failing
        // entry is far better than an exception: with the pre-Hoster API one thrown
        // error hides ALL videos of the episode ("No available videos").
        Log.w(TAG, "no candidate verified, falling back to last capture")
        return candidates.lastOrNull()
    }

    private fun resolvePlayable(url: String, headers: Headers, depth: Int): String? {
        if (depth > 2) return null
        val body = runCatching {
            verifyClient.newCall(GET(url, headers)).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    Log.i(TAG, "playlist HTTP ${response.code}: $url")
                    null
                }
            }
        }.getOrNull() ?: return null
        if (!body.contains("#EXTM3U")) return null

        val base = url.toHttpUrlOrNull() ?: return null
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            // Master playlist — descend into the best variant and verify it instead.
            val bestVariant = lines.zipWithNext()
                .filter { (info, uri) -> info.startsWith("#EXT-X-STREAM-INF") && !uri.startsWith("#") }
                .maxByOrNull { (info, _) ->
                    BANDWIDTH_REGEX.find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                ?.second ?: return null
            val variantUrl = base.resolve(bestVariant)?.toString() ?: return null
            return resolvePlayable(variantUrl, headers, depth + 1)
        }

        // Media playlist — the playlist alone succeeding is not enough, segment
        // requests are what actually fail with 403 in the player.
        val firstSegment = lines.firstOrNull { !it.startsWith("#") } ?: return null
        val segmentUrl = base.resolve(firstSegment)?.toString() ?: return null
        val segmentCode = runCatching {
            val rangeHeaders = headers.newBuilder().add("Range", "bytes=0-1023").build()
            verifyClient.newCall(GET(segmentUrl, rangeHeaders)).execute().use { it.code }
        }.getOrDefault(-1)
        Log.i(TAG, "segment check HTTP $segmentCode for playlist: $url")
        return if (segmentCode in 200..299) url else null
    }

    private fun isSubtitleUrl(url: String): Boolean {
        val path = url.substringBefore('?')
        if (!path.endsWith(".vtt") && !path.endsWith(".srt")) return false
        return !url.contains("imasdk") &&
            !url.contains("doubleclick") &&
            !url.contains("googlesyndication") &&
            !url.contains("/ads/")
    }

    private fun subtitleLabel(url: String, index: Int): String {
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return name.ifBlank { "Субтитры ${index + 1}" }
    }

    private fun isStreamUrl(url: String): Boolean {
        if (!url.contains(".m3u8")) return false
        // Skip ad/analytics playlists (without tripping on substrings like "anima"/"image").
        return !url.contains("imasdk") &&
            !url.contains("doubleclick") &&
            !url.contains("googlesyndication") &&
            !url.contains("/ads/")
    }

    private fun wrapperHtml(iframeUrl: String): String {
        val escaped = iframeUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """<!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <style>*{margin:0;padding:0}html,body,iframe{width:100%;height:100%;border:none;background:#000}</style>
            </head><body>
            <iframe src="$escaped" allow="autoplay;fullscreen" allowfullscreen></iframe>
            </body></html>"""
    }

    private fun normalizeUrl(url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> "https://$url"
    }

    /**
     * JavaScript probe executed inside the wrapper WebView. Ported from the YummyTV
     * app's AllohaExtractor.
     *
     * It first finds the real player inside the nested Alloha iframe, then normalizes
     * quality labels from DOM controls, Allplay config fields, HLS sources, and
     * PlayerJS-like APIs. Only known video quality numbers are kept because the player
     * UI also contains numeric values for speed, subtitles, and styling controls. Each
     * selected quality triggers a new HLS request that is captured by the WebView client.
     */
    private fun qualityProbeScript(): String = """
        (function(){
            function findPlayer(win){
                var names = ["player", "Player", "playerjs", "pl"];
                for (var i = 0; i < names.length; i++) {
                    try {
                        var candidate = win[names[i]];
                        if (isPlayer(candidate)) return candidate;
                    } catch(e) {}
                }
                try {
                    var el = win.document && win.document.getElementById("player");
                    if (isPlayer(el)) return el;
                } catch(e) {}
                for (var key in win) {
                    try {
                        var value = win[key];
                        if (isPlayer(value)) return value;
                    } catch(e) {}
                }
                return null;
            }
            function isPlayer(value) {
                return value && (
                    typeof value.api === "function" ||
                    (value.media && typeof value.media.querySelectorAll === "function" && "quality" in value)
                );
            }
            function labelOf(item) {
                if (typeof item === "string") return item;
                if (typeof item === "number") return String(item);
                if (item && typeof item === "object") {
                    return item.title || item.label || item.name || item.quality || item.text || item.value || item.size || "";
                }
                return String(item || "");
            }
            function addLabels(target, raw) {
                if (!raw) return;
                if (typeof raw === "string") {
                    raw.split(",").forEach(function(item){ addLabels(target, item); });
                    return;
                }
                if (Array.isArray(raw)) {
                    raw.forEach(function(item){ addLabels(target, item); });
                    return;
                }
                if (typeof raw.length === "number" && typeof raw !== "function") {
                    for (var i = 0; i < raw.length; i++) addLabels(target, raw[i]);
                    return;
                }
                var label = labelOf(raw);
                if (label) target.push(label);
            }
            function uniqueQualityLabels(labels) {
                var known = {
                    240: true,
                    360: true,
                    480: true,
                    540: true,
                    720: true,
                    1080: true,
                    1440: true,
                    2160: true
                };
                var byQuality = {};
                labels.forEach(function(label){
                    label = String(label || "").replace(/<[^>]+>/g, "").trim();
                    if (!label || label.indexOf("<<<") === 0 || label === "[object Object]") return;
                    var match = label.match(/\d{3,4}/);
                    if (!match) return;
                    var quality = parseInt(match[0], 10);
                    if (known[quality]) byQuality[quality] = String(quality);
                });
                return Object.keys(byQuality)
                    .map(function(value){ return parseInt(value, 10); })
                    .filter(function(value){ return !!value; })
                    .sort(function(a, b){ return a - b; })
                    .map(function(value){ return String(value); });
            }
            function normalizeApiQualities(raw) {
                var result = [];
                addLabels(result, raw);
                return uniqueQualityLabels(result);
            }
            function normalizeDomQualities(win, player) {
                var roots = [];
                if (player && player.media) roots.push(player.media);
                if (win.document) roots.push(win.document);

                var labels = [];
                for (var r = 0; r < roots.length; r++) {
                    var nodes = [];
                    try {
                        nodes = roots[r].querySelectorAll("source[size][src]");
                    } catch(e) {}
                    for (var i = 0; i < nodes.length; i++) {
                        labels.push(nodes[i].getAttribute("size"));
                    }

                    try {
                        nodes = roots[r].querySelectorAll(
                            "[data-allplay='quality'], [name='quality'], [role='menuitemradio'], [data-quality], [quality]"
                        );
                    } catch(e) {}
                    for (var j = 0; j < nodes.length; j++) {
                        labels.push(
                            nodes[j].getAttribute("value") ||
                            nodes[j].getAttribute("data-quality") ||
                            nodes[j].getAttribute("quality") ||
                            nodes[j].getAttribute("aria-label") ||
                            nodes[j].textContent
                        );
                    }
                }

                return uniqueQualityLabels(labels);
            }
            function normalizePlayerQualities(player) {
                var labels = [];
                try { addLabels(labels, player.config && player.config.quality && player.config.quality.options); } catch(e) {}
                try { addLabels(labels, player.options && player.options.quality); } catch(e) {}
                try { addLabels(labels, player.config && player.config.hlsSource); } catch(e) {}
                try { addLabels(labels, player.config && player.config.sources); } catch(e) {}
                try { addLabels(labels, player.sources); } catch(e) {}
                try {
                    if (player.media && typeof player.media.querySelectorAll === "function") {
                        addLabels(labels, normalizeDomQualities(window, player));
                    }
                } catch(e) {}
                return uniqueQualityLabels(labels);
            }

            var frame = document.querySelector("iframe");
            var win = frame && frame.contentWindow ? frame.contentWindow : window;
            var player = findPlayer(win);
            if (!player) {
                return "no-player";
            }

            var labels = normalizeDomQualities(win, player);
            if (!labels.length) labels = normalizePlayerQualities(player);
            var rawQualities = [];
            try {
                if (player && typeof player.api === "function") rawQualities = player.api("qualities");
            } catch(e) {}
            if (!labels.length) labels = normalizeApiQualities(rawQualities);

            var playable = [];
            for (var i = 0; i < labels.length; i++) {
                if (String(labels[i] || "").indexOf("<<<") !== 0) playable.push({ index: i, label: labels[i] });
            }

            function playCurrent() {
                try {
                    player.api("play");
                } catch(e) {}
                try {
                    var video = win.document && win.document.querySelector("video");
                    if (video) video.play().catch(function(){});
                } catch(e) {}
            }
            // Force subtitles on so their .vtt/.srt files get requested (and captured):
            // in dubbed translations subtitles are off by default.
            function enableSubtitles() {
                try {
                    if (typeof player.api === "function") player.api("subtitle", 0);
                } catch(e) {}
                try {
                    var tracks = win.document ? win.document.querySelectorAll("video track") : [];
                    for (var t = 0; t < tracks.length; t++) {
                        try { tracks[t].track.mode = "showing"; } catch(e) {}
                    }
                } catch(e) {}
                try {
                    frame.contentWindow.postMessage(JSON.stringify({api:"subtitle", value:0}), "*");
                } catch(e) {}
            }
            function switchByVideoSource(quality) {
                try {
                    var source = win.document && win.document.querySelector("source[size='" + quality + "'][src]");
                    var video = win.document && win.document.querySelector("video");
                    if (!source || !video) return false;
                    video.src = source.getAttribute("src");
                    video.load();
                    video.play().catch(function(){});
                    return true;
                } catch(e) {
                    return false;
                }
            }

            if (!playable.length) {
                playCurrent();
                enableSubtitles();
                AllohaBridge.done();
                return JSON.stringify(labels);
            }

            var step = 0;
            function switchNextQuality() {
                if (step >= playable.length) {
                    AllohaBridge.done();
                    return;
                }
                var item = playable[step++];
                AllohaBridge.quality(String(item.label || ""));
                try {
                    var quality = parseInt(item.label, 10);
                    if (quality && typeof player.quality !== "undefined") {
                        player.quality = quality;
                    } else if (typeof player.api === "function") {
                        try {
                            player.api("quality", quality || item.label);
                        } catch(e) {
                            player.api("quality", item.index);
                        }
                    } else if (quality) {
                        switchByVideoSource(quality);
                    }
                    try {
                        frame.contentWindow.postMessage(JSON.stringify({api:"quality", value:quality}), "*");
                        frame.contentWindow.postMessage(JSON.stringify({api:"quality", value:item.index}), "*");
                    } catch(e) {}
                } catch(e) {}
                playCurrent();
                enableSubtitles();
                setTimeout(switchNextQuality, $QUALITY_SWITCH_DELAY_MS);
            }
            switchNextQuality();
            return JSON.stringify(labels);
        })();
    """.trimIndent()

    private fun postMessageFallbackScript(): String = """
        (function(){
            var frame = document.querySelector("iframe");
            if (!frame || !frame.contentWindow) {
                AllohaBridge.done();
                return "no-frame";
            }
            function send(obj){
                try { frame.contentWindow.postMessage(JSON.stringify(obj), "*"); } catch(e) {}
            }
            send({api:"play"});
            send({api:"subtitle", value:0});
            var qualities = [360, 480, 720, 1080];
            var step = 0;
            function next(){
                if (step >= qualities.length) {
                    AllohaBridge.done();
                    return;
                }
                send({api:"quality", value: qualities[step]});
                send({api:"play"});
                step++;
                setTimeout(next, $QUALITY_SWITCH_DELAY_MS);
            }
            setTimeout(next, 1000);
            return "fallback";
        })();
    """.trimIndent()

    companion object {
        private const val MAX_PARALLEL_EXTRACTIONS = 3
        private val EXTRACTION_SEMAPHORE = java.util.concurrent.Semaphore(MAX_PARALLEL_EXTRACTIONS)
        private const val TIMEOUT_MS = 25_000L
        private const val MAX_PROBE_ATTEMPTS = 8
        private const val PROBE_RETRY_DELAY_MS = 500L
        private const val STREAM_SETTLE_DELAY_MS = 2_500L
        private const val QUALITY_SWITCH_DELAY_MS = 1_500L
        private const val EMPTY_GRACE_MS = 6_000L

        // Stream URLs carry short-lived JWT tokens — a long TTL would hand the player
        // an expired link. The cache only needs to cover the app's batch re-resolution
        // of all dubbings when the player (re)opens.
        private const val CACHE_TTL_MS = 2 * 60 * 1000L
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private const val TAG = "AllohaExtractor"
        private const val ALLOHA_ORIGIN = "https://alloha.yani.tv/"
        const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Headers the video player must send when requesting the extracted stream.
         * These are also the headers [pickWorkingUrl] verifies candidates with, so
         * verification tests exactly the conditions of real playback.
         *
         * They mirror what hls.js inside the WebView sends for its (cross-origin) XHR
         * playlist requests: UA, Accept, Referer of the player page and — critically —
         * the Origin header, without which Alloha's CDN answers 403.
         */
        fun playbackHeaders(playerUrl: String): Headers {
            val origin = playerUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
            return Headers.Builder().apply {
                add("User-Agent", CHROME_UA)
                add("Accept", "*/*")
                add("Referer", playerUrl)
                if (origin != null) add("Origin", origin)
            }.build()
        }
    }
}
