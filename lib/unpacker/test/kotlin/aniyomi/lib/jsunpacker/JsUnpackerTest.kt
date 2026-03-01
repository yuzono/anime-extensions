package aniyomi.lib.jsunpacker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsUnpackerTest {

    @Test
    fun testDetectPackedScript() {
        val packedScript = "eval(function(p,a,c,k,e,r){return'test'}('payload',62,1000,'symtab'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun testDetectUnpackedScript() {
        val unpackedScript = "var x = 5; console.log(x);"
        assertFalse(JsUnpacker.detect(unpackedScript))
    }

    @Test
    fun testDetectMultipleScripts() {
        val packedScript = "eval(function(p,a,c,k,e,r){return'test'}('payload',62,1000,'symtab'.split('|')))"
        val unpackedScript = "var x = 5;"
        val results = JsUnpacker.detect(packedScript, unpackedScript)
        assertEquals(1, results.size)
        assertTrue(results.contains(packedScript))
    }

    @Test
    fun testDetectCollectionOfScripts() {
        val scripts = listOf(
            "eval(function(p,a,c,k,e,r){return'test'}('payload',62,1000,'symtab'.split('|')))",
            "var x = 5;",
        )
        val results = JsUnpacker.detect(scripts)
        assertEquals(1, results.size)
    }

    @Test
    fun testUnpackEmptyWhenNoPackedScript() {
        val unpackedScript = "var x = 5; console.log(x);"
        val results = JsUnpacker.unpack(unpackedScript)
        assertTrue(results.toList().isEmpty())
    }

    @Test
    fun testUnpackAndCombineReturnsNullWhenNoPackedScript() {
        val unpackedScript = "var x = 5;"
        val result = JsUnpacker.unpackAndCombine(unpackedScript)
        assertNull(result)
    }

    @Test
    fun testUnpackMultipleScripts() {
        val packedScript = "eval(function(p,a,c,k,e,r){return'test'}('0 1',2,2,'word0|word1'.split('|')))"
        val unpackedScript = "var x = 5;"
        val results = JsUnpacker.unpack(packedScript, unpackedScript)
        // Only packed scripts should be unpacked
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun testUnpackCollectionOfScripts() {
        val scripts = listOf(
            "eval(function(p,a,c,k,e,r){return'test'}('0 1',2,2,'word0|word1'.split('|')))",
            "var x = 5;",
        )
        val results = JsUnpacker.unpack(scripts)
        // Results should contain unpacked code
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun testDetectWithVariantD() {
        // Test detection with 'd' parameter variant
        val packedScript = "eval(function(p,a,c,k,e,d){return'test'}('payload',62,1000,'symtab'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun testDetectCaseInsensitive() {
        // Test case insensitive detection
        val packedScript = "EVAL(FUNCTION(p,a,c,k,e,r){return'test'}('payload',62,1000,'symtab'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun testUnpackSimpleExample() {
        // Test unpacking with a simple example
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('test data',1,1,''.split('|')))"
        val results = JsUnpacker.unpack(packedScript)
        val unpacked = results.toList()
        assertTrue(unpacked.isNotEmpty())
    }

    @Test
    fun testUnpackAndCombineMultipleResults() {
        // Test combining multiple unpacked results
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('first',1,1,''.split('|'))) eval(function(p,a,c,k,e,r){return p}('second',1,1,''.split('|')))"
        val result = JsUnpacker.unpackAndCombine(packedScript)
        // Should combine results with space
        assertTrue(result != null && result.contains("first"))
    }
}
