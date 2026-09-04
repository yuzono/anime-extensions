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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

class AniZone :
    AnimeHttpLegacySource(),
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

    private var nextCursor: String = ""

    private var currentSlug: String = "/anime"

    private val snapShots: MutableMap<String, String> = mutableMapOf(
        ANIME_SNAPSHOT_KEY to "",
        EPISODE_SNAPSHOT_KEY to "",
        VIDEO_SNAPSHOT_KEY to "",
    )

    private val seenUrls = mutableSetOf<String>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        resetAnimeListState(slug = "/anime")

        GET("$baseUrl/anime?sort=title-asc", headers)
    } else {
        createLivewireReq(ANIME_SNAPSHOT_KEY, buildJsonObject { }, buildLoadPageCalls(nextCursor), currentSlug)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val res = response.retryOn419 { req ->
            if (req.url.encodedPath.contains("/livewire/update")) {
                newLivewireCall(ANIME_SNAPSHOT_KEY, buildJsonObject { }, buildLoadPageCalls(nextCursor), currentSlug)
            } else {
                client.newCall(req).execute()
            }
        }

        val isLivewire = res.request.url.encodedPath.contains("/livewire/update")

        var dispatchedItems: List<AnimeXData>? = null
        var dispatchedCursor: String? = null
        var dispatchedHasMore: Boolean? = null

        val html = if (isLivewire) {
            val dto = res.parseAs<LivewireDto>()

            val itemsLoaded = dto.components
                .firstOrNull()
                ?.effects
                ?.dispatches
                ?.firstOrNull { it.name == "items-loaded" }

            dispatchedItems = itemsLoaded?.params?.items.decodeItems<List<AnimeXData>>()
            dispatchedCursor = itemsLoaded?.params?.nextCursor
            dispatchedHasMore = itemsLoaded?.params?.hasMore

            dto.getHtml(ANIME_SNAPSHOT_KEY).body()
        } else {
            res.asJsoup().updateState(ANIME_SNAPSHOT_KEY)
        }

        val xDataContainer = html.selectFirst("[x-data*=items]")
        val xData = xDataContainer?.attr("x-data") ?: ""

        val itemsJson: JsonElement? = xData.extractAndParseJson("items")

        val items = dispatchedItems
            ?: extractJsonListFromXData<AnimeXData>(xData, "items")
                ?.takeIf { it.isNotEmpty() }
            // "items" on the wire is (almost) always a JSON array; if the typed
            // decode above didn't succeed, it's because the array's objects don't
            // match AnimeXData's shape (e.g. wrapped under "anime": {...}), not
            // because it isn't an array. Skip a redundant typed-decode attempt
            // and fall through to the manual per-key JsonObject branch below.
            ?: itemsJson?.takeIf { it !is JsonArray }
                ?.let { runCatching { it.toString().parseAs<List<AnimeXData>>() }.getOrNull() }

        val rawAnimeList = if (!items.isNullOrEmpty()) {
            items.mapNotNull { item ->
                val cleanUrl = item.url.toRelativeUrl()
                if (cleanUrl.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/$cleanUrl")
                    title = resolveTitle(item.title_list, item.main_title)
                    thumbnail_url = item.cover
                }
            }
        } else {
            val jsonElement = xData.extractAndParseJson("items")
            if (jsonElement is JsonArray) {
                jsonElement.mapNotNull { element ->
                    val obj = (element as? JsonObject) ?: return@mapNotNull null
                    val animeObj = obj["anime"] as? JsonObject ?: return@mapNotNull null
                    SAnime.create().apply {
                        val fullUrl = animeObj["url"]?.jsonPrimitive?.content ?: ""
                        setUrlWithoutDomain(fullUrl.replace(DOMAIN_REGEX, ""))

                        val titleListMap = (animeObj["title_list"] as? JsonObject)
                            ?.mapNotNull { (key, value) ->
                                (value as? JsonPrimitive)?.let { key to it.content }
                            }?.toMap()
                        title = resolveTitle(
                            titleListMap,
                            (animeObj["main_title"] as? JsonPrimitive)?.content,
                        )

                        thumbnail_url = obj["snapshot"]?.jsonPrimitive?.content
                            ?: obj["teaser"]?.jsonPrimitive?.content
                            ?: ""
                    }
                }
            } else {
                val animeDictStr = html.selectFirst("[x-data*=animeDict]")?.attr("x-data") ?: ""
                val animeDict = extractAnimeDict(animeDictStr)

                val allElements = html.select(".grid > div, .grid > li, li.space-y-3").filter { it.selectFirst("a[href*=/anime/]") != null }
                allElements.mapNotNull { element -> animeFromElement(element, animeDict) }
            }
        }

        val animeList = rawAnimeList.filter { seenUrls.add(it.url) }

        nextCursor = dispatchedCursor
            // Fallback only: this regex reads the *initial* SSR'd Alpine state,
            // which is usually blank/null — not a reliable live cursor. Real
            // pagination data should come from dispatchedCursor above.
            ?: NEXT_CURSOR_REGEX.find(xData)?.groupValues?.get(1)
            ?: ""

        val hasNextPage = if (nextCursor.isNotBlank()) {
            dispatchedHasMore ?: true
        } else {
            html.selectFirst("div[x-intersect~=loadMore]") != null
        }

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
        resetAnimeListState(slug = "/")

        GET("$baseUrl/", headers)
    } else {
        createLivewireReq(ANIME_SNAPSHOT_KEY, buildJsonObject { }, buildLoadPageCalls(nextCursor), currentSlug)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()

        return if (page == 1) {
            val path = "/anime?search=${java.net.URLEncoder.encode(query, "UTF-8")}&sort=${sortFilter.toUriPart()}"
            resetAnimeListState(slug = path)

            GET("$baseUrl$path", headers)
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

    override fun episodeListRequest(anime: SAnime): Request {
        // Clear any stale episode-list snapshot/state left over from a
        // previously viewed anime before fetching this one. The real
        // snapshot gets populated from the response itself in
        // episodeListParse (via Document.updateState), so nothing needs to
        // be seeded here.
        snapShots[EPISODE_SNAPSHOT_KEY] = ""
        return GET(baseUrl + anime.url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val res = response.retryOn419 { client.newCall(it).execute() }

        val isLivewire = res.request.url.encodedPath.contains("/livewire/update")

        var dispatchedItems: List<EpisodeXData>? = null
        var dispatchedHasMore: Boolean? = null
        var dispatchedCursor: String? = null

        val html = if (isLivewire) {
            val dto = res.parseAs<LivewireDto>()
            val itemsLoaded = dto.components.firstOrNull()
                ?.effects?.dispatches
                ?.firstOrNull { it.name == "items-loaded" }

            dispatchedItems = itemsLoaded?.params?.items.decodeItems<List<EpisodeXData>>()
            dispatchedHasMore = itemsLoaded?.params?.hasMore
            dispatchedCursor = itemsLoaded?.params?.nextCursor

            dto.getHtml(EPISODE_SNAPSHOT_KEY).body()
        } else {
            res.asJsoup().updateState(EPISODE_SNAPSHOT_KEY)
        }

        val xDataContainer = html.selectFirst("[x-data*=items]")
        val xData = xDataContainer?.attr("x-data") ?: ""

        val items = dispatchedItems ?: extractJsonListFromXData<EpisodeXData>(xData, "items", stripFields = listOf("summary"))

        val episodeList = mutableListOf<SEpisode>()

        if (!items.isNullOrEmpty()) {
            episodeList.addAll(items.mapNotNull(::episodeFromXData))
        } else {
            // Note: the <li> markup for episodes lives inside an Alpine
            // <template x-for="..."> block, which Jsoup cannot expand — this
            // selector will never match on pages using this layout. This
            // branch only matters as a genuine "no episodes" fallback for
            // any older/different page structure, not as an HTML-scrape path.
            val allElements = html.select(episodeSelector)
            episodeList.addAll(allElements.mapNotNull(::episodeFromElement))
        }

        // Track added URLs to prevent duplication from cumulative Livewire HTML snapshots
        val seenEpisodeUrls = episodeList.mapTo(mutableSetOf()) { it.url }

        var hasMore = dispatchedHasMore
            ?: (html.selectFirst("div[x-intersect~=loadMore]") != null)

        var cursor = dispatchedCursor
            ?: NEXT_CURSOR_REGEX.find(xData)?.groupValues?.get(1)
            ?: ""

        val updates = buildJsonObject { }

        var iterations = 0
        while (hasMore && cursor.isNotBlank()) {
            if (++iterations > MAX_PAGINATION_ITERATIONS) break
            // Rebuild calls each iteration with the CURRENT cursor as a param
            val calls = buildLoadPageCalls(cursor)

            val resp = newLivewireCall(EPISODE_SNAPSHOT_KEY, updates, calls, response.request.url.encodedPath)
            val liveDto = resp.parseAs<LivewireDto>()
            val liveHtml = liveDto.getHtml(EPISODE_SNAPSHOT_KEY) // always update snapshot state

            val liveDispatch = liveDto.components.firstOrNull()
                ?.effects?.dispatches
                ?.firstOrNull { it.name == "items-loaded" }

            val newItems = liveDispatch?.params?.items.decodeItems<List<EpisodeXData>>()

            val newlyParsed = if (!newItems.isNullOrEmpty()) {
                newItems.mapNotNull(::episodeFromXData)
            } else {
                liveHtml.select(episodeSelector).mapNotNull(::episodeFromElement)
            }

            for (episode in newlyParsed) {
                if (seenEpisodeUrls.add(episode.url)) {
                    episodeList.add(episode)
                }
            }

            val newCursor = liveDispatch?.params?.nextCursor ?: ""
            hasMore = (liveDispatch?.params?.hasMore ?: false) && newCursor != cursor && newCursor.isNotBlank()
            cursor = newCursor
        }

        val (specials, regulars) = episodeList.partition {
            val baseName = it.name.substringBefore(" - ")
            SEASON_REGEX.containsMatchIn(baseName) ||
                baseName.contains("Special", true) ||
                baseName.contains("Recap", true)
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

    private fun episodeFromXData(item: EpisodeXData): SEpisode? {
        val cleanUrl = item.url.toRelativeUrl()

        if (cleanUrl.isBlank()) return null
        val finalPath = "/$cleanUrl"

        // Adjust this if your real baseName format differs (e.g. uses `type` or
        // a separate index field instead of `slug`).
        val baseName = "Episode ${item.slug}"

        val episodeTitle = item.title_list.preferredTitle()

        return SEpisode.create().apply {
            setUrlWithoutDomain(finalPath)

            name = if (!episodeTitle.isNullOrBlank() && episodeTitle != "Unknown") {
                "$baseName - $episodeTitle"
            } else {
                baseName
            }

            episode_number = item.slug.toFloatOrNull() ?: -1f

            date_upload = item.air_date?.let { parseDate(it) } ?: 0L
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    private val playlistUtils: PlaylistUtils by lazy { PlaylistUtils(client, headers) }

    private fun Document.vidstackData(): VidstackConfig? {
        val xData = selectFirst("[x-data*=vidstackPlayer]")?.attr("x-data") ?: return null

        val jsonString = VIDSTACK_REGEX
            .find(xData)
            ?.groupValues?.get(1)
            ?: return null

        val normalizedJson = jsonString
            .replace("""\u0022""", "\"")
            .replace("""\u0026""", "&")
            .replace("""\'""", "'")
            .replace("""\/""", "/")

        return runCatching {
            normalizedJson.parseAs<VidstackConfig>()
        }.getOrNull()
    }
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
            val vidstack = document.vidstackData()

            val subtitles = filterSubs(
                vidstack?.subtitles?.map { Track(it.file.replace("\\/", "/"), it.title) }
                    ?: document.select("track[kind=subtitles]").map {
                        Track(it.attr("src").replace("\\/", "/"), it.attr("label"))
                    },
            )

            val videoUrl = vidstack?.src ?: document.selectFirst("media-player")?.attr("src")

            videoUrl?.also {
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
            val vidstack = doc.vidstackData()

            val subs = filterSubs(
                vidstack?.subtitles?.map { Track(it.file, it.title) }
                    ?: doc.select("track[kind=subtitles]").map {
                        Track(it.attr("src"), it.attr("label"))
                    },
            )

            val videoUrl = vidstack?.src ?: doc.selectFirst("media-player")?.attr("src")

            videoUrl?.also {
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
            Video(video.videoUrl, video.videoTitle, video.videoUrl, video.headers, finalSubs, finalAudio)
        }.filter { video ->
            video.videoTitle.containsLang(audioValue, audioEntry, audioRegex) ||
                video.videoTitle.containsLang(fallbackAudioValue, fallbackAudioEntry, fallbackAudioRegex) ||
                video.audioTracks.isNotEmpty()
        }.ifEmpty { allVideos }
    }

    data class VideoData(
        val url: String,
        val name: String,
        val subtitles: List<Track>,
    )

    @Serializable
    data class AnimeXData(
        val slug: String,
        val url: String,
        val cover: String,
        val main_title: String,
        @Serializable(with = TitleListSerializer::class)
        val title_list: Map<String, String> = emptyMap(),
    )

    object TitleListSerializer : KSerializer<Map<String, String>> {
        override val descriptor = buildClassSerialDescriptor("TitleList")

        override fun deserialize(decoder: Decoder): Map<String, String> {
            val input = decoder as? JsonDecoder ?: error("Only JSON supported")
            val element = input.decodeJsonElement()
            return when (element) {
                is JsonArray -> emptyMap() // PHP's empty-array-as-[] quirk
                is JsonObject -> element.mapValues { it.value.jsonPrimitive.content }
                else -> emptyMap()
            }
        }

        override fun serialize(encoder: Encoder, value: Map<String, String>) {
            error("Serialization not needed")
        }
    }

    @Serializable
    data class EpisodeXData(
        val slug: String,
        val url: String,
        @Serializable(with = TitleListSerializer::class)
        val title_list: Map<String, String> = emptyMap(),
        val summary: String? = null,
        val is_unsafe: Boolean = false,
        val type: String? = null,
        val air_date: String? = null,
        val air_diff: String? = null,
        val videos_count: Int? = null,
        val duration: String? = null,
        val snapshot: String? = null,
        val teaser: String? = null,
    )

    @Serializable
    data class VidstackConfig(
        val src: String,
        val storage: String? = null,
        val snapshot: String? = null,
        val storyboard: String? = null,
        val chapter: String? = null,
        val subtitles: List<VidstackSubtitle> = emptyList(),
        val fonts: List<String> = emptyList(),
    )

    @Serializable
    data class VidstackSubtitle(
        val title: String,
        val file: String,
        val language: String? = null,
        val format: String? = null,
        val default: Boolean = false,
        val forced: String? = null,
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.quality
        val audio = preferences.audio
        val subtitle = preferences.subtitle

        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { it.videoTitle.contains(audio, true) || (audio == "jpn" && (it.videoTitle.contains("jp", true) || it.videoTitle.contains("ja", true))) },
                { it.videoTitle.contains(subtitle, true) || (subtitle == "eng" && (it.videoTitle.contains("en", true))) },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================

    /**
     * Resets all per-list-request state (snapshot, csrf token, dedupe set,
     * cursor) and records [slug] as the page this list's Livewire calls
     * should be scoped to. Call at the start of every page-1 request
     * (popular/latest/search) so a fresh listing never inherits state left
     * over from a previous one.
     */
    private fun resetAnimeListState(slug: String) {
        snapShots[ANIME_SNAPSHOT_KEY] = ""
        token = ""
        seenUrls.clear()
        nextCursor = ""
        currentSlug = slug
    }

    /** Builds the Livewire `calls` payload for requesting the next page via `loadPage`. */
    private fun buildLoadPageCalls(cursor: String): JsonArray = buildJsonArray {
        addJsonObject {
            put("path", "")
            put("method", "loadPage")
            putJsonArray("params") { add(cursor) }
        }
    }

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

        val snapshot = this.getSnapshot()
        snapshot?.let { snapShots[mapKey] = it }

        return this.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]") ?: this.body()
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

        val requestHeaders = headersBuilder().apply {
            add("Accept", "*/*")
            add("Content-Type", "application/json")
            add("X-Livewire", "")
            add("Origin", baseUrl)
            add("Referer", "$baseUrl$initialSlug")
        }.build()

        val payload = buildJsonObject {
            put("_token", token)
            putJsonArray("components") {
                addJsonObject {
                    put("snapshot", snapShots[mapKey] ?: "")
                    put("updates", updates)
                    put("calls", calls)
                }
            }
        }

        val request = POST(
            url = "$baseUrl/livewire/update",
            headers = requestHeaders,
            body = payload.toString().toRequestBody("application/json".toMediaType()),
        )

        return request
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

        val title = titlesMap?.preferredTitle() ?: fallbackTitle

        return title?.clean()
    }

    private fun extractAnimeDict(xData: String): Map<String, Map<String, String>> = extractJsonFromXData<Map<String, Map<String, String>>>(xData, "animeDict") ?: emptyMap()

    private fun resolveTitle(titleList: Map<String, String>?, fallback: String?): String = titleList?.preferredTitle()
        ?: fallback?.clean()
        ?: "Unknown"

    /**
     * Looks up this title map in preference order: the user's configured
     * [PREF_TITLE_LANG_KEY] language, falling back to "1" (English) then
     * "5" (Romaji) — the two keys AniZone's API always populates.
     */
    private fun Map<String, String>.preferredTitle(): String? = this[preferences.preferredTitleLang] ?: this["1"] ?: this["5"]

    private inline fun <reified T> JsonArray?.decodeItems(): T? {
        if (this == null) return null
        return try {
            this.toString().parseAs<T>()
        } catch (_: Exception) {
            null
        }
    }
    private inline fun <reified T> extractJsonFromXData(xData: String, key: String): T? {
        val rawJson = xData.findRawJsonParseArg(key) ?: return null

        return try {
            rawJson.unescapeXDataJson().parseAs<T>()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Same source as [extractJsonFromXData], but for a JSON *array* value
     * (e.g. the "items" list of anime/episodes). Parses each array
     * element independently instead of requiring the whole array to be
     * valid JSON in one shot, and optionally strips [stripFields] out of
     * each element first.
     *
     * AniZone hex-escapes every double-quote character in these x-data
     * payloads uniformly (`\u0022`), with no way to tell a structural
     * delimiter quote from a quote that's just part of the text content
     * (e.g. a quoted phrase inside an episode summary). When an element's
     * text content happens to contain one, unescaping it produces a
     * stray unescaped quote that breaks JSON parsing for that element.
     * [stripFields] should list any long-form text fields the resulting
     * model doesn't actually use (e.g. "summary" on EpisodeXData) — those
     * are the fields most likely to contain a stray quote, and since
     * nothing reads them, removing them before parsing avoids losing the
     * whole element to the ambiguity in the first place. Any element that
     * still fails to parse afterwards (e.g. a *used* field hits the same
     * issue) is dropped via the per-element try/catch rather than
     * silently corrupting the whole list.
     */
    private inline fun <reified T> extractJsonListFromXData(
        xData: String,
        key: String,
        stripFields: List<String> = emptyList(),
    ): List<T>? {
        val rawArray = xData.findRawJsonParseArg(key) ?: return null

        return splitTopLevelJsonObjects(rawArray).mapNotNull { rawElement ->
            val cleaned = stripFields
                .fold(rawElement) { acc, field -> acc.stripXDataTextField(field) }
                .unescapeXDataJson()
                .sanitizeInvalidEscapes()

            try {
                cleaned.parseAs<T>()
            } catch (_: Exception) {
                // add logging here if needed to debug errors
                null
            }
        }
    }

    /** Finds the raw (still-escaped) argument to `JSON.parse('...')` for the x-data key named [key]. */
    private fun String.findRawJsonParseArg(key: String): String? = JSON_PARSE_REGEX.findAll(this).firstOrNull { it.groupValues[1] == key }?.groupValues?.get(2)

    /**
     * Removes a field's value from a raw (still hex-escaped) x-data
     * object, given the field is one [extractJsonListFromXData]'s caller
     * doesn't actually use. Matches on the raw `\u0022key\u0022:\u0022...`
     * tokens directly, *before* the lossy unescape step, so it doesn't
     * need to guess where the field's value ends — it just looks for the
     * next `\u0022,\u0022` (comma before the next key) or `\u0022}` (end
     * of object) boundary. A no-op if the field isn't present.
     */
    private fun String.stripXDataTextField(key: String): String {
        val fieldRegex = Regex(
            """(\\u0022$key\\u0022:\\u0022).*?(\\u0022,\\u0022|\\u0022\})""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return this.replace(fieldRegex, "$1$2")
    }

    private fun String.unescapeXDataJson(): String = this
        .replace("""\u0022""", "\"")
        .replace("""\u0026""", "&")
        .replace("""\'""", "'")
        .replace("""\/""", "/")
        .replace("""\\""", """\""")

    /** Fixes malformed escape sequences commonly found in XData payloads. */
    private fun String.sanitizeInvalidEscapes(): String = this
        .replace("\\&", "&")
        .replace("\\'", "'")
        .replace("\\0", "\\u0000")
        .replace(HEX_ESCAPE) { "\\u%04x".format(it.groupValues[1].toInt(16)) }
        .replace(INVALID_BACKSLASH, "")

    /**
     * Splits a JSON array's raw text ("[{...},{...}]") into the raw text
     * of each top-level object, by tracking brace depth. Deliberately not
     * quote-aware: as noted on [extractJsonListFromXData], there's no
     * reliable way to distinguish a structural quote from a content one
     * in this data anyway. This assumes element content doesn't contain
     * literal '{' or '}' characters, which holds for the anime/episode
     * fields actually parsed here.
     */
    private fun splitTopLevelJsonObjects(rawArray: String): List<String> {
        val body = rawArray.trim().removePrefix("[").removeSuffix("]")
        val chunks = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in body.indices) {
            when (body[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start != -1) {
                        chunks.add(body.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return chunks
    }

    private fun String.extractAndParseJson(prefix: String): JsonElement? {
        val jsonString = this.substringAfter("$prefix: JSON.parse('", "").substringBefore("')")
        if (jsonString.isEmpty()) return null

        val cleanJson = org.jsoup.parser.Parser.unescapeEntities(jsonString, true)
            .replace("\\u0022", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")

        return runCatching { Json.parseToJsonElement(cleanJson) }.getOrNull()
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

    private fun String.toRelativeUrl(): String = this.replace("\\/", "/")
        .replace(DOMAIN_REGEX, "")
        .trimStart('/')
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
        private val DOMAIN_REGEX = Regex("^https?://[^/]+")
        private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")
        private val INVALID_BACKSLASH = Regex("""\\(?!["\\/bfnrt]|u[0-9a-fA-F]{4})""")
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val FALLBACK_TITLE_REGEX = Regex("""getTitle\([^,]+,\s*'([^']+)'\)""")
        private val SLUG_REGEX = Regex("""anmSlug:\s*'([^']+)'""")
        private val SET_VIDEO_REGEX = Regex("""setVideo\(['"]?(\d+)['"]?\)""")

        private val JSON_PARSE_REGEX = Regex("""([a-zA-Z0-9_]+):\s*JSON\.parse\('((?:[^'\\]|\\.)*)'\)""")
        private val VIDSTACK_REGEX = Regex("""JSON\.parse\('(.*?)'\)""")
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val SEASON_REGEX = Regex("(?i)s\\d+")
        private val EPISODE_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")

        private val LANG_REGEX_CACHE = mutableMapOf<String, Regex>()

        private val NEXT_CURSOR_REGEX = Regex("""nextCursor:\s*'([^']+)'""")
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

        private const val ANIME_SNAPSHOT_KEY = "anime_snapshot_key"
        private const val EPISODE_SNAPSHOT_KEY = "episode_snapshot_key"
        private const val VIDEO_SNAPSHOT_KEY = "video_snapshot_key"

        /** Safety cap on episode-list pagination follow-up requests, in case `hasMore`/`nextCursor` never settle. */
        private const val MAX_PAGINATION_ITERATIONS = 50

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
