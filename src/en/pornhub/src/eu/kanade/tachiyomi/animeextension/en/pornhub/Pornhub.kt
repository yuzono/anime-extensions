package eu.kanade.tachiyomi.animeextension.en.pornhub

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Pornhub :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(permits = 5, period = 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", "accessAgeDisclaimerPH=1; hasVisited=1; age_verified=1; platform=pc")

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/video?o=ht&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("li.pcVideoListItem, li.videoBox, ul#videoSearchResult li")
        return AnimesPage(parseVideoCards(elements), hasNextPage(document))
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/video?o=mr&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("li.pcVideoListItem, li.videoBox, ul#videoSearchResult li")
        return AnimesPage(parseVideoCards(elements), hasNextPage(document))
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sort = ""
        var orientation = ""
        var quality = ""
        var duration = ""
        var production = ""
        var category = ""

        for (filter in filters) {
            when (filter) {
                is PornhubFilters.SortFilter -> sort = filter.selectedValue
                is PornhubFilters.OrientationFilter -> orientation = filter.selectedValue
                is PornhubFilters.QualityFilter -> quality = filter.selectedValue
                is PornhubFilters.DurationFilter -> duration = filter.selectedValue
                is PornhubFilters.ProductionFilter -> production = filter.selectedValue
                is PornhubFilters.CategoryFilter -> category = filter.selectedValue
                else -> Unit
            }
        }

        val trimmed = query.trim()
        val url = if (trimmed.isNotEmpty()) {
            val basePath = if (orientation == "gay") "$baseUrl/gay/video/search" else "$baseUrl/video/search"
            basePath.toHttpUrl().newBuilder().apply {
                addQueryParameter("search", trimmed)
                addQueryParameter("page", page.toString())
                if (sort.isNotBlank()) addQueryParameter("o", sort)
                if (quality.isNotBlank()) addQueryParameter("hd", "1")
                if (production.isNotBlank()) addQueryParameter("p", production)
            }.build()
        } else {
            val basePath = when (orientation) {
                "gay" -> "$baseUrl/gay/video"
                "transgender" -> "$baseUrl/transgender"
                else -> "$baseUrl/video"
            }
            basePath.toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                if (category.isNotBlank()) addQueryParameter("c", category)
                if (sort.isNotBlank()) addQueryParameter("o", sort)
                if (quality.isNotBlank()) addQueryParameter("hd", "1")
                if (production.isNotBlank()) addQueryParameter("p", production)
                if (duration.isNotBlank()) addQueryParameter("m", duration)
            }.build()
        }

        return GET(url, headers)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = try {
        super.getSearchAnime(page, query, filters)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is HttpException && e.code == 404) {
            AnimesPage(emptyList(), false)
        } else {
            throw e
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (document.selectFirst(".noResults, h1.searchPageTitle:matches((?i)no results)") != null) {
            return AnimesPage(emptyList(), false)
        }
        val elements = document.select("ul#videoSearchResult li, li.pcVideoListItem, li.videoBox")
        return AnimesPage(parseVideoCards(elements), hasNextPage(document))
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val uploader = document.selectFirst("div.userInfo a.usernameWrap, a.bolded[href*=/users/], a.bolded[href*=/model/]")?.text()
        val views = document.selectFirst("div.ratingInfo div.views span.count, span.views .count")?.text()
        val rating = document.selectFirst("span.percent")?.text()

        val title = document.selectFirst("h1.title span.inlineFree, h1.title, .video-wrapper h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Pornhub Video"

        val genres = document.select("div.categoriesWrapper a[href*=/video?c=], div.tagsWrapper a.item")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return SAnime.create().apply {
            this.title = title
            author = uploader
            artist = uploader
            genre = genres.joinToString()
            status = SAnime.COMPLETED
            description = buildString {
                if (!uploader.isNullOrBlank()) append("Uploader: ").append(uploader).append("\n")
                if (!views.isNullOrBlank()) append("Views: ").append(views).append("\n")
                if (!rating.isNullOrBlank()) append("Rating: ").append(rating).append("\n")
            }
            thumbnail_url = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Play Video"
            episode_number = 1f
        },
    )

    // ============================= Video Links ============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scripts = document.select("script").map { it.data() }

        val flashvarsJson = scripts.firstNotNullOfOrNull { script ->
            FLASHVARS_REGEX.find(script)?.groupValues?.getOrNull(1)
        }

        val flashvars = flashvarsJson?.let {
            runCatching { it.parseAs<Flashvars>() }.getOrNull()
        }

        val videoList = mutableListOf<Video>()

        // 1. Extract HLS streams
        flashvars?.mediaDefinitions
            ?.filter { it.format.equals("hls", ignoreCase = true) || it.videoUrl?.contains(".m3u8") == true }
            ?.mapNotNull { it.videoUrl }
            ?.distinct()
            ?.forEach { hlsUrl ->
                runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = hlsUrl,
                        referer = "$baseUrl/",
                        videoNameGen = { quality -> "$quality (HLS)" },
                    )
                }.getOrNull()?.let(videoList::addAll)
            }

        // 2. Extract MP4 streams
        val mp4Definitions = flashvars?.mediaDefinitions.orEmpty()
            .filter { it.format.equals("mp4", ignoreCase = true) }

        for (def in mp4Definitions) {
            val url = def.videoUrl ?: continue
            if (url.contains("/video/get_media")) {
                runCatching {
                    client.newCall(GET(url, headers)).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val mediaList = resp.parseAs<List<MediaDefinition>>()
                            for (media in mediaList) {
                                val directUrl = media.videoUrl?.takeIf { it.startsWith("http") } ?: continue
                                val quality = media.qualityString
                                    ?: RESOLUTION_REGEX.find(directUrl)?.groupValues?.getOrNull(1)?.let { "${it}p" }
                                    ?: "MP4"
                                videoList.add(Video(directUrl, "$quality (MP4)", directUrl, headers))
                            }
                        }
                    }
                }
            } else if (url.startsWith("http")) {
                val quality = def.qualityString
                    ?: RESOLUTION_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
                    ?: "MP4"
                videoList.add(Video(url, "$quality (MP4)", url, headers))
            }
        }

        // 3. Fallback to direct video tag or meta tags
        if (videoList.isEmpty()) {
            val metaVideo = document.selectFirst(
                "meta[property=og:video:url], meta[property=og:video], meta[name=twitter:player:stream]",
            )?.attr("content")
            if (!metaVideo.isNullOrBlank() && metaVideo.startsWith("http")) {
                videoList.add(Video(metaVideo, "Default (MP4)", metaVideo, headers))
            }
        }

        return videoList.distinctBy { it.videoUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty().sortVideos()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video ->
                    RESOLUTION_REGEX.find(video.videoTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // ============================= Utilities ==============================

    private fun parseVideoCards(elements: Elements): List<SAnime> = elements.mapNotNull { element ->
        val link = element.selectFirst("a[href*=/view_video.php]") ?: return@mapNotNull null
        val title = link.attr("title").ifBlank {
            element.selectFirst("span.title a, .thumbnailTitle, .vidTitleWrapper a")?.text().orEmpty()
        }.ifBlank {
            element.selectFirst("img")?.attr("alt").orEmpty()
        }.trim()
        if (title.isEmpty()) return@mapNotNull null

        val img = element.selectFirst("img")
        val thumb = img?.attr("data-src")?.ifEmpty { img.attr("data-thumb_url") }
            ?.ifEmpty { img.attr("data-mediumthumb") }
            ?.ifEmpty { img.attr("src") }
            ?.takeIf { !it.startsWith("data:") && !it.contains("blank.gif") }

        SAnime.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url = thumb
        }
    }.distinctBy { it.url }

    private fun hasNextPage(document: Document): Boolean = document.selectFirst("li.page_next a, a.page_next, a[rel=next], ul.pagination li.next a") != null

    // ============================= Settings ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        PornhubFilters.SortFilter(),
        PornhubFilters.OrientationFilter(),
        PornhubFilters.QualityFilter(),
        PornhubFilters.DurationFilter(),
        PornhubFilters.ProductionFilter(),
        PornhubFilters.CategoryFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred Video Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080", "720", "480", "360", "240"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_video_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val FLASHVARS_REGEX = Regex(
            """var\s+flashvars_\d+\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val RESOLUTION_REGEX = Regex("""(\d+)p""", RegexOption.IGNORE_CASE)
    }
}
