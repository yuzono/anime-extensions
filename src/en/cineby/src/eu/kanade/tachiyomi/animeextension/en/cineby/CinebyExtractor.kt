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

    private fun getOrigin(url: String, stripWww: Boolean = true): String {
        val origin = url.toHttpUrl().run { "$scheme://$host" }
        return if (stripWww) origin.replace("://www.", "://") else origin
    }

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
        val tmdbId = pathParts[1]

        val eligibleServers = VIDEASY_SERVERS.filter { server ->
            (!server.movieOnly || isMovie) && server.displayName in enabledServers
        }

        if (eligibleServers.isEmpty()) {
            return emptyList()
        }

        // API headers use full domain (preserving www. if present)
        val apiOrigin = getOrigin(baseUrl, stripWww = false)
        val backendHeaders = headers.newBuilder()
            .set("Referer", "$apiOrigin/")
            .set("Origin", apiOrigin)
            .build()

        val seed = client.newCall(
            GET("$VIDEASY_API_BASE/seed?mediaId=$tmdbId", backendHeaders),
        ).awaitSuccess().parseAs<SeedDto>().seed

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
                    addQueryParameter("tmdbId", tmdbId)
                    if (imdbId.isNotBlank()) addQueryParameter("imdbId", imdbId)
                    if (server.language != null) {
                        addQueryParameter("language", server.language)
                    }
                    addQueryParameter("enc", "2")
                    addQueryParameter("seed", seed)
                }.build()

                val encryptedText = client.newCall(
                    GET(serverUrl.toString(), backendHeaders),
                ).awaitSuccess().bodyString()

                val requestBody = mapOf("text" to encryptedText, "id" to tmdbId, "seed" to seed)
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
     * and qualityFilter (e.g. "English"/"Hindi" from hdmovie).
     */
    private fun isLanguageAsQuality(server: VideasyServer, quality: String): Boolean {
        if (qualityRegex.containsMatchIn(quality) || quality.contains("4k", ignoreCase = true)) {
            return false
        }

        if (isGenericQuality(quality)) {
            return true
        }

        return quality.equals(server.audioLabel, ignoreCase = true) ||
            (server.qualityFilter != null && quality.equals(server.qualityFilter, ignoreCase = true))
    }

    private fun isGenericQuality(quality: String): Boolean {
        val normalized = quality.trim().lowercase()
        return normalized in GENERIC_QUALITY_PLACEHOLDERS ||
            normalized.isBlank() ||
            GENERIC_QUALITY_REGEX.matches(normalized)
    }

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

        // Use full domain (preserve www.) for video headers — some CDNs
        // (especially Jett's shegu.net) are sensitive to the exact Referer.
        val streamOrigin = getOrigin(baseUrl, stripWww = false)
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$streamOrigin/")
            .set("Origin", streamOrigin)
            .build()

        val filteredSources = decrypted.sources?.let { sources ->
            server.qualityFilter?.let { filter ->
                sources.filter { it.quality.equals(filter, ignoreCase = true) }
            } ?: sources
        }

        val videos = when {
            !filteredSources.isNullOrEmpty() -> {
                filteredSources.distinctBy { it.url }.flatMap { source ->
                    val rawQuality = source.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                    val isHls = source.url.lowercase().contains(".m3u8")
                    val isDash = source.url.lowercase().contains(".mpd")
                    val isLang = isLanguageAsQuality(server, rawQuality)

                    // Always expand HLS/Dash to extract multiple audio tracks and resolutions.
                    // For servers with generic playlists (like Yoru/Neon), we must expand
                    // to find the actual resolutions.
                    val isGeneric = isGenericQuality(rawQuality)
                    val needsExpansion = isHls || isDash || isLang || isGeneric

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

        return videos.distinctBy { it.videoUrl }.map { video ->
            if (video.audioTracks.size > 1 && !video.quality.contains("Multi audio", ignoreCase = true)) {
                val newQuality = if (video.quality.contains("audio")) {
                    video.quality.replace(Regex("""\w+ audio"""), "Multi audio")
                } else {
                    video.quality + " · Multi audio"
                }
                video.copy(quality = newQuality)
            } else {
                video
            }
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
        val parts = mutableListOf(server.displayName)

        if (!isLanguageAsQuality(server, quality)) {
            parts += quality
        }

        val isUhd = quality.contains("2160") || quality.contains("4k", ignoreCase = true)
        if (isUhd && !quality.contains("4k", ignoreCase = true)) {
            parts += "4K"
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

        server.audioLabel?.let { parts += "$it audio" }
        if (subCount > 0) {
            parts += "$subCount subs"
        }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val VIDEASY_API_BASE = "https://api.wingsdatabase.com"
        private const val DECRYPTION_API_URL = "https://enc-dec.app/api/dec-videasy"
        private const val HEX = "0123456789ABCDEF"

        private const val CACHE_SIZE = 64
        private const val CACHE_TTL_MS = 60_000L

        private const val MAX_SERVER_FAILURES = 2
        private const val CIRCUIT_COOLDOWN_MS = 180_000L

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        private val GENERIC_QUALITY_PLACEHOLDERS = setOf(
            "original",
            "auto",
            "video",
            "full video",
            "watch video",
            "play video",
            "hls",
            "dash",
        )

        private val GENERIC_QUALITY_REGEX = Regex("""^(video|stream|hls|dash)(\s+.*)?$""")

        //   Official servers (verified against website JS + reference table)
        //   Jett    = jett                                   (api.wingsdatabase.com)
        //   Yoru    = cdn          [MOVIE ONLY, MAY HAVE 4K] (api.wingsdatabase.com)
        //   Tejo    = tejo                                   (api.wingsdatabase.com)
        //   Neon    = neon2                                  (api.wingsdatabase.com)
        //   Sage    = ym                                     (api.wingsdatabase.com)
        //   Cypher  = downloader2                            (api.wingsdatabase.com)
        //   Breach  = m4uhd                                  (api.wingsdatabase.com)
        //   Vyse    = hdmovie      [FILTERS quality=English] (api.wingsdatabase.com)
        //   Killjoy = meine ?lang=german  - German           (api.wingsdatabase.com)
        //   Fade    = hdmovie      [FILTERS quality=Hindi]   (api.wingsdatabase.com)
        //   Omen    = lamovie             - Spanish          (api.wingsdatabase.com)
        //   Raze    = superflix           - Portuguese       (api.wingsdatabase.com)
        val VIDEASY_SERVERS = listOf(
            VideasyServer(
                "Jett",
                VIDEASY_API_BASE,
                "jett",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Yoru",
                VIDEASY_API_BASE,
                "cdn",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                "Tejo",
                VIDEASY_API_BASE,
                "tejo",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Neon",
                VIDEASY_API_BASE,
                "neon2",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Sage",
                VIDEASY_API_BASE,
                "ym",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Cypher",
                VIDEASY_API_BASE,
                "downloader2",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Breach",
                VIDEASY_API_BASE,
                "m4uhd",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Vyse",
                VIDEASY_API_BASE,
                "hdmovie",
                qualityFilter = "English",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Killjoy",
                VIDEASY_API_BASE,
                "meine",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                "Fade",
                VIDEASY_API_BASE,
                "hdmovie",
                qualityFilter = "Hindi",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                "Omen",
                VIDEASY_API_BASE,
                "lamovie",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                "Raze",
                VIDEASY_API_BASE,
                "superflix",
                audioLabel = "Portuguese",
            ),
        )

        val SERVER_DISPLAY_NAMES: List<String> = VIDEASY_SERVERS.map { it.displayName }
    }
}
