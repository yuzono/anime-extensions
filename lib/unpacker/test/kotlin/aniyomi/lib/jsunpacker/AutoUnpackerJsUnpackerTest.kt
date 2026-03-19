package aniyomi.lib.jsunpacker

import aniyomi.lib.autoUnpacker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoUnpackerJsUnpackerTest {

    @Test
    fun testUnpackSimplePackedScript() {
        // A simple packed script example
        val packed = "}('0 1',2,2,'word0|word1'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("word0 word1", result)
    }

    @Test
    fun testUnpackWithRadix62() {
        // Test radix 62 decoding (0-9, a-z, A-Z)
        val packed = "}('0 1 a b A B',62,6,'zero|one|ten|eleven|thirtysix|thirtyseven'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("zero one a b A B", result)
    }

    @Test
    fun testUnpackWithEmptyDictionaryValues() {
        // When dictionary entry is empty, use the key itself
        val packed = "}('0 1 2',3,3,'zero||two'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("zero 1 two", result)
    }

    @Test
    fun testUnpackWithSingleQuotes() {
        // Single quotes should be replaced with double quotes
        val packed = "}('test\\'quoted',1,1,'value'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("test\"quoted", result)
    }

    @Test
    fun testUnpackWithLeftRight() {
        val packed = "}('prefix:0:suffix',1,1,'data'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("prefix:data:suffix", result)
    }

    @Test
    fun testUnpackEmptyData() {
        // When data is empty, return empty string
        val packed = "}('',0,0,''.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertNull(result)
    }

    @Test
    fun testUnpackWithMixedRadix() {
        // Test with various radix values
        val packed = "}('a b c d e f g h i j',36,20,'val0|val1|val2|val3|val4|val5|val6|val7|val8|val9|val10|val11|val12|val13|val14|val15|val16|val17|val18|val19'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("val10 val11 val12 val13 val14 val15 val16 val17 val18 val19", result)
    }

    @Test
    fun testUnpackWithKeyOutOfBounds() {
        // When key index is >= dictionary size, use the key itself
        val packed = "}('0 99',2,2,'first|second'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("first 99", result)
    }

    @Test
    fun testUnpackWithSubstringExtractor() {
        val script = "}('prefix:0 1:suffix',2,2,'word0|word1'.split('|'),0,{}))"
        val result = autoUnpacker(script)
        assertEquals("prefix:word0 word1:suffix", result)
    }

    @Test
    fun testUnpackComplexScript() {
        // A more complex example with multiple words
        val packed = "}('0 1 2 3 0 1',10,4,'var|function|return|console'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("var function return console var function", result)
    }

    @Test
    fun testUnpackWithSpecialCharacters() {
        // Test with special characters in the data
        val packed = "}('0.1(2)',3,3,'obj|prop|func'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("obj.prop(func)", result)
    }

    @Test
    fun testUnpackWithLargeRadix() {
        // Test with radix 62 (full alphanumeric range)
        val packed = "}('z Z',62,36,'a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p|q|r|s|t|u|v|w|x|y|z|A|B|C|D|E|F|G|H|I|J'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("J Z", result) // Z in base62 = 35 + 26 = 61, but we only have 36 items (0-35), so index 61 is out of bounds
    }

    @Test
    fun testUnpackPreservesNonWordCharacters() {
        // Non-word characters should be preserved
        val packed = "}('0+1-2*3/4',5,5,'a|b|c|d|e'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("a+b-c*d/e", result)
    }

    @Test
    fun testUnpackWithLeftRightAndEmptyResult() {
        val packed = "}('nodelimiters',1,1,'value'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("nodelimiters", result)
    }

    @Test
    fun testUnpackRealWorldExample() {
        // A more realistic packed JavaScript example
        val packed = "}('4 0=5 1(\\'2\\');',6,6,'var|alert|Hello|World|const|message'.split('|'),0,{}))"
        val result = autoUnpacker(packed)
        assertEquals("const var=message alert(\"Hello\");", result)
    }
}
