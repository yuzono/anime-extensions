package eu.kanade.tachiyomi.multisrc.anikototheme

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addListQueryParameter
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addQueryParameterIfNotEmpty
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ResultResponse
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ServerResponseDto
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.SourceResponseDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours

abstract class AnikotoTheme(
    override val lang: String,
    override val name: String,
    private val domainEntries: List<String>,
    private val hosterNames: List<String>,
    private val hosterDisplayNames: List<String> = hosterNames.map { it.replaceFirstChar { c -> c.uppercase() } },
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    private val context: Application by injectLazy()

    override val supportsLatest = true

    private val defaultBaseUrl = "https://${domainEntries.first()}"

    protected val preferences by getPreferencesLazy { clearOldPrefs() }

    override var baseUrl: String by preferences.delegate(PREF_DOMAIN_KEY, defaultBaseUrl)

    private val domainValues = domainEntries.map { "https://$it" }

    protected open val rateLimit = 5

    protected var docHeaders by LazyMutable { headersBuilder().build() }

    override var client: OkHttpClient by LazyMutable {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = rateLimit, period = 1L, unit = TimeUnit.SECONDS)
            .build()
    }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    private val utils by lazy { AnikotoUtils() }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val kwikExtractor by lazy { KwikExtractor(client, headers, context) }

    // ============================ Headers & Client =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("most-viewed")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        docHeaders,
        cacheControl,
    )

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a.name")?.let { a ->
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(a.attr("href").substringBefore("?"), ""))
            title = getTitle(a)
        }
        thumbnail_url = element.selectFirst(listingThumbnailSelector)?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li.active ~ li"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).mapNotNull {
            runCatching { popularAnimeFromElement(it) }.getOrNull()
        }
        val nextPage = popularAnimeNextPageSelector().let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("latest-updated")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        docHeaders,
        cacheControl,
    )

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector()).mapNotNull {
            runCatching { latestUpdatesFromElement(it) }.getOrNull()
        }
        val nextPage = latestUpdatesNextPageSelector().let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnikotoThemeFilters.getSearchParameters(filters)
        val vrf = if (query.isNotBlank()) vrfEncrypt(query) else ""

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("vrf", vrf)

            addListQueryParameter("genre", params.genres)
            addListQueryParameter("season", params.seasons)
            addListQueryParameter("year", params.years)
            addListQueryParameter("type", params.types)
            addListQueryParameter("status", params.statuses)
            addListQueryParameter("language", params.languages)
            addListQueryParameter("rating", params.ratings)
            addQueryParameterIfNotEmpty("sort", params.sort)
        }.build().toString()

        return GET(url, docHeaders, cacheControl)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).mapNotNull {
            runCatching { searchAnimeFromElement(it) }.getOrNull()
        }
        val nextPage = searchAnimeNextPageSelector().let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnikotoThemeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.title, h2.title")
        val animeId = newDocument.selectFirst("[data-id]")?.attr("data-id")
            ?: newDocument.selectFirst("[data-tip]")?.attr("data-tip")

        return SAnime.create().apply {
            setUrlWithoutDomain(newDocument.location())
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            titleElement?.let { getTitle(it) }?.takeIf(String::isNotBlank)?.let { title = it }
            genre = newDocument.select("div:contains(Genres) > span > a").joinToString(", ") { it.text().trim() }
            author = newDocument.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
            status = parseStatus(newDocument.select("div:contains(Status) > span").text())
            description = buildDescription(newDocument, titleElement)

            if (detailThumbnailSelector.isNotBlank()) {
                newDocument.selectFirst(detailThumbnailSelector)?.let { img ->
                    val url = img.attr("data-src").ifBlank { img.attr("src") }
                    if (url.isNotBlank()) thumbnail_url = url
                }
            }
        }
    }

    // ============================== Related ===============================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")
        return if (animeId.isNotBlank()) {
            GET(baseUrl + animeUrl, docHeaders).newBuilder()
                .header("X-Anime-Id", animeId).build()
        } else {
            GET(baseUrl + animeUrl, docHeaders)
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        return try {
            val document = response.asJsoup()
            val currentAnimePath = response.request.url.encodedPath
            val animeId = response.request.header("X-Anime-Id")
                ?: document.selectFirst("[data-id]")?.attr("data-id")
                ?: document.selectFirst("[data-tip]")?.attr("data-tip")
            val resultList = mutableListOf<SAnime>()

            if (!animeId.isNullOrBlank()) {
                try {
                    val listHeaders = headers.newBuilder().apply {
                        add("Accept", "application/json, text/javascript, */*; q=0.01")
                        add("Referer", response.request.url.toString())
                        add("X-Requested-With", "XMLHttpRequest")
                    }.build()

                    client.newCall(GET("$baseUrl/api/watch-order/$animeId", listHeaders)).execute().use { apiResponse ->
                        val relatedDoc = apiResponse.parseAs<ResultResponse>().toDocument()
                        relatedDoc.select(watchOrderItemSelector).forEach { element ->
                            val href = element.selectFirst("a[href*=/watch/]")?.attr("href")
                                ?: element.attr("href").takeIf { it.contains("/watch/") }
                                ?: return@forEach
                            val path = extractAnimePath(href.substringBefore("?").trim()) ?: return@forEach
                            if (path == currentAnimePath) return@forEach
                            val nameElement = element.selectFirst(".info .name") ?: return@forEach
                            resultList.add(
                                SAnime.create().apply {
                                    url = path
                                    title = getTitle(nameElement).trim()
                                    thumbnail_url = extractRelatedThumbnail(element)
                                },
                            )
                        }
                    }
                } catch (_: Exception) { }
            }

            document.select(recommendedSectionSelector).firstOrNull {
                it.select(".head .title").text().equals("Recommended", ignoreCase = true)
            }?.select("a.item")?.forEach { element ->
                val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@forEach
                if (path == currentAnimePath) return@forEach
                val nameElement = element.selectFirst(".info .name") ?: return@forEach
                resultList.add(
                    SAnime.create().apply {
                        url = path
                        title = getTitle(nameElement).trim()
                        thumbnail_url = extractRelatedThumbnail(element)
                    },
                )
            }
            resultList
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse related anime", e)
            emptyList()
        }
    }

    /**
     * Parses related anime from the watch-order API response.
     * Override this if the site uses a different HTML structure.
     */
    protected open fun parseWatchOrderRelated(document: Document, currentAnimePath: String): List<SAnime> {
        val resultList = mutableListOf<SAnime>()
        document.select("div.item.flexserieslist").forEach { element ->
            val href = element.selectFirst("a[href*=/watch/]")?.attr("href")?.substringBefore("?")?.trim() ?: return@forEach
            val path = extractAnimePath(href) ?: return@forEach
            if (path == currentAnimePath) return@forEach
            val nameElement = element.selectFirst(".info .name") ?: return@forEach
            resultList.add(
                SAnime.create().apply {
                    url = path
                    title = getTitle(nameElement).trim()
                    thumbnail_url = extractRelatedThumbnail(element)
                },
            )
        }
        return resultList
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfter("#", "")
        val animeUrl = anime.url.substringBefore("#")

        val id = animeId.ifBlank {
            client.newCall(GET(baseUrl + animeUrl)).execute().use { response ->
                resolveSearchAnime(response.asJsoup()).let { doc ->
                    doc.selectFirst("[data-id]")?.attr("data-id")
                        ?: doc.selectFirst("[data-tip]")?.attr("data-tip")
                        ?: throw IllegalStateException("Anime ID not found")
                }
            }
        }

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + animeUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()
        return GET("$baseUrl/ajax/episode/list/$id?vrf=${vrfEncrypt(id)}", listHeaders)
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer")
        if (referer.isNullOrBlank()) return emptyList()
        val animeUrl = try {
            referer.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            response.parseAs<ResultResponse>().toDocument().select(episodeListSelector())
                .map { episodeFromElement(it, animeUrl) }
                .reversed()
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse episodes: ${e.message}")
            emptyList()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val title = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toIntOrNull() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toIntOrNull() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.containsMatchIn(title)) "SoftSub" else ""
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()

        val malId = element.attr("data-mal")
        val slug = element.attr("data-slug")
        val timestamp = element.attr("data-timestamp")

        return SEpisode.create().apply {
            this.name = "Episode $epNum" + if (name.isNotEmpty() && name != "Episode $epNum") ": $name" else ""
            this.url = buildString {
                append("$ids&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/ep-$epNum")
                if (malId.isNotBlank()) append("&mal=$malId")
                if (slug.isNotBlank()) append("&slug=$slug")
                if (timestamp.isNotBlank()) append("&ts=$timestamp")
            }
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = RELEASE_REGEX.find(title)?.let { parseDate(it.groupValues[1]) } ?: 0L
            scanlator = listOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(", ")
        }
    }

    // ============================ Video List ==============================

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
        val downloadUrl: String? = null,
    )

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val malParams = episode.url.substringAfter("&mal=", "").takeIf { it.isNotBlank() }
        val epurlPart = episode.url.substringAfter("epurl=").substringBefore("&mal=")

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl$epurlPart")
            add("X-Requested-With", "XMLHttpRequest")
            malParams?.let { params ->
                val parts = params.split("&")
                add("X-Mapper-Mal", parts[0])
                parts.drop(1).forEach { part ->
                    when {
                        part.startsWith("slug=") -> add("X-Mapper-Slug", part.substringAfter("slug="))
                        part.startsWith("ts=") -> add("X-Mapper-Ts", part.substringAfter("ts="))
                    }
                }
            }
        }.build()
        return GET("$baseUrl/ajax/server/list?servers=$ids", listHeaders)
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).awaitSuccess()
        val referer = response.request.header("Referer")
        if (referer.isNullOrBlank()) return emptyList()
        val epurl = try {
            referer.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        val document = try {
            response.parseAs<ResultResponse>().toDocument()
        } catch (e: Exception) {
            Log.e("AnikotoTheme", "Failed to parse video list: ${e.message}")
            return emptyList()
        }

        val typeSelection = typeToggle
        val serverNumSelection = serverNumsToggle

        val serverData = parseServerListData(document, typeSelection, serverNumSelection).toMutableList()

        // Mapper API servers
        val mapperMal = response.request.header("X-Mapper-Mal")
        val mapperSlug = response.request.header("X-Mapper-Slug")
        val mapperTs = response.request.header("X-Mapper-Ts")

        if (!mapperMal.isNullOrBlank() && !mapperSlug.isNullOrBlank() && !mapperTs.isNullOrBlank()) {
            try {
                val mapperHeaders = headers.newBuilder().apply {
                    add("Accept", "application/json, text/javascript, */*; q=0.01")
                    add("Referer", "$baseUrl/")
                    add("Origin", baseUrl)
                }.build()

                client.newCall(GET("https://mapper.mewcdn.online/api/mal/$mapperMal/$mapperSlug/$mapperTs", mapperHeaders))
                    .awaitSuccess().use { apiResponse ->
                        val mapperJson = apiResponse.parseAs<JsonObject>()
                        for ((key, value) in mapperJson) {
                            if (key.equals("status", true)) continue
                            val obj = try {
                                value.jsonObject
                            } catch (_: Exception) {
                                continue
                            }

                            listOf("sub" to "H-Sub", "dub" to "A-Dub").forEach { (typeKey, typeLabel) ->
                                val typeObj = try {
                                    obj[typeKey]?.jsonObject
                                } catch (_: Exception) {
                                    null
                                } ?: return@forEach
                                val linkId = typeObj["url"]?.jsonPrimitive?.content ?: return@forEach
                                val serverName = mapMapperName(key).lowercase()
                                if (hostToggle.any { serverName.contains(it, true) } && typeSelection.contains(typeLabel, true)) {
                                    serverData.add(VideoData(typeLabel, linkId, serverName, extractMapperDownloadUrl(typeObj)))
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e("AnikotoTheme", "Mapper API failed: ${e.message}")
            }
        }

        return serverData.parallelCatchingFlatMap { server ->
            extractVideo(server, epurl)
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Extractors =============================

    private suspend fun extractVideo(server: VideoData, epUrl: String): List<Video> = try {
        val embedLink = getEmbedLink(server.serverId, epUrl)
        when {
            embedLink.contains("mewcdn.online/player/plyr.php") -> extractFromMewcdnPlayer(embedLink, server)
            server.serverName.contains("kiwi", true) -> extractFromKiwistream(embedLink, server, epUrl)
            else -> extractFromPlayer(resolveEmbedChain(embedLink), embedLink, server)
        }
    } catch (e: Exception) {
        Log.e("AnikotoTheme", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    private suspend fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return client.newCall(GET("$baseUrl/ajax/server?get=$serverId", listHeaders)).awaitSuccess().use { response ->
            if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
            response.parseAs<ServerResponseDto>().result.url
        }
    }

    private suspend fun extractFromPlayer(embedUrl: String, parentUrl: String, server: VideoData): List<Video> {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            return emptyList()
        }

        val pageHeaders = headers.newBuilder().add("Referer", parentUrl).build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Player page failed: HTTP ${it.code}")
            it.body.string()
        }

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            ?: throw Exception("Could not find data-id")

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        val sourcesBody = client.newCall(GET("https://$host/stream/getSources?id=$dataId", apiHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("getSources failed: HTTP ${it.code}")
            it.body.string()
        }

        val data = sourcesBody.parseAs<SourceResponseDto>()
        val m3u8 = extractM3u8FromSources(data.sources)?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found")

        val subtitles = data.tracks?.filter { it.kind.equals("captions", true) }?.map { Track(it.file, it.label) }.orEmpty()

        val (serverBaseName, serverNum, _) = getServerInfo(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverBaseName $serverNum$typeSuffix - $it" },
            subtitleList = subtitles,
            referer = "https://$host/",
        )
    }

    private suspend fun extractFromMewcdnPlayer(embedUrl: String, server: VideoData): List<Video> {
        val fragment = embedUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotBlank() }
            ?: throw Exception("No fragment found in mewcdn player URL")

        val m3u8 = String(Base64.decode(fragment, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!m3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL decoded from mewcdn fragment")
        }

        val (serverBaseName, serverNum, qualityFromName) = getServerInfo(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "$serverBaseName $serverNum$typeSuffix - ${qualityFromName ?: quality}"
            },
            referer = "https://mewcdn.online/",
        )
    }

    private suspend fun extractFromKiwistream(embedUrl: String, server: VideoData, epUrl: String): List<Video> {
        val (serverBaseName, serverNum, qualityFromName) = getServerInfo(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        val qualityLabel = qualityFromName ?: "default"
        val videoLabel = "$serverBaseName $serverNum$typeSuffix - $qualityLabel"
        val referer = "$baseUrl$epUrl"

        // Prefer HLS for Kiwi-Stream as MP4 requires heavy CF bypasses
        return kwikExtractor.getHlsVideo(embedUrl, referer = referer, quality = videoLabel)
            .let(::listOf)
    }

    // ============================ Video Sort ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = prefQuality
        val server = prefServer
        val type = prefType
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(" - $type ", true) },
        )
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

    protected open fun vrfEncrypt(input: String): String = utils.vrfEncrypt(input)

    protected open fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.trim()?.takeIf(String::isNotBlank)
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf(String::isNotBlank)
        val malScore = document.select("$metaContainerSelector div.meta > div").firstOrNull {
            it.ownText().trim().removeSuffix(":").equals(scoreLabelName, ignoreCase = true)
        }?.select("span")?.text()?.trim()

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotBlank()) appendLine(fancyScore).appendLine()

        document.selectFirst(synopsisContentSelector)?.text()?.let {
            appendLine(it).appendLine()
        }

        val meta = document.select("$metaContainerSelector div.meta > div").mapNotNull { div ->
            val label = div.ownText().trim().removeSuffix(":").removeSuffix(" ")
            var value = div.select("span").text().trim()
            if (label.equals("Duration", ignoreCase = true)) {
                value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { value = "$it min" }
            }
            if (label.isNotBlank() && value.isNotBlank() && label !in metaExclusionLabels) {
                "$label: $value"
            } else {
                null
            }
        }

        if (meta.isNotEmpty()) appendLine(meta.joinToString(" | ")).appendLine()

        val studios = document.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
        val producers = document.select("div:contains(Producers) > span > a").joinToString(", ") { it.text().trim() }

        when {
            studios.isNotBlank() && producers.isNotBlank() -> appendLine("Studio: $studios (Producers: $producers)").appendLine()
            studios.isNotBlank() -> appendLine("Studio: $studios").appendLine()
            producers.isNotBlank() -> appendLine("Producers: $producers").appendLine()
        }

        val altNames = mutableListOf<String>()
        if (useEnglish) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }
        document.selectFirst(aliasContainerSelector)?.text()?.takeIf { it.isNotBlank() }?.let { namesText ->
            altNames.addAll(namesText.split(";").map { it.trim() }.filter { it.isNotBlank() && it != jpTitle && it != enTitle })
        }
        if (altNames.isNotEmpty()) appendLine("Other name(s): ${altNames.joinToString(", ")}").appendLine()

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotBlank()) append(fancyScore)
    }.trim()

    protected open fun resolveSearchAnime(document: Document): Document {
        if (document.location().startsWith("$baseUrl/filter?keyword=")) {
            val foundAnimePath = document.selectFirst(searchAnimeSelector())?.selectFirst("a[href]")?.attr("href")
                ?: throw IllegalStateException("Search element not found")
            val resolveAnimePath = EP_URL_SUFFIX_REGEX.replace(foundAnimePath, "")
            return client.newCall(GET(baseUrl + resolveAnimePath)).execute().useAsJsoup()
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

    protected open fun parseServerListData(
        document: Document,
        typeSelection: Set<String>,
        serverNumSelection: Set<String>,
    ): List<VideoData> {
        return document.select("div.servers > div.type").flatMap { elem ->
            val label = elem.selectFirst("label")?.text()?.trim()?.let { lbl ->
                when (lbl.uppercase()) {
                    "SUB" -> "Sub"
                    "H-SUB" -> "H-Sub"
                    "DUB" -> "Dub"
                    "A-DUB" -> "A-Dub"
                    else -> lbl.replaceFirstChar { it.uppercase() }
                }
            } ?: elem.attr("data-type").replaceFirstChar { it.uppercase() }

            elem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null
                val serverName = serverElement.text().lowercase()
                if (hostToggle.none { serverName.contains(it, true) }) return@mapNotNull null
                if (!typeSelection.contains(label, true)) return@mapNotNull null
                val serverNum = getServerNumber(serverName)
                if (!serverNumSelection.contains(serverNum.toString())) return@mapNotNull null
                VideoData(label, serverId, serverName)
            }
        }
    }

    protected open fun extractRelatedThumbnail(element: Element): String? = element.selectFirst("img")?.attr("src")?.trim()

    protected open fun mapMapperName(key: String): String = when {
        key.equals("gogoanime", true) -> "Vidstream"
        key.equals("anivibe", true) -> "Vibe-Stream"
        key.equals("animepahe", true) -> "Kiwi-Stream"
        key.startsWith("Kiwi-Stream", true) -> key
        else -> key
    }

    protected open fun getServerInfo(serverName: String): Triple<String, String, String?> {
        val qualityMatch = Regex("""(\d+)p$""").find(serverName)
        val quality = qualityMatch?.value
        val nameWithoutQuality = if (qualityMatch != null) serverName.substringBeforeLast("-${qualityMatch.value}") else serverName

        val parts = nameWithoutQuality.split("-")
        val numPart = parts.lastOrNull()?.toIntOrNull()
        val rawBaseName = if (numPart != null && parts.size > 1) parts.dropLast(1).joinToString("-") else nameWithoutQuality

        val displayName = rawBaseName.split("-").joinToString("-") { word ->
            word.replaceFirstChar { it.uppercase() }
        }

        return Triple(displayName, if (numPart != null) "S$numPart" else "S1", quality)
    }

    protected open fun getServerNumber(serverName: String): Int = serverName.split("-").lastOrNull()?.toIntOrNull() ?: 1

    protected open fun extractM3u8FromSources(sources: kotlinx.serialization.json.JsonElement): String? = when (sources) {
        is JsonObject -> sources["file"]?.jsonPrimitive?.content
        is JsonArray -> sources.firstOrNull()?.let {
            when (it) {
                is JsonObject -> it["file"]?.jsonPrimitive?.content
                is JsonPrimitive -> it.content
                else -> null
            }
        }
        is JsonPrimitive -> sources.content
        else -> null
    }

    protected open suspend fun resolveEmbedChain(url: String): String {
        var currentUrl = url
        repeat(3) {
            try {
                val iframeUrl = client.newCall(GET(currentUrl, docHeaders)).awaitSuccess().use {
                    it.asJsoup().selectFirst("iframe[src]")?.attr("abs:src")
                }
                if (iframeUrl.isNullOrBlank()) return currentUrl
                currentUrl = iframeUrl
            } catch (_: Exception) {
                return currentUrl
            }
        }
        return currentUrl
    }

    @Synchronized
    protected open fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    protected open fun extractMapperDownloadUrl(typeObj: JsonObject): String? {
        val downloadObj = typeObj["download"]?.jsonObject ?: return null
        for (quality in listOf("1080p", "720p", "480p", "360p")) {
            downloadObj.entries.firstOrNull { it.key.contains(quality, true) }?.let {
                return (it.value as? JsonPrimitive)?.content
            }
        }
        return downloadObj.entries.firstOrNull()?.let { (it.value as? JsonPrimitive)?.content }
    }

    // ============================ Shared Utilities ========================

    protected open fun getTitle(element: Element): String {
        val enTitle = element.text().trim().takeIf(String::isNotBlank)
        val jpTitle = element.attr("data-jp").trim().takeIf(String::isNotBlank)
        return if (useEnglish) {
            enTitle ?: jpTitle ?: element.text().trim()
        } else {
            jpTitle ?: enTitle ?: element.text().trim()
        }
    }

    protected open fun parseStatus(statusString: String): Int = when (statusString) {
        "Ongoing Anime", "Currently Airing" -> SAnime.ONGOING
        "Finished Airing", "Completed" -> SAnime.COMPLETED
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

    protected fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    // ============================== Preferences ===========================

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, defaultBaseUrl)!!.removePrefix("https://")
        val hostToggle = getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!!

        val invalidDomain = domain !in domainEntries
        val invalidHosters = hostToggle.any { it !in hosterNames }

        if (invalidDomain || invalidHosters) {
            edit().also { editor ->
                if (invalidDomain) editor.putString(PREF_DOMAIN_KEY, defaultBaseUrl)
                if (invalidHosters) {
                    editor.putStringSet(PREF_HOSTER_KEY, hosterNames.toSet())
                    editor.putString(PREF_SERVER_KEY, hosterNames.first())
                }
            }.apply()
        }
        return this
    }

    private fun getHosters(): Set<String> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!!
        if (hosterSelection.any { it !in hosterNames }) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, hosterNames.toSet()).apply()
            return hosterNames.toSet()
        }
        return hosterSelection
    }

    protected var useEnglish by LazyMutable { getTitleLang == "English" }

    protected val getTitleLang by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)
    protected val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    protected val prefServer by preferences.delegate(PREF_SERVER_KEY, hosterNames.first())
    protected val prefType by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)
    protected val hostToggle: Set<String> by preferences.delegate(PREF_HOSTER_KEY, hosterNames.toSet())
    protected val typeToggle: Set<String> by preferences.delegate(PREF_TYPE_TOGGLE_KEY, DEFAULT_TYPES)
    protected val serverNumsToggle: Set<String> by preferences.delegate(PREF_SERVER_NUMS_KEY, PREF_SERVER_NUMS_DEFAULT)
    protected val scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            getHosters()
        } catch (e: Exception) {
            Toast.makeText(screen.context, e.toString(), Toast.LENGTH_LONG).show()
        }

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred Domain"
            entries = domainEntries.toTypedArray()
            entryValues = domainValues.toTypedArray()
            setDefaultValue(defaultBaseUrl)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = PREF_TITLE_LANG_ENTRIES
            entryValues = PREF_TITLE_LANG_ENTRIES
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = PREF_QUALITY_DISPLAY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = hosterDisplayNames.toTypedArray()
            entryValues = hosterNames.toTypedArray()
            setDefaultValue(hosterNames.first())
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred Type"
            entries = PREF_TYPE_DISPLAY_ENTRIES
            entryValues = PREF_TYPE_ENTRIES
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score Display Position"
            entries = PREF_SCORE_POSITION_ENTRIES
            entryValues = PREF_SCORE_POSITION_VALUES
            setDefaultValue(PREF_SCORE_POSITION_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            summary = "Select which hosts to use"
            entries = hosterDisplayNames.toTypedArray()
            entryValues = hosterNames.toTypedArray()
            setDefaultValue(hosterNames.toSet())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_NUMS_KEY
            title = "Enable/Disable Servers"
            summary = "Select which servers to show"
            entries = SERVER_NUM_NAMES
            entryValues = SERVER_NUM_VALUES
            setDefaultValue(PREF_SERVER_NUMS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val newSet = newValue as Set<String>
                if (newSet.isEmpty()) {
                    Toast.makeText(screen.context, "Must select at least one server", Toast.LENGTH_LONG).show()
                    false
                } else {
                    preferences.edit().putStringSet(key, newSet).apply()
                    true
                }
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_TOGGLE_KEY
            title = "Enable/Disable Types"
            summary = "Select which video types to show"
            entries = PREF_TYPE_DISPLAY_ENTRIES
            entryValues = PREF_TYPE_ENTRIES
            setDefaultValue(DEFAULT_TYPES)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val newSet = newValue as Set<String>
                if (newSet.isEmpty()) {
                    Toast.makeText(screen.context, "Must select at least one type", Toast.LENGTH_LONG).show()
                    false
                } else {
                    preferences.edit().putStringSet(key, newSet).apply()
                    true
                }
            }
        }.also(screen::addPreference)
    }

    companion object {
        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)

        private const val PREF_DOMAIN_KEY = "preferred_domain"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_ENTRIES = arrayOf("English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_DISPLAY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES[0]

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_SERVER_NUMS_KEY = "server_number_selection"
        private val SERVER_NUM_NAMES = arrayOf("Server 1", "Server 2", "Server 3")
        private val SERVER_NUM_VALUES = arrayOf("1", "2", "3")
        private val PREF_SERVER_NUMS_DEFAULT = SERVER_NUM_VALUES.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val PREF_TYPE_ENTRIES = arrayOf("Sub", "H-Sub", "Dub", "A-Dub")
        private val PREF_TYPE_DISPLAY_ENTRIES = arrayOf("Sub", "Hard Sub", "Dub", "Alternate Dub")
        private val DEFAULT_TYPES = PREF_TYPE_ENTRIES.toSet()

        private const val PREF_TYPE_KEY = "preferred_language"
        private const val PREF_TYPE_DEFAULT = "Sub"

        const val SCORE_POS_TOP = "top"
        const val SCORE_POS_BOTTOM = "bottom"
        const val SCORE_POS_NONE = "none"

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = arrayOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = arrayOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)

        const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }

    // =============================== VRF ==================================

    private class AnikotoUtils {
        fun vrfEncrypt(input: String): String {
            var vrf = input
            ORDER.sortedBy { it.first }.forEach { item ->
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
            val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
            val cipher = Cipher.getInstance("RC4")
            cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.parameters)
            val output = cipher.doFinal(input.toByteArray())
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

        companion object {
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
    }
}
