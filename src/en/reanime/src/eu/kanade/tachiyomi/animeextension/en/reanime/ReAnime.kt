package eu.kanade.tachiyomi.animeextension.en.reanime

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    private fun apiHeaders(referer: String = "$baseUrl/home"): Headers = Headers.Builder()
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
    override fun getFilterList(): AnimeFilterList = ReAnimeFilters.FILTER_LIST

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

        // Strict headers matching the curl commands for fetch4.flixcloud.cc and flixcloud.cc
        val flixHeaders = Headers.Builder()
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", "https://flixcloud.cc")
            .add("Sec-GPC", "1")
            .add("Referer", "https://flixcloud.cc/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()

        return try {
            // Step 1: Fetch embed page and extract SvelteKit data
            val html = client.newCall(GET(dataLink, flixHeaders)).execute().use { it.body.string() }

            val dataMatch = Regex(
                """type:\s*"data",\s*data:\s*(\{.*?\})\s*,\s*uses:""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(html) ?: return emptyList()

            // Convert JSON5 → valid JSON, then remove subtitles
            val rawJson = json5ToJson(dataMatch.groupValues[1])
            val embedData = try {
                val obj = jsonParser.parseToJsonElement(rawJson).jsonObject.toMutableMap()
                obj.remove("subtitles")
                JsonObject(obj).toString()
            } catch (_: Exception) {
                rawJson
            }

            // Step 2: POST to dec-flixcloud?type=token
            val tokenPayload = """{"data":$embedData}"""
            val tokenDto = client.newCall(
                Request.Builder()
                    .url("$decApi/dec-flixcloud?type=token")
                    .post(tokenPayload.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudTokenResponseDto>() }

            if (tokenDto.status != 200 || tokenDto.result == null) return emptyList()

            // Step 3: GET flixcloud.cc/api/m3u8/{token} — returns encrypted JSON object
            val m3u8Body = client.newCall(
                GET("https://flixcloud.cc/api/m3u8/${tokenDto.result.token}", flixHeaders),
            ).execute().use { it.body.string() }

            // Parse the encrypted response as a JSON element so it can be properly nested
            val m3u8JsonElement = try {
                jsonParser.parseToJsonElement(m3u8Body)
            } catch (_: Exception) {
                return emptyList()
            }

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
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudStreamResponseDto>() }

            if (streamDto.status != 200 || streamDto.result == null) return emptyList()

            val streamUrl = streamDto.result.stream
            val wPayload = streamDto.result.context["w_payload"]?.jsonPrimitive?.content
                ?: return emptyList()

            // Step 5: Build parse-flixcloud URL (decrypted manifest proxy)
            // Replace + with %20 to strictly match Python's urlencode behavior
            val manifestUrl = "$decApi/parse-flixcloud?" +
                "url=${URLEncoder.encode(streamUrl, "UTF-8").replace("+", "%20")}" +
                "&w_payload=${URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")}"

            // Step 6: Extract videos from decrypted manifest
            PlaylistUtils(client, headers).extractFromHls(
                playlistUrl = manifestUrl,
                referer = "https://flixcloud.cc/",
                masterHeaders = Headers.Builder()
                    .add("Accept", "*/*")
                    .add("Accept-Language", "en-US,en;q=0.9")
                    .add("Sec-Fetch-Dest", "empty")
                    .add("Sec-Fetch-Mode", "cors")
                    .add("Sec-Fetch-Site", "same-origin")
                    .build(),
                videoHeaders = flixHeaders,
                videoNameGen = { quality -> "$label - $quality" },
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
        .replace(Regex("""([{,]\s*)([\w_]+)(\s*:)""")) {
            "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}"
        }
        // Remove trailing commas before } or ]
        .replace(Regex(""",\s*([}\]])"""), "$1")
        // Replace undefined with null
        .replace(Regex("""\bundefined\b"""), "null")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = "Preferred Type For Latest",
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_VALUES,
            default = preferredLang,
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

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = preferredServer,
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
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private val PREF_LANG_ENTRIES = listOf("Sub", "Dub")
        private val PREF_LANG_VALUES = listOf("sub", "dub")
        private const val PREF_LANG_DEFAULT = "sub"

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
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            else -> SAnime.UNKNOWN
        }
    }
}
