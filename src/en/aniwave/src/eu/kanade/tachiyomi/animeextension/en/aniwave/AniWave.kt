package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
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
        val customDomain = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)
        if (customDomain.isNullOrBlank()) {
            preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        } else {
            customDomain
        }
    }

    override val lang = "en"
    override val supportsLatest = true

    private val utils by lazy { AniWaveUtils() }
    private val preferences by getPreferencesLazy()

    private val refererHeaders = headers.newBuilder().apply {
        add("Referer", "$baseUrl/")
    }.build()

    private val scorePosition get() = preferences.getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)!!
    private val useEnglish get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) == "English"

    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val kwikExtractor by lazy { KwikExtractor(client) }

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
            val href = a.attr("href").substringBefore("?")
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(href, ""))
            title = getTitle(a)
        }
        thumbnail_url = element.selectFirst("div.poster img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
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

    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AniWaveFilters.getSearchParameters(filters)
        val vrf = if (query.isNotBlank()) utils.vrfEncrypt(query) else ""
        var url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", query)
        }.build().toString()

        if (searchParams.genre.isNotBlank()) url += searchParams.genre
        if (searchParams.season.isNotBlank()) url += searchParams.season
        if (searchParams.year.isNotBlank()) url += searchParams.year
        if (searchParams.type.isNotBlank()) url += searchParams.type
        if (searchParams.status.isNotBlank()) url += searchParams.status
        if (searchParams.language.isNotBlank()) url += searchParams.language
        if (searchParams.rating.isNotBlank()) url += searchParams.rating
        if (searchParams.sort.isNotBlank()) url += "&sort=${searchParams.sort}"

        return GET("$url&page=$page&vrf=$vrf", refererHeaders)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun getFilterList(): AnimeFilterList = AniWaveFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.title, h2.title")
        val animeId = newDocument.selectFirst("[data-id]")?.attr("data-id")
            ?: newDocument.selectFirst("[data-tip]")?.attr("data-tip")

        anime.apply {
            setUrlWithoutDomain(newDocument.location())
            if (!animeId.isNullOrBlank()) {
                url += "#$animeId"
            }
            title = titleElement?.let { getTitle(it) }.orEmpty()
            genre = newDocument.select("div:contains(Genres) > span > a").joinToString(", ") { it.text().trim() }
            author = newDocument.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
            status = parseStatus(newDocument.select("div:contains(Status) > span").text())
            description = buildDescription(newDocument, titleElement)
        }
        return anime
    }

    private fun getFancyScore(score: String?): String {
        if (score.isNullOrBlank()) return ""
        return try {
            val scoreBig = score.toBigDecimal()
            if (scoreBig.signum() <= 0) return ""
            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP).toInt().coerceIn(0, 5)
            val scoreString = scoreBig.stripTrailingZeros().toPlainString()
            buildString {
                append("★".repeat(stars))
                append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getTitle(element: Element): String {
        val enTitle = element.text().trim().takeIf(String::isNotBlank)
        val jpTitle = element.attr("data-jp").trim().takeIf(String::isNotBlank)
        return if (useEnglish) enTitle ?: jpTitle ?: element.text().trim() else jpTitle ?: enTitle ?: element.text().trim()
    }

    private fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.trim()?.takeIf(String::isNotBlank)
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf(String::isNotBlank)

        val malScore = document.select("div.bmeta div.meta > div").firstOrNull {
            it.ownText().trim().removeSuffix(":").equals("MAL", ignoreCase = true)
        }?.select("span")?.text()?.trim()

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotBlank()) {
            append(fancyScore).append("\n\n")
        }

        document.selectFirst("div.synopsis > div.shorting > div.content")?.text()?.let {
            append(it).append("\n\n")
        }

        val meta = document.select("div.bmeta div.meta > div").mapNotNull { div ->
            val label = div.ownText().trim().removeSuffix(":").removeSuffix(" ")
            var value = div.select("span").text().trim()
            if (label.equals("Duration", ignoreCase = true)) {
                val minutes = value.filter { it.isDigit() }
                if (minutes.isNotEmpty()) value = "$minutes min"
            }
            if (label.isNotBlank() && value.isNotBlank() && label !in listOf("Genres", "Status", "Studios", "Producers", "MAL")) {
                "$label: $value"
            } else {
                null
            }
        }

        if (meta.isNotEmpty()) append(meta.joinToString(" | ")).append("\n\n")

        val studios = document.select("div:contains(Studios) > span > a").joinToString(", ") { it.text().trim() }
        val producers = document.select("div:contains(Producers) > span > a").joinToString(", ") { it.text().trim() }

        if (studios.isNotBlank()) {
            append("Studio: $studios")
            if (producers.isNotBlank()) append(" (Producers: $producers)")
            append("\n\n")
        } else if (producers.isNotBlank()) {
            append("Producers: $producers\n\n")
        }

        val altNames = mutableListOf<String>()
        if (useEnglish) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }
        document.selectFirst("div.names.font-italic")?.text()?.takeIf { it.isNotBlank() }?.let { namesText ->
            altNames.addAll(namesText.split(";").map { it.trim() }.filter { it.isNotBlank() && it != jpTitle && it != enTitle })
        }
        if (altNames.isNotEmpty()) append("Other name(s): ").append(altNames.joinToString(", ")).append("\n\n")

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotBlank()) append(fancyScore)
    }.trim()

    // ============================== Related ===============================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")
        val request = GET(baseUrl + animeUrl, refererHeaders)
        return if (animeId.isNotBlank()) {
            request.newBuilder().header("X-Anime-Id", animeId).build()
        } else {
            request
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
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
                        val href = element.selectFirst("a[href*=/watch/]")?.attr("href")?.substringBefore("?")?.trim()
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
        return resultList
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfter("#", "")
        val animeUrl = anime.url.substringBefore("#")

        val id = animeId.ifBlank {
            client.newCall(GET(baseUrl + animeUrl)).execute().use { response ->
                var document = response.asJsoup()
                document = resolveSearchAnime(document)
                document.selectFirst("[data-id]")?.attr("data-id") ?: document.selectFirst("[data-tip]")?.attr("data-tip")
                    ?: throw IllegalStateException("Anime ID not found on detail page")
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
        val animeUrl = response.request.header("Referer")!!.toHttpUrl().encodedPath
        val resultBody = response.body.string()
        val result = json.decodeFromString(ResultResponse.serializer(), resultBody)
        return result.toDocument().select(episodeListSelector())
            .map { episodeFromElement(it, animeUrl) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val title = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toIntOrNull() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toIntOrNull() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.find(title) != null) "SoftSub" else ""
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"

        return SEpisode.create().apply {
            this.name = "Episode $epNum" + if (name.isNotEmpty() && name != namePrefix) ": $name" else ""
            this.url = "$ids&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/ep-$epNum"
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = RELEASE_REGEX.find(title)?.let { parseDate(it.groupValues[1]) } ?: 0L
            scanlator = arrayOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(", ")
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl${episode.url.substringAfter("epurl=")}")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()
        return GET("$baseUrl/ajax/server/list?servers=$ids", listHeaders)
    }

    data class VideoData(val type: String, val serverId: String, val serverName: String)

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.header("Referer")!!.toHttpUrl().encodedPath
        val resultBody = response.body.string()
        val document = json.decodeFromString(ResultResponse.serializer(), resultBody).toDocument()
        val hosterSelection = getHosters()
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)!!
        val serverNumSelection = preferences.getStringSet(PREF_SERVER_NUMS_KEY, PREF_SERVER_NUMS_DEFAULT)!!

        val serverData = document.select("div.servers > div.type").flatMap { elem ->
            val label = elem.selectFirst("label")?.text()?.trim()?.replaceFirstChar { it.uppercase() }
                ?: elem.attr("data-type").replaceFirstChar { it.uppercase() }
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
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtMost(4),
        )
        val futures = serverData.map { server ->
            executor.submit(java.util.concurrent.Callable { extractVideo(server, epurl) })
        }
        val results = futures.flatMap { it.get() }
        executor.shutdown()
        return results
    }

    private fun getServerInfo(serverName: String): Pair<String, String> {
        val parts = serverName.split("-")
        val numPart = parts.lastOrNull()?.toIntOrNull()
        val baseName = if (numPart != null && parts.size > 1) {
            parts.dropLast(1).joinToString("-")
        } else {
            serverName
        }.replaceFirstChar { it.uppercase() }
        val serverNum = if (numPart != null) "S$numPart" else "S1"
        return Pair(baseName, serverNum)
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun extractVideo(server: VideoData, epUrl: String): List<Video> = try {
        val embedLink = getEmbedLink(server.serverId, epUrl)

        if (server.serverName.contains("kiwi", true)) {
            extractFromKiwistream(embedLink, server)
        } else {
            val finalEmbedUrl = resolveEmbedChain(embedLink)
            extractFromPlayer(finalEmbedUrl, embedLink, server)
        }
    } catch (e: Exception) {
        Log.e("AniWave", "Failed to extract video from ${server.serverName}: ${e.message}", e)
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
            val body = response.body.string()
            json.decodeFromString(ServerResponseDto.serializer(), body).result.url
        }
    }

    // ========================= VidWish Extractor ==========================

    private fun extractFromPlayer(embedUrl: String, parentUrl: String, server: VideoData): List<Video> {
        val host = embedUrl.toHttpUrl().host
        val streamReferer = "https://$host/"

        val pageHeaders = headers.newBuilder()
            .add("Referer", parentUrl)
            .build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).execute().use {
            if (!it.isSuccessful) throw Exception("Failed to load player page: HTTP ${it.code}")
            it.body.string()
        }

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            ?: throw Exception("Could not find data-id in player page")

        Log.d("AniWave", "VidWish host=$host, dataId=$dataId, server=${server.serverName}")

        val apiHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", embedUrl)
            .add("Origin", "https://$host")
            .build()

        val sourcesUrl = "https://$host/stream/getSources?id=$dataId"
        val sourcesBody = client.newCall(GET(sourcesUrl, apiHeaders)).execute().use {
            if (!it.isSuccessful) throw Exception("getSources returned HTTP ${it.code}")
            it.body.string()
        }

        val data = json.decodeFromString(SourceResponseDto.serializer(), sourcesBody)
        val m3u8 = extractM3u8FromSources(data.sources)
            ?: throw Exception("No m3u8 file found in sources")

        if (!m3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL: $m3u8")
        }

        val subtitles = data.tracks
            ?.filter { it.kind.equals("captions", true) }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        Log.d("AniWave", "VidWish m3u8: ${m3u8.take(80)}...")

        val (serverBaseName, serverNum) = getServerInfo(server.serverName)
        val typeSuffix = if (server.type.isBlank()) "" else " - ${server.type}"

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverBaseName $serverNum$typeSuffix - $it" },
            subtitleList = subtitles,
            referer = streamReferer,
        )
    }

    // ========================= Kwik Extractor =============================

    private fun extractFromKiwistream(embedUrl: String, server: VideoData): List<Video> {
        val kwikUrl = resolveToKwik(embedUrl)

        Log.d("AniWave", "Kiwi-Stream: kwik URL = $kwikUrl")

        val m3u8 = kwikExtractor.getHlsStreamUrl(kwikUrl, referer = embedUrl)

        if (!m3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 from kwik: $m3u8")
        }

        Log.d("AniWave", "Kiwi-Stream m3u8: ${m3u8.take(80)}...")

        val (serverBaseName, serverNum) = getServerInfo(server.serverName)
        val typeSuffix = if (server.type.isBlank()) "" else " - ${server.type}"

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { "$serverBaseName $serverNum$typeSuffix - $it" },
            referer = "https://kwik.cx/",
        )
    }

    private fun resolveToKwik(startUrl: String): String {
        var currentUrl = startUrl

        repeat(5) {
            if (currentUrl.contains("kwik", true)) {
                return currentUrl
            }

            try {
                val doc = client.newCall(GET(currentUrl, refererHeaders)).execute().use { it.asJsoup() }

                val kwikInPage = Regex("""https?://[^\s'"]*kwik[^\s'"]*\.[a-z]+/[^\s'"]*""")
                    .find(doc.html())?.groupValues?.get(0)
                if (kwikInPage != null) {
                    return kwikInPage
                }

                val iframe = doc.selectFirst("iframe[src]")
                if (iframe != null) {
                    val src = iframe.attr("abs:src")
                    if (src.isNotBlank()) {
                        currentUrl = src
                    } else {
                        throw Exception("No kwik.cx URL found in iframe chain")
                    }
                } else {
                    throw Exception("No iframe or kwik URL found at $currentUrl")
                }
            } catch (e: Exception) {
                Log.e("AniWave", "Failed to resolve kwik chain at $currentUrl", e)
                throw e
            }
        }

        throw Exception("Could not find kwik.cx URL after following iframe chain from $startUrl")
    }

    // ========================= Shared Utilities ===========================

    private fun extractM3u8FromSources(sources: kotlinx.serialization.json.JsonElement): String? {
        val rawUrl = when (sources) {
            is JsonObject -> sources["file"]?.jsonPrimitive?.content
            is JsonArray -> {
                val first = sources.firstOrNull() ?: return null
                when (first) {
                    is JsonObject -> first["file"]?.jsonPrimitive?.content
                    is JsonPrimitive -> first.content
                    else -> null
                }
            }
            is JsonPrimitive -> sources.content
        }
        return rawUrl?.takeIf { it.startsWith("http") }
    }

    private fun resolveEmbedChain(url: String): String {
        var currentUrl = url
        repeat(3) {
            try {
                val iframeUrl = client.newCall(GET(currentUrl, refererHeaders)).execute().use { response ->
                    response.asJsoup().selectFirst("iframe[src]")?.attr("abs:src")
                }
                if (!iframeUrl.isNullOrBlank()) {
                    currentUrl = iframeUrl
                } else {
                    return currentUrl
                }
            } catch (e: Exception) {
                Log.e("AniWave", "Failed to resolve iframe chain at $currentUrl", e)
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
        val cleanPath = EP_URL_SUFFIX_REGEX.replace(path, "")
        return if (cleanPath.startsWith("/watch/")) cleanPath else null
    }

    private fun getServerNumber(serverName: String): Int {
        val parts = serverName.split("-")
        return parts.lastOrNull()?.toIntOrNull() ?: 1
    }

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
                .thenByDescending { it.quality.contains(type, true) },
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
        if (hosterSelection.any { HOSTERS_NAMES.indexOf(it) == -1 }) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
            return PREF_HOSTER_DEFAULT.toSet()
        }
        return hosterSelection.toSet()
    }

    companion object {
        private val DOMAINS = arrayOf("animewave.to", "aniwave.id", "aniwave.best", "aniwave.ro")
        private val BASE_URLS = DOMAINS.map { "https://$it" }.toTypedArray()

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_DEFAULT = BASE_URLS[0]

        private val SOFTSUB_REGEX by lazy { Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE) }
        private val RELEASE_REGEX by lazy { Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""") }
        private val EP_URL_SUFFIX_REGEX by lazy { Regex("""/ep-\d+$""") }
        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH) }

        private const val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"

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

        private const val PREF_SERVER_NUMS_KEY = "server_number_selection"

        private val SERVER_NUM_NAMES = arrayOf("Server 1", "Server 2", "Server 3")
        private val SERVER_NUM_VALUES = arrayOf("1", "2", "3")

        private val PREF_SERVER_NUMS_DEFAULT = SERVER_NUM_VALUES.toSet()

        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("Sub", "H-Sub", "Dub", "A-Dub")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES.toSet()

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
                preferences.edit().putString(key, entryValues[findIndexOfValue(newValue as String)] as String).commit()
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
                preferences.edit().putString(key, entryValues[findIndexOfValue(newValue as String)] as String).commit()
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
                preferences.edit().putString(key, entryValues[findIndexOfValue(newValue as String)] as String).commit()
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
                preferences.edit().putString(key, entryValues[findIndexOfValue(newValue as String)] as String).commit()
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
                preferences.edit().putString(key, entryValues[findIndexOfValue(newValue as String)] as String).commit()
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
                    Toast.makeText(screen.context, "Must select at least one server instance", Toast.LENGTH_LONG).show()
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
            entries = arrayOf("Sub", "Dub")
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

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_DOMAIN_KEY
            title = "Custom Domain"
            setDefaultValue(null)
            val currentValue = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)
            summary = if (currentValue.isNullOrBlank()) "Custom domain of your choosing" else "Domain: \"$currentValue\". \nLeave blank to disable. Overrides any domain preferences!"
            setOnPreferenceChangeListener { _, newValue ->
                val newDomain = newValue.toString().trim().removeSuffix("/")
                if (newDomain.isBlank() || URLUtil.isValidUrl(newDomain)) {
                    summary = "Restart to apply changes"
                    Toast.makeText(screen.context, "Restart App to apply changes", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, newDomain).apply()
                    true
                } else {
                    Toast.makeText(screen.context, "Invalid url. Url example: $PREF_DOMAIN_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.also(screen::addPreference)
    }
}
