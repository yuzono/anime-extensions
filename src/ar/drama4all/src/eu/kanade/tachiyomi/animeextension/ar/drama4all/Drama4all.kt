package eu.kanade.tachiyomi.animeextension.ar.drama4all

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * Drama4All – Arabic short-drama streaming site.
 *
 * List and search pages embed the catalogue as `const LIBRARY = [...]` in the
 * page body. Episode video URLs come from `/api/episode/<slug>/<n>?t=<now>`:
 * either a direct MP4 (Cloudflare R2) or an HLS master (mydramawave).
 *
 * The mydramawave HLS masters are video-only variants + a separate `#EXT-X-MEDIA`
 * audio rendition (e.g. `stream_1`), and the current app player is mpv. Splitting
 * such a master and re-attaching the audio rendition as `Track`-based
 * `audioTracks` is broken on mpv (it never attaches external HLS audio during
 * startup, leaving the player silently dead — see OctopusExtractor's notes), so
 * the unmodified master is passed to the player with the audio declared natively
 * in `#EXT-X-MEDIA`. mpv autoselects the usable rendition itself.
 */
class Drama4all : AnimeHttpSource() {

    override val name = "دراما للجميع"
    override val baseUrl = "https://drama4all.com"
    override val lang = "ar"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    /**
     * Headers handed to the app's player for the HLS master/playlist/segment
     * fetches. Mirrors the proven OctopusExtractor set (same CMAF master layout:
     * video-only variants + a standalone `#EXT-X-MEDIA` audio rendition, master
     * passed through unmodified). The player is a separate HTTP client from this
     * extension's OkHttp; pinning these headers prevents it from stalling on a
     * gzip Content-Length mismatch and keeps the connection alive across the
     * master → variant → audio → segment chain.
     */
    private val playbackHeaders: Headers = headers.newBuilder()
        .add("Accept-Encoding", "identity")
        .add("Cache-Control", "no-transform")
        .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
        .add("Connection", "keep-alive")
        .build()

    /**
     * Referer-free variant of [playbackHeaders] for the flat single-quality
     * streams. The nsstorage muxed `.ts` host (like the nartodrama stardust host)
     * may reject a page Referer via ACL; audio is muxed in-band anyway, so the
     * safest playback headers drop the Referer while keeping the octopus latency
     * set (identity, no-transform, keep-alive).
     */
    private val flatPlaybackHeaders: Headers = playbackHeaders.newBuilder()
        .removeAll("Referer")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/list/all?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val items = parseLibrary(response)
        val animes = items.map { it.toSAnime() }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/list/recent?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Build the query via HttpUrl so special characters (e.g. `&`, `=`, `#`)
        // in the search text are URL-encoded instead of breaking the URL.
        val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // The search endpoint returns the whole result set on one page: requesting
        // a `page` beyond it returns no catalogue data. Always report
        // hasNextPage = false so the app never re-fetches and re-displays the
        // same results.
        return AnimesPage(parseLibrary(response).map { it.toSAnime() }, false)
    }

    // ============================ Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst("section.title-hero img, .title-art img")?.attr("abs:src")
            description = document.selectFirst(".title-body .desc, .synopsis, .desc")?.text().orEmpty()
            genre = document.select(".stage-tags a.tag, .title-body .genre a, .genres a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("a[href*='/watch/']").mapNotNull { el ->
            val href = el.attr("href")
            if (href.isBlank()) return@mapNotNull null
            SEpisode.create().apply {
                setUrlWithoutDomain(href.substringBefore("?"))
                val epNum = href.substringAfterLast("/").substringBefore("?").toFloatOrNull() ?: 0f
                episode_number = epNum
                name = el.select("span.ep-number, span").firstOrNull()?.text()
                    .orEmpty().ifBlank { null } ?: "الحلقة ${epNum.toInt()}"
            }
        }.sortedWith(compareByDescending<SEpisode> { it.episode_number }.thenByDescending { it.name })
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epUrl = episode.url.takeIf { it.startsWith("http") } ?: "$baseUrl${episode.url}"
        val segments = epUrl.toHttpUrlOrNull()?.pathSegments
        if (segments == null || !segments.contains("watch")) return emptyList()
        val slug = segments[segments.indexOf("watch") + 1].takeIf { it != "watch" }
            ?: return emptyList()
        val epNum = segments.lastOrNull()?.toIntOrNull() ?: return emptyList()

        val apiUrl = "$baseUrl/api/episode/$slug/$epNum?t=${System.currentTimeMillis()}"

        // The /api/episode endpoint is cold-start slow (multi-second on the first
        // hit, occasionally an empty/timeout body). Retry once instead of letting
        // the player sit on a blank screen — the second call is much faster.
        val dto = getEpisodeDto(apiUrl) ?: return emptyList()
        val videoUrl = dto.videoUrl.orEmpty()
        if (videoUrl.isBlank()) return emptyList()

        // The API's subtitle URLs are WebVTT files on the same nsstorage CDN as
        // the video (verified reachable, Arabic). They are handed to the player
        // as `Track`s so the Arabic subtitle shows up alongside playback without
        // ever blocking the stream itself.
        val subs = dto.subs.mapNotNull { sub ->
            val url = sub.url.orEmpty().ifBlank { return@mapNotNull null }
            Track(url, sub.lang.ifBlank { "العربية" })
        }

        val videos = if (isFlatHls(videoUrl)) {
            // nsstorage `…/hls/<token>.m3u8` — a single-quality muxed .ts playlist,
            // no master and no alternate renditions. No extra fetch needed: label
            // the quality from the URL path and hand the playlist straight to the
            // player. Skips a network round-trip, which keeps playback responsive
            // even when the /api/episode endpoint is cold.
            listOf(
                Video(
                    url = videoUrl,
                    quality = "Drama4All · ${qualityFromHlsPath(videoUrl)}",
                    videoUrl = videoUrl,
                    headers = flatPlaybackHeaders,
                    subtitleTracks = subs,
                ),
            )
        } else if (videoUrl.contains(".m3u8")) {
            resolveHls(videoUrl, subs)
        } else {
            // Direct MP4 (Cloudflare R2) with audio muxed in.
            val quality = qualityFromMp4(videoUrl)
            val sized = streamSize(videoUrl)?.let { "$quality · ≈$it" } ?: quality
            listOf(
                Video(
                    url = videoUrl,
                    quality = sized,
                    videoUrl = videoUrl,
                    headers = headers,
                    subtitleTracks = subs,
                ),
            )
        }

        return videos
    }

    /**
     * Returns the playable [Video] for an HLS master.
     *
     * The master is NOT split into its per-variant playlists: the video variants
     * are video-only and audio lives in a standalone `#EXT-X-MEDIA` rendition.
     * Re-attaching that rendition via `Track`-based `audioTracks` is unreliable
     * on the app's mpv player, so the untouched master is handed to the player
     * and the audio rendition is selected natively by the demuxer. Only one
     * "Auto" entry is exposed: listing a fixed resolution per variant would be
     * misleading, since every entry would point at the same adaptive master URL
     * (the demuxer selects the rendition). This also avoids a master fetch, so
     * playback starts immediately.
     */
    private fun resolveHls(videoUrl: String, subs: List<Track>): List<Video> = listOf(
        Video(
            url = videoUrl,
            quality = "Drama4All · Auto",
            videoUrl = videoUrl,
            headers = playbackHeaders,
            subtitleTracks = subs,
        ),
    )

    /**
     * `cdn1/cdn2.nsstorage.space/…/hls/<token>.m3u8` layout: a flat single-quality
     * muxed .ts playlist (no `#EXT-X-STREAM-INF`, no alternate audio rendition),
     * as opposed to the mydramawave `video-v6/vt/…` master with per-resolution
     * video-only variants plus a separate audio group.
     */
    private fun isFlatHls(videoUrl: String): Boolean = (videoUrl.startsWith("https://cdn1.nsstorage.space") || videoUrl.startsWith("https://cdn2.nsstorage.space")) &&
        videoUrl.contains("/hls/")

    /** Extracts a quality label from an nsstorage HLS URL path (e.g. `1080p`). */
    private fun qualityFromHlsPath(videoUrl: String): String = Regex("""/\d{3,4}p/""").find(videoUrl)?.value
        ?.trim('/')
        ?: "Auto"

    /** Extracts a quality label from a direct MP4 filename (e.g. `720p`). */
    private fun qualityFromMp4(videoUrl: String): String {
        val clean = videoUrl.substringBefore("?")
        val name = clean.substringAfterLast("/").substringBefore(".")
        return Regex("""\d{3,4}p|\b\d{3,4}\b""").find(name)?.value?.let { it.trimEnd('p') + "p" } ?: "HD"
    }

    /** HEADs a direct file to read its byte size (no body download). */
    private fun streamSize(url: String): String? = runCatching {
        client.newCall(GET(url, headers).newBuilder().head().build()).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let(::formatSize)
        }
    }.getOrNull()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    // ========================== Helper / DTOs ===========================

    /**
     * Fetches the episode payload for [apiUrl], retrying once on failure/empty.
     *
     * The first call to `/api/episode/` on a cold backend can take seconds or
     * return an empty body; the follow-up call is far faster. A single retry
     * keeps playback from hanging on the cold-start window.
     */
    private fun getEpisodeDto(apiUrl: String): EpisodeDto? {
        repeat(2) {
            val dto = runCatching {
                client.newCall(GET(apiUrl, headers)).execute().parseAs<EpisodeDto>()
            }.getOrNull()
            if (dto != null && dto.videoUrl.orEmpty().isNotBlank()) return dto
        }
        return null
    }

    private fun parseLibrary(response: Response): List<LibraryItem> {
        val json = response.bodyString().extractLibrary() ?: return emptyList()
        return json.parseAs<List<LibraryItem>>()
    }

    /**
     * Extracts the `const LIBRARY = [...]` JSON array from the raw page body,
     * scanning brackets with string-awareness so it works regardless of what
     * follows the array (e.g. `const RANKED` is only present on list pages).
     */
    private fun String.extractLibrary(): String? {
        val start = indexOf("const LIBRARY = ")
        if (start == -1) return null
        val arrayStart = indexOf('[', start)
        if (arrayStart == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in arrayStart until length) {
            when (val c = this[i]) {
                '"' -> if (!escaped) inString = !inString
                '\\' -> if (inString) escaped = !escaped
                '[' -> if (!inString) depth++
                ']' -> {
                    if (!inString) depth--
                    if (depth == 0) return substring(arrayStart, i + 1)
                }
                else -> if (c != '\\' && inString) escaped = false
            }
        }
        return null
    }

    @Serializable
    private class LibraryItem(
        @SerialName("cover") private val cover: String = "",
        @SerialName("title") private val title: String = "",
        @SerialName("slug") private val slug: String = "",
    ) {
        fun toSAnime() = SAnime.create().apply {
            url = "/series/$slug"
            this.title = title
            thumbnail_url = cover
        }
    }

    @Serializable
    private class EpisodeDto(
        @SerialName("video_url") val videoUrl: String? = null,
        val subs: List<SubDto> = emptyList(),
    )

    @Serializable
    private class SubDto(
        val lang: String = "",
        val url: String? = null,
    )
}
