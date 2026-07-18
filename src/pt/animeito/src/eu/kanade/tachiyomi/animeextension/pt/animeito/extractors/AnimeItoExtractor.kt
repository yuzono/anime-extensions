package eu.kanade.tachiyomi.animeextension.pt.animeito.extractors

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeItoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag = javaClass.simpleName
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val arraySepRegex = Regex("""\],\s*\[""")
    private val keySepRegex = Regex("""\],\s*"""")
    private val stringsRegex = Regex("\"([^\"]*?)\"")

    suspend fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()

        val encodedScript = playerDoc.selectFirst("script:containsData(TextDecoder)")?.data()
        val script = if (encodedScript != null) {
            Log.d(tag, "TextDecoder script found, length=${encodedScript.length}")
            decodeTextDecoderScript(encodedScript).takeIf { it.isNotEmpty() }
        } else {
            null
        } ?: run {
            Log.w(tag, "TextDecoder script not found, falling back to const player")
            playerDoc.selectFirst("script:containsData(const player)")?.data()
                ?: return emptyList<Video>().also { Log.e(tag, "No const player script found") }
        }

        Log.d(tag, "Script type: ${if ("googlevideo" in script) "direct" else "hls"}")

        return if ("googlevideo" in script) {
            script.substringAfter("sources:").substringBefore("]")
                .split("{")
                .drop(1)
                .map {
                    val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                    val quality = it.substringAfter("label\":\"").substringBefore('"')
                    Log.d(tag, "Found video: $quality - ${videoUrl.take(80)}...")
                    Video(videoUrl, "Animei.to - $quality", videoUrl, headers)
                }
        } else {
            val masterPlaylistUrl = script.substringAfter("sources:")
                .substringAfter("file\":\"")
                .substringBefore('"')
            Log.d(tag, "HLS master URL: $masterPlaylistUrl")

            playlistUtils.extractFromHls(masterPlaylistUrl, videoNameGen = { "Animei.to - $it" })
        }
    }

    private fun decodeTextDecoderScript(script: String): String {
        val mainContent = script.substringBefore("})();")

        val funcCallStart = mainContent.indexOf("([\"")
        val funcCallEnd = mainContent.lastIndexOf("\");")
        if (funcCallStart < 0 || funcCallEnd < 0 || funcCallEnd <= funcCallStart + 1) {
            Log.w(tag, "Could not find function call boundaries: start=$funcCallStart end=$funcCallEnd")
            return ""
        }

        val params = mainContent.substring(funcCallStart + 2, funcCallEnd + 1)

        val array1End = arraySepRegex.find(params)?.range?.first ?: -1
        if (array1End < 0) {
            Log.w(tag, "Could not find first array boundary")
            return ""
        }

        val array2End = keySepRegex.find(params)?.range?.first ?: -1
        if (array2End < 0) {
            Log.w(tag, "Could not find second array boundary")
            return ""
        }

        val firstArrayStr = params.substring(0, array1End + 1)
        val secondArrayOpen = params.indexOf('[', array1End + 1)
        if (secondArrayOpen < 0 || secondArrayOpen >= array2End) {
            Log.w(tag, "Could not find second array brackets")
            return ""
        }
        val secondArrayStr = params.substring(secondArrayOpen, array2End + 1)

        val keyQuoteStart = params.indexOf('"', array2End + 1)
        if (keyQuoteStart < 0) {
            Log.w(tag, "Could not find key string start")
            return ""
        }
        val keyQuoteEnd = params.indexOf('"', keyQuoteStart + 1)
        if (keyQuoteEnd < 0) {
            Log.w(tag, "Could not find key string end")
            return ""
        }
        val keyStr = params.substring(keyQuoteStart + 1, keyQuoteEnd)

        val strings = stringsRegex
            .findAll(firstArrayStr)
            .map { it.groupValues[1] }
            .toList()

        val rawIndexTokens = secondArrayStr
            .removeSurrounding("[", "]")
            .split(",")
        val indices = rawIndexTokens.mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                Log.w(tag, "Skipping empty index token while decoding")
                return@mapNotNull null
            }
            val value = trimmed.toIntOrNull()
            if (value == null) {
                Log.w(tag, "Skipping non-numeric index token while decoding: '$trimmed'")
            }
            value
        }

        if (indices.isEmpty()) {
            Log.w(tag, "No valid indices found")
            return ""
        }

        val joined = indices.joinToString("") { strings.getOrNull(it) ?: "" }
        if (joined.isEmpty()) {
            Log.w(tag, "Joined base64 is empty")
            return ""
        }

        val decodedData = runCatching { Base64.decode(joined, Base64.DEFAULT) }.getOrNull() ?: return ""
        val key = runCatching { Base64.decode(keyStr, Base64.DEFAULT) }.getOrNull() ?: return ""

        if (decodedData.isEmpty() || key.isEmpty()) {
            Log.w(tag, "Decoded data or key is empty")
            return ""
        }

        val result = ByteArray(decodedData.size) { i ->
            (decodedData[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return String(result, Charsets.UTF_8)
    }
}
