package keiyoushi.templating

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataProviderTest {

    @Test
    fun `resolve with no delegate and no providers returns empty metadata`() {
        val provider = MetadataProvider(emptyList())
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { provider.resolve(context) }

        assertEquals(ExtensionMetadata(), result)
    }

    @Test
    fun `resolve runs delegate first and its fields win`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            ExtensionMetadata(title = "Delegate Title")
        }
        val provider = MetadataProvider(emptyList())
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { provider.resolve(context, delegate) }

        assertEquals("Delegate Title", result.title)
    }

    @Test
    fun `resolve runs providers in priority order`() {
        val provider1 = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(title = "Provider 20"),
        )
        val provider2 = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "Provider 10"),
        )
        val orchestrator = MetadataProvider(listOf(provider1, provider2))
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context) }

        // Provider 10 runs first, fills title
        // Provider 20 runs second, can't override (title already set)
        assertEquals("Provider 10", result.title)
    }

    @Test
    fun `resolve fills only null gaps`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            ExtensionMetadata(title = "Delegate Title")
        }
        val provider = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(
                title = "Provider Title",
                description = "Provider Description",
            ),
        )
        val orchestrator = MetadataProvider(listOf(provider))
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context, delegate) }

        assertEquals("Delegate Title", result.title) // Delegate wins
        assertEquals("Provider Description", result.description) // Provider fills gap
    }

    @Test
    fun `resolve with multiple providers fills different fields`() {
        val provider1 = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "Title"),
        )
        val provider2 = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(description = "Description"),
        )
        val provider3 = TestProvider(
            priority = 30,
            metadata = ExtensionMetadata(thumbnailUrl = "https://example.com/thumb.jpg"),
        )
        val orchestrator = MetadataProvider(listOf(provider1, provider2, provider3))
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context) }

        assertEquals("Title", result.title)
        assertEquals("Description", result.description)
        assertEquals("https://example.com/thumb.jpg", result.thumbnailUrl)
    }

    @Test
    fun `resolve with no anilistId skips native ID resolution`() {
        val provider = MetadataProvider(emptyList())
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = null,
        )

        val result = runBlocking { provider.resolve(context) }

        assertEquals(ExtensionMetadata(), result)
    }

    @Test
    fun `OVERRIDE_ALL lets later provider override earlier fields`() {
        val provider1 = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "First", description = "First Desc"),
        )
        val provider2 = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(title = "Second"),
        )
        val orchestrator = MetadataProvider(
            listOf(provider1, provider2),
            MergeStrategy.OVERRIDE_ALL,
        )
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context) }

        assertEquals("Second", result.title) // provider2 overrides
        assertEquals("First Desc", result.description) // provider2 doesn't touch it
    }

    @Test
    fun `OVERRIDE_ALL delegate fields can be overridden by providers`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            ExtensionMetadata(title = "Delegate Title", description = "Delegate Desc")
        }
        val provider = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "Provider Title"),
        )
        val orchestrator = MetadataProvider(
            listOf(provider),
            MergeStrategy.OVERRIDE_ALL,
        )
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context, delegate) }

        assertEquals("Provider Title", result.title) // provider overrides delegate
        assertEquals("Delegate Desc", result.description) // provider doesn't touch it
    }

    @Test
    fun `OVERRIDE_NON_DELEGATE locks delegate fields`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            ExtensionMetadata(title = "Locked Title", description = "Locked Desc")
        }
        val provider = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "Should Not Override", genre = "Action"),
        )
        val orchestrator = MetadataProvider(
            listOf(provider),
            MergeStrategy.OVERRIDE_NON_DELEGATE,
        )
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context, delegate) }

        assertEquals("Locked Title", result.title) // delegate locked
        assertEquals("Locked Desc", result.description) // delegate locked
        assertEquals("Action", result.genre) // provider fills gap
    }

    @Test
    fun `OVERRIDE_NON_DELEGATE providers can override each other`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            ExtensionMetadata(title = "Locked Title")
        }
        val provider1 = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(description = "First Desc", genre = "Action"),
        )
        val provider2 = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(description = "Second Desc"),
        )
        val orchestrator = MetadataProvider(
            listOf(provider1, provider2),
            MergeStrategy.OVERRIDE_NON_DELEGATE,
        )
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context, delegate) }

        assertEquals("Locked Title", result.title) // delegate locked
        assertEquals("Second Desc", result.description) // provider2 overrides provider1
        assertEquals("Action", result.genre) // provider1 fills gap, provider2 doesn't touch
    }

    @Test
    fun `OVERRIDE_NON_DELEGATE with no delegate allows full override`() {
        val provider1 = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "First"),
        )
        val provider2 = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(title = "Second"),
        )
        val orchestrator = MetadataProvider(
            listOf(provider1, provider2),
            MergeStrategy.OVERRIDE_NON_DELEGATE,
        )
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context) }

        assertEquals("Second", result.title) // no delegate, providers override each other
    }

    // Test helper
    private class TestProvider(
        override val priority: Int,
        private val metadata: ExtensionMetadata,
    ) : MetadataSubProvider {
        override val name: String = "TestProvider_$priority"

        override suspend fun provide(context: MetaproviderContext): ExtensionMetadata = metadata
    }
}
