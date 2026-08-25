package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * MasterExtractor — Legacy H.264 muxed-stream handler.
 *
 * Responsibility: given a fully-signed `master.m3u8` URL from the Master Player
 * CDN (`master-lengs.org`), resolve all variant streams via [PlaylistUtils.extractFromHls]
 * and return a [Video] list with per-resolution quality labels.
 *
 * This class MUST NOT be modified to handle VP9/CMAF split-stream content.
 * The Octopus path is exclusively owned by [OctopusExtractor].
 *
 * Architecture note: the master playlist served by the Legacy player is a standard
 * fMP4/HLS layout — muxed audio+video inside every .ts segment. No separate audio
 * rendition group exists, so PlaylistUtils processes it cleanly without audio-strip risk.
 */
class MasterExtractor(private val client: OkHttpClient) {

    /**
     * Extract quality-labelled [Video] objects from a Legacy Master HLS playlist.
     *
     * @param masterUrl   Fully-qualified, pre-signed master.m3u8 URL
     *                    e.g. `https://master-lengs.org/api/v3/hh/{id}/master.m3u8?hash=…`
     * @param refererUrl  The hentaihaven.xxx episode page URL — sent as both Referer
     *                    and used to derive the Origin for the CDN CORS check.
     */
    fun extractVideos(masterUrl: String, refererUrl: String): List<Video> {
        val masterHeaders = buildMasterHeaders(refererUrl)
        val playlistUtils = PlaylistUtils(client, masterHeaders)

        return try {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = refererUrl,
                masterHeaders = masterHeaders,
                videoHeaders = masterHeaders,
                // Legacy player is purely muxed — no separate subtitle or audio tracks.
            )
        } catch (e: Exception) {
            // Fallback: surface the raw master URL so the user is never left with nothing.
            // Quality label acknowledges the degraded state explicitly.
            listOf(
                Video(
                    url = masterUrl,
                    quality = "Master · Fallback",
                    videoUrl = masterUrl,
                    headers = masterHeaders,
                ),
            )
        }
    }

    /**
     * Headers for `master-lengs.org` CDN requests.
     *
     * The Legacy CDN requires only Origin and Referer for CORS.
     * Accept-Encoding is left at OkHttp default (gzip) here because PlaylistUtils
     * reads the playlist body itself via OkHttp — compression is fine for that path.
     */
    private fun buildMasterHeaders(refererUrl: String): Headers = Headers.Builder()
        .add("Referer", refererUrl)
        .add("Origin", SITE_ORIGIN)
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    companion object {
        private const val SITE_ORIGIN = "https://hentaihaven.xxx"
    }
}
