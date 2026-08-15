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

    private val dateFormat by lazy {
        SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
    }

    private val bgUrlRegex = """url\(['"]?(.*?)['"]?\)""".toRegex()
    private val episodeRegex = Regex("""(?:Ep|Episode)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/hentai/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    // ============================== Latest Updates =============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

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

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimePage(response.asJsoup())

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =============================== Anime Details =============================

    override fun animeDetailsParse(response: Response): SAnime = parseAnimeDetails(response.asJsoup())

    private fun parseAnimeDetails(doc: Document): SAnime = SAnime.create().apply {
        title = doc.selectFirst(".nk-series-synopsis > b")?.text()
            ?: doc.selectFirst(".nk-post-header h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"

        val posterStyle = doc.selectFirst(".nk-series-poster")?.attr("style") ?: ""
        thumbnail_url = extractBgUrl(posterStyle)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".nk-featured-img img")?.attr("abs:src")

        description = doc.selectFirst(".nk-series-synopsis p")?.text()
            ?: doc.select(".nk-post-body p.separator").joinToString("\n\n") { it.text() }
                .takeIf(String::isNotBlank)

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
    }

    private fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase()) {
        "completed" -> SAnime.COMPLETED
        "ongoing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episode List ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodeCards = doc.select(".nk-episode-grid ul li a.nk-episode-card, .nk-episode-grid a.nk-episode-card")
        if (episodeCards.isNotEmpty()) {
            return episodeCards.mapIndexed { index, card ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(card.attr("abs:href").ifEmpty { card.attr("href") })
                    name = card.selectFirst(".nk-episode-card-title")?.text()
                        ?: "Episode ${index + 1}"
                    val badgeText = card.selectFirst(".nk-episode-badge")?.text() ?: ""
                    episode_number = parseEpisodeNumber(badgeText, name, (index + 1).toFloat())
                    val dateElement = card.selectFirst(".nk-episode-card-date")
                    val dateText = dateElement?.text()?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
                    date_upload = dateFormat.tryParse(dateText)
                }
            }
        }

        // Fallback: If opened directly on an episode page, fetch the series page if available
        val seriesLink = doc.selectFirst("a.nk-player-series")?.attr("abs:href")
        if (!seriesLink.isNullOrBlank()) {
            return runCatching {
                val seriesDoc = client.newCall(GET(seriesLink, headers)).execute().asJsoup()
                val seriesCards = seriesDoc.select(".nk-episode-grid ul li a.nk-episode-card, .nk-episode-grid a.nk-episode-card")
                if (seriesCards.isNotEmpty()) {
                    seriesCards.mapIndexed { index, card ->
                        SEpisode.create().apply {
                            setUrlWithoutDomain(card.attr("abs:href").ifEmpty { card.attr("href") })
                            name = card.selectFirst(".nk-episode-card-title")?.text()
                                ?: "Episode ${index + 1}"
                            val badgeText = card.selectFirst(".nk-episode-badge")?.text() ?: ""
                            episode_number = parseEpisodeNumber(badgeText, name, (index + 1).toFloat())
                            val dateElement = card.selectFirst(".nk-episode-card-date")
                            val dateText = dateElement?.text()?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
                            date_upload = dateFormat.tryParse(dateText)
                        }
                    }
                } else {
                    listOf(createSingleEpisode(doc, response.request.url.toString()))
                }
            }.getOrDefault(listOf(createSingleEpisode(doc, response.request.url.toString())))
        }

        return listOf(createSingleEpisode(doc, response.request.url.toString()))
    }

    private fun createSingleEpisode(doc: Document, currentUrl: String): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(currentUrl)
        name = doc.selectFirst(".nk-post-header h1")?.text() ?: "Episode 1"
        episode_number = parseEpisodeNumber("", name, 1F)
        val dateText = doc.selectFirst(".nk-post-header-meta")?.text()
            ?.replace(Regex("[^0-9a-zA-Z ]"), " ")?.trim()
        date_upload = dateFormat.tryParse(dateText)
    }

    private fun parseEpisodeNumber(badge: String, title: String, fallback: Float): Float {
        val badgeNum = episodeRegex.find(badge)?.groupValues?.get(1)?.toFloatOrNull()
        if (badgeNum != null) return badgeNum

        val titleNum = episodeRegex.find(title)?.groupValues?.get(1)?.toFloatOrNull()
        if (titleNum != null) return titleNum

        return fallback
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

                val id = url.substringAfterLast("/")
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

    // ============================= Common Helpers ==============================

    private fun parseAnimePage(doc: Document): AnimesPage {
        val items = doc.select("div.nk-search-results ul li a.nk-search-item, div.nk-episode-grid ul li a.nk-episode-card, a.nk-search-item, a.nk-episode-card")
            .mapNotNull { element ->
                val href = element.attr("abs:href").ifEmpty { element.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = element.selectFirst("h2, .nk-episode-card-title")?.text()
                        ?: element.attr("title").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null

                    val style = element.selectFirst(".nk-search-thumb, .nk-episode-card-thumb")?.attr("style") ?: ""
                    thumbnail_url = extractBgUrl(style)
                        ?: element.selectFirst("img")?.attr("abs:src")
                }
            }

        val hasNextPage = doc.selectFirst("nav.pagination .nav-links a.next.page-numbers, .pagination a.next, .page-numbers.next") != null
        return AnimesPage(items, hasNextPage)
    }

    private fun extractBgUrl(style: String): String? {
        val match = bgUrlRegex.find(style)
        return match?.groupValues?.get(1)?.trim('\'', '"')
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
