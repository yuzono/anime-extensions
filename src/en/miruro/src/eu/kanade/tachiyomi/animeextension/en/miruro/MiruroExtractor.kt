package eu.kanade.tachiyomi.animeextension.en.miruro

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.lib.jsunpacker.JsUnpacker
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.zip.GZIPInputStream

class MiruroExtractor(
    private val client: OkHttpClient,
    private val pipeKey: ByteArray,
    private val headers: Headers,
    private val resolveDisplayName: (String) -> String,
) {

    companion object {
        private const val TAG = "MiruroExtractor"
        private val M3U8_REGEX = Regex("""(https?://[^\s\\'"]+\.m3u8[^\s\\'"]*?)""")
    }

    fun providerDisplayName(key: String): String = resolveDisplayName(key)

    fun decryptResponse(response: Response): String {
        val obfuscated = response.header("x-obfuscated") ?: "1"
        val bodyBytes = response.body.bytes()

        val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
        if (obfuscated != "2") {
            return bodyStr
        }

        if (bodyStr.isEmpty()) {
            Log.e(TAG, "Empty response body from server")
            return ""
        }

        return try {
            val decoded = Base64.decode(bodyStr, Base64.URL_SAFE)
            val data = decoded.mapIndexed { i, b ->
                (b.toInt() xor pipeKey[i % pipeKey.size].toInt()).toByte()
            }.toByteArray()

            GZIPInputStream(java.io.ByteArrayInputStream(data)).use { gzipStream ->
                gzipStream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt response from server: ${e.message}")
            ""
        }
    }

    fun parseStreamsFromResponse(
        response: Response,
        subType: String?,
        providerKey: String = "",
    ): List<Video> {
        val json = try {
            response.use(::decryptResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt stream response: ${e.message}")
            return emptyList()
        }

        val sourcesDto = try {
            SourcesResponseDto.parse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sources response: ${e.message}")
            return emptyList()
        }

        if (sourcesDto.streams.isEmpty()) {
            Log.w(TAG, "Empty streams array in response (subType=$subType)")
            return emptyList()
        }

        val subTypeLabel = when (subType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            "h-sub" -> "Hard Sub"
            null -> null
            else -> subType.replaceFirstChar { it.uppercase() }
        }

        val subtitles = sourcesDto.subtitles
            .filter { it.url.isNotEmpty() }
            .map { sub ->
                Track(sub.url, sub.label.ifEmpty { sub.language })
            }

        val videos = mutableListOf<Video>()

        for (stream in sourcesDto.streams) {
            if (stream.url.isEmpty()) continue

            val qualityInt = stream.quality.toIntOrNull() ?: 0
            val width = stream.resolution?.width ?: 0
            val height = stream.resolution?.height ?: 0

            val streamTypeLabel = stream.type.uppercase()

            val qualityLabel = buildString {
                if (providerKey.isNotEmpty()) append("${providerDisplayName(providerKey)} - ")
                append("${qualityInt}p")
                if (subTypeLabel != null) append(" $subTypeLabel")
                if (width > 0 && height > 0) append(" - ${width}x$height")
                if (stream.codec.isNotEmpty()) append(" ${stream.codec}")
                if (stream.audio.isNotEmpty()) append(" ${stream.audio}")
                if (stream.fansub.isNotEmpty()) append(" ${stream.fansub}")
                append(" $streamTypeLabel")
            }

            when (stream.type.lowercase()) {
                "hls" -> {
                    val videoHeaders = if (stream.referer.isNotEmpty()) {
                        headers.newBuilder().set("Referer", stream.referer).build()
                    } else {
                        headers
                    }
                    videos.add(
                        Video(stream.url, qualityLabel, stream.url, videoHeaders, subtitleTracks = subtitles),
                    )
                }
                "embed" -> {
                    val embedVideos = extractFromKwikEmbed(stream.url, stream.referer, qualityLabel, subtitles)
                    if (embedVideos.isNotEmpty()) {
                        videos.addAll(embedVideos)
                    } else {
                        Log.w(TAG, "Failed to extract from kwik embed: ${stream.url}")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown stream type '${stream.type}', skipping: ${stream.url}")
                }
            }
        }

        return videos
    }

    private fun extractFromKwikEmbed(
        embedUrl: String,
        referer: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> {
        return try {
            val requestHeaders = buildKwikEPageHeaders(referer)
            val html = client.newCall(
                okhttp3.Request.Builder()
                    .url(embedUrl)
                    .headers(requestHeaders)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "kwik.cx /e/ returned HTTP ${response.code}")
                    return emptyList()
                }
                response.body.string().takeIf(String::isNotBlank) ?: return emptyList()
            }

            val m3u8Url = parseM3u8FromKwikHtml(html, embedUrl)
                ?: return emptyList()

            val videoHeaders = Headers.Builder()
                .set("Referer", embedUrl)
                .set("Origin", "https://kwik.cx")
                .build()

            listOf(
                Video(m3u8Url, qualityLabel, m3u8Url, videoHeaders, subtitleTracks = subtitles),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from kwik embed $embedUrl: ${e.message}")
            emptyList()
        }
    }

    private fun parseM3u8FromKwikHtml(html: String, baseUrl: String): String? {
        val document = Jsoup.parse(html, baseUrl)
        val packedScripts = document.select("script:containsData(eval\\(function\\()")

        if (packedScripts.isEmpty()) {
            Log.w(TAG, "No packed scripts found in kwik page (${html.length} bytes)")
            return null
        }

        for (script in packedScripts) {
            val scriptData = script.data()
            val evalPositions = mutableListOf<Int>()
            var searchFrom = 0
            while (true) {
                val idx = scriptData.indexOf("eval(function(", searchFrom)
                if (idx == -1) break
                evalPositions.add(idx)
                searchFrom = idx + 1
            }

            for (pos in evalPositions.reversed()) {
                val packedContent = scriptData.substring(pos + "eval(".length)
                if (packedContent.isBlank()) continue
                try {
                    JsUnpacker.unpackAndCombine("eval($packedContent")?.let { unpacked ->
                        extractM3u8FromUnpacked(unpacked)?.let { return it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unpack failed at pos $pos: ${e.message}")
                }
            }
        }

        Log.w(TAG, "Could not extract m3u8 from kwik /e/ page")
        return null
    }

    private fun extractM3u8FromUnpacked(unpacked: String): String? {
        val patterns = listOf(
            "const source=\\'" to "\\';",
            "var source=\\'" to "\\';",
            "const source='" to "';",
            "var source='" to "';",
            "const source=\"" to "\";",
            "var source=\"" to "\";",
        )
        for ((prefix, suffix) in patterns) {
            unpacked.substringAfter(prefix).substringBefore(suffix).let { url ->
                if (url.startsWith("http") && ".m3u8" in url) return url
            }
        }
        return M3U8_REGEX.find(unpacked)?.groupValues?.get(1)
    }

    private fun buildKwikEPageHeaders(referer: String): Headers = Headers.Builder()
        .add("Referer", referer)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Fetch-Dest", "iframe")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "cross-site")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build()
}
