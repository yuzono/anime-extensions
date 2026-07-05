package eu.kanade.tachiyomi.animeextension.all.anizone

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

class AniZone :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniZone"

    override val baseUrl = "https://anizone.to"

    override val lang = "all"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    private val preferences by getPreferencesLazy()

    private var token: String = ""

    private val snapShots: MutableMap<String, String> = mutableMapOf(
        ANIME_SNAPSHOT_KEY to "",
        EPISODE_SNAPSHOT_KEY to "",
        VIDEO_SNAPSHOT_KEY to "",
    )

    private var animeLoadCount: Int = 0

    private val seenUrls = mutableSetOf<String>()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        animeLoadCount = 0
        snapShots[ANIME_SNAPSHOT_KEY] = ""
        token = ""
        seenUrls.clear()

        GET("$baseUrl/anime?sort=title-asc", headers)
    } else {
        val updates = buildJsonObject { }
        val calls = buildJsonArray {
            addJsonObject {
                put("path", "")
                put("method", "loadMore")
                putJsonArray("params") { }
            }
        }

        createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        if (response.code == 419) {
            token = ""
            throw Exception("Page expired (419). Please refresh.")
        }
        val isLivewire = response.request.url.encodedPath.contains("/livewire/update")
        val html = if (isLivewire) {
            response.parseAs<LivewireDto>().getHtml(ANIME_SNAPSHOT_KEY)
        } else {
            val doc = response.asJsoup()
            doc.selectFirst("script[data-csrf]")?.attr("data-csrf")?.takeIf { it.isNotEmpty() }?.let { token = it }
            doc.getSnapshot()?.let { snapShots[ANIME_SNAPSHOT_KEY] = it }
            doc.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]") ?: doc
        }

        val animeDict = (if (html.hasAttr("x-data") && html.attr("x-data").contains("animeDict")) html else html.selectFirst("[x-data*=animeDict]"))
            ?.attr("x-data")?.let { extractAnimeDict(it) } ?: emptyMap()

        val allElements = html.select(".grid > div, .grid > li, li.space-y-3").filter { it.selectFirst("a[href*=/anime/]") != null }
        val animeList = allElements.drop(animeLoadCount)
            .mapNotNull { element -> animeFromElement(element, animeDict) }
            .filter { it.url !in seenUrls }
            .onEach { seenUrls.add(it.url) }

        val hasNextPage = html.selectFirst("div[x-intersect~=loadMore]") != null

        animeLoadCount = allElements.size

        return AnimesPage(animeList, hasNextPage)
    }

    private fun animeFromElement(element: Element, animeDict: Map<String, Map<String, String>> = emptyMap()): SAnime? {
        val allLinks = element.select("a[href*=/anime/]")
        val titleLink = allLinks.firstOrNull {
            val path = it.attr("href").substringAfter("/anime/").trim('/')
            path.isNotEmpty() && !path.contains("/")
        } ?: allLinks.firstOrNull() ?: return null

        val xData = element.attr("x-data")

        return SAnime.create().apply {
            val rawUrl = titleLink.absUrl("href")
            val animeUrl = if (rawUrl.substringAfter("/anime/").trim('/').contains("/")) {
                rawUrl.substringBeforeLast("/")
            } else {
                rawUrl
            }
            setUrlWithoutDomain(animeUrl)

            val seriesTitleElement = titleLink.selectFirst("span[x-text*=AnimeTitle]")
                ?: element.selectFirst("span[x-text*=AnimeTitle]")
            val fallback = seriesTitleElement?.text()
                ?: titleLink.attr("title").takeIf { it.isNotBlank() }
                ?: titleLink.text().takeIf { it.isNotBlank() }

            val anmSlug = SLUG_REGEX.find(xData)?.groupValues?.get(1)
            val titlesFromDict = animeDict[anmSlug]

            title = getPreferredTitle(xData, fallback, isAnime = true, titlesFromDict = titlesFromDict) ?: return null

            thumbnail_url = element.selectFirst("img")?.attr("abs:src") ?: ""
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        animeLoadCount = 0
        snapShots[ANIME_SNAPSHOT_KEY] = ""
        token = ""
        seenUrls.clear()

        GET("$baseUrl/episode?sort=release-desc", headers)
    } else {
        val updates = buildJsonObject { }
        val calls = buildJsonArray {
            addJsonObject {
                put("path", "")
                put("method", "loadMore")
                putJsonArray("params") { }
            }
        }

        createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls, "/episode")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()

        return if (page == 1) {
            animeLoadCount = 0
            snapShots[ANIME_SNAPSHOT_KEY] = ""
            token = ""
            seenUrls.clear()

            GET("$baseUrl/anime?search=$query&sort=${sortFilter.toUriPart()}", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(SortFilter())

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("A-Z", "title-asc"),
                Pair("Z-A", "title-desc"),
                Pair("Earliest Release", "release-asc"),
                Pair("Latest Release", "release-desc"),
                Pair("First Added", "added-asc"),
                Pair("Last Added", "added-desc"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.flex.items-start img")?.attr("abs:src") ?: ""

            val xDataElement = document.selectFirst("[x-data*=anmTitles]")
            val xData = xDataElement?.attr("x-data") ?: ""

            val fallbackText = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("title")?.text()?.substringBefore(" — AniZone")

            title = getPreferredTitle(xData, fallbackText) ?: throw Exception("Could not find title")

            status = document.select("span.inline-block")
                .firstOrNull {
                    it.text().lowercase() in listOf("completed", "ongoing", "upcoming", "cancelled")
                }?.text()?.let {
                    when (it.lowercase()) {
                        "completed" -> SAnime.COMPLETED
                        "ongoing" -> SAnime.ONGOING
                        else -> SAnime.UNKNOWN
                    }
                } ?: SAnime.UNKNOWN

            genre = document.select("a[href*=/tag/]").joinToString { it.text() }

            description = document.selectFirst("div:has(> h3:contains(Synopsis)) > div")
                ?.html()
                ?.replace(BR_REGEX, "___br___")
                ?.let { parseBodyFragment(it).text() }
                ?.replace("___br___", "\n")
                ?.replace("`", "'")
        }
    }

    // ============================== Episodes ==============================

    private fun getPredefinedSnapshots(slug: String): String = when (slug) {
        "/anime/uyyyn4kf" -> """{"data":{"anime":[null,{"class":"anime","key":68,"s":"mdl"}],"title":null,"search":"","listSize":1104,"sort":"release-asc","sortOptions":[{"release-asc":"First Aired","release-desc":"Last Aired"},{"s":"arr"}],"view":"list","paginators":[{"page":1},{"s":"arr"}]},"memo":{"id":"GD1OiEMOJq6UQDQt1OBt","name":"pages.anime-detail","path":"anime\/uyyyn4kf","method":"GET","children":[],"scripts":[],"assets":[],"errors":[],"locale":"en"},"checksum":"5800932dd82e4862f34f6fd72d8098243b32643e8accb8da6a6a39cd0ee86acd"}"""
        else -> ""
    }

    override fun episodeListRequest(anime: SAnime): Request {
        snapShots[EPISODE_SNAPSHOT_KEY] = getPredefinedSnapshots(anime.url)
        return GET(baseUrl + anime.url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = if (response.code == 419) {
            token = ""
            client.newCall(response.request).execute()
                .parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)
        } else if (response.request.url.encodedPath.contains("/livewire/update")) {
            response.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)
        } else {
            val doc = response.asJsoup()
            doc.selectFirst("script[data-csrf]")?.attr("data-csrf")?.takeIf { it.isNotEmpty() }?.let { token = it }
            doc.getSnapshot()?.let { snapShots[EPISODE_SNAPSHOT_KEY] = it }
            doc.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]") ?: doc
        }

        val allElements = html.select(episodeSelector)
        val episodeList = allElements.mapNotNull(::episodeFromElement).toMutableList()
        var epLoadCount = allElements.size

        var hasMore = html.selectFirst("div[x-intersect~=loadMore]") != null

        while (hasMore) {
            val updates = buildJsonObject { }
            val calls = buildJsonArray {
                addJsonObject {
                    put("path", "")
                    put("method", "loadMore")
                    putJsonArray("params") { }
                }
            }

            var resp = client.newCall(
                createLivewireReq(EPISODE_SNAPSHOT_KEY, updates, calls),
            ).execute()

            if (resp.code == 419) {
                token = ""
                resp = client.newCall(
                    createLivewireReq(EPISODE_SNAPSHOT_KEY, updates, calls),
                ).execute()
            }

            val newElements = resp.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY).select(episodeSelector)
            val episodes = newElements.drop(epLoadCount)
                .mapNotNull(::episodeFromElement)

            episodeList.addAll(episodes)
            epLoadCount = newElements.size

            hasMore = resp.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY).selectFirst("div[x-intersect~=loadMore]") != null
        }

        return episodeList
    }

    private val episodeSelector = "ul > li"

    private fun episodeFromElement(element: Element): SEpisode? {
        val url = element.select("a[href*=/anime/]").firstOrNull()?.absUrl("href")
            ?: element.selectFirst("a[href]")?.absUrl("href")
            ?: return null

        val xData = element.attr("x-data")

        val h3 = element.selectFirst("h3")
        val baseName = h3?.ownText()?.clean() ?: "Episode"

        val fallbackTitle = h3?.selectFirst("span")?.text()
            ?.substringAfter(":")

        val episodeTitle = getPreferredTitle(xData, fallbackTitle, isAnime = false)

        return SEpisode.create().apply {
            setUrlWithoutDomain(url)

            name = if (!episodeTitle.isNullOrBlank() && episodeTitle != "Unknown") {
                "$baseName - $episodeTitle"
            } else {
                baseName
            }

            date_upload = element.select("span")
                .firstOrNull { it.text().matches(DATE_REGEX) }
                ?.text()
                ?.let { parseDate(it) } ?: 0L
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    private val playlistUtils: PlaylistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverSelects = document.select("button[wire:click]")
            .filter { video ->
                video.attr("wire:click").contains("setVideo")
            }

        val subtitles = document.select("track[kind=subtitles]").map {
            Track(it.attr("src"), it.attr("label"))
        }

        val mediaPlayer = document.selectFirst("media-player")
        val m3u8List = mutableListOf<VideoData>()

        mediaPlayer?.attr("src")?.also {
            m3u8List.add(
                VideoData(
                    url = it,
                    name = serverSelects.firstOrNull()?.text() ?: "Default",
                    subtitles = subtitles,
                ),
            )
        }

        snapShots[VIDEO_SNAPSHOT_KEY] = document.getSnapshot() ?: ""

        serverSelects.drop(1).forEach { video ->
            val matchResult = SET_VIDEO_REGEX.find(video.attr("wire:click"))
            val videoId = if (matchResult != null && matchResult.groupValues.size == 2) {
                matchResult.groupValues[1]
            } else {
                "0"
            }
            val updates = buildJsonObject { }
            val calls = buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "")
                        put("method", "setVideo")
                        putJsonArray("params") {
                            add(videoId.toInt())
                        }
                    },
                )
            }

            var resp = client.newCall(
                createLivewireReq(VIDEO_SNAPSHOT_KEY, updates, calls, response.request.url.encodedPath),
            ).execute()

            if (resp.code == 419) {
                token = ""
                resp = client.newCall(
                    createLivewireReq(VIDEO_SNAPSHOT_KEY, updates, calls, response.request.url.encodedPath),
                ).execute()
            }

            val doc = resp.parseAs<LivewireDto>().getHtml(VIDEO_SNAPSHOT_KEY)

            val subs = doc.select("track[kind=subtitles]").map {
                Track(it.attr("src"), it.attr("label"))
            }

            doc.selectFirst("media-player")?.attr("src")?.also {
                m3u8List.add(
                    VideoData(
                        url = it,
                        name = video.text(),
                        subtitles = subs,
                    ),
                )
            }
        }

        val serverList = if (preferences.dub) {
            m3u8List
        } else {
            m3u8List.reversed()
        }

        return serverList.flatMap {
            playlistUtils.extractFromHls(
                playlistUrl = it.url,
                referer = "$baseUrl/",
                videoNameGen = { q -> "${it.name} - $q" },
                subtitleList = it.subtitles,
            )
        }
    }

    data class VideoData(
        val url: String,
        val name: String,
        val subtitles: List<Track>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun LivewireDto.getHtml(mapKey: String): Document {
        val data = this.components.first()

        snapShots[mapKey] = data.snapshot.replace("\\\"", "\"")

        return parseBodyFragment(
            data.effects.html.replace("\\\"", "\"")
                .replace("\\n", ""),
            baseUrl,
        )
    }

    private fun Document.getSnapshot(): String? = this.selectFirst("main > div[wire:snapshot]")
        ?.attr("wire:snapshot")
        ?.replace("&quot;", "\"")

    private fun createLivewireReq(
        mapKey: String,
        updates: JsonObject,
        calls: JsonArray,
        initialSlug: String = "/anime",
    ): Request {
        val firstSnapshot = snapShots[mapKey] ?: ""

        if (firstSnapshot.isEmpty() || token.isEmpty()) {
            val doc = client.newCall(GET(baseUrl + initialSlug, headers)).execute()
                .asJsoup()

            snapShots[mapKey] = doc.getSnapshot() ?: ""

            token = doc.selectFirst("script[data-csrf]")
                ?.attr("data-csrf")
                ?.takeIf(String::isNotEmpty)
                ?: throw Exception("Failed to get csrf token")
        }

        val headers = headersBuilder().apply {
            add("X-Livewire", "")
            add("X-CSRF-TOKEN", token)
            add("Origin", baseUrl)
        }.build()

        val body = LivewireRequestDto(
            token = token,
            components = listOf(
                LivewireComponentRequestDto(
                    calls = calls,
                    snapshot = snapShots[mapKey]!!,
                    updates = updates,
                ),
            ),
        ).toJsonRequestBody()

        return POST("$baseUrl/livewire/update", headers, body)
    }

    private fun getPreferredTitle(
        xData: String,
        fallbackText: String? = null,
        isAnime: Boolean = true,
        titlesFromDict: Map<String, String>? = null,
    ): String? {
        val fallbackTitle = FALLBACK_TITLE_REGEX.find(xData)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.clean()
            ?: fallbackText

        val titlesMap = titlesFromDict ?: run {
            val targetKey = if (isAnime) "anmTitles" else "epsTitles"
            val parseMarker = "$targetKey: JSON.parse('"
            val jsonStart = xData.indexOf(parseMarker)
            if (jsonStart != -1) {
                val startIdx = jsonStart + parseMarker.length
                val endIdx = xData.indexOf("')", startIdx)
                if (endIdx != -1) {
                    val jsonString = xData.substring(startIdx, endIdx)
                        .replace("\\u0022", "\"")
                        .replace("\\u0026", "\\")
                        .replace("\\'", "'")
                    try {
                        jsonString.parseAs<Map<String, String>>()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }

        val title = titlesMap?.let {
            it[preferences.preferredTitleLang]
                ?: it["1"]
                ?: it["5"]
        } ?: fallbackTitle

        return title?.clean()
    }

    private fun extractAnimeDict(xData: String): Map<String, Map<String, String>> {
        val marker = "animeDict: JSON.parse('"
        val start = xData.indexOf(marker)
        if (start == -1) return emptyMap()
        val startIdx = start + marker.length
        val endIdx = xData.indexOf("')", startIdx)
        if (endIdx != -1) {
            val jsonString = xData.substring(startIdx, endIdx)
                .replace("\\u0022", "\"")
                .replace("\\'", "'")
            return try {
                jsonString.parseAs<Map<String, Map<String, String>>>()
            } catch (_: Exception) {
                emptyMap()
            }
        }
        return emptyMap()
    }

    private fun String.clean() = Parser.unescapeEntities(this, false).replace("`", "'").trim()

    private fun parseDate(dateStr: String): Long = DATE_FORMAT.tryParse(dateStr)

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.dub
        get() = getBoolean(PREF_DUB_KEY, PREF_DUB_DEFAULT)

    private val SharedPreferences.preferredTitleLang
        get() = getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!

    companion object {
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val FALLBACK_TITLE_REGEX = Regex("""getTitle\([^,]+,\s*'([^']+)'\)""")
        private val SLUG_REGEX = Regex("""anmSlug:\s*'([^']+)'""")
        private val SET_VIDEO_REGEX = Regex("""setVideo\('(\d+)'\)""")
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

        private const val ANIME_SNAPSHOT_KEY = "anime_snapshot_key"
        private const val EPISODE_SNAPSHOT_KEY = "episode_snapshot_key"
        private const val VIDEO_SNAPSHOT_KEY = "video_snapshot_key"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_DUB_KEY = "attempt_dub"
        private const val PREF_DUB_TITLE = "Attempt To Prefer Dub"
        private const val PREF_DUB_DEFAULT = false

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_TITLE = "Preferred Title Language"
        private const val PREF_TITLE_LANG_DEFAULT = "1"
        private val PREF_TITLE_LANG_ENTRIES = arrayOf("English", "Romaji")
        private val PREF_TITLE_LANG_ENTRY_VALUES = arrayOf("1", "5")
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = PREF_TITLE_LANG_TITLE
            entries = PREF_TITLE_LANG_ENTRIES
            entryValues = PREF_TITLE_LANG_ENTRY_VALUES
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DUB_KEY
            title = PREF_DUB_TITLE
            setDefaultValue(PREF_DUB_DEFAULT)
        }.also(screen::addPreference)
    }
}
