package eu.kanade.tachiyomi.animeextension.en.hentaidb

import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class HentaiDB : Source() {

    override val name = "HentaiDB"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl = "https://hentaidb.xyz"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // The site's origin answers any request that lacks browser `Sec-Fetch-*` metadata with a bare
    // `403 Forbidden` unless Cloudflare already has the page cached. The app's Cloudflare interceptor
    // treats every 403 from a Cloudflare server as a challenge, opens a WebView (which sends the
    // headers, so Cloudflare caches the page for about a minute), finds no `cf_clearance` cookie and
    // fails with "Failed to bypass Cloudflare". That is why an episode only played on the 2nd or 3rd try.
    // Sending the same headers a browser sends avoids the 403 altogether.

    /** Headers for HTML documents (home, series and watch pages). */
    private val pageHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Sec-Fetch-User", "?1")
            .set("Upgrade-Insecure-Requests", "1")
            .build()
    }

    /** Headers for the catalog script, fetched the way the page's `<script>` tag would. */
    private val scriptHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Sec-Fetch-Dest", "script")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
    }

    /** Headers the player uses for video files. */
    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Sec-Fetch-Dest", "video")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
    }

    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val prefCodec by preferences.delegate(PREF_CODEC_KEY, PREF_CODEC_DEFAULT)
    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val prefAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val prefSubtitle by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)

    // =============================== Catalog ==============================

    private val catalogMutex = Mutex()

    @Volatile
    private var cachedCatalog: HentaiDBCatalog? = null

    @Volatile
    private var catalogFetchedAt = 0L

    private suspend fun catalog(): HentaiDBCatalog {
        freshCatalog()?.let { return it }
        return catalogMutex.withLock {
            freshCatalog() ?: fetchCatalog().also {
                cachedCatalog = it
                catalogFetchedAt = System.currentTimeMillis()
            }
        }
    }

    private fun freshCatalog(): HentaiDBCatalog? = cachedCatalog?.takeIf {
        System.currentTimeMillis() - catalogFetchedAt < CATALOG_TTL_MS
    }

    private suspend fun fetchCatalog(): HentaiDBCatalog {
        val home = client.get(baseUrl, pageHeaders).bodyString()
        val path = CATALOG_PATH_REGEX.find(home)?.groupValues?.get(1)
            ?: throw Exception("Could not locate the catalog. Open the site in WebView and try again.")
        // The catalog file name contains a content hash and the site serves it as `immutable` for a year,
        // so a copy in the app's HTTP disk cache is always valid. Allowing it to be reused skips a ~150 KB
        // download after every app restart and every catalog refresh until the site publishes a new file.
        val script = client.get(baseUrl + path, scriptHeaders, IMMUTABLE_CACHE).bodyString()
        val json = script.substringAfter("=").trim().removeSuffix(";")
        return HentaiDBCatalog(json.parseAs<CatalogDto>())
    }

    private fun HentaiDBCatalog.page(list: List<HentaiDBCatalog.Entry>, page: Int): AnimesPage {
        val from = (page - 1) * PAGE_SIZE
        val items = list.drop(from).take(PAGE_SIZE).map { it.toSAnime() }
        return AnimesPage(items, from + PAGE_SIZE < list.size)
    }

    private fun HentaiDBCatalog.Entry.toSAnime(): SAnime = SAnime.create().apply {
        url = "/s/${row.s}"
        title = row.t
        thumbnail_url = row.a.takeIf(String::isNotEmpty)?.let { baseUrl + it }
        author = studio.ifEmpty { null }
        genre = tagNames.joinToString().ifEmpty { null }
        status = row.toStatus()
    }

    private fun CatalogRowDto.toStatus(): Int = when (sa) {
        1 -> SAnime.COMPLETED
        2 -> SAnime.ONGOING
        3 -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val catalog = catalog()
        return catalog.page(catalog.search(HentaiDBCatalog.Query(sort = "pop")), page)
    }

    // =============================== Latest ===============================

    // The catalog has no "date added" field; the newest listed year is the closest equivalent.
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val catalog = catalog()
        return catalog.page(catalog.search(HentaiDBCatalog.Query(sort = "year")), page)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val catalog = catalog()
        val trimmed = query.trim()

        slugFromQuery(trimmed)?.let { slug ->
            val entry = catalog.bySlug[slug]
            return AnimesPage(listOfNotNull(entry?.toSAnime()), false)
        }

        // Parse the text exactly like the site's search box, then apply the filter sheet on top.
        val catalogQuery = HentaiDBFilters.buildQuery(catalog.parse(trimmed), filters, catalog)
        return catalog.page(catalog.search(catalogQuery), page)
    }

    /** Supports `slug:<slug>` and pasted series/watch URLs. */
    private fun slugFromQuery(query: String): String? {
        if (query.startsWith(PREFIX_SLUG)) return query.removePrefix(PREFIX_SLUG).trim().ifEmpty { null }
        val url = query.toHttpUrlOrNull() ?: return null
        if (!url.host.endsWith("hentaidb.xyz")) return null
        val segments = url.pathSegments
        return when (segments.firstOrNull()) {
            "s" -> segments.getOrNull(1)
            "watch" -> segments.getOrNull(1)?.substringBeforeLast('~')
            else -> null
        }?.ifEmpty { null }
    }

    override fun getFilterList(): AnimeFilterList = HentaiDBFilters.getFilterList(cachedCatalog)

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    private fun SAnime.slug(): String = url.substringAfter("/s/").substringBefore('?').trim('/')

    private class PageResult(val html: String, val url: String)

    private class PendingPage(val createdAt: Long) {
        val result = CompletableDeferred<PageResult?>()
    }

    private val seriesPagesLock = Any()
    private val seriesPages = HashMap<String, PendingPage>()

    /**
     * The app asks for details and episodes at the same time, and both are read from the same series
     * page. Sharing one request (and reusing it for a few seconds) halves the page loads per title.
     * Cloudflare already caches these pages for 60 seconds, so the content is the same either way.
     */
    private suspend fun seriesPage(anime: SAnime): Document? {
        val url = getAnimeUrl(anime)
        val now = System.currentTimeMillis()
        var owner = false
        val pending = synchronized(seriesPagesLock) {
            seriesPages.values.removeAll { now - it.createdAt >= SERIES_PAGE_TTL_MS }
            seriesPages.getOrPut(url) { PendingPage(now).also { owner = true } }
        }
        val result = if (owner) {
            val fetched = try {
                fetchPage(url)
            } catch (e: CancellationException) {
                forgetSeriesPage(url, pending)
                pending.result.complete(null)
                throw e
            }
            if (fetched == null) forgetSeriesPage(url, pending)
            pending.result.complete(fetched)
            fetched
        } else {
            // If the shared request failed, try once on our own, exactly as before.
            pending.result.await() ?: fetchPage(url)
        }
        return result?.let { Jsoup.parse(it.html, it.url) }
    }

    private fun forgetSeriesPage(url: String, pending: PendingPage) = synchronized(seriesPagesLock) {
        if (seriesPages[url] === pending) seriesPages.remove(url)
    }

    private suspend fun fetchPage(url: String): PageResult? = try {
        client.get(url, pageHeaders).use { PageResult(it.body.string(), it.request.url.toString()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        // Load the catalog and the series page at the same time instead of one after the other.
        val (entry, doc) = coroutineScope {
            val entry = async { runCatching { catalog().bySlug[anime.slug()] }.getOrNull() }
            val doc = async { seriesPage(anime) }
            entry.await() to doc.await()
        }
        if (entry == null && doc == null) throw Exception("Could not load this title")

        val meta = doc?.select(".hero dl.meta > div, .hero dl.reg > div")
            ?.associate { it.selectFirst("dt")?.text().orEmpty() to it.selectFirst("dd")?.text().orEmpty() }
            .orEmpty()
        val facts = doc?.select(".hero .fr-c > .fc")
            ?.associate { it.selectFirst("dt")?.text().orEmpty() to it.selectFirst("dd")?.text().orEmpty() }
            .orEmpty()

        return SAnime.create().apply {
            url = anime.url
            title = doc?.selectFirst(".hero h1")?.text()?.ifEmpty { null } ?: entry?.row?.t ?: anime.title
            thumbnail_url = entry?.row?.a?.takeIf(String::isNotEmpty)?.let { baseUrl + it }
                ?: doc?.selectFirst(".hero-cv img")?.absUrl("src")
                ?: anime.thumbnail_url
            author = entry?.studio?.ifEmpty { null } ?: meta["Studio"]?.ifEmpty { null }
            genre = doc?.select(".tgs a.chip")?.eachText()?.ifEmpty { null }?.joinToString()
                ?: entry?.tagNames?.joinToString()?.ifEmpty { null }
            status = entry?.row?.toStatus() ?: when (meta["Status"]) {
                "Completed" -> SAnime.COMPLETED
                "Ongoing" -> SAnime.ONGOING
                "Stalled" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                doc?.selectFirst("#syn p, .syn p")?.text()?.takeIf(String::isNotBlank)?.let { append(it).append("\n\n") }
                doc?.selectFirst(".hero .native")?.text()?.takeIf(String::isNotBlank)?.let { append("Native title: ").append(it).append('\n') }
                entry?.row?.k?.takeIf(String::isNotBlank)?.let { append("Also known as: ").append(it).append('\n') }
                val type = meta["Type"] ?: entry?.row?.ty?.let { HentaiDBCatalog.TYPES.getOrNull(it - 1) }
                type?.takeIf(String::isNotBlank)?.let { append("Type: ").append(it).append('\n') }
                val year = meta["Listed"] ?: entry?.row?.y?.takeIf { it > 0 }?.toString()
                year?.takeIf(String::isNotBlank)?.let { append("Year: ").append(it).append('\n') }
                (facts["Episodes"] ?: entry?.row?.e?.toString())?.let { append("Episodes: ").append(it).append('\n') }
                entry?.runtime?.takeIf { it > 0 }?.let { append("Total runtime: ").append(formatDuration(it)).append('\n') }
                (facts["Best copy"] ?: entry?.row?.q?.takeIf { it > 0 }?.let { "${it}p" })?.let { append("Best copy: ").append(it).append('\n') }
                val cut = facts["Cut"] ?: when (entry?.row?.c) {
                    1 -> "Censored"
                    2 -> "Uncensored"
                    else -> null
                }
                cut?.let { append("Cut: ").append(it).append('\n') }
                facts["Subtitles"]?.let { append("Subtitles: ").append(it).append('\n') }
                if (entry?.row?.dub == 1) append("English dub available\n")
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.slug()
        val doc = seriesPage(anime)
        val fromPage = doc?.select("li.ep[data-ep]")?.mapNotNull { li ->
            val link = li.selectFirst("a[href*=/watch/]") ?: return@mapNotNull null
            val number = li.attr("data-ep").substringAfterLast('~').toFloatOrNull()
            SEpisode.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                episode_number = number ?: 0f
                name = li.selectFirst(".ep-t")?.text()?.ifEmpty { null }
                    ?: number?.let { "Episode ${formatNumber(it)}" }
                    ?: "Episode"
                val details = listOfNotNull(li.selectFirst(".ep-d")?.text()?.ifEmpty { null }) + li.select(".ep-m b").eachText()
                scanlator = details.joinToString(SEPARATOR).ifEmpty { null }
                preview_url = li.selectFirst(".still img")?.absUrl("src")?.ifEmpty { null }
            }
        }.orEmpty().distinctBy { it.url }

        if (fromPage.isNotEmpty()) return fromPage.sortedByDescending { it.episode_number }

        // Fallback to the catalog when the series page cannot be parsed.
        val row = catalog().bySlug[slug]?.row ?: return emptyList()
        val numbers = row.en ?: (1..row.e).toList()
        return numbers.mapIndexed { i, number ->
            SEpisode.create().apply {
                url = "/watch/$slug~$number"
                episode_number = number.toFloat()
                name = "Episode $number"
                scanlator = row.d.getOrNull(i)?.takeIf { it > 0 }?.let(::formatClock)
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val html = client.get(getEpisodeUrl(episode), pageHeaders).bodyString()
        val json = html.substringAfter("window.EP=", "").substringBefore("</script>").trim().removeSuffix(";")
        if (json.isEmpty()) throw Exception("No player data found on this episode page")
        val data = json.parseAs<EpisodeDataDto>()

        val hosters = buildHosters(data, data.src, dub = false, videoHeaders) +
            buildHosters(data, data.dub, dub = true, videoHeaders)
        if (hosters.isEmpty()) throw Exception("No video sources found")
        return hosters
    }

    private fun buildHosters(
        data: EpisodeDataDto,
        sources: List<EpisodeSourceDto>,
        dub: Boolean,
        videoHeaders: Headers,
    ): List<Hoster> {
        val live = sources.filterNot { it.isOffline }.ifEmpty { sources }
        return live.groupBy { it.n ?: 0 }.toSortedMap().map { (server, list) ->
            val name = buildString {
                append(if (server > 0) "Server $server" else "Default")
                if (dub) append(" $DUB_SUFFIX")
            }
            val videos = list.map { source -> source.toVideo(data, videoHeaders) }.sortVideos()
            Hoster(hosterName = name, videoList = videos)
        }
    }

    private fun EpisodeSourceDto.toVideo(data: EpisodeDataDto, videoHeaders: Headers): Video {
        val resolution = r ?: RESOLUTION_REGEX.find(q.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val title = listOfNotNull(
            q?.ifEmpty { null } ?: resolution?.let { "${it}p" },
            codecLabel(c),
            sz?.takeIf { it > 0 }?.let(::formatSize),
        ).joinToString(SEPARATOR).ifEmpty { "Video" }
        val bitrate = if (sz != null && d != null && d > 0) (sz * 8 / d).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

        return Video(
            videoUrl = absolute(file),
            videoTitle = title,
            resolution = resolution,
            bitrate = bitrate,
            headers = videoHeaders,
            subtitleTracks = subtitlesFor(this, data),
            initialized = true,
        )
    }

    private fun subtitlesFor(source: EpisodeSourceDto, data: EpisodeDataDto): List<Track> {
        val set = source.t?.let { data.sets.getOrNull(it) } ?: data.tracks
        val preferred = prefSubtitle
        return set.asSequence()
            .filter { it.kind == null || it.kind == "captions" || it.kind == "subtitles" }
            .map { Track(absolute(it.file), it.label?.ifEmpty { null } ?: "Unknown") }
            .distinctBy { it.lang }
            .sortedByDescending { it.lang.equals(preferred, ignoreCase = true) }
            .toList()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val wantDub = prefAudio == "dub"
        val server = prefServer
        val quality = prefQuality.toIntOrNull() ?: 0
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.endsWith(DUB_SUFFIX) == wantDub }
                .thenByDescending { server != "any" && it.hosterName.removeSuffix(" $DUB_SUFFIX") == "Server $server" }
                .thenByDescending { hoster -> hoster.videoList.orEmpty().any { it.resolution == quality } }
                .thenByDescending { hoster -> hoster.videoList.orEmpty().maxOfOrNull { it.resolution ?: 0 } ?: 0 },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = prefQuality.toIntOrNull() ?: 0
        val codec = codecLabel(prefCodec)
        return sortedWith(
            compareByDescending<Video> { it.resolution == quality }
                .thenByDescending { codec != null && it.videoTitle.contains(codec) }
                .thenByDescending { it.resolution ?: 0 }
                .thenByDescending { it.bitrate ?: 0 },
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("2160p (4K)", "1080p", "720p", "576p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "576", "480", "360"),
        )
        screen.addListPreference(
            key = PREF_CODEC_KEY,
            default = PREF_CODEC_DEFAULT,
            title = "Preferred codec",
            summary = "%s\nH.264 plays everywhere; AV1 is smaller but needs a newer device for smooth playback",
            entries = listOf("H.264", "AV1", "No preference"),
            entryValues = listOf("h264", "av1", "any"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = listOf("No preference") + (1..9).map { "Server $it" },
            entryValues = listOf("any") + (1..9).map { "$it" },
        )
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s\nEnglish dubs exist for only a few titles",
            entries = listOf("Japanese (subtitled)", "English dub"),
            entryValues = listOf("sub", "dub"),
        )
        screen.addListPreference(
            key = PREF_SUB_KEY,
            default = PREF_SUB_DEFAULT,
            title = "Preferred subtitle language",
            summary = "%s",
            entries = SUBTITLE_LANGUAGES,
            entryValues = SUBTITLE_LANGUAGES,
        )
    }

    // =============================== Utils ================================

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> baseUrl + "/" + path.trimStart('/')
    }

    private fun codecLabel(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
        null, "", "any" -> null
        "h264", "avc" -> "H.264"
        "h265", "hevc" -> "HEVC"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        else -> codec.uppercase(Locale.ROOT)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        else -> String.format(Locale.US, "%d MB", (bytes / 1e6).toLong())
    }

    private fun formatClock(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600 + 30) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun formatNumber(number: Float): String = if (number % 1f == 0f) number.toInt().toString() else number.toString()

    companion object {
        const val PREFIX_SLUG = "slug:"

        private const val PAGE_SIZE = 30
        private const val CATALOG_TTL_MS = 30 * 60 * 1000L
        private const val SERIES_PAGE_TTL_MS = 30 * 1000L

        private val IMMUTABLE_CACHE = CacheControl.Builder().maxAge(365, TimeUnit.DAYS).build()
        private const val DUB_SUFFIX = "[Dub]"

        // Written as an escape so the bullet can never be mangled by a non-UTF-8 editor or tool again.
        private const val SEPARATOR = " \u2022 "

        private val CATALOG_PATH_REGEX = Regex("""(/cat-[\w-]+\.js)""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_CODEC_KEY = "preferred_codec"
        private const val PREF_CODEC_DEFAULT = "h264"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "any"
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "sub"
        private const val PREF_SUB_KEY = "preferred_subtitle"
        private const val PREF_SUB_DEFAULT = "English"

        private val SUBTITLE_LANGUAGES = listOf(
            "English", "Arabic", "Chinese", "Czech", "Danish", "Dutch", "Finnish", "French", "German",
            "Greek", "Hebrew", "Hungarian", "Indonesian", "Italian", "Japanese", "Korean", "Polish",
            "Portuguese", "Romanian", "Russian", "Spanish", "Swedish", "Thai", "Turkish", "Ukrainian",
            "Vietnamese",
        )
    }
}
