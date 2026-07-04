package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimePaheHlsServer(
    private val client: OkHttpClient,
    port: Int = 0,
) : NanoHTTPD(port) {

    val port: Int
        get() = super.getListeningPort()

    @Volatile
    private var isRunning = false

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

    fun processVideoList(videos: List<Video>): List<Video> {
        ensureStarted()
        return videos.map { video ->
            if (video.url.contains(".m3u8", ignoreCase = true)) {
                video.copyWithLocalUrl(createLocalM3u8Url(video.url))
            } else {
                video
            }
        }
    }

    override fun handle(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val content = runBlocking {
                val headers = extractHeadersFromSession(session)
                val playlist = fetchString(url, headers)
                rewritePlaylist(playlist, url)
            }
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        return try {
            val data = runBlocking {
                val headers = extractHeadersFromSession(session)
                val keyUrl = session.parameters["key"]?.first()
                val iv = session.parameters["iv"]?.first()
                fetchSegment(url, headers, keyUrl, iv)
            }
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(data))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun ensureStarted() {
        if (!isRunning) {
            start()
        }
    }

    private fun createLocalM3u8Url(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://localhost:$port/m3u8?url=$encodedUrl"
    }

    private fun Video.copyWithLocalUrl(localUrl: String): Video = Video(
        videoUrl = localUrl,
        url = url,
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

    private suspend fun fetchString(url: String, headers: Headers): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch playlist: ${response.code}")
            }
            response.body.string()
        }
    }

    private suspend fun fetchSegment(
        url: String,
        headers: Headers,
        keyUrl: String?,
        iv: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val rawData = fetchBytes(url, headers)
        if (keyUrl.isNullOrBlank()) {
            rawData
        } else {
            val ivHex = iv ?: throw IOException("Missing AES-128 IV for encrypted segment")
            decryptAes128Cbc(rawData, fetchBytes(keyUrl, headers), ivHex)
        }
    }

    private suspend fun fetchBytes(url: String, headers: Headers): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch resource: ${response.code}")
            }
            response.body.bytes()
        }
    }

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
                    val segmentUrl = resolveHlsUrl(baseHttpUrl, line)
                    modifiedLines.add(createLocalSegmentUrl(segmentUrl, currentKey, segmentSequence))
                    segmentSequence++
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

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(normalizedIv.hexToByteArray()),
        )
        return cipher.doFinal(data)
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x")
        .removePrefix("0X")
        .padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
