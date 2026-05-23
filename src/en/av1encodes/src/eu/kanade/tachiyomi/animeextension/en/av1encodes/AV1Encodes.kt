package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Locale

@Suppress("SpellCheckingInspection")
class AV1Encodes :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AV1Encodes"
    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val prefLinkType: String
        get() = preferences.getString(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)!!

    private val prefShowTorrent: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)

    // ─── Client ──────────────────────────────────────────────────────────────

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // ─── Headers ─────────────────────────────────────────────────────────────

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", MOBILE_UA)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Dest", "empty")

    // ══════════════════════════════════════════════════════════════════════════
    // POPULAR
    // ══════════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/stats#top-downloads", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseStatsPage(response.asJsoup()), false)

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1, h2, h3, h4, h5, h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }
        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        val items =
            searchContext.select("a[href*='/anime/'], div[class*='card'], div[class*='item'], li")
                .filter { el ->
                    val text = el.text().trim()
                    text.contains(Regex("""\[S\d""")) || text.length in 10..200
                }

        items.forEach { el ->
            val link = el.selectFirst("a[href*='/anime/']")
                ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
            if (link != null) {
                val url = link.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (url.startsWith("/anime/") && seen.add(url)) {
                    animes.add(
                        SAnime.create().apply {
                            this.url = url
                            title = extractCleanTitle(el.text())
                            thumbnail_url = getListImageUrl(el, baseUrl)
                        },
                    )
                }
                return@forEach
            }

            val rawText = el.text().trim()
            val animeName = extractCleanTitle(rawText)
            val slug = animeName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')

            if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach

            animes.add(
                SAnime.create().apply {
                    url = "/anime/$slug"
                    title = animeName
                    thumbnail_url = null
                },
            )
        }

        if (animes.isEmpty()) {
            Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""").findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug =
                        animeName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                url = "/anime/$slug"
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        fetchMissingCovers(animes)
        return animes
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LATEST  —  /airing/sub  card layout: img + h3 + a
    // ══════════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                url = href
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img")?.attr("abs:src")
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
        }

        var sortValue = ""
        var typeValue = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                else -> {}
            }
        }

        return when (typeValue) {
            "sub" -> GET("$baseUrl/airing/sub?page=$page", headers)
            "dual" -> GET("$baseUrl/airing/dual?page=$page", headers)
            else -> {
                val url = "$baseUrl/anime".toHttpUrl().newBuilder()
                    .addQueryParameter("page", page.toString())
                if (sortValue.isNotBlank()) url.addQueryParameter("sort", sortValue)
                GET(url.build().toString(), headers)
            }
        }
    }

    // Search hits two different page layouts depending on the route:
    //   /search  and  /airing/*  → card layout (img + h3 + a)
    //   /anime                   → plain list layout (li > a, no image)
    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val path = response.request.url.encodedPath
        return if (path.startsWith("/anime") && !path.startsWith("/anime/")) {
            parseAnimeListPage(doc)
        } else {
            parseCardList(doc)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CARD LIST PARSER  —  used by /airing/sub, /airing/dual, /search
    // Each entry: enclosing block with img + h3 title + a[href*='/anime/']
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseCardList(doc: Document): AnimesPage {
        val animes = doc.select("h3").mapNotNull { h3 ->
            val block = h3.parent() ?: return@mapNotNull null
            val a = block.selectFirst("a[href*='/anime/']")
                ?: return@mapNotNull null

            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null

            val img = block.parent()?.selectFirst("img")
                ?: block.selectFirst("img")

            SAnime.create().apply {
                url = href
                title = h3.text().trim()
                thumbnail_url = img?.attr("abs:src")
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst("a[rel=next], .pagination .next, a:contains(Next)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAIN LIST PARSER  —  used by /anime (A-Z browse)
    // Each entry: li > a[href*='/anime/'] with title as link text, no image
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li > a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val title = a.text().trim().ifBlank { return@mapNotNull null }

            SAnime.create().apply {
                url = href
                this.title = title
                thumbnail_url = null
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst("a[rel=next], .pagination .next, a:contains(Next)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARALLEL COVER FETCHER  —  fills missing thumbnails from detail pages
    // ══════════════════════════════════════════════════════════════════════════

    private fun fetchMissingCovers(animes: List<SAnime>) {
        val missingCovers = animes.filter { it.thumbnail_url == null }
        if (missingCovers.isEmpty()) return

        runBlocking {
            withContext(Dispatchers.IO) {
                missingCovers.map { anime ->
                    async {
                        try {
                            val detailUrl = baseUrl + anime.url
                            client.newCall(GET(detailUrl, headers)).execute().use { detailResp ->
                                if (detailResp.isSuccessful) {
                                    val detailHtml = detailResp.body.string()
                                    val ogMatch = Regex(
                                        """<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']|<meta\s+content=["']([^"']+)["']\s+property=["']og:image["']""",
                                    ).find(detailHtml)
                                    if (ogMatch != null) {
                                        anime.thumbnail_url = ogMatch.groupValues[1].ifBlank { ogMatch.groupValues[2] }
                                    } else {
                                        val detailDoc = Jsoup.parse(detailHtml)
                                        val img = detailDoc.selectFirst(
                                            "img.anime-poster, img.poster, .anime-hero img",
                                        )
                                        anime.thumbnail_url =
                                            img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANIME DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text()?.trim() ?: ""

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, .detail-page img, main img",
            )
            thumbnail_url = img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                    baseUrl,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()?.trim()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a, [class*='tag']:not(script):not(style)",
            ).joinToString { it.text().trim() }.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()?.trim()
            status = if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EPISODE LIST
    // ══════════════════════════════════════════════════════════════════════════

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())

        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }

        val seasons =
            doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
                .map { it.attr("data-season") }
                .distinct()
                .ifEmpty { listOf("1") }

        val encodedRes = URLEncoder.encode(prefQuality, "UTF-8").replace("+", "%20")
        val allEpisodes = mutableListOf<SEpisode>()

        for (season in seasons.sortedByDescending { it.toIntOrNull() ?: 0 }) {
            val epPageUrl = "$baseUrl/episodes/$slug/$season/$encodedRes"
            val epHtml = try {
                val resp = client.newCall(
                    GET(
                        epPageUrl,
                        headers.newBuilder()
                            .set("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                            .set("Referer", "$baseUrl/")
                            .build(),
                    ),
                ).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    continue
                }
                resp.body.string()
            } catch (_: Exception) {
                continue
            }

            val items = parseEpisodeItems(epHtml)

            for (item in items.sortedByDescending { it.num }) {
                val epPath = if (item.href.startsWith("http")) {
                    item.href.removePrefix(baseUrl)
                } else {
                    item.href
                }

                allEpisodes.add(
                    SEpisode.create().apply {
                        url = epPath
                        name = buildEpisodeLabel(item, season)
                        episode_number = item.num.toFloat()
                    },
                )
            }
        }

        return allEpisodes
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VIDEO LIST
    // ══════════════════════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url

        // Path A: episode.url is already a /download/…?token= link
        if (episodeUrl.contains("/download/") && episodeUrl.contains("token=")) {
            val filename = mkvFilenameFromDownloadUrl(episodeUrl) ?: return emptyList()
            val downloadPageUrl = if (episodeUrl.startsWith("http")) episodeUrl else baseUrl + episodeUrl

            val downloadPageHtml = try {
                client.newCall(
                    GET(
                        downloadPageUrl,
                        headers.newBuilder()
                            .set("Accept", "text/html,application/xhtml+xml,*/*")
                            .set("Referer", "$baseUrl/")
                            .set("Sec-Fetch-Dest", "document")
                            .set("Sec-Fetch-Mode", "navigate")
                            .build(),
                    ),
                ).execute().body.string()
            } catch (_: Exception) {
                return emptyList()
            }

            val ddlToken = extractDdlToken(downloadPageHtml) ?: return emptyList()

            return callGetDdlApi(
                filename = filename,
                token = ddlToken,
                referer = "$baseUrl/",
            )
        }

        // Path B: episode.url is an episode detail page slug
        val pageUrl = if (episodeUrl.startsWith("http")) episodeUrl else baseUrl + episodeUrl

        val pageHtml = try {
            client.newCall(
                GET(
                    pageUrl,
                    headers.newBuilder()
                        .set("Accept", "text/html,application/xhtml+xml,*/*")
                        .set("Referer", "$baseUrl/")
                        .build(),
                ),
            ).execute().body.string()
        } catch (_: Exception) {
            return emptyList()
        }

        val token = extractDdlToken(pageHtml) ?: return emptyList()
        val filename = extractMkvFilenameFromHtml(pageHtml) ?: return emptyList()

        return callGetDdlApi(filename = filename, token = token, referer = pageUrl)
    }

    private suspend fun callGetDdlApi(filename: String, token: String, referer: String): List<Video> {
        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"

        val ddlHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .set("X-Ddl-Token", token)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Sec-Fetch-Dest", "empty")
            .build()

        val raw = try {
            client.newCall(GET(ddlUrl, ddlHeaders)).execute().body.string()
        } catch (_: Exception) {
            return emptyList()
        }

        val ddl = try {
            json.decodeFromString<DdlResponse>(raw)
        } catch (_: Exception) {
            return emptyList()
        }

        if (!ddl.success) return emptyList()

        fun String.normalise() = if (startsWith("/")) baseUrl + this else this

        val subtitleTracks = ddl.subtitles?.mapIndexedNotNull { i, sub ->
            val lang = sub.language ?: return@mapIndexedNotNull null
            Track("${ddl.streamLink?.normalise() ?: ""}#sub$i", "$lang (${sub.format ?: "SUB"})")
        } ?: emptyList()

        val resLabel = ddl.videoDetails?.firstOrNull()?.let { v ->
            val w = v.width?.filter { it.isDigit() }
            val h = v.height?.filter { it.isDigit() }
            if (!w.isNullOrBlank() && !h.isNullOrBlank()) "$w x $h" else null
        }
            ?: Regex("""\\[(\\d+p)]""").find(ddl.fileName ?: "")?.groupValues?.get(1)
            ?: prefQuality

        val audioLabel = ddl.audioDetails?.audio?.mapNotNull { it.language }
            ?.distinct()?.joinToString("/")?.let { " [$it]" } ?: ""
        val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
        val qualLabel = "AV1 · $resLabel$audioLabel$sizeLabel"

        val videos = mutableListOf<Video>()

        ddl.watchLink?.normalise()?.takeIf { it.isNotBlank() }?.let { url ->
            videos.add(Video(url, "$qualLabel · Watch", url, subtitleTracks = subtitleTracks))
        }
        ddl.streamLink?.normalise()?.takeIf { it.isNotBlank() }?.let { url ->
            if (url != ddl.watchLink?.normalise()) {
                videos.add(Video(url, "$qualLabel · Stream", url, subtitleTracks = subtitleTracks))
            }
        }
        ddl.downloadLink?.normalise()?.takeIf { it.isNotBlank() }?.let { url ->
            if (url != ddl.streamLink?.normalise() && url != ddl.watchLink?.normalise()) {
                videos.add(Video(url, "$qualLabel · Download", url, subtitleTracks = subtitleTracks))
            }
        }
        if (prefShowTorrent) {
            ddl.torrentLink?.normalise()?.takeIf { it.isNotBlank() }?.let { url ->
                videos.add(Video(url, "$qualLabel · Torrent", url))
            }
        }

        return videos
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Sort and Type cannot be combined"),
        SortFilter(),
        TypeFilter(),
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen, preferences)
    }

    override fun List<Video>.sort(): List<Video> = sortByPreferredQuality(preferences)

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}
