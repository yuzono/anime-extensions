package keiyoushi.templating

/**
 * [MetadataSubProvider] that reads metadata from the locally cached
 * [anime-offline-database](https://github.com/manami-project/anime-offline-database).
 *
 * This provider should run **first** (lowest priority) since it reads
 * from a pre-downloaded cache with no network requests. Other providers
 * can enrich or override its data.
 *
 * The cache is managed by [AnimeDatabaseCache], which handles downloading,
 * expiry (7 days), and index building. This provider simply queries it.
 *
 * @param priority Lower runs earlier. Defaults to 0 (first provider).
 * @param cache The database cache instance. Must be set before calling
 *   [provide]. Typically set via [MetadataProvider.setDatabaseCache].
 */
class LocalAnimeDatabaseProvider(
    override val priority: Int = 0,
    override val name: String = "LocalAnimeDatabaseProvider",
    private val cache: AnimeDatabaseCache? = null,
) : MetadataSubProvider {

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val anilistId = context.anilistId ?: return ExtensionMetadata()
        val dbCache = cache ?: return ExtensionMetadata()

        return dbCache.getMetadata(anilistId) ?: ExtensionMetadata()
    }
}
