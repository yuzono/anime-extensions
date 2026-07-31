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
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import fi.iki.elonen.NanoHTTPD
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
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
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone.getTimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class ReAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ReAnime"

    override val lang = "en"

    private val preferences: SharedPreferences by getPreferencesLazy()
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val apiUrl: String
        get() = "$baseUrl/api/v1"

    private val flixUrl = "$baseUrl/api/flix"

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
            .rateLimit(5, 1.seconds)
            .build()
    }

    private data class AnimeMeta(val anilistId: Int, val subbed: Int, val dubbed: Int)
    private val animeMetaCache = ConcurrentHashMap<String, AnimeMeta>()
    private var nextLatestCursor: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = getTimeZone("UTC")
    }

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
        val dto = response.parseAs<SearchResponseDto>()
        val animes = dto.results.mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = (dto.offset + dto.limit) < dto.total

        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) nextLatestCursor = null

        val urlBuilder = "$apiUrl/home/latest-aired".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "12")
            addQueryParameter("lang", preferredLang)
            if (page > 1 && nextLatestCursor != null) {
                addQueryParameter("cursor", nextLatestCursor!!)
            }
        }
        return GET(urlBuilder.build(), apiHeaders("$baseUrl/home"))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = response.parseAs<LatestDto>()
        nextLatestCursor = dto.nextCursor

        val animes = dto.data.mapNotNull { it.toSAnime(titleLanguage) }
        return AnimesPage(animes, dto.hasMore)
    }

    // =============================== Search ===============================
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    @RequiresApi(Build.VERSION_CODES.O)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            val limit = 36
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", ((page - 1) * limit).toString())

            if (query.isNotBlank()) addQueryParameter("q", query)

            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is Filters.FormatFilter -> filter.getValue()?.let { addQueryParameter("format", it) }
                    is Filters.StatusFilter -> filter.getValue()?.let { addQueryParameter("status", it) }
                    is Filters.SeasonFilter -> filter.getValue()?.let { addQueryParameter("season", it) }
                    is Filters.OriginFilter -> filter.getValue()?.let { addQueryParameter("country", it) }
                    is Filters.YearFilter -> filter.getValue()?.let { addQueryParameter("year", it) }
                    is Filters.GenreFilter -> {
                        val genres = filter.getSelectedValues()
                        if (genres.isNotEmpty()) addQueryParameter("genre", genres)
                    }
                    is Filters.CharacterFilter -> {
                        val characters = filter.getSelectedValues()
                        if (characters.isNotEmpty()) addQueryParameter("character", characters)
                    }
                    is Filters.StaffFilter -> {
                        val staff = filter.getSelectedValues()
                        if (staff.isNotEmpty()) addQueryParameter("staff", staff)
                    }
                    is Filters.StudioFilter -> {
                        val studios = filter.getSelectedValues()
                        if (studios.isNotEmpty()) addQueryParameter("studio", studios)
                    }
                    is Filters.TagFilter -> {
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
        val dto = response.parseAs<SearchResponseDto>()
        val animes = dto.results.mapNotNull { it.toSAnime(titleLanguage) }
        val hasNextPage = (dto.offset + dto.limit) < dto.total

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    private val detailsUrl = "$baseUrl/anime"
    override fun getAnimeUrl(anime: SAnime): String = "$detailsUrl/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$detailsFromApiUrl/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<AnimeDetailDto>()

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

    private fun buildDescription(dto: AnimeDetailDto): String = buildString {
        dto.averageScore?.let { score ->
            getFancyScore(score).takeIf { it.isNotEmpty() }?.let {
                if (isNotBlank()) append("\n\n")
                append(it)
            }
        }
        dto.description?.let { raw ->
            val cleaned = raw
                .replace(BR_REGEX, "\n")
                .replace(HTML_TAG_REGEX, "")
                .trim()
            if (cleaned.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(cleaned)
            }
        }

        dto.title?.romaji?.takeIf { it.isNotBlank() && it != dto.title.preferredTitle(titleLanguage) }?.let {
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
            val streamingLinks = links.mapNotNull { link ->
                val site = link.site ?: return@mapNotNull null
                val url = link.url ?: return@mapNotNull null
                "[$site]($url)"
            }
            if (streamingLinks.isNotEmpty()) {
                append("\n\n**Streaming**: ${streamingLinks.joinToString(" · ")}")
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
    override val disableRelatedAnimesBySearch = true

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<AnimeDetailDto>()
        val currentId = dto.animeId

        return buildList {
            dto.relations?.mapNotNull { rel ->
                if (rel.animeId.isBlank()) return@mapNotNull null
                if (rel.animeId == currentId) return@mapNotNull null // ← Skip self
                val relTitle = rel.title?.preferredTitle(titleLanguage) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rel.animeId
                    title = relTitle
                    thumbnail_url = rel.coverImage?.safeExtraLarge ?: rel.coverImage?.safeLarge ?: rel.coverImage?.safeMedium
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
                    thumbnail_url = rec.coverImage?.safeExtraLarge ?: rec.coverImage?.safeLarge ?: rec.coverImage?.safeMedium
                    status = parseStatus(null)
                    genre = null
                }
            }.let(::addAll)
        }
    }

    private val detailsFromApiUrl = "$apiUrl/anime"

    private fun fetchRecommendations(slug: String): List<RecommendationDto> {
        return try {
            val res = client.newCall(
                GET("$detailsFromApiUrl/$slug/recommendations", apiHeaders("$detailsUrl/$slug")),
            ).execute()
            res.use {
                if (!it.isSuccessful) return emptyList()
                val dto = it.parseAs<RecommendationsDto>()
                if (dto.success) dto.recommendations else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = "$detailsFromApiUrl/${anime.url}/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "2000")
            .build()
        return GET(url, apiHeaders("$detailsUrl/${anime.url}"))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (!response.isSuccessful) {
            throw Exception("Failed to load episodes (HTTP ${response.code})")
        }

        val dto = try {
            response.parseAs<EpisodeListDto>()
        } catch (_: Exception) {
            throw Exception("Could not parse episode list. The anime may not have episodes yet.")
        }

        if (dto.data.isEmpty()) {
            throw Exception("No episodes available for this anime yet. It may not have aired.")
        }

        val segments = response.request.url.pathSegments
        val animeIdx = segments.indexOf("anime")
        val animeSlug = if (animeIdx != -1 && animeIdx + 1 < segments.size) segments[animeIdx + 1] else ""

        val meta = animeMetaCache[animeSlug] ?: fetchAnimeMeta(animeSlug)
        val maxSub = meta.subbed
        val maxDub = meta.dubbed

        return dto.data.map { ep ->
            SEpisode.create().apply {
                val epNum = ep.episodeNumber
                episode_number = epNum.toFloat()

                val safeEpisodeId = ep.episodeId ?: "ep-${epNum.toInt()}"
                url = "$animeSlug/$safeEpisodeId"

                val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()

                val baseName = if (ep.title.isNotBlank() && !ep.title.contains("Episode", ignoreCase = true)) {
                    "Episode $epNumStr - ${ep.title}"
                } else {
                    "Episode $epNumStr"
                }

                name = buildString {
                    append(baseName)
                    if (ep.isRecap) append(" [Recap]")
                    if (ep.isFiller) append(" [Filler]")
                }

                val hasSub = epNum <= maxSub
                val hasDub = epNum <= maxDub

                scanlator = when {
                    hasSub && hasDub -> "Sub & Dub"
                    hasSub -> "Sub"
                    hasDub -> "Dub"
                    else -> null
                }

                date_upload = dateFormat.tryParse(ep.aired)
            }
        }.reversed()
    }

    private fun fetchAnimeMeta(slug: String): AnimeMeta {
        return try {
            val res = client.newCall(
                GET("$detailsFromApiUrl/$slug", apiHeaders("$detailsUrl/$slug")),
            ).execute()
            res.use {
                if (!it.isSuccessful) return@use AnimeMeta(0, 0, 0)
                val dto = it.parseAs<AnimeDetailDto>()
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

    // ============================== Video Links ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        val bits = episode.url.split("/")
        val slug = bits.getOrNull(0) ?: ""
        val epId = bits.getOrNull(1) ?: ""
        val epNumber = epId.removePrefix("ep-")

        val meta = animeMetaCache[slug]

        if (meta != null && meta.anilistId > 0) {
            return GET(
                "$flixUrl/${meta.anilistId}/$epNumber",
                apiHeaders("$baseUrl/watch/$slug?ep=$epNumber"),
            )
        }

        // Cache miss: fetch anime page to extract anilist_id
        return GET("$detailsUrl/$slug?_ep=$epNumber", headers)
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

        val anilistId = ANILIST_ID_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()

        val slug = response.request.url.pathSegments.lastOrNull() ?: ""
        val epNumber = response.request.url.queryParameter("_ep") ?: return emptyList()

        val subbed = SUBBED_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val dubbed = DUBBED_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        animeMetaCache[slug] = AnimeMeta(anilistId, subbed, dubbed)

        val referer = "$baseUrl/watch/$slug?ep=$epNumber"

        val flixRes = client.newCall(
            GET("$flixUrl/$anilistId/$epNumber", apiHeaders(referer)),
        ).execute()

        return parseFlixServers(flixRes, referer)
    }

    private suspend fun parseFlixServers(response: Response, referer: String): List<Video> {
        val parsed = response.use {
            if (!it.isSuccessful) return emptyList()
            it.parseAs<VideoResponseDto>()
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
            val server = SERVER_NAME_REGEX.find(video.quality)?.groupValues?.get(1) ?: ""
            val resolution = RESOLUTION_REGEX.find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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
            // Step 1: Fetch embed page; has XOR key in HEX format
            val html = client.newCall(GET(dataLink, flixHeaders)).execute().use { it.body.string() }
            val dataMatch = EMBED_DATA_REGEX.find(html) ?: return emptyList()

            val rawJson = json5ToJson(dataMatch.groupValues[1])

            // Parse the embed data for subtitles + chapters (before stripping them for enc-dec)
            val embedDataDto = try {
                jsonParser.decodeFromString<FlixcloudEmbedDataDto>(rawJson)
            } catch (_: Exception) {
                FlixcloudEmbedDataDto()
            }

            val subtitleTracks = embedDataDto.subtitles
                ?.map { Track(it.url, it.language ?: "Unknown") }
                ?: emptyList()

            val skipTimes = embedDataDto.toSkipTimes()

            // Strip subtitles/chapters from the payload (enc-dec.app doesn't need them)
            val embedData = try {
                val obj = jsonParser.parseToJsonElement(rawJson).jsonObject.toMutableMap()
                obj.remove("subtitles")
                obj.remove("intro_chapter")
                obj.remove("outro_chapter")
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
            // Cache skip times for this episode (keyed by the local proxy URL)
            val localManifestUrl = server.createProxyUrl(streamUrl, wPayload)
            skipTimesCache[localManifestUrl] = skipTimes

            return listOf(
                Video(localManifestUrl, "$label - 1080p", localManifestUrl, headers, subtitleTracks),
            )
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
        .replace(JSON5_KEY_REGEX) {
            "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}"
        }
        // Remove trailing commas before } or ]
        .replace(JSON5_TRAILING_COMMA_REGEX, "$1")
        // Replace undefined with null
        .replace(JSON5_UNDEFINED_REGEX, "null")

    private val flixcloudSegmentMask: ByteArray by lazy {
        FLIXCLOUD_SEGMENT_MASK_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Decode a flixcloud segment.
     *
     * Segments are disguised as WebP/PNG images: a real image magic header
     * (12 bytes for WebP, 8 for PNG) followed by the actual MPEG-TS bytes
     * XOR'd with a 16-byte repeating mask. After XOR, the first byte should
     * be 0x47 (MPEG-TS sync).
     *
     * If the response doesn't match this pattern, returns the original bytes
     * unchanged (rare case — happens if the CDN ever serves raw MPEG-TS).
     */
    private fun decodeFlixcloudSegment(data: ByteArray): ByteArray {
        val headerSize = when {
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> 12 // RIFF....WEBP
            data.size >= 8 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() &&
                data[4] == 0x0D.toByte() && data[5] == 0x0A.toByte() &&
                data[6] == 0x1A.toByte() && data[7] == 0x0A.toByte() -> 8 // PNG sig
            else -> return data // Not a flixcloud-wrapped segment, serve as-is
        }

        // Quick check: if byte after header is already 0x47, segment wasn't encrypted
        if (data[headerSize] == 0x47.toByte()) {
            return data.copyOfRange(headerSize, data.size)
        }

        // XOR-decrypt in-place
        val decoded = data.copyOfRange(headerSize, data.size)
        for (i in decoded.indices) {
            decoded[i] = (decoded[i].toInt() xor flixcloudSegmentMask[i and 15].toInt()).toByte()
        }

        // Verify: first byte should now be 0x47 (MPEG-TS sync)
        if (decoded.isEmpty() || decoded[0] != 0x47.toByte()) {
            // Restore: return original data minus the image header (best-effort)
            return data.copyOfRange(headerSize, data.size)
        }

        return decoded
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
                .readTimeout(10.seconds)
                .connectTimeout(5.seconds)
                .connectionPool(okhttp3.ConnectionPool(30, 2, TimeUnit.MINUTES)) // ← more slots, shorter keepalive
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
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
        } catch (_: Exception) {
            segmentUrl
        }

        override fun serve(session: IHTTPSession): NanoHTTPD.Response? {
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
                    val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                    val response = proxyClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        return newFixedLengthResponse(Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "CDN Error")
                    }

                    // 1. Read full segment to safely bypass large image headers (>4KB)
                    val rawData = response.body.bytes()
                    response.close()

                    if (rawData.isEmpty()) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Empty Segment")
                    }

                    // Decode flixcloud segment: strip fake image header + XOR-decrypt with 16-byte mask.
                    val decoded = decodeFlixcloudSegment(rawData)

                    // MPEG-TS — explicit MIME type helps ExoPlayer skip sniffing
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "video/mp2t",
                        ByteArrayInputStream(decoded),
                        decoded.size.toLong(),
                    )
                } else {
                    // Manifest Request
                    val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
                    val response = proxyClient.newCall(request).execute()

                    if (!response.isSuccessful) {
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
                    // The proxy will dynamically route them to enc-dec.app.
                    val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@joinToString ""

                        if (trimmed.startsWith("#")) {
                            if (trimmed.contains("URI=\"")) {
                                val uri = URI_REGEX.find(trimmed)?.groupValues?.get(1) ?: ""
                                if (uri.isNotEmpty()) {
                                    var resolvedUri = parentHttpUrl.resolve(uri).toString()
                                    resolvedUri = ensureToken(resolvedUri, url)
                                    val newUri = createProxyUrl(resolvedUri, wPayload)
                                    trimmed.replace(URI_REGEX, "URI=\"$newUri\"")
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

                    // Use application/vnd.apple.mpegurl to match the CDN exactly
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedText)
                }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )

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
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("reanime.to", "reanime.cz")
        private val PREF_DOMAIN_VALUES = listOf("https://reanime.to", "https://reanime.cz")
        private const val PREF_DOMAIN_DEFAULT = "https://reanime.to"
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

        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)
        private val ANILIST_ID_REGEX = Regex("""anilist_id:(\d+)""")
        private val SUBBED_REGEX = Regex(""",subbed:(\d+)""")
        private val DUBBED_REGEX = Regex(""",dubbed:(\d+)""")
        private val SERVER_NAME_REGEX = Regex("""\[(?:Sub|Dub)]\s*(\S+)""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")
        private val EMBED_DATA_REGEX = Regex("""type:\s*"data",\s*data:\s*(\{.*?\})\s*,\s*uses:""", RegexOption.DOT_MATCHES_ALL)
        private val JSON5_KEY_REGEX = Regex("""([{,]\s*)([\w_]+)(\s*:)""")
        private val JSON5_TRAILING_COMMA_REGEX = Regex(""",\s*([}\]])""")
        private val JSON5_UNDEFINED_REGEX = Regex("""\bundefined\b""")
        private val URI_REGEX = Regex("URI=\"(.*?)\"")

        /**
         * Flixcloud segment XOR mask (16 bytes, repeating).
         *
         * If segments stop decoding (logcat shows "first byte = 0x?? (expected 0x47)"):
         *   1. Open ReAnime video in a browser
         *   2. Search in debugger for: {for(var f=[
         *   3. Copy the 16 numbers from the array literal
         *   4. Convert to hex (Python: bytes([157,42,241,...]).hex())
         *   5. Update FLIXCLOUD_SEGMENT_MASK_HEX below
         *
         * Last verified: 2026-07-31
         */
        private const val FLIXCLOUD_SEGMENT_MASK_HEX = "9D2AF147B38E5C70A619E43BD8620FC5"

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING", "Releasing" -> SAnime.ONGOING
            "FINISHED", "Finished" -> SAnime.COMPLETED
            "CANCELLED", "Cancelled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
