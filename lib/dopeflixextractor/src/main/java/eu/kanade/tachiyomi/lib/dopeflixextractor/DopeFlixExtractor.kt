package eu.kanade.tachiyomi.lib.dopeflixextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class DopeFlixExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val megaCloudAPI: String,
) {
    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private const val SOURCES_URL = "/embed-1/v3/e-1/getSources?id="
        private const val SOURCES_SPLITTER = "/e-1/"
    }

    fun getVideosFromUrl(url: String, name: String): List<Video> {
        val videos = getVideoDto(url)
        if (videos.isEmpty()) return emptyList()

        val subtitles = videos.first().tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()
            .let(playlistUtils::fixSubtitles)

        return videos.flatMap { video ->
            playlistUtils.extractFromHls(
                video.m3u8,
                videoNameGen = { "$name - $it" },
                subtitleList = subtitles,
                referer = "https://${url.toHttpUrl().host}/",
            )
        }
    }

    private fun getVideoDto(url: String): List<VideoDto> {
        val id = url.substringAfter(SOURCES_SPLITTER, "")
            .substringBefore("?", "")
            .ifEmpty { throw Exception("Failed to extract ID from URL") }

        val host = runCatching {
            url.toHttpUrl().host
        }.getOrNull() ?: throw IllegalStateException("MegaCloud host is invalid: $url")

        val megaCloudServerUrl = "https://$host"

        val megaCloudHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "${megaCloudServerUrl}/")
            .build()

        val responseNonce = client.newCall(GET(url, megaCloudHeaders))
            .execute().use { it.body.string() }
        val match1 = Regex("""\b[a-zA-Z0-9]{48}\b""").find(responseNonce)
        val match2 = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""").find(responseNonce)

        val nonce = match1?.value ?: match2?.let {
            it.groupValues[1] + it.groupValues[2] + it.groupValues[3]
        } ?: throw IllegalStateException("Failed to extract nonce from response")

        val srcRes = client.newCall(GET("${megaCloudServerUrl}${SOURCES_URL}${id}&_k=${nonce}", megaCloudHeaders))
            .execute().use { it.body.string() }
        val data = json.decodeFromString<SourceResponseDto>(srcRes)

        val key by lazy { requestNewKey() }

        return data.sources.map { source ->
            val encoded = source.file

            val m3u8: String = if (!data.encrypted || ".m3u8" in encoded) {
                encoded
            } else {
                val fullUrl = buildString {
                    append(megaCloudAPI)
                    append("?encrypted_data=").append(URLEncoder.encode(encoded, "UTF-8"))
                    append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
                    append("&secret=").append(URLEncoder.encode(key, "UTF-8"))
                }

                val decryptedResponse = client.newCall(GET(fullUrl))
                    .execute().use { it.body.string() }
                Regex("\"file\":\"(.*?)\"")
                    .find(decryptedResponse)
                    ?.groupValues?.getOrNull(1)
                    ?: throw Exception("Video URL not found in decrypted response")
            }

            VideoDto(m3u8, data.tracks)
        }
    }

    private fun requestNewKey(): String =
        client.newCall(GET("https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json"))
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Failed to fetch keys.json")
                val jsonStr = response.body.string()
                if (jsonStr.isEmpty()) throw IllegalStateException("keys.json is empty")
                val key = json.decodeFromString<Map<String, String>>(jsonStr)["mega"]
                    ?: throw IllegalStateException("Mega key not found in keys.json")
                Log.d("MegaCloudExtractor", "Using Mega Key: $key")
                key
            }

    @Serializable
    data class VideoDto(
        val m3u8: String = "",
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class SourceResponseDto(
        val sources: List<SourceDto>,
        val encrypted: Boolean = true,
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class SourceDto(
        val file: String,
        val type: String,   // 'hls'
    )

    @Serializable
    data class TrackDto(val file: String, val kind: String, val label: String = "")
}
