package eu.kanade.tachiyomi.animeextension.en.anv

import aniyomi.lib.m3u8server.AutoDetector
import keiyoushi.network.rateLimit
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Refreshed upstream master playlist URL, headers, and subtitle track map for token renewal.
 */
class RefreshedSourceBundle(
    val masterUrl: String,
    val headers: Headers,
    val tracksByKey: Map<String, String> = emptyMap(),
)

/**
 * Parsed `#EXT-X-STREAM-INF` variant stream entry from a master HLS playlist.
 */
class ParsedVariantStream(
    val url: String,
    val height: Int?,
    val width: Int?,
    val bandwidth: Long?,
)

/**
 * Parsed master HLS playlist containing sorted variants, master-level session keys, and final URL.
 */
class ParsedMasterPlaylist(
    val variants: List<ParsedVariantStream>,
    val sessionKeys: List<String>,
    val finalUrl: String,
)

/**
 * Local loopback HTTP proxy for ANV HLS playlists, segments, encryption keys, and WebVTT subtitles.
 */
class AnvStreamProxy(baseClient: OkHttpClient) {

    private val publicOnlyDns = Dns { hostname ->
        if (isDisallowedHostLiteral(hostname)) {
            throw UnknownHostException("Blocked non-public host: $hostname")
        }
        val resolved = Dns.SYSTEM.lookup(hostname)
        if (resolved.isEmpty() || resolved.any(::isDisallowedInetAddress)) {
            throw UnknownHostException("Blocked non-public address for host: $hostname")
        }
        resolved
    }

    private val redirectGuardInterceptor = Interceptor { chain ->
        val req = chain.request()
        validatePublicHttpUrl(req.url.toString())
            ?: throw IOException("Blocked unsafe upstream URL")
        val response = chain.proceed(req)
        val finalUrl = response.request.url
        if (validatePublicHttpUrl(finalUrl.toString()) == null) {
            response.close()
            throw IOException("Blocked redirect to unsafe upstream URL")
        }
        response
    }

    private val client: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(publicOnlyDns)
        .addInterceptor(redirectGuardInterceptor)
        .protocols(listOf(Protocol.HTTP_1_1))
        .rateLimit(10)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val totalCachedSegmentBytes = AtomicLong(0L)
    private val activeBatchId = AtomicLong(0L)
    private val activePlayingEntryId = AtomicReference<String?>(null)

    private class SegmentTask(
        val callRef: AtomicReference<Call?> = AtomicReference(null),
        val accountedBytes: AtomicLong = AtomicLong(0L),
        @Volatile var future: Future<ByteArray>? = null,
    ) {
        /**
         * Cancels both the underlying OkHttp [Call] and worker [Future].
         */
        fun cancel() {
            callRef.getAndSet(null)?.cancel()
            future?.cancel(true)
        }
    }

    private class RangedPayload(
        val statusCode: Int,
        val bytes: ByteArray,
        val contentRange: String?,
    )

    private class FetchedText(
        val body: String,
        val finalUrl: String,
    )

    private inner class HlsEntry(
        val id: String,
        val batchId: Long,
        val originHost: String?,
        @Volatile var masterUrl: String,
        @Volatile var activeVariantUrl: String?,
        val targetHeight: Int?,
        @Volatile var headers: Headers,
        @Volatile var sessionKeys: List<String>,
        val refresh: () -> RefreshedSourceBundle?,
    ) {
        @Volatile var segments: List<String> = emptyList()

        @Volatile var isEncrypted: Boolean = false

        @Volatile var isFmp4: Boolean = false

        @Volatile var hasByteRange: Boolean = false

        var generation: Long = 0L

        var lastRefreshMs: Long = 0L

        var lastRefreshSucceeded: Boolean = false

        val refreshLock = Any()

        val keyCache = ConcurrentHashMap<String, ByteArray>()

        val segmentRemap = ConcurrentHashMap<String, String>()

        private val allowedUrls: MutableMap<String, Boolean> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Boolean>?,
                ): Boolean = size > MAX_ALLOWED_URLS_PER_ENTRY
            },
        )

        val segmentCache: MutableMap<String, SegmentTask> = Collections.synchronizedMap(
            object : LinkedHashMap<String, SegmentTask>(8, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, SegmentTask>?,
                ): Boolean {
                    val evict = size > SEGMENT_CACHE_SIZE
                    if (evict && eldest != null) {
                        releaseTaskBytes(eldest.value)
                    }
                    return evict
                }
            },
        )

        /**
         * Records a validated HTTP(S) URL in this entry's per-stream allowlist.
         */
        fun allowUrl(url: String): Boolean {
            val validated = validatePublicHttpUrl(url) ?: return false
            allowedUrls[validated.toString()] = true
            if (url != validated.toString()) {
                allowedUrls[url] = true
            }
            return true
        }

        /**
         * Returns `true` if [url] was previously emitted by a rewritten playlist for this entry.
         */
        fun isAllowedUrl(url: String): Boolean {
            val validated = validatePublicHttpUrl(url) ?: return false
            return allowedUrls.containsKey(url) || allowedUrls.containsKey(validated.toString())
        }

        /**
         * Removes a served segment task from [segmentCache] and releases its byte accounting.
         */
        fun evictCompletedSegment(url: String, task: SegmentTask) {
            synchronized(segmentCache) {
                if (segmentCache[url] === task) {
                    segmentCache.remove(url)
                    releaseTaskBytes(task)
                }
            }
        }

        /**
         * Cancels a failed or timed-out segment task and removes it from [segmentCache].
         */
        fun cancelAndRemoveSegment(url: String, task: SegmentTask) {
            synchronized(segmentCache) {
                if (segmentCache[url] === task) {
                    segmentCache.remove(url)
                }
            }
            task.cancel()
            releaseTaskBytes(task)
        }

        /**
         * Cancels in-flight segment tasks and releases cached segment/key buffers while keeping route metadata.
         */
        fun clearMediaBuffers() {
            val tasks = synchronized(segmentCache) {
                val copy = segmentCache.values.toList()
                segmentCache.clear()
                copy
            }
            tasks.forEach { task ->
                task.cancel()
                releaseTaskBytes(task)
            }
            keyCache.clear()
        }

        /**
         * Releases all buffered data and URL tables when this entry is permanently evicted.
         */
        fun dispose() {
            clearMediaBuffers()
            segmentRemap.clear()
            allowedUrls.clear()
        }

        private fun releaseTaskBytes(task: SegmentTask) {
            val released = task.accountedBytes.getAndSet(0L)
            if (released > 0L) {
                totalCachedSegmentBytes.addAndGet(-released)
            }
        }
    }

    private class SubEntry(
        val id: String,
        val batchId: Long,
        val originHost: String?,
        val trackLookupKey: String,
        @Volatile var url: String,
        @Volatile var headers: Headers,
        val refresh: () -> RefreshedSourceBundle?,
    ) {
        @Volatile var cachedBytes: ByteArray? = null

        var lastRefreshMs: Long = 0L

        var lastRefreshSucceeded: Boolean = false

        val refreshLock = Any()
    }

    private val hlsEntries: MutableMap<String, HlsEntry> = Collections.synchronizedMap(
        LinkedHashMap(64, 0.75f, true),
    )

    private val subEntries: MutableMap<String, SubEntry> = Collections.synchronizedMap(
        LinkedHashMap(64, 0.75f, true),
    )

    private val acceptExecutor = Executors.newSingleThreadExecutor(daemonFactory("anv-proxy-accept"))
    private val connectionPool = ThreadPoolExecutor(
        2,
        MAX_CONN_THREADS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        daemonFactory("anv-proxy-conn"),
    )
    private val fetchPool = Executors.newFixedThreadPool(FETCH_THREADS, daemonFactory("anv-proxy-fetch"))

    @Volatile
    private var server: ServerSocket? = null

    /**
     * Begins a new `getHosterList` registration batch and disposes stale entries from older episodes.
     */
    fun startBatch(): Long {
        val nextBatch = activeBatchId.incrementAndGet()
        pruneStaleEntries(nextBatch)
        return nextBatch
    }

    /**
     * Evicts stale batches above [SOFT_MAX_HLS_ENTRIES] and enforces [HARD_MAX_HLS_ENTRIES] overall.
     */
    private fun pruneStaleEntries(currentBatch: Long) {
        val minRetainedBatch = currentBatch - 1L
        val activeId = activePlayingEntryId.get()
        val evictedHls = mutableListOf<HlsEntry>()
        synchronized(hlsEntries) {
            val iter = hlsEntries.entries.iterator()
            while (iter.hasNext()) {
                val candidate = iter.next().value
                if (candidate.batchId < minRetainedBatch && candidate.id != activeId) {
                    evictedHls.add(candidate)
                    iter.remove()
                }
            }
            if (hlsEntries.size > SOFT_MAX_HLS_ENTRIES) {
                val overflowIter = hlsEntries.entries.iterator()
                while (hlsEntries.size > SOFT_MAX_HLS_ENTRIES && overflowIter.hasNext()) {
                    val candidate = overflowIter.next().value
                    if (candidate.batchId < currentBatch && candidate.id != activeId) {
                        evictedHls.add(candidate)
                        overflowIter.remove()
                    }
                }
            }
            if (hlsEntries.size > HARD_MAX_HLS_ENTRIES) {
                val hardIter = hlsEntries.entries.iterator()
                while (hlsEntries.size > HARD_MAX_HLS_ENTRIES && hardIter.hasNext()) {
                    val candidate = hardIter.next().value
                    if (candidate.id != activeId) {
                        evictedHls.add(candidate)
                        hardIter.remove()
                    }
                }
            }
        }
        evictedHls.forEach(HlsEntry::dispose)

        synchronized(subEntries) {
            val iter = subEntries.entries.iterator()
            while (iter.hasNext()) {
                val candidate = iter.next().value
                if (candidate.batchId < minRetainedBatch) {
                    candidate.cachedBytes = null
                    iter.remove()
                }
            }
            if (subEntries.size > SOFT_MAX_SUB_ENTRIES) {
                val overflowIter = subEntries.entries.iterator()
                while (subEntries.size > SOFT_MAX_SUB_ENTRIES && overflowIter.hasNext()) {
                    val candidate = overflowIter.next().value
                    if (candidate.batchId < currentBatch) {
                        candidate.cachedBytes = null
                        overflowIter.remove()
                    }
                }
            }
            if (subEntries.size > HARD_MAX_SUB_ENTRIES) {
                val hardIter = subEntries.entries.iterator()
                while (subEntries.size > HARD_MAX_SUB_ENTRIES && hardIter.hasNext()) {
                    val candidate = hardIter.next().value
                    candidate.cachedBytes = null
                    hardIter.remove()
                }
            }
        }
    }

    /**
     * Registers an HLS master or variant stream in [batchId] and returns a local loopback `.m3u8` URL.
     */
    fun registerHls(
        batchId: Long,
        masterUrl: String,
        variantUrl: String?,
        targetHeight: Int?,
        headers: Headers,
        initialSessionKeys: List<String> = emptyList(),
        refresh: () -> RefreshedSourceBundle?,
    ): String {
        val port = ensureServer()
        val token = generateCapabilityToken("h")
        val originHost = extractOriginHost(headers, masterUrl)
        val entry = HlsEntry(
            id = token,
            batchId = batchId,
            originHost = originHost,
            masterUrl = masterUrl,
            activeVariantUrl = variantUrl,
            targetHeight = targetHeight,
            headers = headers,
            sessionKeys = initialSessionKeys,
            refresh = refresh,
        )
        entry.allowUrl(masterUrl)
        variantUrl?.let(entry::allowUrl)
        registerSessionKeyUrls(entry, initialSessionKeys)
        synchronized(hlsEntries) {
            hlsEntries[token] = entry
        }
        pruneStaleEntries(activeBatchId.get())
        return if (variantUrl != null) {
            "http://127.0.0.1:$port/hls/$token/variant.m3u8"
        } else {
            "http://127.0.0.1:$port/hls/$token/master.m3u8"
        }
    }

    /**
     * Registers an external subtitle track in [batchId] and returns a local loopback `.vtt` URL.
     */
    fun registerSubtitle(
        batchId: Long,
        trackLookupKey: String,
        url: String,
        headers: Headers,
        refresh: () -> RefreshedSourceBundle?,
    ): String {
        val port = ensureServer()
        val token = generateCapabilityToken("s")
        val originHost = extractOriginHost(headers, url)
        synchronized(subEntries) {
            subEntries[token] = SubEntry(
                id = token,
                batchId = batchId,
                originHost = originHost,
                trackLookupKey = trackLookupKey,
                url = url,
                headers = headers,
                refresh = refresh,
            )
        }
        pruneStaleEntries(activeBatchId.get())
        return "http://127.0.0.1:$port/sub/$token.vtt"
    }

    /**
     * Marks [entry] as the active playback stream and releases segment buffers held by any prior stream.
     */
    private fun markActivePlayingEntry(entry: HlsEntry) {
        val prevId = activePlayingEntryId.getAndSet(entry.id)
        if (prevId != null && prevId != entry.id) {
            hlsEntries[prevId]?.clearMediaBuffers()
        }
    }

    /**
     * Lazily starts the loopback server socket and accepts incoming player connections.
     */
    @Synchronized
    private fun ensureServer(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        acceptExecutor.execute {
            while (!socket.isClosed) {
                val clientSocket = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    connectionPool.execute { handleConnection(clientSocket) }
                } catch (_: RejectedExecutionException) {
                    runCatching { clientSocket.close() }
                }
            }
        }
        return socket.localPort
    }

    /**
     * Parses a single HTTP/1.1 request from the local media player and dispatches it.
     */
    private fun handleConnection(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = HEADER_READ_TIMEOUT_MS
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                var rangeHeader: String? = null
                var hasBrowserOrigin = false
                var headerCount = 0
                while (headerCount < MAX_REQUEST_HEADERS) {
                    val headerLine = readLine(input) ?: break
                    if (headerLine.isEmpty()) break
                    headerCount++
                    val colon = headerLine.indexOf(':')
                    if (colon > 0) {
                        val name = headerLine.substring(0, colon).trim()
                        val value = headerLine.substring(colon + 1).trim()
                        if (name.equals("Range", ignoreCase = true) && value.startsWith("bytes=", ignoreCase = true)) {
                            rangeHeader = value
                        } else if (name.equals("Origin", ignoreCase = true) && value.isNotEmpty()) {
                            hasBrowserOrigin = true
                        }
                    }
                }

                val out = sock.getOutputStream()
                if (hasBrowserOrigin) {
                    writeResponse(out, 403, "text/plain", ByteArray(0), headOnly = true)
                    return
                }

                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.ROOT)
                val rawTarget = parts[1]

                if (method != "GET" && method != "HEAD") {
                    writeResponse(out, 405, "text/plain", ByteArray(0), headOnly = true)
                    return
                }

                sock.soTimeout = ACTIVE_SOCKET_TIMEOUT_MS
                val path = rawTarget.substringBefore('?')
                val query = rawTarget.substringAfter('?', "")
                val segments = path.trim('/').split('/')

                when (segments.firstOrNull()) {
                    "hls" -> handleHls(segments, query, rangeHeader, method == "HEAD", out)
                    "sub" -> handleSubtitle(segments, method == "HEAD", out)
                    else -> writeResponse(out, 404, "text/plain", ByteArray(0), headOnly = method == "HEAD")
                }
            } catch (_: IOException) {
                // Player closed socket on seek or stop
            } catch (_: Exception) {
                runCatching {
                    writeResponse(socket.getOutputStream(), 502, "text/plain", ByteArray(0), headOnly = true)
                }
            }
        }
    }

    /**
     * Handles `/hls/<token>/<action>` requests for playlists, keys, init maps, and media segments.
     */
    private fun handleHls(
        segments: List<String>,
        query: String,
        rangeHeader: String?,
        headOnly: Boolean,
        out: OutputStream,
    ) {
        if (segments.size < 3) {
            writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
            return
        }
        val id = segments[1]
        val action = segments[2]
        val entry = hlsEntries[id] ?: run {
            writeResponse(out, 404, "text/plain", ByteArray(0), headOnly)
            return
        }
        val targetParam = queryParam(query, "u")

        when (action) {
            "master.m3u8" -> {
                val playlist = fetchMasterPlaylist(entry)
                writeResponse(out, 200, M3U8_CONTENT_TYPE, playlist.toByteArray(Charsets.UTF_8), headOnly)
            }
            "variant.m3u8" -> {
                val url = entry.activeVariantUrl ?: entry.masterUrl
                val playlist = fetchVariantPlaylist(entry, url)
                writeResponse(out, 200, M3U8_CONTENT_TYPE, playlist.toByteArray(Charsets.UTF_8), headOnly)
            }
            "pl", "pl.m3u8" -> {
                val url = requireAllowedTarget(entry, targetParam, headOnly, out) ?: return
                val playlist = fetchVariantPlaylist(entry, url)
                writeResponse(out, 200, M3U8_CONTENT_TYPE, playlist.toByteArray(Charsets.UTF_8), headOnly)
            }
            "key", "key.bin" -> {
                val url = requireAllowedTarget(entry, targetParam, headOnly, out) ?: return
                val keyBytes = fetchEncryptionKey(entry, url)
                writeResponse(out, 200, "application/octet-stream", keyBytes, headOnly)
            }
            "init", "init.mp4" -> {
                val url = requireAllowedTarget(entry, targetParam, headOnly, out) ?: return
                markActivePlayingEntry(entry)
                if (rangeHeader != null) {
                    val ranged = fetchRangedBinaryWithRefresh(entry, url, rangeHeader, isSegment = false)
                    writeResponse(out, ranged.statusCode, "video/mp4", ranged.bytes, headOnly, ranged.contentRange)
                } else {
                    val initBytes = fetchRawBinary(entry, url)
                    writeResponse(out, 200, "video/mp4", initBytes, headOnly, acceptRanges = true)
                }
            }
            "seg", "seg.ts", "seg.m4s" -> {
                val url = requireAllowedTarget(entry, targetParam, headOnly, out) ?: return
                markActivePlayingEntry(entry)
                if (rangeHeader != null && (entry.isEncrypted || entry.isFmp4 || entry.hasByteRange)) {
                    val ranged = fetchRangedBinaryWithRefresh(entry, url, rangeHeader, isSegment = true)
                    val contentType = detectMediaContentType(ranged.bytes, url)
                    writeResponse(out, ranged.statusCode, contentType, ranged.bytes, headOnly, ranged.contentRange)
                    return
                }

                val segList = entry.segments
                val idx = segList.indexOf(url)
                val task = getOrSubmitSegment(entry, url)
                val bytes = try {
                    task.future?.get(SEGMENT_TIMEOUT_SEC, TimeUnit.SECONDS)
                        ?: throw IOException("Segment task missing")
                } catch (e: Exception) {
                    entry.cancelAndRemoveSegment(url, task)
                    throw e
                }
                if (!entry.hasByteRange) {
                    entry.evictCompletedSegment(url, task)
                }
                if (idx >= 0 && rangeHeader == null) {
                    segList.drop(idx + 1).take(PREFETCH_COUNT).forEach { nextUrl ->
                        if (totalCachedSegmentBytes.get() < MAX_GLOBAL_SEGMENT_BYTES) {
                            getOrSubmitSegment(entry, nextUrl)
                        }
                    }
                }
                val contentType = detectMediaContentType(bytes, url)
                if (rangeHeader != null) {
                    val sliced = sliceByteRange(bytes, rangeHeader)
                    if (sliced == null) {
                        writeResponse(out, 416, "text/plain", ByteArray(0), headOnly, "bytes */${bytes.size}")
                    } else {
                        writeResponse(out, 206, contentType, sliced.bytes, headOnly, sliced.contentRange)
                    }
                } else {
                    writeResponse(out, 200, contentType, bytes, headOnly, acceptRanges = true)
                }
            }
            else -> writeResponse(out, 404, "text/plain", ByteArray(0), headOnly)
        }
    }

    /**
     * Validates that [targetParam] is non-null and present in [entry]'s rewritten URL allowlist.
     */
    private fun requireAllowedTarget(
        entry: HlsEntry,
        targetParam: String?,
        headOnly: Boolean,
        out: OutputStream,
    ): String? {
        if (targetParam == null) {
            writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
            return null
        }
        if (!entry.isAllowedUrl(targetParam)) {
            writeResponse(out, 403, "text/plain", ByteArray(0), headOnly)
            return null
        }
        return targetParam
    }

    /**
     * Serves normalized WebVTT subtitles with automatic token refresh on expiry.
     */
    private fun handleSubtitle(
        segments: List<String>,
        headOnly: Boolean,
        out: OutputStream,
    ) {
        val rawId = segments.getOrNull(1)?.removeSuffix(".vtt") ?: run {
            writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
            return
        }
        val entry = subEntries[rawId] ?: run {
            writeResponse(out, 404, "text/plain", ByteArray(0), headOnly)
            return
        }

        entry.cachedBytes?.let { cached ->
            writeResponse(out, 200, VTT_CONTENT_TYPE, cached, headOnly)
            return
        }

        var fetched = fetchTextWithRetry(entry.url, entry.headers, entry.originHost)
        if (fetched == null && refreshSubtitleEntry(entry)) {
            fetched = fetchTextWithRetry(entry.url, entry.headers, entry.originHost)
        }
        val text = fetched?.body
        val normalized = normalizeWebVtt(text ?: "WEBVTT\n\n")
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        if (text != null) {
            entry.cachedBytes = bytes
        }
        writeResponse(out, 200, VTT_CONTENT_TYPE, bytes, headOnly)
    }

    /**
     * Fetches and rewrites an upstream master playlist, refreshing credentials if expired.
     */
    private fun fetchMasterPlaylist(entry: HlsEntry): String {
        var gen = synchronized(entry) { entry.generation }
        var fetched = fetchM3u8(entry.masterUrl, entry.headers, entry.originHost)
        if (fetched == null) {
            if (refreshHlsEntry(entry, force = true)) {
                gen = synchronized(entry) { entry.generation }
                fetched = fetchM3u8(entry.masterUrl, entry.headers, entry.originHost)
            }
        }
        val result = fetched ?: throw IOException("Master playlist unavailable")
        return if (result.body.contains("#EXT-X-STREAM-INF")) {
            val parsed = parseMasterPlaylist(result.body, result.finalUrl)
            synchronized(entry) {
                if (entry.generation == gen && parsed.sessionKeys.isNotEmpty()) {
                    entry.sessionKeys = parsed.sessionKeys
                    registerSessionKeyUrls(entry, parsed.sessionKeys)
                }
            }
            rewriteMasterPlaylist(entry, result.body, result.finalUrl)
        } else {
            rewriteMediaPlaylist(entry, result.body, result.finalUrl, gen)
        }
    }

    /**
     * Fetches and rewrites a variant media playlist, guarding against concurrent refresh races.
     */
    private fun fetchVariantPlaylist(entry: HlsEntry, requestedUrl: String, retryOnStaleGen: Boolean = true): String {
        var gen = synchronized(entry) { entry.generation }
        var currentUrl = requestedUrl
        var fetched = fetchM3u8(currentUrl, entry.headers, entry.originHost)
        if (fetched == null) {
            if (refreshHlsEntry(entry, force = true)) {
                synchronized(entry) {
                    gen = entry.generation
                    currentUrl = entry.activeVariantUrl ?: entry.masterUrl
                }
                fetched = fetchM3u8(currentUrl, entry.headers, entry.originHost)
            }
        }
        val playlist = fetched ?: throw IOException("Variant playlist unavailable")
        if (playlist.body.contains("#EXT-X-STREAM-INF")) {
            val parsed = parseMasterPlaylist(playlist.body, playlist.finalUrl)
            val chosen = selectBestMatchingVariant(parsed.variants, entry.targetHeight)
                ?: throw IOException("No variant streams found in master playlist")
            entry.allowUrl(chosen.url)
            synchronized(entry) {
                if (entry.generation == gen) {
                    entry.activeVariantUrl = chosen.url
                    if (parsed.sessionKeys.isNotEmpty()) {
                        entry.sessionKeys = parsed.sessionKeys
                        registerSessionKeyUrls(entry, parsed.sessionKeys)
                    }
                }
            }
            val mediaFetched = fetchM3u8(chosen.url, entry.headers, entry.originHost)
                ?: throw IOException("Selected variant playlist unavailable")
            return rewriteMediaPlaylist(entry, mediaFetched.body, mediaFetched.finalUrl, gen)
        }

        val currentGen = synchronized(entry) { entry.generation }
        if (currentGen != gen && retryOnStaleGen) {
            val freshUrl = synchronized(entry) { entry.activeVariantUrl ?: entry.masterUrl }
            return fetchVariantPlaylist(entry, freshUrl, retryOnStaleGen = false)
        }
        return rewriteMediaPlaylist(entry, playlist.body, playlist.finalUrl, gen, activeUrl = currentUrl)
    }

    /**
     * Rewrites master playlist child links to point at local `/hls/<id>/pl.m3u8` routes.
     */
    private fun rewriteMasterPlaylist(entry: HlsEntry, playlistText: String, baseUrlStr: String): String {
        val baseUrl = baseUrlStr.toHttpUrlOrNull()
        return playlistText.lineSequence().joinToString("\n") { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> rawLine
                trimmed.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true) -> {
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/key.bin?u=${encodeParam(resolved)}\""
                    }
                }
                trimmed.startsWith("#") -> {
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}\""
                    }
                }
                else -> {
                    val resolved = baseUrl?.resolve(trimmed)?.toString() ?: trimmed
                    entry.allowUrl(resolved)
                    "/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}"
                }
            }
        }
    }

    /**
     * Rewrites media playlist keys, init maps, session keys, and segments while updating entry state.
     */
    private fun rewriteMediaPlaylist(
        entry: HlsEntry,
        playlistText: String,
        baseUrlStr: String,
        expectedGen: Long,
        activeUrl: String? = null,
    ): String {
        val baseUrl = baseUrlStr.toHttpUrlOrNull()
        val discoveredSegments = ArrayList<String>(128)
        var nextIsNestedPlaylist = false
        var sawEncryption = false
        var sawFmp4Map = false
        var sawByteRange = false
        val outputLines = ArrayList<String>(256)
        var injectedSessionKeys = false
        val masterSessionKeys = entry.sessionKeys

        val rawLines = playlistText.lines()
        val mediaHasOwnKey = rawLines.any { line ->
            val t = line.trim()
            t.startsWith("#EXT-X-KEY", ignoreCase = true) && !t.contains("METHOD=NONE", ignoreCase = true)
        }

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (!injectedSessionKeys &&
                !mediaHasOwnKey &&
                masterSessionKeys.isNotEmpty() &&
                (
                    trimmed.startsWith("#EXTINF", ignoreCase = true) ||
                        trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) ||
                        trimmed.startsWith("#EXT-X-BYTERANGE", ignoreCase = true)
                    )
            ) {
                for (sessionKeyLine in masterSessionKeys) {
                    val asMediaKey = sessionKeyLine.replaceFirst(
                        SESSION_KEY_PREFIX_REGEX,
                        "#EXT-X-KEY:",
                    )
                    val rewrittenKey = URI_ATTR_REGEX.replace(asMediaKey) { match ->
                        val resolved = match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/key.bin?u=${encodeParam(resolved)}\""
                    }
                    outputLines.add(rewrittenKey)
                    if (!asMediaKey.contains("METHOD=NONE", ignoreCase = true)) {
                        sawEncryption = true
                    }
                }
                injectedSessionKeys = true
            }

            val mapped = when {
                trimmed.isEmpty() -> rawLine
                trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    if (!trimmed.contains("METHOD=NONE", ignoreCase = true)) {
                        sawEncryption = true
                    }
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/key.bin?u=${encodeParam(resolved)}\""
                    }
                }
                trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    sawFmp4Map = true
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/init.mp4?u=${encodeParam(resolved)}\""
                    }
                }
                trimmed.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    sawByteRange = true
                    rawLine
                }
                trimmed.startsWith("#") -> {
                    if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        nextIsNestedPlaylist = true
                    }
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        entry.allowUrl(resolved)
                        "URI=\"/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}\""
                    }
                }
                else -> {
                    val resolved = baseUrl?.resolve(trimmed)?.toString() ?: trimmed
                    entry.allowUrl(resolved)
                    val isPlaylist = nextIsNestedPlaylist ||
                        resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    nextIsNestedPlaylist = false
                    if (isPlaylist) {
                        "/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}"
                    } else {
                        discoveredSegments.add(resolved)
                        val segAction = if (sawFmp4Map) "seg.m4s" else "seg.ts"
                        "/hls/${entry.id}/$segAction?u=${encodeParam(resolved)}"
                    }
                }
            }
            outputLines.add(mapped)
        }

        synchronized(entry) {
            if (entry.generation == expectedGen) {
                if (activeUrl != null) {
                    entry.activeVariantUrl = activeUrl
                }
                entry.isEncrypted = sawEncryption
                entry.isFmp4 = sawFmp4Map
                entry.hasByteRange = sawByteRange

                val previousSegments = entry.segments
                entry.segments = discoveredSegments
                if (previousSegments.isNotEmpty() &&
                    discoveredSegments.isNotEmpty() &&
                    previousSegments != discoveredSegments
                ) {
                    val count = minOf(previousSegments.size, discoveredSegments.size)
                    if (entry.segmentRemap.size + count > MAX_REMAP_ENTRIES_PER_STREAM) {
                        entry.segmentRemap.clear()
                    }
                    for (i in 0 until count) {
                        entry.segmentRemap[previousSegments[i]] = discoveredSegments[i]
                    }
                }
            }
        }

        return outputLines.joinToString("\n")
    }

    /**
     * Returns an in-flight or newly submitted segment download task for [segmentUrl].
     */
    private fun getOrSubmitSegment(entry: HlsEntry, segmentUrl: String): SegmentTask {
        synchronized(entry.segmentCache) {
            entry.segmentCache[segmentUrl]?.let { return it }
            val task = SegmentTask()
            task.future = fetchPool.submit<ByteArray> {
                val raw = fetchBinaryWithRefresh(entry, segmentUrl, isSegment = true, callRef = task.callRef)
                val processed = if (entry.isEncrypted || entry.isFmp4 || entry.hasByteRange) {
                    raw
                } else {
                    cleanSegmentBytes(raw)
                }
                val size = processed.size.toLong()
                if (task.accountedBytes.compareAndSet(0L, size)) {
                    totalCachedSegmentBytes.addAndGet(size)
                }
                processed
            }
            entry.segmentCache[segmentUrl] = task
            return task
        }
    }

    /**
     * Fetches and caches an AES-128 segment decryption key.
     */
    private fun fetchEncryptionKey(entry: HlsEntry, keyUrl: String): ByteArray {
        entry.keyCache[keyUrl]?.let { return it }
        val bytes = fetchBinaryWithRefresh(entry, keyUrl, isSegment = false, callRef = null)
        if (entry.keyCache.size < MAX_KEYS_PER_ENTRY) {
            entry.keyCache[keyUrl] = bytes
        }
        return bytes
    }

    /**
     * Fetches an unsegmented binary resource such as an fMP4 initialization map.
     */
    private fun fetchRawBinary(entry: HlsEntry, url: String): ByteArray = fetchBinaryWithRefresh(entry, url, isSegment = false, callRef = null)

    /**
     * Downloads a binary resource with automatic token refresh and retry on expiry or rate limits.
     */
    private fun fetchBinaryWithRefresh(
        entry: HlsEntry,
        originalUrl: String,
        isSegment: Boolean,
        callRef: AtomicReference<Call?>?,
    ): ByteArray {
        var targetUrl = entry.segmentRemap[originalUrl] ?: originalUrl
        var lastError: Exception? = null

        repeat(4) { attempt ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Segment download cancelled")
            }
            try {
                val validatedUrl = validatePublicHttpUrl(targetUrl)
                    ?: throw IOException("Rejected non-public upstream URL")
                val safeHeaders = sanitizeHeadersForTarget(entry.headers, entry.originHost, validatedUrl)
                val request = Request.Builder()
                    .url(validatedUrl)
                    .headers(safeHeaders)
                    .build()

                val call = client.newCall(request)
                callRef?.set(call)
                try {
                    call.execute().use { response ->
                        when {
                            response.isSuccessful -> return response.body.bytes()
                            response.code == 429 -> pauseMs(parseRetryAfterMs(response))
                            response.code in REFRESH_STATUS_CODES -> {
                                val segIndex = if (isSegment) {
                                    entry.segments.indexOf(targetUrl).takeIf { it >= 0 }
                                        ?: entry.segments.indexOf(originalUrl).takeIf { it >= 0 }
                                } else {
                                    null
                                }
                                if (refreshHlsEntry(entry, force = true)) {
                                    val refreshPlaylistUrl = entry.activeVariantUrl ?: entry.masterUrl
                                    runCatching { fetchVariantPlaylist(entry, refreshPlaylistUrl) }
                                    targetUrl = entry.segmentRemap[originalUrl]
                                        ?: segIndex?.let { entry.segments.getOrNull(it) }
                                        ?: targetUrl
                                }
                            }
                            else -> throw IOException("Upstream HTTP ${response.code}")
                        }
                    }
                } finally {
                    callRef?.compareAndSet(call, null)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Segment download interrupted")
                }
                lastError = e
                if (attempt > 0) {
                    pauseMs(300L * attempt)
                }
            }
        }

        throw lastError ?: IOException("Failed to fetch stream resource")
    }

    /**
     * Downloads a byte-range slice from upstream (for `#EXT-X-BYTERANGE` or player `Range` requests).
     */
    private fun fetchRangedBinaryWithRefresh(
        entry: HlsEntry,
        originalUrl: String,
        rangeHeader: String,
        isSegment: Boolean,
    ): RangedPayload {
        var targetUrl = entry.segmentRemap[originalUrl] ?: originalUrl
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                val validatedUrl = validatePublicHttpUrl(targetUrl)
                    ?: throw IOException("Rejected non-public upstream URL")
                val safeHeaders = sanitizeHeadersForTarget(entry.headers, entry.originHost, validatedUrl)
                    .newBuilder()
                    .set("Range", rangeHeader)
                    .build()
                val request = Request.Builder()
                    .url(validatedUrl)
                    .headers(safeHeaders)
                    .build()

                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 206 -> {
                            return RangedPayload(
                                statusCode = 206,
                                bytes = response.body.bytes(),
                                contentRange = response.header("Content-Range"),
                            )
                        }
                        response.code == 416 -> {
                            return RangedPayload(
                                statusCode = 416,
                                bytes = ByteArray(0),
                                contentRange = response.header("Content-Range"),
                            )
                        }
                        response.isSuccessful -> {
                            val full = response.body.bytes()
                            val sliced = sliceByteRange(full, rangeHeader)
                                ?: return RangedPayload(416, ByteArray(0), "bytes */${full.size}")
                            return sliced
                        }
                        response.code == 429 -> pauseMs(parseRetryAfterMs(response))
                        response.code in REFRESH_STATUS_CODES -> {
                            val segIndex = if (isSegment) {
                                entry.segments.indexOf(targetUrl).takeIf { it >= 0 }
                                    ?: entry.segments.indexOf(originalUrl).takeIf { it >= 0 }
                            } else {
                                null
                            }
                            if (refreshHlsEntry(entry, force = true)) {
                                val refreshPlaylistUrl = entry.activeVariantUrl ?: entry.masterUrl
                                runCatching { fetchVariantPlaylist(entry, refreshPlaylistUrl) }
                                targetUrl = entry.segmentRemap[originalUrl]
                                    ?: segIndex?.let { entry.segments.getOrNull(it) }
                                    ?: targetUrl
                            }
                        }
                        else -> throw IOException("Upstream HTTP ${response.code}")
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt > 0) {
                    pauseMs(250L * attempt)
                }
            }
        }

        throw lastError ?: IOException("Failed to fetch ranged stream resource")
    }

    /**
     * Refreshes an expired [HlsEntry] without holding `synchronized(entry)` across network calls.
     */
    private fun refreshHlsEntry(entry: HlsEntry, force: Boolean = false): Boolean {
        synchronized(entry.refreshLock) {
            val snapshotSessionKeys: List<String>
            val needsVariant: Boolean
            synchronized(entry) {
                val now = System.currentTimeMillis()
                val minInterval = when {
                    !entry.lastRefreshSucceeded -> FAILED_REFRESH_COOLDOWN_MS
                    force -> FORCE_REFRESH_COOLDOWN_MS
                    else -> NORMAL_REFRESH_COOLDOWN_MS
                }
                if (entry.lastRefreshMs > 0L && now - entry.lastRefreshMs < minInterval) {
                    return entry.lastRefreshSucceeded
                }
                snapshotSessionKeys = entry.sessionKeys
                needsVariant = entry.targetHeight != null || entry.activeVariantUrl != null
            }

            val bundle = runCatching { entry.refresh() }.getOrNull()
            if (bundle == null || bundle.masterUrl.isBlank() || validatePublicHttpUrl(bundle.masterUrl) == null) {
                synchronized(entry) {
                    entry.lastRefreshMs = System.currentTimeMillis()
                    entry.lastRefreshSucceeded = false
                }
                return false
            }

            var resolvedVariantUrl: String? = null
            var resolvedSessionKeys: List<String> = snapshotSessionKeys
            if (needsVariant) {
                val masterFetched = fetchM3u8(bundle.masterUrl, bundle.headers, entry.originHost)
                if (masterFetched == null) {
                    synchronized(entry) {
                        entry.lastRefreshMs = System.currentTimeMillis()
                        entry.lastRefreshSucceeded = false
                    }
                    return false
                }
                if (masterFetched.body.contains("#EXT-X-STREAM-INF")) {
                    val parsed = parseMasterPlaylist(masterFetched.body, masterFetched.finalUrl)
                    val matched = selectBestMatchingVariant(parsed.variants, entry.targetHeight)
                    if (matched == null) {
                        synchronized(entry) {
                            entry.lastRefreshMs = System.currentTimeMillis()
                            entry.lastRefreshSucceeded = false
                        }
                        return false
                    }
                    resolvedVariantUrl = matched.url
                    if (parsed.sessionKeys.isNotEmpty()) {
                        resolvedSessionKeys = parsed.sessionKeys
                    }
                } else {
                    resolvedVariantUrl = masterFetched.finalUrl
                }
            }

            synchronized(entry) {
                entry.lastRefreshMs = System.currentTimeMillis()
                entry.masterUrl = bundle.masterUrl
                entry.headers = bundle.headers
                entry.activeVariantUrl = resolvedVariantUrl
                entry.sessionKeys = resolvedSessionKeys
                entry.allowUrl(bundle.masterUrl)
                resolvedVariantUrl?.let(entry::allowUrl)
                registerSessionKeyUrls(entry, resolvedSessionKeys)
                entry.generation++
                entry.lastRefreshSucceeded = true
            }
            return true
        }
    }

    /**
     * Refreshes an expired [SubEntry] without holding `synchronized(entry)` across network calls.
     */
    private fun refreshSubtitleEntry(entry: SubEntry): Boolean {
        synchronized(entry.refreshLock) {
            synchronized(entry) {
                val now = System.currentTimeMillis()
                val cooldown = if (entry.lastRefreshSucceeded) SUB_REFRESH_COOLDOWN_MS else FAILED_REFRESH_COOLDOWN_MS
                if (entry.lastRefreshMs > 0L && now - entry.lastRefreshMs < cooldown) {
                    return entry.lastRefreshSucceeded
                }
            }
            val bundle = runCatching { entry.refresh() }.getOrNull()
            val freshUrl = bundle?.tracksByKey?.get(entry.trackLookupKey)
            synchronized(entry) {
                entry.lastRefreshMs = System.currentTimeMillis()
                if (bundle == null || freshUrl == null || validatePublicHttpUrl(freshUrl) == null) {
                    entry.lastRefreshSucceeded = false
                    return false
                }
                entry.headers = bundle.headers
                entry.url = freshUrl
                entry.cachedBytes = null
                entry.lastRefreshSucceeded = true
                return true
            }
        }
    }

    /**
     * Fetches an upstream `.m3u8` playlist and preserves its post-redirect final URL.
     */
    private fun fetchM3u8(url: String, headers: Headers, originHost: String?): FetchedText? {
        val fetched = fetchTextWithRetry(url, headers, originHost) ?: return null
        val trimmed = fetched.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (trimmed.startsWith("#EXTM3U")) {
            FetchedText(trimmed, fetched.finalUrl)
        } else {
            null
        }
    }

    /**
     * Fetches a UTF-8 text resource with retry and safe-host header scoping.
     */
    private fun fetchTextWithRetry(url: String, headers: Headers, originHost: String?): FetchedText? {
        val validatedUrl = validatePublicHttpUrl(url) ?: return null
        val safeHeaders = sanitizeHeadersForTarget(headers, originHost, validatedUrl)
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(validatedUrl).headers(safeHeaders).build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            return FetchedText(
                                body = response.body.string(),
                                finalUrl = response.request.url.toString(),
                            )
                        }
                        response.code == 429 -> pauseMs(parseRetryAfterMs(response))
                        response.code in REFRESH_STATUS_CODES -> return null
                        else -> if (attempt == 2) return null
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: IOException) {
                if (attempt == 2) return null
                try {
                    pauseMs(250L * (attempt + 1))
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    /**
     * Registers key URLs referenced inside `#EXT-X-SESSION-KEY` attributes on [entry].
     */
    private fun registerSessionKeyUrls(entry: HlsEntry, sessionKeyLines: List<String>) {
        for (line in sessionKeyLines) {
            URI_ATTR_REGEX.findAll(line).forEach { match ->
                entry.allowUrl(match.groupValues[1])
            }
        }
    }

    /**
     * Ensures subtitle text starts with a valid `WEBVTT` header.
     */
    private fun normalizeWebVtt(raw: String): String {
        val stripped = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (stripped.startsWith("WEBVTT")) {
            stripped
        } else {
            "WEBVTT\n\n$stripped"
        }
    }

    /**
     * Writes an HTTP/1.1 response without wildcard CORS headers.
     */
    private fun writeResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false,
        contentRange: String? = null,
        acceptRanges: Boolean = false,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            else -> "Bad Gateway"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (contentRange != null) {
                append("Content-Range: ").append(contentRange).append("\r\n")
            }
            if (acceptRanges || contentRange != null) {
                append("Accept-Ranges: bytes\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    /**
     * Reads a single CRLF/LF line from [input] up to 16 KiB.
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val ch = input.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(ch.toChar())
            if (sb.length > 16_384) return sb.toString()
        }
    }

    /**
     * Extracts and URL-decodes a query parameter from [query].
     */
    private fun queryParam(query: String, key: String): String? = query
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotEmpty() }
        ?.let { URLDecoder.decode(it, "UTF-8") }

    /**
     * URL-encodes a query parameter value.
     */
    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Sleeps interruptibly on the worker thread when backing off on HTTP 429 or transient errors.
     */
    private fun pauseMs(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    companion object {
        private const val FETCH_THREADS = 6
        private const val MAX_CONN_THREADS = 16
        private const val PREFETCH_COUNT = 2
        private const val SEGMENT_CACHE_SIZE = 3
        private const val SOFT_MAX_HLS_ENTRIES = 256
        private const val HARD_MAX_HLS_ENTRIES = 512
        private const val SOFT_MAX_SUB_ENTRIES = 256
        private const val HARD_MAX_SUB_ENTRIES = 512
        private const val MAX_KEYS_PER_ENTRY = 32
        private const val MAX_ALLOWED_URLS_PER_ENTRY = 4_096
        private const val MAX_REMAP_ENTRIES_PER_STREAM = 2_048
        private const val MAX_GLOBAL_SEGMENT_BYTES = 24L * 1024L * 1024L
        private const val MAX_HEADER_SCAN_BYTES = 262_144
        private const val MAX_REQUEST_HEADERS = 64
        private const val HEADER_READ_TIMEOUT_MS = 5_000
        private const val ACTIVE_SOCKET_TIMEOUT_MS = 30_000
        private const val SEGMENT_TIMEOUT_SEC = 25L
        private const val FAILED_REFRESH_COOLDOWN_MS = 1_000L
        private const val FORCE_REFRESH_COOLDOWN_MS = 2_500L
        private const val NORMAL_REFRESH_COOLDOWN_MS = 10_000L
        private const val SUB_REFRESH_COOLDOWN_MS = 3_000L
        private const val MPEG_TS_PACKET_SIZE = 188
        private const val MPEG_TS_SYNC_BYTE: Byte = 0x47
        private const val M3U8_CONTENT_TYPE = "application/vnd.apple.mpegurl"
        private const val VTT_CONTENT_TYPE = "text/vtt; charset=utf-8"
        private const val HEX_CHARS = "0123456789abcdef"

        private val SECURE_RANDOM = SecureRandom()
        private val REFRESH_STATUS_CODES = setOf(401, 403, 404, 410)
        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")
        private val SESSION_KEY_PREFIX_REGEX = Regex("""^#EXT-X-SESSION-KEY:""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        private val BANDWIDTH_REGEX = Regex("""(?<!AVERAGE-)BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        private val BYTE_RANGE_HEADER_REGEX = Regex("""^bytes=(\d*)-(\d*)$""", RegexOption.IGNORE_CASE)
        private val IPV4_LITERAL_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        private val FMP4_BOX_TYPES = setOf("ftyp", "styp", "moof", "sidx", "emsg", "free")

        /**
         * Parses master playlist variants and resolved `#EXT-X-SESSION-KEY` lines against [finalUrl].
         */
        fun parseMasterPlaylist(playlistText: String, finalUrl: String): ParsedMasterPlaylist {
            val baseUrl = finalUrl.toHttpUrlOrNull()
            val lines = playlistText.lines()
            val variants = mutableListOf<ParsedVariantStream>()
            val sessionKeys = mutableListOf<String>()
            var pendingWidth: Int? = null
            var pendingHeight: Int? = null
            var pendingBandwidth: Long? = null
            var waitingForUrl = false

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true)) {
                    val resolvedKeyLine = URI_ATTR_REGEX.replace(line) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        "URI=\"$resolved\""
                    }
                    sessionKeys.add(resolvedKeyLine)
                } else if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val resMatch = RESOLUTION_REGEX.find(line)
                    pendingWidth = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    pendingHeight = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    pendingBandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    waitingForUrl = true
                } else if (!line.startsWith("#") && waitingForUrl) {
                    val resolved = baseUrl?.resolve(line)?.toString() ?: line
                    variants.add(
                        ParsedVariantStream(
                            url = resolved,
                            height = pendingHeight,
                            width = pendingWidth,
                            bandwidth = pendingBandwidth,
                        ),
                    )
                    waitingForUrl = false
                    pendingWidth = null
                    pendingHeight = null
                    pendingBandwidth = null
                }
            }

            val sortedVariants = variants.sortedWith(
                compareByDescending<ParsedVariantStream> { it.height ?: 0 }
                    .thenByDescending { it.bandwidth ?: 0L },
            )
            return ParsedMasterPlaylist(
                variants = sortedVariants,
                sessionKeys = sessionKeys,
                finalUrl = finalUrl,
            )
        }

        /**
         * Selects the variant matching [targetHeight] or the closest resolution fallback.
         */
        private fun selectBestMatchingVariant(
            variants: List<ParsedVariantStream>,
            targetHeight: Int?,
        ): ParsedVariantStream? {
            if (variants.isEmpty()) return null
            if (targetHeight == null) return variants.first()
            return variants.firstOrNull { it.height == targetHeight }
                ?: variants.minByOrNull { abs((it.height ?: 0) - targetHeight) }
        }

        /**
         * Strips disguised image headers prepended to unencrypted MPEG-TS segments.
         */
        fun cleanSegmentBytes(bytes: ByteArray): ByteArray {
            if (bytes.size < MPEG_TS_PACKET_SIZE) return bytes

            if (bytes[0] == MPEG_TS_SYNC_BYTE && !startsWithGifSignature(bytes)) {
                if (bytes.size < MPEG_TS_PACKET_SIZE * 2 ||
                    (
                        bytes[MPEG_TS_PACKET_SIZE] == MPEG_TS_SYNC_BYTE &&
                            (bytes.size < MPEG_TS_PACKET_SIZE * 3 || bytes[MPEG_TS_PACKET_SIZE * 2] == MPEG_TS_SYNC_BYTE)
                        )
                ) {
                    return bytes
                }
            }

            if (isFmp4Segment(bytes)) {
                return bytes
            }

            val maxOffset = minOf(bytes.size - MPEG_TS_PACKET_SIZE * 3 - 1, MAX_HEADER_SCAN_BYTES)
            if (maxOffset >= 1) {
                for (offset in 1..maxOffset) {
                    if (bytes[offset] == MPEG_TS_SYNC_BYTE &&
                        bytes[offset + MPEG_TS_PACKET_SIZE] == MPEG_TS_SYNC_BYTE &&
                        bytes[offset + MPEG_TS_PACKET_SIZE * 2] == MPEG_TS_SYNC_BYTE &&
                        (
                            offset + MPEG_TS_PACKET_SIZE * 3 >= bytes.size ||
                                bytes[offset + MPEG_TS_PACKET_SIZE * 3] == MPEG_TS_SYNC_BYTE
                            )
                    ) {
                        return bytes.copyOfRange(offset, bytes.size)
                    }
                }
            }

            val autoSkip = runCatching { AutoDetector.detectSkipBytes(bytes) }.getOrDefault(0)
            if (autoSkip in 1 until bytes.size) {
                return bytes.copyOfRange(autoSkip, bytes.size)
            }

            return bytes
        }

        /**
         * Slices [fullBytes] according to an HTTP `Range: bytes=start-end` header.
         */
        private fun sliceByteRange(fullBytes: ByteArray, rangeHeader: String): RangedPayload? {
            if (fullBytes.isEmpty()) return null
            val match = BYTE_RANGE_HEADER_REGEX.find(rangeHeader.trim()) ?: return null
            val startStr = match.groupValues[1]
            val endStr = match.groupValues[2]
            val total = fullBytes.size

            val (start, end) = if (startStr.isEmpty()) {
                val suffixLen = endStr.toIntOrNull()?.takeIf { it > 0 } ?: return null
                val s = (total - suffixLen).coerceAtLeast(0)
                s to (total - 1)
            } else {
                val s = startStr.toIntOrNull() ?: return null
                if (s < 0 || s >= total) return null
                val e = if (endStr.isEmpty()) {
                    total - 1
                } else {
                    (endStr.toIntOrNull() ?: return null).coerceAtMost(total - 1)
                }
                if (e < s) return null
                s to e
            }

            return RangedPayload(
                statusCode = 206,
                bytes = fullBytes.copyOfRange(start, end + 1),
                contentRange = "bytes $start-$end/$total",
            )
        }

        /**
         * Validates that [rawUrl] is an HTTP(S) URL targeting a non-local, non-private host.
         */
        private fun validatePublicHttpUrl(rawUrl: String): HttpUrl? {
            val parsed = rawUrl.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (isDisallowedHostLiteral(parsed.host)) return null
            return parsed
        }

        /**
         * Rejects localhost, `.local`, and literal private/loopback/link-local IP addresses.
         */
        private fun isDisallowedHostLiteral(host: String): Boolean {
            val clean = host.trim().trim('[', ']').lowercase(Locale.ROOT)
            if (clean.isEmpty() || clean == "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local")) {
                return true
            }
            if (IPV4_LITERAL_REGEX.matches(clean) || ':' in clean) {
                val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return true
                return isDisallowedInetAddress(addr)
            }
            return false
        }

        /**
         * Returns `true` when [addr] is loopback, link-local, site-local, multicast, or IPv6 ULA.
         */
        private fun isDisallowedInetAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return true
            }
            if (addr is Inet6Address) {
                val b0 = addr.address[0].toInt() and 0xFF
                if ((b0 and 0xFE) == 0xFC) return true
            }
            return false
        }

        /**
         * Strips session `Cookie` headers when requesting third-party hosts outside [originHost].
         */
        private fun sanitizeHeadersForTarget(
            headers: Headers,
            originHost: String?,
            targetUrl: HttpUrl,
        ): Headers {
            if (headers["Cookie"] == null) return headers
            if (originHost != null && isSameSiteHost(targetUrl.host, originHost)) {
                return headers
            }
            return headers.newBuilder().removeAll("Cookie").build()
        }

        /**
         * Checks whether [candidateHost] matches [originHost] or one of its subdomains.
         */
        private fun isSameSiteHost(candidateHost: String, originHost: String): Boolean {
            val candidate = candidateHost.lowercase(Locale.ROOT)
            val origin = originHost.lowercase(Locale.ROOT)
            return candidate == origin || candidate.endsWith(".$origin")
        }

        /**
         * Determines the primary ANV site host from `Origin`, `Referer`, or fallback URL.
         */
        private fun extractOriginHost(headers: Headers, fallbackUrl: String): String? = headers["Origin"]?.toHttpUrlOrNull()?.host
            ?: headers["Referer"]?.toHttpUrlOrNull()?.host
            ?: fallbackUrl.toHttpUrlOrNull()?.host

        /**
         * Generates an unguessable 128-bit random capability token with [prefix].
         */
        private fun generateCapabilityToken(prefix: String): String {
            val bytes = ByteArray(16)
            SECURE_RANDOM.nextBytes(bytes)
            val sb = StringBuilder(prefix.length + 1 + bytes.size * 2)
            sb.append(prefix).append('_')
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        /**
         * Detects `GIF87a`/`GIF89a` magic headers whose `0x47` byte mimics MPEG-TS sync.
         */
        private fun startsWithGifSignature(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()

        /**
         * Checks whether [bytes] begins with an ISO BMFF / fMP4 box header.
         */
        private fun isFmp4Segment(bytes: ByteArray): Boolean {
            if (bytes.size < 8) return false
            val boxType = String(bytes, 4, 4, Charsets.ISO_8859_1)
            return boxType in FMP4_BOX_TYPES
        }

        /**
         * Selects the MIME content type for a media segment based on magic bytes or extension.
         */
        private fun detectMediaContentType(bytes: ByteArray, url: String): String = when {
            bytes.isNotEmpty() && bytes[0] == MPEG_TS_SYNC_BYTE && !startsWithGifSignature(bytes) -> "video/mp2t"
            isFmp4Segment(bytes) -> "video/mp4"
            url.substringBefore('?').endsWith(".m4s", ignoreCase = true) -> "video/iso.segment"
            else -> "video/mp2t"
        }

        /**
         * Parses `Retry-After` or `X-RateLimit-Reset` headers into a bounded delay in milliseconds.
         */
        fun parseRetryAfterMs(response: Response): Long {
            response.header("Retry-After")?.trim()?.toLongOrNull()?.let { sec ->
                if (sec > 0L) return (sec.coerceAtMost(15L)) * 1_000L
            }
            response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { resetEpoch ->
                val nowEpoch = System.currentTimeMillis() / 1_000L
                val delta = (resetEpoch - nowEpoch).coerceIn(1L, 15L)
                return delta * 1_000L
            }
            return 2_000L
        }

        /**
         * Creates a daemon [ThreadFactory] for background proxy worker pools.
         */
        private fun daemonFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, prefix).apply { isDaemon = true }
        }
    }
}
