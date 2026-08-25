package eu.kanade.tachiyomi.animeextension.en.uniquestream

import android.util.Base64
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
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local proxy for UniqueStream's signed HLS media. Playlists reference `key.bin`
 * files that are not raw keys: they are base64 text holding AES-128-CBC ciphertext
 * that only decrypts to the real content key when fetched with an `x-am-media-id`
 * header, using sha256("key"+mediaId)[:16] and sha256("iv"+mediaId)[:16] as key
 * and IV. Masters (video variants + audio renditions), media playlists and
 * segments are all proxied here; segments are decrypted so the player receives
 * plain MPEG-TS.
 */
object UniqueStreamHlsServer : NanoHTTPD(0) {

    val port: Int
        get() = super.getListeningPort()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    private data class HlsKey(val url: String, val iv: String?)

    // Recovered content keys, cached so each segment does not re-fetch `key.bin`.
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    override fun start() {
        super.start()
        isRunning = true
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    fun setUp(client: OkHttpClient) {
        this.client = client
        ensureStarted()
    }

    fun localPlaylistUrl(playlistUrl: String, mid: String, height: Int? = null): String = buildString {
        append("http://localhost:$port/m3u8?url=${encode(playlistUrl)}&mid=${encode(mid)}")
        height?.let { append("&vheight=$it") }
    }

    override fun handle(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return badRequest("Missing url parameter")
        val mid = session.parameters["mid"]?.first()
            ?: return badRequest("Missing mid parameter")
        val height = session.parameters["vheight"]?.first()

        return try {
            val playlist = fetchString(url)
            val content = rewritePlaylist(playlist, url, mid, height)
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
            ?: return badRequest("Missing url parameter")
        val keyUrl = session.parameters["key"]?.first()
        val iv = session.parameters["iv"]?.first()
        val mid = session.parameters["mid"]?.first()
            ?: return badRequest("Missing mid parameter")

        return try {
            val data = fetchBytes(url)
            val plaintext = if (keyUrl.isNullOrBlank()) {
                data
            } else {
                val ivHex = iv ?: throw IOException("Missing AES-128 IV for encrypted segment")
                decryptAes128Cbc(data, fetchRealKey(keyUrl, mid), ivHex)
            }
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(plaintext))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) start()
    }

    private fun badRequest(message: String): Response = newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, message)

    private fun rewritePlaylist(content: String, originalUrl: String, mid: String, requestedHeight: String?): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        return if (content.contains("#EXT-X-STREAM-INF")) {
            rewriteMaster(content, baseHttpUrl, mid, requestedHeight)
        } else {
            rewriteMedia(content, baseHttpUrl, mid)
        }
    }

    // Masters keep their audio renditions and video variants, but every URI is
    // pointed back at this proxy; with a height filter only that variant stays.
    private fun rewriteMaster(content: String, baseHttpUrl: HttpUrl?, mid: String, requestedHeight: String?): String {
        val header = mutableListOf<String>()
        val renditions = mutableListOf<String>()
        val variants = mutableListOf<Pair<String, String>>() // STREAM-INF tag to resolved uri

        var pendingStreamInf: String? = null
        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingStreamInf = line.trim()
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> Unit
                pendingStreamInf != null && !line.startsWith("#") && line.isNotBlank() -> {
                    variants.add(pendingStreamInf!! to resolveHlsUrl(baseHttpUrl, line.trim()))
                    pendingStreamInf = null
                }
                pendingStreamInf != null -> Unit
                line.startsWith("#EXT-X-MEDIA:") -> renditions.add(
                    rewriteUriAttribute(line, baseHttpUrl) { localPlaylistUrl(it, mid) },
                )
                else -> header.add(line)
            }
        }

        val chosen = if (requestedHeight == null) {
            variants
        } else {
            variants.filter { (tag, _) -> variantHeight(tag) == requestedHeight }.ifEmpty { variants }
        }

        return (header + renditions + chosen.flatMap { (tag, uri) -> listOf(tag, localPlaylistUrl(uri, mid)) })
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun variantHeight(streamInfLine: String): String? = parseHlsAttributes(streamInfLine.substringAfter(":"))["RESOLUTION"]?.substringAfterLast('x')

    private fun rewriteMedia(content: String, baseHttpUrl: HttpUrl?, mid: String): String {
        val modifiedLines = mutableListOf<String>()
        var mediaSequence = 0L
        var segmentSequence = 0L
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
                    currentKey = if (attributes["METHOD"]?.uppercase() == "AES-128" && !attributes["URI"].isNullOrBlank()) {
                        HlsKey(
                            url = resolveHlsUrl(baseHttpUrl, attributes["URI"]!!),
                            iv = attributes["IV"]?.normalizeHlsIv(),
                        )
                    } else {
                        null
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> modifiedLines.add(
                    rewriteUriAttribute(line, baseHttpUrl) { localSegmentUrl(it, currentKey, segmentSequence, mid) },
                )
                line.startsWith("#") || line.isBlank() -> modifiedLines.add(line)
                else -> {
                    modifiedLines.add(localSegmentUrl(resolveHlsUrl(baseHttpUrl, line), currentKey, segmentSequence, mid))
                    segmentSequence++
                }
            }
        }

        return modifiedLines.joinToString("\n")
    }

    private fun localSegmentUrl(url: String, key: HlsKey?, sequence: Long, mid: String): String = buildString {
        append("http://localhost:$port/segment?url=${encode(url)}")
        key?.let {
            append("&key=${encode(it.url)}")
            append("&iv=${encode(it.iv ?: sequence.toHlsIv())}")
        }
        append("&mid=${encode(mid)}")
    }

    private fun rewriteUriAttribute(line: String, baseHttpUrl: HttpUrl?, transform: (String) -> String): String = Regex("""URI="([^"]*)"""").replace(line) { match ->
        "URI=\"${transform(resolveHlsUrl(baseHttpUrl, match.groupValues[1]))}\""
    }

    /**
     * Fetches `key.bin` (which must be requested with the media id header),
     * then recovers the real 16-byte content key.
     */
    private fun fetchRealKey(keyUrl: String, mid: String): ByteArray {
        val cacheKey = "$mid|$keyUrl"
        keyCache[cacheKey]?.let { return it }

        val request = Request.Builder()
            .url(keyUrl)
            .header("x-am-media-id", mid)
            .build()
        val body = requireClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch key: ${response.code}")
            response.body.string().trim()
        }
        val ciphertext = Base64.decode(body, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256")
        val key = digest.digest("key$mid".toByteArray()).copyOf(16)
        val iv = digest.digest("iv$mid".toByteArray()).copyOf(16)

        val recovered = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to recover HLS key", e)
        }

        if (keyCache.size > 32) keyCache.clear()
        keyCache[cacheKey] = recovered
        return recovered
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun fetchString(url: String): String = requireClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch playlist: ${response.code}")
        }
        response.body.string()
    }

    private fun fetchBytes(url: String): ByteArray = requireClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch resource: ${response.code}")
        }
        response.body.bytes()
    }

    private fun requireClient(): OkHttpClient = client ?: throw IOException("UniqueStream HLS server is not initialized")

    private fun parseHlsAttributes(line: String): Map<String, String> {
        val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        return regex.findAll(line.substringAfter(":")).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x").removePrefix("0X").padStart(32, '0')

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
