package keiyoushi.lib.jsunpacker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Taken from https://github.com/DatL4g/JsUnpacker/blob/master/src/commonTest/kotlin/dev/datlag/jsunpacker/UnpackUnitTest.kt
 */
class JsUnpackerUnitTest {

    @Test
    fun callIsNotPacked() {
        assertFalse(JsUnpacker.detect(NOT_PACKED_CALL), "JsUnpacker detected not packed call as packed")
    }

    @Test
    fun functionIsNotPacked() {
        assertFalse(JsUnpacker.detect(NOT_PACKED_FUNCTION), "JsUnpacker detected not packed function as packed")
    }

    @Test
    fun callIsPacked() {
        assertTrue(JsUnpacker.detect(PACKED_CALL), "JsUnpacker did not detect packed call as packed")
    }

    @Test
    fun functionIsPacked() {
        assertTrue(JsUnpacker.detect(PACKED_FUNCTION), "JsUnpacker did not detect packed function as packed")
    }

    @Test
    fun callUnpackedCorrectly() {
        val unpacked = JsUnpacker.unpackAndCombine(PACKED_CALL)
        assertTrue(callUnpackCheck(unpacked), "JsUnpacker did not unpack call correctly")
    }

    private fun callUnpackCheck(unpacked: String?): Boolean = unpacked == UNPACKED_CALL || unpacked == UNPACKED_CALL_ALLOWED

    @Test
    fun functionUnpackedCorrectly() {
        val unpacked = JsUnpacker.unpackAndCombine(PACKED_FUNCTION)
        assertTrue(functionUnpackCheck(unpacked), "JsUnpacker did not unpack function correctly")
    }

    private fun functionUnpackCheck(unpacked: String?): Boolean = unpacked == UNPACKED_FUNCTION || unpacked == UNPACKED_FUNCTION_ALLOWED

    @Test
    fun unpackMultipleCorrectly() {
        val (unpackedCall, unpackedFunction) = JsUnpacker.unpack(PACKED_CALL, PACKED_FUNCTION)
        assertTrue(callUnpackCheck(unpackedCall) && functionUnpackCheck(unpackedFunction), "JsUnpacker did not unpack call and function together correctly")
    }

    companion object {
        private const val NOT_PACKED_CALL = "alert('This is not packed and a plain call');"
        private const val PACKED_CALL = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0(\\'1 2 3 4 5 6 7\\');',8,8,'alert|This|is|packed|and|a|plain|call'.split('|'),0,{}))"
        private const val UNPACKED_CALL = "alert('This is packed and a plain call');"
        private const val UNPACKED_CALL_ALLOWED = "alert(\\'This is packed and a plain call\\');"

        private const val NOT_PACKED_FUNCTION = "function funNotPackedTest() { alert('This is not packed and a function'); }"
        private const val PACKED_FUNCTION = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0 1(){2(\\'3 4 5 6 7 0\\')}',8,8,'function|funPackedTest|alert|This|is|packed|and|a'.split('|'),0,{}))"
        private const val UNPACKED_FUNCTION = "function funPackedTest() { alert('This is packed and a function'); }"
        private const val UNPACKED_FUNCTION_ALLOWED = "function funPackedTest(){alert(\\'This is packed and a function\\')}"
    }
}
