package keiyoushi.templating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionMetadataTest {

    @Test
    fun `merge fills null fields from other`() {
        val base = ExtensionMetadata()
        val other = ExtensionMetadata(
            title = "Naruto",
            description = "A ninja anime",
            thumbnailUrl = "https://example.com/thumb.jpg",
        )

        val result = base.merge(other)

        assertEquals("Naruto", result.title)
        assertEquals("A ninja anime", result.description)
        assertEquals("https://example.com/thumb.jpg", result.thumbnailUrl)
    }

    @Test
    fun `merge preserves non-null fields in base`() {
        val base = ExtensionMetadata(title = "Original Title")
        val other = ExtensionMetadata(title = "Other Title")

        val result = base.merge(other)

        assertEquals("Original Title", result.title)
    }

    @Test
    fun `merge with both null fields stays null`() {
        val base = ExtensionMetadata()
        val other = ExtensionMetadata()

        val result = base.merge(other)

        assertNull(result.title)
        assertNull(result.description)
        assertNull(result.thumbnailUrl)
    }

    @Test
    fun `merge handles isNsfw correctly`() {
        val base = ExtensionMetadata(isNsfw = false)
        val other = ExtensionMetadata(isNsfw = true)

        val result = base.merge(other)

        assertTrue(result.isNsfw)
    }

    @Test
    fun `merge preserves isNsfw when base is true`() {
        val base = ExtensionMetadata(isNsfw = true)
        val other = ExtensionMetadata(isNsfw = false)

        val result = base.merge(other)

        assertTrue(result.isNsfw)
    }

    @Test
    fun `merge handles supportsLatest correctly`() {
        val base = ExtensionMetadata(supportsLatest = true)
        val other = ExtensionMetadata(supportsLatest = false)

        val result = base.merge(other)

        assertFalse(result.supportsLatest)
    }

    @Test
    fun `merge preserves supportsLatest when base is false`() {
        val base = ExtensionMetadata(supportsLatest = false)
        val other = ExtensionMetadata(supportsLatest = true)

        val result = base.merge(other)

        assertFalse(result.supportsLatest)
    }

    @Test
    fun `merge chains multiple providers`() {
        val seed = ExtensionMetadata(title = "Seed Title")
        val provider1 = ExtensionMetadata(description = "Description 1")
        val provider2 = ExtensionMetadata(thumbnailUrl = "https://example.com/thumb.jpg")

        val result = seed.merge(provider1).merge(provider2)

        assertEquals("Seed Title", result.title)
        assertEquals("Description 1", result.description)
        assertEquals("https://example.com/thumb.jpg", result.thumbnailUrl)
    }

    @Test
    fun `merge with empty other returns base unchanged`() {
        val base = ExtensionMetadata(
            title = "Title",
            description = "Description",
            thumbnailUrl = "https://example.com/thumb.jpg",
        )

        val result = base.merge(ExtensionMetadata())

        assertEquals(base, result)
    }

    @Test
    fun `data class equality works`() {
        val meta1 = ExtensionMetadata(title = "Test", lang = "en")
        val meta2 = ExtensionMetadata(title = "Test", lang = "en")

        assertEquals(meta1, meta2)
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = ExtensionMetadata(
            title = "Title",
            description = "Description",
            lang = "en",
        )

        val copied = original.copy(title = "New Title")

        assertEquals("New Title", copied.title)
        assertEquals("Description", copied.description)
        assertEquals("en", copied.lang)
    }
}
