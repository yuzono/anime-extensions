package keiyoushi.templating

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime

/**
 * Controls how [MetadataProvider] merges metadata from the delegate and
 * sub-providers.
 */
enum class MergeStrategy {
    FILL_NULLS,
    OVERRIDE_ALL,
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
 * Field naming mirrors [SAnime] so the metadata object can be directly
 * applied to an `SAnime` instance.
 */
data class ExtensionMetadata(
    val name: String? = null,
    val lang: String? = null,
    val baseUrl: String? = null,
    val isNsfw: Boolean = false,
    val supportsLatest: Boolean = true,
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genre: String? = null,
    val status: Int? = null,
    val updateStrategy: AnimeUpdateStrategy? = null,
    val nativeIds: Map<String, Int> = emptyMap(),
) {
    fun merge(other: ExtensionMetadata): ExtensionMetadata = ExtensionMetadata(
        name = name ?: other.name,
        lang = lang ?: other.lang,
        baseUrl = baseUrl ?: other.baseUrl,
        isNsfw = isNsfw || other.isNsfw,
        supportsLatest = supportsLatest && other.supportsLatest,
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

    fun overrideWith(other: ExtensionMetadata): ExtensionMetadata = ExtensionMetadata(
        name = other.name ?: name,
        lang = other.lang ?: lang,
        baseUrl = other.baseUrl ?: baseUrl,
        isNsfw = isNsfw || other.isNsfw,
        supportsLatest = supportsLatest && other.supportsLatest,
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

    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@ExtensionMetadata.title ?: ""
        author = this@ExtensionMetadata.author ?: ""
        artist = this@ExtensionMetadata.artist ?: ""
        genre = this@ExtensionMetadata.genre ?: ""
        description = this@ExtensionMetadata.description ?: ""
        thumbnail_url = this@ExtensionMetadata.thumbnailUrl ?: ""
        status = this@ExtensionMetadata.status ?: SAnime.UNKNOWN
    }

    fun toSAnimeFallback(fallback: SAnime): SAnime = SAnime.create().apply {
        title = this@ExtensionMetadata.title ?: fallback.title
        author = this@ExtensionMetadata.author ?: fallback.author
        artist = this@ExtensionMetadata.artist ?: fallback.artist
        genre = this@ExtensionMetadata.genre ?: fallback.genre
        description = this@ExtensionMetadata.description ?: fallback.description
        thumbnail_url = this@ExtensionMetadata.thumbnailUrl ?: fallback.thumbnail_url
        status = this@ExtensionMetadata.status ?: fallback.status
    }

    fun applyToAnime(anime: SAnime): SAnime = anime.apply {
        title = this@ExtensionMetadata.title ?: anime.title
        author = this@ExtensionMetadata.author ?: anime.author
        artist = this@ExtensionMetadata.artist ?: anime.artist
        genre = this@ExtensionMetadata.genre ?: anime.genre
        description = this@ExtensionMetadata.description ?: anime.description
        thumbnail_url = this@ExtensionMetadata.thumbnailUrl ?: anime.thumbnail_url
        status = this@ExtensionMetadata.status ?: anime.status
    }

    fun isNotEmpty(): Boolean = title != null || description != null || thumbnailUrl != null ||
        author != null || artist != null || genre != null || status != null ||
        nativeIds.isNotEmpty()

    fun hasIdentity(): Boolean = name != null && lang != null && baseUrl != null

    fun hasContentMetadata(): Boolean = title != null || description != null || thumbnailUrl != null

    fun getMalId(): Int? = nativeIds["mal"]

    fun getKitsuId(): Int? = nativeIds["kitsu"]

    fun getAnidbId(): Int? = nativeIds["anidb"]

    companion object {
        val EMPTY = ExtensionMetadata()

        fun identity(
            name: String,
            lang: String,
            baseUrl: String,
            supportsLatest: Boolean = true,
        ) = ExtensionMetadata(
            name = name,
            lang = lang,
            baseUrl = baseUrl,
            supportsLatest = supportsLatest,
        )
    }
}
