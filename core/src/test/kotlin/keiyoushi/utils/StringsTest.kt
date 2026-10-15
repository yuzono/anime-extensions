package keiyoushi.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StringsTest {

    @Test
    fun `unescapeUnicode decodes escapes in either hex case`() {
        assertEquals("Fran\u00e7ais \u00e7", """Fran\u00e7ais \u00E7""".unescapeUnicode())
    }

    @Test
    fun `unescapeUnicode decodes each surrogate half`() {
        assertEquals("\uD83D\uDE00", """\ud83d\ude00""".unescapeUnicode())
    }

    @Test
    fun `unescapeUnicode decodes a lone surrogate half`() {
        assertEquals("\uD83D", """\ud83d""".unescapeUnicode())
    }

    @Test
    fun `unescapeUnicode leaves other escapes untouched`() {
        val input = """https:\/\/a.com\/e\n\\"""
        assertEquals(input, input.unescapeUnicode())
    }

    @Test
    fun `unescapeUnicode leaves malformed escapes untouched`() {
        val input = """\u12 \u12zz \u"""
        assertEquals(input, input.unescapeUnicode())
    }

    @Test
    fun `unescapeUnicode decodes the escape after an escaped backslash`() {
        assertEquals("""\A""", """\\u0041""".unescapeUnicode())
    }
}
