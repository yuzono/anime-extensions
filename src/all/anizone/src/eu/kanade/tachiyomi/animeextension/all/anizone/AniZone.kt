package eu.kanade.tachiyomi.animeextension.all.anizone

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
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

    private val seenUrls = mutableSetOf<String>()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
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
        val res = response.retryOn419 { req ->
            if (req.url.encodedPath.contains("/livewire/update")) {
                val updates = buildJsonObject { }
                val calls = buildJsonArray {
                    addJsonObject {
                        put("path", "")
                        put("method", "loadMore")
                        putJsonArray("params") { }
                    }
                }
                val slug = if (req.url.toString().contains("/episode")) "/episode" else "/anime"
                newLivewireCall(ANIME_SNAPSHOT_KEY, updates, calls, slug)
            } else {
                client.newCall(req).execute()
            }
        }

        val isLivewire = res.request.url.encodedPath.contains("/livewire/update")
        val html = if (isLivewire) {
            res.parseAs<LivewireDto>().getHtml(ANIME_SNAPSHOT_KEY)
        } else {
            res.asJsoup().updateState(ANIME_SNAPSHOT_KEY)
        }

        val animeDict = (if (html.hasAttr("x-data") && html.attr("x-data").contains("animeDict")) html else html.selectFirst("[x-data*=animeDict]"))
            ?.attr("x-data")?.let { extractAnimeDict(it) } ?: emptyMap()

        val allElements = html.select(".grid > div, .grid > li, li.space-y-3").filter { it.selectFirst("a[href*=/anime/]") != null }
        val animeList = allElements
            .mapNotNull { element -> animeFromElement(element, animeDict) }
            .filter { it.url !in seenUrls }
            .onEach { seenUrls.add(it.url) }

        val hasNextPage = html.selectFirst("div[x-intersect~=loadMore]") != null

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
                        "cancelled" -> SAnime.CANCELLED
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
        val res = response.retryOn419 { client.newCall(it).execute() }

        val html = if (res.request.url.encodedPath.contains("/livewire/update")) {
            res.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)
        } else {
            res.asJsoup().updateState(EPISODE_SNAPSHOT_KEY)
        }

        val allElements = html.select(episodeSelector)
        val episodeList = allElements.mapNotNull(::episodeFromElement).toMutableList()
        var epLoadCount = allElements.size

        var hasMore = html.selectFirst("div[x-intersect~=loadMore]") != null

        val updates = buildJsonObject { }
        val calls = buildJsonArray {
            addJsonObject {
                put("path", "")
                put("method", "loadMore")
                putJsonArray("params") { }
            }
        }

        while (hasMore) {
            val resp = newLivewireCall(EPISODE_SNAPSHOT_KEY, updates, calls, response.request.url.encodedPath)
            val livewireHtml = resp.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)

            val newElements = livewireHtml.select(episodeSelector)
            val episodes = newElements.drop(epLoadCount)
                .mapNotNull(::episodeFromElement)

            episodeList.addAll(episodes)
            epLoadCount = newElements.size

            hasMore = livewireHtml.selectFirst("div[x-intersect~=loadMore]") != null
        }

        val (specials, regulars) = episodeList.partition {
            val baseName = it.name.substringBefore(" - ")
            SEASON_REGEX.containsMatchIn(baseName) ||
                baseName.contains("Special", true) ||
                baseName.contains("Recap", true) ||
                !baseName.contains("Episode", true)
        }

        return specials.sortedByDescending { it.episode_number } + regulars.sortedByDescending { it.episode_number }
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

            episode_number = EPISODE_NUMBER_REGEX.findAll(baseName).lastOrNull()?.value?.toFloatOrNull() ?: -1f

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
        val res = response.retryOn419 { client.newCall(it).execute() }

        val document = res.asJsoup()
        val loadAll = preferences.loadAll

        val audioValue = preferences.audio
        val audioEntry = PREF_AUDIO_ENTRIES[PREF_AUDIO_ENTRY_VALUES.indexOf(audioValue)]
        val audioRegex = getLangRegex(audioValue)

        val fallbackAudioValue = "jpn"
        val fallbackAudioEntry = "Japanese"
        val fallbackAudioRegex = getLangRegex(fallbackAudioValue)

        val subValue = preferences.subtitle
        val subEntry = PREF_SUB_ENTRIES[PREF_SUB_ENTRY_VALUES.indexOf(subValue)]
        val subRegex = getLangRegex(subValue)

        val fallbackSubValue = "eng"
        val fallbackSubEntry = "English"
        val fallbackSubRegex = getLangRegex(fallbackSubValue)

        val subCount = preferences.subCount

        fun filterSubs(subs: List<Track>): List<Track> {
            if (loadAll) return subs
            val preferred = subs.filter { it.lang.containsLang(subValue, subEntry, subRegex) }
            val fallback = subs.filter { it.lang.containsLang(fallbackSubValue, fallbackSubEntry, fallbackSubRegex) }

            val initial = (preferred + fallback).distinctBy { it.url }
            if (initial.size >= subCount) return initial.take(subCount)

            val others = subs.filter { track -> initial.none { it.url == track.url } }
            return (initial + others).take(subCount)
        }

        val serverSelects = document.select("button[wire:click]")
            .filter { it.attr("wire:click").contains("setVideo") }

        val filteredServers = if (loadAll) {
            serverSelects
        } else {
            // Sort servers: preferred audio first, then fallback audio, then others
            val sorted = serverSelects.sortedWith(
                compareByDescending<Element> { it.text().containsLang(audioValue, audioEntry, audioRegex) }
                    .thenByDescending { it.text().containsLang(fallbackAudioValue, fallbackAudioEntry, fallbackAudioRegex) },
            )
            // Take the best match
            listOfNotNull(sorted.firstOrNull())
        }.ifEmpty { serverSelects }

        val m3u8List = mutableListOf<VideoData>()

        if (serverSelects.firstOrNull() in filteredServers) {
            val subtitles = filterSubs(
                document.select("track[kind=subtitles]").map {
                    Track(it.attr("src"), it.attr("label"))
                },
            )

            document.selectFirst("media-player")?.attr("src")?.also {
                m3u8List.add(
                    VideoData(
                        url = it,
                        name = serverSelects.firstOrNull()?.text() ?: "Default",
                        subtitles = subtitles,
                    ),
                )
            }
        }

        snapShots[VIDEO_SNAPSHOT_KEY] = document.getSnapshot() ?: ""

        filteredServers.filter { it != serverSelects.firstOrNull() }.forEach { video ->
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

            val resp = newLivewireCall(VIDEO_SNAPSHOT_KEY, updates, calls, res.request.url.encodedPath)
            val doc = resp.parseAs<LivewireDto>().getHtml(VIDEO_SNAPSHOT_KEY)

            val subs = filterSubs(
                doc.select("track[kind=subtitles]").map {
                    Track(it.attr("src"), it.attr("label"))
                },
            )

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

        val allVideos = m3u8List.flatMap {
            playlistUtils.extractFromHls(
                playlistUrl = it.url,
                referer = "$baseUrl/",
                videoNameGen = { q -> "${it.name} - $q" },
                subtitleList = it.subtitles,
            )
        }

        if (loadAll) return allVideos

        return allVideos.map { video ->
            val filteredAudio = video.audioTracks.filter { it.lang.containsLang(audioValue, audioEntry, audioRegex) }
            val finalAudio = filteredAudio.ifEmpty {
                video.audioTracks.filter { it.lang.containsLang(fallbackAudioValue, fallbackAudioEntry, fallbackAudioRegex) }
            }
            val finalSubs = filterSubs(video.subtitleTracks)
            Video(video.url, video.quality, video.videoUrl, video.headers, finalSubs, finalAudio)
        }.filter { video ->
            video.quality.containsLang(audioValue, audioEntry, audioRegex) ||
                video.quality.containsLang(fallbackAudioValue, fallbackAudioEntry, fallbackAudioRegex) ||
                video.audioTracks.isNotEmpty()
        }.ifEmpty { allVideos }
    }

    data class VideoData(
        val url: String,
        val name: String,
        val subtitles: List<Track>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        val audio = preferences.audio
        val subtitle = preferences.subtitle

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(audio, true) || (audio == "jpn" && (it.quality.contains("jp", true) || it.quality.contains("ja", true))) },
                { it.quality.contains(subtitle, true) || (subtitle == "eng" && (it.quality.contains("en", true))) },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun newLivewireCall(
        mapKey: String,
        updates: JsonObject,
        calls: JsonArray,
        initialSlug: String = "/anime",
    ): Response {
        fun build() = createLivewireReq(mapKey, updates, calls, initialSlug)
        return client.newCall(build()).execute().retryOn419 { client.newCall(build()).execute() }
    }

    private fun Response.retryOn419(onRetry: (Request) -> Response): Response {
        if (code == 419) {
            close()
            token = ""
            return onRetry(request)
        }
        return this
    }

    private fun LivewireDto.getHtml(mapKey: String): Document {
        val data = this.components.first()

        snapShots[mapKey] = data.snapshot.replace("\\\"", "\"")

        return parseBodyFragment(
            data.effects.html.replace("\\\"", "\"")
                .replace("\\n", ""),
            baseUrl,
        )
    }

    private fun Document.getSnapshot(): String? = this.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]")
        ?.attr("wire:snapshot")
        ?.replace("&quot;", "\"")

    private fun Document.updateState(mapKey: String): Element {
        this.selectFirst("script[data-csrf]")?.attr("data-csrf")?.takeIf(String::isNotEmpty)?.let { token = it }
        this.getSnapshot()?.let { snapShots[mapKey] = it }
        return this.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]") ?: this
    }

    private fun createLivewireReq(
        mapKey: String,
        updates: JsonObject,
        calls: JsonArray,
        initialSlug: String = "/anime",
    ): Request {
        val firstSnapshot = snapShots[mapKey] ?: ""

        if (firstSnapshot.isEmpty() || token.isEmpty()) {
            client.newCall(GET(baseUrl + initialSlug, headers)).execute()
                .asJsoup()
                .updateState(mapKey)

            if (token.isEmpty()) {
                throw Exception("Failed to get csrf token")
            }
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
            extractJsonFromXData<Map<String, String>>(xData, targetKey)
        }

        val title = titlesMap?.let {
            it[preferences.preferredTitleLang]
                ?: it["1"]
                ?: it["5"]
        } ?: fallbackTitle

        return title?.clean()
    }

    private fun extractAnimeDict(xData: String): Map<String, Map<String, String>> = extractJsonFromXData<Map<String, Map<String, String>>>(xData, "animeDict") ?: emptyMap()

    private inline fun <reified T> extractJsonFromXData(xData: String, key: String): T? {
        val marker = "$key: JSON.parse('"
        val start = xData.indexOf(marker)
        if (start == -1) return null
        val startIdx = start + marker.length
        val endIdx = xData.indexOf("')", startIdx)
        if (endIdx == -1) return null
        val jsonString = xData.substring(startIdx, endIdx)
            .replace("\\u0022", "\"")
            .replace("\\u0026", "\\&")
            .replace("\\'", "'")
        return try {
            jsonString.parseAs<T>()
        } catch (_: Exception) {
            null
        }
    }

    private fun getLangRegex(langValue: String): Regex? {
        if (LANG_REGEX_CACHE.containsKey(langValue)) return LANG_REGEX_CACHE[langValue]

        val shortCode = when (langValue) {
            "jpn" -> "ja|jp|jap"
            "eng" -> "en|eng"
            "fra" -> "fr|fra"
            "deu" -> "de|deu"
            "ita" -> "it|ita"
            "kor" -> "ko|kor"
            "ara" -> "ar|ara"
            "rus" -> "ru|rus"
            "spa", "spa-la", "spa-eu" -> "es|spa"
            "por-br", "por-eu" -> "pt|por"
            else -> null
        } ?: return null

        return Regex("(^|[^a-z])($shortCode)([^a-z]|$)").also {
            LANG_REGEX_CACHE[langValue] = it
        }
    }

    private fun String.containsLang(langValue: String, langEntry: String, regex: Regex? = null): Boolean {
        val normalized = this.lowercase()
        if (normalized.contains(langEntry.lowercase()) || normalized.contains(langValue.lowercase())) return true

        return regex?.containsMatchIn(normalized) ?: false
    }

    private fun String.clean() = Parser.unescapeEntities(this, false)
        .replace("\\/", "/")
        .replace("`", "'")
        .trim()

    private fun parseDate(dateStr: String): Long = DATE_FORMAT.tryParse(dateStr)

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.audio
        get() = getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)!!

    private val SharedPreferences.subtitle
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.loadAll
        get() = getBoolean(PREF_LOAD_ALL_KEY, PREF_LOAD_ALL_DEFAULT)

    private val SharedPreferences.subCount
        get() = getString(PREF_SUB_COUNT_KEY, PREF_SUB_COUNT_DEFAULT)?.toIntOrNull() ?: PREF_SUB_COUNT_DEFAULT.toInt()

    private val SharedPreferences.preferredTitleLang
        get() = getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!

    companion object {
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val FALLBACK_TITLE_REGEX = Regex("""getTitle\([^,]+,\s*'([^']+)'\)""")
        private val SLUG_REGEX = Regex("""anmSlug:\s*'([^']+)'""")
        private val SET_VIDEO_REGEX = Regex("""setVideo\('(\d+)'\)""")
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val SEASON_REGEX = Regex("(?i)s\\d+")
        private val EPISODE_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")

        private val LANG_REGEX_CACHE = mutableMapOf<String, Regex>()

        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

        private const val ANIME_SNAPSHOT_KEY = "anime_snapshot_key"
        private const val EPISODE_SNAPSHOT_KEY = "episode_snapshot_key"
        private const val VIDEO_SNAPSHOT_KEY = "video_snapshot_key"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_TITLE = "Preferred Audio Language"
        private const val PREF_AUDIO_DEFAULT = "jpn"
        private val PREF_AUDIO_ENTRIES = arrayOf("English", "French", "Polish", "Korean", "Japanese", "German", "Italian", "Spanish", "Hungarian", "Portuguese (Brazilian)", "Arabic", "Thai", "Spanish (Latin American)", "Filipino (Tagalog)", "Indonesian", "Hindi")
        private val PREF_AUDIO_ENTRY_VALUES = arrayOf("eng", "fra", "pol", "kor", "jpn", "deu", "ita", "spa", "hun", "por-br", "ara", "tha", "spa-la", "fil", "ind", "hin")

        private const val PREF_SUB_KEY = "preferred_subtitle"
        private const val PREF_SUB_TITLE = "Preferred Subtitle Language"
        private const val PREF_SUB_DEFAULT = "eng"
        private val PREF_SUB_ENTRIES = arrayOf("English", "Japanese", "Arabic", "Spanish", "Catalan", "Czech", "Danish", "German", "Greek", "Spanish (Latin American)", "Spanish (European)", "Spanish (Basque)", "Finnish", "Filipino (Tagalog)", "French", "Spanish (Galician)", "Hebrew", "Hindi", "Latin", "Croatian", "Hungarian", "Indonesian", "Italian", "Korean", "Malay", "Norwegian", "Dutch", "Polish", "Portuguese (Brazilian)", "Portuguese (European)", "Romanian", "Russian", "Swedish", "Thai", "Turkish", "Ukrainian", "Vietnamese", "Chinese (Simplified)", "Chinese (Traditional)")
        private val PREF_SUB_ENTRY_VALUES = arrayOf("eng", "jpn", "ara", "spa", "cat", "ces", "dan", "deu", "ell", "spa-la", "spa-eu", "eus", "fin", "fil", "fra", "glg", "heb", "hin", "lat", "hrv", "hun", "ind", "ita", "kor", "msa", "nor", "nld", "pol", "por-br", "por-eu", "ron", "rus", "swe", "tha", "tur", "ukr", "vie", "zho-s", "zho-t")

        private const val PREF_LOAD_ALL_KEY = "load_all_tracks"
        private const val PREF_LOAD_ALL_TITLE = "Load All Audio/Subtitle Tracks"
        private const val PREF_LOAD_ALL_DEFAULT = false

        private const val PREF_SUB_COUNT_KEY = "subtitle_count"
        private const val PREF_SUB_COUNT_TITLE = "Subtitle Count"
        private const val PREF_SUB_COUNT_DEFAULT = "2"

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

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = PREF_AUDIO_TITLE
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_ENTRY_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_ENTRY_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOAD_ALL_KEY
            title = PREF_LOAD_ALL_TITLE
            setDefaultValue(PREF_LOAD_ALL_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SUB_COUNT_KEY
            title = PREF_SUB_COUNT_TITLE
            setDefaultValue(PREF_SUB_COUNT_DEFAULT)
            val current = preferences.getString(PREF_SUB_COUNT_KEY, PREF_SUB_COUNT_DEFAULT)
            summary = "Number of subtitle tracks to load when 'Load all' is disabled. Current: $current"

            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }

            setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = "Number of subtitle tracks to load when 'Load all' is disabled. Current: $newValue"
                true
            }
        }.also(screen::addPreference)
    }
}
