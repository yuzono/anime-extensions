package eu.kanade.tachiyomi.animeextension.en.anv

import aniyomi.lib.m3u8server.AutoDetector
import keiyoushi.network.rateLimit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class RefreshedSourceBundle(
    val masterUrl: String,
    val headers: Headers,
    val tracksByKey: Map<String, String> = emptyMap(),
)

class ParsedVariantStream(
    val url: String,
    val height: Int?,
    val width: Int?,
    val bandwidth: Long?,
)

class AnvStreamProxy(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .protocols(listOf(Protocol.HTTP_1_1))
        .rateLimit(10)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private class HlsEntry(
        val id: String,
        @Volatile var masterUrl: String,
        @Volatile var activeVariantUrl: String?,
        val targetHeight: Int?,
        @Volatile var headers: Headers,
        val refresh: () -> RefreshedSourceBundle?,
    ) {
        @Volatile var segments: List<String> = emptyList()

        @Volatile var isEncrypted: Boolean = false

        @Volatile var isFmp4: Boolean = false

        @Volatile var lastRefreshMs: Long = 0L

        val keyCache = ConcurrentHashMap<String, ByteArray>()

        val segmentCache: MutableMap<String, Future<ByteArray>> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, Future<ByteArray>>(16, 0.75f, true) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, Future<ByteArray>>?,
                    ): Boolean = size > SEGMENT_CACHE_SIZE
                },
            )
    }

    private class SubEntry(
        val id: String,
        val trackLookupKey: String,
        @Volatile var url: String,
        @Volatile var headers: Headers,
        val refresh: () -> RefreshedSourceBundle?,
    ) {
        @Volatile var cachedBytes: ByteArray? = null

        @Volatile var lastRefreshMs: Long = 0L
    }

    private val hlsEntries: MutableMap<String, HlsEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, HlsEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, HlsEntry>?,
            ): Boolean = size > MAX_HLS_ENTRIES
        },
    )

    private val subEntries: MutableMap<String, SubEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SubEntry>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, SubEntry>?,
            ): Boolean = size > MAX_SUB_ENTRIES
        },
    )

    private val segmentRemap = ConcurrentHashMap<String, String>()

    private val acceptPool = Executors.newCachedThreadPool(daemonFactory("anv-proxy-accept"))
    private val fetchPool = Executors.newFixedThreadPool(FETCH_THREADS, daemonFactory("anv-proxy-fetch"))

    @Volatile
    private var server: ServerSocket? = null

    fun registerHls(
        id: String,
        masterUrl: String,
        variantUrl: String?,
        targetHeight: Int?,
        headers: Headers,
        refresh: () -> RefreshedSourceBundle?,
    ): String {
        val port = ensureServer()
        hlsEntries[id] = HlsEntry(
            id = id,
            masterUrl = masterUrl,
            activeVariantUrl = variantUrl,
            targetHeight = targetHeight,
            headers = headers,
            refresh = refresh,
        )
        return if (variantUrl != null) {
            "http://127.0.0.1:$port/hls/$id/variant.m3u8"
        } else {
            "http://127.0.0.1:$port/hls/$id/master.m3u8"
        }
    }

    fun registerSubtitle(
        id: String,
        trackLookupKey: String,
        url: String,
        headers: Headers,
        refresh: () -> RefreshedSourceBundle?,
    ): String {
        val port = ensureServer()
        subEntries[id] = SubEntry(
            id = id,
            trackLookupKey = trackLookupKey,
            url = url,
            headers = headers,
            refresh = refresh,
        )
        return "http://127.0.0.1:$port/sub/$id.vtt"
    }

    @Synchronized
    private fun ensureServer(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        acceptPool.execute {
            while (!socket.isClosed) {
                val clientSocket = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                acceptPool.execute { handleConnection(clientSocket) }
            }
        }
        return socket.localPort
    }

    private fun handleConnection(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                while (true) {
                    val headerLine = readLine(input) ?: break
                    if (headerLine.isEmpty()) break
                }

                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawTarget = parts[1]
                val out = sock.getOutputStream()

                if (method == "OPTIONS") {
                    writeResponse(out, 200, "text/plain", ByteArray(0), headOnly = true)
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    writeResponse(out, 405, "text/plain", ByteArray(0), headOnly = true)
                    return
                }

                val path = rawTarget.substringBefore('?')
                val query = rawTarget.substringAfter('?', "")
                val segments = path.trim('/').split('/')

                when (segments.firstOrNull()) {
                    "hls" -> handleHls(segments, query, method == "HEAD", out)
                    "sub" -> handleSubtitle(segments, method == "HEAD", out)
                    else -> writeResponse(out, 404, "text/plain", ByteArray(0), headOnly = method == "HEAD")
                }
            } catch (_: IOException) {
                // Player closed socket on seek or stop
            } catch (_: Exception) {
                runCatching {
                    writeResponse(socket.getOutputStream(), 502, "text/plain", ByteArray(0))
                }
            }
        }
    }

    private fun handleHls(
        segments: List<String>,
        query: String,
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
                val url = targetParam ?: run {
                    writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
                    return
                }
                val playlist = fetchVariantPlaylist(entry, url)
                writeResponse(out, 200, M3U8_CONTENT_TYPE, playlist.toByteArray(Charsets.UTF_8), headOnly)
            }
            "key", "key.bin" -> {
                val url = targetParam ?: run {
                    writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
                    return
                }
                val keyBytes = fetchEncryptionKey(entry, url)
                writeResponse(out, 200, "application/octet-stream", keyBytes, headOnly)
            }
            "init", "init.mp4" -> {
                val url = targetParam ?: run {
                    writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
                    return
                }
                val initBytes = fetchRawBinary(entry, url)
                writeResponse(out, 200, "video/mp4", initBytes, headOnly)
            }
            "seg", "seg.ts", "seg.m4s" -> {
                val url = targetParam ?: run {
                    writeResponse(out, 400, "text/plain", ByteArray(0), headOnly)
                    return
                }
                val segList = entry.segments
                val idx = segList.indexOf(url)
                val future = getOrSubmitSegment(entry, url)
                val bytes = try {
                    future.get(45, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    entry.segmentCache.remove(url)
                    throw e
                }
                if (idx >= 0) {
                    segList.drop(idx + 1).take(PREFETCH_COUNT).forEach { nextUrl ->
                        getOrSubmitSegment(entry, nextUrl)
                    }
                }
                val contentType = detectMediaContentType(bytes, url)
                writeResponse(out, 200, contentType, bytes, headOnly)
            }
            else -> writeResponse(out, 404, "text/plain", ByteArray(0), headOnly)
        }
    }

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

        var text = fetchTextWithRetry(entry.url, entry.headers)
        if (text == null) {
            refreshSubtitleEntry(entry)
            text = fetchTextWithRetry(entry.url, entry.headers)
        }
        val normalized = normalizeWebVtt(text ?: "WEBVTT\n\n")
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        if (text != null) {
            entry.cachedBytes = bytes
        }
        writeResponse(out, 200, VTT_CONTENT_TYPE, bytes, headOnly)
    }

    private fun fetchMasterPlaylist(entry: HlsEntry): String {
        var text = fetchM3u8Text(entry.masterUrl, entry.headers)
        if (text == null) {
            refreshHlsEntry(entry, force = true)
            text = fetchM3u8Text(entry.masterUrl, entry.headers)
                ?: throw IOException("Master playlist unavailable")
        }
        return if (text.contains("#EXT-X-STREAM-INF")) {
            rewriteMasterPlaylist(entry, text, entry.masterUrl)
        } else {
            rewriteMediaPlaylist(entry, text, entry.masterUrl)
        }
    }

    private fun fetchVariantPlaylist(entry: HlsEntry, requestedUrl: String): String {
        var currentUrl = requestedUrl
        var text = fetchM3u8Text(currentUrl, entry.headers)
        if (text == null) {
            if (refreshHlsEntry(entry, force = true)) {
                currentUrl = entry.activeVariantUrl ?: entry.masterUrl
                text = fetchM3u8Text(currentUrl, entry.headers)
            }
        }
        val body = text ?: throw IOException("Variant playlist unavailable")
        return if (body.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(body, currentUrl)
            val chosen = selectBestMatchingVariant(variants, entry.targetHeight)
                ?: throw IOException("No variant streams found in master playlist")
            entry.activeVariantUrl = chosen.url
            val mediaText = fetchM3u8Text(chosen.url, entry.headers)
                ?: throw IOException("Selected variant playlist unavailable")
            rewriteMediaPlaylist(entry, mediaText, chosen.url)
        } else {
            entry.activeVariantUrl = currentUrl
            rewriteMediaPlaylist(entry, body, currentUrl)
        }
    }

    private fun rewriteMasterPlaylist(entry: HlsEntry, playlistText: String, baseUrlStr: String): String {
        val baseUrl = baseUrlStr.toHttpUrlOrNull()
        return playlistText.lineSequence().joinToString("\n") { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> rawLine
                trimmed.startsWith("#") -> {
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        "URI=\"/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}\""
                    }
                }
                else -> {
                    val resolved = baseUrl?.resolve(trimmed)?.toString() ?: trimmed
                    "/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}"
                }
            }
        }
    }

    private fun rewriteMediaPlaylist(entry: HlsEntry, playlistText: String, baseUrlStr: String): String {
        val baseUrl = baseUrlStr.toHttpUrlOrNull()
        val discoveredSegments = ArrayList<String>(128)
        var nextIsNestedPlaylist = false
        var sawEncryption = false
        var sawFmp4Map = false

        val rewritten = playlistText.lineSequence().joinToString("\n") { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> rawLine
                trimmed.startsWith("#EXT-X-KEY") -> {
                    if (!trimmed.contains("METHOD=NONE", ignoreCase = true)) {
                        sawEncryption = true
                    }
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        "URI=\"/hls/${entry.id}/key.bin?u=${encodeParam(resolved)}\""
                    }
                }
                trimmed.startsWith("#EXT-X-MAP") -> {
                    sawFmp4Map = true
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        "URI=\"/hls/${entry.id}/init.mp4?u=${encodeParam(resolved)}\""
                    }
                }
                trimmed.startsWith("#") -> {
                    if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                        nextIsNestedPlaylist = true
                    }
                    URI_ATTR_REGEX.replace(rawLine) { match ->
                        val resolved = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        "URI=\"/hls/${entry.id}/pl.m3u8?u=${encodeParam(resolved)}\""
                    }
                }
                else -> {
                    val resolved = baseUrl?.resolve(trimmed)?.toString() ?: trimmed
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
        }

        entry.isEncrypted = sawEncryption
        entry.isFmp4 = sawFmp4Map

        val previousSegments = entry.segments
        entry.segments = discoveredSegments
        if (previousSegments.isNotEmpty() &&
            discoveredSegments.isNotEmpty() &&
            previousSegments != discoveredSegments
        ) {
            val count = minOf(previousSegments.size, discoveredSegments.size)
            for (i in 0 until count) {
                segmentRemap[previousSegments[i]] = discoveredSegments[i]
            }
            if (segmentRemap.size > MAX_REMAP_ENTRIES) {
                segmentRemap.clear()
                for (i in 0 until count) {
                    segmentRemap[previousSegments[i]] = discoveredSegments[i]
                }
            }
        }

        return rewritten
    }

    private fun getOrSubmitSegment(entry: HlsEntry, segmentUrl: String): Future<ByteArray> {
        synchronized(entry.segmentCache) {
            entry.segmentCache[segmentUrl]?.let { return it }
            val future = fetchPool.submit<ByteArray> {
                val raw = fetchBinaryWithRefresh(entry, segmentUrl, isSegment = true)
                if (entry.isEncrypted || entry.isFmp4) raw else cleanSegmentBytes(raw)
            }
            entry.segmentCache[segmentUrl] = future
            return future
        }
    }

    private fun fetchEncryptionKey(entry: HlsEntry, keyUrl: String): ByteArray {
        entry.keyCache[keyUrl]?.let { return it }
        val bytes = fetchBinaryWithRefresh(entry, keyUrl, isSegment = false)
        entry.keyCache[keyUrl] = bytes
        return bytes
    }

    private fun fetchRawBinary(entry: HlsEntry, url: String): ByteArray = fetchBinaryWithRefresh(entry, url, isSegment = false)

    private fun fetchBinaryWithRefresh(
        entry: HlsEntry,
        originalUrl: String,
        isSegment: Boolean,
    ): ByteArray {
        var targetUrl = segmentRemap[originalUrl] ?: originalUrl
        var lastError: Exception? = null

        repeat(4) { attempt ->
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .headers(entry.headers)
                    .build()

                client.newCall(request).execute().use { response ->
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
                                targetUrl = segmentRemap[originalUrl]
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
                    pauseMs(300L * attempt)
                }
            }
        }

        throw lastError ?: IOException("Failed to fetch stream resource")
    }

    private fun refreshHlsEntry(entry: HlsEntry, force: Boolean = false): Boolean {
        synchronized(entry) {
            val now = System.currentTimeMillis()
            val minInterval = if (force) 2_500L else 10_000L
            if (now - entry.lastRefreshMs < minInterval) {
                return true
            }
            val bundle = runCatching { entry.refresh() }.getOrNull()
            entry.lastRefreshMs = System.currentTimeMillis()
            if (bundle == null || bundle.masterUrl.isBlank()) {
                return false
            }
            entry.masterUrl = bundle.masterUrl
            entry.headers = bundle.headers
            if (entry.targetHeight != null || entry.activeVariantUrl != null) {
                val masterText = fetchM3u8Text(bundle.masterUrl, bundle.headers)
                if (masterText != null && masterText.contains("#EXT-X-STREAM-INF")) {
                    val variants = parseMasterVariants(masterText, bundle.masterUrl)
                    val matched = selectBestMatchingVariant(variants, entry.targetHeight)
                    if (matched != null) {
                        entry.activeVariantUrl = matched.url
                    }
                } else if (masterText != null) {
                    entry.activeVariantUrl = bundle.masterUrl
                }
            }
            return true
        }
    }

    private fun refreshSubtitleEntry(entry: SubEntry): Boolean {
        synchronized(entry) {
            val now = System.currentTimeMillis()
            if (now - entry.lastRefreshMs < 3_000L) return true
            val bundle = runCatching { entry.refresh() }.getOrNull()
            entry.lastRefreshMs = System.currentTimeMillis()
            if (bundle == null) return false
            entry.headers = bundle.headers
            bundle.tracksByKey[entry.trackLookupKey]?.let { freshUrl ->
                entry.url = freshUrl
                entry.cachedBytes = null
                return true
            }
            return false
        }
    }

    private fun fetchM3u8Text(url: String, headers: Headers): String? {
        val raw = fetchTextWithRetry(url, headers) ?: return null
        val trimmed = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (trimmed.startsWith("#EXTM3U")) trimmed else null
    }

    private fun fetchTextWithRetry(url: String, headers: Headers): String? {
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(url).headers(headers).build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return response.body.string()
                        response.code == 429 -> pauseMs(parseRetryAfterMs(response))
                        response.code in REFRESH_STATUS_CODES -> return null
                        else -> if (attempt == 2) return null
                    }
                }
            } catch (_: IOException) {
                if (attempt == 2) return null
                pauseMs(250L * (attempt + 1))
            }
        }
        return null
    }

    private fun normalizeWebVtt(raw: String): String {
        val stripped = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (stripped.startsWith("WEBVTT")) {
            stripped
        } else {
            "WEBVTT\n\n$stripped"
        }
    }

    private fun writeResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Bad Gateway"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

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

    private fun queryParam(query: String, key: String): String? = query
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotEmpty() }
        ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun pauseMs(ms: Long) {
        if (ms > 0L) {
            runBlocking { delay(ms) }
        }
    }

    companion object {
        private const val FETCH_THREADS = 8
        private const val PREFETCH_COUNT = 3
        private const val SEGMENT_CACHE_SIZE = 10
        private const val MAX_HLS_ENTRIES = 64
        private const val MAX_SUB_ENTRIES = 160
        private const val MAX_REMAP_ENTRIES = 4_096
        private const val MAX_HEADER_SCAN_BYTES = 262_144
        private const val MPEG_TS_PACKET_SIZE = 188
        private const val MPEG_TS_SYNC_BYTE: Byte = 0x47
        private const val M3U8_CONTENT_TYPE = "application/vnd.apple.mpegurl"
        private const val VTT_CONTENT_TYPE = "text/vtt; charset=utf-8"

        private val REFRESH_STATUS_CODES = setOf(401, 403, 404, 410)
        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        private val BANDWIDTH_REGEX = Regex("""(?<!AVERAGE-)BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
        private val FMP4_BOX_TYPES = setOf("ftyp", "styp", "moof", "sidx", "emsg", "free")

        fun parseMasterVariants(playlistText: String, masterUrl: String): List<ParsedVariantStream> {
            val baseUrl = masterUrl.toHttpUrlOrNull()
            val lines = playlistText.lines()
            val variants = mutableListOf<ParsedVariantStream>()
            var pendingWidth: Int? = null
            var pendingHeight: Int? = null
            var pendingBandwidth: Long? = null
            var waitingForUrl = false

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
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

            return variants.sortedWith(
                compareByDescending<ParsedVariantStream> { it.height ?: 0 }
                    .thenByDescending { it.bandwidth ?: 0L },
            )
        }

        private fun selectBestMatchingVariant(
            variants: List<ParsedVariantStream>,
            targetHeight: Int?,
        ): ParsedVariantStream? {
            if (variants.isEmpty()) return null
            if (targetHeight == null) return variants.first()
            return variants.firstOrNull { it.height == targetHeight }
                ?: variants.minByOrNull { abs((it.height ?: 0) - targetHeight) }
        }

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

        private fun startsWithGifSignature(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()

        private fun isFmp4Segment(bytes: ByteArray): Boolean {
            if (bytes.size < 8) return false
            val boxType = String(bytes, 4, 4, Charsets.ISO_8859_1)
            return boxType in FMP4_BOX_TYPES
        }

        private fun detectMediaContentType(bytes: ByteArray, url: String): String = when {
            bytes.isNotEmpty() && bytes[0] == MPEG_TS_SYNC_BYTE && !startsWithGifSignature(bytes) -> "video/mp2t"
            isFmp4Segment(bytes) -> "video/mp4"
            url.substringBefore('?').endsWith(".m4s", ignoreCase = true) -> "video/iso.segment"
            else -> "video/mp2t"
        }

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

        private fun daemonFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, prefix).apply { isDaemon = true }
        }
    }
}
