package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.MapperServerDto
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.MegaPlaySourcesDto
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ServerResponseDto
import eu.kanade.tachiyomi.network.get
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnikotoExtractor(private val theme: AnikotoTheme) {

    suspend fun getServerData(document: Document, episode: SEpisode): List<AnikotoTheme.VideoData> {
        val serverData = theme.parseServerListData(document).toMutableList()
        serverData.addAll(fetchMapperServers(episode))
        return serverData
    }

    private suspend fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = theme.headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", theme.baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return theme.client.get("${theme.baseUrl}/ajax/server?get=$serverId", listHeaders)
            .use { response ->
                if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
                response.parseAs<ServerResponseDto>().result.url
            }
    }

    private suspend fun fetchMapperServers(episode: SEpisode): List<AnikotoTheme.VideoData> {
        val epUrlStr = episode.url
        val malId = epUrlStr.substringAfter("&mal=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()
        val slug = epUrlStr.substringAfter("&slug=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()
        val ts = epUrlStr.substringAfter("&ts=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()

        val apiUrl = "${theme.mapperUrl}/mal/$malId/$slug/$ts"

        return try {
            val mapperHeaders = theme.headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Referer", "${theme.baseUrl}/")
                add("Origin", theme.baseUrl)
            }.build()

            theme.client.get(apiUrl, mapperHeaders).use { apiResponse ->
                val mapperJson = apiResponse.parseAs<Map<String, MapperServerDto?>>()

                mapperJson.keys
                    .filter { !it.equals("status", true) }
                    .map { theme.mapMapperServerName(it) }
                    .also { theme.updateDiscoveredServers(it, isMapper = true) }

                theme.updateDiscoveredTypes(listOf("H-Sub", "A-Dub"))

                val servers = mutableListOf<AnikotoTheme.VideoData>()

                for ((key, serverDto) in mapperJson) {
                    if (key.equals("status", true)) continue
                    val serverName = theme.mapMapperServerName(key)

                    listOf("sub" to "H-Sub", "dub" to "A-Dub").forEach { (typeKey, typeLabel) ->
                        val linkDto = when (typeKey) {
                            "sub" -> serverDto?.sub
                            "dub" -> serverDto?.dub
                            else -> null
                        } ?: return@forEach

                        val linkId = linkDto.url?.takeIf { it.isNotBlank() } ?: return@forEach

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

    suspend fun extractVideo(server: AnikotoTheme.VideoData, epUrl: String): List<Video> = try {
        val embedLink = if (server.serverId.startsWith("http")) {
            server.serverId
        } else {
            getEmbedLink(server.serverId, epUrl)
        }

        val videos = when {
            isMegaPlayServer(server.serverName) || isMegaPlayUrl(embedLink) ->
                extractFromMegaPlay(embedLink, server)
            embedLink.contains("mewcdn.online/player/plyr.php") ->
                extractFromMewcdnPlayer(embedLink, server)
            embedLink.endsWith(".m3u8") || (embedLink.contains(".m3u8") && !embedLink.contains("/stream/")) ->
                extractDirectM3u8(embedLink, server)
            else -> {
                Log.w("AnikotoExtractor", "No extractor for ${server.serverName}: $embedLink")
                emptyList()
            }
        }

        videos.map { video ->
            theme.run {
                video.copy(
                    mpvArgs = video.mpvArgs.filterNot { it.first == "demuxer-lavf-o" } +
                        ("demuxer-lavf-o" to "force_mpegts=1"),
                    ffmpegStreamArgs = video.ffmpegStreamArgs.filterNot { it.first == "force_mpegts" } +
                        ("force_mpegts" to "1"),
                )
            }
        }
    } catch (e: Exception) {
        Log.e("AnikotoExtractor", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    // ======================== MegaPlay ========================

    private fun isMegaPlayServer(serverName: String): Boolean {
        val name = serverName.lowercase().replace(" ", "").replace("-", "")
        return name in setOf(
            "vidstream2",
            "hd1",
            "hd2",
        ) || name.contains("vidstream") || name.contains("hd1") || name.contains("hd2")
    }

    private fun isMegaPlayUrl(url: String): Boolean = MEGAPLAY_HOST_REGEX.containsMatchIn(url)

    private suspend fun extractFromMegaPlay(
        embedUrl: String,
        server: AnikotoTheme.VideoData,
    ): List<Video> {
        val pageHeaders = theme.headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "${theme.baseUrl}/")
            .build()

        val pageBody = theme.client.get(embedUrl, pageHeaders).use {
            if (!it.isSuccessful) throw Exception("MegaPlay page failed: HTTP ${it.code}")
            it.body.string()
        }

        val mediaId = parseMegaPlayMediaId(pageBody)
            ?: throw Exception("Failed to find MegaPlay media ID")

        val getSourcesUrl = buildMegaPlayGetSourcesUrl(embedUrl, mediaId)

        val apiHeaders = theme.headers.newBuilder().apply {
            add("Accept", "application/json,*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
        }.build()

        val sourcesDto = theme.client.get(getSourcesUrl, apiHeaders).use { response ->
            if (!response.isSuccessful) throw Exception("MegaPlay getSources failed: HTTP ${response.code}")
            response.parseAs<MegaPlaySourcesDto>()
        }

        val m3u8 = processMegaPlaySource(sourcesDto.enc, sourcesDto.sources)
            ?: throw Exception("Failed to decrypt/find MegaPlay source")

        val tracks = sourcesDto.tracks
            ?.filter { it.label.isNotBlank() }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val skipTimeStamps = buildList {
            sourcesDto.intro?.takeIf { it.start != 0 || it.end != 0 }?.let {
                add(TimeStamp(it.start.toDouble(), it.end.toDouble(), name = "Intro", type = ChapterType.Opening))
            }
            sourcesDto.outro?.takeIf { it.start != 0 || it.end != 0 }?.let {
                add(TimeStamp(it.start.toDouble(), it.end.toDouble(), name = "Outro", type = ChapterType.Ending))
            }
        }

        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            "megaplay.buzz"
        }

        val vidHeaders = theme.headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        val videos = theme.playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "$displayName$typeSuffix - ${theme.cleanHlsQuality(quality)}"
            },
            subtitleList = tracks,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return if (skipTimeStamps.isNotEmpty()) {
            videos.map { it.copy(timestamps = skipTimeStamps) }
        } else {
            videos
        }
    }

    private fun parseMegaPlayMediaId(html: String): String? {
        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1)?.trim()

        if (!dataId.isNullOrBlank()) return dataId

        return FILE_ID_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun buildMegaPlayGetSourcesUrl(embedUrl: String, id: String): String {
        val base = try {
            val u = embedUrl.toHttpUrl()
            "${u.scheme}://${u.host}/stream/getSources"
        } catch (_: Exception) {
            "https://megaplay.buzz/stream/getSources"
        }

        val urlBuilder = base.toHttpUrl().newBuilder()
            .addQueryParameter("id", id)

        try {
            val original = embedUrl.toHttpUrl()
            original.queryParameter("s")?.let { urlBuilder.addQueryParameter("s", it) }
        } catch (_: Exception) { }

        return urlBuilder.build().toString()
    }

    private fun processMegaPlaySource(enc: String?, source: String?): String? {
        var m3u8: String? = null
        var wasDecrypted = false

        if (!enc.isNullOrBlank()) {
            try {
                val keyBytes = ByteArray(32)
                val keySrc = MEGAPLAY_AES_KEY.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(keySrc, 0, keyBytes, 0, keySrc.size.coerceAtMost(32))

                val iv = MEGAPLAY_AES_IV.toByteArray(StandardCharsets.UTF_8)

                val encrypted = Base64.decode(
                    enc.replace('-', '+').replace('_', '/'),
                    Base64.DEFAULT,
                )

                if (encrypted.isNotEmpty() && encrypted.size % 16 == 0) {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        IvParameterSpec(iv),
                    )
                    val decrypted = cipher.doFinal(encrypted)
                    val json = String(decrypted, StandardCharsets.UTF_8)

                    val fileMatch = FILE_JSON_REGEX.find(json)
                    if (fileMatch != null) {
                        m3u8 = fileMatch.groupValues[1]
                        wasDecrypted = true
                    }
                }
            } catch (e: Exception) {
                Log.e("AnikotoExtractor", "MegaPlay AES decrypt failed: ${e.message}")
            }
        }

        if (m3u8.isNullOrBlank()) {
            m3u8 = source
        }

        if (m3u8.isNullOrBlank()) return null

        if (!wasDecrypted || TOKEN_PARAM_REGEX.containsMatchIn(m3u8)) {
            return m3u8
        }

        val match = PATH_KEY_REGEX.find(m3u8) ?: return m3u8

        val pathKey = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val expiry = (System.currentTimeMillis() / 1000) + 90
        val payload = "$expiry|$pathKey"

        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val signatureBytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            val signature = Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_WRAP)
                .trimEnd('=')

            val payloadB64 = Base64.encodeToString(
                payload.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            ).trimEnd('=')

            val token = "$payloadB64.$signature"

            m3u8.toHttpUrl().newBuilder()
                .setQueryParameter("token", token)
                .build()
                .toString()
        } catch (e: Exception) {
            Log.e("AnikotoExtractor", "MegaPlay token generation failed: ${e.message}")
            m3u8
        }
    }
    // ======================== Other paths ========================

    private fun extractDirectM3u8(
        m3u8Url: String,
        server: AnikotoTheme.VideoData,
        referer: String = "${theme.baseUrl}/",
    ): List<Video> {
        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

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

        return videos
    }

    private suspend fun extractFromMewcdnPlayer(serverUrl: String, server: AnikotoTheme.VideoData): List<Video> {
        val fragment = serverUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotEmpty() }
            ?: throw Exception("No fragment found in mewcdn player URL")

        val rawM3u8 = String(Base64.decode(fragment, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!rawM3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL decoded from mewcdn fragment")
        }

        val pageHeaders = theme.headers.newBuilder()
            .add("Referer", "${theme.baseUrl}/")
            .build()

        val hostMap = theme.client.get(serverUrl, pageHeaders).use { response ->
            parseHostMap(response.body.string())
        }

        val m3u8 = applyHostMap(rawM3u8, hostMap)

        val displayName = theme.getServerDisplayName(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

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

        return videos
    }

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

    companion object {
        private const val MEGAPLAY_AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val MEGAPLAY_AES_IV = "W0;27ToaUpl_P%'c"
        private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

        private val MEGAPLAY_HOST_REGEX = Regex("""megaplay\.[^/]+/stream/""", RegexOption.IGNORE_CASE)

        private val DATA_ID_REGEX = Regex("""data-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_ID_REGEX = Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val FILE_JSON_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
        private val TOKEN_PARAM_REGEX = Regex("""[?&]token=""", RegexOption.IGNORE_CASE)
        private val PATH_KEY_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}
