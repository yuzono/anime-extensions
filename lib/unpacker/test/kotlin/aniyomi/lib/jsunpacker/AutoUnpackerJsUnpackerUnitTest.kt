package aniyomi.lib.jsunpacker

import aniyomi.lib.autoUnpacker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoUnpackerJsUnpackerUnitTest {

    // region unpack(String)

    @Test
    fun autoUnpack_decodesNumericKeys_withDictionaryEntries() {
        val script =
            "}('0 1 9',0,0,'zero|one|two|three|four|five|six|seven|eight|nine'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("zero one nine", result)
    }

    @Test
    fun autoUnpack_decodesAlphaKeys_usingBase62Mapping() {
        val dict62 = (0..61).joinToString("|") { "v$it" }
        val script = "}('0 a z A Z',0,0,'$dict62'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("v0 v10 v35 v36 v61", result)
    }

    @Test
    fun autoUnpack_decodesAlphaKeys_inBase62AlphabetOrder() {
        val dict62 = (0..61).joinToString("|") { "v$it" }
        val packed = "}('0 a z A Z',62,62,'$dict62'.split('|'),0,{}))"

        val result = autoUnpacker(packed)

        assertEquals("v0 v10 v35 v36 v61", result)
    }

    @Test
    fun autoUnpack_usesKeyWhenDictionaryEntryIsEmpty() {
        // When dictionary entry is empty, use the key itself
        val script = "}('0 1 2',0,0,'zero||two'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("zero 1 two", result)
    }

    @Test
    fun autoUnpack_usesKeyWhenIndexIsOutOfBounds() {
        // If the decoded index is >= dictionary size, keep the original key
        // "Z" decodes to 61 in base62; dictionary has only one entry.
        val script = "}('0 Z',0,0,'first'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("first Z", result)
    }

    @Test
    fun autoUnpack_preservesNonWordCharacters() {
        // Non-word characters (+-*/.) should be preserved
        val script = "}('0+1-2*3/4',0,0,'a|b|c|d|e'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("a+b-c*d/e", result)
    }

    @Test
    fun autoUnpack_returnsNull_whenDataSectionIsEmpty() {
        val script = "}('',0,0,''.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertNull(result)
    }

    @Test
    fun autoUnpack_decodesTokenWithinSurroundingText() {
        val script = "}('prefix:0:suffix',0,0,'value'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("prefix:value:suffix", result)
    }

    @Test
    fun autoUnpack_returnsOriginalString_whenNoDelimitersOrTokensFound() {
        val script = "}('nodelimiters',0,0,'value'.split('|'),0,{}))"

        val result = autoUnpacker(script)

        assertEquals("nodelimiters", result)
    }

    // endregion
}
