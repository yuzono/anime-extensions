package aniyomi.lib.embed4meextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Embed4MeExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = commonEmptyHeaders,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val id = extractId(url) ?: return emptyList()
        val embedOrigin = extractOrigin(url)

        val apiUrl = buildApiUrl(embedOrigin, id)
        val apiHeaders = headers.newBuilder()
            .set("Referer", "https://anime-sama.to/")
            .set("Origin", embedOrigin)
            .set("Accept", "*/*")
            .build()

        val raw = try {
            client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess().bodyString().trim()
        } catch (_: Exception) {
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()

        val jsonStr = decryptIfNeeded(raw) ?: return emptyList()
        if (jsonStr.isBlank()) return emptyList()

        val candidates = buildCandidates(jsonStr, embedOrigin) ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()

        val hlsHeaders = headers.newBuilder()
            .set("Referer", "https://anime-sama.to/")
            .set("Origin", embedOrigin)
            .build()

        return candidates.parallelCatchingFlatMap { candidateUrl ->
            try {
                playlistUtils.extractFromHls(
                    candidateUrl,
                    referer = embedOrigin,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { quality ->
                        val trimmed = prefix.trim()
                        if (trimmed.isNotEmpty()) "$trimmed Embed4Me - $quality" else "Embed4Me - $quality"
                    },
                )
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun extractId(url: String): String? {
        val fragment = url.substringAfter("#", "")
        if (fragment.isBlank()) return null
        return fragment.substringBefore("&")
            .substringBefore("?")
            .substringBefore("/")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun extractOrigin(url: String): String {
        val withoutFragment = url.substringBefore("#")
        return try {
            val httpUrl = withoutFragment.toHttpUrl()
            "${httpUrl.scheme}://${httpUrl.host}"
        } catch (_: Exception) {
            val base = withoutFragment.substringBefore("?").trimEnd('/')
            if (base.startsWith("http")) {
                val withoutPath = base.substringAfter("://").substringBefore("/")
                val scheme = if (base.startsWith("https")) "https" else "http"
                "$scheme://$withoutPath"
            } else {
                DEFAULT_ORIGIN
            }
        }.ifBlank { DEFAULT_ORIGIN }
    }

    private fun buildApiUrl(origin: String, id: String): String = try {
        origin.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/video")
            .addQueryParameter("id", id)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .addQueryParameter("r", "anime-sama.to")
            .build().toString()
    } catch (_: Exception) {
        "$origin/api/v1/video?id=$id&w=1920&h=1080&r=anime-sama.to"
    }

    private fun decryptIfNeeded(raw: String): String? {
        val trimmed = raw.trim().removeSurrounding("\"")
        // Already JSON
        if (trimmed.startsWith("{")) return trimmed
        // Hex-encoded AES
        return try {
            if (trimmed.matches(Regex("^[0-9a-fA-F]+$")) && trimmed.length % 2 == 0) {
                decryptHex(trimmed)
            } else {
                // Try to parse as JSON containing hex field
                val element = try {
                    json.parseToJsonElement(trimmed)
                } catch (_: Exception) {
                    null
                }
                val obj = element as? JsonObject
                val hexField = obj?.get("data")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("payload")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("result")?.jsonPrimitive?.contentOrNull
                if (!hexField.isNullOrBlank() && hexField.matches(Regex("^[0-9a-fA-F]+$"))) {
                    decryptHex(hexField)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptHex(hex: String): String {
        val encrypted = hex.decodeHex()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun buildCandidates(jsonStr: String, embedOrigin: String): List<String>? {
        val root = try {
            json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Exception) {
            return null
        }

        val streamingConfig = root["streamingConfig"]?.jsonObject
        val order = streamingConfig?.get("order")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        val adjustMap = streamingConfig?.get("adjust")?.jsonObject
            ?.mapValues { (_, v) ->
                val obj = v.jsonObject
                Adjust(
                    disabled = obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    domain = obj["domain"]?.jsonPrimitive?.contentOrNull,
                    params = obj["params"]?.jsonObject
                        ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
                        ?.filterValues { it.isNotEmpty() }
                        ?: emptyMap(),
                )
            } ?: emptyMap()

        val pkObj = root["pk"]?.jsonObject
            ?: root["PK"]?.jsonObject
        val pk = pkObj?.let {
            Pk(
                k = it["k"]?.jsonPrimitive?.contentOrNull,
                kx = it["kx"]?.jsonPrimitive?.contentOrNull,
            )
        }

        // Source fields
        val sourceKeys = listOf("cf", "cfNative", "hlsVideoTiktok", "hlsVideoGoogle", "source")
        val sourceMap = sourceKeys.mapNotNull { key ->
            val value = root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (value != null) key to value else null
        }.toMap()

        val namesInOrder = if (order.isNotEmpty()) order else sourceMap.keys.toList()
        if (namesInOrder.isEmpty() && sourceMap.isEmpty()) {
            // Fallback: any string value that looks like http
            val fallback = root.entries.mapNotNull { (k, v) ->
                val s = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                if (s.startsWith("http") && ("/hls/" in s || ".m3u8" in s || "/v4/" in s)) k to s else null
            }.toMap()
            if (fallback.isEmpty()) return emptyList()
            return buildUrlsFromMap(fallback, adjustMap, pk, embedOrigin, fallback.keys.toList())
        }

        return buildUrlsFromMap(sourceMap, adjustMap, pk, embedOrigin, namesInOrder)
    }

    private fun buildUrlsFromMap(
        sourceMap: Map<String, String>,
        adjustMap: Map<String, Adjust>,
        pk: Pk?,
        embedOrigin: String,
        order: List<String>,
    ): List<String> {
        val result = mutableListOf<String>()
        for (name in order) {
            val rawUrl = sourceMap[name] ?: continue
            if (rawUrl.isBlank()) continue
            val adj = adjustMap[name]
            if (adj?.disabled == true) continue

            var url = rawUrl

            // Apply params
            if (adj != null && adj.params.isNotEmpty()) {
                url = appendParams(url, adj.params)
            }

            // Rewrite /hls/ -> /hlsmod/<domain>/
            if (adj?.domain != null && "/hls/" in url) {
                url = url.replace("/hls/", "/hlsmod/${adj.domain}/")
            }

            // Resolve against embed origin if relative
            if (!url.startsWith("http")) {
                url = UrlUtils.fixUrl(url, embedOrigin) ?: "$embedOrigin/$url".replace("//", "/").let {
                    // ensure scheme preserved
                    if (embedOrigin.startsWith("https")) it.replace("http:/", "https://") else it
                }
            } else {
                // If URL is already absolute but contains /hls/ rewritten, ensure still resolved
                // No-op
            }

            // Append pk token for /v4/
            if ("/v4/" in url && pk != null && !pk.k.isNullOrBlank() && !pk.kx.isNullOrBlank()) {
                url = appendParams(url, mapOf("k" to pk.k!!, "kx" to pk.kx!!))
            }

            // Fix url via UrlUtils for consistency
            val fixed = UrlUtils.fixUrl(url, embedOrigin) ?: url
            result.add(fixed)
        }
        return result.distinct()
    }

    private fun appendParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        return try {
            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl != null) {
                val builder = httpUrl.newBuilder()
                params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
                builder.build().toString()
            } else {
                val sep = if ("?" in url) "&" else "?"
                url + sep + params.entries.joinToString("&") { "${it.key}=${it.value}" }
            }
        } catch (_: Exception) {
            val sep = if ("?" in url) "&" else "?"
            url + sep + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
    }

    private data class Adjust(
        val disabled: Boolean = false,
        val domain: String? = null,
        val params: Map<String, String> = emptyMap(),
    )

    private data class Pk(
        val k: String? = null,
        val kx: String? = null,
    )

    companion object {
        private const val DEFAULT_ORIGIN = "https://lpayer.embed4me.com"
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiuytr"
    }
}
