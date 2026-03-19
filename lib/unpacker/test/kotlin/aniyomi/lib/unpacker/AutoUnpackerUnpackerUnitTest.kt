package aniyomi.lib.unpacker

import aniyomi.lib.autoUnpacker
import kotlin.test.Test
import kotlin.test.assertTrue

class AutoUnpackerUnpackerUnitTest {

    @Test
    fun callUnpackedCorrectly() {
        val unpacked = autoUnpacker(PACKED_CALL)
        assertTrue(callUnpackCheck(unpacked), "Unpacker did not unpack call correctly")
    }

    private fun callUnpackCheck(unpacked: String?): Boolean = unpacked == UNPACKED_CALL || unpacked == UNPACKED_CALL_ALLOWED

    @Test
    fun functionUnpackedCorrectly() {
        val unpacked = autoUnpacker(PACKED_FUNCTION)
        assertTrue(functionUnpackCheck(unpacked), "Unpacker did not unpack function correctly")
    }

    private fun functionUnpackCheck(unpacked: String?): Boolean = unpacked == UNPACKED_FUNCTION || unpacked == UNPACKED_FUNCTION_ALLOWED

    companion object {
        private const val PACKED_CALL = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0(\\'1 2 3 4 5 6 7\\');',8,8,'alert|This|is|packed|and|a|plain|call'.split('|'),0,{}))"
        private const val UNPACKED_CALL = "alert('This is packed and a plain call');"
        private const val UNPACKED_CALL_ALLOWED = "alert(\\'This is packed and a plain call\\');"

        private const val PACKED_FUNCTION = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0 1(){2(\\'3 4 5 6 7 0\\')}',8,8,'function|funPackedTest|alert|This|is|packed|and|a'.split('|'),0,{}))"
        private const val UNPACKED_FUNCTION = "function funPackedTest() { alert('This is packed and a function'); }"
        private const val UNPACKED_FUNCTION_ALLOWED = "function funPackedTest(){alert(\\'This is packed and a function\\')}"
    }
}
