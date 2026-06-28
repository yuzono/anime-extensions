package aniyomi.lib.anilib

import android.content.SharedPreferences
import android.util.Log
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * AniLib — AniList GraphQL client library for anime metadata interaction.
 *
 * Provides methods to query the AniList GraphQL API for:
 * - Resolving AniList ID to MAL ID
 * - Fetching airing schedule (episode number → air date)
 * - Fetching media details (title, cover, status, genres, studios, and more)
 * - Searching anime, fetching trending/seasonal lists
 * - Fetching related media
 * - Fetching episode titles from ani.zip
 *
 * Includes rate-limit handling (respects 429 Retry-After, reads
 * X-RateLimit-Remaining headers) and SharedPreferences caching
 * for MediaSnapshot data.
 *
 * All methods are synchronous (blocking) and accept an [OkHttpClient] to allow
 * callers to configure rate limiting, headers, etc.
 */
object AniLib {

    private const val TAG = "AniLib"
    private const val GRAPHQL_URL = "https://graphql.anilist.co"
    private const val ANIZIP_URL = "https://api.ani.zip/mappings"

    // Rate-limit constants
    private const val RATE_LIMIT_NORMAL = 90 // requests per minute (AniList default)
    private const val RATE_LIMIT_DEGRADED = 30 // after hitting a 429
    private const val RATE_LIMIT_HEADER = "X-RateLimit-Remaining"
    private const val RATE_LIMIT_RETRY_AFTER_HEADER = "Retry-After"
    private const val CACHE_KEY_PREFIX = "anilib_media_"
    private const val CACHE_BACKOFF_KEY = "anilib_rate_limit_until"

    private val graphQLHeaders = Headers.Builder().build()

    /**
     * Default [OkHttpClient] with a 30-second connect/read timeout.
     * Used when no client is provided by the caller.
     */
    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ========================== Rate-Limit State ==========================

    /**
     * Timestamp (ms) until which requests should be throttled.
     * Set when we receive a 429 with Retry-After, or when X-RateLimit-Remaining hits 0.
     */
    @Volatile
    private var rateLimitUntil: Long = 0L

    /**
     * Whether we've been rate-limited at least once and should
     * use the degraded (30 req/min) rate instead of the normal (90 req/min).
     */
    @Volatile
    private var rateLimitDegraded: Boolean = false

    /**
     * Minimum interval (ms) between consecutive requests.
     * Calculated from the current rate limit tier.
     */
    private val minRequestInterval: Long
        get() = if (rateLimitDegraded) {
            60_000L / RATE_LIMIT_DEGRADED
        } else {
            60_000L / RATE_LIMIT_NORMAL
        }

    @Volatile
    private var lastRequestTime: Long = 0L

    /**
     * Blocks the calling thread until enough time has passed since the
     * last request, respecting the current rate-limit tier and any
     * active Retry-After backoff.
     */
    private fun throttle() {
        // If we're in a Retry-After backoff, wait it out
        val backoffEnd = rateLimitUntil
        if (backoffEnd > 0L) {
            val waitMs = backoffEnd - System.currentTimeMillis()
            if (waitMs > 0) {
                Log.d(TAG, "Rate-limit backoff: waiting ${waitMs}ms")
                Thread.sleep(waitMs)
            }
            rateLimitUntil = 0L
        }

        // Enforce minimum interval between requests
        val elapsed = System.currentTimeMillis() - lastRequestTime
        val waitMs = minRequestInterval - elapsed
        if (waitMs > 0) {
            Thread.sleep(waitMs)
        }
        lastRequestTime = System.currentTimeMillis()
    }

    /**
     * Processes rate-limit headers from the response and updates state.
     * Should be called even on successful responses to track remaining quota.
     */
    private fun handleRateLimitHeaders(response: okhttp3.Response) {
        // Check X-RateLimit-Remaining
        val remaining = response.header(RATE_LIMIT_HEADER)?.toIntOrNull()
        if (remaining != null && remaining <= 1) {
            // We're about to exhaust our quota — set a brief backoff
            rateLimitUntil = System.currentTimeMillis() + 60_000L
            Log.w(TAG, "Rate limit nearly exhausted (remaining=$remaining), backing off 60s")
        }
    }

    /**
     * Handles a 429 response by reading Retry-After and setting backoff.
     * Also degrades the rate limit tier for future requests.
     */
    private fun handle429(response: okhttp3.Response) {
        rateLimitDegraded = true
        val retryAfter = response.header(RATE_LIMIT_RETRY_AFTER_HEADER)?.toIntOrNull()
        val waitMs = if (retryAfter != null && retryAfter > 0) {
            retryAfter * 1000L
        } else {
            60_000L // default 60s if no Retry-After header
        }
        rateLimitUntil = System.currentTimeMillis() + waitMs
        Log.w(TAG, "Received 429 rate limit, backing off ${waitMs}ms, degrading to $RATE_LIMIT_DEGRADED req/min")
    }

    /**
     * Persists backoff state to SharedPreferences so it survives process death.
     */
    private fun persistRateLimitState(prefs: SharedPreferences?) {
        prefs ?: return
        prefs.edit()
            .putLong(CACHE_BACKOFF_KEY, rateLimitUntil)
            .apply()
    }

    /**
     * Restores backoff state from SharedPreferences.
     */
    private fun restoreRateLimitState(prefs: SharedPreferences?) {
        prefs ?: return
        val savedUntil = prefs.getLong(CACHE_BACKOFF_KEY, 0L)
        if (savedUntil > System.currentTimeMillis()) {
            rateLimitUntil = savedUntil
            rateLimitDegraded = true
        }
    }

    // ========================== GraphQL Execution ==========================

    /**
     * Executes a GraphQL query against AniList, using [graphQLPost] from core
     * for request construction and [parseGraphQLAs] for response parsing.
     *
     * Handles rate-limiting (throttling, 429 Retry-After, X-RateLimit-Remaining),
     * and caches results in SharedPreferences when provided.
     */
    private inline fun <reified T> executeQuery(
        client: OkHttpClient,
        query: String,
        variables: JsonObject,
        cacheKey: String? = null,
        prefs: SharedPreferences? = null,
        cacheTtlMs: Long = 15 * 60_000L, // 15 minutes default
    ): T? {
        // Check cache first
        cacheKey?.let { key ->
            prefs?.let { p ->
                restoreRateLimitState(p)
                val cached = readCache<T>(p, key, cacheTtlMs)
                if (cached != null) {
                    Log.d(TAG, "Cache hit for key=$key")
                    return cached
                }
            }
        }

        throttle()

        return try {
            val request = graphQLPost(
                url = GRAPHQL_URL,
                headers = graphQLHeaders,
                query = query,
                variables = variables,
            )
            client.newCall(request).execute().use { response ->
                handleRateLimitHeaders(response)

                when {
                    response.code == 429 -> {
                        handle429(response)
                        persistRateLimitState(prefs)
                        null
                    }
                    !response.isSuccessful -> {
                        Log.e(TAG, "AniList request failed: ${response.code}")
                        null
                    }
                    else -> {
                        val result = response.parseGraphQLAs<T>()
                        cacheKey?.let { key ->
                            prefs?.let { p -> writeCache(p, key, cacheTtlMs, result) }
                        }
                        result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AniList query failed: ${e.message}")
            null
        }
    }

    // ========================== Caching ==========================

    @Serializable
    private data class CacheEntry(
        val timestamp: Long,
        val data: String,
    )

    private inline fun <reified T> writeCache(prefs: SharedPreferences, key: String, ttlMs: Long, data: T) {
        try {
            val entry = CacheEntry(
                timestamp = System.currentTimeMillis(),
                data = data.toJsonString(),
            )
            prefs.edit()
                .putString("$CACHE_KEY_PREFIX$key", entry.toJsonString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache for key=$key: ${e.message}")
        }
    }

    private inline fun <reified T> readCache(
        prefs: SharedPreferences,
        key: String,
        ttlMs: Long,
    ): T? {
        return try {
            val raw = prefs.getString("$CACHE_KEY_PREFIX$key", null) ?: return null
            val entry = raw.parseAs<CacheEntry>()
            if (System.currentTimeMillis() - entry.timestamp > ttlMs) return null
            entry.data.parseAs<T>()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache for key=$key: ${e.message}")
            null
        }
    }

    // ========================== MAL ID Resolution ==========================

    /**
     * Resolve an AniList media ID to its corresponding MAL (MyAnimeList) ID.
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @return The MAL ID if found, or null.
     */
    fun fetchMalId(client: OkHttpClient = defaultClient, anilistId: Int): Int? {
        val query = """
            query media(${"$"}id: Int, ${"$"}type: MediaType) {
                Media(id: ${"$"}id, type: ${"$"}type) {
                    idMal
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        return executeQuery<AniListResponse<MediaData>>(client, query, variables)
            ?.data?.media?.malId
    }

    // ========================== Airing Schedule ==========================

    /**
     * Fetch the airing schedule for an anime from AniList.
     * Returns a map of episode number → air date (in milliseconds since epoch).
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @return [AiringScheduleResult] with episode→airDate mapping.
     */
    fun fetchAiringSchedule(client: OkHttpClient = defaultClient, anilistId: Int): AiringScheduleResult {
        val query = """
            query media(${"$"}id: Int) {
                Media(id: ${"$"}id, type: ANIME) {
                    nextAiringEpisode { episode airingAt }
                    airingSchedule(notYetAired: false) {
                        nodes { episode airingAt }
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
        }
        val response = executeQuery<AniListResponse<MediaData>>(client, query, variables)
        val media = response?.data?.media ?: return AiringScheduleResult()

        val schedule = mutableMapOf<Float, Long>()

        media.nextAiringEpisode?.let { next ->
            if (next.episode > 0 && next.airingAt > 0) {
                schedule[next.episode.toFloat()] = next.airingAt * 1000L
            }
        }

        media.airingSchedule?.nodes?.forEach { node ->
            if (node.episode > 0 && node.airingAt > 0 && !schedule.containsKey(node.episode.toFloat())) {
                schedule[node.episode.toFloat()] = node.airingAt * 1000L
            }
        }

        return AiringScheduleResult(schedule = schedule)
    }

    // ========================== Media Details ==========================

    /**
     * Fetch media details from AniList. Returns a comprehensive [MediaSnapshot]
     * including title, cover, status, genres, studios, format, episodes,
     * duration, season, tags, relations, characters, trailer, and more.
     *
     * Results are cached in [prefs] for [cacheTtlMs] (default 15 minutes).
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @param prefs Optional SharedPreferences for caching.
     * @param cacheTtlMs Cache time-to-live in milliseconds. Default 15 minutes.
     * @return [MediaSnapshot] with media details, or null on failure.
     */
    fun fetchMediaDetails(
        client: OkHttpClient = defaultClient,
        anilistId: Int,
        prefs: SharedPreferences? = null,
        cacheTtlMs: Long = 15 * 60_000L,
    ): MediaSnapshot? {
        val query = """
            query media(${"$"}id: Int, ${"$"}type: MediaType) {
                Media(id: ${"$"}id, type: ${"$"}type) {
                    id
                    idMal
                    title { userPreferred romaji english native }
                    coverImage { extraLarge large medium }
                    bannerImage
                    description
                    status
                    genres
                    studios(sort: NAME) { edges { isMain node { name } } }
                    nextAiringEpisode { episode airingAt }
                    airingSchedule(notYetAired: false) {
                        nodes { episode airingAt }
                    }
                    format
                    episodes
                    duration
                    season
                    seasonYear
                    startDate { year month day }
                    endDate { year month day }
                    synonyms
                    averageScore
                    popularity
                    tags { name description rank isGeneralSpoiler isMediaSpoiler }
                    relations {
                        edges { relationType node { id type format title { userPreferred romaji english native } coverImage { extraLarge large medium } status episodes siteUrl } }
                    }
                    characters(sort: ROLE, perPage: 8) {
                        edges { role node { id name { full native } image { large medium } } }
                    }
                    trailer { id site thumbnail }
                    countryOfOrigin
                    source
                    siteUrl
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        return executeQuery<AniListResponse<MediaData>>(
            client,
            query,
            variables,
            cacheKey = "media_$anilistId",
            prefs = prefs,
            cacheTtlMs = cacheTtlMs,
        )?.data?.media
    }

    // ========================== Search ==========================

    /**
     * Search for anime on AniList by text query.
     *
     * @param client OkHttpClient to use for the request.
     * @param query Search text.
     * @param page Page number (1-indexed).
     * @param perPage Results per page (max 50).
     * @return [Pair] of media list and [PageInfo], or null on failure.
     */
    fun fetchSearchAnime(
        client: OkHttpClient = defaultClient,
        query: String,
        page: Int = 1,
        perPage: Int = 20,
    ): Pair<List<MediaSnapshot>, PageInfo?>? {
        val q = """
            query (${"$"}search: String, ${"$"}page: Int, ${"$"}perPage: Int, ${"$"}type: MediaType) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    pageInfo { total currentPage lastPage hasNextPage perPage }
                    media(search: ${"$"}search, type: ${"$"}type, sort: SEARCH_MATCH) {
                        id idMal title { userPreferred romaji english native }
                        coverImage { extraLarge large medium }
                        bannerImage status format episodes season seasonYear
                        genres averageScore siteUrl
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("search", query)
            put("page", page)
            put("perPage", perPage)
            put("type", "ANIME")
        }
        val result = executeQuery<AniListResponse<PageData>>(client, q, variables)
            ?: return null
        return result.data.media to result.data.pageInfo
    }

    // ========================== Trending ==========================

    /**
     * Fetch trending anime from AniList.
     *
     * @param client OkHttpClient to use for the request.
     * @param page Page number (1-indexed).
     * @param perPage Results per page (max 50).
     * @return [Pair] of media list and [PageInfo], or null on failure.
     */
    fun fetchTrending(
        client: OkHttpClient = defaultClient,
        page: Int = 1,
        perPage: Int = 20,
    ): Pair<List<MediaSnapshot>, PageInfo?>? {
        val query = """
            query (${"$"}page: Int, ${"$"}perPage: Int, ${"$"}type: MediaType) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    pageInfo { total currentPage lastPage hasNextPage perPage }
                    media(type: ${"$"}type, sort: TRENDING_DESC) {
                        id idMal title { userPreferred romaji english native }
                        coverImage { extraLarge large medium }
                        bannerImage status format episodes season seasonYear
                        genres averageScore siteUrl
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
            put("type", "ANIME")
        }
        val result = executeQuery<AniListResponse<PageData>>(client, query, variables)
            ?: return null
        return result.data.media to result.data.pageInfo
    }

    // ========================== Seasonal ==========================

    /**
     * Fetch anime for a specific season and year from AniList.
     *
     * @param client OkHttpClient to use for the request.
     * @param season The season (WINTER, SPRING, SUMMER, FALL).
     * @param year The year.
     * @param page Page number (1-indexed).
     * @param perPage Results per page (max 50).
     * @return [Pair] of media list and [PageInfo], or null on failure.
     */
    fun fetchSeasonal(
        client: OkHttpClient = defaultClient,
        season: String,
        year: Int,
        page: Int = 1,
        perPage: Int = 20,
    ): Pair<List<MediaSnapshot>, PageInfo?>? {
        val query = """
            query (${"$"}season: MediaSeason, ${"$"}seasonYear: Int, ${"$"}page: Int, ${"$"}perPage: Int, ${"$"}type: MediaType) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    pageInfo { total currentPage lastPage hasNextPage perPage }
                    media(season: ${"$"}season, seasonYear: ${"$"}seasonYear, type: ${"$"}type, sort: POPULARITY_DESC) {
                        id idMal title { userPreferred romaji english native }
                        coverImage { extraLarge large medium }
                        bannerImage status format episodes season seasonYear
                        genres averageScore siteUrl
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("season", season.uppercase())
            put("seasonYear", year)
            put("page", page)
            put("perPage", perPage)
            put("type", "ANIME")
        }
        val result = executeQuery<AniListResponse<PageData>>(client, query, variables)
            ?: return null
        return result.data.media to result.data.pageInfo
    }

    // ========================== Related Media ==========================

    /**
     * Fetch media related to a given AniList anime.
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @return List of [MediaEdge] representing related anime/manga, or null on failure.
     */
    fun fetchRelatedMedia(
        client: OkHttpClient = defaultClient,
        anilistId: Int,
    ): List<MediaEdge>? {
        val query = """
            query (${"$"}id: Int, ${"$"}type: MediaType) {
                Media(id: ${"$"}id, type: ${"$"}type) {
                    relations {
                        edges { relationType node { id type format title { userPreferred romaji english native } coverImage { extraLarge large medium } status episodes siteUrl } }
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        return executeQuery<AniListResponse<MediaData>>(client, query, variables)
            ?.data?.media?.relations?.edges
    }

    // ========================== Episode Titles (ani.zip) ==========================

    /**
     * Fetch episode titles and metadata from the ani.zip API.
     *
     * Returns per-episode data including title, Japanese title,
     * air date, and runtime. This complements AniList which only
     * provides airing schedule but not episode titles.
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @return [EpisodeTitlesResult] mapping episode number → episode data.
     */
    fun fetchEpisodeTitles(
        client: OkHttpClient = defaultClient,
        anilistId: Int,
    ): EpisodeTitlesResult {
        return try {
            val request = Request.Builder()
                .url("$ANIZIP_URL?anilist_id=$anilistId")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "ani.zip request failed: ${response.code}")
                    return EpisodeTitlesResult()
                }
                val body = response.body.string()
                val anizipResponse = body.parseAs<AnizipResponse>()
                val episodes = mutableMapOf<Int, AnizipEpisode>()
                anizipResponse.episodes?.forEach { (numStr, episode) ->
                    val num = numStr.toIntOrNull() ?: return@forEach
                    episodes[num] = episode
                }
                EpisodeTitlesResult(episodes = episodes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ani.zip fetch failed for anilistId=$anilistId: ${e.message}")
            EpisodeTitlesResult()
        }
    }

    // ========================== Title Resolution ==========================

    /**
     * Resolve the display title from a [MediaTitle] based on the preferred style.
     *
     * @param title The media title object from AniList.
     * @param style The preferred title style: "userPreferred", "romaji", "english", or "native".
     * @return The resolved title string.
     */
    fun resolveTitle(title: MediaTitle?, style: String = "userPreferred"): String {
        if (title == null) return ""
        val fallbackChain = when (style) {
            "romaji" -> listOf(title.romaji, title.userPreferred, title.english, title.native)
            "english" -> listOf(title.english, title.romaji, title.userPreferred, title.native)
            "native" -> listOf(title.native, title.userPreferred, title.romaji, title.english)
            else -> listOf(title.userPreferred, title.romaji, title.english, title.native)
        }
        return fallbackChain.firstNotNullOfOrNull { it?.ifEmpty { null } } ?: ""
    }

    /**
     * Resolve the best cover image URL from a [MediaCoverImage].
     *
     * @param cover The cover image object from AniList.
     * @return The best available cover image URL, or empty string.
     */
    fun resolveCoverUrl(cover: MediaCoverImage?): String {
        if (cover == null) return ""
        return cover.extraLarge?.ifEmpty { null }
            ?: cover.large?.ifEmpty { null }
            ?: cover.medium?.ifEmpty { null }
            ?: ""
    }

    /**
     * Resolve the main studio name from [MediaStudios].
     *
     * @param studios The studios connection from AniList.
     * @return The main studio name, first studio name, or empty string.
     */
    fun resolveMainStudio(studios: MediaStudios?): String {
        if (studios == null) return ""
        val edges = studios.edges ?: return ""
        return edges.firstOrNull { it.isMain }?.node?.name?.ifEmpty { null }
            ?: edges.firstOrNull()?.node?.name?.ifEmpty { null }
            ?: ""
    }
}
