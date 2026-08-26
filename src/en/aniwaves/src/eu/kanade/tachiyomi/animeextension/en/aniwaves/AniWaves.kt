package eu.kanade.tachiyomi.animeextension.en.aniwaves

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class AniWaves :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniWaves.ru"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "en"
    override val supportsLatest = true

    /**
     * Byse thing. See [ByseExtractor] for more info.
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        set("User-Agent", ByseExtractor.USER_AGENT)
    }

    private val preferences by getPreferencesLazy()
    private val vrf = VrfCodec()
    private val doodExtractor by lazy { DoodExtractor(client) }

    private val http1Client by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
    private val playlistServer by lazy { PlaylistServer(http1Client) }
    private val byseExtractor by lazy { ByseExtractor(http1Client) }

    private val useEnglish get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) == "English"
    private val preferredQuality get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
    private val preferredServer get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
    private val excludedServers: Set<String>
        get() = preferences.getStringSet(PREF_SERVER_EXCLUDE_KEY, PREF_SERVER_EXCLUDE_DEFAULT) ?: PREF_SERVER_EXCLUDE_DEFAULT
    private val preferredType get() = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
    private val excludedTypes: Set<String>
        get() = preferences.getStringSet(PREF_TYPE_EXCLUDE_KEY, PREF_TYPE_EXCLUDE_DEFAULT) ?: PREF_TYPE_EXCLUDE_DEFAULT
    private val scorePosition get() = preferences.getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)!!

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/page/$page", siteHeaders())

    override fun popularAnimeParse(response: Response): AnimesPage = response.asJsoup().toAnimePage()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort_by=last_updated&page=$page", siteHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // "#tag" queries route to the site's exact tag pages
        if (query.startsWith("#")) {
            val slug = query.removePrefix("#").trim()
                .lowercase(Locale.US)
                .replace(" ", "-")
                .replace("[^a-z0-9-]".toRegex(), "")
            val tagPage = if (page == 1) "" else "/page/$page"
            return GET("$baseUrl/tags/$slug$tagPage", siteHeaders())
        }

        val params = filters.getSearchParameters()
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            addQueryParameter("keyword", query)
            params.genres.forEach { addQueryParameter("genre[]", it) }
            params.countries.forEach { addQueryParameter("country[]", it) }
            params.seasons.forEach { addQueryParameter("season[]", it) }
            params.years.forEach { addQueryParameter("year[]", it) }
            params.types.forEach { addQueryParameter("type[]", it) }
            params.statuses.forEach { addQueryParameter("status[]", it) }
            if (params.language.isNotBlank()) addQueryParameter("lang", params.language)
            params.ratings.forEach { addQueryParameter("rating[]", it) }
            if (params.sort.isNotBlank()) addQueryParameter("sort_by", params.sort)
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, siteHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)
    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup().resolveIfSearch()
        val titleElement = document.selectFirst("h1.title, h2.title")
        val animeId = document.selectFirst("#watch-main[data-id]")?.attr("data-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")

        val genres = document.select("div:contains(Genres) > span > a").map { it.text() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER) +
            tagsFromPage(document).sortedWith(String.CASE_INSENSITIVE_ORDER)

        return SAnime.create().apply {
            val cleanUrl = document.selectFirst("#watch-main[data-url]")?.attr("data-url")
                ?.takeIf(String::isNotBlank)
                ?: EP_URL_SUFFIX_REGEX.replace(document.location().substringAfter(baseUrl), "")
            setUrlWithoutDomain(cleanUrl)
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            titleElement?.let(::getTitle)?.takeIf(String::isNotBlank)?.let { title = it }
            thumbnail_url = document.selectFirst("#w-info div.poster img")?.posterSrc()
            genre = genres.joinToString()
            author = document.select("div:contains(Studios) > span > a").joinToString { it.text() }
            status = parseStatus(document.select("div:contains(Status) > span").text())
            description = buildDescription(document, titleElement)
        }
    }

    private fun tagsFromPage(document: Document): List<String> {
        if (!preferences.getBoolean(PREF_SHOW_TAGS_KEY, PREF_SHOW_TAGS_DEFAULT)) return emptyList()
        return document.select("div.tags span a[href^=/tags/]").mapNotNull {
            val tagName = it.attr("title").takeIf(String::isNotBlank)
                ?: it.text().removePrefix("#")
            tagName.takeIf(String::isNotBlank)?.let { name -> "#$name" }
        }
    }

    private fun getTitle(element: Element): String {
        val enTitle = element.text().takeIf(String::isNotBlank)
        val jpTitle = element.attr("data-jp").trim().takeIf(String::isNotBlank)

        return if (useEnglish) {
            enTitle.orEmpty().stripEpisodeSuffix().ifBlank { jpTitle.orEmpty().stripEpisodeSuffix() }
        } else {
            jpTitle.orEmpty().stripEpisodeSuffix().ifBlank { enTitle.orEmpty().stripEpisodeSuffix() }
        }.ifBlank { element.text() }
    }

    private fun String.stripEpisodeSuffix() = replace(EP_TITLE_SUFFIX_REGEX, "")

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
        val enTitle = titleElement?.text()?.takeIf(String::isNotBlank)
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf(String::isNotBlank)

        val malScore = document.select("div.bmeta div.meta > div").firstOrNull {
            it.ownText().removeSuffix(":").equals("Scores", ignoreCase = true)
        }?.selectFirst("span")?.text()?.substringBefore(" ")

        val fancyScore = getFancyScore(malScore)

        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotBlank()) {
            appendLine(fancyScore)
            appendLine()
        }

        document.selectFirst("div.shorting.film-description div.content")?.text()
            ?.let { appendLine(it).appendLine() }

        if (preferences.getBoolean(PREF_SHOW_INFO_KEY, PREF_SHOW_INFO_DEFAULT)) {
            val meta = document.select("div.bmeta div.meta > div").mapNotNull { div ->
                val label = div.ownText().trim().removeSuffix(":").removeSuffix(" ")
                var value = div.selectFirst("span")?.text() ?: ""
                // Collapse duplicated values produced by nested spans ("Manga Manga" -> "Manga")
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

            val studios = document.select("div:contains(Studios) > span > a").joinToString { it.text() }
            val producers = document.select("div:contains(Producers) > span > a").joinToString { it.text() }
            when {
                studios.isNotBlank() && producers.isNotBlank() -> appendLine("**Studio:** $studios (Producers: $producers)")
                studios.isNotBlank() -> appendLine("**Studio:** $studios")
                producers.isNotBlank() -> appendLine("**Producers:** $producers")
            }

            val altNames = mutableListOf<String>()
            if (useEnglish) jpTitle?.let(altNames::add) else enTitle?.let(altNames::add)
            document.selectFirst("div.names.font-italic")?.text()?.takeIf(String::isNotBlank)?.let { namesText ->
                altNames.addAll(
                    namesText.split(",").map { it.trim() }
                        .filter { it.isNotBlank() && it != jpTitle && it != enTitle },
                )
            }
            if (altNames.isNotEmpty()) appendLine("**Other name(s):** ${altNames.joinToString()}")
        }

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotBlank()) {
            appendLine()
            append(fancyScore)
        }
    }.trim()

    // ============================== Related ===============================

    override val disableRelatedAnimesBySearch = true

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeUrl = anime.url.substringBefore("#")
        val animeId = anime.url.substringAfter("#", "")
        val request = GET(baseUrl + animeUrl, siteHeaders())
        return if (animeId.isNotBlank()) {
            request.newBuilder().header("X-Anime-Id", animeId).build()
        } else {
            request
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = try {
        val document = response.asJsoup()
        val seenPaths = extractAnimePath(response.request.url.toString())
            ?.let { mutableSetOf(it) }
            ?: mutableSetOf()

        buildList {
            document.select("#w-seasons .season a").mapNotNull { element ->
                val path = extractAnimePath(element.attr("href").substringBefore("?").trim()) ?: return@mapNotNull null
                if (!seenPaths.add(path)) return@mapNotNull null
                val nameElement = element.selectFirst(".title.d-title") ?: return@mapNotNull null
                SAnime.create().apply {
                    url = path
                    title = getTitle(nameElement)
                    thumbnail_url = element.attr("style")
                        .substringAfter("url(").substringBefore(")").trim('\'', '"')
                }
            }.let(::addAll)

            document.select(RELATED_ITEM_SELECTOR).forEach { element ->
                element.toRelatedAnime(seenPaths)?.let(::add)
            }

            val animeId = response.request.header("X-Anime-Id")
                ?: document.selectFirst("#watch-main[data-id]")?.attr("data-id")
            if (animeId != null) {
                runCatching { recommendedAnime(animeId, response.request.url.toString(), seenPaths) }
                    .getOrNull()?.let(::addAll)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse related anime", e)
        emptyList()
    }

    private fun Element.toRelatedAnime(seenPaths: MutableSet<String>): SAnime? {
        val path = extractAnimePath(attr("href").substringBefore("?").trim()) ?: return null
        if (!seenPaths.add(path)) return null
        val nameElement = selectFirst(".info .name") ?: return null
        val tipId = selectFirst("[data-tip]")?.attr("data-tip").orEmpty()
        return SAnime.create().apply {
            url = if (tipId.isNotBlank()) "$path#$tipId" else path
            title = getTitle(nameElement)
            thumbnail_url = selectFirst("img")?.attr("src")
        }
    }

    private fun recommendedAnime(animeId: String, referer: String, seenPaths: MutableSet<String>): List<SAnime> {
        val recHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Referer", referer)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val result = mutableListOf<SAnime>()
        var page = 1
        while (true) {
            val data = client.newCall(
                GET("$baseUrl/ajax/v2/recommendations?page=$page&mov_id=$animeId", recHeaders),
            ).execute().use { recResponse ->
                if (!recResponse.isSuccessful) return@use null
                runCatching { recResponse.parseAs<RecommendationsResponse>() }.getOrNull()
            } ?: break

            if (!data.status || data.html.isBlank()) break
            data.toDocument().select(RELATED_ITEM_SELECTOR).forEach { element ->
                element.toRelatedAnime(seenPaths)?.let(result::add)
            }
            if (!data.hasMorePages) break
            page++
        }
        return result
    }

    // ============================== Episodes ==============================

    private fun ajaxHeaders(referer: String): Headers = headers.newBuilder().apply {
        add("Accept", "application/json, text/javascript, */*; q=0.01")
        add("Referer", referer)
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfter("#", "")
        val animeUrl = anime.url.substringBefore("#")

        val id = animeId.ifBlank {
            client.newCall(GET(baseUrl + animeUrl, siteHeaders())).execute().use { response ->
                response.asJsoup().resolveIfSearch()
                    .selectFirst("#watch-main[data-id]")?.attr("data-id")
                    ?: throw IllegalStateException("Anime ID not found")
            }
        }

        return GET("$baseUrl/ajax/episode/list/$id?vrf=${vrf.encrypt(id)}", ajaxHeaders(baseUrl + animeUrl))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val epUrl = response.request.header("Referer")?.toHttpUrlOrNull()?.encodedPath ?: return emptyList()

        return try {
            response.parseAs<AjaxHtmlResponse>().toDocument()
                .select(EPISODE_ITEM_SELECTOR)
                .map { episodeFromElement(it, epUrl) }
                .reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse episodes: ${e.message}")
            emptyList()
        }
    }

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val releaseTitle = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")

        val scanlatorFlags = listOf(
            if (element.attr("data-sub") == "1") "Sub" else "",
            if (SOFTSUB_REGEX.containsMatchIn(releaseTitle)) "Soft Sub" else "",
            if (element.attr("data-dub") == "1") "Dub" else "",
        ).filter(String::isNotBlank)

        val episodeName = element.parent()?.select("span.d-title")?.text().orEmpty()

        return SEpisode.create().apply {
            name = buildString {
                append("Episode $epNum")
                if (episodeName.isNotEmpty() && episodeName != "Episode $epNum") append(": $episodeName")
            }
            url = "${element.attr("data-ids")}&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/episode/$epNum"
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = RELEASE_REGEX.find(releaseTitle)?.groupValues?.get(1)
                ?.let(DATE_FORMATTER::tryParse) ?: 0L
            scanlator = scanlatorFlags.joinToString(" & ")
        }
    }

    // ============================= Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url = "<serverIds>&epurl=<path>/episode/<num>"
        val serverParams = episode.url.substringBefore("&epurl=")
        val epurlPart = episode.url.substringAfter("epurl=").substringBefore("&mal=")
        return GET("$baseUrl/ajax/server/list?servers=$serverParams", ajaxHeaders("$baseUrl$epurlPart"))
    }

    class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
    )

    override fun videoListParse(response: Response): List<Video> = runBlocking { parseServers(response) }

    private suspend fun parseServers(response: Response): List<Video> {
        val epUrl = response.request.header("Referer")?.toHttpUrlOrNull()?.encodedPath ?: return emptyList()

        val document = try {
            response.parseAs<AjaxHtmlResponse>().toDocument()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse video list: ${e.message}")
            return emptyList()
        }

        return document.select(SERVER_TYPE_SELECTOR).flatMap { typeElement ->
            val label = when (val type = typeElement.attr("data-type").lowercase(Locale.US)) {
                "sub" -> "Sub"
                "dub" -> "Dub"
                "ssub" -> "Soft Sub"
                else -> type.replaceFirstChar { it.uppercase() }
            }
            if (label in excludedTypes) return@flatMap emptyList()

            typeElement.select("li").mapNotNull { serverElement ->
                val rawName = serverElement.text().trim()
                if (rawName.isBlank()) return@mapNotNull null
                val serverName = HOSTER_LABELS[rawName.lowercase(Locale.US)]
                    ?: rawName.replaceFirstChar { it.uppercase() }
                if (serverName in excludedServers) return@mapNotNull null

                val serverId = serverElement.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null

                VideoData(label, serverId, serverName)
            }
        }.parallelCatchingFlatMap { extractVideo(it, epUrl) }
    }

    // ============================= Utilities ==============================

    private suspend fun extractVideo(server: VideoData, epUrl: String): List<Video> {
        val embedUrl = getEmbedUrl(server.serverId, epUrl)

        // Note: myvidplay contains "vidplay" — the dood check must come first
        return when {
            embedUrl.contains("dood", true) || embedUrl.contains("myvidplay", true) ->
                extractFromDood(embedUrl, server)

            embedUrl.contains("gn1r5n", true) ||
                embedUrl.contains("byfms", true) ||
                embedUrl.contains("filemoon", true) ->
                extractFromByse(embedUrl, server, epUrl)

            else -> extractFromEchoVideo(embedUrl, server)
        }
    }

    private fun videoLabel(server: VideoData): String {
        val typeSuffix = server.type.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()
        return server.serverName + typeSuffix
    }

    /**
     * Dood pages here are titled with a file hash and expose no resolution
     * metadata; the stream is mostly 1080p, which is why we hardcode it here.
     */
    private fun extractFromDood(embedUrl: String, server: VideoData): List<Video> {
        val label = videoLabel(server)
        return doodExtractor.videosFromUrl(embedUrl, label).map { video ->
            Video(
                url = video.url,
                quality = "$label - Doodstream 1080p",
                videoUrl = video.videoUrl,
                headers = video.headers,
                subtitleTracks = video.subtitleTracks,
            )
        }
    }

    private suspend fun getEmbedUrl(serverLinkId: String, epUrl: String): String {
        val requestHeaders = ajaxHeaders(baseUrl + epUrl)

        // Current API — data-link-id is already VRF-encoded, send as-is
        var sourcesInfo = ""
        client.newCall(
            GET("$baseUrl/ajax/sources?id=$serverLinkId&asi=0&autoPlay=0", requestHeaders),
        ).awaitSuccess().use { response ->
            val raw = response.body.string()
            sourcesInfo = "sources HTTP ${response.code}: ${raw.take(160)}"
            if (response.isSuccessful) {
                val url = raw.parseAs<SourcesResponse>().result?.url?.takeIf(String::isNotBlank)
                if (url != null) return url
            }
        }

        // Legacy API — encrypted URL that needs a VRF round-trip
        var legacyInfo = ""
        client.newCall(
            GET("$baseUrl/ajax/server/$serverLinkId?vrf=${vrf.encrypt(serverLinkId)}", requestHeaders),
        ).execute().use { response ->
            val raw = response.body.string()
            legacyInfo = "server HTTP ${response.code}: ${raw.take(160)}"
            if (response.isSuccessful) {
                return vrf.decrypt(raw.parseAs<LegacyServerResponse>().result.url)
            }
        }

        throw Exception("Embed resolve failed — $sourcesInfo | $legacyInfo")
    }

    // ==================== EchoVideo Family Extractor ======================
    // Vidplay / MyCloud / DatSaV — all resolve to play.echovideo.ru embeds.

    private suspend fun extractFromEchoVideo(embedUrl: String, server: VideoData): List<Video> {
        val httpUrl = embedUrl.toHttpUrlOrNull() ?: run {
            Log.e(TAG, "Invalid EchoVideo URL: $embedUrl")
            return emptyList()
        }

        val videoId = httpUrl.pathSegments.lastOrNull()
            ?: throw Exception("Could not extract video ID from EchoVideo URL")

        // Preserve the dynamic embed path segment (embed-0/embed-1/embed-20/…)
        val sourcesUrl = embedUrl.substringBeforeLast("/") + "/getSources?id=$videoId"

        val data = client.newCall(
            GET(sourcesUrl, okHeaders("Accept" to "*/*", "Referer" to embedUrl)),
        ).awaitSuccess().parseAs<VidplaySourcesResponse>()

        val label = videoLabel(server)
        val videoReferer = "https://${httpUrl.host}/"
        val videoHeaders = okHeaders(
            "Referer" to videoReferer,
            "User-Agent" to ByseExtractor.USER_AGENT,
        )
        val subtitles = data.subtitles()

        // Quality-keyed direct files (DatSaV)
        val qualityFiles = data.sources?.qualityFiles.orEmpty()
        if (qualityFiles.isNotEmpty()) {
            return qualityFiles.flatMap { (quality, urls) ->
                urls.map { url ->
                    Video(
                        url = url,
                        quality = "$label - ${DATSAV_QUALITY_LABELS[quality] ?: quality}",
                        videoUrl = url,
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
        }

        // Single stream URL (master or media playlist)
        val m3u8 = data.sources?.streamUrl?.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 from EchoVideo")

        return hlsVideosFromPlaylist(m3u8, label, videoReferer, subtitles)
    }

    /**
     * Builds videos for HLS streams.
     *
     * Handles two upstream quirks:
     *  - Echovideo annoys the hell out of you by giving you trash data for
     *    you to decode as actual m3u8;
     *  - They even fake you out with a m3u8 playlist end at the top of
     *    the file, which PlaylistUtils lib takes for granted and won't
     *    load any videos.
     *
     * We rewrite the streams to the proxy server to get rid of that stuff;
     * segments stay upstream unless [proxySegments] routes them
     * through its proxy with pinned headers a.k.a BYFMS my beloved.
     */
    private suspend fun hlsVideosFromPlaylist(
        m3u8Url: String,
        label: String,
        referer: String,
        subtitles: List<Track>,
        proxySegments: Boolean = false,
    ): List<Video> {
        val fetchHeaders = okHeaders(
            "Accept" to "*/*",
            "Referer" to referer,
            "User-Agent" to ByseExtractor.USER_AGENT,
        )

        suspend fun fetchPlaylistText(url: String): String = client.newCall(GET(url, fetchHeaders)).awaitSuccess().use {
            decodeNumericHls(it.body.string())
        }

        val masterBase = m3u8Url.toHttpUrl()
        val masterText = try {
            fetchPlaylistText(m3u8Url)
        } catch (e: Exception) {
            Log.w(TAG, "Master playlist failed (${masterBase.host}): ${e.message}")
            return emptyList()
        }
        val videoHeaders = okHeaders(
            "Referer" to referer,
            "User-Agent" to ByseExtractor.USER_AGENT,
        )

        val variants = buildList {
            if (masterText.contains("#EXT-X-STREAM-INF")) {
                var pendingQuality: String? = null
                for (raw in masterText.lines()) {
                    val line = raw.trim()
                    when {
                        line.startsWith("#EXT-X-STREAM-INF") -> {
                            pendingQuality = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                                ?.groupValues?.get(1)?.let { "${it}p" }
                                ?: Regex("""BANDWIDTH=(\d+)""").find(line)
                                    ?.groupValues?.get(1)?.let { "${it.toInt() / 1000}kbps" }
                        }
                        line.isNotEmpty() && !line.startsWith("#") -> {
                            masterBase.resolve(line)?.toString()?.let { abs ->
                                add(abs to pendingQuality)
                            }
                            pendingQuality = null
                        }
                    }
                }
            } else {
                add(m3u8Url to null)
            }
        }

        return variants.mapNotNull { (variantUrl, quality) ->
            runCatching {
                var text = absolutizeHlsUrls(fetchPlaylistText(variantUrl), variantUrl.toHttpUrl())
                if (!text.contains("#EXT-X-ENDLIST") && text.contains("#EXTINF")) {
                    text = text.trimEnd() + "\n#EXT-X-ENDLIST\n"
                }
                if (proxySegments) {
                    text = proxySegmentUrls(text, referer)
                }
                val localUrl = playlistServer.register(text)
                Video(
                    url = localUrl,
                    quality = "$label - ${quality ?: "auto"}",
                    videoUrl = localUrl,
                    headers = videoHeaders,
                    subtitleTracks = subtitles,
                )
            }.onFailure {
                Log.w(TAG, "Variant failed ($variantUrl): ${it.message}")
            }.getOrNull()
        }
    }

    /** Rewrites segment / key / map URIs to the local segment proxy. */
    private fun proxySegmentUrls(text: String, referer: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trim()
        when {
            t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") ->
                t.replace(URI_ATTRIBUTE_REGEX) { m ->
                    "URI=\"" + playlistServer.segmentProxyUrl(m.groupValues[1], referer, ByseExtractor.USER_AGENT) + "\""
                }
            t.isEmpty() || t.startsWith("#") -> line
            else -> playlistServer.segmentProxyUrl(t, referer, ByseExtractor.USER_AGENT)
        }
    }

    /**
     * Echovideo annoys you with playlists with fake headers, like always.
     * We detect and decode this hell; return plain-text input untouched.
     */
    private fun decodeNumericHls(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty() || !trimmed[0].isDigit()) return text
        val bytes = ArrayList<Byte>(text.length / 3)
        for (raw in text.split('\n', '\r')) {
            val value = raw.trim().toIntOrNull() ?: return text
            if (value !in 0..255) return text
            bytes.add(value.toByte())
        }
        return bytes.takeIf { it.isNotEmpty() }
            ?.let { String(it.toByteArray(), Charsets.UTF_8) }
            ?: text
    }

    private fun absolutizeHlsUrls(text: String, base: HttpUrl): String = text.lines().joinToString("\n") { line ->
        val t = line.trim()
        when {
            t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") ->
                t.replace(URI_ATTRIBUTE_REGEX) { m ->
                    "URI=\"" + (base.resolve(m.groupValues[1])?.toString() ?: m.groupValues[1]) + "\""
                }
            t.isEmpty() || t.startsWith("#") -> line
            else -> base.resolve(t)?.toString() ?: line
        }
    }

    // ========================= Byse (BYFMS) Extractor =====================

    private suspend fun extractFromByse(embedUrl: String, server: VideoData, epUrl: String): List<Video> {
        val sources = try {
            byseExtractor.extract(embedUrl, embedParent = "$baseUrl$epUrl", embedOrigin = baseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Byse extraction failed for ${server.serverName}: ${e.message}")
            return emptyList()
        }

        return sources.flatMap { source ->
            hlsVideosFromPlaylist(
                m3u8Url = source.url,
                label = videoLabel(server),
                referer = "https://${embedUrl.toHttpUrl().host}/",
                subtitles = source.subtitles,
                proxySegments = true,
            )
        }
    }

    // ========================= Shared Utilities ===========================

    private fun siteHeaders(): Headers = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    private fun okHeaders(vararg pairs: Pair<String, String>): Headers = Headers.Builder().apply {
        pairs.forEach { (name, value) -> add(name, value) }
    }.build()

    private fun Document.resolveIfSearch(): Document {
        if (!location().startsWith("$baseUrl/filter?keyword=")) return this
        val foundPath = selectFirst(ANIME_ITEM_SELECTOR)?.selectFirst("a[href]")?.attr("href")
            ?: throw IllegalStateException("Search element not found")
        return client.newCall(GET(baseUrl + EP_URL_SUFFIX_REGEX.replace(foundPath, ""), siteHeaders()))
            .execute().useAsJsoup()
    }

    private fun Document.toAnimePage(): AnimesPage = AnimesPage(
        select(ANIME_ITEM_SELECTOR).map(::popularAnimeFromElement),
        select(NEXT_PAGE_SELECTOR).isNotEmpty(),
    )

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link: Element? = element.selectFirst("a.name, a.d-title")
        if (link != null) {
            setUrlWithoutDomain(EP_URL_SUFFIX_REGEX.replace(link.attr("href").substringBefore("?"), ""))
            title = getTitle(link)
        }
        thumbnail_url = element.selectFirst("div.poster img, img")?.posterSrc()
    }

    private fun Element.posterSrc(): String {
        val dataSrc: String = attr("data-src")
        return dataSrc.ifBlank { attr("src") }
    }

    private fun extractAnimePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: href
        return ANIME_PATH_REGEX.find(path)?.value
    }

    override fun List<Video>.sort(): List<Video> {
        val qualityTiers = PREF_QUALITY_VALUES.reversed()
        val typeTag = " - $preferredType "

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferredQuality) }
                .thenByDescending { video -> qualityTiers.indexOfLast { tier -> video.quality.contains(tier) } }
                .thenByDescending { it.quality.contains(preferredServer, ignoreCase = true) }
                .thenByDescending { it.quality.contains(typeTag, ignoreCase = true) },
        )
    }

    private fun parseStatus(statusString: String): Int {
        val status = statusString.lowercase(Locale.US)
        return when {
            "finished" in status || "completed" in status -> SAnime.COMPLETED
            "currently" in status || "ongoing" in status || "airing" in status -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "AniWaves"

        private val DOMAINS = arrayOf("aniwaves.ru")
        private val BASE_URLS = DOMAINS.map { "https://$it" }.toTypedArray()

        private const val ANIME_ITEM_SELECTOR = "div.ani.items > div.item"
        private const val NEXT_PAGE_SELECTOR = "nav > ul.pagination > li.active ~ li"
        private const val EPISODE_ITEM_SELECTOR = "div.episodes ul li a"
        private const val SERVER_TYPE_SELECTOR = "div.servers div.type[data-type]"
        private const val RELATED_ITEM_SELECTOR = "#w-related .scaff.side.items a.item"

        /** DatSaV quality tiers on its direct-file endpoints. */
        private val DATSAV_QUALITY_LABELS = mapOf(
            "FHD" to "1080p",
            "HD" to "720p",
            "HQ" to "480p",
            "SD" to "360p",
        )

        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        private val EP_TITLE_SUFFIX_REGEX = Regex("""\s+Episode\s+\d+.*$""")
        private val EP_URL_SUFFIX_REGEX = Regex("""/(?:ep-\d+|episode/\d+)$""")
        private val DUPLICATE_REGEX = Regex("""^(.+?)\s+\1$""")
        private val ANIME_PATH_REGEX = Regex("""^/watch/[^/?#]+""")
        private val URI_ATTRIBUTE_REGEX = Regex("""URI="([^"]+)"""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT)

        private val META_EXCLUDED_LABELS = listOf("Genres", "Status", "Studios", "Producers", "Scores")

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_DEFAULT = BASE_URLS[0]

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = arrayOf("English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "vidplay"

        private const val PREF_SERVER_EXCLUDE_KEY = "excluded_servers"
        private val PREF_SERVER_EXCLUDE_DEFAULT = emptySet<String>()

        private val HOSTERS = arrayOf("Vidplay", "BYFMS", "DGHG", "MyCloud", "DatSaV")
        private val HOSTER_LABELS = mapOf(
            "vidplay" to "Vidplay",
            "byfms" to "BYFMS",
            "dghg" to "DGHG",
            "mycloud" to "MyCloud",
            "datsav" to "DatSaV",
        )

        private const val PREF_TYPE_EXCLUDE_KEY = "excluded_types"
        private val PREF_TYPE_EXCLUDE_DEFAULT = emptySet<String>()
        private val TYPES = arrayOf("Sub", "Soft Sub", "Dub")

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
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred Domain"
            entries = DOMAINS
            entryValues = BASE_URLS
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = PREF_TITLE_LANG_LIST
            entryValues = PREF_TITLE_LANG_LIST
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_EXCLUDE_KEY
            title = "Exclude Servers"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_SERVER_EXCLUDE_DEFAULT)
            summary = "Hide videos from the selected servers."
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Type"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_EXCLUDE_KEY
            title = "Exclude Types"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_TYPE_EXCLUDE_DEFAULT)
            summary = "Hide videos of the selected types."
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score Display"
            entries = arrayOf("Top of description", "Bottom of description", "Don't show")
            entryValues = arrayOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)
            setDefaultValue(PREF_SCORE_POSITION_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_INFO_KEY
            title = "Show Info"
            summary = "Display metadata (Type, Country, Aired, Source, Studio, Other names, etc.) in the description."
            setDefaultValue(PREF_SHOW_INFO_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_KEY
            title = "Show Tags"
            summary = "Display tags as clickable chips (prefixed with #). Clicking triggers an in-app tag search"
            setDefaultValue(PREF_SHOW_TAGS_DEFAULT)
        }.also(screen::addPreference)
    }
}
