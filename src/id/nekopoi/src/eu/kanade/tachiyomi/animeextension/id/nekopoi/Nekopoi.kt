package eu.kanade.tachiyomi.animeextension.id.nekopoi

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

class Nekopoi :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Nekopoi"

    override val baseUrl = "https://nekopoi.care"

    override val lang = "id"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai-list/?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return NekopoiParser.parsePopularHentaiList(doc, page).toAnimesPage()
    }

    // ============================== Latest Updates =============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = NekopoiParser.parseAnimePage(response.asJsoup()).toAnimesPage()
    // ================================== Search =================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> {
                if (page == 1) {
                    val url = baseUrl.toHttpUrl().newBuilder().apply {
                        addQueryParameter("s", query)
                        addQueryParameter("post_type", "anime")
                    }.build()
                    GET(url, headers)
                } else {
                    val url = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("search")
                        addPathSegment(query)
                        addPathSegment("page")
                        addPathSegment(page.toString())
                        addPathSegment("")
                    }.build()
                    GET(url, headers)
                }
            }
            params.category.isNotBlank() && params.category != "hentai" -> {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("category")
                    addPathSegment(params.category)
                    if (page > 1) {
                        addPathSegment("page")
                        addPathSegment(page.toString())
                    }
                    addPathSegment("")
                }.build()
                GET(url, headers)
            }
            params.genre.isNotBlank() -> {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("genres")
                    addPathSegment(params.genre)
                    if (page > 1) {
                        addPathSegment("page")
                        addPathSegment(page.toString())
                    }
                    addPathSegment("")
                }.build()
                GET(url, headers)
            }
            params.category.isNotBlank() -> {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("category")
                    addPathSegment(params.category)
                    if (page > 1) {
                        addPathSegment("page")
                        addPathSegment(page.toString())
                    }
                    addPathSegment("")
                }.build()
                GET(url, headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = NekopoiParser.parseAnimePage(response.asJsoup()).toAnimesPage()

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =============================== Anime Details =============================

    override fun animeDetailsParse(response: Response): SAnime = NekopoiParser.parseAnimeDetails(response.asJsoup()).toSAnime()

    // ============================== Episode List ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val currentUrl = response.request.url.toString()
        val episodes = NekopoiParser.parseEpisodeList(doc, currentUrl)
        return episodes.map(ParsedEpisode::toSEpisode)
    }

    // ============================= Video Links =================================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val iframes = doc.select("#nk-player .nk-player-frame iframe, #nk-player iframe, div.nk-player-frame iframe, iframe")
            .mapNotNull {
                val src = it.attr("abs:src").ifEmpty { it.attr("src") }
                src.takeIf(String::isNotBlank)
            }
            .distinct()

        val videos = iframes.parallelCatchingFlatMapBlocking { iframeUrl ->
            val fullUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                else -> iframeUrl
            }
            extractVideosFromUrl(fullUrl)
        }

        return NekopoiParser.deduplicateVideos(videos)
    }
    private suspend fun extractVideosFromUrl(url: String): List<Video> {
        return when {
            "playmogo" in url || "dood" in url || "d000d" in url || "ds2play" in url -> {
                val directVideos = doodExtractor.videosFromUrl(url)
                if (directVideos.isNotEmpty()) return directVideos

                val id = url
                    .substringAfterLast("/")
                    .substringBefore("?")
                    .substringBefore("#")
                val mirrorUrl = "https://d000d.com/e/$id"
                doodExtractor.videosFromUrl(mirrorUrl)
            }
            "streamwish" in url || "wishembed" in url || "awish" in url || "streampoi" in url -> {
                streamWishExtractor.videosFromUrl(url)
            }
            "vidhide" in url || "vidhided" in url || "embedwish" in url -> {
                vidHideExtractor.videosFromUrl(url)
            }
            url.endsWith(".mp4") || url.endsWith(".m3u8") -> {
                listOf(Video(url, "Direct", url, headers = headers))
            }
            else -> emptyList()
        }
    }

    // ============================= Preferences =================================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality, ignoreCase = true) })
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")
    }
}

internal data class ParsedAnime(
    var url: String = "",
    var title: String = "",
    var thumbnailUrl: String? = null,
    var description: String? = null,
    var genre: String? = null,
    var status: Int = SAnime.UNKNOWN,
    var author: String? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = this@ParsedAnime.url
        title = this@ParsedAnime.title
        thumbnail_url = this@ParsedAnime.thumbnailUrl
        description = this@ParsedAnime.description
        genre = this@ParsedAnime.genre
        status = this@ParsedAnime.status
        author = this@ParsedAnime.author
    }
}

internal data class ParsedAnimePage(
    val animes: List<ParsedAnime>,
    val hasNextPage: Boolean,
) {
    fun toAnimesPage(): AnimesPage = AnimesPage(animes.map(ParsedAnime::toSAnime), hasNextPage)
}

internal data class ParsedEpisode(
    var url: String = "",
    var name: String = "",
    var episodeNumber: Float = 0F,
    var dateUpload: Long = 0L,
) {
    fun toSEpisode(): SEpisode = SEpisode.create().apply {
        url = this@ParsedEpisode.url
        name = this@ParsedEpisode.name
        episode_number = this@ParsedEpisode.episodeNumber
        date_upload = this@ParsedEpisode.dateUpload
    }
}

internal object NekopoiParser {

    private val bgUrlRegex = """url\(['"]?(.*?)['"]?\)""".toRegex()
    private val episodeRegex = Regex("""(?:Ep|Episode)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val titleCleanupRegex = Regex("""(?i)\[NEW\s+RELEASE\]\s*|\bSUB\s*[-_]?\s*INDO(?:NESIA)?\b|\bSubtitle\s+Indonesia\b""")
    private val emptyBracketsRegex = Regex("""\[\s*\]|\(\s*\)""")
    private val tooltipImgRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val tooltipScoreRegex = Regex("""Skor\s*(?:</b>)?\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val dateFormat by lazy {
        SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
    }

    fun cleanTitle(title: String): String {
        var t = titleCleanupRegex.replace(title, "")
        t = emptyBracketsRegex.replace(t, "")
        t = t.replace(Regex("""\[\s+"""), "[").replace(Regex("""\s+\]"""), "]")
        t = t.replace(Regex("""\(\s+"""), "(").replace(Regex("""\s+\)"""), ")")
        t = t.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', '—')
        return t
    }

    fun isValidEntry(title: String, url: String): Boolean {
        val lowerTitle = title.lowercase()
        if (lowerTitle.contains("anniversary") ||
            lowerTitle.contains("selamat tahun baru") ||
            lowerTitle.contains("ulang tahun") ||
            lowerTitle.contains("nekopoi mengucapkan") ||
            lowerTitle.contains("[batch]") ||
            lowerTitle.contains(" batch")
        ) {
            return false
        }
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("selamat-tahun-baru") ||
            lowerUrl.contains("anniversary") ||
            lowerUrl.contains("ulang-tahun")
        ) {
            return false
        }
        return true
    }

    fun parseAnimePage(doc: Document): ParsedAnimePage {
        val items = doc.select(".nk-post-card, div.nk-search-results ul li a.nk-search-item, a.nk-search-item, div.nk-episode-grid ul li a.nk-episode-card, a.nk-episode-card")
            .mapNotNull { element ->
                val linkElement = when {
                    element.tagName() == "a" -> element
                    else -> element.selectFirst("h2 a, .nk-post-meta h2 a, a") ?: return@mapNotNull null
                }
                val href = linkElement.attr("abs:href").ifEmpty { linkElement.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val rawTitle = element.selectFirst("h2, .nk-episode-card-title")?.text()
                    ?: linkElement.text().takeIf(String::isNotBlank)
                    ?: element.attr("title").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                if (!isValidEntry(rawTitle, href)) return@mapNotNull null

                val thumbnail = extractThumbnail(element)

                ParsedAnime(
                    url = cleanUrlWithoutDomain(href),
                    title = cleanTitle(rawTitle),
                    thumbnailUrl = thumbnail,
                )
            }

        val hasNextPage = doc.selectFirst("nav.pagination .nav-links a.next.page-numbers, .pagination a.next, .page-numbers.next, a.next.page-numbers") != null
        return ParsedAnimePage(items, hasNextPage)
    }
    fun parsePopularHentaiList(doc: Document, page: Int, pageSize: Int = 20): ParsedAnimePage {
        val items = doc.select(".nk-az-item a, a.nk-series-link")
            .mapNotNull { element ->
                val href = element.attr("abs:href").ifEmpty { element.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val rawTitle = element.text().takeIf(String::isNotBlank)
                    ?: element.attr("title").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                if (!isValidEntry(rawTitle, href)) return@mapNotNull null

                val rawTooltip = element.attr("original-title")
                val thumbnail = tooltipImgRegex.find(rawTooltip)?.groupValues?.get(1)
                    ?: extractThumbnail(element)

                val score = tooltipScoreRegex.find(rawTooltip)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.0f

                ScoredAnime(
                    anime = ParsedAnime(
                        url = cleanUrlWithoutDomain(href),
                        title = cleanTitle(rawTitle),
                        thumbnailUrl = thumbnail,
                    ),
                    score = score,
                )
            }
            .sortedByDescending { it.score }
            .map { it.anime }

        val startIndex = (page - 1) * pageSize
        if (startIndex >= items.size) {
            return ParsedAnimePage(emptyList(), false)
        }

        val endIndex = minOf(startIndex + pageSize, items.size)
        val pagedItems = items.subList(startIndex, endIndex)
        val hasNextPage = endIndex < items.size

        return ParsedAnimePage(pagedItems, hasNextPage)
    }

    private data class ScoredAnime(
        val anime: ParsedAnime,
        val score: Float,
    )

    fun parseAnimeDetails(doc: Document): ParsedAnime {
        val rawTitle = doc.selectFirst(".nk-series-synopsis > b")?.text()
            ?: doc.selectFirst(".nk-post-header h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"
        val title = cleanTitle(rawTitle)
        val posterStyle = doc.selectFirst(".nk-series-poster")?.attr("style").orEmpty()
        val thumbnail = extractBgUrl(posterStyle)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".nk-featured-img img, .featured-image img, .nk-post-thumb img")?.let { it.attr("abs:src").ifEmpty { it.attr("src") } }
        val synopsisParts = mutableListOf<String>()
        val genres = mutableListOf<String>()
        var author: String? = null
        var status = SAnime.UNKNOWN

        doc.selectFirst(".nk-series-synopsis p")?.text()?.takeIf(String::isNotBlank)?.let {
            synopsisParts.add(it)
        }

        val metaElements = doc.select(".nk-series-meta-list li")
        for (meta in metaElements) {
            val text = meta.text()
            when {
                text.contains("Status", ignoreCase = true) -> {
                    status = parseStatus(text.substringAfter(":"))
                }
                text.contains("Produser", ignoreCase = true) || text.contains("Producers", ignoreCase = true) -> {
                    author = text.substringAfter(":").trim()
                }
                text.contains("Genre", ignoreCase = true) -> {
                    val metaGenres = meta.select("a").mapNotNull { it.text().takeIf(String::isNotBlank) }
                    if (metaGenres.isNotEmpty()) {
                        genres.addAll(metaGenres)
                    } else {
                        val gText = text.substringAfter(":").trim()
                        if (gText.isNotBlank()) {
                            genres.addAll(gText.split(",").map(String::trim).filter(String::isNotBlank))
                        }
                    }
                }
            }
        }

        val kontenPs = doc.select(".konten p, .nk-post-body p, .entry-content p")
        for (p in kontenPs) {
            val text = p.text().trim()
            if (text.isEmpty() || text.equals("Sinopsis", ignoreCase = true)) continue
            val lower = text.lowercase()
            when {
                lower.startsWith("genre") -> {
                    val gText = text.substringAfter(":").trim()
                    if (gText.isNotBlank()) {
                        genres.addAll(gText.split(",").map(String::trim).filter(String::isNotBlank))
                    }
                }
                lower.startsWith("producers") || lower.startsWith("produser") -> {
                    if (author.isNullOrBlank()) {
                        author = text.substringAfter(":").trim()
                    }
                }
                lower.startsWith("actress") -> {
                    if (author.isNullOrBlank()) {
                        author = text.substringAfter(":").trim()
                    }
                }
                lower.startsWith("status") -> {
                    if (status == SAnime.UNKNOWN) {
                        status = parseStatus(text.substringAfter(":"))
                    }
                }
                !lower.startsWith("size") && !lower.startsWith("duration") && !lower.startsWith("durasi") -> {
                    synopsisParts.add(text)
                }
            }
        }

        val description = synopsisParts.distinct().joinToString("\n\n").takeIf(String::isNotBlank)
        val genre = genres.distinct().joinToString().takeIf(String::isNotBlank)

        return ParsedAnime(
            title = title,
            thumbnailUrl = thumbnail,
            description = description,
            genre = genre,
            status = status,
            author = author,
        )
    }

    fun parseEpisodeList(doc: Document, currentUrl: String = ""): List<ParsedEpisode> {
        val episodeCards = doc.select(".nk-episode-grid ul li a.nk-episode-card, .nk-episode-grid a.nk-episode-card")
        if (episodeCards.isNotEmpty()) {
            return episodeCards.mapIndexed { index, card ->
                val href = card.attr("abs:href").ifEmpty { card.attr("href") }
                val rawTitle = card.selectFirst(".nk-episode-card-title")?.text() ?: ""
                val badgeText = card.selectFirst(".nk-episode-badge")?.text() ?: ""
                val epNum = parseEpisodeNumber(badgeText, rawTitle, (index + 1).toFloat())
                val epStr = if (epNum % 1.0f == 0.0f) epNum.toInt().toString() else epNum.toString()
                val name = "Episode $epStr"

                val dateElement = card.selectFirst(".nk-episode-card-date")
                val dateText = dateElement?.text()?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
                val dateUpload = dateFormat.tryParse(dateText)

                ParsedEpisode(
                    url = cleanUrlWithoutDomain(href),
                    name = name,
                    episodeNumber = (index + 1).toFloat(),
                    dateUpload = dateUpload,
                )
            }
        }

        return listOf(createSingleEpisode(doc, currentUrl))
    }

    fun createSingleEpisode(doc: Document, currentUrl: String): ParsedEpisode {
        val rawTitle = doc.selectFirst(".nk-post-header h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: ""
        val epNum = parseEpisodeNumber("", rawTitle, 0F)
        val name = if (epNum > 0F) {
            val epStr = if (epNum % 1.0f == 0.0f) epNum.toInt().toString() else epNum.toString()
            "Episode $epStr"
        } else {
            "Episode 1"
        }

        val dateText = doc.selectFirst(".nk-post-header-meta")?.text()
            ?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
        val dateUpload = dateFormat.tryParse(dateText)

        return ParsedEpisode(
            url = cleanUrlWithoutDomain(currentUrl),
            name = name,
            episodeNumber = 1F,
            dateUpload = dateUpload,
        )
    }

    fun deduplicateVideos(videos: List<Video>): List<Video> {
        val result = mutableListOf<Video>()
        val seenUrls = mutableSetOf<String>()
        val qualityCounts = mutableMapOf<String, Int>()

        for (video in videos) {
            if (!seenUrls.add(video.url)) continue
            val baseQuality = video.quality
            val count = (qualityCounts[baseQuality] ?: 0) + 1
            qualityCounts[baseQuality] = count
            val quality = if (count > 1) "$baseQuality ($count)" else baseQuality
            result.add(Video(video.url, quality, video.videoUrl, video.headers, video.subtitleTracks, video.audioTracks))
        }
        return result
    }

    fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase()) {
        "completed",
        "complete",
        "finished",
        "finish",
        "ended",
        "end",
        "tamat",
        -> SAnime.COMPLETED
        "ongoing",
        "on going",
        "on-going",
        "berjalan",
        "sedang berjalan",
        -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    fun parseEpisodeNumber(badge: String, title: String, fallback: Float): Float {
        val badgeNum = episodeRegex.find(badge)?.groupValues?.get(1)?.toFloatOrNull()
        if (badgeNum != null) return badgeNum

        val titleNum = episodeRegex.find(title)?.groupValues?.get(1)?.toFloatOrNull()
        if (titleNum != null) return titleNum

        return fallback
    }

    fun extractBgUrl(style: String): String? {
        val match = bgUrlRegex.find(style)
        return match?.groupValues?.get(1)?.trim('\'', '"')
    }
    fun extractThumbnail(element: org.jsoup.nodes.Element): String? {
        val styleEl = element.selectFirst("[style*='url('], [style*='url (']")
        if (styleEl != null) {
            val bg = extractBgUrl(styleEl.attr("style"))
            if (!bg.isNullOrBlank()) return bg
        }

        val thumbEl = element.selectFirst(".nk-thumb-crop, .nk-search-thumb, .nk-episode-card-thumb, .nk-series-poster")
        if (thumbEl != null) {
            val bg = extractBgUrl(thumbEl.attr("style"))
            if (!bg.isNullOrBlank()) return bg
        }

        val img = element.selectFirst("img")
        if (img != null) {
            val src = img.attr("abs:data-src").ifEmpty {
                img.attr("data-src").ifEmpty {
                    img.attr("abs:src").ifEmpty { img.attr("src") }
                }
            }
            if (src.isNotBlank()) return src
        }

        return null
    }

    private fun cleanUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig)
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        path + query + fragment
    } catch (_: Throwable) {
        orig
    }
}
