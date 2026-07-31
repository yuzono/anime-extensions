package eu.kanade.tachiyomi.animeextension.en.reanime

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import fi.iki.elonen.NanoHTTPD
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class ReAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ReAnime"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl: String = "https://reanime.to"

    private val apiUrl: String = "$baseUrl/api/v1"

    override val lang = "en"

    override val supportsLatest = true

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val preferredLang: String
        get() = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private fun apiHeaders(referer: String = "$baseUrl/home"): Headers = headers.newBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    override val client by lazy {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = 5, period = 1L, unit = TimeUnit.SECONDS)
            .build()
    }

    private data class AnimeMeta(val anilistId: Int, val subbed: Int, val dubbed: Int)
    private val animeMetaCache = java.util.concurrent.ConcurrentHashMap<String, AnimeMeta>()
    private var nextLatestCursor: String? = null

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 36
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "popularity_desc")
            addQueryParameter("limit", "36")
            addQueryParameter("offset", offset.toString())
        }.build()

        return GET(url, apiHeaders("$baseUrl/search"))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<ReAnimeSearchResponseDto>()
        val animes = dto.results.mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = (dto.offset + dto.limit) < dto.total

        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) nextLatestCursor = null

        val urlBuilder = "$apiUrl/home/latest-aired".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "24")
            addQueryParameter("lang", preferredLang)
            if (page > 1 && nextLatestCursor != null) {
                addQueryParameter("cursor", nextLatestCursor!!)
            }
        }
        return GET(urlBuilder.build(), apiHeaders("$baseUrl/home"))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = response.parseAs<ReAnimeLatestDto>()
        nextLatestCursor = dto.nextCursor

        val animes = dto.data.mapNotNull { it.toSAnime(titleLanguage) }
        return AnimesPage(animes, dto.hasMore)
    }

    // =============================== Search ===============================
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getFilterList(): AnimeFilterList = ReAnimeFilters.FILTER_LIST

    @RequiresApi(Build.VERSION_CODES.O)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            val limit = 36
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", ((page - 1) * limit).toString())

            if (query.isNotBlank()) addQueryParameter("q", query)

            filters.forEach { filter ->
                when (filter) {
                    is ReAnimeFilters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is ReAnimeFilters.FormatFilter -> filter.getValue()?.let { addQueryParameter("format", it) }
                    is ReAnimeFilters.StatusFilter -> filter.getValue()?.let { addQueryParameter("status", it) }
                    is ReAnimeFilters.SeasonFilter -> filter.getValue()?.let { addQueryParameter("season", it) }
                    is ReAnimeFilters.OriginFilter -> filter.getValue()?.let { addQueryParameter("country", it) }
                    is ReAnimeFilters.YearFilter -> filter.getValue()?.let { addQueryParameter("year", it) }
                    is ReAnimeFilters.GenreFilter -> {
                        val genres = filter.getSelectedValues()
                        if (genres.isNotEmpty()) addQueryParameter("genre", genres)
                    }
                    is ReAnimeFilters.CharacterFilter -> {
                        val characters = filter.getSelectedValues()
                        if (characters.isNotEmpty()) addQueryParameter("character", characters)
                    }
                    is ReAnimeFilters.StaffFilter -> {
                        val staff = filter.getSelectedValues()
                        if (staff.isNotEmpty()) addQueryParameter("staff", staff)
                    }
                    is ReAnimeFilters.StudioFilter -> {
                        val studios = filter.getSelectedValues()
                        if (studios.isNotEmpty()) addQueryParameter("studio", studios)
                    }
                    is ReAnimeFilters.TagFilter -> {
                        val tags = filter.getSelectedValues()
                        if (tags.isNotEmpty()) addQueryParameter("tag", tags)
                    }
                    else -> {}
                }
            }
        }.build()

        return GET(url, apiHeaders("$baseUrl/search"))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<ReAnimeSearchResponseDto>()
        val animes = dto.results.mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = (dto.offset + dto.limit) < dto.total

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<ReAnimeAnimeDetailDto>()

        // Cache metadata for episode/video logic
        if (dto.anilistId != null && dto.anilistId > 0) {
            animeMetaCache[dto.animeId] = AnimeMeta(
                anilistId = dto.anilistId,
                subbed = dto.subbed ?: 0,
                dubbed = dto.dubbed ?: 0,
            )
        }

        return dto.toSAnime(titleLanguage).apply {
            description = buildDescription(dto)
        }
    }

    private fun buildDescription(dto: ReAnimeAnimeDetailDto): String = buildString {
        dto.averageScore?.let { score ->
            getFancyScore(score).takeIf { it.isNotEmpty() }?.let {
                if (isNotBlank()) append("\n\n")
                append(it)
            }
        }
        dto.description?.let { raw ->
            val cleaned = raw
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleaned.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(cleaned)
            }
        }

        dto.title?.romaji?.takeIf { it.isNotBlank() && it != dto.title?.preferredTitle(titleLanguage) }?.let {
            append("\n\n**Romaji**: $it")
        }

        dto.synonyms?.takeIf { it.isNotEmpty() }?.let {
            append("\n**Alternative Titles**: ${it.joinToString(", ")}")
        }

        dto.season?.let { season ->
            val year = dto.seasonYear?.let { " $it" } ?: ""
            append("\n**Season**: ${season.replaceFirstChar { c -> c.titlecase() }}$year")
        }

        dto.duration?.takeIf { it > 0 }?.let {
            append("\n**Duration**: ${it}m")
        }

        dto.rating?.takeIf { it.isNotBlank() }?.let {
            append("\n**Rating**: $it")
        }

        // All tracker links — only shown if ID is valid
        val trackers = buildList {
            dto.anilistId?.takeIf { it > 0 }?.let {
                add("[AniList](https://anilist.co/anime/$it)")
            }
            dto.malId?.takeIf { it > 0 }?.let {
                add("[MAL](https://myanimelist.net/anime/$it)")
            }
            dto.kitsuId?.takeIf { it > 0 }?.let {
                add("[Kitsu](https://kitsu.io/anime/$it)")
            }
            dto.anidbId?.takeIf { it > 0 }?.let {
                add("[AniDB](https://anidb.net/anime/$it)")
            }
            dto.animePlanetId?.takeIf { it.isNotBlank() }?.let {
                add("[Anime-Planet](https://www.anime-planet.com/anime/$it)")
            }
            dto.animeNewsNetworkId?.takeIf { it > 0 }?.let {
                add("[ANN](https://www.animenewsnetwork.com/encyclopedia/anime.php?id=$it)")
            }
            dto.anisearchId?.takeIf { it > 0 }?.let {
                add("[Anisearch](https://www.anisearch.com/anime/$it)")
            }
            dto.simklId?.takeIf { it > 0 }?.let {
                add("[Simkl](https://simkl.com/anime/$it)")
            }
            dto.tmdbId?.takeIf { it > 0 }?.let {
                add("[TMDB](https://www.themoviedb.org/tv/$it)")
            }
            dto.tvdbId?.takeIf { it > 0 }?.let {
                add("[TVDB](https://thetvdb.com/series/$it)")
            }
            dto.imdbId?.takeIf { it.isNotBlank() }?.let {
                add("[IMDB](https://www.imdb.com/title/$it)")
            }
        }
        if (trackers.isNotEmpty()) {
            append("\n\n**Trackers**: ${trackers.joinToString(" · ")}")
        }

        dto.externalLinks?.filter { it.type == "STREAMING" }?.takeIf { it.isNotEmpty() }?.let { links ->
            append("\n\n**Streaming**:")
            links.forEach { link ->
                link.site?.let { site -> link.url?.let { url -> append("\n- [$site]($url)") } }
            }
        }

        dto.trailer?.takeIf { it.site == "youtube" && !it.id.isNullOrBlank() }?.let {
            append("\n\n**Trailer**: [YouTube](https://www.youtube.com/watch?v=${it.id})")
        }
    }

    private fun getFancyScore(score: Int): String {
        if (score <= 0) return ""
        val stars = (score / 20.0).roundToInt().coerceIn(1, 5)
        return "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $score"
    }

    // ============================== Related Anime ==============================
    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<ReAnimeAnimeDetailDto>()
        val currentId = dto.animeId

        return buildList {
            dto.relations?.mapNotNull { rel ->
                if (rel.animeId.isBlank()) return@mapNotNull null
                if (rel.animeId == currentId) return@mapNotNull null // ← Skip self
                val relTitle = rel.title?.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rel.animeId
                    title = relTitle
                    thumbnail_url = rel.coverImage?.extraLarge ?: rel.coverImage?.large
                    status = parseStatus(null)
                    genre = buildString {
                        rel.format?.let { append(it) }
                        rel.season?.let { s ->
                            if (isNotBlank()) append(" · ")
                            append(s.replaceFirstChar { c -> c.titlecase() })
                        }
                        rel.seasonYear?.let { y -> append(" $y") }
                    }.takeIf { it.isNotBlank() }
                }
            }?.let(::addAll)

            fetchRecommendations(dto.animeId).mapNotNull { rec ->
                if (rec.id.isBlank()) return@mapNotNull null
                if (rec.id == currentId) return@mapNotNull null
                val recTitle = rec.title.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rec.id
                    title = recTitle
                    thumbnail_url = rec.coverImage?.extraLarge ?: rec.coverImage?.large
                    status = parseStatus(rec.status)
                    genre = rec.genres?.joinToString()?.takeIf { it.isNotBlank() }
                }
            }.let(::addAll)
        }
    }

    private fun fetchRecommendations(slug: String): List<ReAnimeRecommendationDto> {
        return try {
            val res = client.newCall(
                GET("$apiUrl/anime/$slug/recommendations", apiHeaders("$baseUrl/anime/$slug")),
            ).execute()
            res.use {
                if (!it.isSuccessful) return emptyList()
                val dto = it.parseAs<ReAnimeRecommendationsDto>()
                if (dto.success) dto.recommendations else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = "$apiUrl/anime/${anime.url}/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "2000")
            .build()
        return GET(url, apiHeaders("$baseUrl/anime/${anime.url}"))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val dto = response.parseAs<ReAnimeEpisodeListDto>()

        val segments = response.request.url.pathSegments
        val animeIdx = segments.indexOf("anime")
        val animeSlug = if (animeIdx != -1 && animeIdx + 1 < segments.size) segments[animeIdx + 1] else ""

        val meta = animeMetaCache[animeSlug] ?: fetchAnimeMeta(animeSlug)
        val maxSub = meta.subbed
        val maxDub = meta.dubbed

        return dto.data.map { ep ->
            SEpisode.create().apply {
                val epNum = ep.episode_number
                episode_number = epNum.toFloat()

                val safeEpisodeId = ep.episodeId ?: "ep-${epNum.toInt()}"
                url = "$animeSlug/$safeEpisodeId"

                val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()

                val baseName = if (ep.title.isNotBlank() && !ep.title.contains("Episode", ignoreCase = true)) {
                    "Episode $epNumStr: ${ep.title}"
                } else {
                    "Episode $epNumStr"
                }

                name = buildString {
                    append(baseName)
                    if (ep.is_recap) append(" [Recap]")
                    if (ep.is_filler) append(" [Filler]")
                }

                val hasSub = epNum <= maxSub
                val hasDub = epNum <= maxDub

                scanlator = when {
                    hasSub && hasDub -> "Sub & Dub"
                    hasSub -> "Sub"
                    hasDub -> "Dub"
                    else -> null
                }

                date_upload = parseEpisodeDate(ep.aired)
            }
        }.reversed()
    }

    private fun fetchAnimeMeta(slug: String): AnimeMeta {
        return try {
            val res = client.newCall(
                GET("$apiUrl/anime/$slug", apiHeaders("$baseUrl/anime/$slug")),
            ).execute()
            res.use {
                if (!it.isSuccessful) return@use AnimeMeta(0, 0, 0)
                val dto = it.parseAs<ReAnimeAnimeDetailDto>()
                AnimeMeta(
                    anilistId = dto.anilistId ?: 0,
                    subbed = dto.subbed ?: 0,
                    dubbed = dto.dubbed ?: 0,
                )
            }
        } catch (_: Exception) {
            AnimeMeta(0, 0, 0)
        }
    }

    private fun parseEpisodeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val cleaned = dateStr.substringBefore(".").removeSuffix("Z")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ============================== Video Links ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        val bits = episode.url.split("/")
        val slug = bits.getOrNull(0) ?: ""
        val epId = bits.getOrNull(1) ?: ""
        val epNumber = epId.removePrefix("ep-")

        val meta = animeMetaCache[slug]

        if (meta != null && meta.anilistId > 0) {
            return GET(
                "$baseUrl/api/flix/${meta.anilistId}/$epNumber",
                apiHeaders("$baseUrl/watch/$slug?ep=$epNumber"),
            )
        }

        // Cache miss: fetch anime page to extract anilist_id
        return GET("$baseUrl/anime/$slug?_ep=$epNumber", headers)
    }

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val requestUrl = response.request.url.toString()

        if (!requestUrl.contains("/api/flix/")) {
            return@runBlocking handleAnimePageResponse(response)
        }

        val referer = response.request.header("Referer") ?: "$baseUrl/home"
        parseFlixServers(response, referer)
    }

    private suspend fun handleAnimePageResponse(response: Response): List<Video> {
        val html = response.body.string()

        val anilistId = Regex("""anilist_id:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()

        val slug = response.request.url.pathSegments.lastOrNull() ?: ""
        val epNumber = response.request.url.queryParameter("_ep") ?: return emptyList()

        val subbed = Regex(""",subbed:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val dubbed = Regex(""",dubbed:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        animeMetaCache[slug] = AnimeMeta(anilistId, subbed, dubbed)

        val referer = "$baseUrl/watch/$slug?ep=$epNumber"

        val flixRes = client.newCall(
            GET("$baseUrl/api/flix/$anilistId/$epNumber", apiHeaders(referer)),
        ).execute()

        return parseFlixServers(flixRes, referer)
    }

    private suspend fun parseFlixServers(response: Response, referer: String): List<Video> {
        val parsed = response.use {
            if (!it.isSuccessful) return emptyList()
            it.parseAs<ReAnimeVideoResponseDto>()
        }

        if (!parsed.success || parsed.servers.isNullOrEmpty()) return emptyList()

        val audioTag = if (preferredAudio == "dub") "[Dub]" else "[Sub]"

        val videos = parsed.servers.parallelCatchingFlatMap { server ->
            val dataLink = server.dataLink ?: return@parallelCatchingFlatMap emptyList()
            val label = buildString {
                when (server.dataType) {
                    "sub" -> append("[Sub]")
                    "dub" -> append("[Dub]")
                    else -> server.dataType?.let { append("[$it]") }
                }
                server.serverName?.let { append(" $it") }
                if (server.softsub) append(" [Softsub]")
            }

            extractFromServer(dataLink, label, referer)
        }

        // Group by (server, resolution) so we can interleave audio types
        val grouped = videos.groupBy { video ->
            val server = Regex("""\[(?:Sub|Dub)\]\s*(\S+)""").find(video.quality)?.groupValues?.get(1) ?: ""
            val resolution = Regex("""(\d{3,4})p""").find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            server to resolution
        }

        // Sort groups: preferred server first, then resolution descending
        val sortedGroups = grouped.entries.sortedByDescending { (key, _) ->
            val (server, resolution) = key
            val serverScore = if (server.equals(preferredServer, ignoreCase = true)) 10000 else 0
            serverScore + resolution
        }

        // Within each group, preferred audio first → produces Dub/Sub Dub/Sub pattern
        return sortedGroups.flatMap { (_, groupVideos) ->
            groupVideos.sortedBy { !it.quality.contains(audioTag) }
        }
    }

    private val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun extractFromServer(dataLink: String, label: String, referer: String): List<Video> {
        val decApi = "https://enc-dec.app/api"

        val flixHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", "https://flixcloud.cc")
            .add("Referer", "https://flixcloud.cc/")
            .build()

        val decHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .build()

        return try {
            // Step 1: Fetch embed page
            val html = client.newCall(GET(dataLink, flixHeaders)).execute().use { it.body.string() }
            val dataMatch = Regex(
                """type:\s*"data",\s*data:\s*(\{.*?\})\s*,\s*uses:""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(html) ?: return emptyList()

            val rawJson = json5ToJson(dataMatch.groupValues[1])
            val embedData = try {
                val obj = jsonParser.parseToJsonElement(rawJson).jsonObject.toMutableMap()
                obj.remove("subtitles")
                JsonObject(obj).toString()
            } catch (_: Exception) {
                rawJson
            }

            // Step 2: Get Token
            val tokenPayload = """{"data":$embedData}"""
            val tokenDto = client.newCall(
                Request.Builder()
                    .url("$decApi/dec-flixcloud?type=token")
                    .post(tokenPayload.toRequestBody("application/json".toMediaType()))
                    .headers(decHeaders)
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudTokenResponseDto>() }

            if (tokenDto.status != 200 || tokenDto.result == null) return emptyList()

            // Step 3: Fetch encrypted stream
            val m3u8Body = client.newCall(
                GET("https://flixcloud.cc/api/m3u8/${tokenDto.result.token}", flixHeaders),
            ).execute().use { it.body.string() }

            val m3u8JsonElement = try {
                jsonParser.parseToJsonElement(m3u8Body)
            } catch (_: Exception) {
                return emptyList()
            }

            // Step 4: Decrypt Stream
            val streamPayload = buildJsonObject {
                putJsonObject("data") {
                    put("context", tokenDto.result.context)
                    put("stream_response", m3u8JsonElement.jsonObject)
                }
            }.toString()

            val streamDto = client.newCall(
                Request.Builder()
                    .url("$decApi/dec-flixcloud?type=stream")
                    .post(streamPayload.toRequestBody("application/json".toMediaType()))
                    .headers(decHeaders)
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudStreamResponseDto>() }

            if (streamDto.status != 200 || streamDto.result == null) return emptyList()

            val streamUrl = streamDto.result.stream
            val wPayload = streamDto.result.context["w_payload"]?.jsonPrimitive?.content
                ?: return emptyList()

            // Step 5: Build local proxy URL
            val server = getProxyServer()
            val localManifestUrl = server.createProxyUrl(streamUrl, wPayload)

            // Step 6: Return Video object pointing to local server
            return listOf(Video(localManifestUrl, "$label - 1080p", localManifestUrl, headers))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Converts SvelteKit JSON5-like data to valid JSON.
     * Handles unquoted keys, trailing commas, and undefined values.
     */
    private fun json5ToJson(json5: String): String = json5
        // Quote unquoted keys: identifier followed by colon, preceded by { or ,
        .replace(Regex("""([{,]\s*)([\w_]+)(\s*:)""")) {
            "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}"
        }
        // Remove trailing commas before } or ]
        .replace(Regex(""",\s*([}\]])"""), "$1")
        // Replace undefined with null
        .replace(Regex("""\bundefined\b"""), "null")

    object AutoDetector {
        private const val MPEG_TS_SYNC = 0x47.toByte()
        private const val MAX_HEADER_SEARCH = 1024 * 1024
        private const val MPEG_TS_MIN_SYNCS = 5

        fun detectSkipBytes(data: ByteArray): Int {
            if (data.size < 100) return 0
            val limit = minOf(data.size, MAX_HEADER_SEARCH)

            // MPEG-TS — require N consecutive 188-byte-aligned sync bytes.
            // Try both 188 (standard TS) and 192 (M2TS/BDAV) spacings.
            for (packetSize in intArrayOf(188, 192)) {
                val scanLimit = limit - packetSize * MPEG_TS_MIN_SYNCS
                for (i in 0..scanLimit) {
                    if (data[i] != MPEG_TS_SYNC) continue
                    var ok = true
                    for (k in 1 until MPEG_TS_MIN_SYNCS) {
                        if (data[i + k * packetSize] != MPEG_TS_SYNC) {
                            ok = false
                            break
                        }
                    }
                    if (ok) return i
                }
            }

            // Diagnostic: dump first 64 bytes so we can extend the detector if needed.
            android.util.Log.e(
                "ReAnimeHex",
                "AutoDetector failed. First 64 bytes: ${toHex(data, 0, 64)}",
            )
            return -1
        }

        private fun toHex(data: ByteArray, offset: Int, length: Int): String {
            val actualLen = minOf(length, data.size - offset)
            val sb = StringBuilder(actualLen * 3)
            for (i in offset until offset + actualLen) {
                if (i > offset) {
                    sb.append(' ')
                    if ((i - offset) % 16 == 0) sb.append('\n')
                }
                sb.append(String.format("%02X", data[i].toInt() and 0xFF))
            }
            return sb.toString()
        }
    }

    private var proxyServer: FlixProxyServer? = null

    private fun getProxyServer(): FlixProxyServer {
        if (proxyServer == null || !proxyServer!!.isAlive) {
            proxyServer = FlixProxyServer()
            proxyServer!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        return proxyServer!!
    }

    inner class FlixProxyServer : NanoHTTPD(0) {
        val decApi = "https://enc-dec.app/api"

        // Dedicated client: 30s timeout, larger connection pool. DO NOT force HTTP/1.1 (causes 403s)
        private val proxyClient by lazy {
            client.newBuilder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(20, 5, java.util.concurrent.TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        fun createProxyUrl(originalUrl: String, wPayload: String): String {
            val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload=${URLEncoder.encode(wPayload, "UTF-8")}"
            // Do not append fake extensions. MPV handles the stream better when it relies
            // on the MIME type and the HLS demuxer handles the timestamps correctly.
            return "http://127.0.0.1:$listeningPort/proxy?$params"
        }

        fun wrapInDecApi(originalUrl: String, wPayload: String): String {
            if (originalUrl.contains("enc-dec.app")) return originalUrl
            val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8").replace("+", "%20")
            val encodedWPayload = URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")
            return "$decApi/parse-flixcloud?url=$encodedUrl&w_payload=$encodedWPayload"
        }

        fun ensureToken(segmentUrl: String, parentUrl: String): String = try {
            val segHttpUrl = segmentUrl.toHttpUrl()

            // Extract token from parent URL or its nested 'url' parameter if wrapped in enc-dec.app
            // If segment URL has no token, walk up to 3 levels of nested `url=` params
            // looking for one. The enc-dec.app wrapper carries the token in its top-level
            // query string, so this finds it.
            var token: String? = segHttpUrl.queryParameter("token")
            if (token == null) {
                var currentUrl = parentUrl
                repeat(3) {
                    val httpUrl = currentUrl.toHttpUrl()
                    if (token == null) token = httpUrl.queryParameter("token")
                    if (token == null) {
                        val nestedUrl = httpUrl.queryParameter("url")
                        if (nestedUrl != null) currentUrl = nestedUrl
                    }
                }
            }

            segHttpUrl.newBuilder().apply {
                if (token != null && segHttpUrl.queryParameter("token") == null) {
                    addQueryParameter("token", token)
                }
            }.build().toString()
        } catch (e: Exception) {
            segmentUrl
        }

        /**
         * Detect the CDN's canned decoy response.
         *
         * When vault97 (and possibly other edge nodes) decides a request is "suspicious",
         * it returns a fast 200 OK with a fake WebP or PNG header followed by an
         * identical high-entropy payload — same bytes for every segment, with only a
         * single decrementing counter byte changing between responses.
         *
         * The fingerprint `DA 6A E0` at a fixed offset is distinctive enough that
         * real segments will never match it.
         *
         * Returns true if the response is a decoy; caller should return 503 so
         * ExoPlayer retries and may hit a different edge node.
         */
        private fun isDecoyResponse(data: ByteArray): Boolean {
            // WebP decoy: 52 49 46 46 00 00 00 00 57 45 42 50 DA 6A E0 ??
            if (data.size >= 16 &&
                data[0] == 0x52.toByte() && data[1].toInt() == 0x49 && data[2].toInt() == 0x46 && data[3].toInt() == 0x46 &&
                data[8].toInt() == 0x57 && data[9].toInt() == 0x45 && data[10].toInt() == 0x42 && data[11].toInt() == 0x50 &&
                data[12] == 0xDA.toByte() && data[13].toInt() == 0x6A && data[14].toInt() == 0xE0
            ) {
                return true
            }

            // PNG decoy: 89 50 4E 47 0D 0A 1A 0A DA 6A E0 ??
            if (data.size >= 12 &&
                data[0] == 0x89.toByte() && data[1].toInt() == 0x50 && data[2].toInt() == 0x4E && data[3].toInt() == 0x47 &&
                data[4].toInt() == 0x0D && data[5].toInt() == 0x0A && data[6].toInt() == 0x1A && data[7].toInt() == 0x0A &&
                data[8] == 0xDA.toByte() && data[9].toInt() == 0x6A && data[10].toInt() == 0xE0
            ) {
                return true
            }

            return false
        }

        override fun serve(session: IHTTPSession): NanoHTTPD.Response? {
            android.util.Log.w("ReAnime", "★ BUILD-7-31-1655 serve() entered")
            val params = session.parameters
            val url = params["url"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing url")
            val wPayload = params["w_payload"]?.firstOrNull() ?: ""

            return try {
                val isManifest = url.contains(".m3u8")
                val isMasterManifest = isManifest && (
                    url.contains("master.m3u8") ||
                        (url.contains("flixcloud.cc") && !url.contains("audio.m3u8") && !url.contains("video.m3u8"))
                    )
                val finalUrl = if (isMasterManifest) wrapInDecApi(url, wPayload) else url

                val proxyHeaders = headers.newBuilder()
                    .set("Accept", "*/*")
                    .removeAll("Origin").removeAll("Referer")
                    .removeAll("Sec-Fetch-Dest").removeAll("Sec-Fetch-Mode")
                    .removeAll("Sec-Fetch-Site").removeAll("Accept-Encoding")
                    .apply {
                        if (url.contains("enc-dec.app")) {
                            add("Origin", "https://enc-dec.app")
                            add("Referer", "https://enc-dec.app/")
                        } else {
                            add("Origin", "https://flixcloud.cc")
                            add("Referer", "https://flixcloud.cc/")
                            add("Sec-Fetch-Dest", "empty")
                            add("Sec-Fetch-Mode", "cors")
                            add("Sec-Fetch-Site", "same-site")
                        }
                    }.build()

                if (!isManifest) {
                    // Segment Request
                    val segType = if (url.contains("/audio/")) "AUDIO" else "VIDEO"
                    android.util.Log.d("ReAnime", "====== $segType SEGMENT ======")
                    android.util.Log.d("ReAnime", "Requesting: $finalUrl")

                    val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                    val response = proxyClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        android.util.Log.e("ReAnime", "Fetch failed: ${response.code}")
                        return newFixedLengthResponse(Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "CDN Error")
                    }

                    // 1. Read full segment to safely bypass large image headers (>4KB)
                    val rawData = response.body.bytes()
                    response.close()

                    if (rawData.isEmpty()) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Empty Segment")
                    }

                    // 1b. Detect the CDN's canned decoy response. The CDN serves this to
                    // "suspicious" clients (rate-limited, bad reputation, etc.) — it's a fast
                    // 200 OK with fake image headers and a high-entropy payload that no player
                    // can decode. Return 503 so ExoPlayer retries; the retry may hit a
                    // different edge node or get a real response.
                    if (isDecoyResponse(rawData)) {
                        android.util.Log.w("ReAnimeHex", "CDN served decoy for $finalUrl — returning 503 for retry")
                        return newFixedLengthResponse(
                            Response.Status.SERVICE_UNAVAILABLE,
                            "text/plain",
                            "CDN decoy — retry",
                        )
                    }

                    // 2. Calculate garbage bytes
                    val skipBytes = AutoDetector.detectSkipBytes(rawData)
                    if (skipBytes < 0) {
                        android.util.Log.e("ReAnime", "Could not identify segment format for $finalUrl")
                        return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "Unrecognized segment format",
                        )
                    }
                    val validStreamLength = (rawData.size - skipBytes).toLong()
                    val slicedStream = java.io.ByteArrayInputStream(rawData, skipBytes, rawData.size - skipBytes)

                    android.util.Log.d("ReAnime", "Streamed $validStreamLength bytes to player (Skipped $skipBytes header bytes)")
                    android.util.Log.d("ReAnime", "=============================")

                    // 3. Serve as octet-stream so ExoPlayer sniffs the formats natively
                    return newFixedLengthResponse(Response.Status.OK, "video/mp2t", slicedStream, validStreamLength)
                } else {
                    // Manifest Request
                    android.util.Log.d("ReAnime", "====== MANIFEST REQUEST ======")
                    android.util.Log.d("ReAnime", "Requesting: $finalUrl")

                    val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                    val response = proxyClient.newCall(request).execute()
                    android.util.Log.d("ReAnime", "Response Code: ${response.code}")

                    if (!response.isSuccessful) {
                        android.util.Log.e("ReAnime", "Manifest fetch failed: ${response.code}")
                        val errorBody = response.body.string()
                        response.close()
                        return newFixedLengthResponse(Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "Manifest Error: $errorBody")
                    }

                    val bodyText = response.body.string()
                    response.close()

                    val parentHttpUrl = if (url.contains("enc-dec.app")) {
                        url.toHttpUrl().queryParameter("url")?.toHttpUrl() ?: url.toHttpUrl()
                    } else {
                        url.toHttpUrl()
                    }

                    // Simplified parser: just resolve URLs and pass them to the proxy.
                    // The proxy will dynamically route them to enc-dec.app or direct.
                    val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@joinToString ""

                        if (trimmed.startsWith("#")) {
                            if (trimmed.contains("URI=\"")) {
                                val uri = Regex("URI=\"(.*?)\"").find(trimmed)?.groupValues?.get(1) ?: ""
                                if (uri.isNotEmpty()) {
                                    var resolvedUri = parentHttpUrl.resolve(uri).toString()
                                    resolvedUri = ensureToken(resolvedUri, url)
                                    val newUri = createProxyUrl(resolvedUri, wPayload)
                                    trimmed.replace(Regex("URI=\"(.*?)\""), "URI=\"$newUri\"")
                                } else {
                                    trimmed
                                }
                            } else {
                                trimmed
                            }
                        } else {
                            var resolvedUrl = parentHttpUrl.resolve(trimmed).toString()
                            resolvedUrl = ensureToken(resolvedUrl, url)
                            createProxyUrl(resolvedUrl, wPayload)
                        }
                    }

                    android.util.Log.d("ReAnime", "Manifest parsed and rewritten successfully.")
                    android.util.Log.d("ReAnime", "==============================")

                    // Use application/vnd.apple.mpegurl to match the CDN exactly
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedText)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReAnime", "PROXY CRASHED on $url", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = PREF_TITLE_LANG_ENTRIES,
            entryValues = PREF_TITLE_LANG_VALUES,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = "Preferred Type For Latest",
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_VALUES,
            default = preferredLang,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = preferredServer,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio Type",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = preferredAudio,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private val PREF_LANG_ENTRIES = listOf("All", "Sub", "Dub")
        private val PREF_LANG_VALUES = listOf("", "sub", "dub")
        private const val PREF_LANG_DEFAULT = ""

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Dub")
        private val PREF_AUDIO_VALUES = listOf("sub", "dub")
        private const val PREF_AUDIO_DEFAULT = "sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = listOf("HD-1", "HD-2")
        private val PREF_SERVER_VALUES = listOf("HD-1", "HD-2")
        private const val PREF_SERVER_DEFAULT = "HD-1"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING", "Releasing" -> SAnime.ONGOING
            "FINISHED", "Finished" -> SAnime.COMPLETED
            "CANCELLED", "Cancelled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
