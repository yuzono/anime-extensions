package keiyoushi.templating

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAnimeDatabaseProviderTest {

    @Test
    fun `provider returns empty metadata when no anilistId`() {
        val provider = LocalAnimeDatabaseProvider(cache = null)
        val context = MetaproviderContext(baseUrl = "https://example.com")

        val result = runBlocking { provider.provide(context) }

        assertEquals(ExtensionMetadata(), result)
    }

    @Test
    fun `provider returns empty metadata when no cache`() {
        val provider = LocalAnimeDatabaseProvider(cache = null)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = 12345,
        )

        val result = runBlocking { provider.provide(context) }

        assertEquals(ExtensionMetadata(), result)
    }

    @Test
    fun `provider has correct default priority`() {
        val provider = LocalAnimeDatabaseProvider(cache = null)

        assertEquals(0, provider.priority)
    }

    @Test
    fun `provider has correct name`() {
        val provider = LocalAnimeDatabaseProvider(cache = null)

        assertEquals("LocalAnimeDatabaseProvider", provider.name)
    }

    @Test
    fun `provider returns metadata when anilistId is resolved from mal native ID`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForMal = mapOf(100 to 12345),
            metadataForAnilist = mapOf(12345 to ExtensionMetadata(title = "Test Title")),
        )
        val provider = LocalAnimeDatabaseProvider(cache = mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 100),
        )

        val result = runBlocking { provider.provide(context) }

        assertEquals("Test Title", result.title)
    }

    @Test
    fun `provider returns metadata when anilistId is resolved from kitsu native ID`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForKitsu = mapOf(200 to 12345),
            metadataForAnilist = mapOf(12345 to ExtensionMetadata(title = "Test Title")),
        )
        val provider = LocalAnimeDatabaseProvider(cache = mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("kitsu" to 200),
        )

        val result = runBlocking { provider.provide(context) }

        assertEquals("Test Title", result.title)
    }

    @Test
    fun `provider returns empty metadata when native ID has no match`() {
        val mockCache = MockAnimeDatabaseCache(
            anilistIdForMal = emptyMap(),
        )
        val provider = LocalAnimeDatabaseProvider(cache = mockCache)
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 999),
        )

        val result = runBlocking { provider.provide(context) }

        assertEquals(ExtensionMetadata(), result)
    }

    private class MockAnimeDatabaseCache(
        private val anilistIdForMal: Map<Int, Int> = emptyMap(),
        private val anilistIdForKitsu: Map<Int, Int> = emptyMap(),
        private val metadataForAnilist: Map<Int, ExtensionMetadata> = emptyMap(),
    ) : AnimeDatabaseCache(context = null, client = null) {
        override suspend fun resolveAnilistIdFromMal(malId: Int): Int? = anilistIdForMal[malId]
        override suspend fun resolveAnilistIdFromKitsu(kitsuId: Int): Int? = anilistIdForKitsu[kitsuId]
        override suspend fun getMetadata(anilistId: Int): ExtensionMetadata? = metadataForAnilist[anilistId]
    }
}
