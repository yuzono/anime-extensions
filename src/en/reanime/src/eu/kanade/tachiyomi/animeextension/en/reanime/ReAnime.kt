package eu.kanade.tachiyomi.animeextension.en.reanime

import android.content.SharedPreferences
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.decApi
import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.flixCloudUrl
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.nanohttpd.protocols.http.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone.getTimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class ReAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Re:ANIME"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val apiUrl: String
        get() = "$baseUrl/api/v1"

    private val flixUrl = "$baseUrl/api/flix"

    private val preferredLang: String
        get() = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val excludedServers: Set<String>
        get() = preferences.getStringSet(PREF_SERVER_EXCLUDE_KEY, PREF_SERVER_EXCLUDE_DEFAULT)
            ?: PREF_SERVER_EXCLUDE_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, PREF_AUDIO_EXCLUDE_DEFAULT)
            ?: PREF_AUDIO_EXCLUDE_DEFAULT

    private val hideFiller: Boolean
        get() = preferences.getBoolean(PREF_HIDE_FILLER_KEY, PREF_HIDE_FILLER_DEFAULT)

    private val includeDirectDownloads: Boolean
        get() = preferences.getBoolean(PREF_DOWNLOAD_KEY, PREF_DOWNLOAD_DEFAULT)

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

    private val playlistUtils by lazy { PlaylistUtils(network.client, headers) }

    private data class AnimeMeta(val anilistId: Int, val subbed: Int, val dubbed: Int)

    private val animeMetaCache by lazy { LruCache<String, AnimeMeta>(64) }

    @Synchronized
    private fun fetchAnimeMeta(animeSlug: String): AnimeMeta? {
        // Double-check: Another thread might have fetched it while we waited for the lock
        animeMetaCache.get(animeSlug)?.let { return it }

        return try {
            client.newCall(
                GET("$detailsFromApiUrl/$animeSlug", apiHeaders("$detailsUrl/$animeSlug")),
            ).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val dto = res.parseAs<AnimeDetailDto>()
                AnimeMeta(
                    anilistId = dto.anilistId ?: 0,
                    subbed = dto.subbed ?: 0,
                    dubbed = dto.dubbed ?: 0,
                ).also { animeMetaCache.put(animeSlug, it) }
            }
        } catch (_: Exception) {
            null
        }
    }

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

    private fun getOrdinal(n: Int): String = if (n in 11..13) {
        "${n}th"
    } else {
        when (n % 10) {
            1 -> "${n}st"
            2 -> "${n}nd"
            3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    private fun formatFuzzyDate(date: FuzzyDateDto?): String? {
        if (date == null || date.year == null || date.year <= 0) return null
        if (date.month == null || date.month !in 1..12) return null

        val monthStr = MONTHS[date.month - 1]
        return if (date.day != null && date.day > 0) {
            "$monthStr ${getOrdinal(date.day)}, ${date.year}"
        } else {
            "$monthStr ${date.year}"
        }
    }

    private fun parseAiringDate(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val parts = iso.substringBefore('T').split('-')
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()
            formatFuzzyDate(FuzzyDateDto(year, month, day))
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDescription(dto: AnimeDetailDto): String = buildString {
        val infoLines = mutableListOf<String>()

        dto.averageScore?.let { score ->
            getFancyScore(score).takeIf { it.isNotEmpty() }?.let {
                append(it)
                append("\n\n")
            }
        }

        dto.description?.let { raw ->
            val cleaned = raw
                .replace(BR_REGEX, "\n")
                .replace(HTML_TAG_REGEX, "")
                .trim()
            if (cleaned.isNotBlank()) {
                append(cleaned)
                append("\n\n")
            }
        }

        val altTitles = mutableListOf<String>()

        dto.title?.let { title ->
            val preferred = title.preferredTitle(titleLanguage)
            listOfNotNull(title.english, title.romaji, title.native)
                .filter { it.isNotBlank() && it != preferred }
                .forEach { altTitles.add(it) }
        }

        dto.synonyms?.filter { it.isNotBlank() }?.let { altTitles.addAll(it) }

        val uniqueAltTitles = altTitles.distinctBy { it }

        if (uniqueAltTitles.isNotEmpty()) {
            infoLines.add("**Alternative Titles**: ${uniqueAltTitles.joinToString(" • ")}")
        }

        dto.format?.takeIf { it.isNotBlank() }?.let {
            infoLines.add("**Format**: $it")
        }

        val sourceStr = dto.source?.takeIf { it.isNotBlank() }?.let { it.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } }
        val countryStr = dto.countryOfOrigin?.takeIf { it.isNotBlank() }
        if (sourceStr != null || countryStr != null) {
            infoLines.add("**Source**: " + listOfNotNull(sourceStr, countryStr).joinToString(" • "))
        }

        val startDateStr = formatFuzzyDate(dto.startDate)
        val endDateStr = formatFuzzyDate(dto.endDate)

        when {
            startDateStr != null && endDateStr != null -> {
                if (startDateStr == endDateStr) {
                    infoLines.add("**Air Date**: On $startDateStr")
                } else {
                    infoLines.add("**Airing**: From $startDateStr to $endDateStr")
                }
            }
            startDateStr != null -> infoLines.add("**Start Date**: $startDateStr")
            endDateStr != null -> infoLines.add("**End Date**: $endDateStr")
        }

        dto.nextAiringEpisode?.let { next ->
            val epNum = next.episode
            val airingAt = next.airingAt?.takeIf { it.isNotBlank() }
            if (epNum != null && airingAt != null) {
                parseAiringDate(airingAt)?.let { formattedDate ->
                    infoLines.add("**Next Airing**: Episode $epNum on $formattedDate")
                }
            }
        }

        val seasonStr = dto.season?.takeIf { it.isNotBlank() && it != "0" }?.replaceFirstChar { c -> c.titlecase() }
        val seasonYearStr = dto.seasonYear?.takeIf { it > 0 }?.toString()

        when {
            seasonStr != null && seasonYearStr != null -> infoLines.add("**Season**: $seasonStr $seasonYearStr")
        }

        dto.duration?.takeIf { it > 0 }?.let {
            infoLines.add("**Duration**: ${it}m")
        }

        dto.rating?.takeIf { it.isNotBlank() }?.let {
            infoLines.add("**Rating**: $it")
        }

        // Append all info lines cleanly
        if (infoLines.isNotEmpty()) {
            append(infoLines.joinToString("\n"))
            append("\n")
        }

        val trackers = buildList {
            dto.anilistId?.takeIf { it > 0 }?.let { add("[AniList](https://anilist.co/anime/$it)") }
            dto.malId?.takeIf { it > 0 }?.let { add("[MAL](https://myanimelist.net/anime/$it)") }
            dto.kitsuId?.takeIf { it > 0 }?.let { add("[Kitsu](https://kitsu.io/anime/$it)") }
            dto.anidbId?.takeIf { it > 0 }?.let { add("[AniDB](https://anidb.net/anime/$it)") }
            dto.animePlanetId?.takeIf { it.isNotBlank() }?.let { add("[Anime-Planet](https://www.anime-planet.com/anime/$it)") }
            dto.animeNewsNetworkId?.takeIf { it > 0 }?.let { add("[ANN](https://www.animenewsnetwork.com/encyclopedia/anime.php?id=$it)") }
            dto.anisearchId?.takeIf { it > 0 }?.let { add("[Anisearch](https://www.anisearch.com/anime/$it)") }
            dto.simklId?.takeIf { it > 0 }?.let { add("[Simkl](https://simkl.com/anime/$it)") }
            dto.tmdbId?.takeIf { it > 0 }?.let { add("[TMDB](https://www.themoviedb.org/tv/$it)") }
            dto.tvdbId?.takeIf { it > 0 }?.let { add("[TVDB](https://thetvdb.com/series/$it)") }
            dto.imdbId?.takeIf { it.isNotBlank() }?.let { add("[IMDB](https://www.imdb.com/title/$it)") }
        }
        if (trackers.isNotEmpty()) {
            append("**Trackers**: ${trackers.joinToString(" • ")}")
            append("\n")
        }

        dto.externalLinks?.filter { it.type == "STREAMING" }?.takeIf { it.isNotEmpty() }?.let { links ->
            val streamingLinks = links.mapNotNull { link ->
                val site = link.site ?: return@mapNotNull null
                val url = link.url ?: return@mapNotNull null
                "[$site]($url)"
            }
            if (streamingLinks.isNotEmpty()) {
                append("**Streaming**: ${streamingLinks.joinToString(" • ")}")
                append("\n")
            }
        }

        dto.trailer?.takeIf { it.site == "youtube" && !it.id.isNullOrBlank() }?.let {
            append("**Trailer**: [YouTube](https://www.youtube.com/watch?v=${it.id})")
            append("\n")
        }

        dto.bannerImage?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append("![Banner]($it)")
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

        val visibleEpisodes = dto.data.filterNot { it.isFiller && hideFiller }

        if (visibleEpisodes.isEmpty()) {
            throw Exception("No episodes available for this anime yet. It may not have aired.")
        }

        val segments = response.request.url.pathSegments
        val animeIdx = segments.indexOf("anime")
        val animeSlug = if (animeIdx != -1 && animeIdx + 1 < segments.size) segments[animeIdx + 1] else ""

        val meta = animeMetaCache.get(animeSlug) ?: fetchAnimeMeta(animeSlug)
        val maxSub = meta?.subbed ?: 0
        val maxDub = meta?.dubbed ?: 0

        return visibleEpisodes.map { ep ->
            SEpisode.create().apply {
                val epNum = ep.episodeNumber
                episode_number = epNum.toFloat()

                val safeEpisodeId = ep.episodeId ?: "ep-${epNum.toInt()}"
                url = "$animeSlug/$safeEpisodeId"

                val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()

                val epTitle = ep.getPreferredTitle(titleLanguage)

                val baseName = if (epTitle.isNotBlank() && !epTitle.contains("Episode", ignoreCase = true)) {
                    "Episode $epNumStr - $epTitle"
                } else {
                    "Episode $epNumStr"
                }

                name = buildString {
                    append(baseName)
                    if (ep.isRecap) append(" [Recap]")
                    if (ep.isFiller && !hideFiller) append(" [Filler]")
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

    // ============================== Video Links ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        val bits = episode.url.split("/")
        val slug = bits.getOrNull(0) ?: ""
        val epId = bits.getOrNull(1) ?: ""
        val epNumber = epId.removePrefix("ep-")

        val meta = animeMetaCache.get(slug) ?: fetchAnimeMeta(slug)

        if (meta != null && meta.anilistId > 0) {
            return GET(
                "$flixUrl/${meta.anilistId}/$epNumber",
                apiHeaders("$baseUrl/watch/$slug?ep=$epNumber"),
            )
        }

        // Fallback to HTML page if API completely failed to get Anilist ID
        return GET("$detailsUrl/$slug?_ep=$epNumber", headers)
    }

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val requestUrl = response.request.url.toString()

        if (!requestUrl.contains("/api/flix/")) {
            return@runBlocking response.use { handleAnimePageResponse(it) }
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

        // Skip excluded servers before doing any network work for them.
        // HLS streams and downloads are excluded independently: blocking "HD-1"
        // hides only its HLS videos, while "[Sub] HD-1 Download" stays available
        // unless "HD-1 Download" is also selected.
        val excluded = excludedServers
        val excludedAudio = excludedAudioTypes
        fun isExcluded(name: String?, suffix: String = ""): Boolean {
            if (excluded.isEmpty()) return false
            val n = name?.takeIf { it.isNotBlank() } ?: return false
            return (n + suffix) in excluded
        }
        fun isAudioExcluded(dataType: String?): Boolean = excludedAudio.isNotEmpty() && audioTagOf(dataType) in excludedAudio

        val videos = parsed.servers.parallelCatchingFlatMap { server ->
            if (isExcluded(server.serverName)) return@parallelCatchingFlatMap emptyList()
            if (isAudioExcluded(server.dataType)) return@parallelCatchingFlatMap emptyList()
            val dataLink = server.dataLink ?: return@parallelCatchingFlatMap emptyList()
            val label = buildString {
                append(audioTagOf(server.dataType))
                server.serverName?.let { append(" $it") }
                if (server.softsub) append(" [Softsub]")
            }.trim()

            extractFromServer(dataLink, label, referer)
        }.toMutableList()

        if (includeDirectDownloads) {
            parsed.servers
                .filterNot { isExcluded(it.serverName, " Download") }
                .filterNot { isAudioExcluded(it.dataType) }
                .mapNotNull { server ->
                    val aid = server.dataLink?.let { EMBED_AID_REGEX.find(it)?.groupValues?.get(1) }
                        ?: return@mapNotNull null
                    Triple(
                        aid,
                        server.serverName?.takeIf { it.isNotBlank() }
                            ?: "Direct",
                        audioTagOf(server.dataType),
                    )
                }
                .distinct()
                .parallelCatchingFlatMap { (aid, serverName, audioTag) ->
                    extractDirectDownload(aid, serverName, audioTag)?.let(::listOf) ?: emptyList()
                }
                .let(videos::addAll)
        }

        if (excluded.isNotEmpty() || excludedAudio.isNotEmpty()) {
            videos.removeAll { video ->
                video.serverKey() in excluded || excludedAudio.any { video.quality.startsWith(it) }
            }
        }

        val quality = preferredQuality
        val qualitiesList = PREF_QUALITY_VALUES.reversed()
        val audioTag = if (preferredAudio == "dub") "[Dub]" else "[Sub]"
        return videos.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(preferredServer, ignoreCase = true) }
                .thenByDescending { it.quality.contains(audioTag) },
        )
    }

    private fun audioTagOf(dataType: String?): String = when (dataType) {
        "sub" -> "[Sub]"
        "dub" -> "[Dub]"
        else -> dataType?.takeIf { it.isNotBlank() }?.let { "[$it]" } ?: ""
    }

    /**
     * Identifies which server a video belongs to, e.g. "HD-1" or "HD-1 Download",
     * so that exclusion matching doesn't hide "HD-1 Download" when only "HD-1"
     * is excluded (a plain contains() would).
     */
    private fun Video.serverKey(): String? {
        val base = SERVER_NAME_REGEX.find(quality)?.groupValues?.get(1) ?: return null
        return if (quality.contains("Download", ignoreCase = true)) "$base Download" else base
    }

    /**
     * Fetches the direct-download variant of a FlixCloud embed as an extra video.
     *
     * Flow: /d/{aid}/__data.json mints a short-lived IP-bound JWT ->
     * the progress endpoint confirms the file is ready -> the file itself is
     * a plain unencrypted Matroska stream (no XOR mask or fake image headers,
     * unlike HLS segments), so they don't require any proxy.
     */
    private fun extractDirectDownload(aid: String, serverName: String, audioTag: String): Video? {
        return try {
            val dlHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Referer", "$flixCloudUrl/")
                .build()

            val dataBody = client.newCall(
                GET("$flixCloudUrl/d/$aid/__data.json", dlHeaders),
            ).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body.string()
            }

            val fileId = FILE_ID_REGEX.find(dataBody)?.value ?: return null
            val token = JWT_REGEX.find(dataBody)?.value ?: return null
            val base = FETCH_BASE_REGEX.find(dataBody)?.value ?: flixCloudUrl
            val resolution = RESOLUTION_REGEX.find(dataBody)?.groupValues?.get(1)

            var ready = false
            var attempts = 0
            while (!ready && attempts < 2) {
                ready = pollDownloadReady(base, fileId, token, dlHeaders)
                attempts++
            }
            if (!ready) return null

            val fileUrl = "$base/download/$fileId?token=$token"
            val label = buildString {
                append(audioTag)
                append(" ").append(serverName).append(" Download")
                append(" - MKV")
                resolution?.let { append(" ").append(it) }
            }.trim()

            Video(fileUrl, label, fileUrl, headers = dlHeaders)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dedicated client for the SSE progress check with hard timeouts, so a
     * stalled or endlessly-streaming progress endpoint can never block the
     * video list for more than ~[PROGRESS_TIMEOUT_SECONDS] per attempt.
     */
    private val progressClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(PROGRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout((PROGRESS_TIMEOUT_SECONDS + 2L), TimeUnit.SECONDS)
            .build()
    }

    private fun pollDownloadReady(base: String, fileId: String, token: String, dlHeaders: Headers): Boolean = flixcloudProgressIsReady(
        client = progressClient,
        progressUrl = "$base/download/$fileId/progress?token=$token",
        headers = dlHeaders,
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun extractFromServer(dataLink: String, label: String, referer: String): List<Video> {
        val flixHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", flixCloudUrl)
            .add("Referer", "$flixCloudUrl/")
            .build()

        val decHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .build()

        return try {
            // Step 1: Fetch embed page; has XOR key in HEX format
            val html = client.newCall(GET(dataLink, flixHeaders)).execute().use { it.body.string() }

            // --- XOR Mask Extraction ---
            val hardcodedFallback = listOf(
                157, 42, 241, 71, 179, 142, 92, 112,
                166, 25, 228, 59, 216, 98, 15, 197,
            ).map { it.toByte() }.toByteArray()

            var xorMask: ByteArray? = null

            val scriptPath = HLS_SCRIPT_REGEX.find(html)?.groupValues?.get(1)
            if (scriptPath != null) {
                val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else "$flixCloudUrl$scriptPath"
                try {
                    val jsContent = client.newCall(GET(scriptUrl, flixHeaders)).execute().use { it.body.string() }
                    xorMask = XOR_MASK_REGEX.find(jsContent)?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().toInt().toByte() }
                        ?.toByteArray()
                } catch (_: Exception) {
                    // Ignore fetch/parse errors
                }
            }

            if (xorMask != null) {
                // Save the newly fetched mask to preferences
                saveXorMask(xorMask)
            } else {
                xorMask = loadSavedXorMask() ?: hardcodedFallback
            }

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
                    .post(tokenPayload.toJsonBody())
                    .headers(decHeaders)
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudTokenResponseDto>() }

            if (tokenDto.status != 200 || tokenDto.result == null) return emptyList()

            // Step 3: Fetch encrypted stream
            val m3u8Body = client.newCall(
                GET("$flixCloudUrl/api/m3u8/${tokenDto.result.token}", flixHeaders),
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
                    .post(streamPayload.toJsonBody())
                    .headers(decHeaders)
                    .build(),
            ).execute().use { it.parseAs<DecFlixCloudStreamResponseDto>() }

            if (streamDto.status != 200 || streamDto.result == null) return emptyList()

            val streamUrl = streamDto.result.stream
            val wPayload = streamDto.result.context["w_payload"]?.jsonPrimitive?.content
                ?: return emptyList()

            // Step 5: Build local proxy URL
            val server = getProxyServer(headers, xorMask)
            val localManifestUrl = server.createProxyUrl(streamUrl, wPayload)

            // Cache skip times for this episode (keyed by the local proxy URL)
            skipTimesCache.put(localManifestUrl, skipTimes)

            // Subtitles need to be routed through proxy server so that servers like HD-2 load its subtitles
            val proxiedSubtitles = subtitleTracks.map { Track(server.createSubtitleProxyUrl(it.url), it.lang) }

            // Step 6: Pass to PlaylistUtils
            return playlistUtils.extractFromHls(
                playlistUrl = localManifestUrl,
                referer = flixCloudUrl,
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality ->
                    "$label - $quality"
                },
                subtitleList = proxiedSubtitles,
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
        .replace(JSON5_UNDEFINED_REGEX, ": null")

    private fun EpisodeDto.getPreferredTitle(language: String): String {
        val preferred = when (language) {
            "native" -> titleJapanese
            "romaji" -> titleRomanji
            else -> title
        }?.takeIf { it.isNotBlank() }

        return preferred
            ?: title.takeIf { it.isNotBlank() }
            ?: titleRomanji?.takeIf { it.isNotBlank() }
            ?: titleJapanese?.takeIf { it.isNotBlank() }
            ?: ""
    }

    @Volatile
    private var proxyServer: FlixProxyServer? = null

    @Synchronized
    private fun getProxyServer(headers: Headers, segmentMask: ByteArray): FlixProxyServer {
        if (proxyServer == null || !proxyServer!!.isAlive) {
            proxyServer?.stop()
            proxyServer = FlixProxyServer(headers, segmentMask)
            proxyServer!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } else {
            proxyServer!!.updateSegmentMask(segmentMask)
        }
        return proxyServer!!
    }

    private fun loadSavedXorMask(): ByteArray? {
        val savedStr = preferences.getString("flixcloud_xor_mask", null) ?: return null
        return try {
            savedStr.split(",").map { it.trim().toInt().toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveXorMask(mask: ByteArray) {
        // Save as a comma-separated string of unsigned integers
        val maskStr = mask.joinToString(",") { (it.toInt() and 0xFF).toString() }
        preferences.edit().putString("flixcloud_xor_mask", maskStr).apply()
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
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = preferredQuality,
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_EXCLUDE_KEY
            title = "Exclude Servers"
            entries = SERVER_EXCLUDE_ENTRIES.toTypedArray()
            entryValues = SERVER_EXCLUDE_ENTRIES.toTypedArray()
            setDefaultValue(PREF_SERVER_EXCLUDE_DEFAULT)
            summary = "Hide videos from the selected servers."
        }.also(screen::addPreference)

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio Type",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = preferredAudio,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_AUDIO_EXCLUDE_KEY
            title = "Exclude Audio Types"
            entries = AUDIO_EXCLUDE_ENTRIES.toTypedArray()
            entryValues = AUDIO_EXCLUDE_VALUES.toTypedArray()
            setDefaultValue(PREF_AUDIO_EXCLUDE_DEFAULT)
            summary = "Hide videos of the selected audio types."
        }.also(screen::addPreference)

        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_HIDE_FILLER_KEY
                title = "Hide Filler Episodes"
                summary = "Hides episodes marked as filler from the episode list."
                setDefaultValue(PREF_HIDE_FILLER_DEFAULT)
            },
        )

        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_DOWNLOAD_KEY
                title = "Include Direct Downloads"
                summary = "Adds the original MKV file of each server as an extra video entry."
                setDefaultValue(PREF_DOWNLOAD_DEFAULT)
            },
        )
    }

    // Status domain: https://restatus.me/
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
        private val PREF_SERVER_ENTRIES = listOf("HD-1", "HD-1 Download", "HD-2", "HD-2 Download")
        private val PREF_SERVER_VALUES = listOf("HD-1", "HD-1 Download", "HD-2", "HD-2 Download")
        private const val PREF_SERVER_DEFAULT = "HD-1"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")

        private const val PREF_HIDE_FILLER_KEY = "hide_filler"
        private const val PREF_HIDE_FILLER_DEFAULT = false

        private const val PREF_DOWNLOAD_KEY = "include_direct_downloads"
        private const val PREF_DOWNLOAD_DEFAULT = true

        private const val PREF_SERVER_EXCLUDE_KEY = "excluded_servers"
        private val SERVER_EXCLUDE_ENTRIES = listOf("HD-1", "HD-1 Download", "HD-2", "HD-2 Download")
        private val PREF_SERVER_EXCLUDE_DEFAULT = emptySet<String>()
        private val SERVER_NAME_REGEX = Regex("""(HD-\d+)""")

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"
        private val AUDIO_EXCLUDE_ENTRIES = listOf("Sub", "Dub")
        private val AUDIO_EXCLUDE_VALUES = listOf("[Sub]", "[Dub]")
        private val PREF_AUDIO_EXCLUDE_DEFAULT = emptySet<String>()

        private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)
        private val ANILIST_ID_REGEX = Regex("""anilist_id:\s*(\d+)""")
        private val SUBBED_REGEX = Regex(""",\s*subbed:\s*(\d+)""")
        private val DUBBED_REGEX = Regex(""",\s*dubbed:\s*(\d+)""")
        private val EMBED_DATA_REGEX = Regex("""type:\s*"data",\s*data:\s*(\{.*?\})\s*,\s*uses:""", RegexOption.DOT_MATCHES_ALL)
        private val JSON5_KEY_REGEX = Regex("""([{,]\s*)([\w_]+)(\s*:)""")
        private val JSON5_TRAILING_COMMA_REGEX = Regex(""",\s*([}\]])""")
        private val JSON5_UNDEFINED_REGEX = Regex(""":\s*undefined\b""")

        /**
         * FlixCloud segment XOR mask (16 bytes, repeating).
         *
         * This mask is fetched DYNAMICALLY from hls.js on every episode load.
         * If the dynamic fetch fails, the extension falls back to the array below.
         *
         * How to manually update the fallback:
         *   1. Open a Re:ANIME video in a browser.
         *   2. In DevTools, search the loaded scripts for: `for(var f=[`
         *   3. Copy the 16 decimal numbers and paste them into `hardcodedFallback`
         *   in [extractFromServer].
         *
         * Last verified fallback: 2026-08-01
         */

        private val HLS_SCRIPT_REGEX = Regex("""href="([^"]*hls\.js[^"]*)""")
        private val XOR_MASK_REGEX = Regex("""for\(var f=\[(\d{1,3}(?:,\d{1,3}){15})]""")

        private val EMBED_AID_REGEX = Regex("""/e/([a-z0-9]+)""")
        private val FILE_ID_REGEX = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        private val JWT_REGEX = Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""")
        private val FETCH_BASE_REGEX = Regex("""https://fetch\d*\.flixcloud\.cc""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4}p)""")
        private const val PROGRESS_TIMEOUT_SECONDS = 5L

        fun parseStatus(status: String?): Int = when (status) {
            "RELEASING", "Releasing" -> SAnime.ONGOING
            "FINISHED", "Finished" -> SAnime.COMPLETED
            "CANCELLED", "Cancelled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
