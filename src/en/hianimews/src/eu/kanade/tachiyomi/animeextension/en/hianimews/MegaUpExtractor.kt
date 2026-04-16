package eu.kanade.tachiyomi.animeextension.en.hianimews

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

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(
        url: String,
        serverName: String? = null,
    ): List<Video> {
        val parsedUrl = url.toHttpUrl()
        val host = extractHoster(parsedUrl.host).proper()
        val prefix = serverName ?: host

        val userAgent = headers["User-Agent"] ?: ""
        val iframeHost = "${parsedUrl.scheme}://${parsedUrl.host}/"

        val token = parsedUrl.pathSegments.lastOrNull()
            ?: throw IllegalArgumentException("No token found in URL: $url")

        val megaUrl = "${parsedUrl.scheme}://${parsedUrl.host}".toHttpUrl().newBuilder().apply {
            addPathSegment("media")
            addPathSegment(token)
        }.build().toString()

        val mediaHeaders = Headers.headersOf(
            "User-Agent",
            userAgent,
            "Referer",
            url,
            "X-Requested-With",
            "XMLHttpRequest",
        )

        val megaToken = client.newCall(GET(megaUrl, mediaHeaders))
            .awaitSuccess().use { response ->
                response.parseAs<ResultResponse>().result
            }

        val tokenBody = buildJsonObject {
            put("text", megaToken)
            put("agent", userAgent)
        }.toRequestBody()

        val megaUpResult = client.newCall(POST("https://enc-dec.app/api/dec-mega", body = tokenBody))
            .awaitSuccess().use { response ->
                response.parseAs<TokenResponse>().result
            }

        val subtitleTracks = megaUpResult.subtitleTracks()

        val videoHeaders = Headers.headersOf(
            "User-Agent",
            userAgent,
            "Referer",
            iframeHost,
        )

        return megaUpResult.sources.flatMap {
            val videoUrl = it.file
            when {
                m3u8Regex.containsMatchIn(videoUrl) -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = iframeHost,
                        subtitleList = subtitleTracks,
                        videoNameGen = { quality -> "$prefix: $quality" },
                    )
                }

                mpdRegex.containsMatchIn(videoUrl) -> {
                    playlistUtils.extractFromDash(
                        mpdUrl = videoUrl,
                        videoNameGen = { quality -> "$prefix: $quality" },
                        subtitleList = subtitleTracks,
                        referer = iframeHost,
                    )
                }

                mp4Regex.containsMatchIn(videoUrl) -> {
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

    private fun extractHoster(host: String): String {
        val parts = host.split(".")
        return when {
            parts.size >= 2 -> parts[parts.size - 2]
            else -> host
        }
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase()
        } else {
            it.toString()
        }
    }

    @Serializable
    data class TokenResponse(
        val result: MegaUpResult,
    )

    @Serializable
    data class MegaUpResult(
        val sources: List<MegaUpSource>,
        val tracks: List<MegaUpTrack>,
        val download: String? = null,
    ) {
        fun subtitleTracks(): List<Track> = tracks
            .filter { it.kind == "captions" && it.file.endsWith(".vtt") }
            .sortedByDescending { it.default }
            .map { Track(it.file, it.label ?: "Unknown") }
    }

    @Serializable
    data class MegaUpSource(
        val file: String,
    )

    @Serializable
    data class MegaUpTrack(
        val file: String,
        val label: String? = null,
        val kind: String,
        val default: Boolean = false,
    )
}
