package aniyomi.lib.anilib

import eu.kanade.tachiyomi.animesource.model.SAnime
import keiyoushi.templating.ExtensionMetadata
import keiyoushi.templating.MetadataSubProvider
import keiyoushi.templating.MetaproviderContext
import keiyoushi.templating.stripHtml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [MetadataSubProvider] that enriches metadata from the TMDb (The Movie Database) REST API.
 *
 * TMDb uses its own numeric ID space. The TMDb ID is read from
 * [MetaproviderContext.nativeIds] under the key `"tmdb"`. The
 * extension's delegate (or a previous provider) is responsible for
 * populating this value.
 *
 * **Data flow:**
 * 1. Extension delegate resolves TMDb ID → puts in `nativeIds["tmdb"]`
 * 2. This provider reads `nativeIds["tmdb"]`, fetches from TMDb API
 * 3. Returns enriched [ExtensionMetadata]
 *
 * **API Key:**
 * TMDb requires an API key. The provider reads it from preferences
 * under the key `"tmdb_api_key"`. Extensions should add a preference
 * entry for users to provide their own key.
 *
 * @param priority Lower runs earlier. Defaults to 25 (after AniLib at 20).
 * @param cacheTtlMs Cache time-to-live for API responses. Defaults to 15 minutes.
 */
class TmdbMetadataProvider(
    override val priority: Int = 25,
    override val name: String = "TmdbMetadataProvider",
    private val cacheTtlMs: Long = 15 * 60_000L,
) : MetadataSubProvider {

    companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
        const val NATIVE_KEY = "tmdb"
        const val PREF_KEY_API_KEY = "tmdb_api_key"
    }

    private val cache = mutableMapOf<String, CachedResponse>()

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val tmdbId = context.nativeIds[NATIVE_KEY]
            ?: return ExtensionMetadata()

        val client: OkHttpClient = context.httpClient
            ?: return ExtensionMetadata()

        val apiKey = context.preferences?.getString(PREF_KEY_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return ExtensionMetadata()

        // Check cache
        val cacheKey = "tmdb_$tmdbId"
        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
                return cached.metadata
            }
        }

        val data = fetchAnime(client, tmdbId, apiKey)
            ?: return ExtensionMetadata()

        val metadata = mapToMetadata(data)
        cache[cacheKey] = CachedResponse(metadata, System.currentTimeMillis())
        return metadata
    }

    private fun fetchAnime(client: OkHttpClient, tmdbId: Int, apiKey: String): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/tv/$tmdbId?api_key=$apiKey&language=en-US")
            .header("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToMetadata(data: JSONObject): ExtensionMetadata {
        // Title: prefer original_name > name
        val title = data.optString("original_name", null)
            ?: data.optString("name", null)

        // Description
        val description = data.optString("overview", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { stripHtml(it) }

        // Poster image: prefer poster_path > backdrop_path
        val posterPath = data.optString("poster_path", null)
        val backdropPath = data.optString("backdrop_path", null)
        val thumbnail = posterPath?.let { "$IMAGE_BASE_URL/w500$it" }
            ?: backdropPath?.let { "$IMAGE_BASE_URL/w780$it" }

        // Genres
        val genre = extractGenres(data)

        // Status
        val status = mapStatus(data.optString("status", null))

        // Network/Studios
        val networks = data.optJSONArray("networks")
        val author = if (networks != null && networks.length() > 0) {
            networks.getJSONObject(0).optString("name", null)
        } else {
            null
        }

        return ExtensionMetadata(
            title = title,
            description = description,
            thumbnailUrl = thumbnail,
            author = author,
            genre = genre,
            status = status,
        )
    }

    private fun extractGenres(data: JSONObject): String? {
        val genres = data.optJSONArray("genres") ?: return null
        val items = mutableListOf<String>()
        for (i in 0 until genres.length()) {
            genres.optJSONObject(i)?.optString("name")?.let { items.add(it) }
        }
        return items.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun mapStatus(status: String?): Int? = when (status) {
        "Returning Series" -> SAnime.ONGOING
        "Ended" -> SAnime.COMPLETED
        "Canceled" -> SAnime.CANCELLED
        "In Production" -> SAnime.ONGOING
        "Planned" -> SAnime.LICENSED
        else -> null
    }

    private data class CachedResponse(
        val metadata: ExtensionMetadata,
        val timestamp: Long,
    )
}
