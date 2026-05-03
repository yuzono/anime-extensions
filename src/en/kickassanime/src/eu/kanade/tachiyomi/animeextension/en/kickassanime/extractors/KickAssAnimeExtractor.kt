package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import aniyomi.lib.cryptoaes.CryptoAES
import aniyomi.lib.cryptoaes.CryptoAES.decodeHex
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest

class KickAssAnimeExtractor(
    private val client: OkHttpClient,
    private val json: Json,
    private val headers: Headers,
) {
    private fun getVideoHeaders(url: String): Headers {
        val host = url.toHttpUrl().host
        return headers.newBuilder()
            .removeAll("Referer")
            .set("Accept", "*/*")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Origin", "https://$host")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-site")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36") // May have been the fix
            .build()
    }

    private fun buildVideoClient(videoHeaders: Headers): OkHttpClient = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                videoHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }.build()
            chain.proceed(request)
        }
        .build()

    /**
     * Properly normalizes a URL. Handles:
     * - https:////host/path → https://host/path  (BirdStream)
     * - ////host/path       → https://host/path  (BirdStream)
     * - //host/path         → https://host/path  (protocol-relative)
     * - /path               → https://host/path  (root-relative)
     * - https://host/path   → as-is
     */
    private fun fixUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") ->
                trimmed.replace(Regex("^(https?:)//+"), "$1//")
            trimmed.startsWith("//") ->
                "https://${trimmed.substring(2)}"
            trimmed.startsWith("/") -> {
                val base = baseUrl.toHttpUrl()
                "${base.scheme}://${base.host}$trimmed"
            }
            else -> trimmed
        }
    }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val finalUrl = if (url.contains("/vast")) {
            url.toHttpUrl().newBuilder()
                .encodedPath("/cat-player/player")
                .build()
                .toString()
        } else {
            url
        }

        val html = try {
            client.newCall(GET(finalUrl, headers)).execute().body.string()
        } catch (_: Exception) {
            return emptyList()
        }

        val cleanHtml = html.replace("&quot;", "\"")

        if ("""manifest":\[0,"""".toRegex().containsMatchIn(cleanHtml)) {
            return parseNewPlayer(cleanHtml, finalUrl, name)
        }

        if (!html.contains("cid: '")) {
            return emptyList()
        }

        val host = finalUrl.toHttpUrl().host
        val mid = if (name == "DuckStream") "mid" else "id"
        val isBird = name == "BirdStream"

        val query = finalUrl.toHttpUrl().queryParameter(mid) ?: return emptyList()

        val key = when (name) {
            "VidStreaming" -> "e13d38099bf562e8b9851a652d2043d3"
            "DuckStream" -> "4504447b74641ad972980a6b8ffd7631"
            "BirdStream" -> "4b14d0ff625163e3c9c7a47926484bf2"
            else -> return emptyList()
        }.toByteArray()

        val (sig, timeStamp, route) = getSignature(html, name, query, key) ?: return emptyList()
        val sourceUrl = buildString {
            append("https://")
            append(host)
            append(route)
            append("?$mid=$query")
            if (!isBird) append("&e=$timeStamp")
            append("&s=$sig")
        }

        val request = GET(
            sourceUrl,
            headers.newBuilder()
                .set("Referer", finalUrl)
                .set("Origin", "https://$host")
                .build(),
        )
        val response = client.newCall(request).execute().body.string()

        val (encryptedData, ivhex) = response.substringAfter(":\"")
            .substringBefore('"')
            .replace("\\", "")
            .split(":")

        val iv = ivhex.decodeHex()

        val videoObject = try {
            val decrypted = CryptoAES.decrypt(encryptedData, key, iv)
            json.decodeFromString<VideoDto>(decrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val videoHeaders = getVideoHeaders(finalUrl)
        val vClient = buildVideoClient(videoHeaders)
        val subtitleUtils = PlaylistUtils(vClient, videoHeaders)

        val rawSubtitles = videoObject.subtitles.map {
            val subUrl = fixUrl(it.src, finalUrl)
            Track(subUrl, "${it.name} (${it.language})")
        }

        val subtitles = if (name == "CatStream") {
            subtitleUtils.fixSubtitles(rawSubtitles)
        } else {
            rawSubtitles
        }

        val localPlaylistUtils = PlaylistUtils(vClient, videoHeaders)

        val playlistUrl = fixUrl(videoObject.hls.ifEmpty { videoObject.dash }, finalUrl)

        val rawVideos = when {
            videoObject.hls.isBlank() ->
                localPlaylistUtils.extractFromDash(
                    playlistUrl,
                    videoNameGen = { res -> "$name - $res" },
                    subtitleList = subtitles,
                )

            else -> localPlaylistUtils.extractFromHls(
                playlistUrl,
                videoNameGen = { "$name - $it" },
                subtitleList = subtitles,
            )
        }

        return rawVideos.map {
            Video(it.url, it.quality, it.videoUrl, videoHeaders, it.subtitleTracks, it.audioTracks)
        }
    }

    private fun parseNewPlayer(cleanHtml: String, url: String, name: String): List<Video> {
        val videoHeaders = getVideoHeaders(url)
        val vClient = buildVideoClient(videoHeaders)
        val localPlaylistUtils = PlaylistUtils(vClient, videoHeaders)

        val rawManifestUrl = (
            """manifest":\[0,"(//[^"]+)"\]""".toRegex()
                .find(cleanHtml)?.groupValues?.get(1)
                ?: """manifest":\[0,"(https?://[^"]+)"\]""".toRegex()
                    .find(cleanHtml)?.groupValues?.get(1)
            ) ?: return emptyList()

        val manifestUrl = fixUrl(rawManifestUrl, url)

        val trackRegex = """"language":\[\d+,"([^"]+)"\][^}]+?"name":\[\d+,"([^"]+)"\][^}]+?"src":\[\d+,"([^"]+)"\]""".toRegex()

        val subtitles = trackRegex.findAll(cleanHtml).mapNotNull { match ->
            val lang = match.groupValues[1]
            val subName = match.groupValues[2]
            val rawSubUrl = match.groupValues[3].replace("\\/", "/")

            val subUrl = fixUrl(rawSubUrl, url)

            runCatching {
                subUrl.toHttpUrl()
                Track(subUrl, "$subName ($lang)")
            }.getOrNull()
        }.toList()

        val rawVideos = try {
            if (manifestUrl.contains(".m3u8")) {
                localPlaylistUtils.extractFromHls(
                    manifestUrl,
                    videoNameGen = { "$name - $it" },
                    subtitleList = subtitles,
                )
            } else {
                localPlaylistUtils.extractFromDash(
                    manifestUrl,
                    videoNameGen = { "$name - $it" },
                    subtitleList = subtitles,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

        return rawVideos.map {
            Video(it.url, it.quality, it.videoUrl, videoHeaders, it.subtitleTracks, it.audioTracks)
        }
    }

    private fun getSignature(html: String, server: String, query: String, key: ByteArray): Triple<String, String, String>? {
        val order = when (server) {
            "VidStreaming", "DuckStream" -> listOf("IP", "USERAGENT", "ROUTE", "MID", "TIMESTAMP", "KEY")
            "BirdStream" -> listOf("IP", "USERAGENT", "ROUTE", "MID", "KEY")
            else -> return null
        }

        val cid = String(html.substringAfter("cid: '").substringBefore("'").decodeHex()).split("|")
        val timeStamp = (System.currentTimeMillis() / 1000 + 60).toString()
        val route = cid[1].replace("player.php", "source.php")

        val signature = buildString {
            order.forEach {
                when (it) {
                    "IP" -> append(cid[0])
                    "USERAGENT" -> append(headers["User-Agent"] ?: "")
                    "ROUTE" -> append(route)
                    "MID" -> append(query)
                    "TIMESTAMP" -> append(timeStamp)
                    "KEY" -> append(String(key))
                    else -> {}
                }
            }
        }

        return Triple(sha1sum(signature), timeStamp, route)
    }

    private fun sha1sum(value: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(value.toByteArray())
        bytes.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        throw Exception("Attempt to create the signature failed.")
    }
}
