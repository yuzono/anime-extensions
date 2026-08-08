package keiyoushi.templating

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-extension orchestrator that merges metadata from a delegate function
 * and an ordered list of [MetadataSubProvider] instances.
 *
 * **Merge behavior** is controlled by [mergeStrategy]:
 * - [MergeStrategy.FILL_NULLS] (default): Delegate seeds, providers fill gaps.
 * - [MergeStrategy.OVERRIDE_ALL]: Each provider can override previous fields.
 * - [MergeStrategy.OVERRIDE_NON_DELEGATE]: Delegate fields locked, providers override each other.
 *
 * **ID resolution:**
 * Before providers run, the orchestrator checks if [MetaproviderContext.anilistId]
 * is set. If so, it queries [AnimeDatabaseCache] to populate [MetaproviderContext.nativeIds]
 * with converted IDs (MAL, Kitsu, AniDB, etc.). Providers can then read
 * these IDs directly.
 *
 * This class is NOT a singleton — each [AnimeExtension] instance owns its
 * own [MetadataProvider] constructed with its sub-provider list.
 */
class MetadataProvider(
    private val subProviders: List<MetadataSubProvider>,
    private val mergeStrategy: MergeStrategy = MergeStrategy.FILL_NULLS,
) {
    private var databaseCache: AnimeDatabaseCache? = null
    private val metadataCache = ConcurrentHashMap<Int, CachedMetadata>()
    private val cacheTtlMs = CACHE_TTL_MS

    fun setDatabaseCache(cache: AnimeDatabaseCache) {
        databaseCache = cache
    }

    suspend fun resolve(
        context: MetaproviderContext,
        metadataDelegate: (suspend MetaproviderContext.() -> ExtensionMetadata)? = null,
    ): ExtensionMetadata {
        val cacheKey = context.anilistId ?: context.nativeIds.hashCode()
        metadataCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
                return cached.data
            }
        }

        val result = resolveInternal(context, metadataDelegate)
        metadataCache[cacheKey] = CachedMetadata(result, System.currentTimeMillis())
        return result
    }

    private suspend fun resolveInternal(
        context: MetaproviderContext,
        metadataDelegate: (suspend MetaproviderContext.() -> ExtensionMetadata)?,
    ): ExtensionMetadata {
        val enrichedContext = resolveNativeIds(context)
        val seed = runCatching {
            metadataDelegate?.invoke(enrichedContext) ?: ExtensionMetadata()
        }.getOrElse { e ->
            Log.e(TAG, "Metadata delegate failed", e)
            ExtensionMetadata()
        }

        val finalContext = if (seed.nativeIds.isNotEmpty()) {
            enrichedContext.copy(nativeIds = enrichedContext.nativeIds + seed.nativeIds)
        } else {
            enrichedContext
        }

        val sorted = subProviders.sortedBy { it.priority }
        return when (mergeStrategy) {
            MergeStrategy.FILL_NULLS -> {
                sorted.fold(seed) { acc, provider ->
                    val provided = runCatching {
                        provider.provide(finalContext)
                    }.getOrElse { e ->
                        Log.w(TAG, "Provider '${provider.name}' failed", e)
                        ExtensionMetadata()
                    }
                    acc.merge(provided)
                }
            }
            MergeStrategy.OVERRIDE_ALL -> {
                sorted.fold(seed) { acc, provider ->
                    val provided = runCatching {
                        provider.provide(finalContext)
                    }.getOrElse { e ->
                        Log.w(TAG, "Provider '${provider.name}' failed", e)
                        ExtensionMetadata()
                    }
                    acc.overrideWith(provided)
                }
            }
            MergeStrategy.OVERRIDE_NON_DELEGATE -> {
                sorted.fold(seed) { acc, provider ->
                    val provided = runCatching {
                        provider.provide(finalContext)
                    }.getOrElse { e ->
                        Log.w(TAG, "Provider '${provider.name}' failed", e)
                        ExtensionMetadata()
                    }
                    mergeNonDelegateFields(acc, provided, metadataDelegate?.let { seed })
                }
            }
        }
    }

    private fun mergeNonDelegateFields(
        current: ExtensionMetadata,
        other: ExtensionMetadata,
        delegateMeta: ExtensionMetadata?,
    ): ExtensionMetadata {
        if (delegateMeta == null) return current.overrideWith(other)

        return ExtensionMetadata(
            name = if (delegateMeta.name != null) current.name else (other.name ?: current.name),
            lang = if (delegateMeta.lang != null) current.lang else (other.lang ?: current.lang),
            baseUrl = if (delegateMeta.baseUrl != null) current.baseUrl else (other.baseUrl ?: current.baseUrl),
            isNsfw = current.isNsfw || other.isNsfw,
            supportsLatest = if (!other.supportsLatest) false else current.supportsLatest,
            title = if (delegateMeta.title != null) current.title else (other.title ?: current.title),
            description = if (delegateMeta.description != null) current.description else (other.description ?: current.description),
            thumbnailUrl = if (delegateMeta.thumbnailUrl != null) current.thumbnailUrl else (other.thumbnailUrl ?: current.thumbnailUrl),
            author = if (delegateMeta.author != null) current.author else (other.author ?: current.author),
            artist = if (delegateMeta.artist != null) current.artist else (other.artist ?: current.artist),
            genre = if (delegateMeta.genre != null) current.genre else (other.genre ?: current.genre),
            status = if (delegateMeta.status != null) current.status else (other.status ?: current.status),
            updateStrategy = if (delegateMeta.updateStrategy != null) current.updateStrategy else (other.updateStrategy ?: current.updateStrategy),
        )
    }

    private suspend fun resolveNativeIds(context: MetaproviderContext): MetaproviderContext {
        val cache = databaseCache ?: return context

        if (context.anilistId != null) {
            val nativeIds = cache.resolveNativeIds(context.anilistId)
            if (nativeIds.isEmpty()) return context
            return context.copy(nativeIds = context.nativeIds + nativeIds)
        }

        val resolvedAnilistId = resolveAnilistIdFromNativeIds(context, cache)
            ?: return context

        val nativeIds = cache.resolveNativeIds(resolvedAnilistId)
        if (nativeIds.isEmpty()) return context

        return context.copy(
            anilistId = resolvedAnilistId,
            nativeIds = context.nativeIds + nativeIds,
        )
    }

    private suspend fun resolveAnilistIdFromNativeIds(
        context: MetaproviderContext,
        cache: AnimeDatabaseCache,
    ): Int? {
        context.nativeIds["mal"]?.let { malId ->
            cache.resolveAnilistIdFromMal(malId)?.let { return it }
        }
        context.nativeIds["kitsu"]?.let { kitsuId ->
            cache.resolveAnilistIdFromKitsu(kitsuId)?.let { return it }
        }
        return null
    }

    fun clearCache() {
        metadataCache.clear()
    }

    private data class CachedMetadata(
        val data: ExtensionMetadata,
        val timestamp: Long,
    )

    companion object {
        private const val TAG = "MetadataProvider"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
