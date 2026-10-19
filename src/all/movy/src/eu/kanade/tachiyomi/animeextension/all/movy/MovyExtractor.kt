package eu.kanade.tachiyomi.animeextension.all.movy

import android.util.Log
import android.util.LruCache
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

/**
 * Resolves Videasy embeds for a Movy episode/movie: double-encoded
 * /sources-with-title request → enc-dec.app decrypt → HLS expansion +
 * subtitle/quality formatting.
 */
class MovyExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val resultCache = LruCache<CacheKey, CachedResult>(CACHE_SIZE)
    private val resultCacheLock = Any()

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

    private fun getOrigin(url: String, stripWww: Boolean = true): String {
        val origin = url.toHttpUrl().run { "$scheme://$host" }
        return if (stripWww) origin.replace("://www.", "://") else origin
    }

    suspend fun videosForServer(
        server: VideasyServer,
        path: String,
        title: String,
        year: String,
        imdbId: String,
        baseUrl: String,
        subLimit: Int,
    ): List<Video> {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"
        val tmdbId = pathParts[1]

        val now = System.currentTimeMillis()
        val cacheKey = cacheKey(server, path, title, year, imdbId)

        val cached = synchronized(resultCacheLock) {
            resultCache.get(cacheKey)?.takeIf { it.expiresAtMillis > now }
        }
        if (cached != null) {
            return buildVideos(server, cached.result, baseUrl, subLimit)
        }

        return try {
            val apiOrigin = getOrigin(baseUrl, stripWww = false)
            val backendHeaders = headers.newBuilder()
                .set("Referer", "$apiOrigin/")
                .set("Origin", apiOrigin)
                .build()

            val seed = client.get(
                "$VIDEASY_API_BASE/seed?mediaId=$tmdbId",
                backendHeaders,
            ).parseAs<SeedDto>().seed

            val serverUrl = server.apiBase.toHttpUrl().newBuilder().apply {
                addPathSegments(server.path)
                addPathSegment("sources")
                addEncodedQueryParameter("title", doubleEncode(title))
                addQueryParameter("mediaType", if (isMovie) "movie" else "tv")
                addQueryParameter("year", year)
                if (!isMovie) {
                    addQueryParameter("episodeId", pathParts[3])
                    addQueryParameter("seasonId", pathParts[2])
                }
                addQueryParameter("tmdbId", tmdbId)
                if (imdbId.isNotBlank()) addQueryParameter("imdbId", imdbId)
                if (server.language != null) {
                    addQueryParameter("language", server.language)
                }
                addQueryParameter("enc", "2")
                addQueryParameter("seed", seed)
            }.build()

            val encryptedText = client.get(
                serverUrl.toString(),
                backendHeaders,
            ).bodyString()

            val requestBody = mapOf("text" to encryptedText, "id" to tmdbId, "seed" to seed)
                .toJsonRequestBody()
            val decrypted = client.post(DECRYPTION_API_URL, body = requestBody)
                .parseAs<VideasyDecryptionDto>()
                .result

            synchronized(resultCacheLock) {
                resultCache.put(cacheKey, CachedResult(decrypted, now + CACHE_TTL_MS))
            }
            buildVideos(server, decrypted, baseUrl, subLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("MovyExtractor", "Error resolving video for server ${server.displayName}", e)
            emptyList()
        }
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
     * Returns true if the quality string already represents a real resolution
     * (e.g. "1080p", "720p", "480p", "4K", "2160p", or bare digits like "1080").
     * These don't need HLS expansion — the server already provided the correct label.
     */
    private fun isRealResolution(quality: String): Boolean = quality.isNotBlank() && (
        qualityRegex.containsMatchIn(quality) ||
            quality.contains("4k", ignoreCase = true) ||
            quality.all { it.isDigit() }
        )

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

                    // Expand when quality is NOT a real resolution AND either:
                    // - URL is .m3u8/.mpd (standard HLS/DASH), or
                    // - Quality is a language name (these are almost always HLS
                    //   even if the URL doesn't contain .m3u8 explicitly)
                    // - Quality is a generic placeholder (e.g. "Auto", "video")
                    //   to catch playlists that lack a file extension.
                    //
                    // FORCE EXPANSION FOR PHOENIX: phoenix often returns a master playlist
                    // without a .m3u8 extension. We force it here to extract the variants.
                    val isGeneric = isGenericQuality(rawQuality)
                    val needsExpansion = (!isRealResolution(rawQuality) && (isHls || isDash || isLang || isGeneric)) ||
                        (server.displayName == "Phoenix")

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
                                    videoUrl = source.url,
                                    videoTitle = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                    headers = videoHeaders,
                                    subtitleTracks = subtitles,
                                ),
                            )
                        }
                    } else {
                        listOf(
                            Video(
                                videoUrl = source.url,
                                videoTitle = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
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
                        videoUrl = url,
                        videoTitle = buildVideoLabel(server, quality, url, subtitles.size),
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
            decrypted.url != null -> {
                val expanded = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = decrypted.url,
                        videoNameGen = { quality ->
                            buildVideoLabel(server, quality, decrypted.url, subtitles.size)
                        },
                        subtitleList = subtitles,
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                    )
                }.getOrDefault(emptyList())

                expanded.ifEmpty {
                    listOf(
                        Video(
                            videoUrl = decrypted.url,
                            videoTitle = buildVideoLabel(server, "Auto", decrypted.url, subtitles.size),
                            headers = videoHeaders,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
            else -> emptyList()
        }

        // Return directly without post-processing to preserve original labels
        return videos.distinctBy { it.videoUrl }
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
        private const val VIDEASY_API_BASE = "https://api.wecollege.net"
        private const val DECRYPTION_API_URL = "https://enc-dec.app/api/dec-videasy"
        private const val HEX = "0123456789ABCDEF"

        private const val CACHE_SIZE = 64
        private const val CACHE_TTL_MS = 60_000L

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
        //   Miami      Original     https://api.wecollege.net/miami/sources        [May have 4K]
        //   Boise      Original     https://api.wecollege.net/boise/sources        [May have 4K]
        //   Vegas      Original     https://api.wecollege.net/vegas/sources
        //   Reno       Original     https://api.wecollege.net/reno/sources
        //   Oakland    Original     https://api.wecollege.net/oakland/sources
        //   Seattle    Original     https://api.wecollege.net/seattle/sources
        //   Phoenix    Original     https://api.wecollege.net/phoenix/sources
        //   Atlanta    Original     https://api.wecollege.net/atlanta/sources
        //   Portland   Original     https://api.wecollege.net/portland/sources
        //   Austin     Original     https://api.wecollege.net/austin/sources       [FILTERS quality == "English"]
        //   Dallas     Original     https://api.wecollege.net/dallas/sources
        //   Tampa      Original     https://api.wecollege.net/tampa/sources
        //   Orlando    Original     https://api.wecollege.net/orlando/sources
        //   Munich     German       https://api.wecollege.net/munich/sources?language=german
        //   Berlin     German       https://api.wecollege.net/berlin/sources       [USES German altTitle when available]
        //   Paris      French       https://api.wecollege.net/paris/sources
        //   Delhi      Hindi        https://api.wecollege.net/delhi/sources        [FILTERS quality == "Hindi"]
        //   Cancun     Spanish      https://api.wecollege.net/cancun/sources

        val VIDEASY_SERVERS = listOf(
            VideasyServer(
                "Miami",
                VIDEASY_API_BASE,
                "miami",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                "Boise",
                VIDEASY_API_BASE,
                "boise",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                "Vegas",
                VIDEASY_API_BASE,
                "vegas",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Reno",
                VIDEASY_API_BASE,
                "reno",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Oakland",
                VIDEASY_API_BASE,
                "oakland",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Seattle",
                VIDEASY_API_BASE,
                "seattle",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Phoenix",
                VIDEASY_API_BASE,
                "phoenix",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Atlanta",
                VIDEASY_API_BASE,
                "atlanta",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Portland",
                VIDEASY_API_BASE,
                "portland",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Austin",
                VIDEASY_API_BASE,
                "austin",
                qualityFilter = "English",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Dallas",
                VIDEASY_API_BASE,
                "dallas",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Tampa",
                VIDEASY_API_BASE,
                "tampa",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Orlando",
                VIDEASY_API_BASE,
                "orlando",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Munich",
                VIDEASY_API_BASE,
                "munich",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                "Berlin",
                VIDEASY_API_BASE,
                "berlin",
                audioLabel = "German",
            ),
            VideasyServer(
                "Paris",
                VIDEASY_API_BASE,
                "paris",
                audioLabel = "French",
            ),
            VideasyServer(
                "Delhi",
                VIDEASY_API_BASE,
                "delhi",
                qualityFilter = "Hindi",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                "Cancun",
                VIDEASY_API_BASE,
                "cancun",
                audioLabel = "Spanish",
            ),
        )

        val SERVER_DISPLAY_NAMES: List<String> = VIDEASY_SERVERS.map { it.displayName }
    }
}
