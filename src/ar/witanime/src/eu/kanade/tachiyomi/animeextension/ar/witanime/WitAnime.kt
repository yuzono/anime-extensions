package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class WitAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "WIT ANIME"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .rateLimit(permits = 1, period = 2.seconds) { url ->
                val baseHost = runCatching { baseUrl.toHttpUrl().host }.getOrNull()
                url.host == baseHost
            }
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", USER_AGENT)
        .add("sec-ch-ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    // Extractors
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences by getPreferencesLazy {
        val oldQuality = getString("preferred_quality", null)
        if (oldQuality != null && getString(PREF_QUALITY_KEY, null) == null) {
            val mapped = when {
                oldQuality.contains("1080") -> "FHD"
                oldQuality.contains("720") -> "HD"
                oldQuality.contains("480") || oldQuality.contains("360") -> "SD"
                else -> "FHD"
            }
            edit().putString(PREF_QUALITY_KEY, mapped).apply()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            runCatching {
                Filters.fetchFilters(client, baseUrl, preferences)
            }
        }
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/browse/page/$page"
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()

        val animeList = doc.select("a[href*='/anime/'], a[href*='/movie/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val thumb = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = thumb
                    this.url = href.removePrefix(baseUrl)
                    fetch_type = FetchType.Episodes
                }
            }

        val hasNext = doc.selectFirst("a[href*='page/${page + 1}'], a[href*='page=${page + 1}']") != null
        return AnimesPage(animeList, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val req = Request.Builder()
            .url("$baseUrl/new-episodes?page=$page")
            .headers(headers)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", "$baseUrl/")
            .build()

        val response = client.newCall(req).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception("فشل تحميل آخر الحلقات (HTTP $code)")
        }

        val jsonObject = response.parseAs<JsonObject>()
        val html = jsonObject["html"]?.jsonPrimitive?.contentOrNull ?: ""
        val hasOlder = jsonObject["hasOlder"]?.jsonPrimitive?.booleanOrNull ?: false
        val doc = org.jsoup.Jsoup.parseBodyFragment(html, baseUrl)

        val animeList = doc.select("a[href*='/watch/']")
            .distinctBy { extractSlug(it.attr("href")) }
            .mapNotNull { el ->
                val href = el.attr("href")
                val slug = extractSlug(href)
                val isMovie = href.contains("/watch/movie/")
                val animePath = if (isMovie) "/movie/$slug" else "/anime/$slug"
                val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val thumb = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = thumb
                    this.url = animePath
                    fetch_type = FetchType.Episodes
                }
            }
        return AnimesPage(animeList, hasOlder)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("page", page.toString())
            }.build().toString()
        } else {
            "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                for (filter in filters) {
                    when (filter) {
                        is Filters.StatusFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("status", filter.toUriPart())
                        }

                        is Filters.TypeFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("type", filter.toUriPart())
                        }

                        is Filters.SortFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("sort", filter.toUriPart())
                        }

                        is Filters.SeasonFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("season", filter.toUriPart())
                        }

                        is Filters.YearFilter -> if (filter.state.trim().isNotBlank()) {
                            addQueryParameter("year", filter.state.trim())
                        }

                        is Filters.GenreFilter -> {
                            filter.getSelectedValues().forEach { genre ->
                                addQueryParameter("genres[]", genre)
                            }
                        }

                        else -> {}
                    }
                }
            }.build().toString()
        }

        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val animeList = doc.select("a[href*='/anime/'], a[href*='/movie/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val thumb = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = thumb
                    this.url = href.removePrefix(baseUrl)
                    fetch_type = FetchType.Episodes
                }
            }

        val hasNext = doc.selectFirst("a[href*='page=${page + 1}'], a[href*='page/${page + 1}']") != null
        return AnimesPage(animeList, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = Filters.build(preferences, baseUrl) {
        scope.launch {
            runCatching {
                Filters.fetchFilters(client, baseUrl, preferences)
            }
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val title = doc.selectFirst("h1")?.text()?.ifBlank { null } ?: anime.title
        val thumb = doc.selectFirst("img[src*='posters']")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("src") }
        } ?: anime.thumbnail_url

        val synopsis = doc.selectFirst("p.leading-relaxed")?.text()?.trim() ?: ""
        val statusText = doc.selectFirst("span.bg-neutral-800")?.text() ?: ""
        val score = doc.selectFirst("span.backdrop-blur-sm, span[dir='ltr']:contains(.)")?.text()?.trim()?.toDoubleOrNull()
        val genres = doc.select("div.flex.flex-wrap.gap-2 span.rounded-full")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return SAnime.create().apply {
            this.url = anime.url
            this.title = title
            thumbnail_url = thumb
            genre = genres.joinToString(", ")
            status = when {
                statusText.contains("مستمر") || statusText.contains("يعرض") -> SAnime.ONGOING
                statusText.contains("مكتمل") || statusText.contains("منتهي") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (score != null && score > 0.0) {
                    append("⭐ $score\n\n")
                }
                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val epElements = doc.select("div[data-episode-list] a.group.rounded-xl[href*='/watch/']").distinctBy { it.attr("href") }
        if (epElements.isNotEmpty()) {
            epElements.forEach { el ->
                val href = el.attr("href")
                val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 0f
                val epName = el.selectFirst("span, div:contains(الحلقة)")?.text()?.trim()
                    ?: el.text().trim().ifBlank { "الحلقة ${epNum.toInt()}" }
                episodes.add(
                    SEpisode.create().apply {
                        this.url = href.removePrefix(baseUrl)
                        name = epName
                        episode_number = epNum
                    },
                )
            }
        } else {
            val genericEps = doc.select("div[data-episode-list] a[href*='/watch/']").distinctBy { it.attr("href") }
            if (genericEps.isNotEmpty()) {
                genericEps.forEach { el ->
                    val href = el.attr("href")
                    val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 0f
                    episodes.add(
                        SEpisode.create().apply {
                            this.url = href.removePrefix(baseUrl)
                            name = "الحلقة ${epNum.toInt()}"
                            episode_number = epNum
                        },
                    )
                }
            } else {
                val movieBtn = doc.selectFirst("a[href*='/watch/movie/'], a.group\\/cta[href*='/watch/'], a[href*='/watch/']")
                if (movieBtn != null) {
                    val href = movieBtn.attr("href")
                    episodes.add(
                        SEpisode.create().apply {
                            this.url = href.removePrefix(baseUrl)
                            name = "الحلقة 1"
                            episode_number = 1f
                        },
                    )
                }
            }
        }

        return if (episodes.size > 1 && episodes.first().episode_number < episodes.last().episode_number) {
            episodes.reversed()
        } else {
            episodes
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val cleanEpUrl = "/" + episode.url.removePrefix(cleanBaseUrl).trimStart('/')
        val watchUrl = "$cleanBaseUrl$cleanEpUrl"
        val httpUrl = watchUrl.toHttpUrl()

        var watchResp = client.newCall(GET(watchUrl, headers)).execute()
        if (watchResp.code == 429) {
            watchResp.close()
            delay(2500)
            watchResp = client.newCall(GET(watchUrl, headers)).execute()
        }

        if (!watchResp.isSuccessful) {
            val code = watchResp.code
            watchResp.close()
            if (code == 429) {
                throw Exception("تم حظر الطلب مؤقتاً (HTTP 429) بسبب قيود الموقع - يُرجى تبديل وضع الطيران أو تفعيل VPN لتغيير الـ IP والانتظار لحظة")
            }
            throw Exception("فشل تحميل صفحة الحلقة (HTTP $code)")
        }

        val respCookieHeaders = watchResp.headers("Set-Cookie")
        val parsedCookies = respCookieHeaders.mapNotNull { Cookie.parse(httpUrl, it) }
        if (parsedCookies.isNotEmpty()) {
            runCatching { client.cookieJar.saveFromResponse(httpUrl, parsedCookies) }
        }

        val jarCookies = runCatching {
            client.cookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }.getOrNull().orEmpty()
        val rawRespCookies = respCookieHeaders.joinToString("; ") { it.substringBefore(";") }
        val combinedCookies = buildString {
            if (jarCookies.isNotBlank()) append(jarCookies)
            if (rawRespCookies.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append(rawRespCookies)
            }
        }

        val doc = watchResp.asJsoup()

        // 1. Check for legacy WordPress servers list if present
        val legacyServers = doc.select("ul#episode-servers li a")
        if (legacyServers.isNotEmpty()) {
            return legacyServers.distinctBy { it.text().substringBefore(" -") }.map { el ->
                val encoded = el.attr("data-url").takeUnless { it.isBlank() }
                    ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
                    ?: el.attr("onclick").substringAfter("'").substringBefore("'")
                        .let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() ?: it }
                Hoster(
                    hosterName = el.text().trim(),
                    hosterUrl = encoded ?: "",
                    internalData = "",
                )
            }
        }

        // 2. Modern watchPlayer JSON manifest
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val htmlContent = doc.html()
        val baseHttpUrl = cleanBaseUrl.toHttpUrl()
        val rawSourcesUrl = SOURCES_REGEX.find(htmlContent)?.let { match ->
            (1..4).firstNotNullOfOrNull { idx -> match.groups[idx]?.value }
        }?.replace("\\/", "/")?.trim()

        val sourcesPath = when {
            !rawSourcesUrl.isNullOrBlank() -> {
                val candidate = if (rawSourcesUrl.startsWith("http")) rawSourcesUrl else "$cleanBaseUrl/${rawSourcesUrl.trimStart('/')}"
                val parsed = runCatching { candidate.toHttpUrl() }.getOrNull()
                if (parsed != null && parsed.host == baseHttpUrl.host) {
                    candidate
                } else {
                    "$cleanBaseUrl/${cleanEpUrl.trim('/')}/sources"
                }
            }

            else -> "$cleanBaseUrl/${cleanEpUrl.trim('/')}/sources"
        }

        val reqBuilder = Request.Builder()
            .url(sourcesPath)
            .post("".toRequestBody(null))
            .headers(headers)
            .header("Referer", watchUrl)
            .header("Origin", cleanBaseUrl)
            .header("X-CSRF-TOKEN", csrfToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (combinedCookies.isNotBlank()) {
            reqBuilder.header("Cookie", combinedCookies)
        }

        var sourcesResp = client.newCall(reqBuilder.build()).execute()
        if (sourcesResp.code == 429) {
            sourcesResp.close()
            delay(2500)
            sourcesResp = client.newCall(reqBuilder.build()).execute()
        }

        if (!sourcesResp.isSuccessful) {
            val code = sourcesResp.code
            sourcesResp.close()
            if (code == 429) {
                throw Exception("تم حظر الطلب مؤقتاً (HTTP 429) بسبب قيود الموقع - يُرجى تبديل وضع الطيران أو تفعيل VPN لتغيير الـ IP والانتظار لحظة")
            }
            throw Exception("فشل جلب خوادم الحلقة (HTTP $code)")
        }

        val postCookieHeaders = sourcesResp.headers("Set-Cookie")
        val parsedPostCookies = postCookieHeaders.mapNotNull { Cookie.parse(sourcesPath.toHttpUrl(), it) }
        if (parsedPostCookies.isNotEmpty()) {
            runCatching { client.cookieJar.saveFromResponse(sourcesPath.toHttpUrl(), parsedPostCookies) }
        }
        val rawPostCookies = postCookieHeaders.joinToString("; ") { it.substringBefore(";") }
        val sessionCookies = buildString {
            if (combinedCookies.isNotBlank()) append(combinedCookies)
            if (rawPostCookies.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append(rawPostCookies)
            }
        }

        val sourcesJson = sourcesResp.parseAs<JsonObject>()
        val hosterMap = mutableMapOf<String, MutableList<ServerItem>>()

        // Streaming players
        val playersObj = sourcesJson["players"]?.jsonObject
        playersObj?.forEach { (quality, playerArray) ->
            playerArray.jsonArray.forEach { p ->
                val pObj = p.jsonObject
                val token = pObj["token"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val rawLabel = pObj["label"]?.jsonPrimitive?.contentOrNull ?: "Stream"
                val label = rawLabel.replaceFirstChar { it.uppercase() }
                hosterMap.getOrPut(label) { mutableListOf() }.add(
                    ServerItem(
                        quality = quality,
                        token = token,
                        isDownload = false,
                        watchUrl = watchUrl,
                        csrfToken = csrfToken,
                        cookies = sessionCookies,
                    ),
                )
            }
        }

        // Downloads
        val downloadsObj = sourcesJson["downloads"]?.jsonObject
        downloadsObj?.forEach { (quality, dlArray) ->
            dlArray.jsonArray.forEach { d ->
                val dObj = d.jsonObject
                val token = dObj["token"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val rawLabel = dObj["label"]?.jsonPrimitive?.contentOrNull ?: "Download"
                val label = "[DL] " + rawLabel.replaceFirstChar { it.uppercase() }
                hosterMap.getOrPut(label) { mutableListOf() }.add(
                    ServerItem(
                        quality = quality,
                        token = token,
                        isDownload = true,
                        watchUrl = watchUrl,
                        csrfToken = csrfToken,
                        cookies = sessionCookies,
                    ),
                )
            }
        }

        if (hosterMap.isEmpty()) {
            throw Exception("لم يتم العثور على خوادم تشغيل لهذه الحلقة")
        }

        return hosterMap.map { (serverName, items) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = watchUrl,
                internalData = items.toJsonString(),
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        if (hoster.internalData.isBlank()) {
            return extractVideosFromUrl(hoster.hosterUrl, hoster.hosterName).sortByPreference()
        }

        val items = runCatching { hoster.internalData.parseAs<List<ServerItem>>() }.getOrNull()
            ?: return emptyList()

        val cleanBaseUrl = baseUrl.trimEnd('/')

        return items.parallelCatchingFlatMap { item ->
            if (!TOKEN_REGEX.matches(item.token)) return@parallelCatchingFlatMap emptyList()

            val endpoint = if (item.isDownload) "/watch/download-source/" else "/watch/stream-source/"
            val gateEndpoint = if (item.isDownload) "/watch/download-gate/" else "/watch/stream-gate/"

            // 1. Authorize token in session
            val authBuilder = Request.Builder()
                .url("$cleanBaseUrl$endpoint${item.token}")
                .post("".toRequestBody(null))
                .headers(headers)
                .header("Referer", item.watchUrl)
                .header("Origin", cleanBaseUrl)
                .header("X-CSRF-TOKEN", item.csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/plain, */*")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")

            if (item.cookies.isNotBlank()) {
                authBuilder.header("Cookie", item.cookies)
            }

            val authResp = runCatching { client.newCall(authBuilder.build()).execute() }.getOrNull()
            val authCookies = authResp?.headers("Set-Cookie")?.joinToString("; ") { it.substringBefore(";") } ?: ""
            val gateCookies = buildString {
                if (item.cookies.isNotBlank()) append(item.cookies)
                if (authCookies.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append(authCookies)
                }
            }
            authResp?.close()

            // 2. Gate endpoint to follow redirect.
            // DO NOT pass the watch page as Referer: WitAnime gate returns 404 when Referer is sent!
            val gateBuilder = Request.Builder()
                .url("$cleanBaseUrl$gateEndpoint${item.token}")
                .headers(headers)
                .removeHeader("Referer")
                .header("Sec-Fetch-Dest", if (item.isDownload) "document" else "iframe")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")

            if (gateCookies.isNotBlank()) {
                gateBuilder.header("Cookie", gateCookies)
            }

            val gateClient = client.newBuilder().followRedirects(true).build()
            val gateResp = runCatching { gateClient.newCall(gateBuilder.build()).execute() }.getOrNull()
                ?: return@parallelCatchingFlatMap emptyList()

            val (finalUrl, gateHtml) = gateResp.use { resp ->
                val fUrl = resp.request.url.toString()
                val html = if (fUrl.contains("/watch/")) resp.body?.string().orEmpty() else ""
                fUrl to html
            }

            val resolvedUrl = if (!finalUrl.contains("/watch/")) {
                finalUrl
            } else {
                val gateDoc = org.jsoup.Jsoup.parse(gateHtml, cleanBaseUrl)
                gateDoc.selectFirst("iframe")?.let { it.attr("abs:src").ifBlank { it.attr("src") }.ifBlank { it.attr("data-src") } }
                    ?: gateDoc.selectFirst("source, video")?.let { it.attr("abs:src").ifBlank { it.attr("src") } }
                    ?: finalUrl
            }

            extractVideosFromUrl(resolvedUrl, "${hoster.hosterName} - ${item.quality}")
        }.sortByPreference()
    }

    private suspend fun extractVideosFromUrl(url: String, qualityLabel: String): List<Video> = runCatching {
        when {
            url.contains("mp4upload.com") -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }

            url.contains("4shared.com") -> {
                sharedExtractor.videosFromUrl(url, qualityLabel)?.let { listOf(it) } ?: emptyList()
            }

            url.contains("mediafire.com") -> {
                val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                val directUrl = doc.selectFirst("a#downloadButton")?.attr("abs:href")
                    ?: doc.selectFirst("a[href*='download']")?.attr("abs:href")
                if (!directUrl.isNullOrBlank()) {
                    listOf(
                        Video(
                            videoUrl = directUrl,
                            videoTitle = qualityLabel,
                            headers = headers,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            url.contains("dood") || url.contains("ds2play") || url.contains("d000d") -> {
                doodExtractor.videosFromUrl(url)
            }

            url.contains("ok.ru") || url.contains("odnoklassniki") -> {
                okruExtractor.videosFromUrl(url)
            }

            url.contains("dailymotion.com") || url.contains("dai.ly") -> {
                dailymotionExtractor.videosFromUrl(url)
            }

            url.contains("streamtape.com") || url.contains("streamtape.net") -> {
                streamtapeExtractor.videoFromUrl(url)?.let { listOf(it) } ?: emptyList()
            }

            url.endsWith(".m3u8") || url.contains(".m3u8?") -> {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { q -> "$qualityLabel - $q" },
                )
            }

            url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains(".mp4?") -> {
                listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = qualityLabel,
                        headers = headers,
                    ),
                )
            }

            else -> {
                universalExtractor.videosFromUrl(url, headers, prefix = "$qualityLabel - ")
            }
        }
    }.getOrElse { emptyList() }

    private fun List<Video>.sortByPreference(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy<Video> {
                when (quality) {
                    "FHD" -> it.videoTitle.contains("FHD", ignoreCase = true) || it.videoTitle.contains("1080")
                    "HD" -> (it.videoTitle.contains("HD", ignoreCase = true) && !it.videoTitle.contains("FHD", ignoreCase = true)) || it.videoTitle.contains("720")
                    "SD" -> it.videoTitle.contains("SD", ignoreCase = true) || it.videoTitle.contains("480") || it.videoTitle.contains("360")
                    else -> it.videoTitle.contains(quality, ignoreCase = true)
                }
            }.thenBy { it.videoTitle },
        ).reversed()
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_BASE_URL_KEY,
            default = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            summary = "%s",
            restartRequired = true,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p (FHD)", "720p (HD)", "480p (SD)"),
            entryValues = listOf("FHD", "HD", "SD"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf("Auto", "Mp4upload", "4Shared", "Mega", "Videa"),
            entryValues = listOf("auto", "Mp4upload", "4Shared", "Mega", "Videa"),
        )
    }

    private fun extractSlug(url: String): String {
        val clean = url.substringBefore("#").substringBefore("?").trimEnd('/')
        return when {
            clean.contains("/watch/movie/") -> clean.substringAfter("/watch/movie/")
            clean.contains("/watch/") -> clean.substringAfter("/watch/").substringBefore("/")
            clean.contains("/anime/") -> clean.substringAfter("/anime/")
            clean.contains("/movie/") -> clean.substringAfter("/movie/")
            else -> clean.substringAfterLast("/")
        }
    }

    @Serializable
    private data class ServerItem(
        val quality: String = "",
        val token: String = "",
        val isDownload: Boolean = false,
        val watchUrl: String = "",
        val csrfToken: String = "",
        val cookies: String = "",
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://witanime.site"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "FHD"

        private val TOKEN_REGEX by lazy { Regex("^[a-zA-Z0-9_\\-]+$") }
        private val SOURCES_REGEX by lazy {
            Regex("""sourcesUrl\s*:\s*(?:'([^']+)'|"([^"]+)"|&quot;([^&]+)&quot;|&#0*39;([^&]+)&#0*39;)""")
        }
    }
}
