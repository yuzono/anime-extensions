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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone.getTimeZone
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

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(5, 1.seconds)
            .build()
    }

    private data class AnimeMeta(val anilistId: Int, val subbed: Int, val dubbed: Int)

    private val animeMetaCache = android.util.LruCache<String, AnimeMeta>(64)
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
            animeMetaCache.put(
                dto.animeId,
                AnimeMeta(
                    anilistId = dto.anilistId,
                    subbed = dto.subbed ?: 0,
                    dubbed = dto.dubbed ?: 0,
                ),
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
        animeMetaCache.put(slug, AnimeMeta(anilistId, subbed, dubbed))

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

    private val jsonParser = Json { ignoreUnknownKeys = true }

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
            skipTimesCache.put(localManifestUrl, skipTimes)

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

    private var proxyServer: FlixProxyServer? = null

    private fun getProxyServer(): FlixProxyServer {
        if (proxyServer == null || !proxyServer!!.isAlive) {
            proxyServer = FlixProxyServer(headers, client)
            proxyServer!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        return proxyServer!!
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
        // Status domain: https://restatus.me/
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

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING", "Releasing" -> SAnime.ONGOING
            "FINISHED", "Finished" -> SAnime.COMPLETED
            "CANCELLED", "Cancelled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
