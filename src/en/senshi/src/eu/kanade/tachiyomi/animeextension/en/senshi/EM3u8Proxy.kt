package eu.kanade.tachiyomi.animeextension.en.senshi

import android.util.Log
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

class EM3u8Proxy(
    private val baseHeaders: Headers,
    client: OkHttpClient,
) : NanoHTTPD("127.0.0.1", 0) {

    private val proxyClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
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
                // Relative URIs must resolve against the FINAL (post-redirect) URL —
                // resolver-issued URLs redirect across CDN hosts.
                val finalUrl = res.request.url.toString()
                val bytes = res.body.bytes()

                when {
                    // Content sniff wins over extension: playlist URLs can lose .m3u8/.txt
                    // across redirects; a misroute surfaces as unrewritten relative lines in
                    // the player (subtle failure mode).
                    bytes.startsWithAscii("#EXTM3U") || isPlaylist(finalUrl) ->
                        serveManifest(bytes.toString(Charsets.UTF_8), finalUrl, audio)

                    // Episode images and subtitles; both need the referer (subtitles also
                    // sit behind Cloudflare), which the player does not send.
                    else -> {
                        val path = res.request.url.encodedPath
                        val mime = when {
                            path.endsWith(".ass") -> "text/x-ssa"
                            path.endsWith(".vtt") -> "text/vtt"
                            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                            path.endsWith(".webp") -> "image/webp"
                            path.endsWith(".png") -> "image/png"
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

    /**
     * Child playlists are proxied, everything else is made absolute for the player.
     * Playlist-ness comes from the HLS structure, not the extension: bcdn serves
     * child playlists as `/p/<base64>.jpg` and segments as `/c/<base64>.jpg`.
     */
    private fun serveManifest(text: String, parentUrl: String, audioLanguage: String? = null): Response {
        if ("#EXT-X-KEY" in text || "#EXT-X-SESSION-KEY" in text) {
            Log.w(TAG, "manifest carries #EXT-X-KEY — playlist/segment encryption may be back: $parentUrl")
        }
        val filtered = audioLanguage?.let { filterAudioRenditions(text, it) } ?: text
        val parent = parentUrl.toHttpUrl()
        val isMaster = "#EXT-X-STREAM-INF" in filtered
        val out = filtered.split("\n").joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> line.replace(URI_REGEX) { m ->
                    val resolved = parent.resolve(m.groupValues[1])?.toString() ?: return@replace m.value
                    val childPlaylist = isMaster && line.startsWith("#EXT-X-MEDIA")
                    "URI=\"${if (childPlaylist) proxyUrl(resolved) else resolved}\""
                }
                else -> {
                    val resolved = parent.resolve(line)?.toString() ?: line
                    if (isMaster) proxyUrl(resolved) else resolved // segment: player-direct
                }
            }
        }
        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", out)
    }

    /**
     * Keeps only the TYPE=AUDIO #EXT-X-MEDIA rendition with LANGUAGE=[language]
     * ("ja"/"en"), forcing DEFAULT=YES (explicit DEFAULT=NO is flipped;
     * a rendition without a DEFAULT attribute is left as-is) — turns a shared
     * both-audio stream into a single-language hoster. Falls back to the
     * untouched manifest when the layout is unknown (no renditions / nothing
     * matches) so audio is never lost.
     */
    private fun filterAudioRenditions(manifest: String, language: String): String {
        val pattern = "LANGUAGE=\"$language\""
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

    private fun isPlaylist(url: String) = url.substringBefore('?').let { it.endsWith(".m3u8") || it.endsWith(".txt") }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean = size >= prefix.length && String(this, 0, prefix.length, Charsets.US_ASCII) == prefix

    companion object {
        private const val TAG = "EM3u8Proxy"
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
    }
}
