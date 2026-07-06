package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AnimePaheHlsServer : NanoHTTPD(0) {

    val port: Int
        get() = super.getListeningPort()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var mp4Client: OkHttpClient? = null

    private val mp4Headers = ConcurrentHashMap<String, Headers>()

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private data class HlsKey(val url: String, val iv: String?)

    override fun start() {
        super.start()
        isRunning = true
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    fun processVideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        this.client = client
        ensureStarted()
        return videos.map { video ->
            if (video.url.contains(".m3u8", ignoreCase = true)) {
                video.copyWithLocalUrl(createLocalM3u8Url(video.url))
            } else {
                video
            }
        }
    }

    fun processMp4VideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        mp4Client = client
        ensureStarted()
        return videos.map { video ->
            val localUrl = createLocalMp4Url(video.url)
            mp4Headers[video.url] = video.headers ?: Headers.Builder().build()
            video.copyWithLocalMp4Url(localUrl)
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        session.uri.startsWith("/mp4") -> handleMp4Request(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val headers = extractHeadersFromSession(session)
            val playlist = fetchString(url, headers)
            val content = rewritePlaylist(playlist, url)
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val headers = extractHeadersFromSession(session)
            val keyUrl = session.parameters["key"]?.first()
            val iv = session.parameters["iv"]?.first()
            val data = fetchSegment(url, headers, keyUrl, iv)
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(data))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleMp4Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val upstream = fetchMp4(url, session)
            val body = upstream.body
            val contentLength = upstream.header("Content-Length")?.toLongOrNull() ?: -1L
            val contentType = upstream.header("Content-Type") ?: "video/mp4"
            val status = Status.lookup(upstream.code) ?: Status.OK
            val stream = object : FilterInputStream(body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        upstream.close()
                    }
                }
            }

            val localResponse = if (contentLength >= 0) {
                newFixedLengthResponse(status, contentType, stream, contentLength)
            } else {
                newChunkedResponse(status, contentType, stream)
            }
            localResponse.apply {
                upstream.header("Accept-Ranges")?.let { addHeader("Accept-Ranges", it) }
                upstream.header("Content-Range")?.let { addHeader("Content-Range", it) }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) {
            start()
        }
    }

    private fun createLocalM3u8Url(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://localhost:$port/m3u8?url=$encodedUrl"
    }

    private fun createLocalMp4Url(mp4Url: String): String {
        val encodedUrl = URLEncoder.encode(mp4Url, Charsets.UTF_8.name())
        return "http://localhost:$port/mp4?url=$encodedUrl"
    }

    private fun Video.copyWithLocalUrl(localUrl: String): Video = Video(
        videoUrl = localUrl,
        url = url,
        quality = quality,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        headers = headers,
    )

    private fun Video.copyWithLocalMp4Url(localUrl: String): Video = Video(
        videoUrl = localUrl,
        url = localUrl,
        quality = quality,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        headers = headers,
    )

    private fun extractHeadersFromSession(session: IHTTPSession): Headers = Headers.Builder().apply {
        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "cache-control", "pragma",
                -> add(key, value)
            }
        }
    }.build()

    private fun fetchMp4(url: String, session: IHTTPSession): okhttp3.Response {
        val headers = Headers.Builder().apply {
            mp4Headers[url]?.let { sourceHeaders ->
                for (index in 0 until sourceHeaders.size) {
                    add(sourceHeaders.name(index), sourceHeaders.value(index))
                }
            }
            session.headers["range"]?.let { set("Range", it) }
        }.build()

        val response = requireMp4Client().newCall(Request.Builder().url(url).headers(headers).build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Failed to fetch MP4: $code")
        }
        return response
    }

    private fun fetchString(url: String, headers: Headers): String = requireClient().newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch playlist: ${response.code}")
        }
        response.body.string()
    }

    private fun fetchSegment(
        url: String,
        headers: Headers,
        keyUrl: String?,
        iv: String?,
    ): ByteArray {
        val rawData = fetchBytes(url, headers)
        return if (keyUrl.isNullOrBlank()) {
            rawData
        } else {
            val ivHex = iv ?: throw IOException("Missing AES-128 IV for encrypted segment")
            decryptAes128Cbc(rawData, fetchBytes(keyUrl, headers), ivHex)
        }
    }

    private fun fetchBytes(url: String, headers: Headers): ByteArray = requireClient().newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch resource: ${response.code}")
        }
        response.body.bytes()
    }

    private fun requireClient(): OkHttpClient = client ?: throw IOException("AnimePahe HLS server is not initialized")

    private fun requireMp4Client(): OkHttpClient = mp4Client ?: throw IOException("AnimePahe MP4 server is not initialized")

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()
        var mediaSequence = 0L
        var segmentSequence = mediaSequence
        var currentKey: HlsKey? = null

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseHlsAttributes(line)
                    when (attributes["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                currentKey = null
                                modifiedLines.add(line)
                            } else {
                                currentKey = HlsKey(
                                    url = resolveHlsUrl(baseHttpUrl, keyUri),
                                    iv = attributes["IV"]?.normalizeHlsIv(),
                                )
                            }
                        }
                        "NONE" -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                        else -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                    }
                }
                line.startsWith("#") || line.isBlank() -> modifiedLines.add(line)
                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line)
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        modifiedLines.add(createLocalM3u8Url(resolvedUrl))
                    } else {
                        modifiedLines.add(createLocalSegmentUrl(resolvedUrl, currentKey, segmentSequence))
                        segmentSequence++
                    }
                }
            }
        }

        return modifiedLines.joinToString("\n")
    }

    private fun parseHlsAttributes(line: String): Map<String, String> = hlsAttributeRegex.findAll(line.substringAfter(":")).associate {
        it.groupValues[1] to it.groupValues[2].trim('"')
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    private fun createLocalSegmentUrl(segmentUrl: String, key: HlsKey?, sequence: Long): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        return buildString {
            append("http://localhost:$port/segment?url=$encodedUrl")
            if (key != null) {
                append("&key=")
                append(URLEncoder.encode(key.url, Charsets.UTF_8.name()))
                append("&iv=")
                append(URLEncoder.encode(key.iv ?: sequence.toHlsIv(), Charsets.UTF_8.name()))
            }
        }
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }

        val normalizedIv = iv.normalizeHlsIv()
        if (normalizedIv.length != 32) {
            throw IOException("Invalid AES-128 IV length: ${normalizedIv.length}")
        }

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(normalizedIv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x")
        .removePrefix("0X")
        .padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
