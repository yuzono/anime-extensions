package eu.kanade.tachiyomi.animeextension.en.sflix

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class SFlixExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val tag = "SFlixExtractor"

    // ── Chain A: VidSrc ──────────────────────────────────────────────────────

    fun fromVidSrc(embedUrl: String, serverName: String): List<Video> = runCatching {
        VidsrcExtractor(client, headers).videosFromUrl(
            embedLink = embedUrl,
            hosterName = serverName,
        )
    }.getOrElse {
        Log.e(tag, "VidSrc failed for $embedUrl: ${it.message}")
        emptyList()
    }

    // ── Chain B: MoviesAPI ───────────────────────────────────────────────────

    fun fromMoviesApi(
        clubUrl: String,
        serverName: String,
        season: String? = null,
        episode: String? = null,
    ): List<Video> = runCatching {
        // ── Step 1: Get the IMDB content ID ──────────────────────────────────
        // Follow the HTTP 301 from moviesapi.club to moviesapi.to.
        // We only need the content ID (imdb) from the final URL.
        val step1Resp = client.newCall(
            GET(clubUrl, headers.newBuilder().set("Referer", "$siteUrl/").build()),
        ).execute()
        step1Resp.body.close()

        // Extract the raw path segment (e.g. "tt1234567" for movies,
        // "tt1234567-1-2" for TV URLs like moviesapi.club/tv/<imdb>-<s>-<e>).
        val rawSegment = step1Resp.request.url.pathSegments
            .lastOrNull { it.isNotBlank() }
            ?: run {
                Log.e(tag, "No content ID in ${step1Resp.request.url}")
                return emptyList()
            }

        // Strip season/episode suffix if present so the streamdata API
        // receives only the bare IMDB ID (e.g. "tt1234567", not "tt1234567-1-2").
        // Season and episode are passed explicitly as parameters when known.
        val contentId = if (season != null && episode != null) {
            rawSegment.substringBefore("-$season-$episode")
                .substringBefore("-$season-")
                .takeIf { it.startsWith("tt") } ?: rawSegment
        } else {
            rawSegment
        }

        // ── Step 2: Call streamdata.vaplayer.ru directly ──────────────────────
        // Referer and Origin must be brightpathsignals.com or the API may reject.
        val type = if (season != null && episode != null) "tv" else "movie"
        val apiUrl = buildString {
            append("$STREAMDATA_API?imdb=$contentId&type=$type")
            if (season != null && episode != null) append("&s=$season&e=$episode")
        }

        val apiHeaders = headers.newBuilder()
            .set("Referer", "$BRIGHTPATH_ORIGIN/")
            .set("Origin", BRIGHTPATH_ORIGIN)
            .set("Accept", "*/*")
            .build()

        // ── Step 3: Call the API and extract stream URLs ──────────────────────
        // Use .use {} so the response body is always closed, including the error path.
        val streamUrls = client.newCall(GET(apiUrl, apiHeaders)).execute().use { apiResp ->
            if (!apiResp.isSuccessful) {
                Log.e(tag, "streamdata API returned ${apiResp.code} for $apiUrl")
                return emptyList()
            }
            val apiBody = apiResp.body.string()
            val apiData = json.decodeFromString<StreamDataResponse>(apiBody)

            // Confirmed: data.stream_urls is a list of master.m3u8 URLs (multiple CDN mirrors).
            apiData.data?.streamUrls
                ?.filter { it.isNotBlank() && it.contains(".m3u8") }
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    Log.e(tag, "No stream_urls in streamdata response for $apiUrl")
                    return emptyList()
                }
        }

        val hlsHeaders = headers.newBuilder()
            .set("Referer", "$BRIGHTPATH_ORIGIN/")
            .set("Origin", BRIGHTPATH_ORIGIN)
            .build()

        streamUrls.flatMap { masterUrl ->
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                masterHeaders = hlsHeaders,
                videoHeaders = hlsHeaders,
                videoNameGen = { quality -> "$serverName - $quality" },
            ).ifEmpty {
                listOf(Video(masterUrl, "$serverName - Unknown", masterUrl, hlsHeaders))
            }
        }
    }.getOrElse {
        Log.e(tag, "MoviesAPI failed for $clubUrl: ${it.message}", it)
        emptyList()
    }

    // ── Response models ──────────────────────────────────────────────────────

    @Serializable
    data class StreamDataResponse(
        @SerialName("status_code") val statusCode: String? = null,
        val data: StreamData? = null,
    )

    @Serializable
    data class StreamData(
        val title: String? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("stream_urls") val streamUrls: List<String> = emptyList(),
        @SerialName("thumbnails_url") val thumbnailsUrl: String? = null,
    )

    companion object {
        private const val STREAMDATA_API = "https://streamdata.vaplayer.ru/api.php"

        private const val BRIGHTPATH_ORIGIN = "https://brightpathsignals.com"
    }
}
