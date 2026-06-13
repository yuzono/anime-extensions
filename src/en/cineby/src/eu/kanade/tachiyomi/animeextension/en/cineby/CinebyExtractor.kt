package eu.kanade.tachiyomi.animeextension.en.cineby

import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Videasy embeds for a Cineby episode/movie: double-encoded
 * /sources-with-title request → enc-dec.app decrypt → HLS expansion +
 * subtitle/quality formatting.
 */
class CinebyExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val resultCache = LruCache<CacheKey, CachedResult>(CACHE_SIZE)
    private val resultCacheLock = Any()

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

    private fun apiOrigin(url: String): String = url
        .removePrefix("https://www.").removePrefix("http://www.")
        .let { if (it.startsWith("http")) it else "https://$it" }

    @RequiresApi(Build.VERSION_CODES.N)
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

        val videoList = eligibleServers.parallelCatchingFlatMap { server ->
            val now = System.currentTimeMillis()

            val stateKey = "${server.displayName}:$path"
            val state = serverFailureState[stateKey]
            val circuitOpen = state != null &&
                state.count >= MAX_SERVER_FAILURES &&
                now - state.lastFailureAtMillis < CIRCUIT_COOLDOWN_MS
            if (circuitOpen) {
                return@parallelCatchingFlatMap emptyList()
            }

            val cacheKey = cacheKey(server, path, title, year, imdbId)

            val cached = synchronized(resultCacheLock) {
                resultCache.get(cacheKey)?.takeIf { it.expiresAtMillis > now }
            }
            if (cached != null) {
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

                // API headers use stripped domain (Videasy expects no www.)
                val apiOrigin = apiOrigin(baseUrl)
                val backendHeaders = headers.newBuilder()
                    .set("Referer", "$apiOrigin/")
                    .set("Origin", apiOrigin)
                    .build()

                val encryptedText = client.newCall(
                    GET(serverUrl.toString(), backendHeaders),
                ).awaitSuccess().bodyString()

                val requestBody = mapOf("text" to encryptedText, "id" to pathParts[1])
                    .toJsonRequestBody()
                val decrypted = client.newCall(POST(DECRYPTION_API_URL, body = requestBody))
                    .awaitSuccess()
                    .parseAs<VideasyDecryptionDto>()
                    .result

                synchronized(resultCacheLock) {
                    resultCache.put(cacheKey, CachedResult(decrypted, now + CACHE_TTL_MS))
                }
                serverFailureState.remove(stateKey)
                buildVideos(server, decrypted, baseUrl, subLimit)
            } catch (e: Throwable) {
                serverFailureState.merge(
                    stateKey,
                    FailureState(1, now),
                ) { old, _ -> FailureState(old.count + 1, now) }
                throw e
            }
        }

        return videoList.sortedWith(
            compareByDescending<Video> {
                it.quality.contains(qualityPref, ignoreCase = true) ||
                    (qualityPref == "2160" && it.quality.contains("4k", ignoreCase = true))
            }.thenByDescending {
                extractQualityValue(it.quality)
            },
        )
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

    private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))

    /**
     * Returns true if the given quality string is a language name masquerading
     * as a resolution label. Checks both audioLabel (e.g. "German" from meine)
     * and qualityFilter (e.g. "English"/"Hindi" from hdmovie) since the
     * audioLabel for English servers is now "Original" instead of "English".
     */
    private fun isLanguageAsQuality(server: VideasyServer, quality: String): Boolean = quality.equals(server.audioLabel, ignoreCase = true) ||
        (server.qualityFilter != null && quality.equals(server.qualityFilter, ignoreCase = true))

    /**
     * Returns true if the quality string already represents a real resolution
     * (e.g. "1080p", "720p", "480p", "4K", "2160p", or bare digits like "1080").
     * These don't need HLS expansion — the server already provided the correct label.
     */
    private fun isRealResolution(quality: String): Boolean = qualityRegex.containsMatchIn(quality) ||
        quality.contains("4k", ignoreCase = true) ||
        quality.all { it.isDigit() }

    /**
     * Extracts a numeric quality value for sorting. Maps "4K" to 2160
     * so it sorts above 1080p instead of being treated as 0.
     */
    private fun extractQualityValue(quality: String): Int {
        val match = qualityRegex.find(quality)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: 0
        }
        if (quality.contains("4k", ignoreCase = true)) return 2160
        return 0
    }

    private fun buildVideos(
        server: VideasyServer,
        decrypted: VideasyDecryptedResult,
        baseUrl: String,
        subLimit: Int,
    ): List<Video> {
        val subtitles = decrypted.subtitles
            .mapNotNull { sub ->
                val u = sub.url ?: return@mapNotNull null
                val l = sub.language ?: return@mapNotNull null
                Track(u, l)
            }
            .take(subLimit.coerceAtLeast(0))

        // Use stripped domain (no www.) for video headers — matches what the
        // original working code sent. Some CDNs/Cloudflare configs are sensitive
        // to the exact Referer value.
        val streamOrigin = apiOrigin(baseUrl)
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$streamOrigin/")
            .set("Origin", streamOrigin)
            .build()

        val filteredSources = decrypted.sources?.let { sources ->
            server.qualityFilter?.let { filter ->
                sources.filter { it.quality.equals(filter, ignoreCase = true) }
            } ?: sources
        }

        return when {
            !filteredSources.isNullOrEmpty() -> {
                filteredSources.flatMap { source ->
                    val rawQuality = source.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                    val isHls = source.url.lowercase().contains(".m3u8")
                    val isLang = isLanguageAsQuality(server, rawQuality)

                    // Expand when quality is NOT a real resolution AND either:
                    // - URL is .m3u8 (standard HLS), or
                    // - Quality is a language name (these are almost always HLS
                    //   even if the URL doesn't contain .m3u8 explicitly)
                    // Don't expand when quality is already "1080p" etc —
                    // those servers provide correct labels per source.
                    val needsExpansion = !isRealResolution(rawQuality) &&
                        (isHls || isLang)

                    if (needsExpansion) {
                        val expanded = runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = source.url,
                                videoNameGen = { quality ->
                                    buildVideoLabel(server, quality, source.url, subtitles.size)
                                },
                                subtitleList = subtitles,
                                masterHeaders = videoHeaders,
                                videoHeaders = videoHeaders,
                            )
                        }.getOrDefault(emptyList())

                        expanded.ifEmpty {
                            listOf(
                                Video(
                                    url = source.url,
                                    quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                    videoUrl = source.url,
                                    headers = videoHeaders,
                                    subtitleTracks = subtitles,
                                ),
                            )
                        }
                    } else {
                        listOf(
                            Video(
                                url = source.url,
                                quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                videoUrl = source.url,
                                headers = videoHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }
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

    private fun buildVideoLabel(
        server: VideasyServer,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf("Server: ${server.displayName}")

        // If the "quality" is actually a language name (e.g. "German" from
        // meine, "English"/"Hindi" from hdmovie), don't show it as video
        // quality — the audioLabel already conveys the language.
        if (!isLanguageAsQuality(server, quality)) {
            parts += quality
        }

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

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        //   Official servers (verified against website JS + reference table)
        //   Neon    = mb-flix                                (api.videasy.to)
        //   Yoru    = cdn          [MOVIE ONLY, MAY HAVE 4K] (api.videasy.to)
        //   Cypher  = downloader2                            (api.videasy.to)
        //   Sage    = 1movies                                (api.videasy.to)
        //   Breach  = m4uhd                                  (api.videasy.to)
        //   Vyse    = hdmovie      [FILTERS quality=English] (api.videasy.to)
        //   Killjoy = meine ?lang=german  - German          (api.videasy.to)
        //   Harbor  = meine ?lang=italian - Italian         (api.videasy.to)
        //   Chamber = meine ?lang=french  - French [MOVIE ONLY] (api.videasy.to)
        //   Fade    = hdmovie      [FILTERS quality=Hindi]  (api.videasy.to)
        //   Omen    = lamovie             - Spanish          (api.videasy.to)
        //   Raze    = superflix           - Portuguese       (api.videasy.to)
        val VIDEASY_SERVERS = listOf(
            VideasyServer(
                "Neon",
                "https://api.videasy.to",
                "mb-flix",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Yoru",
                "https://api.videasy.to",
                "cdn",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                "Cypher",
                "https://api.videasy.to",
                "downloader2",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Sage",
                "https://api.videasy.to",
                "1movies",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Breach",
                "https://api.videasy.to",
                "m4uhd",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Vyse",
                "https://api.videasy.to",
                "hdmovie",
                qualityFilter = "English",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Killjoy",
                "https://api.videasy.to",
                "meine",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                "Harbor",
                "https://api.videasy.to",
                "meine",
                language = "italian",
                audioLabel = "Italian",
            ),
            VideasyServer(
                "Chamber",
                "https://api.videasy.to",
                "meine",
                language = "french",
                movieOnly = true,
                audioLabel = "French",
            ),
            VideasyServer(
                "Fade",
                "https://api.videasy.to",
                "hdmovie",
                qualityFilter = "Hindi",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                "Omen",
                "https://api.videasy.to",
                "lamovie",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                "Raze",
                "https://api.videasy.to",
                "superflix",
                audioLabel = "Portuguese",
            ),
        )

        val SERVER_DISPLAY_NAMES: List<String> = VIDEASY_SERVERS.map { it.displayName }
    }
}
