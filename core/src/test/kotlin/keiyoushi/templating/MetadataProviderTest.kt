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

        assertEquals("Delegate Title", result.title)
        assertEquals("Provider Description", result.description)
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
    fun `resolve with mal native ID resolves anilistId and populates native IDs`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForMal = mapOf(100 to 12345),
            nativeIdsForAnilist = mapOf(12345 to mapOf("mal" to 100, "kitsu" to 200)),
        )
        val provider = MetadataProvider(emptyList())
        provider.setDatabaseCache(mockCache)

        var capturedContext: MetaproviderContext? = null
        val capturingProvider = ContextCapturingProvider(capturedContext = { capturedContext = it })

        val orchestrator = MetadataProvider(listOf(capturingProvider))
        orchestrator.setDatabaseCache(mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 100),
        )

        runBlocking { orchestrator.resolve(context) }

        val ctx = capturedContext!!
        assertEquals(12345, ctx.anilistId)
        assertEquals(100, ctx.nativeIds["mal"])
        assertEquals(200, ctx.nativeIds["kitsu"])
    }

    @Test
    fun `resolve with kitsu native ID resolves anilistId and populates native IDs`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForKitsu = mapOf(200 to 12345),
            nativeIdsForAnilist = mapOf(12345 to mapOf("mal" to 100, "kitsu" to 200)),
        )

        var capturedContext: MetaproviderContext? = null
        val capturingProvider = ContextCapturingProvider(capturedContext = { capturedContext = it })

        val orchestrator = MetadataProvider(listOf(capturingProvider))
        orchestrator.setDatabaseCache(mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("kitsu" to 200),
        )

        runBlocking { orchestrator.resolve(context) }

        val ctx = capturedContext!!
        assertEquals(12345, ctx.anilistId)
        assertEquals(100, ctx.nativeIds["mal"])
        assertEquals(200, ctx.nativeIds["kitsu"])
    }

    @Test
    fun `resolve with native ID populates all other native IDs from database`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForMal = mapOf(100 to 12345),
            nativeIdsForAnilist = mapOf(12345 to mapOf("mal" to 100, "kitsu" to 200, "anidb" to 300)),
        )

        var capturedContext: MetaproviderContext? = null
        val capturingProvider = ContextCapturingProvider(capturedContext = { capturedContext = it })

        val orchestrator = MetadataProvider(listOf(capturingProvider))
        orchestrator.setDatabaseCache(mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 100),
        )

        runBlocking { orchestrator.resolve(context) }

        val ctx = capturedContext!!
        assertEquals(12345, ctx.anilistId)
        assertEquals(100, ctx.nativeIds["mal"])
        assertEquals(200, ctx.nativeIds["kitsu"])
        assertEquals(300, ctx.nativeIds["anidb"])
    }

    @Test
    fun `resolve with native ID but no database match returns original context`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForMal = emptyMap(),
        )
        val provider = MetadataProvider(emptyList())
        provider.setDatabaseCache(mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 999),
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

        assertEquals("Second", result.title)
        assertEquals("First Desc", result.description)
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

        assertEquals("Provider Title", result.title)
        assertEquals("Delegate Desc", result.description)
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

        assertEquals("Locked Title", result.title)
        assertEquals("Locked Desc", result.description)
        assertEquals("Action", result.genre)
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

        assertEquals("Locked Title", result.title)
        assertEquals("Second Desc", result.description)
        assertEquals("Action", result.genre)
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

        assertEquals("Second", result.title)
    }

    @Test
    fun `provider failure is caught and logged`() {
        val failingProvider = object : MetadataSubProvider {
            override val priority: Int = 10
            override val name: String = "FailingProvider"
            override suspend fun provide(context: MetaproviderContext): ExtensionMetadata = throw RuntimeException("Provider exploded")
        }
        val goodProvider = TestProvider(
            priority = 20,
            metadata = ExtensionMetadata(title = "Survivor"),
        )
        val orchestrator = MetadataProvider(listOf(failingProvider, goodProvider))
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context) }

        assertEquals("Survivor", result.title)
    }

    @Test
    fun `delegate failure is caught and returns empty metadata`() {
        val delegate: suspend MetaproviderContext.() -> ExtensionMetadata = {
            throw RuntimeException("Delegate exploded")
        }
        val provider = TestProvider(
            priority = 10,
            metadata = ExtensionMetadata(title = "From Provider"),
        )
        val orchestrator = MetadataProvider(listOf(provider))
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { orchestrator.resolve(context, delegate) }

        assertEquals("From Provider", result.title)
    }

    private class TestProvider(
        override val priority: Int,
        private val metadata: ExtensionMetadata,
    ) : MetadataSubProvider {
        override val name: String = "TestProvider_$priority"
        override suspend fun provide(context: MetaproviderContext): ExtensionMetadata = metadata
    }

    private class ContextCapturingProvider(
        private val capturedContext: (MetaproviderContext) -> Unit,
    ) : MetadataSubProvider {
        override val priority: Int = 999
        override val name: String = "ContextCapturingProvider"
        override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
            capturedContext(context)
            return ExtensionMetadata()
        }
    }

    private class MockAnimeDatabaseCache(
        private val anilistIdForMal: Map<Int, Int> = emptyMap(),
        private val anilistIdForKitsu: Map<Int, Int> = emptyMap(),
        private val nativeIdsForAnilist: Map<Int, Map<String, Int>> = emptyMap(),
    ) : AnimeDatabaseCache(context = null, client = null) {
        override suspend fun resolveAnilistIdFromMal(malId: Int): Int? = anilistIdForMal[malId]
        override suspend fun resolveAnilistIdFromKitsu(kitsuId: Int): Int? = anilistIdForKitsu[kitsuId]
        override suspend fun resolveNativeIds(anilistId: Int): Map<String, Int> = nativeIdsForAnilist[anilistId] ?: emptyMap()
    }
}
