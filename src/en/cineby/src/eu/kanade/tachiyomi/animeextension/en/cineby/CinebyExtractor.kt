package eu.kanade.tachiyomi.animeextension.en.cineby

import android.util.LruCache
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Videasy embeds for a Cineby episode/movie: double-encoded
 * /sources-with-title request → enc-dec.app decrypt → HLS expansion +
 * subtitle/quality formatting.
 */
class CinebyExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val json: Json,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Short-TTL cache of successful decrypts so back→reopen reuses both
    // network round-trips. TTL stays under typical CDN signed-URL expiry.
    private val resultCache = LruCache<CacheKey, CachedResult>(CACHE_SIZE)

    // Half-open per-server circuit breaker: skip after MAX_SERVER_FAILURES
    // failures, probe again after CIRCUIT_COOLDOWN_MS. Resets on app restart.
    private val serverFailureState = ConcurrentHashMap<String, FailureState>()

    private data class CacheKey(
        val server: VideasyServer,
        val path: String,
        val title: String,
        val year: String,
        val imdbId: String,
    )

    private data class CachedResult(
        val result: VideasyDecryptedResult,
        val expiresAtMillis: Long,
    )

    private data class FailureState(
        val count: Int,
        val lastFailureAtMillis: Long,
    )

    /**
     * Resolve the playable sources for a single episode/movie.
     *
     * @param path TMDB-shaped path "movie/{id}" or "tv/{id}/{season}/{episode}"
     * @param title Display title used for the (double-encoded) ?title= param
     * @param year Release year used by some servers as a disambiguation key
     * @param imdbId Optional IMDb id; sent only when non-blank
     * @param baseUrl The currently-selected Cineby host (Referer / Origin)
     * @param enabledServers Set of [VideasyServer.displayName] values the
     *                       user has enabled in preferences
     * @param subLimit Maximum number of subtitle tracks to attach per source
     * @param qualityPref User's preferred quality token (e.g. "1080");
     *                    matching videos float to the top of the result
     */
    suspend fun videosFromUrl(
        path: String,
        title: String,
        year: String,
        imdbId: String,
        baseUrl: String,
        enabledServers: Set<String>,
        subLimit: Int,
        qualityPref: String,
    ): List<Video> {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"

        val eligibleServers = VIDEASY_SERVERS.filter { server ->
            (!server.movieOnly || isMovie) && server.displayName in enabledServers
        }

        // Title is double-URL-encoded to match the enc-dec.app reference
        // (Python `quote(quote(t, safe=""), safe="")`). For movies, mirror
        // the site's seasonId=1&episodeId=1 quirk.
        val videoList = eligibleServers.parallelCatchingFlatMap { server ->
            val now = System.currentTimeMillis()

            val state = serverFailureState[server.displayName]
            val circuitOpen = state != null &&
                state.count >= MAX_SERVER_FAILURES &&
                now - state.lastFailureAtMillis < CIRCUIT_COOLDOWN_MS
            if (circuitOpen) {
                return@parallelCatchingFlatMap emptyList()
            }

            val cacheKey = cacheKey(server, path, title, year, imdbId)

            // Headers rebuilt per call since baseUrl can change between hits.
            resultCache.get(cacheKey)
                ?.takeIf { it.expiresAtMillis > now }
                ?.let { cached ->
                    return@parallelCatchingFlatMap buildVideos(server, cached.result, baseUrl, subLimit)
                }

            try {
                val seasonId = if (isMovie) "1" else pathParts[2]
                val episodeId = if (isMovie) "1" else pathParts[3]
                val serverUrl = server.apiBase.toHttpUrl().newBuilder().apply {
                    addPathSegments(server.path)
                    addPathSegment("sources-with-title")
                    addEncodedQueryParameter("title", doubleEncode(title))
                    addQueryParameter("mediaType", if (isMovie) "movie" else "tv")
                    addQueryParameter("year", year)
                    addQueryParameter("episodeId", episodeId)
                    addQueryParameter("seasonId", seasonId)
                    addQueryParameter("tmdbId", pathParts[1])
                    if (imdbId.isNotBlank()) addQueryParameter("imdbId", imdbId)
                    if (server.language != null) {
                        addQueryParameter("language", server.language)
                    }
                }.build()

                val backendHeaders = headers.newBuilder()
                    .add("Referer", "$baseUrl/")
                    .build()

                val encryptedText = client.newCall(
                    GET(serverUrl.toString(), backendHeaders),
                ).awaitSuccess().body.string()

                val decryptionPayload = json.encodeToString(
                    mapOf("text" to encryptedText, "id" to pathParts[1]),
                )
                val requestBody = decryptionPayload.toRequestBody(
                    "application/json".toMediaType(),
                )
                val decrypted = client.newCall(POST(DECRYPTION_API_URL, body = requestBody))
                    .awaitSuccess()
                    .parseAs<VideasyDecryptionDto>()
                    .result

                // Empty `decrypted` still counts as success — upstream answered.
                resultCache.put(cacheKey, CachedResult(decrypted, now + CACHE_TTL_MS))
                serverFailureState.remove(server.displayName)
                buildVideos(server, decrypted, baseUrl, subLimit)
            } catch (e: Throwable) {
                serverFailureState.merge(
                    server.displayName,
                    FailureState(1, now),
                ) { old, _ -> FailureState(old.count + 1, now) }
                throw e
            }
        }

        return videoList.sortedWith(
            compareByDescending<Video> {
                it.quality.contains(qualityPref, ignoreCase = true)
            }.thenByDescending {
                qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        )
    }

    // RFC 3986 percent-encode (NOT java.net.URLEncoder, which is form-encoding:
    // space→"+", leaves "*" unreserved, encodes "~"). Unreserved set:
    // [A-Z a-z 0-9 - . _ ~]; everything else is %HH over UTF-8 bytes.
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

    private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))

    private fun buildVideos(
        server: VideasyServer,
        decrypted: VideasyDecryptedResult,
        baseUrl: String,
        subLimit: Int,
    ): List<Video> {
        // SubtitleDto fields are nullable — server JSON shapes vary
        // ({file,label} vs {url,lang}). Drop unusable entries.
        val subtitles = decrypted.subtitles
            .mapNotNull { sub ->
                val u = sub.url ?: return@mapNotNull null
                val l = sub.language ?: return@mapNotNull null
                Track(u, l)
            }
            .take(subLimit)

        // Segment CDN enforces Referer hot-link check (Origin alone 403s).
        val videoHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()

        return when {
            !decrypted.sources.isNullOrEmpty() -> {
                decrypted.sources.map { source ->
                    val q = source.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                    Video(
                        url = source.url,
                        quality = buildVideoLabel(server, q, source.url, subtitles.size),
                        videoUrl = source.url,
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
            decrypted.streams != null -> {
                decrypted.streams.map { (quality, url) ->
                    Video(
                        url = url,
                        quality = buildVideoLabel(server, quality, url, subtitles.size),
                        videoUrl = url,
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
            decrypted.url != null -> {
                playlistUtils.extractFromHls(
                    playlistUrl = decrypted.url,
                    videoNameGen = { quality ->
                        buildVideoLabel(server, quality, decrypted.url, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                )
            }
            else -> emptyList()
        }
    }

    private fun cacheKey(
        server: VideasyServer,
        path: String,
        title: String,
        year: String,
        imdbId: String,
    ): CacheKey = CacheKey(server, path, title, year, imdbId)

    // Format must stay compatible with `qualityRegex` (matches `(\d{3,4})p`)
    // and the user's quality preference substring filter. Audio-language
    // hint is per-server brand, not per-stream — the API doesn't expose it.
    private fun buildVideoLabel(
        server: VideasyServer,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf("Server: ${server.displayName}", quality)
        val isUhd = quality.contains("2160") || quality.contains("4k", ignoreCase = true)
        if (isUhd && !quality.contains("4k", ignoreCase = true)) parts += "4K"
        val lower = url.lowercase()
        val container = when {
            ".m3u8" in lower -> "HLS"
            ".mpd" in lower -> "DASH"
            ".mkv" in lower -> "MKV"
            ".mp4" in lower -> "MP4"
            ".webm" in lower -> "WebM"
            else -> null
        }
        if (container != null) parts += container
        server.audioLabel?.let { parts += "$it audio" }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    companion object {
        private const val DECRYPTION_API_URL = "https://enc-dec.app/api/dec-videasy"
        private const val HEX = "0123456789ABCDEF"

        private const val CACHE_SIZE = 64
        private const val CACHE_TTL_MS = 60_000L

        private const val MAX_SERVER_FAILURES = 2
        private const val CIRCUIT_COOLDOWN_MS = 180_000L

        private val qualityRegex = Regex("""(\d{3,4})p""")

        //   Tier 1
        //   Neon  = myflixerzupcloud
        //   Yoru  = cdn                (huge sub catalog)
        //   Sage  = 1movies
        //   Sova  = mb-flix           (bonus)
        //
        //
        //   Tier 2 — official catalog
        //   Cypher = moviebox
        //   Reyna  = primewire (api2)
        //   Breach = m4uhd
        //   Vyse   = hdmovie
        //   Jett   = primesrcme
        //   Phoenix= overflix (api2)
        //   Astra  = visioncine
        //
        //   Tier 3 — foreign-language servers
        //   Killjoy = meine ?lang=german  - German
        //   Harbor  = meine ?lang=italian - Italian (movies only in practice)
        //   Chamber = meine ?lang=french  - French (officially MOVIE ONLY)
        //   Fade    = hdmovie             - Hindi (currently dead)
        //   Omen    = lamovie             - Spanish
        //   Gekko   = cuevana (api2)      - Spanish
        //   Raze    = superflix           - Portuguese
        //
        //   Tier 4 — bonus direct-download backend
        //   Brimstone = downloader2         MP4/MKV direct files
        val VIDEASY_SERVERS = listOf(
            // Tier 1: default-enabled English/multi
            VideasyServer(
                "Neon",
                "https://api.videasy.net",
                "myflixerzupcloud",
                audioLabel = "English",
            ),
            VideasyServer(
                "Yoru",
                "https://api.videasy.net",
                "cdn",
                mayHave4K = true,
                audioLabel = "English",
            ),
            VideasyServer(
                "Sage",
                "https://api.videasy.net",
                "1movies",
                audioLabel = "English",
            ),
            VideasyServer(
                "Sova",
                "https://api.videasy.net",
                "mb-flix",
                audioLabel = "English",
            ),
            // Tier 2: official catalog
            VideasyServer(
                "Cypher",
                "https://api.videasy.net",
                "moviebox",
                audioLabel = "English",
            ),
            VideasyServer(
                "Reyna",
                "https://api2.videasy.net",
                "primewire",
                audioLabel = "English",
            ),
            VideasyServer(
                "Breach",
                "https://api.videasy.net",
                "m4uhd",
                audioLabel = "English",
            ),
            VideasyServer(
                "Vyse",
                "https://api.videasy.net",
                "hdmovie",
                audioLabel = "English",
            ),
            VideasyServer(
                "Jett",
                "https://api.videasy.net",
                "primesrcme",
                audioLabel = "English",
            ),
            VideasyServer(
                "Phoenix",
                "https://api2.videasy.net",
                "overflix",
                audioLabel = "Portuguese",
            ),
            VideasyServer(
                "Astra",
                "https://api.videasy.net",
                "visioncine",
                audioLabel = "Portuguese",
            ),
            // Tier 3: foreign-language servers
            VideasyServer(
                "Killjoy",
                "https://api.videasy.net",
                "meine",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                "Harbor",
                "https://api.videasy.net",
                "meine",
                language = "italian",
                audioLabel = "Italian",
            ),
            VideasyServer(
                "Chamber",
                "https://api.videasy.net",
                "meine",
                language = "french",
                movieOnly = true,
                audioLabel = "French",
            ),
            VideasyServer(
                "Fade",
                "https://api.videasy.net",
                "hdmovie",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                "Omen",
                "https://api.videasy.net",
                "lamovie",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                "Gekko",
                "https://api2.videasy.net",
                "cuevana",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                "Raze",
                "https://api.videasy.net",
                "superflix",
                audioLabel = "Portuguese",
            ),
            // Tier 4: bonus direct-download backend
            VideasyServer(
                "Brimstone",
                "https://api.videasy.net",
                "downloader2",
                audioLabel = "English",
            ),
        )

        val SERVER_DISPLAY_NAMES: List<String> = VIDEASY_SERVERS.map { it.displayName }
    }
}
