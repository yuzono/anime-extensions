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
) {
    fun getNativeId(type: String): Int? = nativeIds[type]

    fun getMalId(): Int? = nativeIds["mal"]

    fun getKitsuId(): Int? = nativeIds["kitsu"]

    fun getAnidbId(): Int? = nativeIds["anidb"]

    fun hasAnilistId(): Boolean = anilistId != null

    fun hasNativeIds(): Boolean = nativeIds.isNotEmpty()

    fun getPreferenceString(key: String, default: String? = null): String? = preferences?.getString(key, default) ?: default

    fun getPreferenceBoolean(key: String, default: Boolean = false): Boolean = preferences?.getBoolean(key, default) ?: default

    fun <T> getExtra(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return extra[key] as? T
    }

    fun <T> getExtra(key: String, defaultValue: T): T = getExtra(key) ?: defaultValue

    fun withAnilistId(id: Int): MetaproviderContext = copy(anilistId = id)

    fun withNativeIds(ids: Map<String, Int>): MetaproviderContext = copy(nativeIds = nativeIds + ids)

    fun withNativeId(type: String, id: Int): MetaproviderContext = copy(nativeIds = nativeIds + (type to id))

    fun withDocument(doc: Document): MetaproviderContext = copy(document = doc)

    fun withExtra(key: String, value: Any?): MetaproviderContext = copy(extra = extra + (key to value))

    fun buildString(): String = buildString {
        append("MetaproviderContext(")
        append("baseUrl=$baseUrl")
        anilistId?.let { append(", anilistId=$it") }
        if (nativeIds.isNotEmpty()) append(", nativeIds=$nativeIds")
        animeUrl?.let { append(", animeUrl=$it") }
        append(")")
    }

    companion object {
        fun builder(baseUrl: String) = Builder(baseUrl)

        fun fromAnime(
            baseUrl: String,
            animeUrl: String,
            httpClient: OkHttpClient? = null,
            headers: Headers? = null,
            preferences: android.content.SharedPreferences? = null,
            context: Context? = null,
        ) = Builder(baseUrl)
            .animeUrl(animeUrl)
            .apply { httpClient?.let { httpClient(it) } }
            .apply { headers?.let { headers(it) } }
            .apply { preferences?.let { preferences(it) } }
            .apply { context?.let { context(it) } }
            .build()
    }

    class Builder(private val baseUrl: String) {
        private var anilistId: Int? = null
        private var nativeIds: MutableMap<String, Int> = mutableMapOf()
        private var animeUrl: String? = null
        private var httpClient: OkHttpClient? = null
        private var headers: Headers? = null
        private var document: Document? = null
        private var preferences: android.content.SharedPreferences? = null
        private var context: Context? = null
        private var extra: MutableMap<String, Any?> = mutableMapOf()

        fun anilistId(id: Int) = apply { this.anilistId = id }
        fun nativeIds(ids: Map<String, Int>) = apply { this.nativeIds.putAll(ids) }
        fun nativeId(type: String, id: Int) = apply { this.nativeIds[type] = id }
        fun animeUrl(url: String) = apply { this.animeUrl = url }
        fun httpClient(client: OkHttpClient) = apply { this.httpClient = client }
        fun headers(headers: Headers) = apply { this.headers = headers }
        fun document(doc: Document) = apply { this.document = doc }
        fun preferences(prefs: android.content.SharedPreferences) = apply { this.preferences = prefs }
        fun context(ctx: Context) = apply { this.context = ctx }
        fun extra(key: String, value: Any?) = apply { this.extra[key] = value }

        fun build() = MetaproviderContext(
            baseUrl = baseUrl,
            anilistId = anilistId,
            nativeIds = nativeIds,
            animeUrl = animeUrl,
            httpClient = httpClient,
            headers = headers,
            document = document,
            preferences = preferences,
            context = context,
            extra = extra,
        )
    }
}
