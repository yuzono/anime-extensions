package keiyoushi.templating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaproviderContextTest {

    @Test
    fun `default context has null optional fields`() {
        val context = MetaproviderContext(baseUrl = "https://example.com")

        assertEquals("https://example.com", context.baseUrl)
        assertNull(context.anilistId)
        assertTrue(context.nativeIds.isEmpty())
        assertNull(context.animeUrl)
        assertNull(context.httpClient)
        assertNull(context.headers)
        assertNull(context.document)
        assertNull(context.preferences)
        assertNull(context.context)
        assertTrue(context.extra.isEmpty())
    }

    @Test
    fun `context with all fields`() {
        val context = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = 12345,
            nativeIds = mapOf("mal" to 20, "kitsu" to 30),
            animeUrl = "/anime/12345",
            extra = mapOf("key" to "value"),
        )

        assertEquals(12345, context.anilistId)
        assertEquals(20, context.nativeIds["mal"])
        assertEquals(30, context.nativeIds["kitsu"])
        assertEquals("/anime/12345", context.animeUrl)
        assertEquals("value", context.extra["key"])
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = 12345,
            nativeIds = mapOf("mal" to 20),
        )

        val copied = original.copy(anilistId = 67890)

        assertEquals("https://example.com", copied.baseUrl)
        assertEquals(67890, copied.anilistId)
        assertEquals(mapOf("mal" to 20), copied.nativeIds)
    }

    @Test
    fun `copy with nativeIds merges correctly`() {
        val original = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 20),
        )

        val copied = original.copy(
            nativeIds = original.nativeIds + ("kitsu" to 30),
        )

        assertEquals(20, copied.nativeIds["mal"])
        assertEquals(30, copied.nativeIds["kitsu"])
    }

    @Test
    fun `data class equality works`() {
        val ctx1 = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = 12345,
        )
        val ctx2 = MetaproviderContext(
            baseUrl = "https://example.com",
            anilistId = 12345,
        )

        assertEquals(ctx1, ctx2)
    }

    @Test
    fun `nativeIds are independent copies`() {
        val ctx1 = MetaproviderContext(
            baseUrl = "https://example.com",
            nativeIds = mapOf("mal" to 20),
        )
        val ctx2 = ctx1.copy(
            nativeIds = ctx1.nativeIds + ("kitsu" to 30),
        )

        assertEquals(1, ctx1.nativeIds.size)
        assertEquals(2, ctx2.nativeIds.size)
    }
}
