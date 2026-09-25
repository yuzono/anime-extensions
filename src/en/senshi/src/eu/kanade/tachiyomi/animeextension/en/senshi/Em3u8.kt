package eu.kanade.tachiyomi.animeextension.en.senshi

import android.content.SharedPreferences
import android.util.Base64
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * vidcloud/bcdn1 "EM3U8v1" playlist encryption, recovered from the site's
 * WatchPage-*.js bundle (installed there as a custom hls.js `pLoader`):
 *
 *   body := "EM3U8v1:" + base64( IV[12] || AES-256-GCM(ciphertext) || tag[16] )
 *   key  := arrayA[i] xor arrayB[i]   (two hardcoded 32-byte arrays in the bundle)
 *
 * Only playlists are encrypted (master + variant + audio renditions, all *.txt /
 * *.m3u8); segments are plain MPEG-TS behind path-embedded signatures — the .jpg
 * extension is camouflage only (verified: 0x47 sync byte at 0 and 188).
 * Non-prefixed bodies pass through untouched. A wrong key fails the GCM auth
 * tag with certainty, which doubles as our runtime key validation.
 */
object Em3u8 {
    const val PREFIX = "EM3U8v1:"

    fun isEncrypted(body: String) = body.startsWith(PREFIX)

    fun decrypt(body: String, key: ByteArray): String {
        val data = Base64.decode(body.substring(PREFIX.length), Base64.DEFAULT)
        check(data.size >= 29) { "Truncated encrypted playlist payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}

/**
 * Dynamic key extraction:
 *   1. Try the persisted key (SharedPreferences).
 *   2. On GCM auth-tag failure (definitive wrong-key signal), re-extract from the
 *      site's bundles: "/" -> index-*.js -> WatchPage-*.js -> the two 32-byte arrays.
 *   3. Fall back to the hardcoded constant below if extraction fails.
 * Extraction is order-agnostic (XOR is symmetric), so we don't care which
 * array is `ir` and which is `lr`.
 */
class Em3u8KeyStore(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
    private val baseHeaders: Headers,
    private val baseUrl: () -> String,
) {
    @Volatile
    private var cachedKey: ByteArray? = loadSaved()

    private fun loadSaved(): ByteArray? = preferences.getString(KEY_PREF, null)?.let { str ->
        runCatching { str.split(",").map { it.trim().toInt().toByte() }.toByteArray() }.getOrNull()
    }

    private fun currentKey(): ByteArray = cachedKey ?: FALLBACK_KEY

    /** Blocking; called from proxy threads. */
    @Synchronized
    fun decryptWithRetry(body: String): String {
        if (!Em3u8.isEncrypted(body)) return body
        try {
            return Em3u8.decrypt(body, currentKey())
        } catch (_: BadPaddingException) {
            // GCM auth-tag failure == wrong key. Fall through to refresh.
        }
        val fresh = refresh()
            ?: throw IllegalStateException("EM3U8 key refresh failed; update FALLBACK_KEY (see its comment)")
        return Em3u8.decrypt(body, fresh)
    }

    /** Walks "/" -> index chunk -> WatchPage chunk and XORs the two key arrays. */
    fun refresh(): ByteArray? = runCatching {
        val root = baseUrl()

        val home = get("$root/")
        val indexName = INDEX_SCRIPT_REGEX.find(home)?.groupValues?.get(1) ?: return@runCatching null
        val indexJs = get("$root/assets/$indexName.js")

        val chunkName = WATCH_CHUNK_REGEX.find(indexJs)?.groupValues?.get(1) ?: return@runCatching null
        val chunkJs = get("$root/assets/$chunkName.js")

        // Primary: the two arrays are declared back-to-back in the bundle.
        val pair = KEY_PAIR_REGEX.find(chunkJs)?.groupValues
        val key = if (pair != null) {
            // Capture groups 1 & 2: groupValues[0] is the full match text.
            xor(parseArray(pair[1]), parseArray(pair[2]))
                // If the adjacent-pair capture is malformed (site restructured),
                // fall through to the standalone-array scan instead of failing.
                ?: run {
                    val arrays = KEY_ARRAY_REGEX.findAll(chunkJs).mapNotNull {
                        parseArray(it.groupValues[1])
                    }.toList()
                    if (arrays.size == 2) xor(arrays[0], arrays[1]) else null
                }
        } else {
            val arrays = KEY_ARRAY_REGEX.findAll(chunkJs).mapNotNull {
                parseArray(it.groupValues[1])
            }.toList()
            if (arrays.size == 2) xor(arrays[0], arrays[1]) else null
        } ?: return@runCatching null

        cachedKey = key
        preferences.edit()
            .putString(KEY_PREF, key.joinToString(",") { (it.toInt() and 0xFF).toString() })
            .apply()
        key
    }.getOrNull()

    private fun parseArray(csv: String): ByteArray? = csv.split(",")
        .map { it.trim().toIntOrNull() ?: return null }
        .takeIf { it.size == 32 && it.all { v -> v in 0..255 } }
        ?.map { it.toByte() }
        ?.toByteArray()

    private fun xor(a: ByteArray?, b: ByteArray?): ByteArray? {
        if (a == null || b == null || a.size != 32 || b.size != 32) return null
        return ByteArray(32) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }
    private fun get(url: String): String = client.newCall(Request.Builder().url(url).headers(baseHeaders).build())
        .execute().use { if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}") else it.body.string() }

    companion object {
        private const val KEY_PREF = "em3u8_key"

        // "<script ... src="/assets/index-5Esc5H_t.js">" in the site HTML.
        private val INDEX_SCRIPT_REGEX = Regex("""assets/(index-[A-Za-z0-9_-]+)\.js""")

        // Vite dynamic-import reference inside the entry chunk.
        private val WATCH_CHUNK_REGEX = Regex("""WatchPage-([A-Za-z0-9_-]+)\.js""")

        // Two adjacent declarations (separator is non-numeric in the current bundle).
        private val KEY_PAIR_REGEX = Regex(
            """Uint8Array\.from\(\[((?:\d{1,3},){31}\d{1,3})\]\)[^\d]{1,40}?Uint8Array\.from\(\[((?:\d{1,3},){31}\d{1,3})\]\)""",
        )
        private val KEY_ARRAY_REGEX = Regex("""Uint8Array\.from\(\[((?:\d{1,3},){31}\d{1,3})\]\)""")

        /**
         * Fallback key — arrayA[i] xor arrayB[i] from WatchPage-*.js.
         *
         * Manual update if dynamic extraction breaks:
         *   1. Open a Senshi watch page in a browser.
         *   2. Sources -> assets/WatchPage-*.js -> find the two `Uint8Array.from([...])`
         *      literals with exactly 32 entries.
         *   3. XOR them pairwise, hex-encode, paste below.
         *
         * Last verified: 2026-09-20 (decrypts master + variant + audio renditions)
         */
        private val FALLBACK_KEY = "6EE2721327ED469BB6D93AB9B7A838045190B5BA85D9CEA3B1E17805F7B4AEF6"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * Thin local loopback proxy. Decrypts + rewrites EM3U8 playlists (master, video
 * variants, audio renditions) and serves subtitles with browser-matching headers.
 * Segment URLs are rewritten to absolute CDN URLs and fetched by the player
 * DIRECTLY — the proxy never touches segment bytes.
 */
class Em3u8Proxy(
    private val baseHeaders: Headers,
    client: OkHttpClient,
    private val keyStore: Em3u8KeyStore,
) : NanoHTTPD("127.0.0.1", 0) {

    private val proxyClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    fun proxyUrl(original: String): String = "http://127.0.0.1:$listeningPort/proxy?url=${URLEncoder.encode(original, "UTF-8")}"

    override fun handle(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")
        val audio = session.parameters["audio"]?.firstOrNull()
        return try {
            proxyClient.newCall(Request.Builder().url(url).headers(baseHeaders).build()).execute().use { res ->
                if (!res.isSuccessful) {
                    return newFixedLengthResponse(
                        Status.lookup(res.code) ?: Status.INTERNAL_ERROR,
                        "text/plain",
                        "Upstream error: ${res.code}",
                    )
                }

                when {
                    // Text paths — String is safe here.
                    isPlaylist(url) -> serveManifest(keyStore.decryptWithRetry(res.body.string()), url, audio)
                    isSubtitle(url) -> newFixedLengthResponse(Status.OK, subtitleMime(url), res.body.string())
                        .also { it.addHeader("Access-Control-Allow-Origin", "*") }

                    // For episode images; they require referer, which app does not pass.
                    // We simply pass them through the proxy with referer, fixing thumbnails.
                    else -> {
                        val bytes = res.body.bytes()
                        val mime = when {
                            url.endsWith(".jpg") || url.endsWith(".jpeg") -> "image/jpeg"
                            url.endsWith(".webp") -> "image/webp"
                            url.endsWith(".png") -> "image/png"
                            else -> "application/octet-stream"
                        }
                        newFixedLengthResponse(Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                    }
                }
            }
        } catch (e: Exception) {
            val status = if (e is java.net.SocketTimeoutException) Status.SERVICE_UNAVAILABLE else Status.INTERNAL_ERROR
            newFixedLengthResponse(status, "text/plain", e.toString())
        }
    }

    private fun serveManifest(text: String, parentUrl: String, audioRendition: String? = null): Response {
        val filtered = audioRendition?.let { filterAudioRenditions(text, it) } ?: text
        val parent = parentUrl.toHttpUrl()
        val out = filtered.split("\n").joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> line.replace(URI_REGEX) { m ->
                    val resolved = parent.resolve(m.groupValues[1])?.toString() ?: return@replace m.value
                    if (isPlaylist(resolved)) "URI=\"${proxyUrl(resolved)}\"" else m.value
                }
                isPlaylist(line) -> proxyUrl(parent.resolve(line)?.toString() ?: line)
                else -> parent.resolve(line)?.toString() ?: line // segment: absolute, player-direct
            }
        }
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", out)
    }

    /**
     * Keeps only the TYPE=AUDIO #EXT-X-MEDIA rendition whose URI matches [pattern]
     * ("0_ja"/"1_en"), forcing it DEFAULT=YES — turns a shared both-audio stream
     * into a single-language hoster. Falls back to the untouched manifest when the
     * layout is unknown (no renditions / nothing matches) so audio is never lost.
     */
    private fun filterAudioRenditions(manifest: String, pattern: String): String {
        val lines = manifest.split("\n")
        val audioMedia = lines.filter { it.trimStart().startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in it }
        if (audioMedia.isEmpty()) return manifest
        val matching = audioMedia.filter { pattern in it }
        if (matching.isEmpty() || matching.size == audioMedia.size) return manifest

        return lines.joinToString("\n") { line ->
            when {
                !line.trimStart().startsWith("#EXT-X-MEDIA") || "TYPE=AUDIO" !in line -> line
                pattern in line -> if ("DEFAULT=YES" in line) line else line.replace("DEFAULT=NO", "DEFAULT=YES")
                else -> "" // strip the other language's audio rendition
            }
        }
    }

    // Playlists are .m3u8 or .txt (bcdn convention).
    // Segments are .jpg.
    private fun isPlaylist(url: String) = url.substringBefore('?').let { it.endsWith(".m3u8") || it.endsWith(".txt") }
    private fun isSubtitle(url: String) = url.endsWith(".ass") || url.endsWith(".vtt")
    private fun subtitleMime(url: String) = when {
        url.endsWith(".ass") -> "text/x-ass"
        url.endsWith(".vtt") -> "text/vtt"
        else -> "text/plain"
    }

    companion object {
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
    }
}
