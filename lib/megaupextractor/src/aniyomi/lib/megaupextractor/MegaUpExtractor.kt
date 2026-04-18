package eu.kanade.aniyomi.lib.megaupextractor

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MegaUpExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    // Automatically sets the tag to "MegaUpExtractor" for Logcat filtering
    private val tag by lazy { javaClass.simpleName }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun encDecHeaders(url: String): Headers {
        val referer = headers["Referer"] ?: url
        val origin = runCatching { referer.toHttpUrl().let { "${it.scheme}://${it.host}" } }.getOrDefault("")

        return Headers.Builder()
            .set("User-Agent", headers["User-Agent"] ?: "")
            .set("Accept", "application/json, text/plain, */*")
            .set("Origin", origin)
            .set("Referer", referer)
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .build()
    }

    suspend fun videosFromUrl(
        url: String,
        serverName: String? = null,
    ): List<Video> {
        val parsedUrl = url.toHttpUrl()
        val megaHost = "${parsedUrl.scheme}://${parsedUrl.host}"
        val host = extractHoster(parsedUrl.host).proper()
        val prefix = serverName ?: host

        Log.d(tag, "Fetching videos for $prefix from: $url")

        val userAgent = headers["User-Agent"] ?: ""

        val token = parsedUrl.pathSegments.lastOrNull { it.isNotEmpty() }
            ?: throw IllegalArgumentException("No token found in URL: $url")

        val megaUrl = "$megaHost/media/$token"

        val mediaHeaders = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", url)
            .build()

        val megaToken = client.newCall(GET(megaUrl, mediaHeaders))
            .awaitSuccess().use { response ->
                response.parseAs<InternalEncryptedResponse>().result
            }

        val tokenBody = buildJsonObject {
            put("text", megaToken)
            put("agent", userAgent)
        }.toRequestBody()

        // LOG for API Call
        Log.d(tag, "Sending token to decryption API: https://enc-dec.app/api/dec-mega")

        val megaUpResult = client.newCall(
            POST("https://enc-dec.app/api/dec-mega", body = tokenBody, headers = encDecHeaders(url)),
        ).awaitSuccess().use { response ->
            response.parseAs<InternalTokenResponse>().result
        }

        val subtitleTracks = megaUpResult.subtitleTracks()

        val videoHeaders = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Origin", megaHost)
            .set("Referer", "$megaHost/")
            .build()

        return megaUpResult.sources.flatMap {
            val videoUrl = it.file
            when {
                m3u8Regex.containsMatchIn(videoUrl) -> {
                    // Log for HLS
                    Log.d(tag, "m3u8 URL found: $videoUrl")
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "$megaHost/",
                        subtitleList = subtitleTracks,
                        videoNameGen = { quality -> "$prefix: $quality" },
                    )
                }

                mpdRegex.containsMatchIn(videoUrl) -> {
                    // Log for Dash
                    Log.d(tag, "mpd URL found: $videoUrl")
                    playlistUtils.extractFromDash(
                        mpdUrl = videoUrl,
                        videoNameGen = { quality -> "$prefix: $quality" },
                        subtitleList = subtitleTracks,
                        referer = "$megaHost/",
                    )
                }

                mp4Regex.containsMatchIn(videoUrl) -> {
                    // Log for MP4
                    Log.d(tag, "mp4 URL found: $videoUrl")
                    Video(
                        url = videoUrl,
                        quality = "$prefix: MP4",
                        videoUrl = videoUrl,
                        headers = videoHeaders,
                        subtitleTracks = subtitleTracks,
                    ).let(::listOf)
                }

                else -> emptyList()
            }
        }
    }

    private val m3u8Regex by lazy { Regex(".*\\.m3u8(\\?.*)?$", RegexOption.IGNORE_CASE) }
    private val mpdRegex by lazy { Regex(".*\\.mpd(\\?.*)?$", RegexOption.IGNORE_CASE) }
    private val mp4Regex by lazy { Regex(".*\\.mp4(\\?.*)?$", RegexOption.IGNORE_CASE) }

    /**
     * Extracts the main domain segment from a host string.
     * For example, "www.megaup.live" -> "megaup"
     */

    private fun extractHoster(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) parts[parts.size - 2] else host
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }

    @Serializable
    private data class InternalEncryptedResponse(val result: String)

    @Serializable
    private data class InternalTokenResponse(val result: InternalMegaUpResult)

    @Serializable
    private data class InternalMegaUpResult(
        val sources: List<InternalMegaUpSource>,
        val tracks: List<InternalMegaUpTrack> = emptyList(),
    ) {
        fun subtitleTracks(): List<Track> = tracks
            .filter { it.kind == "captions" && it.file.endsWith(".vtt", ignoreCase = true) }
            .sortedByDescending { it.default }
            .map { Track(it.file, it.label ?: "Unknown") }
    }

    @Serializable
    private data class InternalMegaUpSource(val file: String)

    @Serializable
    private data class InternalMegaUpTrack(
        val file: String,
        val label: String? = null,
        val kind: String,
        val default: Boolean = false,
    )
}
