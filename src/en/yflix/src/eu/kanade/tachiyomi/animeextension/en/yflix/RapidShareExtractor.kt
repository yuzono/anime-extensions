package eu.kanade.tachiyomi.animeextension.en.yflix

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class RapidShareExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val jsonMimeType = "application/json".toMediaType()

    suspend fun videosFromUrl(url: String, prefix: String, preferredLang: String): List<Video> {
        val rapidUrl = url.toHttpUrl()
        val token = rapidUrl.pathSegments.last()
        val subtitleUrl = rapidUrl.queryParameter("sub.list")
        // Dynamic base URL
        val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
        val mediaUrl = "$baseUrl/media/$token"

        val encryptedResult = try {
            client.newCall(GET(mediaUrl, headers))
                .awaitSuccess().use {
                    it.parseAs<EncryptedRapidResponse>().result
                }
        } catch (_: Exception) {
            return emptyList()
        }

        val decryptionBody = buildJsonObject {
            put("text", encryptedResult)
            put("agent", headers["User-Agent"] ?: "")
        }.toString().toRequestBody(jsonMimeType)

        val rapidResult = try {
            client.newCall(POST("https://enc-dec.app/api/dec-rapid", body = decryptionBody))
                .awaitSuccess().use {
                    it.parseAs<RapidDecryptResponse>().result
                }
        } catch (_: Exception) {
            return emptyList()
        }

        val subtitleList = try {
            if (subtitleUrl != null) {
                getSubtitles(subtitleUrl, baseUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { Track(it.file, it.label!!) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val videoSources = rapidResult.sources
        return videoSources.flatMap { source ->
            val videoUrl = source.file
            when {
                videoUrl.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "$baseUrl/",
                        videoNameGen = { quality -> "$prefix - $quality" },
                        subtitleList = subLangSelect(subtitleList, preferredLang),
                    )
                }

                else -> emptyList()
            }
        }
    }

    private suspend fun getSubtitles(url: String, baseUrl: String): List<Track> {
        val subHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        return try {
            client.newCall(GET(url, subHeaders))
                .awaitSuccess().use {
                    it.parseAs<List<RapidShareTrack>>()
                }
                .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                .map { Track(it.file, it.label!!) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Puts the preferred language subtitle first in the list.
     * The player will likely default to the first subtitle.
     */
    private fun subLangSelect(tracks: List<Track>, language: String): List<Track> = tracks.sortedByDescending { it.lang.contains(language, true) }
}
