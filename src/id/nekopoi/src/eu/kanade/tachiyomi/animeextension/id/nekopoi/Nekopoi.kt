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

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/hentai/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = NekopoiParser.parseAnimePage(response.asJsoup()).toAnimesPage()

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
        val episodes = NekopoiParser.parseEpisodeList(doc)
        if (episodes.isNotEmpty()) {
            return episodes.map(ParsedEpisode::toSEpisode)
        }

        // Fallback: If opened directly on an episode page, fetch the series page if available
        val seriesLink = doc.selectFirst("a.nk-player-series")?.attr("abs:href")
        if (!seriesLink.isNullOrBlank()) {
            return runCatching {
                val seriesDoc = client.newCall(GET(seriesLink, headers)).execute().asJsoup()
                val seriesEpisodes = NekopoiParser.parseEpisodeList(seriesDoc)
                if (seriesEpisodes.isNotEmpty()) {
                    seriesEpisodes.map(ParsedEpisode::toSEpisode)
                } else {
                    listOf(NekopoiParser.createSingleEpisode(doc, response.request.url.toString()).toSEpisode())
                }
            }.getOrDefault(listOf(NekopoiParser.createSingleEpisode(doc, response.request.url.toString()).toSEpisode()))
        }

        return listOf(NekopoiParser.createSingleEpisode(doc, response.request.url.toString()).toSEpisode())
    }

    // ============================= Video Links =================================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val iframes = doc.select("#nk-player .nk-player-frame iframe, #nk-player iframe, div.nk-player-frame iframe")
            .mapNotNull {
                val src = it.attr("abs:src").ifEmpty { it.attr("src") }
                src.takeIf(String::isNotBlank)
            }
            .distinct()

        return iframes.parallelCatchingFlatMapBlocking { iframeUrl ->
            val fullUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                else -> iframeUrl
            }
            extractVideosFromUrl(fullUrl)
        }
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

    private val dateFormat by lazy {
        SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
    }

    fun parseAnimePage(doc: Document): ParsedAnimePage {
        val items = doc.select("div.nk-search-results ul li a.nk-search-item, div.nk-episode-grid ul li a.nk-episode-card, a.nk-search-item, a.nk-episode-card")
            .mapNotNull { element ->
                val href = element.attr("abs:href").ifEmpty { element.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val title = element.selectFirst("h2, .nk-episode-card-title")?.text()
                    ?: element.attr("title").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                val style = element.selectFirst(".nk-search-thumb, .nk-episode-card-thumb")?.attr("style") ?: ""
                val thumbnail = extractBgUrl(style)
                    ?: element.selectFirst("img")?.attr("abs:src")

                ParsedAnime(
                    url = cleanUrlWithoutDomain(href),
                    title = title,
                    thumbnailUrl = thumbnail,
                )
            }

        val hasNextPage = doc.selectFirst("nav.pagination .nav-links a.next.page-numbers, .pagination a.next, .page-numbers.next") != null
        return ParsedAnimePage(items, hasNextPage)
    }

    fun parseAnimeDetails(doc: Document): ParsedAnime {
        val title = doc.selectFirst(".nk-series-synopsis > b")?.text()
            ?: doc.selectFirst(".nk-post-header h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"

        val posterStyle = doc.selectFirst(".nk-series-poster")?.attr("style") ?: ""
        val thumbnail = extractBgUrl(posterStyle)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".nk-featured-img img")?.attr("abs:src")

        val description = doc.selectFirst(".nk-series-synopsis p")?.text()
            ?: doc.select(".nk-post-body p.separator").joinToString("\n\n") { it.text() }
                .takeIf(String::isNotBlank)

        var status = SAnime.UNKNOWN
        var author: String? = null
        var genre: String? = null

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
                    val genres = meta.select("a").mapNotNull { it.text().takeIf(String::isNotBlank) }
                    genre = if (genres.isNotEmpty()) {
                        genres.joinToString()
                    } else {
                        text.substringAfter(":").trim()
                    }
                }
            }
        }

        if (genre.isNullOrBlank()) {
            val bodyGenres = doc.select(".konten p:has(b:contains(Genre))").text().substringAfter(":").trim()
            if (bodyGenres.isNotBlank()) {
                genre = bodyGenres
            }
        }

        return ParsedAnime(
            title = title,
            thumbnailUrl = thumbnail,
            description = description,
            genre = genre,
            status = status,
            author = author,
        )
    }

    fun parseEpisodeList(doc: Document): List<ParsedEpisode> {
        val episodeCards = doc.select(".nk-episode-grid ul li a.nk-episode-card, .nk-episode-grid a.nk-episode-card")
        return episodeCards.mapIndexed { index, card ->
            val href = card.attr("abs:href").ifEmpty { card.attr("href") }
            val name = card.selectFirst(".nk-episode-card-title")?.text()
                ?: "Episode ${index + 1}"
            val badgeText = card.selectFirst(".nk-episode-badge")?.text() ?: ""
            val episodeNumber = parseEpisodeNumber(badgeText, name, (index + 1).toFloat())
            val dateElement = card.selectFirst(".nk-episode-card-date")
            val dateText = dateElement?.text()?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
            val dateUpload = dateFormat.tryParse(dateText)

            ParsedEpisode(
                url = cleanUrlWithoutDomain(href),
                name = name,
                episodeNumber = episodeNumber,
                dateUpload = dateUpload,
            )
        }
    }

    fun createSingleEpisode(doc: Document, currentUrl: String): ParsedEpisode {
        val name = doc.selectFirst(".nk-post-header h1")?.text() ?: "Episode 1"
        val episodeNumber = parseEpisodeNumber("", name, 1F)
        val dateText = doc.selectFirst(".nk-post-header-meta")?.text()
            ?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
        val dateUpload = dateFormat.tryParse(dateText)

        return ParsedEpisode(
            url = cleanUrlWithoutDomain(currentUrl),
            name = name,
            episodeNumber = episodeNumber,
            dateUpload = dateUpload,
        )
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
