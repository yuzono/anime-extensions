package eu.kanade.tachiyomi.multisrc.anikototheme

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addListQueryParameter
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addQueryParameterIfNotEmpty
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ResultResponse
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.AniSource
import keiyoushi.source.model.Related
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.source.model.VideoThumbnails
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.rc4
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

abstract class AnikotoTheme(
    private val hosterNames: List<String>,
) : AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    protected val preferences by getPreferencesLazy { clearOldPrefs() }

    protected open val rateLimit = 5

    open val mapperUrl = "https://mapper.nekostream.site/api"

    override val hasRelatedAnime = true

    open val useEpisodeTitles = true

    open val hasSourceFilter = false
    open val hasEpisodeFilter = false

    // ============================ Headers & Client =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Built on each use, so a change of the mirror preference applies at once
    protected val docHeaders: Headers
        get() = headersBuilder().build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(rateLimit)

    // Don't eagerly initialize client in multi-src class since subclass overwrite rateLimit will get 0 instead
    internal val playlistClient by lazy { buildPlaylistClient() }

    internal val playlistUtils by lazy { PlaylistUtils(playlistClient, headers) }

    private fun buildPlaylistClient() = client.newBuilder()
        .readTimeout(30.seconds)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val extractors by lazy { AnikotoExtractor(this) }

    private var discoveredHtmlServersCache: Set<String>? = null
    private var discoveredMapperServersCache: Set<String>? = null

    protected val discoveredServers: Set<String>
        get() {
            if (discoveredHtmlServersCache == null) {
                discoveredHtmlServersCache = preferences.getStringSet(PREF_DISCOVERED_HTML_SERVERS_KEY, null) ?: emptySet()
            }
            if (discoveredMapperServersCache == null) {
                discoveredMapperServersCache = preferences.getStringSet(PREF_DISCOVERED_MAPPER_SERVERS_KEY, null) ?: emptySet()
            }
            val merged = discoveredHtmlServersCache!! + discoveredMapperServersCache!!
            return merged.ifEmpty { hosterNames.toSet() }
        }

    fun updateDiscoveredServers(rawNames: Collection<String>, isMapper: Boolean) {
        val newExact = rawNames.map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (newExact.isEmpty()) return

        val now = System.currentTimeMillis()
        val serverTimestamps = (
            preferences.getStringSet(PREF_SERVER_TIMESTAMPS_KEY, null)
                ?.mapNotNull { entry ->
                    val parts = entry.split("|", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
                }
                ?.mapNotNull { (name, timestamp) -> timestamp?.let { name to it } }
                ?.toMap() ?: emptyMap()
            ).toMutableMap()

        newExact.forEach { serverTimestamps[it] = now }

        if (isMapper) {
            val mergedMapper = discoveredMapperServersCache!! + newExact
            if (mergedMapper != discoveredMapperServersCache) {
                discoveredMapperServersCache = mergedMapper
                preferences.edit().putStringSet(PREF_DISCOVERED_MAPPER_SERVERS_KEY, mergedMapper).apply()
            }
        } else {
            val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)
            val validHtmlServers = (discoveredHtmlServersCache!! + newExact).filter { server ->
                val ts = serverTimestamps[server] ?: 0L
                server in newExact || now - ts < sevenDaysMillis
            }.toSet()

            if (validHtmlServers != discoveredHtmlServersCache) {
                discoveredHtmlServersCache = validHtmlServers
                preferences.edit().putStringSet(PREF_DISCOVERED_HTML_SERVERS_KEY, validHtmlServers).apply()
                cleanStaleExclusions(discoveredServers, PREF_HOSTER_EXCLUDE_KEY)
            }
        }

        val currentValidServers = discoveredHtmlServersCache!! + discoveredMapperServersCache!!
        serverTimestamps.keys.retainAll(currentValidServers)

        preferences.edit().putStringSet(
            PREF_SERVER_TIMESTAMPS_KEY,
            serverTimestamps.map { "${it.key}|${it.value}" }.toSet(),
        ).apply()
    }

    protected open val seedTypes: Set<String> = setOf("Sub", "HSub", "Dub", "H-Sub", "A-Dub")

    private var discoveredTypesCache: Set<String>? = null

    protected val discoveredTypes: Set<String>
        get() {
            if (discoveredTypesCache == null) {
                discoveredTypesCache = preferences.getStringSet(PREF_DISCOVERED_TYPES_KEY, null)
                    ?.takeIf { it.isNotEmpty() }
                    ?: seedTypes
            }
            return discoveredTypesCache!!
        }

    internal fun updateDiscoveredTypes(rawTypes: Collection<String>) {
        val newTypes = rawTypes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (newTypes.isEmpty()) return
        val current = discoveredTypes
        val merged = current + newTypes
        if (merged != current) {
            discoveredTypesCache = merged
            preferences.edit().putStringSet(PREF_DISCOVERED_TYPES_KEY, merged).apply()
        }
        cleanStaleExclusions(merged, PREF_TYPE_EXCLUDE_KEY)
    }

    private fun cleanStaleExclusions(validEntries: Set<String>, exclusionKey: String) {
        val currentExcluded = preferences.getStringSet(exclusionKey, null)?.toSet() ?: emptySet()
        val validExcluded = currentExcluded.filter { it in validEntries }.toSet()
        if (validExcluded.size != currentExcluded.size) {
            preferences.edit().putStringSet(exclusionKey, validExcluded).apply()
        }
    }

    protected open fun extractBaseServerName(rawName: String): String = rawName.replace(Regex("-\\*\\d+\\s*$"), "").trimEnd('-', ' ').trim()

    protected open fun getHosterDisplayName(baseName: String): String = baseName

    protected open fun getTypeDisplayName(typeKey: String): String = when (typeKey) {
        "Sub" -> "Sub"
        "H-Sub" -> "H-Sub"
        "S-Sub" -> "Soft Sub"
        "Dub" -> "Dub"
        "A-Dub" -> "A-Dub"
        else -> typeKey
    }

    val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    private val excludedHosts: Set<String> by preferences.delegate(PREF_HOSTER_EXCLUDE_KEY, emptySet())
    private val excludedTypes: Set<String> by preferences.delegate(PREF_TYPE_EXCLUDE_KEY, emptySet())

    val hostToggle: Set<String> get() = discoveredServers - excludedHosts
    val typeToggle: Set<String> get() = discoveredTypes - excludedTypes

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("most-viewed")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url, docHeaders, cacheControl).useAsJsoup()
        return document.toAnimesPage(popularAnimeSelector(), popularAnimeNextPageSelector(), ::popularAnimeFromElement)
    }

    private fun Document.toAnimesPage(
        selector: String,
        nextPageSelector: String,
        fromElement: (Element) -> SAnime,
    ): AnimesPage {
        val animes = select(selector).mapNotNull { runCatching { fromElement(it) }.getOrNull() }
        return AnimesPage(animes, selectFirst(nextPageSelector) != null)
    }

    open fun popularAnimeSelector(): String = "div.ani.items > div.item"

    open fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a.name")?.let { a ->
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(a.attr("href").substringBefore("?"), ""))
            title = getTitle(a)
        }
        thumbnail_url = element.selectFirst(listingThumbnailSelector)?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    open fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("latest-updated")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url, docHeaders, cacheControl).useAsJsoup()
        return document.toAnimesPage(latestUpdatesSelector(), latestUpdatesNextPageSelector(), ::latestUpdatesFromElement)
    }

    open fun latestUpdatesSelector() = popularAnimeSelector()
    open fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    open fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val document = client.get(searchAnimeUrl(page, query, filters), docHeaders, cacheControl).useAsJsoup()
        return document.toAnimesPage(searchAnimeSelector(), searchAnimeNextPageSelector(), ::searchAnimeFromElement)
    }

    protected open fun searchAnimeUrl(page: Int, query: String, filters: AnimeFilterList): String {
        val params = AnikotoThemeFilters.getSearchParameters(filters)
        val vrf = if (query.isNotEmpty()) vrfEncrypt(query) else ""

        return baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("vrf", vrf)

            addListQueryParameter("genre", params.genres)
            addListQueryParameter("season", params.seasons)
            addListQueryParameter("year", params.years)
            addListQueryParameter("term_type", params.types)
            addListQueryParameter("status", params.statuses)
            addListQueryParameter("language", params.languages)
            addListQueryParameter("rating", params.ratings)
            addListQueryParameter("source", params.sources)
            addQueryParameterIfNotEmpty("ep_min", params.episodesMin)
            addQueryParameterIfNotEmpty("ep_max", params.episodesMax)
            addQueryParameterIfNotEmpty("sort", params.sort)
        }.build().toString()
    }

    open fun searchAnimeSelector() = popularAnimeSelector()
    open fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    open fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun getFilterList(): AnimeFilterList = AnikotoThemeFilters.getFilterList(hasSourceFilter, hasEpisodeFilter)

    // =========================== Anime Details ============================

    open fun parseAnimeDetails(document: Document): SAnime {
        val titleElement = document.selectFirst("h1.title, h2.title")
        val animeId = document.selectFirst("[data-id]")?.attr("data-id")
            ?: document.selectFirst("[data-tip]")?.attr("data-tip")

        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            titleElement?.let { getTitle(it) }?.takeIf { it.isNotEmpty() }?.let { title = it }
            genre = document.select("div:contains(Genres) > span > a").joinToString { it.text() }
            author = document.select("div:contains(Studios) > span > a").joinToString { it.text() }
            status = parseStatus(document.select("div:contains(Status) > span").text())
            description = buildDescription(document, titleElement)

            if (detailThumbnailSelector.isNotEmpty()) {
                document.selectFirst(detailThumbnailSelector)?.let { img ->
                    val url = img.attr("data-src").ifBlank { img.attr("src") }
                    if (url.isNotEmpty()) thumbnail_url = url
                }
            }
        }
    }

    // ============================== Related ===============================

    override suspend fun fetchRelatedAnime(anime: SAnime): List<Related> {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")

        val (pageUrl, document) = client.get(baseUrl + animeUrl, docHeaders).let { it.request.url to it.asJsoup() }
        val currentAnimePath = pageUrl.encodedPath

        return try {
            val id = animeId.ifBlank { relatedAnimeId(document).orEmpty() }
            var watchOrder = if (id.isNotBlank()) fetchWatchOrder(id, pageUrl, currentAnimePath) else emptyList()
            var recommended = recommendedItems(document).mapNotNull {
                relatedAnimeFromElement(it, recommendedHref(it), currentAnimePath)
            }

            if (distinctRelated) {
                watchOrder = watchOrder.distinctBy { it.url }
                val seen = watchOrder.mapTo(mutableSetOf()) { it.url }
                recommended = recommended.filter { seen.add(it.url) }
            }

            listOf(Related("Watch order", watchOrder), Related("Recommended", recommended))
                .filter { it.animes.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse related anime", e)
            emptyList()
        }
    }

    private suspend fun fetchWatchOrder(animeId: String, pageUrl: HttpUrl, currentAnimePath: String): List<SAnime> = try {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", pageUrl.toString())
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        client.get("$baseUrl/api/watch-order/$animeId", listHeaders)
            .parseAs<ResultResponse>()
            .toDocument()
            .select(watchOrderItemSelector)
            .mapNotNull { relatedAnimeFromElement(it, watchOrderHref(it), currentAnimePath) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun relatedAnimeFromElement(element: Element, href: String?, currentAnimePath: String): SAnime? {
        val path = extractAnimePath(href?.substringBefore("?")?.trim()) ?: return null
        if (path == currentAnimePath) return null
        val nameElement = element.selectFirst(".info .name") ?: return null
        return SAnime.create().apply {
            url = path
            title = getTitle(nameElement)
            thumbnail_url = extractRelatedThumbnail(element)
        }
    }

    protected open val distinctRelated = false

    protected open fun relatedAnimeId(document: Document): String? = document.selectFirst("[data-id]")?.attr("data-id")
        ?: document.selectFirst("[data-tip]")?.attr("data-tip")

    protected open fun watchOrderHref(element: Element): String? = element.selectFirst("a[href*=/watch/]")?.attr("href")
        ?: element.attr("href").takeIf { it.contains("/watch/") }

    protected open fun recommendedItems(document: Document): List<Element> = document.select(recommendedSectionSelector)
        .firstOrNull { it.select(".head .title").text().equals("Recommended", ignoreCase = true) }
        ?.select("a.item")
        .orEmpty()

    protected open fun recommendedHref(element: Element): String? = element.attr("href")

    // ============================== Episodes ==============================

    open fun episodeListSelector() = "div.episodes ul > li > a"

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate = coroutineScope {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")

        val needsPage = fetchDetails || (fetchEpisodes && animeId.isBlank())
        val page = if (needsPage) async { fetchAnimePage(animeUrl) } else null

        val details = if (fetchDetails) async { parseAnimeDetails(page!!.await()) } else null
        val episodeList = if (fetchEpisodes) {
            async {
                val id = animeId.ifBlank {
                    page!!.await().let { doc ->
                        doc.selectFirst("[data-id]")?.attr("data-id")
                            ?: doc.selectFirst("[data-tip]")?.attr("data-tip")
                            ?: throw IllegalStateException("Anime ID not found")
                    }
                }
                fetchEpisodeList(id, animeUrl)
            }
        } else {
            null
        }

        SAnimeUpdate(details?.await() ?: anime, episodeList?.await() ?: episodes)
    }

    private suspend fun fetchAnimePage(animeUrl: String): Document = resolveSearchAnime(client.get(baseUrl + animeUrl, docHeaders).useAsJsoup())

    private suspend fun fetchEpisodeList(animeId: String, animeUrl: String): List<SEpisode> {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + animeUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val response = client.get("$baseUrl/ajax/episode/list/$animeId?vrf=${vrfEncrypt(animeId)}", listHeaders)

        return try {
            val episodeAnimeUrl = (baseUrl + animeUrl).toHttpUrl().encodedPath
            response.parseAs<ResultResponse>().toDocument().select(episodeListSelector())
                .map { episodeFromElement(it, episodeAnimeUrl) }
                .reversed()
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse episodes: ${e.message}")
            emptyList()
        }
    }

    open fun isFiller(element: Element): Boolean = element.hasClass("filler")

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val tooltip = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toIntOrNull() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toIntOrNull() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.containsMatchIn(tooltip)) "SoftSub" else ""

        var name = element.parent()?.select("span.d-title")?.text().orEmpty()
        if (useEpisodeTitles && name.isEmpty() && tooltip.isNotEmpty()) {
            name = tooltip.substringBefore("Release:").substringBefore("Softsub").trim()
        }

        val malId = element.attr("data-mal")
        val slug = element.attr("data-slug")
        val timestamp = element.attr("data-timestamp").toLongOrNull()

        return SEpisode.create().apply {
            this.name = "Episode $epNum" + (if (name.isNotEmpty() && name != "Episode $epNum") ": $name" else "")
            fillermark = isFiller(element)
            this.url = buildString {
                append("$ids&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/ep-$epNum")
                if (malId.isNotEmpty()) append("&mal=$malId")
                if (slug.isNotEmpty()) append("&slug=$slug")
                if (timestamp != null) append("&ts=$timestamp")
            }
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = timestamp?.let { it * 1000 }
                ?: DATE_FORMATTER.tryParse(RELEASE_REGEX.find(tooltip)?.groupValues?.get(1))
            scanlator = listOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(" & ")
        }
    }

    // ============================ Video List ==============================

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
    )

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val isServerInvalid = preferences.getBoolean(PREF_SERVER_INVALID_FLAG, false) ||
            (prefServer.isNotEmpty() && prefServer !in discoveredServers && prefServer !in hosterNames)

        if (isServerInvalid) {
            preferences.edit().putBoolean(PREF_SERVER_INVALID_FLAG, true).apply()
            throw Exception("The site's video servers have changed. Please open the extension settings to update your Preferred Server.")
        }

        val ids = episode.url.substringBefore("&")
        val epurlPart = episode.url.substringAfter("epurl=").substringBefore("&")

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl$epurlPart")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val epUrl = try {
            "$baseUrl$epurlPart".toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        val response = client.get("$baseUrl/ajax/server/list?servers=$ids", listHeaders)

        val document = try {
            response.parseAs<ResultResponse>().toDocument()
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse video list: ${e.message}")
            return emptyList()
        }

        return extractors.getServerData(document, episode).map { server ->
            Hoster(
                hosterUrl = server.serverId,
                hosterName = server.serverName,
                internalData = "${server.type}$INTERNAL_DATA_SEPARATOR$epUrl",
            )
        }
    }

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.split(INTERNAL_DATA_SEPARATOR, limit = 2)
        if (data.size != 2) return emptyList()

        val serverType = data[0]
        val epUrl = data[1]
        val server = VideoData(type = serverType, serverId = hoster.hosterUrl, serverName = hoster.hosterName)

        return extractors.extractVideo(server, epUrl).markPreferred(server)
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url.substringBefore("#")

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url.substringAfter("epurl=").substringBefore("&")

    // The preferred quality of the preferred server and type, so the app picks it
    private fun List<Video>.markPreferred(server: VideoData): List<Video> {
        if (buildTypeFallbackChain(prefType).none { it.equals(server.type, ignoreCase = true) }) return this
        val preferredBase = extractBaseServerName(prefServer)
        val isPreferredServer = server.serverName.equals(prefServer, ignoreCase = true) ||
            extractBaseServerName(server.serverName).equals(preferredBase, ignoreCase = true)
        if (!isPreferredServer) return this

        val preferredIndex = indexOfFirst { it.videoTitle.contains(prefQuality) }.takeIf { it >= 0 } ?: 0
        return mapIndexed { index, video -> video.copy(preferred = index == preferredIndex) }
    }

    override suspend fun fetchVideoThumbnails(video: Video): VideoThumbnails? {
        val vttUrl = video.internalData.takeIf { it.isNotBlank() } ?: return null
        return VideoThumbnails.fromVtt(client.get(vttUrl, video.headers ?: headers))
    }

    // =========================== Hoster Sort ===============================

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredServer = prefServer
        val preferredBase = extractBaseServerName(prefServer)
        val sortType = buildTypeFallbackChain(prefType)

        return sortedWith(
            compareByDescending<Hoster> { hoster ->
                val type = hoster.internalData.substringBefore(INTERNAL_DATA_SEPARATOR)
                sortType.any { it.equals(type, ignoreCase = true) }
            }.thenByDescending { hoster ->
                when {
                    hoster.hosterName.equals(preferredServer, ignoreCase = true) -> 2
                    extractBaseServerName(hoster.hosterName).equals(preferredBase, ignoreCase = true) -> 1
                    else -> 0
                }
            },
        )
    }

    // ============================ Video Sort ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = prefQuality
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.videoTitle.contains(it) } },
        )
    }

    protected open fun buildTypeFallbackChain(type: String): List<String> = when (type) {
        "Sub" -> listOf("Sub", "H-Sub", "HSub")
        "H-Sub", "HSub" -> listOf("H-Sub", "HSub")
        "S-Sub" -> listOf("S-Sub", "Sub")
        "Dub" -> listOf("Dub", "A-Dub")
        "A-Dub" -> listOf("A-Dub", "Dub")
        else -> listOf(type)
    }

    // =================== Protected Open Selector Properties ===============

    protected open val watchOrderItemSelector = "div.item.flexserieslist"
    protected open val listingThumbnailSelector = "div.poster img"
    protected open val metaContainerSelector = "div.bmeta"
    protected open val scoreLabelName = "MAL"
    protected open val aliasContainerSelector = "div.names.font-italic"
    protected open val metaExclusionLabels = listOf("Genres", "Status", "Studios", "Producers", "MAL")
    protected open val recommendedSectionSelector = "section.w-side-section"
    protected open val synopsisContentSelector = "div.synopsis > div.shorting > div.content"
    protected open val detailThumbnailSelector = ""

    // ========================= Protected Open Helpers =====================

    protected open fun vrfEncrypt(input: String): String = AnikotoUtils.vrfEncrypt(input)

    protected open fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.takeIf { it.isNotEmpty() }
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf { it.isNotEmpty() }
        val malScore = document.select("$metaContainerSelector div.meta > div").firstOrNull {
            it.ownText().removeSuffix(":").equals(scoreLabelName, ignoreCase = true)
        }?.select("span")?.text()

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) appendLine(fancyScore).appendLine()

        document.selectFirst(synopsisContentSelector)?.text()?.let {
            appendLine(it).appendLine()
        }

        val meta = document.select("$metaContainerSelector div.meta > div").mapNotNull { div ->
            val label = div.ownText().removeSuffix(":").removeSuffix(" ")
            var value = div.select("span").text()
            if (label.equals("Duration", ignoreCase = true)) {
                value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { value = "$it min" }
            }
            if (label.isNotEmpty() && value.isNotEmpty() && label !in metaExclusionLabels) {
                "$label: $value"
            } else {
                null
            }
        }

        if (meta.isNotEmpty()) appendLine(meta.joinToString(" | ")).appendLine()

        val studios = document.select("div:contains(Studios) > span > a").joinToString { it.text() }
        val producers = document.select("div:contains(Producers) > span > a").joinToString { it.text() }

        when {
            studios.isNotEmpty() && producers.isNotEmpty() -> appendLine("**Studio:** $studios (**Producers:** $producers)").appendLine()
            studios.isNotEmpty() -> appendLine("**Studio:** $studios").appendLine()
            producers.isNotEmpty() -> appendLine("**Producers:** $producers").appendLine()
        }

        val altNames = mutableListOf<String>()
        if (useEnglish()) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }
        document.selectFirst(aliasContainerSelector)?.text()?.takeIf { it.isNotEmpty() }?.let { namesText ->
            altNames.addAll(namesText.split(";").map { it.trim() }.filter { it.isNotEmpty() && it != jpTitle && it != enTitle })
        }
        if (altNames.isNotEmpty()) appendLine("**Other name(s):** ${altNames.joinToString()}").appendLine()

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) append(fancyScore)
    }.trim()

    protected open suspend fun resolveSearchAnime(document: Document): Document {
        if (document.location().startsWith("$baseUrl/filter?keyword=")) {
            val foundAnimePath = document.selectFirst(searchAnimeSelector())?.selectFirst("a[href]")?.attr("href")
                ?: throw IllegalStateException("Search element not found")
            val resolveAnimePath = EP_URL_SUFFIX_REGEX.replace(foundAnimePath, "")
            return client.get(baseUrl + resolveAnimePath, docHeaders).useAsJsoup()
        }
        return document
    }

    protected open fun extractAnimePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val path = try {
            href.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return null
        }
        return EP_URL_SUFFIX_REGEX.replace(path, "").takeIf { it.startsWith("/watch/") }
    }

    open fun resolveTypeLabel(typeElem: Element): String {
        val labelText = typeElem.selectFirst("label")?.text().orEmpty()
        val dataType = typeElem.attr("data-type")

        return when (labelText.lowercase()) {
            "sub" -> "Sub"
            "h-sub" -> "H-Sub"
            "hsub" -> "HSub"
            "dub" -> "Dub"
            "a-dub", "adub" -> "A-Dub"
            "s-sub" -> "S-Sub"
            else -> when (dataType.lowercase()) {
                "sub" -> "Sub"
                "hsub" -> "HSub"
                "dub" -> "Dub"
                "adub" -> "A-Dub"
                "" -> if (labelText.isNotEmpty()) labelText.replaceFirstChar { it.uppercase() } else "Unknown"
                else -> dataType.replaceFirstChar { it.uppercase() }
            }
        }
    }

    open fun parseServerListData(document: Document): List<VideoData> {
        val typeElements = document.select("div.servers > div.type")

        val allTypes = typeElements.mapNotNull { elem ->
            resolveTypeLabel(elem).takeIf { it.isNotEmpty() && it != "Unknown" }
        }.toSet()
        updateDiscoveredTypes(allTypes)

        typeElements.flatMap { elem ->
            elem.select("li")
                .filter { !it.hasClass("download-icon") }
                .mapNotNull { it.text().takeIf { it.isNotEmpty() } }
        }.also { updateDiscoveredServers(it, isMapper = false) }

        val effectiveTypeToggle = typeToggle
        val effectiveHostToggle = hostToggle

        return typeElements.flatMap { elem ->
            val label = resolveTypeLabel(elem)

            if (!isTypeEnabled(label, effectiveTypeToggle)) return@flatMap emptyList()

            elem.select("li").mapNotNull { serverElement ->
                if (serverElement.hasClass("download-icon")) return@mapNotNull null

                val serverId = serverElement.attr("data-link-id")
                if (serverId.isEmpty()) return@mapNotNull null

                val serverName = serverElement.text()

                if (!effectiveHostToggle.contains(serverName, true)) return@mapNotNull null

                VideoData(label, serverId, serverName)
            }
        }
    }

    open fun getServerDisplayName(serverName: String): String = serverName.trimEnd('-', ' ')

    protected open fun extractRelatedThumbnail(element: Element): String? = element.selectFirst("img")?.attr("src")

    internal open fun mapMapperServerName(key: String): String = when {
        key.equals("gogoanime", true) -> "Vidstream"
        key.equals("anivibe", true) -> "Vibe-Stream"
        key.equals("animepahe", true) -> "Kiwi-Stream"
        key.startsWith("Kiwi-Stream", true) -> "Kiwi-Stream"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    internal fun cleanHlsQuality(quality: String): String = quality.substringBefore(" (").substringBefore(" - ")

    fun isTypeEnabled(label: String, typeSelection: Set<String>): Boolean = typeSelection.any { it.equals(label, ignoreCase = true) }

    fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    // ============================ Shared Utilities ========================

    protected open fun getTitle(element: Element): String {
        val enTitle = element.text().takeIf { it.isNotEmpty() }
        val jpTitle = element.attr("data-jp").trim().takeIf { it.isNotEmpty() }
        return if (useEnglish()) {
            enTitle ?: jpTitle ?: element.text()
        } else {
            jpTitle ?: enTitle ?: element.text()
        }
    }

    protected open fun parseStatus(statusString: String): Int = when (statusString.lowercase()) {
        "ongoing anime", "currently airing" -> SAnime.ONGOING
        "finished airing", "completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    protected open fun getFancyScore(score: String?): String {
        if (score.isNullOrBlank()) return ""
        return try {
            val scoreBig = score.toBigDecimal()
            if (scoreBig.signum() <= 0) return ""
            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP).toInt().coerceIn(0, 5)
            "★".repeat(stars) + "☆".repeat(5 - stars) + " " + scoreBig.stripTrailingZeros().toPlainString()
        } catch (_: Exception) {
            ""
        }
    }

    protected fun useEnglish() = getTitleLang == "English"

    protected val getTitleLang by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)
    protected val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    protected val prefServer by preferences.delegate(PREF_SERVER_KEY, hosterNames.firstOrNull() ?: "")
    protected val prefType by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)
    protected val scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    // ============================== Preferences ===========================

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        try {
            val validServers = (
                (getStringSet(PREF_DISCOVERED_HTML_SERVERS_KEY, null) ?: emptySet()) +
                    (getStringSet(PREF_DISCOVERED_MAPPER_SERVERS_KEY, null) ?: emptySet())
                ).takeIf { it.isNotEmpty() } ?: hosterNames.toSet()

            val oldHosterSelection = getStringSet("hoster_selection", null)?.toSet()
            if (oldHosterSelection != null) {
                val newExclusion = validServers - oldHosterSelection.filter { it in validServers || it in hosterNames }.toSet()
                edit().putStringSet(PREF_HOSTER_EXCLUDE_KEY, newExclusion).remove("hoster_selection").apply()
            }
            val currentExcludedHosts = getStringSet(PREF_HOSTER_EXCLUDE_KEY, null)?.toSet() ?: emptySet()
            val invalidHosters = currentExcludedHosts.any { it !in validServers && it !in hosterNames }

            val validTypes = (
                getStringSet(PREF_DISCOVERED_TYPES_KEY, null)
                    ?.takeIf { it.isNotEmpty() }
                    ?.toSet()
                    ?: seedTypes
                )

            val oldTypeSelection = getStringSet("type_selection", null)?.toSet()
            if (oldTypeSelection != null) {
                val validOldTypes = oldTypeSelection.filter { it in validTypes || it == "H-Sub" }.toSet()
                val newExclusion = validTypes - validOldTypes
                edit().putStringSet(PREF_TYPE_EXCLUDE_KEY, newExclusion).remove("type_selection").apply()
            }
            val currentExcludedTypes = getStringSet(PREF_TYPE_EXCLUDE_KEY, null)?.toSet() ?: emptySet()
            val invalidTypes = currentExcludedTypes.any { it !in validTypes }

            val savedPrefType = getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
            val invalidPrefType = savedPrefType !in validTypes

            val savedPrefServer = getString(PREF_SERVER_KEY, null)
            val invalidPrefServer = savedPrefServer != null &&
                savedPrefServer !in validServers &&
                savedPrefServer !in hosterNames

            if (invalidHosters || invalidTypes || invalidPrefType || invalidPrefServer) {
                edit().also { editor ->
                    if (invalidHosters) {
                        editor.putStringSet(
                            PREF_HOSTER_EXCLUDE_KEY,
                            currentExcludedHosts.filter { it in validServers || it in hosterNames }.toSet(),
                        )
                    }
                    if (invalidTypes) {
                        editor.putStringSet(
                            PREF_TYPE_EXCLUDE_KEY,
                            currentExcludedTypes.filter { it in validTypes }.toSet(),
                        )
                    }
                    if (invalidPrefType) editor.putString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)
                    if (invalidPrefServer) {
                        editor.putBoolean(PREF_SERVER_INVALID_FLAG, true)
                    }
                }.apply()
            }
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to clear old prefs", e)
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (preferences.getBoolean(PREF_SERVER_INVALID_FLAG, false)) {
            preferences.edit()
                .putBoolean(PREF_SERVER_INVALID_FLAG, false)
                .putString(PREF_SERVER_KEY, discoveredServers.firstOrNull() ?: hosterNames.firstOrNull() ?: "")
                .apply()
        }

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = PREF_TITLE_LANG_ENTRIES
            entryValues = PREF_TITLE_LANG_ENTRIES
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = PREF_QUALITY_DISPLAY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        val preferredServerEntries = discoveredServers.map { getHosterDisplayName(it) }.toTypedArray()
        val preferredServerValues = discoveredServers.toTypedArray()

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = preferredServerEntries
            entryValues = preferredServerValues
            setDefaultValue(preferredServerValues.firstOrNull() ?: "")
            summary = "%s"
        }.also(screen::addPreference)

        val typeEntries = discoveredTypes.map { getTypeDisplayName(it) }.toTypedArray()
        val typeValues = discoveredTypes.toTypedArray()

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred Type"
            entries = typeEntries
            entryValues = typeValues
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score Display Position"
            entries = PREF_SCORE_POSITION_ENTRIES
            entryValues = PREF_SCORE_POSITION_VALUES
            setDefaultValue(PREF_SCORE_POSITION_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        val excludeServerEntries = discoveredServers.map { getHosterDisplayName(it) }.toTypedArray()
        val excludeServerValues = discoveredServers.toTypedArray()

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_EXCLUDE_KEY
            title = "Exclude Servers"
            summary = "Choose which exact servers you want to exclude"
            entries = excludeServerEntries
            entryValues = excludeServerValues
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_EXCLUDE_KEY
            title = "Exclude Types"
            summary = "Choose which video types you want to exclude"
            entries = typeEntries
            entryValues = typeValues
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)
    }

    companion object {
        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)

        private const val INTERNAL_DATA_SEPARATOR = "\u0000"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_ENTRIES = arrayOf("English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_DISPLAY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES[0]

        private const val PREF_HOSTER_EXCLUDE_KEY = "hoster_exclusion"
        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_TYPE_EXCLUDE_KEY = "type_exclusion"
        private const val PREF_TYPE_KEY = "preferred_language"
        private const val PREF_TYPE_DEFAULT = "Sub"

        const val SCORE_POS_TOP = "top"
        const val SCORE_POS_BOTTOM = "bottom"
        const val SCORE_POS_NONE = "none"

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = arrayOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = arrayOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)

        private const val PREF_DISCOVERED_TYPES_KEY = "discovered_types"
        private const val PREF_DISCOVERED_HTML_SERVERS_KEY = "discovered_html_servers"
        private const val PREF_DISCOVERED_MAPPER_SERVERS_KEY = "discovered_mapper_servers"
        private const val PREF_SERVER_TIMESTAMPS_KEY = "server_timestamps"
        private const val PREF_SERVER_INVALID_FLAG = "server_invalid_flag"
    }
}

// =============================== VRF ==================================

private object AnikotoUtils {
    fun vrfEncrypt(input: String): String {
        var vrf = input
        ORDER.forEach { item ->
            when (item.second) {
                "exchange" -> vrf = exchange(vrf, item.third)
                "rc4" -> vrf = rc4Encrypt(item.third[0], vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.encode(vrf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
            }
        }
        return java.net.URLEncoder.encode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val output = rc4(key.toByteArray(), input.toByteArray())
        return Base64.encode(output, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, keys: List<String>): String {
        val key1 = keys[0]
        val key2 = keys[1]
        return input.map { i ->
            val idx = key1.indexOf(i)
            if (idx != -1) key2[idx] else i
        }.joinToString("")
    }

    private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
    private const val KEY_1 = "ItFKjuWokn4ZpB"
    private const val KEY_2 = "fOyt97QWFB3"
    private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
    private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
    private const val KEY_3 = "736y1uTJpBLUX"

    private val ORDER = listOf(
        Triple(1, "exchange", EXCHANGE_KEY_1),
        Triple(2, "rc4", listOf(KEY_1)),
        Triple(3, "rc4", listOf(KEY_2)),
        Triple(4, "exchange", EXCHANGE_KEY_2),
        Triple(5, "exchange", EXCHANGE_KEY_3),
        Triple(6, "reverse", emptyList()),
        Triple(7, "rc4", listOf(KEY_3)),
        Triple(8, "base64", emptyList()),
    )
}
