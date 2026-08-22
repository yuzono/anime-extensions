package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class AniWaves :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniWaves (Unoriginal)"

    override val baseUrl: String
        get() = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "en"
    override val supportsLatest = true

    private val utils by lazy { AniWavesUtils() }
    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val refererHeaders = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    private val useEnglish get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) == "English"
    private val scorePosition get() = preferences.getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)!!

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/trending/page/$page",
        refererHeaders,
    )

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a.name, a.d-title")?.let { a ->
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(a.attr("href").substringBefore("?"), ""))
            title = getTitle(a)
        }
        thumbnail_url = element.selectFirst("div.poster img, img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/filter?sort_by=last_updated&page=$page",
        refererHeaders,
    )

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Intercept tag clicks (prefixed with #) to route to exact tag search pages
        if (query.startsWith("#")) {
            val slug = query.removePrefix("#").trim()
                .lowercase(Locale.US)
                .replace(" ", "-")
                .replace("[^a-z0-9-]".toRegex(), "")
            val url = if (page == 1) "$baseUrl/tags/$slug" else "$baseUrl/tags/$slug/page/$page"
            return GET(url, refererHeaders)
        }

        // Standard search uses /filter
        val params = AniWavesFilters.getSearchParameters(filters)

        val url = buildString {
            append("$baseUrl/filter?keyword=${URLEncoder.encode(query, "UTF-8")}")
            if (params.genre.isNotBlank()) append(params.genre)
            if (params.country.isNotBlank()) append(params.country)
            if (params.season.isNotBlank()) append(params.season)
            if (params.year.isNotBlank()) append(params.year)
            if (params.type.isNotBlank()) append(params.type)
            if (params.status.isNotBlank()) append(params.status)
            if (params.language.isNotBlank()) append(params.language)
            if (params.rating.isNotBlank()) append(params.rating)
            if (params.sort.isNotBlank()) append("&sort_by=${params.sort}")
            append("&page=$page")
        }
        return GET(url, refererHeaders)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun getFilterList(): AnimeFilterList = AniWavesFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.title, h2.title")
        val animeId = newDocument.selectFirst("#watch-main[data-id]")?.attr("data-id")
            ?: newDocument.selectFirst("[data-id]")?.attr("data-id")

        val genresFromPage = newDocument.select("div:contains(Genres) > span > a").map { it.text().trim() }
        val showTags = preferences.getBoolean(PREF_SHOW_TAGS_KEY, PREF_SHOW_TAGS_DEFAULT)

        val tagsFromPage = if (showTags) {
            newDocument.select("div.tags span a[href^=/tags/]").mapNotNull {
                val tagName = it.attr("title").trim().takeIf(String::isNotBlank)
                    ?: it.text().trim().removePrefix("#")
                if (tagName.isNotBlank()) "#$tagName" else null
            }
        } else {
            emptyList()
        }

        val combinedGenres = genresFromPage + tagsFromPage

        return SAnime.create().apply {
            val cleanUrl = newDocument.selectFirst("#watch-main[data-url]")?.attr("data-url")
                ?.takeIf { it.isNotBlank() }
                ?: EP_URL_SUFFIX_REGEX.replace(newDocument.location().substringAfter(baseUrl), "")
            setUrlWithoutDomain(cleanUrl)
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            titleElement?.let { getTitle(it) }?.takeIf(String::isNotBlank)?.let { title = it }
            thumbnail_url = newDocument.selectFirst("#w-info div.poster img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            genre = combinedGenres.joinToString(", ")
            author = newDocument.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
            status = parseStatus(newDocument.select("div:contains(Status) > span").text())
            description = buildDescription(newDocument, titleElement)
        }
    }

    private fun getTitle(element: Element): String {
        val enTitle = element.text().trim().takeIf(String::isNotBlank)
        val jpTitle = element.attr("data-jp").trim().takeIf(String::isNotBlank)

        val stripEpSuffix: (String) -> String = { it.replace(EP_TITLE_SUFFIX_REGEX, "") }

        return if (useEnglish) {
            enTitle?.let(stripEpSuffix) ?: jpTitle?.let(stripEpSuffix) ?: element.text().trim()
        } else {
            jpTitle?.let(stripEpSuffix) ?: enTitle?.let(stripEpSuffix) ?: element.text().trim()
        }
    }

    private fun getFancyScore(score: String?): String {
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

    private fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.trim()?.takeIf(String::isNotBlank)
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf(String::isNotBlank)

        val malScore = document.select("div.bmeta div.meta > div").firstOrNull {
            it.ownText().trim().removeSuffix(":").equals("Scores", ignoreCase = true)
        }?.selectFirst("span")?.text()?.trim()?.substringBefore(" ")

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotBlank()) {
            appendLine(fancyScore)
            appendLine()
        }

        // Synopsis
        document.selectFirst("div.shorting.film-description div.content")?.text()
            ?.let { appendLine(it).appendLine() }

        val showInfo = preferences.getBoolean(PREF_SHOW_INFO_KEY, PREF_SHOW_INFO_DEFAULT)

        if (showInfo) {
            val meta = document.select("div.bmeta div.meta > div").mapNotNull { div ->
                val label = div.ownText().trim().removeSuffix(":").removeSuffix(" ")
                var value = div.selectFirst("span")?.text()?.trim() ?: ""
                // Fix duplicate values from nested HTML spans (e.g. "Manga Manga" → "Manga")
                value = DUPLICATE_REGEX.matchEntire(value)?.groupValues?.get(1)?.trim() ?: value
                if (label.equals("Duration", ignoreCase = true)) {
                    value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { value = "$it min" }
                }
                if (label.isNotBlank() && value.isNotBlank() && label !in META_EXCLUDED_LABELS) {
                    "**$label:** $value"
                } else {
                    null
                }
            }
            if (meta.isNotEmpty()) appendLine(meta.joinToString(" | "))

            val studios = document.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
            val producers = document.select("div:contains(Producers) > span > a").joinToString(", ") { it.text().trim() }
            when {
                studios.isNotBlank() && producers.isNotBlank() -> appendLine("**Studio:** $studios (Producers: $producers)")
                studios.isNotBlank() -> appendLine("**Studio:** $studios")
                producers.isNotBlank() -> appendLine("**Producers:** $producers")
            }

            val altNames = mutableListOf<String>()
            if (useEnglish) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }
            document.selectFirst("div.names.font-italic")?.text()?.takeIf { it.isNotBlank() }?.let { namesText ->
                altNames.addAll(
                    namesText.split(",").map { it.trim() }
                        .filter { it.isNotBlank() && it != jpTitle && it != enTitle },
                )
            }
            if (altNames.isNotEmpty()) appendLine("**Other name(s):** ${altNames.joinToString(", ")}")
        }

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotBlank()) {
            appendLine()
            append(fancyScore)
        }
    }.trim()

    // ============================== Related ===============================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")
        return if (animeId.isNotBlank()) {
            GET(baseUrl + animeUrl, refererHeaders).newBuilder()
                .header("X-Anime-Id", animeId).build()
        } else {
            GET(baseUrl + animeUrl, refererHeaders)
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        return try {
            val document = response.asJsoup()
            val currentPath = extractAnimePath(response.request.url.toString())
            val animeId = response.request.header("X-Anime-Id")
                ?: document.selectFirst("#watch-main[data-id]")?.attr("data-id")
                ?: document.selectFirst("[data-id]")?.attr("data-id")
            val seenPaths = mutableSetOf(currentPath)

            buildList {
                // 1. Seasons (#w-seasons)
                document.select("#w-seasons .season a").mapNotNull { element ->
                    val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@mapNotNull null
                    if (!seenPaths.add(path)) return@mapNotNull null
                    val nameElement = element.selectFirst(".title.d-title") ?: return@mapNotNull null
                    SAnime.create().apply {
                        url = path
                        title = getTitle(nameElement).trim()
                        thumbnail_url = element.attr("style")
                            .substringAfter("url(").substringBefore(")").trim('\'', '"')
                    }
                }.let(::addAll)

                // 2. Related (#w-related — all tabs in DOM order)
                document.select("#w-related .scaff.side.items a.item").mapNotNull { element ->
                    val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@mapNotNull null
                    if (!seenPaths.add(path)) return@mapNotNull null
                    val nameElement = element.selectFirst(".info .name") ?: return@mapNotNull null
                    val tipId = element.selectFirst("[data-tip]")?.attr("data-tip")
                    SAnime.create().apply {
                        url = path + if (!tipId.isNullOrBlank()) "#$tipId" else ""
                        title = getTitle(nameElement).trim()
                        thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
                    }
                }.let(::addAll)

                // 3. Recommended — from API (AJAX-loaded on website)
                if (!animeId.isNullOrBlank()) {
                    try {
                        val recHeaders = headers.newBuilder().apply {
                            add("Accept", "*/*")
                            add("Referer", response.request.url.toString())
                            add("X-Requested-With", "XMLHttpRequest")
                        }.build()
                        var recPage = 1
                        do {
                            val recResponse = client.newCall(
                                GET("$baseUrl/ajax/v2/recommendations?page=$recPage&mov_id=$animeId", recHeaders),
                            ).execute()
                            if (!recResponse.isSuccessful) break
                            val recData = recResponse.parseAs<RecommendationsResponse>()
                            if (!recData.status || recData.html.isBlank()) break
                            val recDoc = Jsoup.parseBodyFragment(recData.html)
                            recDoc.select("a.item").mapNotNull { element ->
                                val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@mapNotNull null
                                if (!seenPaths.add(path)) return@mapNotNull null
                                val nameElement = element.selectFirst(".info .name") ?: return@mapNotNull null
                                val tipId = element.selectFirst("[data-tip]")?.attr("data-tip")
                                SAnime.create().apply {
                                    url = path + if (!tipId.isNullOrBlank()) "#$tipId" else ""
                                    title = getTitle(nameElement).trim()
                                    thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
                                }
                            }.let(::addAll)
                            recPage++
                            if (!recData.has_more_pages) break
                        } while (true)
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.e("AniWave", "Failed to parse related anime", e)
            emptyList()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfter("#", "")
        val animeUrl = anime.url.substringBefore("#")

        val id = animeId.ifBlank {
            client.newCall(GET(baseUrl + animeUrl)).execute().use { response ->
                resolveSearchAnime(response.asJsoup()).let { doc ->
                    doc.selectFirst("#watch-main[data-id]")?.attr("data-id")
                        ?: doc.selectFirst("[data-id]")?.attr("data-id")
                        ?: throw IllegalStateException("Anime ID not found")
                }
            }
        }

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + animeUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()
        return GET("$baseUrl/ajax/episode/list/$id?vrf=${utils.vrfEncrypt(id)}", listHeaders)
    }

    override fun episodeListSelector(): String = "div.episodes ul li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer")
        if (referer.isNullOrBlank()) return emptyList()
        val animeUrl = try {
            referer.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val result = response.parseAs<ResultResponse>()
            result.toDocument().select(episodeListSelector())
                .map { episodeFromElement(it, animeUrl) }
                .reversed()
        } catch (e: Exception) {
            Log.e("AniWave", "Failed to parse episodes: ${e.message}")
            emptyList()
        }
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val title = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toIntOrNull() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toIntOrNull() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.containsMatchIn(title)) "SoftSub" else ""
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()

        return SEpisode.create().apply {
            this.name = "Episode $epNum" + if (name.isNotEmpty() && name != "Episode $epNum") ": $name" else ""
            this.url = "$ids&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/episode/$epNum"
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = RELEASE_REGEX.find(title)?.let { parseDate(it.groupValues[1]) } ?: 0L
            scanlator = listOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(", ")
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val epurlPart = episode.url.substringAfter("epurl=").substringBefore("&mal=")

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl$epurlPart")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()
        return GET("$baseUrl/ajax/server/list/$ids?vrf=${utils.vrfEncrypt(ids)}", listHeaders)
    }

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
        val svId: Int,
    )

    override fun videoListParse(response: Response): List<Video> {
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
            Log.e("AniWave", "Failed to parse video list: ${e.message}")
            return emptyList()
        }

        val hosterSelection = getHosters()
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)!!

        Log.d("AniWave", "Server HTML: ${document.html().take(500)}")

        return document.select("div.servers div.type[data-type]").flatMap { elem ->
            val label = elem.attr("data-type").trim().let { type ->
                when (type.lowercase()) {
                    "sub" -> "Sub"
                    "dub" -> "Dub"
                    "ssub" -> "SoftSub"
                    else -> type.replaceFirstChar { it.uppercase() }
                }
            }

            elem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null
                val serverName = serverElement.text().lowercase()
                val svId = serverElement.attr("data-sv-id").toIntOrNull() ?: 0

                if (hosterSelection.none { serverName.contains(it, true) }) return@mapNotNull null
                if (!typeSelection.contains(label, true)) return@mapNotNull null

                VideoData(label, serverId, serverName, svId)
            }
        }.parallelFlatMapBlocking { extractVideo(it, epurl) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private suspend fun extractVideo(server: VideoData, epUrl: String): List<Video> = try {
        val embedResult = getEmbedUrl(server.serverId, epUrl)
        val embedUrl = embedResult.first

        when {
            embedUrl.contains("echovideo", true) ||
                embedUrl.contains("vidplay", true) ->
                extractFromVidplay(embedUrl, server, epUrl)

            embedUrl.contains("weneverbeenfree", true) ||
                embedUrl.contains("byfms", true) ->
                extractFromVidWish(embedUrl, server, epUrl)

            embedUrl.contains("myvidplay", true) ||
                embedUrl.contains("dghg", true) ->
                extractFromVidWish(embedUrl, server, epUrl)

            else -> extractFromVidWish(embedUrl, server, epUrl)
        }
    } catch (e: Exception) {
        Log.e("AniWave", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    private suspend fun getEmbedUrl(serverLinkId: String, epUrl: String): Pair<String, SkipData?> {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        // Try new API — data-link-id is already VRF-encrypted, send directly
        try {
            client.newCall(
                GET("$baseUrl/ajax/sources?id=$serverLinkId&asi=0&autoPlay=0", listHeaders),
            ).awaitSuccess().use { response ->
                if (response.isSuccessful) {
                    val parsed = response.parseAs<SourcesResponse>()
                    val result = parsed.result
                    if (result != null && result.url.isNotBlank()) {
                        return Pair(result.url, result.skip_data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("AniWave", "/ajax/sources failed, falling back: ${e.message}")
        }

        // Fallback to old API
        val vrf = utils.vrfEncrypt(serverLinkId)
        return client.newCall(
            GET("$baseUrl/ajax/server/$serverLinkId?vrf=$vrf", listHeaders),
        ).awaitSuccess().use { response ->
            if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
            val encryptedUrl = response.parseAs<ServerResponse>().result.url
            Pair(utils.vrfDecrypt(encryptedUrl), null)
        }
    }

    // ========================= Vidplay Extractor ==========================

    private suspend fun extractFromVidplay(
        embedUrl: String,
        server: VideoData,
        epUrl: String,
    ): List<Video> {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            Log.e("AniWave", "Invalid Vidplay URL: $embedUrl")
            return emptyList()
        }

        val videoId = embedUrl.toHttpUrl().pathSegments.lastOrNull()
            ?: throw Exception("Could not extract video ID from Vidplay URL")

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Referer", embedUrl)
        }.build()

        val sourcesBody = client.newCall(
            GET("https://$host/embed-1/getSources?id=$videoId", apiHeaders),
        ).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Vidplay getSources failed: HTTP ${it.code}")
            it.body.string()
        }

        val data = sourcesBody.parseAs<VidplaySourcesResponse>()
        val m3u8 = data.sources?.let { extractM3u8FromSources(it) }
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 from Vidplay")

        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        val serverLabel = server.serverName.replaceFirstChar { it.uppercase() }

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverLabel$typeSuffix - $it" },
            subtitleList = emptyList(),
            referer = "https://$host/",
        )
    }

    // ========================= VidWish Extractor ==========================

    private suspend fun extractFromVidWish(
        embedUrl: String,
        server: VideoData,
        epUrl: String,
    ): List<Video> {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            Log.e("AniWave", "Invalid embed URL: $embedUrl")
            return emptyList()
        }

        val pageHeaders = headers.newBuilder().add("Referer", "$baseUrl$epUrl").build()
        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Player page failed: HTTP ${it.code}")
            it.body.string()
        }

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            ?: throw Exception("Could not find data-id on $host")

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        val sourcesBody = client.newCall(
            GET("https://$host/stream/getSources?id=$dataId", apiHeaders),
        ).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("getSources failed: HTTP ${it.code}")
            it.body.string()
        }

        val data = sourcesBody.parseAs<SourceResponseDto>()
        val m3u8 = extractM3u8FromSources(data.sources)
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found from $host")

        val subtitles = data.tracks
            ?.filter { it.kind.equals("captions", true) }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        val serverLabel = server.serverName.replaceFirstChar { it.uppercase() }

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverLabel$typeSuffix - $it" },
            subtitleList = subtitles,
            referer = "https://$host/",
        )
    }

    // ========================= Shared Utilities ===========================

    private fun extractM3u8FromSources(sources: kotlinx.serialization.json.JsonElement): String? = when (sources) {
        is JsonObject -> sources["file"]?.jsonPrimitive?.content
        is JsonArray -> sources.firstOrNull()?.let {
            when (it) {
                is JsonObject -> it["file"]?.jsonPrimitive?.content
                is JsonPrimitive -> it.content
                else -> null
            }
        }
        is JsonPrimitive -> sources.content
    }

    private fun extractAnimePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val rawPath = try {
            if (href.startsWith("http")) href.toHttpUrl().encodedPath else href
        } catch (_: Exception) {
            return null
        }
        return ANIME_PATH_REGEX.find(rawPath)?.value
    }

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val type = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(" - $type ", true) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    @Synchronized
    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("Currently", true) ||
            statusString.contains("Ongoing", true) ||
            statusString.contains("Airing", true) -> SAnime.ONGOING
        statusString.contains("Finished", true) ||
            statusString.contains("Completed", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun resolveSearchAnime(document: Document): Document {
        if (document.location().startsWith("$baseUrl/filter?keyword=")) {
            val foundAnimePath = document.selectFirst(searchAnimeSelector())?.selectFirst("a[href]")?.attr("href")
                ?: throw IllegalStateException("Search element not found")
            val resolvePath = EP_URL_SUFFIX_REGEX.replace(foundAnimePath, "")
            return client.newCall(GET(baseUrl + resolvePath)).execute().useAsJsoup()
        }
        return document
    }

    private fun getHosters(): Set<String> {
        val serverPref = preferences.getString(PREF_SERVER_KEY, null)
        if (serverPref != null && serverPref !in HOSTERS_NAMES) {
            preferences.edit().putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT).apply()
        }

        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        if (hosterSelection.any { it !in HOSTERS_NAMES }) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
            return PREF_HOSTER_DEFAULT.toSet()
        }
        return hosterSelection.toSet()
    }

    companion object {
        private val DOMAINS = arrayOf("aniwaves.ru")
        private val BASE_URLS = DOMAINS.map { "https://$it" }.toTypedArray()

        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        private val EP_TITLE_SUFFIX_REGEX = Regex("""\s+Episode\s+\d+.*$""")
        private val EP_URL_SUFFIX_REGEX = Regex("""/(?:ep-\d+|episode/\d+)$""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)

        private val DUPLICATE_REGEX = Regex("""^(.+?)\s+\1$""")

        private val META_EXCLUDED_LABELS = listOf("Genres", "Status", "Studios", "Producers", "Scores")

        private val ANIME_PATH_REGEX = Regex("""^/watch/[^/?#]+""")

        private const val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_DEFAULT = BASE_URLS[0]

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = arrayOf("English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "vidplay"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf("Vidplay", "BYFMS", "DGHG", "MyCloud")
        private val HOSTERS_NAMES = arrayOf("vidplay", "byfms", "dghg", "mycloud")
        private val PREF_HOSTER_DEFAULT = arrayOf("vidplay", "byfms", "dghg", "mycloud").toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("Sub", "Dub", "SoftSub")
        private val PREF_TYPES_TOGGLE_DEFAULT = setOf("Sub", "Dub")

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        const val SCORE_POS_TOP = "top"
        const val SCORE_POS_BOTTOM = "bottom"
        const val SCORE_POS_NONE = "none"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP

        private const val PREF_SHOW_INFO_KEY = "show_info"
        private const val PREF_SHOW_INFO_DEFAULT = true

        private const val PREF_SHOW_TAGS_KEY = "show_tags"
        private const val PREF_SHOW_TAGS_DEFAULT = true
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, null)
        if (typeSelection != null && typeSelection.any { it !in TYPES }) {
            preferences.edit().putStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT).apply()
        }

        try {
            getHosters()
        } catch (e: Exception) {
            Toast.makeText(screen.context, e.toString(), Toast.LENGTH_LONG).show()
        }

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred Domain"
            entries = DOMAINS
            entryValues = BASE_URLS
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_DOMAIN_KEY
            title = "Custom domain"
            setDefaultValue(null)
            summary = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)?.takeIf { it.isNotBlank() }
                ?: "Overrides domain preference if set"
            setOnPreferenceChangeListener { _, newValue ->
                val v = newValue as String
                if (v.isBlank() || v.startsWith("http")) {
                    preferences.edit().putString(key, v).apply()
                    true
                } else {
                    Toast.makeText(screen.context, "Invalid URL. Example: https://aniwaves.ru", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = PREF_TITLE_LANG_LIST
            entryValues = PREF_TITLE_LANG_LIST
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Type"
            entries = arrayOf("Sub", "Dub")
            entryValues = TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score Display"
            entries = arrayOf("Top of description", "Bottom of description", "Don't show")
            entryValues = arrayOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)
            setDefaultValue(PREF_SCORE_POSITION_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_INFO_KEY
            title = "Show Info"
            summary = "Display metadata (Type, Country, Aired, Source, Studio, Other names, etc.) in the description"
            setDefaultValue(PREF_SHOW_INFO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_KEY
            title = "Show Tags"
            summary = "Display tags as clickable chips (prefixed with #). Clicking triggers an in-app tag search"
            setDefaultValue(PREF_SHOW_TAGS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_TOGGLE_KEY
            title = "Enable/Disable Types"
            entries = arrayOf("Sub", "Dub")
            entryValues = TYPES
            setDefaultValue(PREF_TYPES_TOGGLE_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val set = newValue as Set<String>
                if (set.isEmpty()) {
                    Toast.makeText(screen.context, "Must select at least one type", Toast.LENGTH_LONG).show()
                    false
                } else {
                    preferences.edit().putStringSet(key, set).apply()
                    true
                }
            }
        }.also(screen::addPreference)
    }
}
