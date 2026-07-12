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
 * [MetadataSubProvider] that enriches metadata from the Kitsu REST API.
 *
 * Kitsu uses its own ID space. The Kitsu ID is read from
 * [MetaproviderContext.nativeIds] under the key `"kitsu"`. The
 * extension's delegate (or a previous provider) is responsible for
 * populating this value.
 *
 * **Data flow:**
 * 1. Extension delegate resolves Kitsu ID → puts in `nativeIds["kitsu"]`
 * 2. This provider reads `nativeIds["kitsu"]`, fetches from Kitsu
 * 3. Returns enriched [ExtensionMetadata]
 *
 * @param priority Lower runs earlier. Defaults to 15 (between Tenrai at 10 and AniLib at 20).
 */
class KitsuMetadataProvider(
    override val priority: Int = 15,
    override val name: String = "KitsuMetadataProvider",
) : MetadataSubProvider {

    companion object {
        private const val BASE_URL = "https://kitsu.io/api/edge"
        const val NATIVE_KEY = "kitsu"
    }

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val kitsuId = context.nativeIds[NATIVE_KEY]
            ?: return ExtensionMetadata()

        val client: OkHttpClient = context.httpClient
            ?: return ExtensionMetadata()

        val data = fetchAnime(client, kitsuId)
            ?: return ExtensionMetadata()

        return mapToMetadata(data)
    }

    private fun fetchAnime(client: OkHttpClient, kitsuId: Int): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/anime/$kitsuId")
            .header("Accept", "application/vnd.api+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        return try {
            JSONObject(body).optJSONObject("data")
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToMetadata(data: JSONObject): ExtensionMetadata {
        val attrs = data.optJSONObject("attributes") ?: return ExtensionMetadata()

        // Title: prefer en_jp > en_us > canonicalTitle > ja_jp
        val titles = attrs.optJSONObject("titles")
        val title = titles?.optString("en_jp", null)
            ?: titles?.optString("en_us", null)
            ?: attrs.optString("canonicalTitle", null)
            ?: titles?.optString("ja_jp", null)

        // Description
        val description = attrs.optString("synopsis", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { stripHtml(it) }

        // Cover image: prefer original > large > small
        val coverImage = attrs.optJSONObject("coverImage")
        val posterImage = attrs.optJSONObject("posterImage")
        val thumbnail = coverImage?.optString("original", null)
            ?: coverImage?.optString("large", null)
            ?: posterImage?.optString("original", null)
            ?: posterImage?.optString("large", null)

        // Genres (from included resources or relationships)
        val genre = extractGenres(data)

        // Status
        val status = mapStatus(attrs.optString("status", null))

        return ExtensionMetadata(
            title = title,
            description = description,
            thumbnailUrl = thumbnail,
            genre = genre,
            status = status,
        )
    }

    private fun extractGenres(data: JSONObject): String? {
        val included = data.optJSONArray("included") ?: return null
        val genres = mutableListOf<String>()
        for (i in 0 until included.length()) {
            val item = included.optJSONObject(i) ?: continue
            if (item.optString("type") == "genres") {
                item.optJSONObject("attributes")?.optString("name")?.let { genres.add(it) }
            }
        }
        return genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun mapStatus(status: String?): Int? = when (status) {
        "current" -> SAnime.ONGOING
        "finished" -> SAnime.COMPLETED
        "tba", "unreleased", "upcoming" -> SAnime.LICENSED
        else -> null
    }
}
