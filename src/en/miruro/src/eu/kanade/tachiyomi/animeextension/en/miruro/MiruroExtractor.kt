package eu.kanade.tachiyomi.animeextension.en.miruro

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class MiruroExtractor(
    private val client: OkHttpClient,
    private val pipeKey: ByteArray,
    private val headers: Headers,
) {

    private val providerFailureState = ConcurrentHashMap<String, FailureState>()

    data class FailureState(
        val failureCount: Int,
        val firstFailureTime: Long,
    )

    companion object {
        private const val TAG = "MiruroExtractor"
        private const val MAX_PROVIDER_FAILURES = 3
        private const val CIRCUIT_COOLDOWN_MS = 180_000L
        private val PERMANENT_FAILURE_CODES = setOf(444)
        private val TRANSIENT_RETRY_CODES = setOf(429, 502, 503, 504)
    }

    fun providerDisplayName(key: String): String = Miruro.providerDisplayName(key)

    fun isProviderCircuitOpen(provider: String): Boolean {
        val state = providerFailureState[provider] ?: return false
        if (System.currentTimeMillis() - state.firstFailureTime > CIRCUIT_COOLDOWN_MS) {
            providerFailureState.remove(provider)
            return false
        }
        return state.failureCount >= MAX_PROVIDER_FAILURES
    }

    fun recordProviderFailure(provider: String) {
        val current = providerFailureState[provider]
        val now = System.currentTimeMillis()
        if (current != null && now - current.firstFailureTime <= CIRCUIT_COOLDOWN_MS) {
            providerFailureState[provider] = current.copy(failureCount = current.failureCount + 1)
        } else {
            providerFailureState[provider] = FailureState(1, now)
        }
    }

    fun recordProviderSuccess(provider: String) {
        providerFailureState.remove(provider)
    }

    suspend fun safePipeApiCall(request: Request, maxRetries: Int = 3): Response {
        var lastResponse: Response? = null
        repeat(maxRetries) { attempt ->
            lastResponse?.close()
            val response = client.newCall(request).execute()
            val code = response.code
            if (code in PERMANENT_FAILURE_CODES || code !in TRANSIENT_RETRY_CODES) {
                lastResponse = null // prevent finally from closing the returned response
                return response
            }
            lastResponse = response
            if (attempt < maxRetries - 1) {
                val backoffMs = 1000L * (attempt + 1)
                Log.w(TAG, "Pipe API returned $code, retrying (${attempt + 1}/$maxRetries) in ${backoffMs}ms...")
                delay(backoffMs)
            }
        }
        return lastResponse!!
    }

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

    fun parseStreamsFromResponse(response: Response, subType: String?, providerKey: String = ""): List<Video> {
        val json = try {
            response.use(::decryptResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt stream response: ${e.message}")
            return emptyList()
        }

        val sourcesDto = try {
            jsonParser.decodeFromString<SourcesResponseDto>(json)
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
            "embed" -> "Embed"
            null -> null
            else -> subType.replaceFirstChar { it.uppercase() }
        }

        val subtitles = sourcesDto.subtitles
            .filter { it.url.isNotEmpty() }
            .map { sub -> Track(sub.url, sub.label.ifEmpty { sub.language }) }

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

            val videoHeaders = headers.newBuilder().set("Referer", stream.referer).build()
            videos.add(
                Video(stream.url, qualityLabel, stream.url, videoHeaders, subtitleTracks = subtitles),
            )
        }

        return videos
    }

    suspend fun fetchFallbackVideos(
        provider: String,
        fallbackProviders: JsonObject,
        fallbackProviderSubTypes: JsonObject?,
        preferredSubType: String,
        prefProviderValues: List<String>,
        buildPipeRequest: (path: String, method: String, query: JsonObject) -> Request,
    ): List<Video> {
        val videos = mutableListOf<Video>()

        val rankedFallbacks = prefProviderValues
            .filter { it != provider && !isProviderCircuitOpen(it) }
            .sortedByDescending { fallbackKey ->
                val subTypesArr = fallbackProviderSubTypes?.get(fallbackKey)
                    ?.jsonArray?.size ?: 0
                subTypesArr
            }

        for (fallbackKey in rankedFallbacks) {
            val fbSubTypesObj = fallbackProviders[fallbackKey]?.jsonObject ?: continue

            val fbSubTypeList = fallbackProviderSubTypes?.get(fallbackKey)?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: listOf(preferredSubType)

            val orderedSubTypes = fbSubTypeList.sortedBy { if (it == preferredSubType) 0 else 1 }
            var foundVideos = false

            for (fbSubType in orderedSubTypes) {
                val fbEpId = fbSubTypesObj[fbSubType]?.jsonPrimitive?.content ?: continue
                if (fbEpId.isEmpty()) continue

                try {
                    val query = buildPipeQueryMap(
                        "episodeId" to fbEpId,
                        "provider" to fallbackKey,
                        "category" to fbSubType,
                    )
                    val fbVideos = safePipeApiCall(
                        buildPipeRequest("sources", "GET", query),
                    ).use { parseStreamsFromResponse(it, fbSubType, fallbackKey) }
                    if (fbVideos.isNotEmpty()) {
                        videos.addAll(fbVideos)
                        recordProviderSuccess(fallbackKey)
                        Log.i(TAG, "Fallback to provider ${providerDisplayName(fallbackKey)} ($fallbackKey)/$fbSubType succeeded (${fbVideos.size} videos)")
                        foundVideos = true
                        break
                    }
                } catch (e: Exception) {
                    recordProviderFailure(fallbackKey)
                    Log.e(TAG, "Fallback provider ${providerDisplayName(fallbackKey)} ($fallbackKey)/$fbSubType failed: ${e.message}")
                }
            }
            if (foundVideos) break
        }

        return videos
    }

    private fun buildPipeQueryMap(vararg pairs: Pair<String, String>): JsonObject {
        val map = pairs.filter { it.second.isNotEmpty() }.associate { it.first to it.second }
        return JsonObject(map.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
    }
}
