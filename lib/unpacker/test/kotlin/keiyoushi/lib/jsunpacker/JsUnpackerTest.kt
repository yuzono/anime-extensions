package keiyoushi.lib.jsunpacker

import kotlin.collections.single
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsUnpackerTest {

    // region detect(scriptBlock: String)

    @Test
    fun detect_returnsTrue_whenScriptIsPacked() {
        val packedScript = "eval(function(p,a,c,k,e,r){return'test'}('0 1',2,2,'a|b'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun detect_returnsFalse_whenScriptIsNotPacked() {
        val unpackedScript = "var x = 5; console.log(x);"
        assertFalse(JsUnpacker.detect(unpackedScript))
    }

    @Test
    fun detect_returnsTrue_whenScriptUsesVariantD() {
        val packedScript = "eval(function(p,a,c,k,e,d){return'test'}('0',2,1,'x'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun detect_returnsTrue_whenScriptUsesVariantRd() {
        val packedScript = "eval(function(p,a,c,k,e,rd){return'test'}('0',2,1,'x'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun detect_isCaseInsensitive() {
        val packedScript = "EVAL(FUNCTION(p,a,c,k,e,r){return'test'}('0',2,1,'x'.split('|')))"
        assertTrue(JsUnpacker.detect(packedScript))
    }

    @Test
    fun detect_returnsFalse_forEmptyString() {
        assertFalse(JsUnpacker.detect(""))
    }

    // endregion

    // region detect(vararg scriptBlock: String)

    @Test
    fun detectVararg_returnsOnlyPackedScripts() {
        val packed = "eval(function(p,a,c,k,e,r){return'test'}('0',2,1,'x'.split('|')))"
        val unpacked = "var x = 5;"
        val results = JsUnpacker.detect(packed, unpacked)
        assertEquals(1, results.size)
        assertEquals(packed, results.single())
    }

    @Test
    fun detectVararg_returnsEmptyList_whenNoPackedScripts() {
        val results = JsUnpacker.detect("var a = 1;", "var b = 2;")
        assertTrue(results.isEmpty())
    }

    @Test
    fun detectVararg_returnsAllPackedScripts() {
        val packed1 = "eval(function(p,a,c,k,e,r){return'test'}('0',2,1,'a'.split('|')))"
        val packed2 = "eval(function(p,a,c,k,e,d){return'test'}('0',2,1,'b'.split('|')))"
        val results = JsUnpacker.detect(packed1, packed2)
        assertEquals(2, results.size)
        assertTrue(results.contains(packed1))
        assertTrue(results.contains(packed2))
    }

    // endregion

    // region detect(scriptBlocks: Collection<String>)

    @Test
    fun detectCollection_returnsOnlyPackedScripts() {
        val packed = "eval(function(p,a,c,k,e,r){return'test'}('0',2,1,'x'.split('|')))"
        val scripts = listOf(packed, "var x = 5;")
        val results = JsUnpacker.detect(scripts)
        assertEquals(1, results.size)
        assertEquals(packed, results.single())
    }

    @Test
    fun detectCollection_returnsSameResultAsVararg() {
        val packed = "eval(function(p,a,c,k,e,r){return'test'}('0',2,1,'x'.split('|')))"
        val scripts = listOf(packed, "var x = 5;")
        val varargResults = JsUnpacker.detect(packed, "var x = 5;")
        val collectionResults = JsUnpacker.detect(scripts)
        assertEquals(varargResults, collectionResults)
    }

    // endregion

    // region unpack(scriptBlock: String)

    @Test
    fun unpack_returnsEmptySequence_whenScriptIsNotPacked() {
        val unpackedScript = "var x = 5; console.log(x);"
        val results = JsUnpacker.unpack(unpackedScript)
        assertTrue(results.toList().isEmpty())
    }

    @Test
    fun unpack_unpacksSimplePackedScript() {
        // payload "0 1" with radix 2, symtab ["word0","word1"] -> "word0 word1"
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'word0|word1'.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(1, results.size)
        assertEquals("word0 word1", results.single())
    }

    @Test
    fun unpack_unpacksWithBase10() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1 2',10,3,'a|b|c'.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(1, results.size)
        assertEquals("a b c", results.single())
    }

    @Test
    fun unpack_unpacksWithBase62() {
        val packedScript = """eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('0 1',2,2,'test|data'.split('|'),0,{}))"""
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(1, results.size)
        assertEquals("test data", results.single())
    }

    @Test
    fun unpack_unpacksWithEmptySymtab() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('',1,1,''.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(1, results.size)
        assertEquals("", results.single())
    }

    @Test
    fun unpack_unpacksWithEmptySymtabWithPayload() {
        // Return unchanged payload if malformed symtab
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('test data',1,1,''.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(1, results.size)
        assertEquals("test data", results.single())
    }

    @Test
    fun unpack_returnsEmptySequence_whenSymtabCountMismatch() {
        // symtab has 1 element, count says 2 - malformed, should be ignored
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'only'.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun unpack_unpacksMultipleOccurrencesInSameScript() {
        val packedScript = """
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'first'.split('|')))
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'second'.split('|')))
        """.trimIndent()
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals(2, results.size)
        assertEquals("first", results[0])
        assertEquals("second", results[1])
    }

    @Test
    fun unpack_preservesUnknownWords() {
        // Words not in symtab (e.g. invalid index) are preserved as-is
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'a|b'.split('|')))"
        val results = JsUnpacker.unpack(packedScript).toList()
        assertEquals("a b", results.single())
    }

    // endregion

    // region unpackAndCombine(scriptBlock: String)

    @Test
    fun unpackAndCombine_returnsNull_whenNoPackedScript() {
        val result = JsUnpacker.unpackAndCombine("var x = 5;")
        assertNull(result)
    }

    @Test
    fun unpackAndCombine_returnsUnpackedResult_whenSingleOccurrence() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'hello|world'.split('|')))"
        val result = JsUnpacker.unpackAndCombine(packedScript)
        assertNotNull(result)
        assertEquals("hello world", result)
    }

    @Test
    fun unpackAndCombine_combinesMultipleResultsWithSpace() {
        val packedScript = """
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'first'.split('|')))
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'second'.split('|')))
        """.trimIndent()
        val result = JsUnpacker.unpackAndCombine(packedScript)
        assertNotNull(result)
        assertEquals("first second", result)
    }

    @Test
    fun testUnpackWithEmptyDictionaryValues() {
        // When dictionary entry is empty, use the key itself
        val packed = "eval(function(p,a,c,k,e,r){return p}('0 1 2',3,3,'zero||two'.split('|'),0,{}))"
        val result = JsUnpacker.unpackAndCombine(packed)
        assertEquals("zero 1 two", result)
    }

    // endregion

    // region unpack(vararg scriptBlock: String)

    @Test
    fun unpackVararg_returnsEmptyList_whenNoPackedScripts() {
        val results = JsUnpacker.unpack("var a = 1;", "var b = 2;")
        assertTrue(results.isEmpty())
    }

    @Test
    fun unpackVararg_unpacksOnlyPackedScripts() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'unpacked'.split('|')))"
        val unpacked = "var x = 5;"
        val results = JsUnpacker.unpack(packed, unpacked)
        assertEquals(1, results.size)
        assertEquals("unpacked", results.single())
    }

    @Test
    fun unpackVararg_unpacksMultiplePackedScripts() {
        val packed1 = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'one'.split('|')))"
        val packed2 = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'two'.split('|')))"
        val results = JsUnpacker.unpack(packed1, packed2)
        assertEquals(2, results.size)
        assertEquals("one", results[0])
        assertEquals("two", results[1])
    }

    // endregion

    // region unpack(scriptBlocks: Collection<String>)

    @Test
    fun unpackCollection_returnsSameResultAsVararg() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'a|b'.split('|')))"
        val scripts = listOf(packed, "var x = 5;")
        val varargResults = JsUnpacker.unpack(packed, "var x = 5;")
        val collectionResults = JsUnpacker.unpack(scripts)
        assertEquals(varargResults, collectionResults)
    }

    @Test
    fun unpackCollection_returnsEmptyList_whenEmptyCollection() {
        val results = JsUnpacker.unpack(emptyList())
        assertTrue(results.isEmpty())
    }

    // endregion
}
