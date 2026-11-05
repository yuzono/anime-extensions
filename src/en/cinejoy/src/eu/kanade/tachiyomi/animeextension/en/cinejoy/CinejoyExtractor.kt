package eu.kanade.tachiyomi.animeextension.en.cinejoy

import android.util.Base64
import android.util.Log
import aniyomi.lib.hlsdash.HlsDashServer
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Resolves wing.st streams for a Cinejoy episode/movie:
 * wing.st url → enc-dec.app enc-cinejoy → POST api.wing.st/g →
 * enc-dec.app dec-cinejoy → HLS expansion + captions.
 */
class CinejoyExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosForServer(
        server: WingServerInfo,
        path: String,
        title: String,
        year: String,
        imdbId: String,
        subLimit: Int,
    ): List<Video> {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"
        val tmdbId = pathParts[1]

        return try {
            // 1. Build the wing.st request URL.
            val wingUrl = WING_API_BASE.toHttpUrl().newBuilder().apply {
                addEncodedQueryParameter("title", pctEncode(title))
                addQueryParameter("type", if (isMovie) "movie" else "series")
                addQueryParameter("year", year)
                if (imdbId.isNotBlank()) addQueryParameter("imdb", imdbId)
                addQueryParameter("tmdb", tmdbId)
                addQueryParameter("server", server.name)
                if (!isMovie) {
                    addQueryParameter("season", pathParts[2])
                    addQueryParameter("episode", pathParts[3])
                }
            }.build().toString()

            // 2. Encrypt the request through enc-dec.app.
            val enc = client.get("$ENC_DEC_API/enc-cinejoy?url=${pctEncode(wingUrl)}", headers)
                .parseAs<EncCinejoyDto>()
                .also { if (it.status != 200) throw IllegalStateException("enc-cinejoy status ${it.status}") }
                .result

            // 3. POST the encrypted request body to wing.st.
            val payload = Base64.decode(
                enc.data.let { data ->
                    val padding = when (data.length % 4) {
                        2 -> "=="
                        3 -> "="
                        else -> ""
                    }
                    data.replace('-', '+').replace('_', '/') + padding
                },
                Base64.DEFAULT,
            )
            val encrypted = client.post(
                "$WING_API_BASE/g",
                headers = wingHeaders,
                body = payload.toRequestBody(null),
            ).use { it.body.bytes() }

            // 4. Decrypt the response through enc-dec.app.
            val decBody = DecCinejoyRequestDto(
                Base64.encodeToString(encrypted, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                enc.state,
            ).toJsonRequestBody()
            val dec = client.post(DEC_CINEJOY_URL, headers = headers, body = decBody).parseAs<DecCinejoyDto>()
            if (dec.status != 200) throw IllegalStateException("dec-cinejoy status ${dec.status}")
            val streams = dec.result.data?.stream.orEmpty()
            if (streams.isEmpty()) {
                Log.i("CinejoyExtractor", "No streams from ${server.displayName}: ${dec.result.error}")
                return emptyList()
            }

            buildVideos(server, streams, subLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("CinejoyExtractor", "Error resolving video for server ${server.displayName}", e)
            emptyList()
        }
    }

    private val wingHeaders: Headers = headers.newBuilder()
        .set("Referer", "$SITE_ORIGIN/")
        .set("Origin", SITE_ORIGIN)
        .build()

    /**
     * wing.st servers mix MPEG-TS and fMP4 (EXT-X-MAP) HLS. Every playlist is
     * expanded by [PlaylistUtils] into variants; fMP4 variants (whose FFmpeg
     * HLS demuxer stalls after ~1s) are served as a loopback DASH manifest,
     * with the same muxed playlist passed as the audio rendition so audio is
     * carried inside the manifest as its own AdaptationSet.
     */
    private suspend fun buildVideos(
        server: WingServerInfo,
        streams: List<StreamDto>,
        subLimit: Int,
    ): List<Video> = streams.flatMap { stream ->
        val url = stream.playlist
            // Only fetch plain http(s) playlists; anything else in the
            // payload is a malformed or hostile entry.
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@flatMap emptyList()
        val subtitles = stream.captions.mapNotNull { caption ->
            val u = caption.url ?: return@mapNotNull null
            Track(u, caption.language ?: "unknown")
        }.take(subLimit.coerceAtLeast(0))

        val lower = url.lowercase()
        val isHls = stream.type.equals("hls", ignoreCase = true) || ".m3u8" in lower
        val isDash = stream.type.equals("dash", ignoreCase = true) || ".mpd" in lower

        if (isHls || isDash) {
            val expanded = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    videoNameGen = { quality ->
                        buildVideoLabel(server, quality, url, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = wingHeaders,
                    videoHeaders = wingHeaders,
                )
            }.getOrDefault(emptyList()).ifEmpty {
                listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = buildVideoLabel(server, "Auto", url, subtitles.size),
                        headers = wingHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
            if (isHls) {
                val renditions = runCatching {
                    client.get(url, wingHeaders).bodyString()
                }.getOrNull()?.let { parseAudioRenditions(url, it) }.orEmpty()
                expanded.map { maybeConvertFmp4Hls(it, renditions) }
            } else {
                expanded
            }
        } else {
            listOf(
                Video(
                    videoUrl = url,
                    videoTitle = buildVideoLabel(server, "Auto", url, subtitles.size),
                    headers = wingHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }.distinctBy { it.videoUrl }
        .map { video -> video.copy(subtitleTracks = video.subtitleTracks.take(subLimit.coerceAtLeast(0))) }

    /**
     * fMP4 HLS (EXT-X-MAP) can't be demuxed by the app's FFmpeg directly, so
     * the variant is registered as the video of a loopback DASH manifest.
     * Audio renditions all behave as external audio tracks (each registered
     * as its own loopback manifest, since the raw playlists are disguised
     * (.jpg, image/jpeg) and the player refuses to demux them); the video
     * manifest itself stays video-only so internal and external tracks don't
     * mix. When the master has no audio group, the muxed variant itself is
     * the only audio source and joins the manifest as its audio rendition.
     */
    private suspend fun maybeConvertFmp4Hls(
        video: Video,
        renditions: List<AudioRendition>,
    ): Video = try {
        val playlist = client.get(video.videoUrl, wingHeaders).bodyString()
        if (!playlist.contains("#EXT-X-MAP")) return video

        val audioTracks = renditions.mapNotNull { rendition ->
            runCatching {
                if (!client.get(rendition.url, wingHeaders).bodyString().contains("#EXT-X-MAP")) {
                    return@mapNotNull null
                }
                Track(HlsDashServer.register(client, wingHeaders, rendition.url, null), rendition.name)
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }

        video.copy(
            videoUrl = HlsDashServer.register(
                client,
                wingHeaders,
                video.videoUrl,
                if (audioTracks.isEmpty()) video.videoUrl else null,
            ),
            audioTracks = audioTracks,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        video
    }

    private fun parseAudioRenditions(masterUrl: String, masterText: String): List<AudioRendition> = EXT_X_MEDIA_REGEX.findAll(masterText)
        .map { line -> ATTRIBUTE_REGEX.findAll(line.value).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") } }
        .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }
        .map { attrs ->
            AudioRendition(
                url = masterUrl.toHttpUrl().resolve(attrs["URI"]!!).toString(),
                name = attrs["NAME"] ?: attrs["LANGUAGE"] ?: attrs["GROUP-ID"] ?: "audio",
                isDefault = attrs["DEFAULT"] == "YES",
            )
        }
        .toList()

    private data class AudioRendition(
        val url: String,
        val name: String,
        val isDefault: Boolean,
    )

    private fun buildVideoLabel(
        server: WingServerInfo,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf(server.displayName)

        if (quality.isNotBlank() && !quality.equals("Auto", ignoreCase = true)) {
            parts += quality
            if (quality.contains("2160") && !quality.contains("4k", ignoreCase = true)) {
                parts += "4K"
            }
        }

        val lower = url.lowercase()
        val container = when {
            ".m3u8" in lower -> "HLS"
            ".mpd" in lower -> "DASH"
            ".mkv" in lower -> "MKV"
            ".mp4" in lower -> "MP4"
            ".webm" in lower -> "WebM"
            else -> null
        }
        if (container != null) {
            parts += container
        }

        if (subCount > 0) {
            parts += "$subCount subs"
        }
        return parts.joinToString(" · ")
    }

    private fun pctEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (raw in bytes) {
            val c = raw.toInt() and 0xFF
            val unreserved =
                (c in 0x30..0x39) || // 0-9
                    (c in 0x41..0x5A) || // A-Z
                    (c in 0x61..0x7A) || // a-z
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E // - . _ ~
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[(c ushr 4) and 0x0F])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    companion object {
        private const val WING_API_BASE = "https://api.wing.st"
        private const val ENC_DEC_API = "https://enc-dec.app/api"
        private const val DEC_CINEJOY_URL = "$ENC_DEC_API/dec-cinejoy"
        private const val SITE_ORIGIN = "https://cinejoy.pk"
        private const val HEX = "0123456789ABCDEF"

        private val EXT_X_MEDIA_REGEX = Regex("""#EXT-X-MEDIA:.*""")
        private val ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }
}
