package eu.kanade.tachiyomi.animeextension.en.anidb

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AniDB :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniDB"

    override val baseUrl = "https://anidb.app"

    override val lang = "en"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_top_airing&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith("http")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && (url.host == "anidb.app" || url.host == "anidb.net") && url.pathSegments.contains("anime")) {
                return GET(url, headers)
            }
        }

        var themeId: String? = null
        var demographicId: String? = null
        var studioId: String? = null

        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("type", filter.toUriPart())
                }
                is Filters.StatusFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                }
                is Filters.SeasonFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())
                }
                is Filters.YearFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("year", filter.toUriPart())
                }
                is Filters.DemographicFilter -> {
                    if (!filter.isDefault() && query.isBlank()) demographicId = filter.toUriPart()
                }
                is Filters.GenreFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("genres", filter.toUriPart())
                }
                is Filters.ThemeFilter -> {
                    if (!filter.isDefault() && query.isBlank()) themeId = filter.toUriPart()
                }
                is Filters.StudioFilter -> {
                    if (!filter.isDefault() && query.isBlank()) studioId = filter.toUriPart()
                }
                is Filters.SortFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                }
                else -> {}
            }
        }

        urlBuilder.addQueryParameter("page", page.toString())

        return if (themeId != null) {
            val themeBuilder = "$baseUrl/themes/$themeId".toHttpUrl().newBuilder()
            themeBuilder.addQueryParameter("page", page.toString())
            GET(themeBuilder.build(), headers)
        } else if (demographicId != null) {
            val demoBuilder = "$baseUrl/demographics/$demographicId".toHttpUrl().newBuilder()
            demoBuilder.addQueryParameter("page", page.toString())
            GET(demoBuilder.build(), headers)
        } else if (studioId != null) {
            val studioBuilder = "$baseUrl/studios/$studioId".toHttpUrl().newBuilder()
            studioBuilder.addQueryParameter("page", page.toString())
            GET(studioBuilder.build(), headers)
        } else {
            GET(urlBuilder.build(), headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.pathSegments.contains("anime")) {
            val anime = animeDetailsParse(response).apply {
                setUrlWithoutDomain(response.request.url.toString())
            }
            return AnimesPage(listOf(anime), false)
        }
        return parseAnimesPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.DemographicFilter(),
        Filters.GenreFilter(),
        Filters.ThemeFilter(),
        Filters.StudioFilter(),
        Filters.SortFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val dl = document.selectFirst("dl.grid")

        return SAnime.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("img[src*=poster]")?.attr("abs:src")

            val altTitles = mutableListOf<String>()
            document.selectFirst("p.text-sm.text-muted.mb-3")?.text()?.takeIf { it.isNotEmpty() }?.let { altTitles.add(it) }
            dl?.selectFirst("dt:contains(Synonyms) + dd")?.text()?.takeIf { it.isNotEmpty() }?.let { it ->
                it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.forEach { altTitles.add(it) }
            }

            val scoreText = dl?.selectFirst("dt:contains(Score) + dd")?.text()
            val scoreValue = scoreText?.toFloatOrNull()
            val stars = if (scoreValue != null) {
                val filled = (scoreValue / 2.0).roundToInt().coerceIn(0, 5)
                "★".repeat(filled) + "☆".repeat(5 - filled) + " $scoreValue"
            } else {
                null
            }

            val type = dl?.selectFirst("dt:contains(Type) + dd")?.text()
            val season = dl?.selectFirst("dt:contains(Season) + dd")?.text()
            val duration = dl?.selectFirst("dt:contains(Duration) + dd")?.text()
            val rating = dl?.selectFirst("dt:contains(Rating) + dd")?.text()
            val metaLine1 = listOfNotNull(
                type?.let { "**Type:** $it" },
                season?.let { "**Season:** $it" },
                duration?.let { "**Duration:** $it" },
                rating?.let { "**Rating:** $it" },
            ).joinToString(" | ")

            val airedRaw = dl?.selectFirst("dt:contains(Aired) + dd")?.text()
            val metaLine2 = if (airedRaw != null) {
                val parts = airedRaw.split(Regex("\\s*[–—-]\\s*"))
                val dateAired = parts.getOrNull(0)?.trim()
                val dateEnded = parts.getOrNull(1)?.trim()
                buildString {
                    if (dateAired != null) append("**Date Aired:** $dateAired")
                    if (dateEnded != null) {
                        if (isNotEmpty()) append("\n")
                        append("**Date Ended:** $dateEnded")
                    }
                }
            } else {
                ""
            }

            val trailerUrl = document.selectFirst("a:contains(Trailer)")?.absUrl("href")
            val synopsis = document.select("h2:contains(Synopsis) + div p")
                .joinToString("\n\n") { it.text() }

            val allowedDomains = listOf("myanimelist.net", "anilist.co", "anidb.net", "kitsu.app")
            val links = document.select("div[class*='gap-2'].mb-4 a[target=_blank]")
                .filter { a ->
                    val href = a.attr("href").lowercase()
                    allowedDomains.any { domain -> href.contains(domain) }
                }
                .joinToString(" | ") { a -> "[${a.text()}](${a.absUrl("href")})" }

            description = buildString {
                if (stars != null) {
                    append("$stars\n\n")
                }
                append(synopsis)
                if (metaLine1.isNotEmpty()) {
                    append("\n\n$metaLine1")
                }
                if (metaLine2.isNotEmpty()) {
                    append("\n$metaLine2")
                }
                if (altTitles.isNotEmpty()) {
                    append("\n\n**Alternative Titles:**\n")
                    altTitles.distinct().forEach { append("- $it\n") }
                }
                if (links.isNotEmpty()) {
                    append("\n\n**Links:** $links")
                }
                if (!trailerUrl.isNullOrEmpty()) {
                    append("\n\n[Trailer]($trailerUrl)")
                }
            }.trim()

            author = dl?.selectFirst("dt:contains(Studios) + dd a, dt:contains(Studio) + dd a")?.text()
            val statusText = dl?.selectFirst("dt:contains(Status) + dd")?.text()?.lowercase()
            status = when {
                statusText?.contains("currently airing") == true -> SAnime.ONGOING
                statusText?.contains("finished airing") == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            val demographic = dl?.selectFirst("dt:contains(Demographic) + dd a")?.text()
            val genresList = document.select("div[class*='gap-1.5'].mb-4 a").map { it.text() }
            genre = (listOfNotNull(demographic) + genresList).joinToString()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val lastSegment = (baseUrl + anime.url).toHttpUrl().pathSegments.last()
        val animeId = ANIME_ID_REGEX.find(lastSegment)?.groupValues?.get(1) ?: lastSegment
        return GET("$baseUrl/api/frontend/anime/$animeId/episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodesArr = response.parseAs<EpisodeResponseDto>().episodes

        val minEpNumber = episodesArr.minOfOrNull { it.number.toFloat() } ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        val hideFiller = preferences.getBoolean(PREF_FILLER_HIDE_KEY, PREF_FILLER_HIDE_DEFAULT)
        val showFillerTag = preferences.getBoolean(PREF_FILLER_TAG_KEY, PREF_FILLER_TAG_DEFAULT)

        return episodesArr
            .filter { !hideFiller || !it.filler }
            .map { it.toSEpisode(offset, showFillerTag) }
            .reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/api/frontend/episode/${episode.url}/languages", headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val languages = client.newCall(videoListRequest(episode)).awaitSuccess()
            .parseAs<LanguageResponseDto>().languages

        return languages.parallelCatchingFlatMap { language ->
            client.newCall(GET(language.embedUrl, headers)).awaitSuccess().use { embedResponse ->
                val html = embedResponse.body.string()
                val m3u8Url = M3U8_REGEX.find(html)?.groupValues?.get(1)
                    ?: return@parallelCatchingFlatMap emptyList()

                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = "$baseUrl/",
                    masterHeaders = headers,
                    videoHeaders = headers,
                    videoNameGen = { quality -> "${language.name} - $quality" },
                )
            }
        }.sortVideos()
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    private fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        val primaryLang = if (langPref == "eng") "English" else "Japanese"
        val secondaryLang = if (langPref == "eng") "Japanese" else "English"

        val qualityOrder = listOfNotNull(
            qualityPref,
            "1080p".takeIf { it != qualityPref },
            "720p".takeIf { it != qualityPref },
            "360p".takeIf { it != qualityPref },
        )

        val langOrder = listOf(primaryLang, secondaryLang)

        val idealOrder = qualityOrder.flatMap { res ->
            langOrder.map { lang -> "$lang - $res" }
        }

        return this.sortedBy { video ->
            idealOrder.indexOfFirst { video.quality.startsWith(it) }
                .let { if (it != -1) it else Int.MAX_VALUE }
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES.toTypedArray()
            entryValues = PREF_QUALITY_ENTRIES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            screen.addPreference(this)
        }

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES.toTypedArray()
            entryValues = PREF_LANG_VALUES.toTypedArray()
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            screen.addPreference(this)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FILLER_TAG_KEY
            title = PREF_FILLER_TAG_TITLE
            setDefaultValue(PREF_FILLER_TAG_DEFAULT)
            summary = "Adds '(Filler)' to episode names when available."
            screen.addPreference(this)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FILLER_HIDE_KEY
            title = PREF_FILLER_HIDE_TITLE
            setDefaultValue(PREF_FILLER_HIDE_DEFAULT)
            summary = "Hides detected filler episodes from episode list."
            screen.addPreference(this)
        }
    }

    // ============================= Utilities ==============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeMap = linkedMapOf<String, SAnime>()

        // Priority: Seasons
        document.select("div.overflow-x-auto.snap-x a[href*=/anime/]").forEach { a ->
            val url = a.absUrl("href")
            if (url.isNotEmpty()) {
                animeMap[url] = SAnime.create().apply {
                    setUrlWithoutDomain(url)
                    title = a.attr("title").takeIf { it.isNotEmpty() } ?: a.text()
                    thumbnail_url = a.selectFirst("img")?.absUrl("src")
                }
            }
        }

        // Standard grid / Relations (skips duplicates already added from seasons)
        document.select(".anime-grid a.anime-card").forEach { card ->
            val url = card.absUrl("href")
            if (url.isNotEmpty() && url !in animeMap) {
                animeMap[url] = SAnime.create().apply {
                    setUrlWithoutDomain(url)
                    title = card.selectFirst("p.text-xs, .card-overlay p")?.text()
                        ?: card.attr("title")
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                }
            }
        }

        val hasNextPage = document.select("a").any {
            it.text().contains("Next") && it.attr("href").contains("page=")
        }
        return AnimesPage(animeMap.values.toList(), hasNextPage)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred Language"
        private const val PREF_LANG_DEFAULT = "jpn"
        private val PREF_LANG_ENTRIES = listOf("Japanese", "English")
        private val PREF_LANG_VALUES = listOf("jpn", "eng")

        private const val PREF_FILLER_TAG_KEY = "append_filler_tag"
        private const val PREF_FILLER_TAG_TITLE = "Filler Detection"
        private const val PREF_FILLER_TAG_DEFAULT = true

        private const val PREF_FILLER_HIDE_KEY = "hide_filler"
        private const val PREF_FILLER_HIDE_TITLE = "Hide Filler Episodes"
        private const val PREF_FILLER_HIDE_DEFAULT = false

        private val ANIME_ID_REGEX = Regex("-(\\d+)$")

        private val M3U8_REGEX = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")
    }
}
