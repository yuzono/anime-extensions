package keiyoushi.templating

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy

/**
 * Controls how [MetadataProvider] merges metadata from the delegate and
 * sub-providers.
 */
enum class MergeStrategy {
    /**
     * First writer wins. The delegate seeds metadata, then each provider
     * can only fill fields that are still `null`. Once a field is set,
     * no subsequent provider can override it.
     *
     * This is the safest strategy — delegate values are always preserved.
     */
    FILL_NULLS,

    /**
     * Last writer wins. Each provider (including the delegate) can
     * override any field from a previous provider. The final value for
     * each field comes from the last provider that set it.
     *
     * Use this when you want lower-priority providers to act as
     * fallbacks that can be overridden by higher-priority ones.
     */
    OVERRIDE_ALL,

    /**
     * Delegate fields are locked; providers can override each other.
     * The delegate seeds metadata with highest precedence. Providers
     * then run in priority order, and each can override fields set
     * by earlier providers — but never the delegate.
     */
    OVERRIDE_NON_DELEGATE,
}

/**
 * Standardized metadata object returned by [MetadataProvider.resolve] and
 * pre-populate delegates.
 *
 * Every field is nullable. A field that is `null` means "unknown / not yet
 * provided". The [merge] operation only overwrites `null` fields in the
 * receiver with non-null values from [other], which gives delegates
 * (which run first) the highest precedence over sub-providers.
 *
 * Field naming mirrors [eu.kanade.tachiyomi.animesource.model.SAnime] so the
 * metadata object can be directly applied to an `SAnime` instance.
 */
data class ExtensionMetadata(
    // === Identity ===
    val name: String? = null,
    val lang: String? = null,
    val baseUrl: String? = null,
    val isNsfw: Boolean = false,
    val supportsLatest: Boolean = true,

    // === Content metadata (maps 1:1 to SAnime fields) ===
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genre: String? = null,
    val status: Int? = null, // SAnime.ONGOING / COMPLETED / UNKNOWN
    val updateStrategy: AnimeUpdateStrategy? = null,

    // === Provider-specific IDs ===
    // Carries native IDs (MAL, Kitsu, AniDB) that providers read.
    // Delegates populate this when they know IDs the orchestrator can't resolve.
    // Key convention: "mal", "kitsu", "anidb", "anilist".
    val nativeIds: Map<String, Int> = emptyMap(),
) {
    /**
     * Returns a copy of this metadata where every `null` field is filled
     * from the corresponding non-null field of [other].
     *
     * Non-null fields in this metadata are preserved (delegate always wins).
     */
    fun merge(other: ExtensionMetadata): ExtensionMetadata = ExtensionMetadata(
        name = name ?: other.name,
        lang = lang ?: other.lang,
        baseUrl = baseUrl ?: other.baseUrl,
        isNsfw = if (other.isNsfw) true else isNsfw,
        supportsLatest = if (other.supportsLatest == false) false else supportsLatest,
        title = title ?: other.title,
        description = description ?: other.description,
        thumbnailUrl = thumbnailUrl ?: other.thumbnailUrl,
        author = author ?: other.author,
        artist = artist ?: other.artist,
        genre = genre ?: other.genre,
        status = status ?: other.status,
        updateStrategy = updateStrategy ?: other.updateStrategy,
        nativeIds = nativeIds + other.nativeIds,
    )

    /**
     * Returns a copy where [other]'s non-null fields overwrite this
     * metadata's fields — used by [MergeStrategy.OVERRIDE_ALL].
     */
    fun overrideWith(other: ExtensionMetadata): ExtensionMetadata = ExtensionMetadata(
        name = other.name ?: name,
        lang = other.lang ?: lang,
        baseUrl = other.baseUrl ?: baseUrl,
        isNsfw = if (other.isNsfw) true else isNsfw,
        supportsLatest = if (other.supportsLatest == false) false else supportsLatest,
        title = other.title ?: title,
        description = other.description ?: description,
        thumbnailUrl = other.thumbnailUrl ?: thumbnailUrl,
        author = other.author ?: author,
        artist = other.artist ?: artist,
        genre = other.genre ?: genre,
        status = other.status ?: status,
        updateStrategy = other.updateStrategy ?: updateStrategy,
        nativeIds = other.nativeIds.ifEmpty { nativeIds },
    )
}
