package keiyoushi.templating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceEntryTest {

    @Test
    fun `Text entry has correct properties`() {
        val entry = PreferenceEntry.EditTextPreference(
            key = "api_key",
            title = "API Key",
            summary = "Enter your API key",
            default = "",
        )

        assertEquals("api_key", entry.key)
        assertEquals("API Key", entry.title)
        assertEquals("Enter your API key", entry.summary)
        assertEquals("", entry.default)
    }

    @Test
    fun `List entry has correct properties`() {
        val entry = PreferenceEntry.ListPreference(
            key = "quality",
            title = "Video Quality",
            summary = "%s",
            default = "720",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )

        assertEquals("quality", entry.key)
        assertEquals("Video Quality", entry.title)
        assertEquals("%s", entry.summary)
        assertEquals("720", entry.default)
        assertEquals(3, entry.entries.size)
        assertEquals(3, entry.entryValues.size)
    }

    @Test
    fun `Switch entry has correct properties`() {
        val entry = PreferenceEntry.SwitchPreferenceCompat(
            key = "mark_fillers",
            title = "Mark Fillers",
            summary = "Mark filler episodes",
            default = true,
        )

        assertEquals("mark_fillers", entry.key)
        assertEquals("Mark Fillers", entry.title)
        assertEquals("Mark filler episodes", entry.summary)
        assertTrue(entry.default)
    }

    @Test
    fun `Switch entry with false default`() {
        val entry = PreferenceEntry.SwitchPreferenceCompat(
            key = "enabled",
            title = "Enabled",
            summary = "Enable feature",
            default = false,
        )

        assertFalse(entry.default)
    }

    @Test
    fun `MultiSelect entry has correct properties`() {
        val entry = PreferenceEntry.MultiSelectListPreference(
            key = "genres",
            title = "Genres",
            summary = "%s",
            default = setOf("Action"),
            entries = listOf("Action", "Comedy", "Drama"),
            entryValues = listOf("action", "comedy", "drama"),
        )

        assertEquals("genres", entry.key)
        assertEquals("Genres", entry.title)
        assertEquals("%s", entry.summary)
        assertEquals(setOf("Action"), entry.default)
        assertEquals(3, entry.entries.size)
        assertEquals(3, entry.entryValues.size)
    }

    @Test
    fun `MultiSelect entry with empty default`() {
        val entry = PreferenceEntry.MultiSelectListPreference(
            key = "selected",
            title = "Selected",
            summary = "%s",
            default = emptySet(),
            entries = listOf("A", "B"),
            entryValues = listOf("a", "b"),
        )

        assertTrue(entry.default.isEmpty())
    }

    @Test
    fun `entries and entryValues have same size`() {
        val listEntry = PreferenceEntry.ListPreference(
            key = "test",
            title = "Test",
            summary = "%s",
            default = "a",
            entries = listOf("A", "B", "C"),
            entryValues = listOf("a", "b", "c"),
        )

        assertEquals(listEntry.entries.size, listEntry.entryValues.size)

        val multiEntry = PreferenceEntry.MultiSelectListPreference(
            key = "test",
            title = "Test",
            summary = "%s",
            default = emptySet(),
            entries = listOf("X", "Y"),
            entryValues = listOf("x", "y"),
        )

        assertEquals(multiEntry.entries.size, multiEntry.entryValues.size)
    }

    @Test
    fun `data class equality works for all variants`() {
        val text1 = PreferenceEntry.EditTextPreference(key = "k", title = "T", summary = "S", default = "d")
        val text2 = PreferenceEntry.EditTextPreference(key = "k", title = "T", summary = "S", default = "d")
        assertEquals(text1, text2)

        val list1 = PreferenceEntry.ListPreference(
            key = "k",
            title = "T",
            summary = "S",
            default = "d",
            entries = listOf("A"),
            entryValues = listOf("a"),
        )
        val list2 = PreferenceEntry.ListPreference(
            key = "k",
            title = "T",
            summary = "S",
            default = "d",
            entries = listOf("A"),
            entryValues = listOf("a"),
        )
        assertEquals(list1, list2)

        val switch1 = PreferenceEntry.SwitchPreferenceCompat(key = "k", title = "T", summary = "S", default = true)
        val switch2 = PreferenceEntry.SwitchPreferenceCompat(key = "k", title = "T", summary = "S", default = true)
        assertEquals(switch1, switch2)

        val multi1 = PreferenceEntry.MultiSelectListPreference(
            key = "k",
            title = "T",
            summary = "S",
            default = setOf("x"),
            entries = listOf("X"),
            entryValues = listOf("x"),
        )
        val multi2 = PreferenceEntry.MultiSelectListPreference(
            key = "k",
            title = "T",
            summary = "S",
            default = setOf("x"),
            entries = listOf("X"),
            entryValues = listOf("x"),
        )
        assertEquals(multi1, multi2)
    }
}
