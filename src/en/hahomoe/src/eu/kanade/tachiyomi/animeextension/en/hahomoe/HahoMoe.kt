package eu.kanade.tachiyomi.animeextension.en.hahomoe

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class HahoMoe :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client by lazy {
        network.client.newBuilder()
            .addCookie({ baseUrl.toHttpUrl().host }, "loop-view" to "thumb")
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?s=vdy-d&page=$page", headers)

    override fun popularAnimeParse(response: Response) = parseAnimesPage(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?s=rel-d&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parseAnimesPage(response)

    // =============================== Search ===============================
    override fun getFilterList() = HahoMoeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val (includedTags, excludedTags, orderBy, ordering) = HahoMoeFilters.getSearchParameters(filters)

        val httpQuery = buildString {
            if (query.isNotBlank()) append(query.trim())
            if (includedTags.isNotEmpty()) {
                append(includedTags.joinToString(" genre:", prefix = " genre:"))
            }
            if (excludedTags.isNotEmpty()) {
                append(excludedTags.joinToString(" -genre:", prefix = " -genre:"))
            }
        }.let { URLEncoder.encode(it, "UTF-8") }

        return GET("$baseUrl/anime?page=$page&s=$orderBy$ordering&q=$httpQuery", headers)
    }

    override fun searchAnimeParse(response: Response) = parseAnimesPage(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()
        setUrlWithoutDomain(response.request.url.toString())
        thumbnail_url = document.selectFirst("img.cover-image.img-thumbnail")?.absUrl("src")
        title = document.selectFirst("li.breadcrumb-item.active")!!.text()
        genre = document.select("li.genre span.value, div.genre-tree ul > li > a").joinToString { it.text() }
        description = document.selectFirst("div.card-body")?.text()
        author = document.select("li.production span.value").joinToString { it.text() }
        artist = document.selectFirst("li.group span.value")?.text()
        status = parseStatus(document.selectFirst("li.status span.value")?.text())
    }

    private fun parseStatus(statusString: String?): Int = when (statusString) {
        "Ongoing" -> SAnime.ONGOING
        "Completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // =============================== Season ===============================
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var doc = client.get(baseUrl + anime.url, headers).asJsoup()
        return buildList {
            while (true) {
                doc.select("ul.episode-loop > li > a")
                    .map(::episodeFromElement)
                    .also(::addAll)

                val nextUrl = doc.selectFirst(EPISODE_NEXT_PAGE_SELECTOR)?.absUrl("href")
                if (nextUrl == null) break
                doc = client.get(nextUrl, headers).asJsoup()
            }

            sortByDescending { it.episode_number }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))

        val episodeNumberString = element.selectFirst("div.episode-number, div.episode-slug")?.text() ?: "Episode"
        episode_number = episodeNumberString.removePrefix("Episode ").toFloatOrNull() ?: 1F
        val title = element.selectFirst("div.episode-label, div.episode-title")?.text()
            ?.takeUnless { it.equals("No Title", true) }
            ?.let { ": $it" }
            .orEmpty()
        name = episodeNumberString + title
        date_upload = element.selectFirst("div.date")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get(baseUrl + episode.url, headers).asJsoup()
        val iframe = document.selectFirst("iframe")?.absUrl("src") ?: return emptyList()

        val iframeHeaders = headersBuilder().set("Referer", baseUrl + episode.url).build()
        val iframeResponse = client.get(iframe, iframeHeaders).asJsoup()

        val videos = iframeResponse.select("source").mapNotNull { element ->
            val videoUrl = element.absUrl("src").ifBlank { element.attr("src") }
            if (videoUrl.isBlank()) return@mapNotNull null
            val quality = element.attr("title").ifBlank { "Video" }
            Video(
                videoUrl = videoUrl,
                videoTitle = quality,
                headers = iframeHeaders,
            )
        }
        return videos.toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty().sortVideos()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("ul.anime-loop.loop > li > a").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.absUrl("href") + "?s=srt-d")
                title = element.selectFirst("div.label > span, div span.thumb-title")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li.page-item a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun String.toDate(): Long {
        val fixedDate = trim().replace(DATE_REGEX, "").replace("'", "")
        return DATE_FORMATTER.tryParse(fixedDate)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val EPISODE_NEXT_PAGE_SELECTOR = "ul.pagination li.page-item a[rel=next]"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd 'of' MMM, yyyy", Locale.ENGLISH)
        }

        private val DATE_REGEX by lazy { Regex("(?<=\\d)(st|nd|rd|th)") }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
