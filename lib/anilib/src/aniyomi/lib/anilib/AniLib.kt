package aniyomi.lib.anilib

import android.util.Log
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * AniLib — AniList GraphQL client library for anime metadata interaction.
 *
 * Provides methods to query the AniList GraphQL API for:
 * - Resolving AniList ID to MAL ID
 * - Fetching airing schedule (episode number → air date)
 * - Fetching media details (title, cover, status, genres, studios)
 *
 * All methods are synchronous (blocking) and accept an [OkHttpClient] to allow
 * callers to configure rate limiting, headers, etc.
 */
object AniLib {

    private const val TAG = "AniLib"
    private const val GRAPHQL_URL = "https://graphql.anilist.co"

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
     * Fetch media details from AniList.
     *
     * @param client OkHttpClient to use for the request.
     * @param anilistId The AniList media ID.
     * @return [MediaSnapshot] with media details, or null on failure.
     */
    fun fetchMediaDetails(client: OkHttpClient = defaultClient, anilistId: Int): MediaSnapshot? {
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
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        return executeQuery<AniListResponse<MediaData>>(client, query, variables)
            ?.data?.media
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

    // ========================== GraphQL Execution ==========================

    private inline fun <reified T> executeQuery(
        client: OkHttpClient,
        query: String,
        variables: kotlinx.serialization.json.JsonObject,
    ): T? {
        return try {
            val body = FormBody.Builder()
                .add("query", query)
                .add("variables", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), variables))
                .build()
            val request = POST(GRAPHQL_URL, body = body)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "AniList request failed: ${response.code}")
                    return null
                }
                response.parseAs<T>()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AniList query failed: ${e.message}")
            null
        }
    }
}
