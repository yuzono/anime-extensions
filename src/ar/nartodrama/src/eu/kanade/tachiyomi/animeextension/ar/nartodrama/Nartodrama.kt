package eu.kanade.tachiyomi.animeextension.ar.nartodrama

import android.util.Base64
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
import org.jsoup.nodes.Element

/**
 * Narto Drama – Arabic short-drama / miniseries streaming site.
 *
 * Playback uses the site's `refresh-source` endpoint (`edge.narto-drama.com`),
 * which returns fresh per-play URLs since CDN links expire quickly. The
 * endpoint's `rs_ctx` is a JWT, but the edge ignores the signature and every
 * claim: it only reads the slug/episode from the URL path (verified with a
 * self-minted token carrying a zero signature and bogus claims — the edge
 * still returns `ok:true` with the correct playlist). So the token is minted
 * locally instead of fetching the Cloudflare-challenged `narto-drama.com`
 * watch page, which makes playback independent of passing Cloudflare.
 * Episodes are one of:
 * - an HLS master (mydramawave) — served either proxied through
 *   `stream.narto-drama.com` (JWT-wrapped URIs) or directly via
 *   `direct_play_url`. The direct master is preferred: it is the same
 *   video-v6 CDN sequence drama4all plays, needs no per-segment JWT
 *   microtask, and its `#EXT-X-MEDIA` audio rendition (e.g. `stream_1`) is
 *   selected natively by the app's mpv player (see the HLS notes in
 *   Drama4all for why re-attaching audio via `audioTracks` is not used);
 * - a single-segment joyreels proxy playlist (`seg?u=<urlencoded-ts>`) that
 *   resolves to a direct signed .ts file which rejects the narto Referer
 *   ("denied by Referer ACL"), so it is played with referer-free headers.
 */
class Nartodrama : AnimeHttpSource() {

    override val name = "نارتو دراما"

    override val baseUrl = "https://narto-drama.com"

    override val lang = "ar"

    override val supportsLatest = true

    // The joyreels direct .ts host rejects requests carrying the narto-drama
    // Referer ("denied by Referer ACL"), so build a referer-free header set.
    private val noRefererHeaders: Headers = headers.newBuilder()
        .removeAll("Referer")
        .build()

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
     * Referer-free variant of [playbackHeaders], for flat single-quality streams.
     *
     * The stardust/asiacdn muxed `.ts` host rejects requests carrying the
     * narto-drama Referer ("denied by Referer ACL"), which made those streams
     * play silent once the flat path inherited [playbackHeaders] (it contains
     * the site Referer). The octopus latency headers are kept — `identity`
     * avoids the gzip Content-Length stall on the playlist/segment fetches —
     * only the Referer is dropped.
     */
    private val flatPlaybackHeaders: Headers = noRefererHeaders.newBuilder()
        .add("Accept-Encoding", "identity")
        .add("Cache-Control", "no-transform")
        .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
        .add("Connection", "keep-alive")
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    companion object {
        private val STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF""")

        /**
         * Mints a stateless `rs_ctx` JWT. The edge refresh-source API validates
         * neither the signature nor the payload claims — it reads only the slug
         * and episode number from the URL path (verified: a token with an
         * all-zero signature and bogus claims still returns `ok:true`). The
         * header/payload mirror the real token shape the site mints so the
         * endpoint stays happy; the signature is padded with zeros.
         */
        private fun mintRsCtx(slug: String): String {
            val header = """{"alg":"HS256","typ":"JWT"}"""
            val payload = """
                {
                  "movie_id": 0,
                  "slug": "$slug",
                  "stream_api": 1,
                  "source_app_name": "stardusttv",
                  "source_book_id": "",
                  "language": "ar-SA",
                  "storage_backend": "r2",
                  "exp": 1787967572
                }
            """.trimIndent()
            val encHeader = Base64.encodeToString(header.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
            val encPayload = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
            // Zero-padded 32-byte signature (token asserted to be HS256, but unused).
            val zeros = "0".repeat(43) // 256 bits ≈ 43 base64 chars
            return "$encHeader.$encPayload.$zeros"
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl?page=$page&lang=ar-SA", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.card").map { popularAnimeFromElement(it) }
        return AnimesPage(animes, document.selectFirst("link[rel=next]") != null)
    }

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.attr("data-movie-title").ifBlank {
            element.selectFirst("h3.title, .title")?.text().orEmpty()
        }
        val watchUrl = element.attr("data-watch-url")
            .ifBlank { element.selectFirst("a")?.attr("abs:href").orEmpty() }
        setUrlWithoutDomain(watchUrl.substringBefore("?"))
        thumbnail_url = element.selectFirst("img.poster")?.attr("abs:src")
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ============================ Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.movie-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.movie-meta img.poster")?.attr("abs:src")
            description = document.selectFirst("div.movie-desc")?.text().orEmpty()
            genre = document.select("div.movie-tags a.movie-tag-pill")
                .joinToString { it.text().removePrefix("#") }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup().select("div.episode-list a.episode-item").map { element ->
        SEpisode.create().apply {
            name = element.attr("title").ifBlank { element.text() }
            setUrlWithoutDomain(element.attr("abs:href"))
            element.attr("href").substringAfterLast("/").substringBefore("?")
                .toFloatOrNull()?.let { episode_number = it }
        }
    }

    // ============================ Video Links =============================

    // The refresh context token (rs_ctx) no longer requires the Cloudflare-
    // challenged watch page: the edge refresh-source API ignores the token's
    // signature and every claim, reading only the slug/episode from the URL
    // path. A locally minted JWT is therefore used directly, so playback never
    // depends on passing Cloudflare.
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url.takeIf { it.startsWith("http") } ?: "$baseUrl${episode.url}"
        val slug = episodeSlug(episodeUrl)
            .takeIf { it.isNotBlank() } ?: return emptyList()
        // URL-safe: slug is ASCII [a-z0-9-]; minting is cheap and stateless.
        val rsCtx = mintRsCtx(slug)
        return resolveVideos(slug, episodeUrl, rsCtx)
    }

    private fun resolveVideos(slug: String, episodeUrl: String, rsCtx: String): List<Video> {
        if (rsCtx.isBlank() || slug.isBlank()) return emptyList()

        val epNumber = episodeUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
            ?: "1"

        val refreshUrl = "https://edge.narto-drama.com/e/rs/detail/watch/" +
            "$slug/$epNumber/refresh-source?rs_ctx=${rsCtx.urlEncoded()}"

        val payload = client.newCall(GET(refreshUrl, headers)).execute()
            .parseAs<RefreshSourceDto>()

        // The direct (unproxied) mydramawave master is preferred: it is the same
        // CDN sequence drama4all plays (verified to stream audio natively), and
        // it avoids the stream.narto-drama.com JWT proxy entirely (no per-segment
        // token fetch, no Cloudflare 403 on that host). Fall back to the proxied
        // play_url when the direct master is absent so episodes that expose only
        // the proxied stream still get a playable video.
        val masterUrl = payload.directPlayUrl?.takeIf { it.isNotBlank() }
            ?: payload.playUrl?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // Reuse a small in-memory cache so the subtitle list is never rebuilt
        // twice for the same episode: the transcript confirms the very first
        // playback is what shows subtitles at all, and the extra fetch of the
        // proxy playlists was the original culprit for the never-ending spinner
        // -- keeping every reachable subtitle while never stalling on one.
        // Subtitle URLs come back as host-relative signed paths (`/e/s/<JWT>`)
        // that resolve to the actual Arabic WebVTT only on the streaming JWT-proxy
        // host (`stream.narto-drama.com`) — narto-drama.com itself is
        // Cloudflare-challenged and returns 403, which made the track appear in
        // the player's menu but never load (so it couldn't be selected). Resolve
        // against the proxy host; leave already-absolute URLs untouched.
        val subtitleList = payload.multiSubtitles.mapNotNull { sub ->
            val url = sub.subtitleUrl.orEmpty().ifBlank { return@mapNotNull null }
            val resolved = if (url.startsWith("http")) url else "https://stream.narto-drama.com$url"
            Track(resolved, sub.label.ifBlank { "العربية" })
        }

        // Prefer the source's own resolution list for a quality picker, so the
        // player starts on the user's chosen resolution; the underlying master
        // remains the audio-capable one.
        val heightLabels = payload.multiResolutions
            .mapNotNull { it.resolution.takeIf { r -> r > 0 } }
            .distinct()
            .sortedDescending()

        // The `direct_play_url` comes in three shapes:
        // - mydramawave master (`video-v6/vt/…`): variants + a standalone audio
        //   rendition; hand the untouched master to the player so its demuxer
        //   selects audio natively. One fetch enumerates the declared qualities.
        // - a flat single-quality HLS playlist (muxed .ts over hosts like
        //   stardust/kalostv): no renditions, no extra fetch needed; play it
        //   referer-free (those Hosts reject the site Referer via ACL).
        // - a direct MP4: play with the site Referer headers.
        val videos = when {
            !masterUrl.contains(".m3u8") -> {
                // Direct MP4 (muxed audio in-band), e.g. txvideo.netshort.com.
                val quality = listOfNotNull(
                    qualityFromMp4(masterUrl),
                    streamSizeLabel(masterUrl),
                ).joinToString(" · ").ifBlank { "Narto Drama" }
                listOf(
                    Video(
                        url = masterUrl,
                        quality = quality,
                        videoUrl = masterUrl,
                        headers = headers,
                        subtitleTracks = subtitleList,
                    ),
                )
            }
            isFlatHls(masterUrl) -> {
                listOf(masterVideo(masterUrl, subtitleList, heightLabels, flatPlaybackHeaders))
            }
            else -> {
                val masterBody = fetchQuietly(masterUrl)
                if (STREAM_INF_REGEX.containsMatchIn(masterBody ?: "")) {
                    buildMasterVideos(masterUrl, subtitleList)
                } else {
                    listOf(masterVideo(masterUrl, subtitleList, heightLabels, flatPlaybackHeaders))
                }
            }
        }

        return videos
    }

    /** Lightweight fetch returning the playlist body, or null on any failure.
     * One retry per header set covers the cold-start stall without hanging. */
    private fun fetchQuietly(url: String): String? {
        val headerSets = listOf(playbackHeaders, noRefererHeaders, flatPlaybackHeaders)
        var last: String? = null
        for (i in 0 until 2) {
            for (hs in headerSets) {
                last = runCatching {
                    client.newCall(GET(url, hs)).execute().use { response ->
                        if (response.isSuccessful) response.bodyString() else null
                    }
                }.getOrNull()
                if (!last.isNullOrBlank()) return last
            }
        }
        return last
    }

    /**
     * Builds a single adaptive [Video] pointing at the mydramawave HLS master.
     * The master's video variants are video-only; audio is a separate
     * `#EXT-X-MEDIA` rendition. Handing the untouched master to the player lets
     * its demuxer (mpv) select the audio rendition natively — re-attaching the
     * rendition as `Track`-based `audioTracks` is unreliable on mpv (verified in
     * OctopusExtractor), so it is deliberately not done here. Only one "Auto"
     * entry is exposed because every variant resolves to the same adaptive master
     * URL, so per-resolution entries could not actually be selected.
     */
    private fun buildMasterVideos(masterUrl: String, subs: List<Track>): List<Video> = listOf(
        Video(
            url = masterUrl,
            quality = "Narto Drama · Auto",
            videoUrl = masterUrl,
            headers = playbackHeaders,
            subtitleTracks = subs,
        ),
    )

    private fun masterVideo(masterUrl: String, subs: List<Track>, heightLabels: List<Int>, headers: Headers): Video = Video(
        url = masterUrl,
        quality = heightLabels.maxOrNull()?.let { "Narto Drama · ${it}p" } ?: "Narto Drama",
        videoUrl = masterUrl,
        headers = headers,
        subtitleTracks = subs,
    )

    /**
     * True when the URL points at a flat single-quality HLS playlist (muxed .ts,
     * no `#EXT-X-STREAM-INF`): a stardust-tv `..._001/<hex>.m3u8` path, a
     * kalostv `…/<hex>.m3u8` media playlist, or any other `.m3u8` that is not
     * the mydramawave `video-v6/vt/…` master.
     */
    private fun isFlatHls(masterUrl: String): Boolean {
        val schemeFree = masterUrl.substringAfter("://").substringBefore("?")
        val host = schemeFree.substringBefore("/")
        val path = schemeFree.substringAfter("/")
        val isStardustFlat = host == "asiacdn-v.stardust-tv.com" && path.endsWith(".m3u8")
        val isKalostvFlat = host == "akm3u8.kalostv.com" && path.endsWith(".m3u8")
        val isMydramawaveMaster = host == "video-v6.mydramawave.com" && path.contains("/vt/")
        return isStardustFlat || isKalostvFlat || (!isMydramawaveMaster && path.contains("/hls/"))
    }

    private fun episodeSlug(episodeUrl: String): String {
        val segments = episodeUrl.toHttpUrlOrNull()?.pathSegments ?: return ""
        val watchIndex = segments.indexOf("watch")
        if (watchIndex == -1 || watchIndex + 1 >= segments.size) return ""
        return segments[watchIndex + 1]
    }

    /** Extracts a quality label from a direct MP4 filename or path (e.g. `720p`). */
    private fun qualityFromMp4(url: String): String? {
        val clean = url.substringBefore("?")
        val name = clean.substringAfterLast("/").substringBefore(".")
        return Regex("""\d{3,4}p|\b\d{3,4}\b""").find(name)?.value?.let { it.trimEnd('p') + "p" }
    }

    /** HEADs a direct file to read its byte size (no body download). */
    private fun streamSizeLabel(url: String): String? = runCatching {
        client.newCall(GET(url, headers).newBuilder().head().build()).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let {
                when {
                    it >= 1_000_000_000 -> "%.1f GB".format(it / 1_000_000_000.0)
                    it >= 1_000_000 -> "%.0f MB".format(it / 1_000_000.0)
                    else -> null
                }
            }
        }
    }.getOrNull()

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lang", "ar-SA")
            .addQueryParameter("page", "$page")
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.card").map { searchAnimeFromElement(it) }
        return AnimesPage(animes, document.selectFirst("link[rel=next]") != null)
    }

    private fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.attr("data-search-title").ifBlank {
            element.attr("data-movie-title").ifBlank {
                element.selectFirst("h3.title")?.text().orEmpty()
            }
        }
        val watchUrl = element.attr("data-watch-url")
            .ifBlank { element.selectFirst("a")?.attr("abs:href").orEmpty() }
        setUrlWithoutDomain(watchUrl.substringBefore("?"))
        thumbnail_url = element.selectFirst("img.poster")?.attr("abs:src")
    }

    // =============================== DTOs ===============================

    @Serializable
    private class RefreshSourceDto(
        @SerialName("play_url") val playUrl: String? = null,
        @SerialName("direct_play_url") val directPlayUrl: String? = null,
        @SerialName("multi_subtitles") val multiSubtitles: List<SubtitleDto> = emptyList(),
        @SerialName("multi_resolutions") val multiResolutions: List<ResolutionDto> = emptyList(),
    )

    @Serializable
    private class SubtitleDto(
        val label: String = "",
        @SerialName("subtitle_url") val subtitleUrl: String? = null,
    )

    @Serializable
    private class ResolutionDto(
        val resolution: Int = 0,
    )
}
