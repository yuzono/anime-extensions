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
 * [MetadataSubProvider] that enriches metadata from the Tenrai REST API
 * (Jikan v4 compatible).
 *
 * Tenrai uses MAL IDs, not AniList IDs. The MAL ID is read from
 * [MetaproviderContext.nativeIds] under the key `"mal"`. The extension's
 * delegate (or a previous provider) is responsible for populating this
 * value.
 *
 * **Data flow:**
 * 1. Extension delegate resolves MAL ID from its source URL → puts in `nativeIds["mal"]`
 * 2. This provider reads `nativeIds["mal"]`, fetches from Tenrai
 * 3. Returns enriched [ExtensionMetadata]
 *
 * @param priority Lower runs earlier. Defaults to 10 (before AniLib at 20).
 */
class TenraiMetadataProvider(
    override val priority: Int = 10,
    override val name: String = "TenraiMetadataProvider",
) : MetadataSubProvider {

    companion object {
        private const val BASE_URL = "https://api.tenrai.org/v1"
        const val NATIVE_KEY = "mal"
    }

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val malId = context.nativeIds[NATIVE_KEY]
            ?: return ExtensionMetadata()

        val client: OkHttpClient = context.httpClient
            ?: return ExtensionMetadata()

        val data = fetchAnime(client, malId)
            ?: return ExtensionMetadata()

        return mapToMetadata(data)
    }

    private fun fetchAnime(client: OkHttpClient, malId: Int): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/anime/$malId")
            .header("Accept", "application/json")
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
        // Title: prefer English > Default > Japanese
        val title = data.optString("title_english", null)
            ?: data.optString("title", null)
            ?: data.optString("title_japanese", null)
            ?: run {
                // Synonyms fallback
                val synonyms = data.optJSONArray("title_synonyms")
                if (synonyms != null && synonyms.length() > 0) {
                    synonyms.getString(0)
                } else null
            }

        // Description
        val description = data.optString("synopsis", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { stripHtml(it) }

        // Cover image: prefer large_image_url > image_url
        val images = data.optJSONObject("images")
        val jpg = images?.optJSONObject("jpg")
        val thumbnail = jpg?.optString("large_image_url", null)
            ?: jpg?.optString("image_url", null)

        // Studios
        val studios = data.optJSONArray("studios")
        val author = if (studios != null && studios.length() > 0) {
            studios.getJSONObject(0).optString("name", null)
        } else null

        // Genres + Themes + Demographics
        val genre = buildGenreList(data)

        // Status
        val status = mapStatus(data.optString("status", null))

        return ExtensionMetadata(
            title = title,
            description = description,
            thumbnailUrl = thumbnail,
            author = author,
            genre = genre,
            status = status,
        )
    }

    private fun buildGenreList(data: JSONObject): String? {
        val items = mutableListOf<String>()
        for (key in listOf("genres", "themes", "demographics")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.let { items.add(it) }
            }
        }
        return items.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun mapStatus(status: String?): Int? = when (status) {
        "Currently Airing" -> SAnime.ONGOING
        "Finished Airing" -> SAnime.COMPLETED
        "Not yet aired" -> SAnime.LICENSED
        else -> null
    }
}
