package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.app.Application
import android.util.Log
import android.widget.Toast
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

class AniWave :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniWave (Unofficial)"

    override val baseUrl by lazy {
        preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"
    override val supportsLatest = true

    private val utils by lazy { AniWaveUtils() }
    private val preferences by getPreferencesLazy()
    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val appCtx: Application by injectLazy()
    private val kwikExtractor by lazy { KwikExtractor(client, appCtx) }

    private val refererHeaders = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    private val scorePosition get() = preferences.getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)!!
    private val useEnglish get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) == "English"

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("most-viewed")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        refererHeaders,
    )

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a.name")?.let { a ->
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(a.attr("href").substringBefore("?"), ""))
            title = getTitle(a)
        }
        thumbnail_url = element.selectFirst("div.poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("latest-updated")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        refererHeaders,
    )

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AniWaveFilters.getSearchParameters(filters)
        val vrf = if (query.isNotBlank()) utils.vrfEncrypt(query) else ""

        val url = buildString {
            append(
                baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("filter")
                    addQueryParameter("keyword", query)
                }.build(),
            )
            if (searchParams.genre.isNotBlank()) append(searchParams.genre)
            if (searchParams.season.isNotBlank()) append(searchParams.season)
            if (searchParams.year.isNotBlank()) append(searchParams.year)
            if (searchParams.type.isNotBlank()) append(searchParams.type)
            if (searchParams.status.isNotBlank()) append(searchParams.status)
            if (searchParams.language.isNotBlank()) append(searchParams.language)
            if (searchParams.rating.isNotBlank()) append(searchParams.rating)
            if (searchParams.sort.isNotBlank()) append("&sort=${searchParams.sort}")
            append("&page=$page&vrf=$vrf")
        }
        return GET(url, refererHeaders)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun getFilterList(): AnimeFilterList = AniWaveFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.title, h2.title")
        val animeId = newDocument.selectFirst("[data-id]")?.attr("data-id")
            ?: newDocument.selectFirst("[data-tip]")?.attr("data-tip")

        return SAnime.create().apply {
            setUrlWithoutDomain(newDocument.location())
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            title = titleElement?.let { getTitle(it) }.orEmpty()
            genre = newDocument.select("div:contains(Genres) > span > a").joinToString(", ") { it.text().trim() }
            author = newDocument.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
            status = parseStatus(newDocument.select("div:contains(Status) > span").text())
            description = buildDescription(newDocument, titleElement)
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

    private fun getTitle(element: Element): String {
        val enTitle = element.text().trim().takeIf(String::isNotBlank)
        val jpTitle = element.attr("data-jp").trim().takeIf(String::isNotBlank)
        return if (useEnglish) {
            enTitle ?: jpTitle ?: element.text().trim()
        } else {
            jpTitle ?: enTitle ?: element.text().trim()
        }
    }

    private fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.trim()?.takeIf(String::isNotBlank)
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf(String::isNotBlank)
        val malScore = document.select("div.bmeta div.meta > div").firstOrNull {
            it.ownText().trim().removeSuffix(":").equals("MAL", ignoreCase = true)
        }?.select("span")?.text()?.trim()

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotBlank()) {
            appendLine(fancyScore).appendLine()
        }

        document.selectFirst("div.synopsis > div.shorting > div.content")?.text()?.let {
            appendLine(it).appendLine()
        }

        val meta = document.select("div.bmeta div.meta > div").mapNotNull { div ->
            val label = div.ownText().trim().removeSuffix(":").removeSuffix(" ")
            var value = div.select("span").text().trim()
            if (label.equals("Duration", ignoreCase = true)) {
                value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { value = "$it min" }
            }
            if (label.isNotBlank() && value.isNotBlank() && label !in listOf("Genres", "Status", "Studios", "Producers", "MAL")) {
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
        document.selectFirst("div.names.font-italic")?.text()?.takeIf { it.isNotBlank() }?.let { namesText ->
            altNames.addAll(
                namesText.split(";").map { it.trim() }
                    .filter { it.isNotBlank() && it != jpTitle && it != enTitle },
            )
        }
        if (altNames.isNotEmpty()) appendLine("Other name(s): ${altNames.joinToString(", ")}").appendLine()

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotBlank()) append(fancyScore)
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
                        relatedDoc.select("div.item.flexserieslist").forEach { element ->
                            val href = element.selectFirst("a[href*=/watch/]")?.attr("href")?.substringBefore("?")?.trim() ?: return@forEach
                            val path = extractAnimePath(href) ?: return@forEach
                            if (path == currentAnimePath) return@forEach
                            val nameElement = element.selectFirst(".info .name") ?: return@forEach
                            resultList.add(
                                SAnime.create().apply {
                                    url = path
                                    title = getTitle(nameElement).trim()
                                    thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
                                },
                            )
                        }
                    }
                } catch (_: Exception) { }
            }

            document.select("section.w-side-section").firstOrNull {
                it.select(".head .title").text().equals("Recommended", ignoreCase = true)
            }?.select("a.item")?.forEach { element ->
                val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@forEach
                if (path == currentAnimePath) return@forEach
                val nameElement = element.selectFirst(".info .name") ?: return@forEach
                resultList.add(
                    SAnime.create().apply {
                        url = path
                        title = getTitle(nameElement).trim()
                        thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
                    },
                )
            }
            resultList
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
        return GET("$baseUrl/ajax/episode/list/$id?vrf=${utils.vrfEncrypt(id)}", listHeaders)
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
            val responseBody = response.body.string() ?: return emptyList()
            val result = json.decodeFromString<ResultResponse>(responseBody)
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

    // ============================ Video Links =============================

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

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
        val downloadUrl: String? = null,
    )

    override fun videoListParse(response: Response): List<Video> {
        val referer = response.request.header("Referer")
        if (referer.isNullOrBlank()) return emptyList()
        val epurl = try {
            referer.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        val responseBody = try {
            response.body.string()
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        val document = try {
            json.decodeFromString<ResultResponse>(responseBody).toDocument()
        } catch (e: Exception) {
            Log.e("AniWave", "Failed to parse video list: ${e.message}")
            return emptyList()
        }

        val hosterSelection = getHosters()
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)!!
        val serverNumSelection = preferences.getStringSet(PREF_SERVER_NUMS_KEY, PREF_SERVER_NUMS_DEFAULT)!!

        val serverData = document.select("div.servers > div.type").flatMap { elem ->
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
                if (hosterSelection.none { serverName.contains(it, true) }) return@mapNotNull null
                if (!typeSelection.contains(label, true)) return@mapNotNull null
                val serverNum = getServerNumber(serverName)
                if (!serverNumSelection.contains(serverNum.toString())) return@mapNotNull null
                VideoData(label, serverId, serverName)
            }
        }.toMutableList()

        // Add mapper API servers (Kiwi-Stream, etc.)
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

                val mapperJson = client.newCall(
                    GET("https://mapper.mewcdn.online/api/mal/$mapperMal/$mapperSlug/$mapperTs", mapperHeaders),
                ).execute().use { json.parseToJsonElement(it.body.string() ?: "").jsonObject }

                for ((key, value) in mapperJson) {
                    if (key.equals("status", true)) continue
                    val obj = value.jsonObject

                    listOf(
                        "sub" to "H-Sub",
                        "dub" to "A-Dub",
                    ).forEach { (typeKey, typeLabel) ->
                        obj[typeKey]?.jsonObject?.let { typeObj ->
                            val linkId = typeObj["url"]?.jsonPrimitive?.content ?: return@let
                            val serverName = mapMapperName(key).lowercase()
                            if (hosterSelection.any { serverName.contains(it, true) } &&
                                typeSelection.contains(typeLabel, true)
                            ) {
                                serverData.add(VideoData(typeLabel, linkId, serverName, extractMapperDownloadUrl(typeObj)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AniWave", "Mapper API failed: ${e.message}")
            }
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        val results = serverData.map { server ->
            executor.submit(
                java.util.concurrent.Callable {
                    try {
                        extractVideo(server, epurl)
                    } catch (t: Throwable) {
                        Log.e("AniWave", "Critical extraction error", t)
                        emptyList()
                    }
                },
            )
        }.mapNotNull {
            try {
                it.get()
            } catch (e: Exception) {
                Log.e("AniWave", "Thread execution error", e)
                null
            }
        }.flatten()
        executor.shutdown()
        return results
    }

    private fun mapMapperName(key: String): String = when {
        key.equals("gogoanime", true) -> "Vidstream"
        key.equals("anivibe", true) -> "Vibe-Stream"
        key.equals("animepahe", true) -> "Kiwi-Stream"
        key.startsWith("Kiwi-Stream", true) -> key
        else -> key
    }

    private fun extractMapperDownloadUrl(typeObj: JsonObject): String? {
        val downloadObj = typeObj["download"]?.jsonObject ?: return null
        for (quality in listOf("1080p", "720p", "480p", "360p")) {
            downloadObj.entries.firstOrNull { it.key.contains(quality, true) }?.let {
                return (it.value as? JsonPrimitive)?.content
            }
        }
        return downloadObj.entries.firstOrNull()?.let { (it.value as? JsonPrimitive)?.content }
    }

    private fun getServerInfo(serverName: String): Triple<String, String, String?> {
        val qualityMatch = Regex("""(\d+)p$""").find(serverName)
        val quality = qualityMatch?.value
        val nameWithoutQuality = if (qualityMatch != null) {
            serverName.substringBeforeLast("-${qualityMatch.value}")
        } else {
            serverName
        }

        val parts = nameWithoutQuality.split("-")
        val numPart = parts.lastOrNull()?.toIntOrNull()
        val baseName = if (numPart != null && parts.size > 1) {
            parts.dropLast(1).joinToString("-")
        } else {
            nameWithoutQuality
        }

        return Triple(baseName.replaceFirstChar { it.uppercase() }, if (numPart != null) "S$numPart" else "S1", quality)
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun extractVideo(server: VideoData, epUrl: String): List<Video> = try {
        val embedLink = getEmbedLink(server.serverId, epUrl)
        if (server.serverName.contains("kiwi", true)) {
            extractFromKiwistream(embedLink, server, epUrl)
        } else {
            extractFromPlayer(resolveEmbedChain(embedLink), embedLink, server)
        }
    } catch (e: Exception) {
        Log.e("AniWave", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    private fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return client.newCall(GET("$baseUrl/ajax/server?get=$serverId", listHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
            json.decodeFromString<ServerResponseDto>(response.body.string() ?: "").result.url
        }
    }

    // ========================= VidWish Extractor ==========================

    private fun extractFromPlayer(embedUrl: String, parentUrl: String, server: VideoData): List<Video> {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            Log.e("AniWave", "Invalid embed URL: $embedUrl")
            return emptyList()
        }

        val pageHeaders = headers.newBuilder().add("Referer", parentUrl).build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).execute().use {
            if (!it.isSuccessful) throw Exception("Player page failed: HTTP ${it.code}")
            it.body.string() ?: throw Exception("Empty player page body")
        }

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            ?: throw Exception("Could not find data-id")

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        val sourcesBody = client.newCall(GET("https://$host/stream/getSources?id=$dataId", apiHeaders)).execute().use {
            if (!it.isSuccessful) throw Exception("getSources failed: HTTP ${it.code}")
            it.body.string() ?: throw Exception("Empty getSources body")
        }

        val data = json.decodeFromString<SourceResponseDto>(sourcesBody)
        val m3u8 = extractM3u8FromSources(data.sources)
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found")

        val subtitles = data.tracks
            ?.filter { it.kind.equals("captions", true) }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val (serverBaseName, serverNum, _) = getServerInfo(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverBaseName $serverNum$typeSuffix - $it" },
            subtitleList = subtitles,
            referer = "https://$host/",
        )
    }

    // ========================= Kiwi-Stream Extractor ======================

    private fun extractFromKiwistream(embedUrl: String, server: VideoData, epUrl: String): List<Video> {
        val (serverBaseName, serverNum, qualityFromName) = getServerInfo(server.serverName)
        val typeSuffix = server.type.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        val qualityLabel = qualityFromName ?: "default"
        val videoLabel = "$serverBaseName $serverNum$typeSuffix - $qualityLabel"
        val useHLS = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)
        val referer = "$baseUrl$epUrl"

        return if (useHLS) {
            val m3u8 = kwikExtractor.getHlsStreamUrl(embedUrl, referer)
            playlistUtils.extractFromHls(
                m3u8,
                videoNameGen = { "$videoLabel - $it" },
                referer = "https://kwik.cx/",
            )
        } else {
            val videoHeaders = headers.newBuilder()
                .add("Referer", "https://kwik.cx/")
                .add("Origin", "https://kwik.cx")
                .build()

            server.downloadUrl?.let { dlUrl ->
                try {
                    val mp4Url = kwikExtractor.getMp4FromOnline(dlUrl)
                    if (mp4Url.isNotBlank() && mp4Url.startsWith("http")) {
                        return listOf(Video(mp4Url, videoLabel, mp4Url, headers = videoHeaders))
                    }
                } catch (e: Exception) {
                    Log.w("AniWave", "Mapper MP4 failed: ${e.message}")
                }
            }

            val mp4Url = kwikExtractor.getMp4StreamUrl(embedUrl, "https://kwik.cx/")
            listOf(Video(mp4Url, videoLabel, mp4Url, headers = videoHeaders))
        }
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

    private fun resolveEmbedChain(url: String): String {
        var currentUrl = url
        repeat(3) {
            try {
                val iframeUrl = client.newCall(GET(currentUrl, refererHeaders)).execute().use {
                    it.asJsoup().selectFirst("iframe[src]")?.attr("abs:src")
                }
                if (iframeUrl.isNullOrBlank()) return currentUrl
                currentUrl = iframeUrl
            } catch (e: Exception) {
                Log.e("AniWave", "Embed chain failed at $currentUrl: ${e.message}")
                return currentUrl
            }
        }
        return currentUrl
    }

    private fun extractAnimePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val path = try {
            href.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return null
        }
        return EP_URL_SUFFIX_REGEX.replace(path, "").takeIf { it.startsWith("/watch/") }
    }

    private fun getServerNumber(serverName: String): Int = serverName.split("-").lastOrNull()?.toIntOrNull() ?: 1

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val type = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(" - $type ", true) },
        )
    }

    @Synchronized
    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "Ongoing Anime", "Currently Airing" -> SAnime.ONGOING
        "Finished Airing", "Completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun resolveSearchAnime(document: Document): Document {
        if (document.location().startsWith("$baseUrl/filter?keyword=")) {
            val foundAnimePath = document.selectFirst(searchAnimeSelector())?.selectFirst("a[href]")?.attr("href")
                ?: throw IllegalStateException("Search element not found")
            return client.newCall(GET(baseUrl + EP_URL_SUFFIX_REGEX.replace(foundAnimePath, ""))).execute().use { it.asJsoup() }
        }
        return document
    }

    private fun getHosters(): Set<String> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        if (hosterSelection.any { it !in HOSTERS_NAMES }) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
            return PREF_HOSTER_DEFAULT.toSet()
        }
        return hosterSelection.toSet()
    }

    companion object {
        private val DOMAINS = arrayOf("animewave.to", "aniwave.id", "aniwave.best", "aniwave.ro") // Domains from https://megaplay.buzz/domains (Base64)
        private val BASE_URLS = DOMAINS.map { "https://$it" }.toTypedArray()

        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        private val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)

        private const val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_DEFAULT = BASE_URLS[0]

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = arrayOf("English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "vidstream"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream")
        private val HOSTERS_NAMES = arrayOf("megaplay", "vidstream", "vidcloud", "kiwi-stream")
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()

        private const val PREF_SERVER_NUMS_KEY = "server_number_selection"
        private val SERVER_NUM_NAMES = arrayOf("Server 1", "Server 2", "Server 3")
        private val SERVER_NUM_VALUES = arrayOf("1", "2", "3")
        private val PREF_SERVER_NUMS_DEFAULT = SERVER_NUM_VALUES.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("Sub", "H-Sub", "Dub", "A-Dub")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES.toSet()

        private const val PREF_LINK_TYPE_KEY = "preferred_link_type" // MP4 needs more work, so forced to use HLS temporarily
        private const val PREF_LINK_TYPE_DEFAULT = true

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        const val SCORE_POS_TOP = "top"
        const val SCORE_POS_BOTTOM = "bottom"
        const val SCORE_POS_NONE = "none"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = arrayOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = arrayOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart App to apply changes", Toast.LENGTH_LONG).show()
                true
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
            entries = arrayOf("Sub", "Hard Sub", "Dub", "Alternate Dub")
            entryValues = TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
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
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // SwitchPreferenceCompat(screen.context).apply {
        //  key = PREF_LINK_TYPE_KEY
        //  title = "Use HLS Links (Kiwi-Stream)"
        //  summary = "Enable for HLS streaming (allows seeking).\nDisable for direct MP4 downloads.\nApplies to Kiwi-Stream only."
        //  setDefaultValue(PREF_LINK_TYPE_DEFAULT)
        //  setOnPreferenceChangeListener { _, newValue ->
        //      preferences.edit().putBoolean(key, newValue as Boolean).commit()
        //  }
        // }.also(screen::addPreference)

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
            entries = arrayOf("Sub", "Hard Sub", "Dub", "Alternate Dub")
            entryValues = TYPES
            setDefaultValue(PREF_TYPES_TOGGLE_DEFAULT)
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
}
