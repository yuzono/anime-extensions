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
 * [MetadataSubProvider] that enriches metadata from IMDb (Internet Movie Database).
 *
 * IMDb uses its own ID format (e.g., "tt1234567"). Since IMDb IDs are strings
 * (not integers), they are stored in [MetaproviderContext.extra] under the key
 * `"imdb_id"` rather than in [MetaproviderContext.nativeIds]. The extension's
 * delegate (or a previous provider) is responsible for populating this value.
 *
 * **Data flow:**
 * 1. Extension delegate resolves IMDb ID → puts in `extra["imdb_id"]`
 * 2. This provider reads `extra["imdb_id"]`, fetches from OMDb API
 * 3. Returns enriched [ExtensionMetadata]
 *
 * **API Key:**
 * This provider uses the OMDb API (omdbapi.com) which requires an API key.
 * The provider reads it from preferences under the key `"omdb_api_key"`.
 * Extensions should add a preference entry for users to provide their own key.
 *
 * OMDb API keys can be obtained free at: http://www.omdbapi.com/apikey.aspx
 *
 * @param priority Lower runs earlier. Defaults to 30 (after TMDb at 25).
 * @param cacheTtlMs Cache time-to-live for API responses. Defaults to 15 minutes.
 */
class ImdbMetadataProvider(
    override val priority: Int = 30,
    override val name: String = "ImdbMetadataProvider",
    private val cacheTtlMs: Long = 15 * 60_000L,
) : MetadataSubProvider {

    companion object {
        private const val BASE_URL = "https://www.omdbapi.com"
        const val EXTRA_KEY_IMDB_ID = "imdb_id"
        const val PREF_KEY_API_KEY = "omdb_api_key"
    }

    private val cache = mutableMapOf<String, CachedResponse>()

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val imdbId: String = context.getExtra(EXTRA_KEY_IMDB_ID)
            ?: return ExtensionMetadata()

        val client: OkHttpClient = context.httpClient
            ?: return ExtensionMetadata()

        val apiKey = context.preferences?.getString(PREF_KEY_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return ExtensionMetadata()

        // Check cache
        val cacheKey = "imdb_$imdbId"
        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
                return cached.metadata
            }
        }

        val data = fetchTitle(client, imdbId, apiKey)
            ?: return ExtensionMetadata()

        val metadata = mapToMetadata(data)
        cache[cacheKey] = CachedResponse(metadata, System.currentTimeMillis())
        return metadata
    }

    private fun fetchTitle(client: OkHttpClient, imdbId: String, apiKey: String): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/?i=$imdbId&apikey=$apiKey&plot=short")
            .header("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        return try {
            val json = JSONObject(body)
            // OMDb returns "Response": "False" for errors
            if (json.optString("Response") == "False") null else json
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToMetadata(data: JSONObject): ExtensionMetadata {
        // Title
        val title = data.optString("Title", null)
            ?.takeIf { it.isNotBlank() }

        // Description (from Plot field)
        val description = data.optString("Plot", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }
            ?.let { stripHtml(it) }

        // Poster image
        val thumbnail = data.optString("Poster", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // Year (as additional info)
        val year = data.optString("Year", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // Genre
        val genre = data.optString("Genre", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // Director (as author)
        val author = data.optString("Director", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // IMDb Rating
        val rating = data.optString("imdbRating", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // Type (movie, series, etc.)
        val type = data.optString("Type", null)
            ?.takeIf { it.isNotBlank() && it != "N/A" }

        // Status mapping based on type and year
        val status = mapStatus(type, year)

        // Build description with additional info
        val fullDescription = buildString {
            description?.let { append(it) }
            if (rating != null && rating != "N/A") {
                if (isNotEmpty()) append("\n\n")
                append("IMDb Rating: $rating/10")
            }
            if (year != null && year != "N/A") {
                if (isNotEmpty()) append("\n")
                append("Year: $year")
            }
        }.takeIf { it.isNotBlank() }

        return ExtensionMetadata(
            title = title,
            description = fullDescription,
            thumbnailUrl = thumbnail,
            author = author,
            genre = genre,
            status = status,
        )
    }

    private fun mapStatus(type: String?, year: String?): Int? {
        // For TV series, check if year contains "-" (ongoing) or is a single year (ended)
        if (type == "series" && year != null) {
            return if (year.contains("-")) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        }
        // For movies, assume completed
        if (type == "movie") {
            return SAnime.COMPLETED
        }
        return null
    }

    private data class CachedResponse(
        val metadata: ExtensionMetadata,
        val timestamp: Long,
    )
}
