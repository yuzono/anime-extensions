package aniyomi.lib.unpacker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsUnpackerUnpackerTest {

    // region unpack(scriptBlock: String)

    @Test
    fun unpack_returnsEmptySequence_whenScriptIsNotPacked() {
        val unpackedScript = "var x = 5; console.log(x);"
        val results = Unpacker.unpack(unpackedScript)
        assertTrue(results.isEmpty())
    }

    @Test
    fun unpack_unpacksSimplePackedScript() {
        // payload "0 1" with radix 2, symtab ["word0","word1"] -> "word0 word1"
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'word0|word1'.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertEquals("word0 word1", results)
    }

    @Test
    fun unpack_unpacksWithBase10() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1 2',10,3,'a|b|c'.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertEquals("a b c", results)
    }

    @Test
    fun unpack_unpacksWithBase62() {
        val packedScript = """eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('0 1',2,2,'test|data'.split('|'),0,{}))"""
        val results = Unpacker.unpack(packedScript)
        assertEquals("test data", results)
    }

    @Test
    fun unpack_unpacksWithEmptySymtab() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('',1,1,''.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertEquals("", results)
    }

    @Test
    fun unpack_unpacksWithEmptySymtabWithPayload() {
        // Return unchanged payload if malformed symtab
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('test data',1,1,''.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertEquals("test data", results)
    }

    @Test
    fun unpack_returnsEmptySequence_whenSymtabCountMismatch() {
        // symtab has 1 element, count says 2 - malformed, should be ignored
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'only'.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertTrue(results.isEmpty())
    }

    @Test
    fun unpack_unpacksMultipleOccurrencesInSameScript() {
        val packedScript = """
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'first'.split('|')))
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'second'.split('|')))
        """.trimIndent()
        val results = Unpacker.unpack(packedScript)
        assertEquals("first second", results)
    }

    @Test
    fun unpack_preservesUnknownWords() {
        // Words not in symtab (e.g. invalid index) are preserved as-is
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'a|b'.split('|')))"
        val results = Unpacker.unpack(packedScript)
        assertEquals("a b", results)
    }

    // endregion

    // region unpack(scriptBlock: String)

    @Test
    fun unpackAndCombine_returnsNull_whenNoPackedScript() {
        val result = Unpacker.unpack("var x = 5;")
        assertNull(result)
    }

    @Test
    fun unpackAndCombine_returnsUnpackedResult_whenSingleOccurrence() {
        val packedScript = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'hello|world'.split('|')))"
        val result = Unpacker.unpack(packedScript)
        assertNotNull(result)
        assertEquals("hello world", result)
    }

    @Test
    fun unpackAndCombine_combinesMultipleResultsWithSpace() {
        val packedScript = """
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'first'.split('|')))
            eval(function(p,a,c,k,e,r){return p}('0',2,1,'second'.split('|')))
        """.trimIndent()
        val result = Unpacker.unpack(packedScript)
        assertNotNull(result)
        assertEquals("first second", result)
    }

    @Test
    fun testUnpackWithEmptyDictionaryValues() {
        // When dictionary entry is empty, use the key itself
        val packed = "eval(function(p,a,c,k,e,r){return p}('0 1 2',3,3,'zero||two'.split('|'),0,{}))"
        val result = Unpacker.unpack(packed)
        assertEquals("zero 1 two", result)
    }

    // endregion

    // region unpack(vararg scriptBlock: String)

    @Test
    fun unpackVararg_returnsEmptyList_whenNoPackedScripts() {
        val results = Unpacker.unpack("var a = 1;", "var b = 2;")
        assertTrue(results.isEmpty())
    }

    @Test
    fun unpackVararg_unpacksOnlyPackedScripts() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'unpacked'.split('|')))"
        val unpacked = "var x = 5;"
        val results = Unpacker.unpack(packed, unpacked)
        assertEquals("unpacked", results)
    }

    @Test
    fun unpackVararg_unpacksMultiplePackedScripts() {
        val packed1 = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'one'.split('|')))"
        val packed2 = "eval(function(p,a,c,k,e,r){return p}('0',2,1,'two'.split('|')))"
        val results = Unpacker.unpack(packed1, packed2)
        assertEquals("one two", results)
    }

    // endregion

    // region unpack(scriptBlocks: Collection<String>)

    @Test
    fun unpackCollection_returnsSameResultAsVararg() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0 1',2,2,'a|b'.split('|')))"
        val scripts = listOf(packed, "var x = 5;")
        val varargResults = Unpacker.unpack(packed, "var x = 5;")
        val collectionResults = Unpacker.unpack(scripts.joinToString("\n"))
        assertEquals(varargResults, collectionResults)
    }

    @Test
    fun unpackCollection_returnsEmptyList_whenEmptyCollection() {
        val results = Unpacker.unpack("")
        assertTrue(results.isEmpty())
    }

    // endregion
}
