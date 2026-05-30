package eu.kanade.tachiyomi.animeextension.en.miruro

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.zip.GZIPInputStream

class MiruroExtractor(
    private val client: OkHttpClient,
    private val pipeKey: ByteArray,
    private val headers: Headers,
    private val resolveDisplayName: (String) -> String,
) {

    companion object {
        private const val TAG = "MiruroExtractor"
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

            val videoHeaders = if (stream.referer.isNotEmpty()) {
                headers.newBuilder().set("Referer", stream.referer).build()
            } else {
                headers
            }
            videos.add(
                Video(stream.url, qualityLabel, stream.url, videoHeaders, subtitleTracks = subtitles),
            )
        }

        return videos
    }
}
