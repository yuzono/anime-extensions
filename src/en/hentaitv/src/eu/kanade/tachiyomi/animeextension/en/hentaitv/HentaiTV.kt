package eu.kanade.tachiyomi.animeextension.en.hentaitv

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class HentaiTV : Source() {

    override val name = "HentaiTV"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl = "https://hentai.tv"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val pageHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .build()
    }

    /** The video CDN challenges browser user agents sent from FFmpeg, so use a media-player UA. */
    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .set("User-Agent", PLAYER_USER_AGENT)
            .set("Referer", "${NhPlayerExtractor.NH_BASE}/")
            .set("Accept", "*/*")
            .build()
    }

    /**
     * The app's client opens a Cloudflare bypass WebView for any 403 served by Cloudflare. nhplayer
     * answers a rejected challenge, and the CDN an expired link, with exactly such a 403, which must
     * reach this extension as-is so it can simply sign a new link.
     */
    private val streamClient by lazy {
        client.newBuilder()
            .apply { interceptors().removeAll { it.javaClass.name.contains("Cloudflare", ignoreCase = true) } }
            .build()
    }

    private val nhPlayer by lazy { NhPlayerExtractor(client, streamClient, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val proxy by lazy { KeepWarmProxy(streamClient) }

    private val prefBuffer by preferences.delegate(PREF_BUFFER_KEY, PREF_BUFFER_DEFAULT)
    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val prefKeepWarm by preferences.delegate(PREF_KEEP_WARM_KEY, PREF_KEEP_WARM_DEFAULT)

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = seriesListing("views", page)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = seriesListing("recent", page)

    private suspend fun seriesListing(sort: String, page: Int): AnimesPage {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        val doc = client.get(url, pageHeaders).useAsJsoup()
        val animes = doc.select("a.cat-card[href^='/series/']").map { card ->
            SAnime.create().apply {
                setUrlWithoutDomain(card.absUrl("href").substringBefore('?').trimEnd('/'))
                title = card.selectFirst(".cat-title")?.text()?.ifBlank { null }
                    ?: card.selectFirst("img")?.attr("alt").orEmpty()
                thumbnail_url = card.selectFirst("img.poster-img, img")?.absUrl("src")?.ifBlank { null }
                author = card.selectFirst(".cat-desc")?.text()?.ifBlank { null }
            }
        }.distinctBy { it.url }
        val hasNextPage = doc.select("a[href*='page=']").any { link ->
            PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() == page + 1
        }
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = HentaiTVFilters.parse(filters)
        val slugQuery = query.trim().removePrefix(baseUrl).trim('/')
        return when {
            slugQuery.startsWith("series/") || slugQuery.startsWith("hentai/") -> searchByUrl(slugQuery)
            query.isNotBlank() -> textSearch(query.trim(), params)
            params.needsBrowse -> browse(page, params)
            else -> seriesListing(params.sort.seriesKey, page)
        }
    }

    override fun getFilterList(): AnimeFilterList = HentaiTVFilters.getFilterList()

    /** Opening a pasted `hentai.tv/series/...` or `hentai.tv/hentai/...` link. */
    private suspend fun searchByUrl(path: String): AnimesPage {
        val slug = if (path.startsWith("series/")) {
            path.removePrefix("series/").substringBefore('?').substringBefore('/')
        } else {
            val episodeSlug = path.removePrefix("hentai/").substringBefore('?').substringBefore('/')
            episodePayloadVideo(episodeSlug)?.titleSlug ?: return AnimesPage(emptyList(), false)
        }
        val anime = SAnime.create().apply { url = "/series/$slug" }
        return AnimesPage(listOf(getAnimeDetails(anime).apply { url = "/series/$slug" }), false)
    }

    private suspend fun textSearch(query: String, params: HentaiTVFilters.Params): AnimesPage {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "100")
            .build()
        val videos = client.get(url, apiHeaders).parseAs<VideoListDto>().videos
            .filter { video -> params.matches(video) }
        val series = videos.toSeries()
        val sorted = if (params.isDefaultSort) series else series.sortedBySort(params.sort, videos)
        return AnimesPage(sorted, false)
    }

    private suspend fun browse(page: Int, params: HentaiTVFilters.Params): AnimesPage {
        val url = "$baseUrl/api/browse".toHttpUrl().newBuilder()
            .apply {
                if (params.include.isNotEmpty()) addQueryParameter("genres", params.include.joinToString(","))
                if (params.exclude.isNotEmpty()) addQueryParameter("blacklist", params.exclude.joinToString(","))
                params.studio?.let { addQueryParameter("brands", it) }
                addQueryParameter("sort", params.sort.browseKey)
                addQueryParameter("page", page.toString())
            }
            .build()
        val result = client.get(url, apiHeaders).parseAs<VideoListDto>()
        return AnimesPage(result.videos.toSeries(), page < result.pages)
    }

    private fun HentaiTVFilters.Params.matches(video: VideoDto): Boolean {
        val tags = video.tags.orEmpty().map { it.lowercase() }.toSet()
        return include.all { it.lowercase() in tags } &&
            exclude.none { it.lowercase() in tags } &&
            (studio == null || video.brand.equals(studio, ignoreCase = true))
    }

    private fun List<SAnime>.sortedBySort(sort: HentaiTVFilters.SortOption, videos: List<VideoDto>): List<SAnime> {
        val bySeries = videos.groupBy { it.titleSlug }
        fun episodes(anime: SAnime) = bySeries[anime.url.substringAfterLast('/')].orEmpty()
        return when (sort.seriesKey) {
            "views" -> sortedByDescending { anime -> episodes(anime).sumOf { it.views ?: 0L } }
            "rating" -> sortedByDescending { anime -> episodes(anime).maxOfOrNull { it.rating ?: 0.0 } ?: 0.0 }
            "az" -> sortedBy { it.title.lowercase() }
            else -> sortedByDescending { anime -> episodes(anime).maxOfOrNull { it.releasedAt.orEmpty() }.orEmpty() }
        }
    }

    /** The APIs list episodes; the app lists series, one per `titleSlug`. */
    private fun List<VideoDto>.toSeries(): List<SAnime> = filter { !it.titleSlug.isNullOrBlank() }
        .groupBy { it.titleSlug!! }
        .map { (titleSlug, episodes) ->
            val first = episodes.minBy { it.ep ?: Double.MAX_VALUE }
            SAnime.create().apply {
                url = "/series/$titleSlug"
                title = first.title?.ifBlank { null } ?: titleSlug
                thumbnail_url = absolute(first.cover ?: first.thumb ?: first.featureImage)
                author = first.brand
                genre = episodes.flatMap { it.tags.orEmpty() }.distinctBy { it.lowercase() }.joinToString()
            }
        }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    private fun SAnime.slug(): String = url.substringAfter("/series/").substringBefore('?').trim('/')

    private class SeriesPage(val title: String?, val image: String?, val episodes: List<VideoDto>, val payload: String)

    private suspend fun seriesPage(slug: String): SeriesPage {
        val doc = client.get("$baseUrl/series/$slug", pageHeaders).useAsJsoup()
        val payload = flightPayload(doc)
        var episodes = payloadVideos(payload).filter { it.titleSlug == slug }
        if (episodes.isEmpty()) {
            // Fall back to the search API (matches on the series title).
            val title = doc.selectFirst("h1.genre-name")?.text() ?: slug.replace('-', ' ')
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", title)
                .addQueryParameter("limit", "100")
                .build()
            episodes = runCatching { client.get(url, apiHeaders).parseAs<VideoListDto>().videos }
                .getOrDefault(emptyList())
                .filter { it.titleSlug == slug }
        }
        return SeriesPage(
            title = doc.selectFirst("h1.genre-name")?.text()?.ifBlank { null },
            image = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.ifBlank { null },
            episodes = episodes.distinctBy { it.slug },
            payload = payload,
        )
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val page = seriesPage(anime.slug())
        val episodes = page.episodes.sortedBy { it.ep ?: Double.MAX_VALUE }
        val first = episodes.firstOrNull()
        return SAnime.create().apply {
            title = page.title ?: first?.title ?: anime.title
            thumbnail_url = anime.thumbnail_url ?: absolute(first?.cover ?: first?.thumb) ?: page.image
            background_url = page.image ?: absolute(first?.backdrop ?: first?.featureImage)
            author = episodes.mapNotNull { it.brand?.ifBlank { null } }.distinct().joinToString().ifEmpty { null }
            genre = episodes.flatMap { it.tags.orEmpty() }
                .filterNot { it.equals("HD", true) || it.equals("Hentai", true) }
                .distinctBy { it.lowercase() }
                .joinToString()
                .ifEmpty { null }
            description = buildDescription(episodes, page.payload)
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    private fun buildDescription(episodes: List<VideoDto>, payload: String): String? {
        val synopsis = episodes.asSequence()
            .mapNotNull { it.description?.let { text -> resolveText(text, payload) } }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.isJunkDescription() }
        val info = buildList {
            add("Episodes: ${episodes.size}")
            episodes.mapNotNull { it.year }.minOrNull()?.let { add("Year: $it") }
            episodes.mapNotNull { it.language?.ifBlank { null } }.distinct().takeIf { it.isNotEmpty() }?.let { add("Language: ${it.joinToString()}") }
            episodes.mapNotNull { it.censored }.distinct().takeIf { it.isNotEmpty() }?.let { flags ->
                add(flags.joinToString(" / ") { if (it) "Censored" else "Uncensored" })
            }
            episodes.sumOf { it.views ?: 0L }.takeIf { it > 0 }?.let { add("Views: ${"%,d".format(Locale.US, it)}") }
            episodes.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.let { add("Rating: ${"%.1f".format(Locale.US, it.average())}/10") }
        }.joinToString("\n")
        return listOfNotNull(synopsis, info.ifEmpty { null }).joinToString("\n\n").ifEmpty { null }
    }

    private fun String.isJunkDescription() = startsWith("I'm sorry") || startsWith("I\u2019m sorry") || startsWith("I can't")

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val page = seriesPage(anime.slug())
        val seen = mutableMapOf<String, Int>()
        return page.episodes
            .sortedWith(compareBy<VideoDto> { it.ep ?: Double.MAX_VALUE }.thenBy { it.releasedAt.orEmpty() })
            .mapIndexed { index, video ->
                val number = video.ep ?: (index + 1).toDouble()
                val label = number.toEpisodeLabel()
                val copy = seen.merge(label, 1, Int::plus)!!
                SEpisode.create().apply {
                    url = "/hentai/${video.slug}"
                    name = if (copy > 1) "Episode $label ($copy)" else "Episode $label"
                    episode_number = number.toFloat()
                    date_upload = DATE_FORMAT.tryParse(video.releasedAt)
                    scanlator = listOfNotNull(
                        if (video.embedUrl == null) "No video on hentai.tv" else null,
                        video.quality?.ifBlank { null },
                        video.language?.ifBlank { null },
                        video.censored?.let { if (it) "Censored" else "Uncensored" },
                        video.duration?.ifBlank { null },
                    ).joinToString(" \u2022 ").ifEmpty { null }
                    summary = video.description?.let { resolveText(it, page.payload) }?.trim()?.takeUnless { it.isJunkDescription() }
                    preview_url = absolute(video.thumb ?: video.cover)
                }
            }
            .reversed()
    }

    private fun Double.toEpisodeLabel(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

    // ============================ Video Links =============================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One signed link per server, re-signed on demand; shared by the prefetch, the player and the proxy. */
    private inner class StreamState(@Volatile var ref: StreamRef) {
        private val mutex = Mutex()

        @Volatile private var url: String? = null

        @Volatile private var issuedAt = 0L

        @Volatile var lastUsed = System.currentTimeMillis()

        val age: Long get() = if (url == null) Long.MAX_VALUE else System.currentTimeMillis() - issuedAt

        suspend fun url(forceRefresh: Boolean): String {
            lastUsed = System.currentTimeMillis()
            val stale = url
            return mutex.withLock {
                val current = url
                // Another caller may have refreshed while this one waited for the lock.
                if (current != null && (!forceRefresh || current != stale) && age < FRESH_FOR_MS) return@withLock current
                val fresh = sign()
                url = fresh
                issuedAt = issuedAtOf(fresh)
                fresh
            }
        }

        private suspend fun sign(): String {
            val first = runCatching { nhPlayer.handshake(ref.embedUrl, nhPlayer.absolute(ref.dataId)) }
            first.getOrNull()?.let { return it }

            // The player link inside the embed expires after a day: fetch a current one and retry.
            val second = runCatching {
                val servers = nhPlayer.servers(ref.embedUrl)
                val server = servers.firstOrNull { it.name == ref.name } ?: servers.getOrNull(ref.index) ?: servers.first()
                ref = ref.copy(dataId = server.dataId)
                nhPlayer.handshake(ref.embedUrl, nhPlayer.absolute(ref.dataId))
            }
            second.getOrNull()?.let { return it }

            // Last resort: let the site's own script answer the challenge.
            return runCatching { nhPlayer.webViewResolve(ref.embedUrl, nhPlayer.absolute(ref.dataId)) }
                .getOrElse { throw second.exceptionOrNull() ?: first.exceptionOrNull() ?: it }
        }
    }

    private val streams = ConcurrentHashMap<String, StreamState>()

    private fun stateFor(ref: StreamRef): StreamState = streams.getOrPut(ref.key) { StreamState(ref) }

    /**
     * Re-signs links in the background while the player is (or was recently) reading them, so a seek
     * or a resume never waits for a new handshake. Idle streams are dropped after a few hours.
     */
    private val keepWarmJob by lazy {
        scope.launch {
            while (isActive) {
                delay(KEEP_WARM_TICK_MS)
                val now = System.currentTimeMillis()
                streams.forEach { (key, state) ->
                    val lastUsed = maxOf(state.lastUsed, proxy.lastAccess(key) ?: 0L)
                    when {
                        now - lastUsed > FORGET_AFTER_MS -> streams.remove(key)
                        now - lastUsed < KEEP_WARM_WINDOW_MS && state.age > REFRESH_AFTER_MS ->
                            launch { runCatching { state.url(forceRefresh = true) } }
                    }
                }
                proxy.forgetOlderThan(now - FORGET_AFTER_MS)
            }
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val slug = episode.url.substringAfter("/hentai/").substringBefore('?').trim('/')
        val doc = client.get(getEpisodeUrl(episode), pageHeaders).useAsJsoup()
        val video = payloadVideos(flightPayload(doc)).firstOrNull { it.slug == slug }
        val embedUrl = doc.selectFirst("iframe[src*=nhplayer]")?.absUrl("src")?.ifBlank { null }
            ?: video?.embedUrl?.takeIf { it.startsWith("http") }
            ?: doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { EMBED_URL_REGEX.find(it.data())?.groupValues?.get(1) }
            ?: doc.selectFirst("iframe[src*='/v/']")?.absUrl("src")?.ifBlank { null }
            ?: throw Exception("No video available on hentai.tv for this episode")

        val quality = video?.quality?.ifBlank { null }
        val resolution = quality?.let { RESOLUTION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val servers = nhPlayer.servers(embedUrl).ifEmpty { throw Exception("The player has no servers for this episode") }

        val hosters = servers.mapIndexed { index, server ->
            val ref = StreamRef(embedUrl, index, server.name, server.dataId, quality)
            val lazyVideo = Video(
                videoUrl = "",
                videoTitle = listOfNotNull(server.name, quality).joinToString(" \u2022 "),
                resolution = resolution,
                headers = videoHeaders,
                internalData = ref.toJsonString(),
                initialized = false,
            )
            Hoster(hosterUrl = embedUrl, hosterName = server.name, videoList = listOf(lazyVideo))
        }.sortHosters()

        // Sign the preferred server right away; by the time the player asks, the link is ready.
        hosters.firstOrNull()?.videoList?.firstOrNull()?.let { first ->
            val ref = first.internalData.parseAs<StreamRef>()
            if (ref.dataId.contains("player.php")) {
                val state = stateFor(ref)
                scope.launch { runCatching { state.url(forceRefresh = false) } }
            }
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    override suspend fun resolveVideo(video: Video): Video? {
        if (video.initialized && video.internalData.isEmpty()) return video
        val ref = video.internalData.parseAs<StreamRef>()
        val target = nhPlayer.absolute(ref.dataId)
        val path = target.toHttpUrlOrNull()?.encodedPath.orEmpty()
        return when {
            path.endsWith("player.php") -> resolveNhPlayer(video, ref, target)
            path.endsWith(".m3u8") -> withContext(Dispatchers.IO) {
                playlistUtils.extractFromHls(target, referer = "${NhPlayerExtractor.NH_BASE}/", masterHeaders = videoHeaders, videoHeaders = videoHeaders)
            }.maxByOrNull { it.resolution ?: 0 }?.withPlayerOptions(video.videoTitle)
            path.endsWith(".mp4") -> video.copy(videoUrl = target, mpvArgs = mpvArgs(), initialized = true)
            else -> withContext(Dispatchers.IO) {
                universalExtractor.videosFromUrl(target, headers, prefix = "${ref.name} - ")
            }.maxByOrNull { it.resolution ?: 0 }?.withPlayerOptions(null)
        } ?: throw Exception("Could not load ${ref.name}")
    }

    private fun Video.withPlayerOptions(title: String?): Video = copy(videoTitle = title ?: videoTitle, mpvArgs = mpvArgs(), initialized = true)

    private suspend fun resolveNhPlayer(video: Video, ref: StreamRef, playerUrl: String): Video {
        keepWarmJob.start()
        val state = stateFor(ref)
        val directUrl = state.url(forceRefresh = false)
        val playUrl = if (prefKeepWarm) {
            proxy.register(ref.key, videoHeaders) { force -> state.url(force) }
        } else {
            directUrl
        }
        return video.copy(
            videoUrl = playUrl,
            headers = videoHeaders,
            subtitleTracks = nhPlayer.subtitleOf(playerUrl)?.let { listOf(Track(it, "English")) }.orEmpty(),
            mpvArgs = mpvArgs(),
            initialized = true,
        )
    }

    /**
     * Player options: a bigger demuxer cache than the app's 64 MiB default (see the preference) and
     * FFmpeg auto-reconnect, so a dropped connection resumes from the current byte instead of failing.
     */
    private fun mpvArgs(): List<Pair<String, String>> = listOf(
        "user-agent" to PLAYER_USER_AGENT,
        "cache" to "yes",
        "demuxer-max-bytes" to "${prefBuffer}MiB",
        "demuxer-max-back-bytes" to "${(prefBuffer.toIntOrNull() ?: 96) / 2}MiB",
        "network-timeout" to "30",
        "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10",
    )

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferred = prefServer
        return sortedByDescending { it.hosterName.equals(preferred, ignoreCase = true) }
    }

    // ============================= Utilities ==============================

    private fun absolute(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> baseUrl + "/" + path.removePrefix("/")
    }

    private suspend fun episodePayloadVideo(slug: String): VideoDto? = runCatching {
        val doc = client.get("$baseUrl/hentai/$slug", pageHeaders).useAsJsoup()
        payloadVideos(flightPayload(doc)).firstOrNull { it.slug == slug }
    }.getOrNull()

    /** Joins the `self.__next_f.push([1, "..."])` chunks Next.js streams into the page. */
    private fun flightPayload(doc: Document): String = buildString {
        for (script in doc.select("script")) {
            val data = script.data()
            val start = data.indexOf("self.__next_f.push(")
            if (start < 0) continue
            val array = data.substring(start + "self.__next_f.push(".length).trim().removeSuffix(";").trim().removeSuffix(")")
            val element = runCatching { Json.parseToJsonElement(array) as? JsonArray }.getOrNull() ?: continue
            if ((element.getOrNull(0) as? JsonPrimitive)?.intOrNull != 1) continue
            (element.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.let(::append)
        }
    }

    /** Every episode object (`{"id":"...","slug":...}`) found in the flight payload. */
    private fun payloadVideos(payload: String): List<VideoDto> {
        val videos = mutableListOf<VideoDto>()
        var index = payload.indexOf(VIDEO_OBJECT_START)
        while (index >= 0) {
            val end = matchingBrace(payload, index)
            if (end < 0) break
            val candidate = payload.substring(index, end + 1)
            if (candidate.contains("\"titleSlug\"")) {
                runCatching { candidate.parseAs<VideoDto>() }.getOrNull()
                    ?.takeIf { it.slug.isNotBlank() }
                    ?.let(videos::add)
            }
            index = payload.indexOf(VIDEO_OBJECT_START, index + 1)
        }
        return videos.distinctBy { it.slug }
    }

    private fun matchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /**
     * Long texts are sent as references (`"$22"`) to a `22:T<byte length hex>,<text>` row.
     * The length counts UTF-8 bytes, so it is walked character by character.
     */
    private fun resolveText(value: String, payload: String): String? {
        val ref = TEXT_REF_REGEX.matchEntire(value)?.groupValues?.get(1) ?: return value
        val row = Regex("""(?<![0-9a-zA-Z])${Regex.escape(ref)}:T([0-9a-f]+),""").find(payload) ?: return null
        val byteLength = row.groupValues[1].toIntOrNull(16) ?: return null
        val start = row.range.last + 1
        var bytes = 0
        var i = start
        while (i < payload.length && bytes < byteLength) {
            val c = payload[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return payload.substring(start, minOf(i, payload.length))
    }

    private fun issuedAtOf(url: String): Long {
        val now = System.currentTimeMillis()
        val signed = url.toHttpUrlOrNull()?.queryParameter("verify")?.substringBefore('-')?.toLongOrNull()?.times(1000)
        // Trust the CDN timestamp unless the device clock is far off.
        return signed?.takeIf { kotlin.math.abs(now - it) < 10 * 60_000L } ?: now
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_BUFFER_KEY,
            default = PREF_BUFFER_DEFAULT,
            title = "Buffer size",
            summary = "%s\nHow much video the player downloads ahead (app default is 64 MiB).",
            entries = PREF_BUFFER_ENTRIES,
            entryValues = PREF_BUFFER_VALUES,
        )
        screen.addSwitchPreference(
            key = PREF_KEEP_WARM_KEY,
            default = PREF_KEEP_WARM_DEFAULT,
            title = "Keep video links alive",
            summary = "Video links expire 5 minutes after they are issued. When enabled, playback goes through a " +
                "small on-device relay that re-signs the link automatically, so seeking or resuming after a long " +
                "pause keeps working. Disable only if playback fails to start.",
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_ENTRIES,
        )
    }

    companion object {
        private const val VIDEO_OBJECT_START = "{\"id\":\""

        /** FFmpeg's default User-Agent: what mpv sends on its own and what the video CDN lets through. */
        private const val PLAYER_USER_AGENT = "Lavf/61.7.100"

        private val PAGE_REGEX = Regex("""[?&]page=(\d+)""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")
        private val EMBED_URL_REGEX = Regex(""""embedUrl"\s*:\s*"([^"]+)"""")
        private val TEXT_REF_REGEX = Regex("""\$([0-9a-f]+)""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /** Links are valid for 300 s; hand out ones younger than 4 min so every request starts in time. */
        private const val FRESH_FOR_MS = 4 * 60_000L
        private const val REFRESH_AFTER_MS = 3 * 60_000L
        private const val KEEP_WARM_TICK_MS = 30_000L
        private const val KEEP_WARM_WINDOW_MS = 45 * 60_000L
        private const val FORGET_AFTER_MS = 4 * 60 * 60_000L

        private const val PREF_BUFFER_KEY = "buffer_size_mib"
        private const val PREF_BUFFER_DEFAULT = "96"
        private val PREF_BUFFER_ENTRIES = listOf("Normal (64 MiB)", "Slightly bigger (96 MiB)", "Large (128 MiB)", "Extra large (192 MiB)")
        private val PREF_BUFFER_VALUES = listOf("64", "96", "128", "192")

        private const val PREF_KEEP_WARM_KEY = "keep_links_warm"
        private const val PREF_KEEP_WARM_DEFAULT = true

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Main Player"
        private val PREF_SERVER_ENTRIES = listOf("Main Player")
    }
}
