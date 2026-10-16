package eu.kanade.tachiyomi.animeextension.en.animecube

import android.util.Base64
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.youtubeextractor.YoutubeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class AnimeCube : AnimeHttpSource() {

    override val name = "AnimeCube"

    override val baseUrl = "https://animecube.live"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(permits = 3, period = 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val desktopHeaders by lazy {
        headersBuilder().build()
    }

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, desktopHeaders) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, desktopHeaders) }
    private val youtubeExtractor by lazy { YoutubeExtractor(client, desktopHeaders) }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ============================== Popular Anime ==============================
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val html = response.body.string()
        val animes = parseAnimesFromHtml(html)
        return AnimesPage(animes, hasNextPage = false)
    }

    // ============================== Latest Updates =============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Search Anime ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmedQuery = query.trim()
        val httpUrl = trimmedQuery.toHttpUrlOrNull()
        val baseHost = baseUrl.toHttpUrl().host
        if (httpUrl != null && httpUrl.scheme == "https" && httpUrl.host == baseHost) {
            return GET(httpUrl, headers)
        }

        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmedQuery)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/anime/")) {
            val anime = animeDetailsParse(response).apply {
                url = requestUrl.substringAfter(baseUrl).substringBefore("?")
            }
            return AnimesPage(listOf(anime), hasNextPage = false)
        }

        val html = response.body.string()
        val allAnimes = parseAnimesFromHtml(html)
        val query = response.request.url.queryParameter("q")?.lowercase()
        val filtered = if (!query.isNullOrBlank()) {
            allAnimes.filter { it.title.lowercase().contains(query) }
        } else {
            allAnimes
        }
        return AnimesPage(filtered, hasNextPage = false)
    }

    // ============================== Anime Details ==============================
    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.body.string()
        val animeData = extractAnimeJsonFromRsc(html)

        return SAnime.create().apply {
            val doc by lazy { Jsoup.parse(html, baseUrl) }
            val studio = animeData?.studio?.takeIf { it.isNotBlank() }
                ?: animeData?.studios?.firstOrNull { it.isNotBlank() }
                ?: animeData?.releasePreview?.studio?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("*:matchesOwn((?i)^studio:?)")?.nextElementSibling()?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("*:containsOwn(Studio)")?.text()?.substringAfter("Studio")?.trim(':', ' ')?.takeIf { it.isNotBlank() }
                ?: "AnimeCube"

            author = studio
            artist = studio
            if (animeData != null) {
                title = animeData.title ?: ""
                description = animeData.description
                thumbnail_url = animeData.coverImage
                genre = animeData.genres?.joinToString()
                status = when (animeData.status?.lowercase()) {
                    "ongoing" -> SAnime.ONGOING
                    "completed", "season-completed" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            } else {
                title = doc.selectFirst("h1")?.text()?.trim() ?: ""
                description = doc.selectFirst("p")?.text()?.trim()
                thumbnail_url = doc.selectFirst("img")?.attr("abs:src")
            }
            initialized = true
        }
    }

    // ============================== Episode List ===============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body.string()
        val animeData = extractAnimeJsonFromRsc(html) ?: return emptyList()
        val animeSlug = animeData.slug ?: response.request.url.pathSegments.lastOrNull() ?: ""

        val episodes = mutableListOf<SEpisode>()

        animeData.primaryTabs?.forEach { tab ->
            val tabId = tab.id ?: "primary-1"
            tab.seasons?.forEach { season ->
                val seasonId = season.id ?: "tab-1"
                season.episodes?.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    val epNumber = ep.number ?: 1f
                    val epTitle = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${epNumber.toInt()}"

                    val epUrl = ep.redirectHref?.trim()?.takeIf { it.isNotEmpty() }?.let { redirect ->
                        val httpUrl = (if (redirect.startsWith("http")) redirect else "$baseUrl$redirect").toHttpUrlOrNull()
                        if (httpUrl != null) {
                            val targetSlug = httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: animeSlug
                            val targetSeasonId = httpUrl.queryParameter("seasonId")
                                ?: httpUrl.queryParameter("season")
                                ?: seasonId
                            val targetEpId = httpUrl.queryParameter("episodeId")
                                ?: httpUrl.queryParameter("episode")
                                ?: epId
                            "/anime/$targetSlug?primaryTabId=primary-1&seasonId=$targetSeasonId&episodeId=$targetEpId"
                        } else {
                            null
                        }
                    } ?: "/anime/$animeSlug?primaryTabId=$tabId&seasonId=$seasonId&episodeId=$epId"

                    episodes.add(
                        SEpisode.create().apply {
                            url = epUrl
                            name = epTitle
                            episode_number = epNumber
                            date_upload = synchronized(dateFormat) { dateFormat.tryParse(ep.publishedAt) }
                        },
                    )
                }
            }
        }

        return episodes.reversed()
    }

    // ============================== Seasons ====================================
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Hosters & Videos ===========================
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    private var cachedVersions: SourcesVersionsDto? = null
    private var versionsFetchedAt: Long = 0L
    private val versionsMutex = Mutex()

    private fun generateObfHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decryptAesGcm(ciphertextB64: String, keyMaterial: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.toByteArray(Charsets.UTF_8))
        val raw = Base64.decode(ciphertextB64, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private suspend fun getSourcesVersions(forceRefresh: Boolean = false): SourcesVersionsDto? = versionsMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedVersions != null && (now - versionsFetchedAt) < 180_000L) {
            return cachedVersions
        }

        val obf = generateObfHex()
        val reqHeaders = headersBuilder()
            .add("X-Obf", obf)
            .add("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(GET("$baseUrl/api/anime-sources-versions", reqHeaders)).awaitSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return cachedVersions
        }

        val etag = response.header("X-Registry-ETag") ?: response.header("x-registry-etag")
        val bodyStr = response.body.string()
        val payload = runCatching { bodyStr.parseAs<EncryptedPayloadDto>() }.getOrNull()

        val decodedJson = if (payload?.d != null && !etag.isNullOrBlank()) {
            runCatching { decryptAesGcm(payload.d, "$obf|$etag") }.getOrNull()
        } else {
            bodyStr
        } ?: return cachedVersions

        val versions = runCatching { decodedJson.parseAs<SourcesVersionsDto>() }.getOrNull()
        if (!versions?.bySeason.isNullOrEmpty()) {
            cachedVersions = versions
            versionsFetchedAt = now
        }
        versions ?: cachedVersions
    }

    private suspend fun resolveVersionHash(slug: String, primaryTabId: String, seasonId: String): String? {
        for (force in listOf(false, true)) {
            val versions = getSourcesVersions(forceRefresh = force) ?: continue
            val exactHash = versions.bySeason?.get(slug)?.get(primaryTabId)?.get(seasonId)
            if (!exactHash.isNullOrBlank()) {
                return exactHash
            }
        }
        return null
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val httpUrl = (baseUrl + episode.url).toHttpUrl()
        val slug = httpUrl.pathSegments.getOrNull(1) ?: ""
        val reqTabId = httpUrl.queryParameter("primaryTabId") ?: "primary-1"
        val reqSeasonId = httpUrl.queryParameter("seasonId") ?: "tab-1"
        val episodeId = httpUrl.queryParameter("episodeId") ?: ""

        val versionV = resolveVersionHash(slug, reqTabId, reqSeasonId) ?: return emptyList()

        val obf = generateObfHex()
        val apiUrl = "$baseUrl/api/anime/$slug/episode/$episodeId/sources".toHttpUrl().newBuilder()
            .addQueryParameter("v", versionV)
            .addQueryParameter("primaryTabId", reqTabId)
            .addQueryParameter("seasonId", reqSeasonId)
            .build()
            .toString()

        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/anime/$slug")
            .add("X-Obf", obf)
            .add("Accept", "application/json")
            .build()

        val response = client.newCall(GET(apiUrl, reqHeaders)).awaitSuccess()

        val bodyStr = response.body.string()
        val encryptedDto = runCatching { bodyStr.parseAs<EncryptedPayloadDto>() }.getOrNull()
        val rawSources = when {
            encryptedDto != null && !encryptedDto.d.isNullOrBlank() && obf.isNotBlank() && versionV.isNotBlank() -> {
                val decryptedJson = runCatching {
                    decryptAesGcm(encryptedDto.d, "$obf|$versionV")
                }.getOrNull()
                decryptedJson?.let {
                    runCatching { it.parseAs<EpisodeSourcesResponse>().sources }.getOrNull()
                }
            }
            encryptedDto != null && !encryptedDto.sources.isNullOrEmpty() -> encryptedDto.sources
            else -> runCatching { bodyStr.parseAs<EpisodeSourcesResponse>().sources }.getOrNull()
        } ?: return emptyList()

        return rawSources.mapNotNull { source ->
            val videoId = source.videoId
            val privateId = source.privateId
            val qualitySuffix = if (!source.quality.isNullOrBlank()) " - ${source.quality}" else ""
            val subSuffix = if (source.goodSub == true) " (GoodSub)" else ""

            when (source.platform?.lowercase()) {
                "dailymotion", null, "" -> {
                    val targetId = privateId.takeUnless { it.isNullOrBlank() }
                        ?: videoId.takeUnless { it.isNullOrBlank() }
                        ?: return@mapNotNull null
                    val dailyUrl = "https://geo.dailymotion.com/player/x1h6z6.html?video=$targetId"
                    Hoster(
                        hosterName = "Dailymotion$qualitySuffix$subSuffix",
                        hosterUrl = dailyUrl,
                    )
                }
                "rumble" -> {
                    val targetId = videoId.takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                    val rumbleId = if (targetId.startsWith("v", ignoreCase = true)) targetId else "v$targetId"
                    val rumbleUrl = "https://rumble.com/embed/$rumbleId"
                    Hoster(
                        hosterName = "Rumble$qualitySuffix$subSuffix",
                        hosterUrl = rumbleUrl,
                    )
                }
                "youtube" -> {
                    val targetId = videoId.takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                    val ytUrl = "https://www.youtube.com/watch?v=$targetId"
                    Hoster(
                        hosterName = "YouTube$qualitySuffix$subSuffix",
                        hosterUrl = ytUrl,
                    )
                }
                else -> null
            }
        }.distinctBy { it.hosterUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return when {
            url.contains("dailymotion.com") -> withContext(Dispatchers.IO) {
                dailymotionExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ")
                    .distinctBy { it.videoUrl }
            }
            url.contains("rumble.com") -> withContext(Dispatchers.IO) {
                rumbleExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ")
                    .distinctBy { it.videoUrl }
            }
            url.contains("youtube.com") || url.contains("youtu.be") -> {
                youtubeExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ")
                    .distinctBy { it.videoUrl }
            }
            else -> emptyList()
        }
    }

    // ============================== Helper Functions ===========================
    private fun parseAnimesFromHtml(html: String): List<SAnime> {
        val animes = mutableListOf<SAnime>()
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("a[href^=\"/anime/\"], a[href*=\"animecube.live/anime/\"]").forEach { element ->
            val href = element.attr("href")
            val cleanPath = href.toHttpUrlOrNull()?.encodedPath ?: href.substringBefore("?")
            if (cleanPath == "/anime" || cleanPath == "/anime/") return@forEach

            val altTitle = element.selectFirst("img")?.attr("alt")?.trim()
            val ariaTitle = element.attr("aria-label").trim()
            val title = when {
                !altTitle.isNullOrBlank() -> altTitle
                ariaTitle.isNotBlank() -> ariaTitle
                else -> cleanPath.substringAfterLast("/").replace("-", " ")
                    .split(" ").joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
            }

            if (title.isBlank() || animes.any { it.url == cleanPath }) return@forEach

            val thumbUrl = element.selectFirst("img")?.let { img ->
                val src = img.attr("abs:src")
                if (src.isNotBlank()) src else img.attr("src")
            }

            animes.add(
                SAnime.create().apply {
                    url = cleanPath
                    this.title = title
                    thumbnail_url = thumbUrl
                    author = "AnimeCube"
                    artist = "AnimeCube"
                },
            )
        }

        return animes
    }

    private val rscPushRegex = Regex("""self\.__next_f\.push\(\[(\d+),\s*"((?:[^"\\]|\\.)*)"]\)""")

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n) {
                when (val next = s[i + 1]) {
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '/' -> {
                        sb.append('/')
                        i += 2
                    }
                    'b' -> {
                        sb.append('\b')
                        i += 2
                    }
                    'f' -> {
                        sb.append('\u000C')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < n) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(c)
                                i += 1
                            }
                        } else {
                            sb.append(c)
                            i += 1
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun extractAnimeJsonFromRsc(html: String): AnimeDetailDto? {
        val rscBuilder = StringBuilder()
        rscPushRegex.findAll(html).forEach { match ->
            val chunkRaw = match.groupValues[2]
            rscBuilder.append(unescapeJsString(chunkRaw))
        }
        val fullRsc = rscBuilder.toString()

        val marker = """"anime":{"""
        var currIdx = 0

        while (currIdx < fullRsc.length) {
            val idx = fullRsc.indexOf(marker, currIdx)
            if (idx == -1) break

            val startIdx = idx + marker.length - 1
            var depth = 0
            var endIdx = startIdx
            var inString = false
            var escape = false

            for (i in startIdx until fullRsc.length) {
                val c = fullRsc[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') {
                        depth++
                    } else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            endIdx = i + 1
                            break
                        }
                    }
                }
            }

            if (depth == 0 && endIdx > startIdx) {
                val jsonBlock = fullRsc.substring(startIdx, endIdx)
                val dto = runCatching {
                    jsonBlock.parseAs<AnimeDetailDto>()
                }.getOrNull()

                if (dto != null && (!dto.primaryTabs.isNullOrEmpty() || !dto.title.isNullOrBlank())) {
                    return dto
                }
            }

            currIdx = if (endIdx > idx) endIdx else idx + marker.length
        }

        return null
    }
}
