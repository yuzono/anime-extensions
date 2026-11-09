package eu.kanade.tachiyomi.animeextension.all.nyaatorrent

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.CustomUrlPreferences
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class NyaaTorrent :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    private val extId: Int
        get() = if (name == "Nyaa Sukebein (Torrent)") 2 else 1

    // Not lazy, so a domain saved by the old setting moves before the generated baseUrl first reads the preferences
    private val preferences = getPreferences {
        CustomUrlPreferences.migrate(this, "domain", if (extId == 2) "https://sukebei.nyaa.si" else "https://nyaa.si")
    }

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Shared ===============================
    private val animeSelector = "table.torrent-list tbody tr"
    private val animeNextPageSelector = "ul.pagination a[rel='next']"

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("td:nth-child(2) a:not(.comments)") ?: element.selectFirst("td:nth-child(2) a")
        anime.setUrlWithoutDomain(link?.attr("href").orEmpty())
        anime.title = link?.attr("title")?.ifBlank { link.text() }.orEmpty()
        return anime
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page&s=seeders&o=desc", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(animeSelector).map { animeFromElement(it) }
        val hasNext = document.selectFirst(animeNextPageSelector) != null
        return AnimesPage(animeList, hasNext)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val categoryParam = if (extId == 1) "1_0" else "1_1"
        return GET("$baseUrl/?f=0&c=$categoryParam&p=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val response = try {
                client.get("$baseUrl/view/$id", headers)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return AnimesPage(emptyList(), false)
            }
            return searchAnimeByIdParse(response, id)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        if (!response.isSuccessful) return AnimesPage(emptyList(), false)
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain("/view/$id")
        }
        if (details.title.isBlank()) return AnimesPage(emptyList(), false)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        var sortParam = "id"
        var sortDirection = "desc"
        var filterParam = "0"
        var categoryParam = if (extId == 1) "1_0" else "1_1"
        filters.forEach { filter ->
            when (filter) {
                is SortList -> {
                    sortParam = availableSorts[filter.state?.index ?: 0].id
                    sortDirection = if (filter.state?.ascending == true) "asc" else "desc"
                }

                is FilterList -> filterParam = availableFilters[filter.state].id

                is CategoriesList -> categoryParam = availableCategories[filter.state].id

                else -> {}
            }
        }
        return GET("$baseUrl/?f=$filterParam&c=$categoryParam&s=$sortParam&o=$sortDirection&q=$encodedQuery&p=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.startsWith("/view/")) {
            val anime = animeDetailsParse(response).apply {
                setUrlWithoutDomain(response.request.url.encodedPath)
            }
            if (anime.title.isBlank()) return AnimesPage(emptyList(), false)
            return AnimesPage(listOf(anime), false)
        }
        return popularAnimeParse(response)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        anime.title = document.selectFirst("h3.panel-title")?.text()?.trim().orEmpty()
        val category = document.select("div.panel-body > div:nth-child(1) > div:nth-child(2)").text()
        val seeders = document.select("div.panel-body > div:nth-child(2) > div:nth-child(4)").text()
        val leechers = document.select("div.panel-body > div:nth-child(3) > div:nth-child(4) > span").text()
        val filesize = document.select("div.panel-body > div:nth-child(4) > div:nth-child(2)").text()
        val genre = buildList {
            if (category.isNotEmpty()) add("Category: $category")
            if (seeders.isNotEmpty()) add("Seeders: $seeders")
            if (leechers.isNotEmpty()) add("Leechers: $leechers")
            if (filesize.isNotEmpty()) add("File Size: $filesize")
        }.joinToString(", ")
        anime.genre = genre
        val desc = document.select("#torrent-description").text()
        anime.description = desc
        anime.author = document.select("a[title=user]").text()
        val imageRegex = Regex("""\b(http|https)?:\S+(?:jpg|png|gif|bmp|webp|tiff|jpeg)(?!\.html)\b""", RegexOption.IGNORE_CASE)
        val match = imageRegex.find(desc)

        if (match != null) {
            anime.thumbnail_url = match.value
        }
        return anime
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun getEpisodeUrl(episode: SEpisode): String = if (episode.url.startsWith("magnet:")) episode.url else baseUrl + episode.url

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val torrentFile = document.selectFirst("div.panel-footer a")?.absUrl("href").orEmpty()
        val torrentDate = parseDate(document.select("div.panel-body > div:nth-child(1) > div:nth-child(4)").text())
        try {
            val torrent = TorrentUtils.getTorrentInfo(torrentFile, "torrent")
            val torrentIndexed = torrent.files
            val torrentTracker = torrent.trackers.filter { it.trim().isNotEmpty() }.joinToString("") { "&tr=$it" }
            val torrentMagnet = "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}"

            var episodeNumber = 1F
            return torrentIndexed
                .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in validExtensions }
                .map {
                    SEpisode.create().apply {
                        name = if (preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                            it.path.trim().split('/').last()
                        } else {
                            it.path.trim()
                                .replace("[", "(")
                                .replace(Regex("]"), ")")
                                .replace("/", "\uD83D\uDCC2 ")
                        }
                        url = "$torrentMagnet$torrentTracker&index=${it.indexFile}"
                        episode_number = episodeNumber++
                        scanlator = convertBytesToReadable(it.size)
                        date_upload = torrentDate
                    }
                }.reversed()
                .toMutableList()
        } catch (_: SocketTimeoutException) {
            throw Exception("Dead Torrent \uD83D\uDE35")
        }
    }

    private val validExtensions = setOf("mp4", "mov", "avi", "wmv", "mkv", "flv", "webm", "ogg", "mpeg", "mpg", "mts", "vob", "ts")

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    private fun convertBytesToReadable(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0

        return when {
            gigabytes >= 1 -> String.format(Locale.ROOT, "%.2f GB", gigabytes)
            megabytes >= 1 -> String.format(Locale.ROOT, "%.2f MB", megabytes)
            else -> String.format(Locale.ROOT, "%.2f KB", kilobytes)
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "Torrent",
            videoList = listOf(
                Video(
                    videoUrl = episode.url,
                    videoTitle = episode.name,
                ),
            ),
        ),
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will note display full path of episode."
        }.also(screen::addPreference)
    }

    // ============================== Filters ==============================
    private data class Sort(val name: String, val id: String)
    private class SortList(availableSorts: Array<String>) : AnimeFilter.Sort("Sort", availableSorts, Selection(0, false))
    private val availableSorts = arrayOf(
        Sort("Date", "id"),
        Sort("Seeders", "seeders"),
        Sort("Leechers", "leechers"),
        Sort("Downloads", "downloads"),
    )

    private data class Filter(val name: String, val id: String) {
        override fun toString() = name
    }
    private class FilterList(availableFilters: Array<String>) : AnimeFilter.Select<String>("Filter", availableFilters)
    private val availableFilters = arrayOf(
        Filter("No filter", "0"),
        Filter("No remakes", "1"),
        Filter("Trusted only", "2"),
    )

    private data class Category(val name: String, val id: String) {
        override fun toString() = name
    }
    private class CategoriesList(availableCategories: Array<String>) : AnimeFilter.Select<String>("Category", availableCategories)
    private val availableCategories = if (extId == 1) {
        listOf(
            Category("Anime", "1_0"),
            Category("- Anime Music Video", "1_1"),
            Category("- English-translated", "1_2"),
            Category("- Non-English-translated", "1_3"),
            Category("- Raw", "1_4"),
            Category("Live Action", "4_0"),
            Category("- English-translated", "4_1"),
            Category("- Idol/Promotional Video", "4_2"),
            Category("- Non-English-translated", "4_3"),
            Category("- Raw", "4_4"),
        )
    } else {
        listOf(
            Category("Anime", "1_1"),
            Category("Real Life", "2_2"),
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortList(availableSorts.map { it.name }.toTypedArray()),
        FilterList(availableFilters.map { it.name }.toTypedArray()),
        CategoriesList(availableCategories.map { it.name }.toTypedArray()),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        }
    }
}
