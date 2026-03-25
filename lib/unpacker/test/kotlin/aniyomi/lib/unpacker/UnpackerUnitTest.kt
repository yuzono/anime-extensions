package aniyomi.lib.unpacker

import kotlin.test.Test
import kotlin.test.assertEquals

class UnpackerUnitTest {

    // region unpack(String)

    @Test
    fun unpack_returnsEmptyString_whenScriptIsNotPacked() {
        val script = "var x = 1;"

        val result = Unpacker.unpack(script)

        assertEquals("", result)
    }

    @Test
    fun unpack_decodesNumericKeys_withDictionaryEntries() {
        val script =
            "}('0 1 9',0,0,'zero|one|two|three|four|five|six|seven|eight|nine'.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("zero one nine", result)
    }

    @Test
    fun unpack_decodesAlphaKeys_usingBase62Mapping() {
        val dict62 = (0..61).joinToString("|") { "v$it" }
        val script = "}('0 a z A Z',0,0,'$dict62'.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("v0 v10 v35 v36 v61", result)
    }

    @Test
    fun unpack_decodesAlphaKeys_inBase62AlphabetOrder() {
        val dict62 = (0..61).joinToString("|") { "v$it" }
        val packed = "}('0 a z A Z',62,62,'$dict62'.split('|'),0,{}))"

        val result = Unpacker.unpack(packed)

        assertEquals("v0 v10 v35 v36 v61", result)
    }

    @Test
    fun unpack_usesKeyWhenDictionaryEntryIsEmpty() {
        // When dictionary entry is empty, use the key itself
        val script = "}('0 1 2',0,0,'zero||two'.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("zero 1 two", result)
    }

    @Test
    fun unpack_usesKeyWhenIndexIsOutOfBounds() {
        // If the decoded index is >= dictionary size, keep the original key
        // "Z" decodes to 61 in base62; dictionary has only one entry.
        val script = "}('0 Z',0,0,'first'.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("first Z", result)
    }

    @Test
    fun unpack_preservesNonWordCharacters() {
        // Non-word characters (+-*/.) should be preserved
        val script = "}('0+1-2*3/4',0,0,'a|b|c|d|e'.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("a+b-c*d/e", result)
    }

    @Test
    fun unpack_returnsEmptyString_whenDataSectionIsEmpty() {
        val script = "}('',0,0,''.split('|'),0,{}))"

        val result = Unpacker.unpack(script)

        assertEquals("", result)
    }

    @Test
    fun unpack_withLeftAndRight_extractsSubrangeBeforeDecoding() {
        // Extract only the data between the left and right delimiters
        val script = "}('prefix:0:suffix',0,0,'value'.split('|'),0,{}))"

        val result = Unpacker.unpack(script, "prefix:", ":suffix")

        assertEquals("value", result)
    }

    @Test
    fun unpack_withLeftAndRight_returnsEmptyString_whenDelimitersNotFound() {
        // If left/right delimiters are not present in the data, unpack returns empty string
        val script = "}('nodelimiters',0,0,'value'.split('|'),0,{}))"

        val result = Unpacker.unpack(script, "left:", ":right")

        assertEquals("", result)
    }

    // endregion

    // region unpack(SubstringExtractor)

    @Test
    fun unpack_withSubstringExtractor_decodesSinglePackedSegment() {
        val script = "prefix}('0 1',0,0,'word0|word1'.split('|'),0,{}))suffix"
        val extractor = SubstringExtractor(script)

        val result = Unpacker.unpack(extractor)

        assertEquals("word0 word1", result)
    }

    @Test
    fun unpack_withSubstringExtractor_canDecodeMultipleSegmentsSequentially() {
        val script =
            "start}('0',0,0,'a'.split('|'),0,{})) middle }('1',0,0,'a|b'.split('|'),0,{})) end"
        val extractor = SubstringExtractor(script)

        val first = Unpacker.unpack(extractor)
        val second = Unpacker.unpack(extractor)

        assertEquals("a", first)
        assertEquals("b", second)
    }

    @Test
    fun unpack_withSubstringExtractor_andDelimiters_behavesLikeStringOverload() {
        val script = "xx}('prefix:0:suffix',0,0,'val'.split('|'),0,{}))yy"
        val extractor = SubstringExtractor(script)

        val result = Unpacker.unpack(extractor, "prefix:", ":suffix")

        assertEquals("val", result)
    }

    // endregion
}
