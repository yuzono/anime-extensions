package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.SourceResponseDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/**
 * Wraps extraction results with a flag indicating whether the extracted
 * stream uses fake bytes (junk prefix on segment responses) and therefore
 * needs to be proxied through the local M3U8 server.
 *
 * - getSources API  → requiresProxy = false (clean segments)
 * - getSourcesNew API → requiresProxy = true  (252 fake bytes per segment)
 * - mewcdn player (Kiwi) → requiresProxy = true (ibyteimg.com CDN)
 * - direct m3u8 / page scrape → requiresProxy = false
 */
private data class ExtractionResult(
    val videos: List<Video>,
    val requiresProxy: Boolean,
)

class AnikotoExtractor(private val theme: AnikotoTheme) {

    suspend fun extractVideos(document: Document, episode: SEpisode, epUrl: String): List<Video> {
        val serverData = theme.parseServerListData(document).toMutableList()
        val mapperServers = fetchMapperServers(episode)
        serverData.addAll(mapperServers)

        return serverData.parallelCatchingFlatMap { server ->
            extractVideo(server, epUrl)
        }
    }

    private suspend fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = theme.headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", theme.baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return theme.client.newCall(GET("${theme.baseUrl}/ajax/server?get=$serverId", listHeaders))
            .awaitSuccess().use { response ->
                if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
                response.parseAs<eu.kanade.tachiyomi.multisrc.anikototheme.dto.ServerResponseDto>().result.url
            }
    }

    private suspend fun fetchMapperServers(episode: SEpisode): List<AnikotoTheme.VideoData> {
        val epUrlStr = episode.url
        val malId = epUrlStr.substringAfter("&mal=", "").substringBefore("&")
            .takeIf { it.isNotBlank() } ?: return emptyList()
        val slug = epUrlStr.substringAfter("&slug=", "").substringBefore("&")
            .takeIf { it.isNotBlank() } ?: return emptyList()
        val ts = epUrlStr.substringAfter("&ts=", "").substringBefore("&")
            .takeIf { it.isNotBlank() } ?: return emptyList()

        val apiUrl = "${theme.mapperUrl}/mal/$malId/$slug/$ts"

        return try {
            val mapperHeaders = theme.headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Referer", "${theme.baseUrl}/")
                add("Origin", theme.baseUrl)
            }.build()

            theme.client.newCall(GET(apiUrl, mapperHeaders)).awaitSuccess().use { apiResponse ->
                val mapperJson = apiResponse.parseAs<JsonObject>()

                mapperJson.keys
                    .filter { !it.equals("status", true) }
                    .map { theme.mapMapperServerName(it) }
                    .also { theme.updateDiscoveredServers(it) }

                theme.updateDiscoveredTypes(listOf("H-Sub", "A-Dub"))

                val servers = mutableListOf<AnikotoTheme.VideoData>()

                for ((key, value) in mapperJson) {
                    if (key.equals("status", true)) continue
                    val obj = try {
                        value.jsonObject
                    } catch (_: Exception) {
                        continue
                    }

                    val serverName = theme.mapMapperServerName(key)

                    listOf("sub", "dub").forEach { typeKey ->
                        val typeObj = try {
                            obj[typeKey]?.jsonObject
                        } catch (_: Exception) {
                            null
                        } ?: return@forEach

                        val linkId = typeObj["url"]?.jsonPrimitive?.content ?: return@forEach

                        val typeLabel = when (typeKey) {
                            "sub" -> "H-Sub"
                            "dub" -> "A-Dub"
                            else -> typeKey.replaceFirstChar { it.uppercase() }
                        }

                        if (!theme.hostToggle.contains(serverName)) return@forEach
                        if (!theme.isTypeEnabled(typeLabel, theme.typeToggle)) return@forEach

                        servers.add(AnikotoTheme.VideoData(typeLabel, linkId, serverName))
                    }
                }

                servers
            }
        } catch (e: Exception) {
            Log.e("AnikotoExtractor", "Mapper API failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun extractVideo(server: AnikotoTheme.VideoData, epUrl: String): List<Video> = try {
        val embedLink = if (server.serverId.startsWith("http")) {
            server.serverId
        } else {
            getEmbedLink(server.serverId, epUrl)
        }

        val result = when {
            embedLink.contains("mewcdn.online/player/plyr.php") ->
                extractFromMewcdnPlayer(embedLink, server)
            embedLink.endsWith(".m3u8") || (embedLink.contains(".m3u8") && !embedLink.contains("/stream/")) ->
                extractDirectM3u8(embedLink, server)
            else ->
                extractFromPlayer(embedLink, server)
        }

        // Proxy is needed when:
        // - The extraction itself determined fake bytes are present (getSourcesNew / mewcdn)
        // - The server is known to always use fake bytes (Kiwi, VidPlay)
        val needsProxy = result.requiresProxy || theme.alwaysNeedsProxy(server.serverName)

        if (needsProxy) proxyVideoList(result.videos) else result.videos
    } catch (e: Exception) {
        Log.e("AnikotoExtractor", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    private suspend fun extractFromPlayer(
        embedUrl: String,
        server: AnikotoTheme.VideoData,
        pageReferer: String = "${theme.baseUrl}/",
    ): ExtractionResult {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            return ExtractionResult(emptyList(), false)
        }

        val pageHeaders = theme.headers.newBuilder()
            .add("Referer", pageReferer)
            .build()

        val pageBody = theme.client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Player page failed: HTTP ${it.code}")
            it.body.string()
        }

        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, server)
        }

        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = resolveUrl(iframeSrc, embedUrl)
            return extractFromPlayer(resolvedSrc, server, pageReferer = embedUrl)
        }

        val directM3u8 = M3U8_REGEX.find(pageBody)?.groupValues?.get(0)
        if (directM3u8 != null) {
            return extractDirectM3u8(directM3u8, server, "https://$host/")
        }

        val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            val resolvedSrc = resolveUrl(sourceSrc, embedUrl)
            return extractDirectM3u8(resolvedSrc, server, "https://$host/")
        }

        val jsVarUrl = JS_VAR_M3U8_REGEX.find(pageBody)?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)
                ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)
        }
        if (jsVarUrl != null) {
            val resolvedUrl = resolveUrl(jsVarUrl, embedUrl)
            if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains("/stream/")) {
                return try {
                    fetchSourcesFromPage(resolvedUrl, server, "https://$host/")
                } catch (_: Exception) {
                    extractDirectM3u8(resolvedUrl, server, "https://$host/")
                }
            }
        }

        Log.e("AnikotoExtractor", "No extraction strategy matched for ${server.serverName} at $embedUrl")
        return ExtractionResult(emptyList(), false)
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        server: AnikotoTheme.VideoData,
    ): ExtractionResult {
        val streamType = try {
            embedUrl.toHttpUrl().pathSegments.lastOrNull()
                ?.takeIf { it == "sub" || it == "dub" }
        } catch (_: Exception) {
            null
        } ?: ""

        val apiHeaders = theme.headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        // fetchSourceData returns (data, usedGetSourcesNew).
        // usedGetSourcesNew = true means the stream has fake bytes, needing proxy.
        val (data, usedGetSourcesNew) = fetchSourceData(dataId, host, apiHeaders, streamType)

        val m3u8 = theme.extractM3u8FromSources(data.sources)?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found")

        val subtitles = data.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        val vidHeaders = theme.headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        val videos = theme.playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "$displayName$typeSuffix - ${theme.cleanHlsQuality(quality)}"
            },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, usedGetSourcesNew)
    }

    /**
     * Fetches source data from the player API.
     *
     * Returns a [Pair] of (source data, usedGetSourcesNew):
     * - Tries `getSources` first → no fake bytes → requiresProxy = false
     * - Falls back to `getSourcesNew` → fake bytes → requiresProxy = true
     *
     * The fake-bytes distinction is critical: `getSources` returns clean CDN URLs
     * (e.g. streamzone1.site) that must NOT be proxied, while `getSourcesNew`
     * returns URLs whose segments have 252 junk bytes that the M3U8 server's
     * JunkBytesInterceptor must strip.
     */
    private suspend fun fetchSourceData(
        dataId: String,
        host: String,
        apiHeaders: Headers,
        streamType: String,
    ): Pair<SourceResponseDto, Boolean> {
        // Primary: getSources (clean, no fake bytes)
        val primaryResult = try {
            val body = theme.client.newCall(GET("https://$host/stream/getSources?id=$dataId", apiHeaders))
                .awaitSuccess().use {
                    if (!it.isSuccessful) throw Exception("getSources failed: HTTP ${it.code}")
                    it.body.string()
                }
            val data = body.parseAs<SourceResponseDto>()
            val m3u8 = theme.extractM3u8FromSources(data.sources)
            if (m3u8 != null && !isBrokenM3u8Host(m3u8)) {
                data to false
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        if (primaryResult != null) return primaryResult

        // Fallback: getSourcesNew (fake bytes, needs proxy)
        val newUrl = if (streamType.isNotBlank()) {
            "https://$host/stream/getSourcesNew?id=$dataId&type=$streamType"
        } else {
            "https://$host/stream/getSourcesNew?id=$dataId"
        }

        val data = theme.client.newCall(GET(newUrl, apiHeaders))
            .awaitSuccess().use {
                if (!it.isSuccessful) throw Exception("getSourcesNew failed: HTTP ${it.code}")
                it.body.string()
            }.parseAs<SourceResponseDto>()

        return data to true
    }

    private fun isBrokenM3u8Host(url: String): Boolean {
        val host = try {
            url.toHttpUrl().host
        } catch (_: Exception) {
            return false
        }
        return host.equals("cdn.mewstream.buzz", ignoreCase = true)
    }

    private suspend fun fetchSourcesFromPage(
        url: String,
        server: AnikotoTheme.VideoData,
        referer: String,
    ): ExtractionResult {
        val pageHeaders = theme.headers.newBuilder()
            .add("Referer", referer)
            .build()

        val body = theme.client.newCall(GET(url, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Page fetch failed: HTTP ${it.code}")
            it.body.string()
        }

        if (body.trimStart().startsWith("#EXTM3U")) {
            return extractDirectM3u8(url, server, referer)
        }

        val m3u8 = M3U8_REGEX.find(body)?.groupValues?.get(0)
            ?: throw Exception("No m3u8 found in page")

        return extractDirectM3u8(m3u8, server, referer)
    }

    private suspend fun extractDirectM3u8(
        m3u8Url: String,
        server: AnikotoTheme.VideoData,
        referer: String = "${theme.baseUrl}/",
    ): ExtractionResult {
        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        val vidHeaders = theme.headers.newBuilder()
            .set("Referer", referer)
            .build()

        val videos = theme.playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality ->
                "$displayName$typeSuffix - ${theme.cleanHlsQuality(quality)}"
            },
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, false)
    }

    private suspend fun extractFromMewcdnPlayer(embedUrl: String, server: AnikotoTheme.VideoData): ExtractionResult {
        val fragment = embedUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotBlank() }
            ?: throw Exception("No fragment found in mewcdn player URL")

        val rawM3u8 = String(Base64.decode(fragment, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!rawM3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL decoded from mewcdn fragment")
        }

        val pageHeaders = theme.headers.newBuilder()
            .add("Referer", "${theme.baseUrl}/")
            .build()

        val hostMap = theme.client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use { response ->
            parseHostMap(response.body.string())
        }

        val m3u8 = applyHostMap(rawM3u8, hostMap)

        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        val vidHeaders = theme.headers.newBuilder()
            .set("Referer", "https://mewcdn.online/")
            .set("Origin", "https://mewcdn.online")
            .build()

        val videos = theme.playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "$displayName$typeSuffix - ${theme.cleanHlsQuality(quality)}"
            },
            referer = "https://mewcdn.online/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        // Mewcdn player (Kiwi-Stream) serves segments from ibyteimg.com/tiktokcdn.com
        // which always have 252 fake bytes
        return ExtractionResult(videos, true)
    }

    // ========================== M3U8 Server Proxy ==========================

    private suspend fun proxyVideoList(videos: List<Video>): List<Video> {
        if (!theme.m3u8ServerManager.isRunning()) {
            Log.e("AnikotoExtractor", "M3U8 server not running, dropping ${videos.size} videos")
            return emptyList()
        }
        return videos.mapNotNull { video ->
            val processedUrl = proxyThroughM3u8Server(video.url)
            if (processedUrl == null) {
                Log.w("AnikotoExtractor", "Proxy failed for: ${video.quality}")
            }
            processedUrl?.let {
                Video(
                    url = it,
                    quality = video.quality,
                    videoUrl = it,
                    headers = video.headers,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            }
        }
    }

    private fun proxyThroughM3u8Server(originalUrl: String): String? = try {
        theme.m3u8ServerManager.processM3u8Url(originalUrl)
    } catch (e: Exception) {
        Log.e("AnikotoExtractor", "Proxy process failed: ${e.message}")
        null
    }

    // =========================== URL Helpers ============================

    private fun parseHostMap(html: String): Map<String, String> {
        val mapMatch = HOST_MAP_REGEX.find(html) ?: return emptyMap()
        return HOST_ENTRY_REGEX.findAll(mapMatch.groupValues[1]).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun applyHostMap(url: String, hostMap: Map<String, String>): String {
        var result = url
        for ((origin, proxy) in hostMap) {
            if (result.contains(origin)) {
                result = result.replace(origin, proxy)
                break
            }
        }
        return result
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        val baseUrl = try {
            base.toHttpUrl()
        } catch (_: Exception) {
            return url
        }
        return baseUrl.resolve(url)?.toString() ?: url
    }

    companion object {
        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
        private val JS_VAR_M3U8_REGEX = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""",
        )
        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}
