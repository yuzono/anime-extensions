package keiyoushi.templating

/**
 * A single source of metadata that runs as part of the [MetadataProvider]
 * resolution chain.
 *
 * Lower [priority] values run first. A provider can only fill fields that
 * are still `null` after the delegate and higher-priority providers have
 * run (see [ExtensionMetadata.merge] semantics).
 */
interface MetadataSubProvider {
    /** Lower value = runs earlier in the chain. */
    val priority: Int

    /** Human-readable identifier for debugging. */
    val name: String

    /**
     * @return a partially-filled [ExtensionMetadata]. Only non-null fields
     *   will be merged into the accumulator.
     */
    suspend fun provide(context: MetaproviderContext): ExtensionMetadata
}
