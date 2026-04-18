package eu.kanade.tachiyomi.animeextension.en.onetwothreecine

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
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

    suspend fun videosFromUrl(
        url: String,
        prefix: String,
        preferredLang: String = "English",
    ): List<Video> {
        val rapidUrl = url.toHttpUrl()
        val token = rapidUrl.pathSegments.last()
        val subtitleUrl = rapidUrl.queryParameter("sub.list")
        val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
        val mediaUrl = "$baseUrl/media/$token"

        val encryptedResult = try {
            client.newCall(GET(mediaUrl, headers))
                .awaitSuccess()
                .parseAs<EncryptedRapidResponse>().result
        } catch (_: Exception) {
            return emptyList()
        }

        if (encryptedResult.isBlank()) return emptyList()

        val decryptionBody = buildJsonObject {
            put("text", encryptedResult)
            put("agent", headers["User-Agent"] ?: "")
        }.toString().toRequestBody(jsonMimeType)

        val rapidResult = try {
            client.newCall(POST("https://enc-dec.app/api/dec-rapid", body = decryptionBody))
                .awaitSuccess()
                .parseAs<RapidDecryptResponse>().result
        } catch (_: Exception) {
            return emptyList()
        }

        val subtitleList = try {
            if (!subtitleUrl.isNullOrBlank()) {
                getSubtitles(subtitleUrl, baseUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { Track(it.file, it.label!!) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val sortedSubs = subtitleList
            .sortedByDescending { it.lang.contains(preferredLang, true) }

        return rapidResult.sources.flatMap { source ->
            if (source.file.contains(".m3u8")) {
                playlistUtils.extractFromHls(
                    playlistUrl = source.file,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$prefix - $quality" },
                    subtitleList = sortedSubs,
                )
            } else {
                emptyList()
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
                .awaitSuccess()
                .parseAs<List<RapidShareTrack>>()
                .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                .map { Track(it.file, it.label!!) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
