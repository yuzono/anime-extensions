package keiyoushi.lib.unpacker

import kotlin.test.Test
import kotlin.test.assertEquals

class SubstringExtractorTest {

    @Test
    fun testSubstringBefore() {
        val extractor = SubstringExtractor("Hello World Test")
        val result = extractor.substringBefore(" World")
        assertEquals("Hello", result)
    }

    @Test
    fun testSubstringBeforeNotFound() {
        val extractor = SubstringExtractor("Hello World")
        val result = extractor.substringBefore(" NotFound")
        assertEquals("", result)
    }

    @Test
    fun testSubstringBetween() {
        val extractor = SubstringExtractor("Start{Middle}End")
        val result = extractor.substringBetween("{", "}")
        assertEquals("Middle", result)
    }

    @Test
    fun testSubstringBetweenLeftNotFound() {
        val extractor = SubstringExtractor("Start{Middle}End")
        val result = extractor.substringBetween("[", "]")
        assertEquals("", result)
    }

    @Test
    fun testSubstringBetweenRightNotFound() {
        val extractor = SubstringExtractor("Start{Middle")
        val result = extractor.substringBetween("{", "}")
        assertEquals("", result)
    }

    @Test
    fun testSkipOver() {
        val extractor = SubstringExtractor("Skip this part and continue here")
        extractor.skipOver(" part")
        val result = extractor.substringBefore(" continue")
        assertEquals(" and", result)
    }

    @Test
    fun testSkipOverNotFound() {
        val extractor = SubstringExtractor("Hello World")
        extractor.skipOver("NotFound")
        val result = extractor.substringBefore(" World")
        assertEquals("Hello", result)
    }

    @Test
    fun testMultipleOperations() {
        val extractor = SubstringExtractor("prefix{data1}middle{data2}suffix")
        val first = extractor.substringBetween("{", "}")
        val second = extractor.substringBetween("{", "}")
        assertEquals("data1", first)
        assertEquals("data2", second)
    }

    @Test
    fun testEmptyString() {
        val extractor = SubstringExtractor("")
        val result = extractor.substringBefore("test")
        assertEquals("", result)
    }

    @Test
    fun testSubstringBetweenWithEmptyDelimiters() {
        val extractor = SubstringExtractor("{data1}{data2}")
        val result = extractor.substringBetween("{data1}", "{data2}")
        assertEquals("", result)
    }
}
