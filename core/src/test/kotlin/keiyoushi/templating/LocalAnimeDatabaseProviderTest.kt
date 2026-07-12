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
}
