package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * OctopusExtractor — Orchestrator + VP9/CMAF split-stream handler.
 *
 * Responsibilities (and ONLY these):
 *  1. Decode the base64 player payload from the `player.php?data=` query parameter.
 *  2. Assemble and fire the multipart POST to `api.php` to obtain the signed JSON response.
 *  3. Parse the JSON to determine which player framework is active (`isOctopus` flag).
 *  4. Delegate Legacy H.264 streams to [MasterExtractor] (no logic duplication).
 *  5. For Octopus (VP9/CMAF) streams: construct the `playlist_vp9.m3u8` master URL,
 *     attach optimised CDN headers, and return a single [Video] whose URL is the
 *     top-level HLS master playlist — letting ExoPlayer's native HlsMediaSource
 *     handle the #EXT-X-MEDIA audio rendition demux without interference.
 *
 * ── Octopus Stream Architecture ──────────────────────────────────────────────
 *
 *  The Octopus CDN (octopusmanifest.org) serves a CMAF split-stream layout:
 *
 *    playlist_vp9.m3u8   ← top-level HLS Master Playlist  ← we pass this to Video.url
 *    │
 *    ├─ #EXT-X-STREAM-INF  →  v.m3u8           (video-only VP9 variants)
 *    ├─ #EXT-X-MEDIA TYPE=AUDIO  →  snd/a.m3u8  (standalone AAC audio rendition)
 *    └─ #EXT-X-MEDIA TYPE=SUBTITLES  →  s/en.vtt
 *
 *  ExoPlayer's HlsMediaSource correctly demuxes these when given the master URL
 *  directly. Any attempt to pass v.m3u8 or snd/a.m3u8 individually silences audio.
 *  PlaylistUtils must NOT be called for this path for the same reason.
 *
 * ── Latency Optimisation Strategy ────────────────────────────────────────────
 *
 *  ExoPlayer's HlsMediaSource issues its own HTTP requests for the master playlist
 *  and every sub-playlist/segment using the headers map on the [Video] object.
 *  Those headers must eliminate every source of startup overhead:
 *
 *  1. `Accept-Encoding: identity`
 *       The CDN may serve the manifest with `Content-Encoding: gzip`. OkHttp
 *       transparently decompresses it, but the response headers still carry the
 *       compressed Content-Length. ExoPlayer's DataSpec sees a length mismatch
 *       and re-fetches the manifest before it can parse #EXT-X-STREAM-INF entries,
 *       adding a full RTT before buffering begins. Forcing `identity` prevents any
 *       encoding negotiation and ensures the Content-Length the CDN sends matches
 *       the actual bytes ExoPlayer reads.
 *
 *  2. `Cache-Control: no-transform`
 *       Some CDN edge nodes rewrite manifests for adaptive-bitrate optimisation.
 *       This header signals the proxy to serve the canonical manifest untouched,
 *       which avoids a secondary validation round-trip.
 *
 *  3. `Accept: application/x-mpegURL, application/vnd.apple.mpegurl, *\/\*;q=0.8`
 *       Explicit MIME declaration skips the CDN's content-type negotiation step,
 *       shaving the decision overhead on the first manifest request.
 *
 *  4. `Connection: keep-alive`
 *       ExoPlayer issues at minimum 3 sequential HTTP requests before the first
 *       segment download (master → variant → audio rendition playlist). Without an
 *       explicit keep-alive hint the CDN may close the connection between requests,
 *       forcing a new TCP + TLS handshake per request (~150-300 ms each on mobile).
 *
 *  Hard constraints honoured:
 *  - No pre-flight requests to resolve redirects.
 *  - No manifest rewriting or slicing.
 *  - `identity` encoding is applied via the Video headers map only, not via OkHttp
 *    client configuration, so it does not interfere with OkHttp's own compressed
 *    responses for the API POST.
 */
class OctopusExtractor(private val client: OkHttpClient) {

    private val json: Json = Injekt.get()
    private val masterExtractor by lazy { MasterExtractor(client) }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Decode the player payload, fire the API, and route to the correct extractor.
     *
     * @param apiUrl        Resolved `api.php` URL (extracted from inline JS or fallback).
     * @param playerDataB64 Raw base64 string from `player.php?data=`.
     * @param episodeUrl    Full episode page URL (used for Referer/Origin headers).
     */
    fun getVideosFromPayload(
        apiUrl: String,
        playerDataB64: String,
        episodeUrl: String,
    ): List<Video> {
        // ── Step 1: decode base64 payload ─────────────────────────────────────
        val decoded = try {
            String(Base64.decode(playerDataB64, Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Base64 decode failed for payload: ${playerDataB64.take(40)}…", e)
            return emptyList()
        }

        // Payload format after decode: "<ciphertext>:|:<key_material>"
        val parts = decoded.split(":|:")
        val ciphertext = parts.getOrNull(0)?.trim() ?: return emptyList()
        if (ciphertext.isBlank()) {
            Log.e(TAG, "Empty ciphertext after splitting decoded payload")
            return emptyList()
        }

        // Part index 1 (or last part containing "=" / length > 20) is the key blob.
        // Re-encode to Base64 NO_WRAP because the server expects it URL-safe with no padding lines.
        val bRaw = parts.lastOrNull { part ->
            part.contains("=") || part.length > 20
        }?.trim() ?: ""
        val bEncoded = Base64.encodeToString(bRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // ── Step 2: POST to api.php ───────────────────────────────────────────
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "zarat_get_data_player_ajax")
            .addFormDataPart("a", ciphertext)
            .addFormDataPart("b", bEncoded)
            .build()

        val apiHeaders = buildApiHeaders(episodeUrl)

        val responseBody = try {
            client.newCall(
                Request.Builder()
                    .url(apiUrl)
                    .post(requestBody)
                    .headers(apiHeaders)
                    .build(),
            ).awaitSuccess().use { response ->
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "API POST to $apiUrl failed", e)
            return emptyList()
        } ?: return emptyList()

        // ── Step 3: parse JSON response ───────────────────────────────────────
        val payload = runCatching {
            json.decodeFromString<JsonObject>(responseBody)
        }.getOrElse {
            Log.e(TAG, "JSON parse failed. Body preview: ${responseBody.take(200)}")
            return emptyList()
        }

        val data = payload["data"]?.jsonObject ?: run {
            Log.e(TAG, "No 'data' key in API response")
            return emptyList()
        }

        val sourceUrl = data["sources"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("src")
            ?.jsonPrimitive
            ?.content
            ?: run {
                Log.e(TAG, "No 'sources[0].src' in API response data")
                return emptyList()
            }

        // `isOctopus` distinguishes the player type:
        //   true  → VP9/CMAF split-stream (octopusmanifest.org CDN)
        //   false / absent → Legacy H.264 muxed (master-lengs.org CDN)
        val isOctopus = data["isOctopus"]?.jsonPrimitive?.boolean ?: false

        Log.d(TAG, "Player type: ${if (isOctopus) "Octopus VP9/CMAF" else "Master H.264"}")
        Log.d(TAG, "Source URL: $sourceUrl")

        // ── Step 4: delegate to the correct extractor ─────────────────────────
        return if (isOctopus) {
            extractOctopusStream(sourceUrl, episodeUrl, payload)
        } else {
            masterExtractor.extractVideos(sourceUrl, episodeUrl)
        }
    }

    // ── Octopus VP9/CMAF path ─────────────────────────────────────────────────

    /**
     * Build a single [Video] whose URL is the Octopus top-level HLS master playlist.
     *
     * The playlist_vp9.m3u8 master contains both the video variant list and the
     * #EXT-X-MEDIA audio rendition group. We pass this URL directly to Video.url so
     * ExoPlayer's HlsMediaSource can read the full manifest and wire up audio natively.
     *
     * PlaylistUtils is intentionally NOT called here. Calling it would cause
     * HlsMediaSource to receive a variant-level playlist (v.m3u8) that has no
     * #EXT-X-MEDIA entry, stripping the audio track permanently.
     */
    private fun extractOctopusStream(
        sourceUrl: String,
        episodeUrl: String,
        payload: JsonObject,
    ): List<Video> {
        // The API returns "playlist.m3u8"; the VP9 CMAF master is "playlist_vp9.m3u8".
        // This single string replacement is the only manifest-path manipulation allowed.
        val masterPlaylistUrl = sourceUrl.replace("playlist.m3u8", "playlist_vp9.m3u8")

        // CDN base for relative sub-resource paths.
        // octopusBase = "https://octopusmanifest.org/{uuid}" (everything before final slash)
        val octopusBase = masterPlaylistUrl.substringBeforeLast("/")

        // External VTT subtitle track.
        // The CDN always places English captions at s/en.vtt relative to the playlist root.
        val subtitleTracks = listOf(Track("$octopusBase/s/en.vtt", "English"))

        // Extract signed auth tokens from the `authorization` object in the API response.
        // These are passed as custom headers on every CDN request ExoPlayer makes.
        val auth = payload["authorization"]?.jsonObject
        val videoHeaders = buildOctopusCdnHeaders(episodeUrl, auth)

        Log.d(TAG, "Octopus master URL: $masterPlaylistUrl")

        return listOf(
            Video(
                url = masterPlaylistUrl,
                quality = "Octopus · Auto",
                videoUrl = masterPlaylistUrl,
                headers = videoHeaders,
                subtitleTracks = subtitleTracks,
            ),
        )
    }

    // ── Header builders ───────────────────────────────────────────────────────

    /**
     * Headers for the `api.php` POST request.
     * Standard browser-mimicry headers + WordPress AJAX marker.
     */
    private fun buildApiHeaders(episodeUrl: String): Headers = Headers.Builder()
        .add("Referer", episodeUrl)
        .add("Origin", SITE_ORIGIN)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    /**
     * Optimised CDN headers placed on the [Video] object for the Octopus stream.
     *
     * These headers are used by ExoPlayer's DefaultHttpDataSource for every HTTP
     * request it makes while loading the master playlist, variant playlists, audio
     * rendition playlist, and media segments.
     *
     * Each header is required for the reason documented in the class-level KDoc.
     * Do not remove or reorder them without re-testing startup latency on device.
     *
     * @param episodeUrl  Episode page URL — used as Referer so the CDN CORS check passes.
     * @param auth        Optional `authorization` object from the API JSON response.
     *                    Contains `token`, `expiration`, and `ip` fields when present.
     */
    private fun buildOctopusCdnHeaders(episodeUrl: String, auth: JsonObject?): Headers {
        val builder = Headers.Builder()
            .add("Referer", episodeUrl)
            .add("Origin", SITE_ORIGIN)
            .add("Accept-Language", "en-US,en;q=0.9")
            // ── Latency optimisation headers ──────────────────────────────────
            // 1. Prevent gzip-encoding of the manifest.
            //    Without this, the CDN sends Content-Encoding: gzip but ExoPlayer
            //    reads the decompressed bytes against the original compressed Content-Length,
            //    causing a DataSpec length mismatch and a manifest re-fetch stall.
            .add("Accept-Encoding", "identity")
            // 2. Prevent CDN edge nodes from rewriting the manifest.
            //    Ensures ExoPlayer always receives the canonical #EXT-X-MEDIA entries.
            .add("Cache-Control", "no-transform")
            // 3. Explicit MIME type hint for the HLS playlist requests.
            //    Eliminates CDN-side content-type negotiation overhead.
            .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
            // 4. Persist the TCP+TLS connection across the master → variant → audio
            //    rendition chain of requests ExoPlayer issues at startup.
            .add("Connection", "keep-alive")

        // Auth tokens — required when the CDN enforces token-gated access.
        // All three must be present together; partial sets are rejected by the CDN.
        if (auth != null) {
            val token = auth["token"]?.jsonPrimitive?.content.orEmpty()
            val expiration = auth["expiration"]?.jsonPrimitive?.content.orEmpty()
            val ip = auth["ip"]?.jsonPrimitive?.content.orEmpty()

            if (token.isNotBlank()) builder.add("X-Video-Token", token)
            if (expiration.isNotBlank()) builder.add("X-Video-Expiration", expiration)
            if (ip.isNotBlank()) builder.add("X-Video-Ip", ip)
        }

        return builder.build()
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "OctopusExtractor"
        private const val SITE_ORIGIN = "https://hentaihaven.xxx"
    }
}
