package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AV1Encodes : Source() {

    override val name = "AV1Encodes"

    override val lang = "en"

    override val supportsLatest = true

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val prefQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val showTorrent: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .addInterceptor { chain ->
            val original = chain.request()
            val response = chain.proceed(original)
            if (response.code == 403) {
                response.close()
                runCatching {
                    chain.proceed(
                        original.newBuilder()
                            .url("$baseUrl/")
                            .get()
                            .build(),
                    ).close()
                }
                chain.proceed(original)
            } else {
                response
            }
        }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Sec-Ch-Ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = if (page == 1) {
        val response = client.get(baseUrl)
        val animes = parseCardList(
            response.useAsJsoup(),
            selector = "article.spotlight-slide, #latestCompletedList li, .sidebar-list-panel li",
        ).animes
        AnimesPage(animes, true)
    } else {
        val response = client.get("$baseUrl/anime?page=${page - 1}")
        parseAnimeListPage(response.useAsJsoup())
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = if (page == 1) {
        val response = client.get(baseUrl)
        val animes = parseCardList(
            response.useAsJsoup(),
            selector = "#episodeGrid article, .latest-panel article",
        ).animes
        AnimesPage(animes, true)
    } else {
        val response = client.get("$baseUrl/anime?page=${page - 1}")
        parseAnimeListPage(response.useAsJsoup())
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
        } else {
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

            val builder = "$baseUrl/anime".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
            if (genreValue.isNotBlank()) builder.addQueryParameter("genres", genreValue)
            if (sortValue.isNotBlank()) builder.addQueryParameter("sort", sortValue)
            if (typeValue.isNotBlank()) builder.addQueryParameter("type", typeValue)
            builder.build()
        }

        val response = client.get(url)
        val doc = response.useAsJsoup()
        return if (url.encodedPath == "/anime") {
            parseAnimeListPage(doc)
        } else {
            parseCardList(doc)
        }
    }

    private fun parseCardList(
        doc: Document,
        selector: String = "a.anime-link, article.spotlight-slide, article.anime-card, #episodeGrid article, #latestCompletedList li, article[class*='card']",
    ): AnimesPage {
        var animes = doc.select(selector).mapNotNull { el ->
            val a = if (el.tagName() == "a" && el.attr("href").contains("/anime/")) {
                el
            } else {
                el.selectFirst("a[href*='/anime/'], h3 > a, h4 > a") ?: return@mapNotNull null
            }
            val href = normalizePath(a.attr("href"))
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val img = el.selectFirst("img") ?: a.selectFirst("img")
            val titleEl = el.selectFirst(".spotlight-title, h3, h4") ?: a.selectFirst("h3, h4") ?: a
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleEl.text()
                thumbnail_url = extractImgUrl(img)
            }
        }.distinctBy { it.url }

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
                    title = h3.text()
                    thumbnail_url = extractImgUrl(img)
                }
            }.distinctBy { it.url }
        }

        val hasNextPage = doc.selectFirst(
            "a.next-page, .pagination a[rel=next], .pagination .next:not(.disabled), " +
                "nav.pagination a:contains(Next), [aria-label=Next page]",
        ) != null
        return AnimesPage(animes, hasNextPage)
    }

    private suspend fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li > a[href*='/anime/'], a.anime-index-link").mapNotNull { a ->
            val href = normalizePath(a.attr("href"))
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = a.text().trim().ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst(
            "a.next-page, a[rel=next], .pagination .next, a:contains(Next)",
        ) != null

        return AnimesPage(animes.fetchMissingCovers(), hasNextPage)
    }

    private suspend fun List<SAnime>.fetchMissingCovers(): List<SAnime> {
        return parallelMapNotNull { anime ->
            runCatching {
                if (anime.thumbnail_url != null) return@runCatching anime
                val doc = client.get(baseUrl + anime.url).useAsJsoup()
                val img = doc.selectFirst(
                    "img.anime-poster, img.poster, .anime-hero img, " +
                        "[class*='poster'] img, [class*='hero'] img, main img",
                )
                anime.thumbnail_url = extractImgUrl(img)
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                anime
            }.getOrElse { anime }
        }
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeUrl = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        val doc = client.get(animeUrl).useAsJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text() ?: anime.title

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, main img",
            )
            thumbnail_url = extractImgUrl(img)
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a, link[rel='tag'][href*='/genre/']",
            ).map { el ->
                if (el.tagName() == "link") el.attr("href").substringAfterLast("/").replaceFirstChar { it.uppercase() } else el.text()
            }.distinct().filter { it.isNotBlank() }.joinToString().ifBlank {
                doc.selectFirst("p.anime-meta")?.text()?.substringAfter("Genre:")?.substringBefore("|")?.trim()
            }?.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()
            status = if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        }
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        val doc = client.get(animeUrl).useAsJsoup()
        val slug = anime.url.trim('/').split("/").last { it.isNotBlank() }

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }

        val resolutionCandidates = qualityCandidates(prefQuality)

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMap { season ->
            var epHtml = ""
            var downloadLinks: List<Element> = emptyList()
            var selectedRes = resolutionCandidates.first()

            for (res in resolutionCandidates) {
                selectedRes = res
                val epPageUrl = "$baseUrl/episodes/$slug/$season/$res"
                val html = runCatching { client.get(epPageUrl).bodyString() }.getOrNull() ?: continue
                epHtml = html

                if (html.trim().startsWith("[")) {
                    val items = runCatching { html.parseAs<List<EpisodeItem>>() }.getOrNull()
                    if (!items.isNullOrEmpty()) {
                        return@parallelCatchingFlatMap items.sortedByDescending { it.num }.map { item ->
                            val filename = Uri.decode(item.href.substringAfterLast("/").substringBefore("?"))
                            SEpisode.create().apply {
                                setUrlWithoutDomain(item.href)
                                name = if (item.label.isNotBlank()) item.label else buildEpisodeLabel(filename, season)
                                episode_number = if (item.num > 0) item.num.toFloat() else parseEpisodeNumber(filename)
                            }
                        }
                    }
                }

                val parsed = Jsoup.parse(html)
                val links = parsed.select("a[href*='/download/']")
                if (links.isNotEmpty()) {
                    downloadLinks = links
                    break
                }
            }

            if (downloadLinks.isEmpty() && epHtml.isNotBlank()) {
                val filenames = extractFilenames(epHtml)
                if (filenames.isNotEmpty()) {
                    return@parallelCatchingFlatMap filenames.sortedByDescending { parseEpisodeNumber(it) }.map { filename ->
                        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                        SEpisode.create().apply {
                            setUrlWithoutDomain("/download/$slug/$season/$selectedRes/$encodedFilename")
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename)
                        }
                    }
                }
            }

            downloadLinks.sortedByDescending { link ->
                EPISODE_NUMBER_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.map { link ->
                val fullHref = link.attr("href")
                val filename = Uri.decode(fullHref.substringAfterLast("/").substringBefore("?"))
                SEpisode.create().apply {
                    setUrlWithoutDomain(fullHref)
                    name = buildEpisodeLabel(filename, season)
                    episode_number = parseEpisodeNumber(filename)
                }
            }
        }
    }

    // =============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val preferredLinkType = preferences.getString(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)!!
        val hosters = buildList {
            add(Hoster(hosterName = "Dash", internalData = episode.url))
            add(Hoster(hosterName = "Stream", internalData = episode.url))
            add(Hoster(hosterName = "Direct DL", internalData = episode.url))
            if (showTorrent) {
                add(Hoster(hosterName = "Torrent", internalData = episode.url))
            }
        }
        return hosters.sortedByDescending { it.hosterName.equals(preferredLinkType, ignoreCase = true) }
    }

    // =============================== Videos ================================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.internalData
        if (episodeUrl.isBlank()) return emptyList()

        val pathParts = episodeUrl.substringBefore("?").trim('/').split("/")
        val encodedFilename = episodeUrl.substringBefore("?").substringAfterLast("/")
        val filename = Uri.decode(encodedFilename)

        if (pathParts.size < 5 || pathParts[0] != "download") {
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val slug = pathParts[1]
        val season = pathParts[2]
        val originalRes = Uri.decode(pathParts[3])
        val epNum = EPISODE_S_NUMBER_REGEX.find(filename)?.groupValues?.get(1)?.toIntOrNull()

        val allResolutions = listOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360")

        val videos = allResolutions.parallelMapNotNull { resString ->
            runCatching {
                val dlPath = if (resString == originalRes) {
                    episodeUrl
                } else {
                    val encodedRes = URLEncoder.encode(resString, "UTF-8").replace("+", "%20")
                    val epDoc = client.get("$baseUrl/episodes/$slug/$season/$encodedRes").useAsJsoup()
                    epDoc.select("a[href*='/download/']").firstOrNull { link ->
                        val href = link.attr("href")
                        val decodedHref = Uri.decode(href)
                        val linkEpNum = EPISODE_NUMBER_REGEX.find(decodedHref)?.groupValues?.get(1)?.toIntOrNull()
                        (epNum != null && linkEpNum == epNum) || (epNum == null && decodedHref.contains(filename))
                    }?.attr("href") ?: return@runCatching null
                }

                extractVideoForHoster(hoster.hosterName, dlPath, resString, filename, slug, season)
            }.getOrNull()
        }.distinctBy { it.videoUrl }

        if (videos.isEmpty()) {
            return fallbackDirectUrl(episodeUrl, filename)
        }

        return videos.sortByPreferredQuality(preferences).mapIndexed { index, video ->
            if (index == 0) video.copy(preferred = true) else video
        }
    }

    private suspend fun extractVideoForHoster(
        hosterName: String,
        dlPath: String,
        resString: String,
        baseFilename: String,
        slug: String,
        season: String,
    ): Video? {
        val encodedFilename = dlPath.substringBefore("?").substringAfterLast("/")
        val filename = Uri.decode(encodedFilename)
        var downloadPageUrl = baseUrl + dlPath
        var pageHtml = runCatching { client.get(downloadPageUrl).bodyString() }.getOrNull()

        if (pageHtml == null || !pageHtml.contains("anime-video-player")) {
            val encodedRes = URLEncoder.encode(resString, "UTF-8").replace("+", "%20")
            val freshEpDoc = runCatching {
                client.get("$baseUrl/episodes/$slug/$season/$encodedRes").useAsJsoup()
            }.getOrNull()
            val freshLink = freshEpDoc?.select("a[href*='/download/']")?.firstOrNull {
                it.attr("href").contains(encodedFilename)
            }?.attr("href")
            if (!freshLink.isNullOrBlank()) {
                downloadPageUrl = baseUrl + freshLink
                pageHtml = runCatching { client.get(downloadPageUrl).bodyString() }.getOrNull()
            }
        }

        val resLabel = RES_LABEL_REGEX.find(filename)?.groupValues?.get(1)
            ?: resLabelFromResolution(resString)
        val audioTag = AUDIO_TAG_REGEX.find(filename)?.groupValues?.get(1)
            ?: AUDIO_TAG_REGEX.find(baseFilename)?.groupValues?.get(1).orEmpty()
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
        val videoTitle = "AV1 · $resLabel$audioSuffix"

        val doc = pageHtml?.let { Jsoup.parse(it) }
        val iframeSrc = doc?.selectFirst("iframe#anime-video-player, iframe[src*='/r/']")?.attr("src")
        val watchUrl = iframeSrc?.let { resolveRedirect(it) }

        val ddlToken = pageHtml?.let { TOKEN_REGEX.find(it)?.groupValues?.get(1) }
        val ddl = if (ddlToken != null) {
            runCatching {
                client.get(
                    "$baseUrl/get_ddl/$encodedFilename",
                    headers.newBuilder()
                        .set("Accept", "application/json")
                        .set("Referer", downloadPageUrl)
                        .set("X-Ddl-Token", ddlToken)
                        .build(),
                ).parseAs<DdlResponse>()
            }.getOrNull()
        } else {
            null
        }

        val ddlWatch = ddl?.watchLink?.let { resolveRedirect(it) }
        val effectiveWatch = watchUrl ?: ddlWatch
        val streamUrl = ddl?.streamLink?.let { resolveRedirect(it) } ?: effectiveWatch
        val directDlUrl = ddl?.downloadLink?.let { resolveRedirect(it) } ?: downloadPageUrl
        val torrentUrl = ddl?.torrentLink?.let { resolveRedirect(it) }

        return when {
            hosterName.equals("Dash", ignoreCase = true) -> {
                if (effectiveWatch != null && effectiveWatch.contains("/watch/")) {
                    val mpdUrl = effectiveWatch.replace("/watch/", "/dash/") + "/manifest.mpd"
                    Video(videoUrl = mpdUrl, videoTitle = videoTitle)
                } else {
                    Video(videoUrl = directDlUrl, videoTitle = videoTitle)
                }
            }
            hosterName.equals("Stream", ignoreCase = true) -> {
                val url = effectiveWatch ?: streamUrl ?: directDlUrl
                Video(videoUrl = url, videoTitle = videoTitle)
            }
            hosterName.equals("Direct DL", ignoreCase = true) -> {
                Video(videoUrl = directDlUrl, videoTitle = videoTitle)
            }
            hosterName.equals("Torrent", ignoreCase = true) -> {
                if (!torrentUrl.isNullOrBlank()) {
                    Video(videoUrl = torrentUrl, videoTitle = videoTitle)
                } else {
                    null
                }
            }
            else -> Video(videoUrl = directDlUrl, videoTitle = videoTitle)
        }
    }

    private suspend fun resolveRedirect(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val url = if (path.startsWith("/")) "$baseUrl$path" else path
        return runCatching {
            client.get(url).use { resp ->
                resp.request.url.toString()
            }
        }.getOrNull()
    }

    private fun fallbackDirectUrl(episodeUrl: String, filename: String): List<Video> {
        val fullUrl = baseUrl + episodeUrl
        val resLabel = RES_LABEL_REGEX.find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = AUDIO_TAG_REGEX.find(filename)?.groupValues?.get(1) ?: ""
        val label = "AV1 · $resLabel${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        return listOf(Video(videoUrl = fullUrl, videoTitle = label))
    }

    // =========================== Extraction Helpers ========================

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        FILENAME_REGEX.findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = EPISODE_NAME_REGEX.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = SUBDUB_REGEX.find(filename)?.groupValues?.get(1) ?: ""
            "Season $season Ep $e - $titlePart${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        } else {
            val cleanName = filename.replace(QUALITY_REGEX, "").substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private fun parseEpisodeNumber(filename: String): Float = EPISODE_S_NUMBER_REGEX.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    private fun extractImgUrl(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }.ifBlank { img.attr("abs:src") }
        return src.takeIf { it.isNotBlank() }
    }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = BACKGROUND_URL_REGEX.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    private fun resLabelFromResolution(res: String): String = when {
        res.contains("1080") -> "1080p"
        res.contains("720") -> "720p"
        res.contains("480") -> "480p"
        res.contains("360") -> "360p"
        else -> res
    }

    private fun qualityCandidates(pref: String): List<String> {
        val normalized = when {
            pref.contains("1080") -> "1920 x 1080"
            pref.contains("720") -> "1280 x 720"
            pref.contains("480") -> "854 x 480"
            pref.contains("360") -> "640 x 360"
            else -> pref
        }
        val list = mutableListOf(normalized)
        listOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360").forEach {
            if (it !in list) list.add(it)
        }
        return list.map { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun normalizePath(href: String): String {
        val value = href.trim()
        if (value.startsWith("/")) return value
        if (!value.startsWith("http", ignoreCase = true)) return value

        return runCatching {
            val url = value.toHttpUrl()
            val host = url.host
            val allowed = host == baseUrl.toHttpUrl().host ||
                host.endsWith("av1encodes.com") ||
                host.endsWith("animealpha.cc") ||
                host.endsWith("av1please.com")
            if (allowed) {
                buildString {
                    append(url.encodedPath)
                    url.encodedQuery?.let { append('?').append(it) }
                }
            } else {
                ""
            }
        }.getOrDefault("")
    }

    // =============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen)
    }

    // ============================== Constants ==============================

    companion object {
        private const val TAG = "AV1Encodes"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val RES_LABEL_REGEX = Regex("""\[(\d+p)]""")
        private val AUDIO_TAG_REGEX = Regex("""\[(Dual|Sub|Dub|Tri|Multi)]""", RegexOption.IGNORE_CASE)
        private val EPISODE_NUMBER_REGEX = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        private val EPISODE_S_NUMBER_REGEX = Regex("""\[(?:S\d+-)?E(\d+)]""")
        private val EPISODE_NAME_REGEX = Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""")
        private val SUBDUB_REGEX = Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""\[\d{3,4}p].*""")
        private val FILENAME_REGEX = Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE)
        private val TOKEN_REGEX = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""")
        private val BACKGROUND_URL_REGEX = Regex("""url\(['"](.*?)['"]\)""")
    }
}
