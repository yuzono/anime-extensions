package keiyoushi.templating

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import kotlin.getValue

/**
 * Abstract [AnimeHttpSource] that provides:
 *
 * 1. **Identity delegation** — subclasses provide a single
 *    [ExtensionMetadata] object; `name`, `lang`, `baseUrl`,
 *    `supportsLatest` are delegated to it, eliminating per-extension
 *    `override val` duplication.
 * 2. **Modular metadata resolution** — each subclass declares a list of
 *    [MetadataSubProvider] instances (and optionally a [metadataDelegate])
 *    that are orchestrated by a per-instance [MetadataProvider]. The
 *    delegate runs first and always wins; providers fill only remaining
 *    `null` fields.
 * 3. **Auto-managed preferences** — subclasses declare a declarative
 *    [preferenceSchema] (a list of [PreferenceEntry] instances). The
 *    [PreferenceRegistry] handles typed reads (`registry[key]`), writes
 *    (via the underlying [PreferenceDelegate]), and UI generation
 *    ([PreferenceRegistry.renderTo]) — `setupPreferenceScreen` is
 *    auto-implemented, no per-extension UI code required.
 *
 * This class coexists with [keiyoushi.utils.Source] and the existing
 * `lib-multisrc` themes. Existing extensions are unaffected; new
 * extensions extend [AnimeExtension] instead of `AnimeHttpSource`.
 */
abstract class AnimeExtension :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    // ==================== Identity ====================

    /**
     * Single source of truth for this extension's identity and any
     * metadata known at construction time.
     *
     * `name`, `lang`, `baseUrl`, and `supportsLatest` are delegated here;
     * the remaining fields (`title`, `description`, `thumbnailUrl`, …)
     * serve as seed values for [resolveMetadata].
     */
    protected abstract val identity: ExtensionMetadata

    override val name: String get() = requireNotNull(identity.name) { "identity.name must not be null" }
    override val lang: String get() = requireNotNull(identity.lang) { "identity.lang must not be null" }
    override val baseUrl: String get() = requireNotNull(identity.baseUrl) { "identity.baseUrl must not be null" }
    override val supportsLatest: Boolean get() = identity.supportsLatest

    // ==================== Lifecycle Hooks ====================

    /**
     * Called once when the extension is first created.
     * Override to perform initialization logic (e.g., setup database, load configs).
     * Default implementation is a no-op.
     */
    open fun onInit() {}

    /**
     * Called when the extension is being torn down.
     * Override to perform cleanup logic (e.g., close database connections, cancel pending jobs).
     * Default implementation is a no-op.
     */
    open fun onDestroy() {}

    /**
     * Called when the user changes a preference.
     * Override to react to preference changes (e.g., rebuild client, clear caches).
     *
     * @param key The preference key that changed
     * @param newValue The new value
     * @return true if the preference should be persisted, false to reject the change
     */
    open fun onPreferenceChanged(key: String, newValue: Any?): Boolean = true

    // ==================== Metadata Providers ====================

    /** Sub-providers invoked during [resolveMetadata], in ascending [MetadataSubProvider.priority]. */
    open val metadataSubProviders: List<MetadataSubProvider> = emptyList()

    /**
     * Pre-populate delegate that runs before sub-providers. Non-null fields
     * returned here always win — providers can only fill `null` gaps.
     */
    open val metadataDelegate: (suspend MetaproviderContext.() -> ExtensionMetadata)? = null

    /**
     * Controls how providers merge metadata fields.
     * - [MergeStrategy.FILL_NULLS] (default): delegate seeds, providers fill gaps only.
     * - [MergeStrategy.OVERRIDE_ALL]: each provider can override any field.
     * - [MergeStrategy.OVERRIDE_NON_DELEGATE]: delegate fields locked, providers override each other.
     */
    open val metadataMergeStrategy: MergeStrategy = MergeStrategy.FILL_NULLS

    /** Per-instance orchestrator — NOT a global singleton. */
    val metadataProvider: MetadataProvider by lazy {
        MetadataProvider(metadataSubProviders, metadataMergeStrategy).also { provider ->
            val cache = AnimeDatabaseCache(context, client)
            provider.setDatabaseCache(cache)
        }
    }

    /**
     * Resolves the fully-merged metadata for the given [context] by
     * invoking the [metadataDelegate] (if present) and then folding in
     * each [metadataSubProviders] entry.
     */
    suspend fun resolveMetadata(context: MetaproviderContext): ExtensionMetadata = metadataProvider.resolve(context, metadataDelegate)

    /**
     * Convenience method to resolve metadata from an anime URL.
     * Automatically creates the context and resolves the metadata.
     *
     * @param anime The anime to resolve metadata for
     * @param anilistId Optional AniList ID (if known)
     * @param document Optional pre-fetched document
     * @return Merged metadata
     */
    suspend fun resolveMetadataFor(
        anime: SAnime,
        anilistId: Int? = null,
        document: org.jsoup.nodes.Document? = null,
    ): ExtensionMetadata {
        val ctx = context ?: return ExtensionMetadata()
        val context = MetaproviderContext(
            baseUrl = baseUrl,
            anilistId = anilistId,
            animeUrl = anime.url,
            httpClient = client,
            headers = headers,
            document = document,
            preferences = preferences,
            context = ctx,
        )
        return resolveMetadata(context)
    }

    /**
     * Apply resolved metadata to an SAnime object.
     * Useful for getAnimeDetails() implementations.
     *
     * @param anime The anime to update
     * @param meta The resolved metadata
     * @param mergeWithOriginal If true, non-null meta fields override anime fields;
     *   if false, only null anime fields are filled from meta
     */
    fun applyMetadata(
        anime: SAnime,
        meta: ExtensionMetadata,
        mergeWithOriginal: Boolean = true,
    ): SAnime = if (mergeWithOriginal) {
        anime.apply {
            title = meta.title ?: anime.title
            author = meta.author ?: anime.author
            artist = meta.artist ?: anime.artist
            genre = meta.genre ?: anime.genre
            description = meta.description ?: anime.description
            thumbnail_url = meta.thumbnailUrl ?: anime.thumbnail_url
            status = meta.status ?: anime.status
        }
    } else {
        SAnime.create().apply {
            title = meta.title ?: anime.title
            author = meta.author
            artist = meta.artist
            genre = meta.genre
            description = meta.description
            thumbnail_url = meta.thumbnailUrl
            status = meta.status ?: SAnime.UNKNOWN
        }
    }

    // ==================== Preferences ====================

    /** Declarative preference schema. See [PreferenceEntry]. */
    open val preferenceSchema: List<PreferenceEntry<*>> = emptyList()

    protected open val migration: SharedPreferences.() -> Unit = {}

    val preferences: SharedPreferences by getPreferencesLazy { migration }

    val preferenceRegistry: PreferenceRegistry by lazy {
        PreferenceRegistry.fromSchema(preferenceSchema, preferences)
    }

    // Auto-implemented — extensions do NOT override this unless they need
    // custom additional preferences beyond the schema.
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferenceRegistry.renderTo(screen)
    }

    fun getString(key: String, default: String = ""): String = preferenceRegistry.getString(key, default)
    fun getStringOrNull(key: String): String? = preferenceRegistry.getStringOrNull(key)
    fun getInt(key: String, default: Int = 0): Int = preferenceRegistry.getInt(key, default)
    fun getLong(key: String, default: Long = 0L): Long = preferenceRegistry.getLong(key, default)
    fun getFloat(key: String, default: Float = 0f): Float = preferenceRegistry.getFloat(key, default)
    fun getBoolean(key: String, default: Boolean = false): Boolean = preferenceRegistry.getBoolean(key, default)
    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> = preferenceRegistry.getStringSet(key, default)

    fun <T> getOrDefault(key: String, defaultValue: T): T {
        @Suppress("UNCHECKED_CAST")
        return when (defaultValue) {
            is String -> getString(key, defaultValue) as T
            is Int -> getInt(key, defaultValue) as T
            is Long -> getLong(key, defaultValue) as T
            is Float -> getFloat(key, defaultValue) as T
            is Boolean -> getBoolean(key, defaultValue) as T
            else -> defaultValue
        }
    }

    // ==================== Video Utilities ====================

    /**
     * Filter and sort videos based on user preferences.
     * This is a convenience method that combines server filtering, type filtering, and quality sorting.
     *
     * @param videos The list of videos to filter
     * @param preferredQuality The preferred quality (e.g., "1080", "720")
     * @param preferredServer The preferred server (optional)
     * @param excludedServers Set of servers to exclude (optional)
     * @param allowedTypes Set of allowed types (e.g., "Sub", "Dub") (optional)
     * @return Filtered and sorted list
     */
    fun filterAndSortVideos(
        videos: List<Video>,
        preferredQuality: String = getString("preferred_quality", "720"),
        preferredServer: String? = null,
        excludedServers: Set<String> = emptySet(),
        allowedTypes: Set<String> = emptySet(),
    ): List<Video> {
        var result = videos
        if (excludedServers.isNotEmpty()) {
            result = result.filterByExcludedServers(excludedServers)
        }
        if (allowedTypes.isNotEmpty()) {
            result = result.filterByType(allowedTypes)
        }
        return result.sortByQuality(preferredQuality, preferredServer)
    }

    // ==================== Shared Infrastructure ====================

    protected val context: Application by injectLazy()

    open val json: Json by injectLazy()

    protected val handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }

    /**
     * Log a debug message with the extension name as tag.
     */
    protected fun logDebug(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    /**
     * Log an error message with the extension name as tag.
     */
    protected fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    /**
     * Log a warning message with the extension name as tag.
     */
    protected fun logWarn(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "AnimeExtension"
    }

    // ==================== Stubs ====================

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException(
        "popularAnimeRequest not implemented. Override this method in your extension.",
    )
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException(
        "popularAnimeParse not implemented. Override this method in your extension.",
    )
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException(
        "latestUpdatesRequest not implemented. Override this method in your extension.",
    )
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException(
        "latestUpdatesParse not implemented. Override this method in your extension.",
    )
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = throw UnsupportedOperationException(
        "searchAnimeRequest not implemented. Override this method in your extension.",
    )
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException(
        "searchAnimeParse not implemented. Override this method in your extension.",
    )
    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException(
        "animeDetailsRequest not implemented. Override this method in your extension.",
    )
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException(
        "animeDetailsParse not implemented. Override this method in your extension.",
    )
    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException(
        "episodeListRequest not implemented. Override this method in your extension.",
    )
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException(
        "episodeListParse not implemented. Override this method in your extension.",
    )
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException(
        "videoListRequest not implemented. Override this method in your extension.",
    )
    override fun videoListParse(response: Response) = throw UnsupportedOperationException(
        "videoListParse not implemented. Override this method in your extension.",
    )
}
