package keiyoushi.templating

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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

    // ==================== Shared Infrastructure ====================

    protected val context: Application by injectLazy()

    open val json: Json by injectLazy()

    protected val handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }

    // ==================== Stubs ====================
    //
    // Same pattern as keiyoushi.utils.Source: subclasses implement only
    // the methods they actually need. These defaults blow up loudly if
    // called without implementation.

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response) = throw UnsupportedOperationException()
}
