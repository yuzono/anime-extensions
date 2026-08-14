package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OctopusExtractor — Orchestrator + VP9/CMAF split-stream handler.
 *
 * Responsibilities (and ONLY these):
 *  1. Decode the base64 player payload from the `player.php?data=` query parameter.
 *  2. Assemble and fire the multipart POST to `api.php` to obtain the signed JSON response.
 *  3. Parse the JSON to determine which player framework is active (`isOctopus` flag).
 *  4. Delegate Legacy H.264 streams to [MasterExtractor] (no logic duplication).
 *  5. For Octopus (VP9/CMAF) streams: construct the `playlist_vp9.m3u8` master URL
 *     and return one [Video] per declared variant quality (all pointing at the same
 *     master URL) with the English VTT attached via `subtitleTracks` — see the
 *     architecture section below for why the master is handed to the player
 *     unmodified instead of the video-only variant playlists.
 *
 * ── Octopus Stream Architecture ──────────────────────────────────────────────
 *
 *  The Octopus CDN (octopusmanifest.org) serves a CMAF split-stream layout:
 *
 *    playlist_vp9.m3u8   ← top-level HLS Master Playlist
 *    │
 *    ├─ #EXT-X-STREAM-INF  →  v.m3u8           (video-only VP9 variants)
 *    ├─ #EXT-X-MEDIA TYPE=AUDIO  →  snd/a.m3u8  (standalone AAC audio rendition)
 *    └─ s/en.vtt            (English subtitle track, external VTT)
 *
 *  Segments are plain public static content (no auth header, no cookie, CORS
 *  fully open) — the X-Video-* headers derived from the API response are
 *  optional and harmless.
 *
 *  The variants are video-only, so a bare `v.m3u8` plays without audio. Earlier
 *  versions split the master into per-variant [Video] entries and re-attached the
 *  standalone rendition via `audioTracks` + `subtitleTracks`. That was verified
 *  broken on mpv-based clients (libmpv 0.41, Anikku): `--audio-file` at startup
 *  plays, but `audio-add` of an external HLS playlist while playback is starting
 *  up never attaches (track-list stays at zero, no segment requests), so the
 *  player stays silent. Handing the untouched master instead gives every demuxer
 *  (ExoPlayer, mpv) video + audio + adaptive switching natively in one container —
 *  exactly the layout the vendor's own player requests.
 *
 *  To keep the quality picker visible, one [Video] entry is produced per variant
 *  resolution declared in the master (e.g. "Octopus · 1080p"). Every entry shares
 *  the same master URL, so whatever the user selects the player receives the full
 *  master with native audio and picks the rendition itself (mpv selects the
 *  highest bitrate within `--hls-bitrate-max`, ExoPlayer is adaptive). Quality
 *  selection is therefore a UI affordance, not a bandwidth-pin, but it needs no
 *  proxy and never breaks audio.
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
 *  3. `Accept: application/x-mpegURL, application/vnd.apple.mpegurl, *;q=0.8`
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
 *  - The only manifest processing is reading the master body for the subtitle
 *    URI and the quality label; every URL handed to the player is the canonical
 *    CDN URL, byte-for-byte unmodified.
 *  - `identity` encoding is applied via the Video headers map only, not via OkHttp
 *    client configuration, so it does not interfere with OkHttp's own compressed
 *    responses for the API POST.
 */
class OctopusExtractor(private val client: OkHttpClient) {

    private val masterExtractor by lazy { MasterExtractor(client) }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Decode the player payload, fire the API, and route to the correct extractor.
     *
     * @param apiUrl        Resolved `api.php` URL (extracted from inline JS or fallback).
     * @param playerDataB64 Raw base64 string from `player.php?data=`.
     * @param episodeUrl    Full episode page URL (used for Referer/Origin headers).
     */
    suspend fun getVideosFromPayload(
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
                response.body.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "API POST to $apiUrl failed", e)
            return emptyList()
        }

        // ── Step 3: parse JSON response ───────────────────────────────────────
        val payload = runCatching {
            responseBody.parseAs<JsonObject>()
        }.getOrElse {
            Log.e(TAG, "JSON parse failed. Body preview: ${responseBody.take(200)}")
            return emptyList()
        }

        val videos = runCatching {
            val data = payload["data"]?.jsonObject ?: return emptyList()
            val sourceUrl = data["sources"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("src")
                ?.jsonPrimitive
                ?.content
                ?: return emptyList()

            val isOctopus = data["isOctopus"]?.jsonPrimitive?.boolean ?: false

            Log.d(TAG, "Player type: ${if (isOctopus) "Octopus VP9/CMAF" else "Master H.264"}")
            Log.d(TAG, "Source URL: $sourceUrl")

            if (isOctopus) {
                extractOctopusStream(sourceUrl, episodeUrl, payload)
            } else {
                masterExtractor.extractVideos(sourceUrl, episodeUrl)
            }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to extract videos from payload", e)
            emptyList()
        }

        return videos
    }

    // ── Octopus VP9/CMAF path ─────────────────────────────────────────────────

    /**
     * Build the playback entries for an Octopus (VP9/CMAF) split-stream layout.
     *
     * ── Why the master is handed over unmodified ───────────────────────────────
     *
     * Only the master playlist carries video + audio together (the standalone
     * string rendition is referenced via #EXT-X-MEDIA; the English VTT sits at
     * the CDN-layout path `s/en.vtt`, optionally declared as a SUBTITLES group).
     * The variants themselves are video-only.
     *
     * Splitting the master into per-variant [Video] entries and re-attaching the
     * string rendition via [Track]-based `audioTracks` is unreliable on
     * mpv-based clients (libmpv 0.41, Anikku): external HLS audio added with
     * `audio-add` while playback starts up never attaches, so the player stays
     * silent. Feeding the untouched master instead makes audio/adaptive
     * switching native to the demuxer — verified to play audio on both
     * ExoPlayer and mpv, and it is what the site's own player requests.
     *
     * One [Video] entry is produced per variant resolution declared in the
     * master so the app's quality picker stays populated (e.g. "Octopus ·
     * 1080p", "Octopus · 720p", "Octopus · 360p"). Every entry shares the same
     * master URL, so whichever entry is selected the player receives the full
     * master with native audio and picks the rendition itself — quality
     * selection is a UI affordance, not a bandwidth pin, but it requires no
     * proxy and can never produce a silent stream.
     */
    private fun extractOctopusStream(
        sourceUrl: String,
        episodeUrl: String,
        payload: JsonObject,
    ): List<Video> {
        val masterPlaylistUrl = sourceUrl.toHttpUrl().let { url ->
            if (url.pathSegments.lastOrNull() == "playlist.m3u8") {
                url.newBuilder()
                    .setPathSegment(url.pathSize - 1, "playlist_vp9.m3u8")
                    .build()
                    .toString()
            } else {
                sourceUrl
            }
        }
        val octopusBase = masterPlaylistUrl.substringBeforeLast("/")

        // Signed auth tokens from the API response, if present.
        val auth = payload["authorization"]?.jsonObject
        val videoHeaders = buildOctopusCdnHeaders(episodeUrl, auth)

        Log.d(TAG, "Octopus master URL: $masterPlaylistUrl")

        // Lightweight master fetch: subtitle URI + declared variant resolutions.
        // Never fatal — playback works from the master URL alone.
        var subtitleTrack = Track("$octopusBase/s/en.vtt", "English")
        val declaredHeights = mutableListOf<Int>()
        try {
            val masterBody = client.newCall(GET(masterPlaylistUrl, videoHeaders))
                .execute()
                .use { response -> if (response.isSuccessful) response.bodyString() else "" }
            if (masterBody.isNotBlank()) {
                val declaredSubtitle = masterBody.lineSequence()
                    .firstOrNull { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=\"SUBTITLES\"") }
                    ?.let { line ->
                        line.substringAfter("URI=\"", "")
                            .substringBefore('"')
                            .takeIf { it.isNotBlank() }
                            ?.let { UrlUtils.fixUrl(it, masterPlaylistUrl) }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch the Octopus master playlist", e)
        }

        val entries: List<Video> = if (declaredHeights.isEmpty()) {
            listOf(
                Video(
                    url = masterPlaylistUrl,
                    videoUrl = masterPlaylistUrl,
                    quality = "Octopus · Auto",
                    headers = videoHeaders,
                    subtitleTracks = listOf(subtitleTrack),
                ),
            )
        } else {
            declaredHeights.map { height ->
                Video(
                    url = masterPlaylistUrl,
                    videoUrl = masterPlaylistUrl,
                    quality = "Octopus · ${height}p",
                    headers = videoHeaders,
                    subtitleTracks = listOf(subtitleTrack),
                )
            }
        }

        Log.d(TAG, "Octopus entries: ${entries.joinToString { it.quality }}")
        return entries
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

        /** Maximum number of per-quality entries produced from the master. */
        private const val MAX_QUALITIES = 6

        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=([xX\d]+)""") }
    }
}
