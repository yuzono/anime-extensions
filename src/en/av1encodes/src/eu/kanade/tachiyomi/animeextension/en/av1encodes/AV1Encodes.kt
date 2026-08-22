package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class AV1Encodes :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AV1Encodes"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")

    // ══════════════════════════════════════════════════════════════════════════
    // POPULAR
    // ══════════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val spotlight = parseSpotlightList(doc)
        return AnimesPage(
            if (spotlight.isNotEmpty()) spotlight else parseCardList(doc).animes,
            false,
        )
    }

    private val seasonRegex by lazy { Regex("""\[S\d""") }
    private val animeNameRegex by lazy { Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""") }
    private val specialCharactersRegex by lazy { Regex("[^a-z0-9]+") }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull {
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

        searchContext.select("a[href*='/anime/'],div[class*='card'],div[class*='item'],li")
            .filter { el ->
                val text = el.text().trim()
                text.contains(seasonRegex) || text.length in 10..200
            }
            .forEach { el ->
                val link = el.selectFirst("a[href*='/anime/']")
                    ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
                if (link != null) {
                    val url = normalizePath(link.attr("href"))
                    if (url.startsWith("/anime/") && seen.add(url)) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain(url)
                                title = extractCleanTitle(el.text())
                                thumbnail_url = getListImageUrl(el)
                            },
                        )
                    }
                    return@forEach
                }

                val animeName = extractCleanTitle(el.text().trim())
                val slug = animeName.lowercase(Locale.US).replace(specialCharactersRegex, "-").trim('-')
                if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach
                animes.add(
                    SAnime.create().apply {
                        setUrlWithoutDomain("/anime/$slug")
                        title = animeName
                    },
                )
            }

        if (animes.isEmpty()) {
            animeNameRegex.findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug = animeName.lowercase(Locale.US)
                        .replace(specialCharactersRegex, "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain("/anime/$slug")
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        return animes.fetchMissingCovers()
    }

    private fun parseSpotlightList(doc: Document): List<SAnime> {
        return doc.select("article.spotlight-slide, .spotlight-slide").mapNotNull { slide ->
            val link = slide.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val url = normalizePath(link.attr("href"))
            if (!url.startsWith("/anime/") || url == "/anime/") return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(url)
                title = slide.selectFirst(".spotlight-title, h3, h4")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: link.text().trim()
                thumbnail_url = slide.selectFirst("img")?.let { image ->
                    image.attr("abs:data-src").ifBlank { null }
                        ?: image.attr("abs:data-lazy-src").ifBlank { null }
                        ?: image.attr("abs:src").ifBlank { null }
                }
            }.takeIf { it.title.isNotBlank() }
        }.distinctBy { it.url }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LATEST
    // ══════════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = normalizePath(a.attr("href"))
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img, img")?.let { img: Element ->
                    img.attr("abs:data-src").ifBlank { null }
                        ?: img.attr("abs:data-lazy-src").ifBlank { null }
                        ?: img.attr("abs:src").ifBlank { null }
                }
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
        var genreValue = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                is GenreFilter -> genreValue = GENRE_VALUES.getOrElse(filter.state) { "" }
                else -> {}
            }
        }

        Log.d(TAG, "searchAnimeRequest: sort=$sortValue type=$typeValue genre=$genreValue")

        // Genre takes priority — uses path-based route
        // With this:
        val url = "$baseUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (genreValue.isNotBlank()) {
            Log.d(TAG, "searchAnimeRequest: routing to /anime?genres=$genreValue&page=$page")
            url.addQueryParameter("genres", genreValue)
        }
        if (sortValue.isNotBlank()) url.addQueryParameter("sort", sortValue)
        if (typeValue.isNotBlank()) url.addQueryParameter("type", typeValue)
        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val url = response.request.url
        val path = url.encodedPath
        val hasQueryParams = url.querySize > 0
        Log.d(TAG, "searchAnimeParse: path=$path fullUrl=$url hasQueryParams=$hasQueryParams")
        return when {
            // Search page uses card layout
            path == "/search" -> parseCardList(doc)

            // Airing pages use card layout
            path.startsWith("/airing") -> parseCardList(doc)

            // Anime browse + sort + genres all use the SAME plain list layout
            path == "/anime" -> parseAnimeListPage(doc)

            else -> parseCardList(doc)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CARD LIST PARSER  —  /airing/*, /search, /genre/*
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseCardList(doc: Document): AnimesPage {
        // Try article cards first (same structure as latestUpdatesParse)
        var animes = doc.select("article.anime-card, article[class*='card'], article[class*='anime']")
            .mapNotNull { card ->
                val a = card.selectFirst("h3 > a, h4 > a, .card-body a, a[href*='/anime/']")
                    ?: return@mapNotNull null
                val href = normalizePath(a.attr("href"))
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = card.selectFirst("div.poster-wrap > img, img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = (card.selectFirst("h3, h4")?.text() ?: a.text()).trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }

        // Fallback: h3-based selector (original logic)
        if (animes.isEmpty()) {
            val contentRoot = doc.selectFirst(
                "main, #main, #content, .content, [class*='anime-list'], [class*='anime-grid'], " +
                    "[class*='result'], [class*='listing'], [class*='airing'], section.animes",
            ) ?: doc
            animes = contentRoot.select("h3").mapNotNull { h3 ->
                val block = h3.parent() ?: return@mapNotNull null
                val a = block.selectFirst("a[href*='/anime/']")
                    ?: block.parent()?.selectFirst("a[href*='/anime/']")
                    ?: return@mapNotNull null
                val href = normalizePath(a.attr("href"))
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = block.parent()?.selectFirst("img") ?: block.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = h3.text().trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }
        }

        Log.d(TAG, "parseCardList: found ${animes.size} animes")
        val hasNextPage = doc.selectFirst(
            ".pagination a[rel=next], .pagination .next:not(.disabled), " +
                "nav.pagination a:contains(Next), [aria-label=Next page]",
        ) != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAIN LIST PARSER  —  /anime (A-Z browse)
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li > a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = a.text().trim().ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst(
            "a[rel=next], .pagination .next, a:contains(Next)",
        ) != null

        return AnimesPage(animes.fetchMissingCovers(), hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // N+1 COVER FETCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun List<SAnime>.fetchMissingCovers(): List<SAnime> {
        return parallelMapNotNullBlocking { anime ->
            runCatching {
                if (anime.thumbnail_url != null) return@runCatching anime
                val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().useAsJsoup()
                val img = doc.selectFirst(
                    "img.anime-poster, img.poster, .anime-hero img, " +
                        "[class*='poster'] img, [class*='hero'] img, main img",
                )
                anime.thumbnail_url =
                    img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                        ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                anime
            }.getOrNull()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANIME DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text()?.trim() ?: ""

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, main img",
            )
            thumbnail_url = img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()?.trim()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a",
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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }
        Log.d(TAG, "episodeListParse: slug=$slug quality=$prefQuality")

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }
        Log.d(TAG, "episodeListParse: seasons=$seasons")

        val resolutionCandidates = qualityPathCandidates(prefQuality)

        val episodeNumberRegex = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMapBlocking { season ->
            var selectedResolution = resolutionCandidates.first()
            var epHtml = ""
            var downloadLinks: List<Element> = emptyList()

            for (resolution in resolutionCandidates) {
                selectedResolution = resolution
                val epPageUrl = "$baseUrl/episodes/$slug/$season/$resolution"
                Log.d(TAG, "episodeListParse: fetching episodes page → $epPageUrl")
                epHtml = client.newCall(GET(epPageUrl, headers)).awaitSuccess().bodyString()
                downloadLinks = Jsoup.parse(epHtml).select("a[href*='/download/']")
                if (downloadLinks.isNotEmpty()) break
            }

            Log.d(TAG, "episodeListParse: found ${downloadLinks.size} download links for season $season")

            if (downloadLinks.isEmpty()) {
                Log.w(TAG, "episodeListParse: no <a> links found, falling back to regex on raw HTML")
                val filenames = extractFilenames(epHtml)
                Log.d(TAG, "episodeListParse: regex found ${filenames.size} filenames")
                return@parallelCatchingFlatMapBlocking filenames.sortedByDescending { parseEpisodeNumber(it) }.map { filename ->
                    val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                    SEpisode.create().apply {
                        setUrlWithoutDomain("/download/$slug/$season/$selectedResolution/$encodedFilename")
                        name = buildEpisodeLabel(filename, season)
                        episode_number = parseEpisodeNumber(filename)
                    }
                }
            }

            downloadLinks.sortedByDescending { link ->
                episodeNumberRegex
                    .find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.map { link ->
                val fullHref = link.attr("href")
                Log.d(TAG, "episodeListParse: episode link → $fullHref")

                val filename = Uri.decode(fullHref.substringAfterLast("/").substringBefore("?"))

                SEpisode.create().apply {
                    setUrlWithoutDomain(fullHref)
                    name = buildEpisodeLabel(filename, season)
                    episode_number = parseEpisodeNumber(filename)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VIDEO LIST
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url
        Log.d(TAG, "getVideoList: episode.url=$episodeUrl")

        val encodedFilename = episodeUrl.substringBefore("?").substringAfterLast("/")
        val filename = Uri.decode(encodedFilename)
        Log.d(TAG, "getVideoList: filename=$filename")

        val downloadPageUrl = baseUrl + episodeUrl

        Log.d(TAG, "getVideoList: fetching download page → $downloadPageUrl")
        val pageHtml = try {
            client.newCall(
                GET(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
            ).awaitSuccess()
                .bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: download page failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val ddlToken = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""").find(pageHtml)
            ?.groupValues?.get(1)
            ?: run {
                Log.w(TAG, "getVideoList: no ddl-token found in page, falling back")
                return fallbackDirectUrl(episodeUrl, filename)
            }
        Log.d(TAG, "getVideoList: ddlToken=$ddlToken")

        val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"
        Log.d(TAG, "getVideoList: calling get_ddl → $ddlUrl")
        val ddlRaw = try {
            client.newCall(
                GET(
                    ddlUrl,
                    headers.newBuilder()
                        .set("Accept", "application/json")
                        .set("Referer", downloadPageUrl)
                        .set("X-Ddl-Token", ddlToken)
                        .build(),
                ),
            ).awaitSuccess()
                .bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: get_ddl failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }
        Log.d(TAG, "getVideoList: get_ddl response=$ddlRaw")

        val ddl = try {
            ddlRaw.parseAs<DdlResponse>()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: get_ddl parse failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }
        if (!ddl.success) {
            Log.w(TAG, "getVideoList: get_ddl success=false")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val videos = mutableListOf<Video>()

        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
        val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
        val qualLabel = "AV1 · $resLabel$audioSuffix$sizeLabel"

        suspend fun resolveRedirect(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val url = if (path.startsWith("/")) "$baseUrl$path" else path
            return try {
                // A GET here can make the source wait for a large media response before
                // the video list is returned. HEAD follows the same redirects without
                // downloading the file, so the player can start immediately.
                val finalUrl = client.newCall(
                    Request.Builder()
                        .url(url)
                        .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
                        .head()
                        .build(),
                )
                    .awaitSuccess().use { resp ->
                        resp.request.url.toString()
                    }
                Log.d(TAG, "getVideoList: redirect $path → $finalUrl")
                finalUrl
            } catch (e: Exception) {
                // Some file hosts reject HEAD. The original URL is still usable
                // because the player follows redirects itself.
                Log.w(TAG, "getVideoList: HEAD failed for $path, using original URL — ${e.message}")
                url
            }
        }

        val (watchUrl, streamUrl, dlUrl, torrentUrl) = coroutineScope {
            listOf(
                async { resolveRedirect(ddl.watchLink) },
                async { resolveRedirect(ddl.streamLink) },
                async { resolveRedirect(ddl.downloadLink) },
                async {
                    if (preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)) {
                        resolveRedirect(ddl.torrentLink)
                    } else {
                        null
                    }
                },
            ).awaitAll()
        }

        val mpdUrl = watchUrl?.let(::buildDashManifestUrl)
        if (mpdUrl != null) {
            Log.d(TAG, "getVideoList: DASH MPD → $mpdUrl")
            videos.add(Video(mpdUrl, "$qualLabel · DASH", mpdUrl))
        }

        if (streamUrl != null && streamUrl != watchUrl) {
            Log.d(TAG, "getVideoList: stream URL → $streamUrl")
            videos.add(Video(streamUrl, "$qualLabel · Stream", streamUrl))
        }

        if (dlUrl != null) {
            Log.d(TAG, "getVideoList: download URL → $dlUrl")
            videos.add(Video(dlUrl, "$qualLabel · Direct DL", dlUrl))
        }

        if (torrentUrl != null) {
            Log.d(TAG, "getVideoList: torrent URL → $torrentUrl")
            videos.add(Video(torrentUrl, "$qualLabel · Torrent", torrentUrl))
        }

        if (videos.isEmpty()) {
            Log.w(TAG, "getVideoList: no videos from get_ddl, falling back")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        Log.d(TAG, "getVideoList: returning ${videos.size} videos")
        // Apply the preference here as well as in the framework sort callback.
        // This keeps the selected link type first on app versions that do not
        // invoke VideoSource.sort() for the initial list.
        return videos.sortByPreferredQuality(preferences)
    }

    private fun qualityPathCandidates(preferredQuality: String): List<String> {
        val value = preferredQuality.trim()
        val compact = value.replace(Regex("""\s+"""), "")
        val resolution = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)
            ?: Regex("""[xX]\s*(\d{3,4})""").find(value)?.groupValues?.get(1)
        val resolutionPath = resolution?.let { "${it}p" }
        return listOf(value, compact, resolutionPath)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .map { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun buildDashManifestUrl(watchUrl: String): String? {
        return runCatching {
            val url = watchUrl.toHttpUrl()
            val marker = "/watch/"
            val markerIndex = url.encodedPath.indexOf(marker)
            if (markerIndex < 0) return null
            val dashPath = url.encodedPath.replaceRange(
                markerIndex,
                markerIndex + marker.length,
                "/dash/",
            ) + "/manifest.mpd"
            url.newBuilder().encodedPath(dashPath).build().toString()
        }.getOrNull()
    }

    private fun fallbackDirectUrl(episodeUrl: String, filename: String): List<Video> {
        val fullUrl = baseUrl + episodeUrl
        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val label = "AV1 · $resLabel${if (audioTag.isNotBlank()) " [$audioTag]" else ""} · Direct DL"
        Log.d(TAG, "getVideoList: fallback URL → $fullUrl")
        return listOf(Video(fullUrl, label, fullUrl))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private val filenameRegex by lazy { Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE) }

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        filenameRegex
            .findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private val episodeNameRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""") }
    private val subdubRegex by lazy { Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE) }
    private val qualityRegex by lazy { Regex("""\[\d{3,4}p].*""") }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = episodeNameRegex.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = subdubRegex
                .find(filename)?.groupValues?.get(1) ?: ""
            "Season $season Ep $e - $titlePart${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        } else {
            val cleanName = filename.replace(qualityRegex, "")
                .substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private val episodeSNumberRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]""") }
    private fun parseEpisodeNumber(filename: String): Float = episodeSNumberRegex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    private val cleanTitleRegex1 by lazy { Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex2 by lazy { Regex("""^\[[a-zA-Z0-9_\-]+]\s*""") }
    private val cleanTitleRegex3 by lazy { Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex4 by lazy { Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE) }

    private fun extractCleanTitle(raw: String): String {
        var cleaned = raw.replace(cleanTitleRegex1, "")
        cleaned = cleaned.replace(cleanTitleRegex2, "")
        cleaned = cleaned.replace(cleanTitleRegex3, "")
        cleaned = cleaned.replace(cleanTitleRegex4, "")
        return cleaned.trim()
    }

    private fun normalizePath(href: String): String {
        val value = href.trim()
        if (value.startsWith("/")) return value
        if (!value.startsWith("http", ignoreCase = true)) return value

        return runCatching {
            val url = value.toHttpUrl()
            val hostIsAllowed = url.host == baseUrl.toHttpUrl().host ||
                url.host.endsWith(".av1encodes.com") ||
                url.host.endsWith(".av1please.com")
            if (hostIsAllowed) {
                buildString {
                    append(url.encodedPath)
                    url.encodedQuery?.let { append('?').append(it) }
                }
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private fun getListImageUrl(anchor: Element): String? {
        val img = anchor.selectFirst("img")
        if (img != null) {
            val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("abs:src") }
            if (url.isNotBlank()) return url
        }
        return extractBg(anchor) ?: anchor.allElements.firstNotNullOfOrNull { extractBg(it) }
    }

    private val backgroundUrlRegex by lazy { Regex("""url\(['"](.*?)['"]\)""") }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = backgroundUrlRegex.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Genre overrides Sort and Type"),
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen)
    }

    override fun List<Video>.sort(): List<Video> = sortByPreferredQuality(preferences)

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "AV1Encodes"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
