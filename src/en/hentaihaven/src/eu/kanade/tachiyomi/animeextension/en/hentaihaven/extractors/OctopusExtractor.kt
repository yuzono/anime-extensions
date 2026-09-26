package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds [Video] entries for the Octopus VP9/CMAF stream. Every entry points at the
 * untouched master playlist so audio and adaptive switching stay native to the player.
 */
class OctopusExtractor(private val client: OkHttpClient) {

    suspend fun getVideosFromSourceUrl(
        sourceUrl: String,
        episodeUrl: String,
    ): List<Video> = withContext(Dispatchers.IO) {
        extractOctopusStream(sourceUrl, episodeUrl)
    }

    private suspend fun extractOctopusStream(sourceUrl: String, episodeUrl: String): List<Video> {
        val masterUrl = sourceUrl.toHttpUrl().let { url ->
            if (url.pathSegments.lastOrNull() == "playlist.m3u8") {
                url.newBuilder()
                    .setPathSegment(url.pathSize - 1, "playlist_vp9.m3u8")
                    .build()
            } else {
                url
            }
        }
        val masterUrlString = masterUrl.toString()
        val videoHeaders = buildCdnHeaders(episodeUrl)

        // Never fatal — playback works from the master URL alone.
        var subtitleTrack: Track? = masterUrl.resolve("s/en.vtt")?.let { Track(it.toString(), "English") }
        val declaredHeights = mutableListOf<Int>()
        try {
            val masterBody = client.get(masterUrl, videoHeaders, ensureSuccess = false)
                .use { response -> if (response.isSuccessful) response.bodyString() else "" }
            if (masterBody.isNotBlank()) {
                val declaredSubtitle = masterBody.lineSequence()
                    .firstOrNull { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=\"SUBTITLES\"") }
                    ?.let { line ->
                        line.substringAfter("URI=\"", "")
                            .substringBefore('"')
                            .takeIf { it.isNotBlank() }
                            ?.let { UrlUtils.fixUrl(it, masterUrlString) }
                    }
                if (declaredSubtitle != null) subtitleTrack = Track(declaredSubtitle, "English")

                masterBody.lineSequence()
                    .mapNotNull { line ->
                        RESOLUTION_REGEX.find(line)?.groupValues?.get(1)
                            ?.substringAfterLast('x')
                            ?.substringAfterLast('X')
                            ?.toIntOrNull()
                    }
                    .distinct()
                    .sortedDescending()
                    .take(MAX_QUALITIES)
                    .forEach { declaredHeights.add(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep the defaults: playback works from the master URL alone
        }

        val entries: List<Video> = if (declaredHeights.isEmpty()) {
            listOf(
                Video(
                    videoTitle = "Octopus · Auto",
                    videoUrl = masterUrlString,
                    headers = videoHeaders,
                    subtitleTracks = listOfNotNull(subtitleTrack),
                ),
            )
        } else {
            declaredHeights.map { height ->
                Video(
                    videoTitle = "Octopus · ${height}p",
                    videoUrl = masterUrlString,
                    headers = videoHeaders,
                    subtitleTracks = listOfNotNull(subtitleTrack),
                )
            }
        }

        return entries
    }

    private fun buildCdnHeaders(episodeUrl: String): Headers {
        val origin = episodeUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        return Headers.Builder()
            .add("Referer", episodeUrl)
            .add("Origin", origin)
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "identity")
            .add("Cache-Control", "no-transform")
            .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
            .add("Connection", "keep-alive")
            .build()
    }

    companion object {
        private const val MAX_QUALITIES = 6

        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=([xX\d]+)""") }
    }
}
