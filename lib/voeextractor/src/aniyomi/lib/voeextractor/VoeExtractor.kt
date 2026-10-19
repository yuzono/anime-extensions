package aniyomi.lib.voeextractor

import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.UrlUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class VoeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val clientDdos by lazy { client.newBuilder().addInterceptor(DdosGuardInterceptor(client)).build() }

    private val playlistUtils by lazy { PlaylistUtils(clientDdos, headers) }

    // Fails on a non-playlist response, which PlaylistUtils would otherwise pass on as a single video.
    private val hlsPlaylistUtils by lazy {
        val playlistClient = clientDdos.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            try {
                if (!response.isSuccessful || !response.peekBody(64).string().trimStart().removePrefix("\uFEFF").startsWith("#EXTM3U")) {
                    throw IOException("VOE: HLS playlist is unavailable")
                }
                response
            } catch (e: IOException) {
                response.close()
                throw e
            }
        }.build()
        PlaylistUtils(playlistClient, headers)
    }

    private val redirectRegex = Regex("""window.location.href\s*=\s*'([^']+)';""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        var document = clientDdos.newCall(GET(url, headers)).execute().asJsoup()
        var baseUrl = url
        val scriptData = document.selectFirst("script")?.data()
        val redirectMatch = scriptData?.let { redirectRegex.find(it) }

        if (redirectMatch != null) {
            val originalUrl = redirectMatch.groupValues[1]
            baseUrl = originalUrl
            document = clientDdos.newCall(GET(originalUrl, headers)).execute().asJsoup()
        }

        val encodedString = document.selectFirst("script[type=application/json]")?.data()
            ?.trim()?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: return emptyList()

        val decryptedJson = decryptF7(encodedString) ?: return emptyList()
        val m3u8 = decryptedJson["source"]?.jsonPrimitive?.content
        val mp4 = decryptedJson["direct_access_url"]?.jsonPrimitive?.content

        var cleanPrefix = prefix.trim()
        if (cleanPrefix.startsWith("(") && cleanPrefix.endsWith(")")) {
            cleanPrefix = cleanPrefix.substring(1, cleanPrefix.length - 1).trim()
        }
        if (cleanPrefix.endsWith("-")) {
            cleanPrefix = cleanPrefix.removeSuffix("-").trim()
        }
        val displayPrefix = if (cleanPrefix.isNotBlank()) cleanPrefix else "VOE"
        // The caption files are SubRip served from a `.srt` url behind a `text/vtt` content type,
        // which a player will refuse to parse, so they are converted before being handed over.
        val tracks = runCatching {
            (decryptedJson["captions"] as? JsonArray).orEmpty()
                .mapNotNull { caption ->
                    val obj = caption as? JsonObject ?: return@mapNotNull null
                    val file = obj["file"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = UrlUtils.fixUrl(file, baseUrl) ?: return@mapNotNull null
                    Track(url, obj["label"]?.jsonPrimitive?.content ?: "Subtitle")
                }
                .let(playlistUtils::fixSubtitles)
        }.getOrDefault(emptyList())
        val subHint = if (tracks.isNotEmpty()) " [CC ${tracks.size}]" else ""

        if (m3u8 != null) {
            try {
                hlsPlaylistUtils.extractFromHls(
                    m3u8,
                    videoNameGen = { quality ->
                        val base = if (displayPrefix == "VOE") "VOE:$quality" else "$displayPrefix - VOE $quality"
                        base + subHint
                    },
                    subtitleList = tracks,
                ).let { videoList.addAll(it) }
            } catch (e: IOException) {
                if (mp4.isNullOrBlank()) throw e
            }
        }
        // The MP4 duplicates the top HLS variant, so it is only offered when HLS is unavailable.
        if (videoList.isNotEmpty()) return videoList
        if (mp4 != null) {
            val videoHeaders = headers.newBuilder().set("Referer", baseUrl).build()
            val dimensions = mp4Dimensions(mp4, videoHeaders)
            val resolution = dimensions?.let { (width, height) -> "${playlistUtils.standardQuality(height.toString())} (${width}x$height)" }
                ?: "Unknown quality"
            val mp4Quality = if (displayPrefix == "VOE") "VOE:MP4 - $resolution" else "$displayPrefix - VOE MP4 - $resolution"
            videoList.add(
                Video(
                    url = mp4,
                    quality = mp4Quality + subHint,
                    videoUrl = mp4,
                    headers = videoHeaders,
                    subtitleTracks = tracks,
                ),
            )
        }

        return videoList
    }

    private fun mp4Dimensions(url: String, headers: Headers): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, headers.toMap())
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != null && height != null && width > 0 && height > 0) width to height else null
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun decryptF7(p8: String): JsonObject? = try {
        val vF = rot13(p8)
        val vF2 = replacePatterns(vF)
        val vF3 = removeUnderscores(vF2)
        val vF4 = base64Decode(vF3)
        val vF5 = charShift(vF4, 3)
        val vF6 = reverse(vF5)
        val vAtob = base64Decode(vF6)
        json.decodeFromString<JsonObject>(vAtob)
    } catch (e: Exception) {
        Log.e("VoeExtractor", "Decryption error: ${e.message}")
        null
    }

    private fun rot13(input: String): String = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private val patternsRegex = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").joinToString("|") { Regex.escape(it) }.toRegex()

    private fun replacePatterns(input: String): String = input.replace(patternsRegex, "_")

    private fun removeUnderscores(input: String): String = input.replace("_", "")

    private fun charShift(input: String, shift: Int): String = input.map { (it.code - shift).toChar() }.joinToString("")

    private fun reverse(input: String): String = input.reversed()

    private fun base64Decode(input: String): String {
        val decodedBytes = Base64.decode(input, Base64.DEFAULT)
        return String(decodedBytes, Charsets.ISO_8859_1)
    }
}
