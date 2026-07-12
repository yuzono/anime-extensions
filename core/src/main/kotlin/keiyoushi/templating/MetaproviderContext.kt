package keiyoushi.templating

import android.content.Context
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Context passed to [MetadataSubProvider.provide] and pre-populate delegates.
 *
 * **ID hierarchy:**
 * - [anilistId] is the universal master ID. The delegate (or extension)
 *   resolves it once; every sub-provider that understands AniList IDs
 *   uses it directly.
 * - [nativeIds] carries provider-specific IDs (e.g. MAL, Kitsu) when a
 *   provider cannot convert from AniList. The delegate populates this
 *   map with the IDs it knows. Providers read their own key.
 *
 * Key convention: `"mal"` for MyAnimeList, `"kitsu"` for Kitsu,
 * `"anidb"` for AniDB, etc.
 *
 * **Storage:**
 * - [preferences] for small key-value data (settings, flags).
 * - [context] for large datasets — use [Context.filesDir] or
 *   [Context.cacheDir] to persist JSON/database files.
 *
 * The remaining fields carry enough information for providers to perform
 * HTTP requests or scrape a pre-fetched document without re-fetching.
 */
data class MetaproviderContext(
    val baseUrl: String,
    val anilistId: Int? = null,
    val nativeIds: Map<String, Int> = emptyMap(),
    val animeUrl: String? = null,
    val httpClient: OkHttpClient? = null,
    val headers: Headers? = null,
    val document: Document? = null,
    val preferences: android.content.SharedPreferences? = null,
    val context: Context? = null,
    val extra: Map<String, Any?> = emptyMap(),
)
