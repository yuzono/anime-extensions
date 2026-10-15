package keiyoushi.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsTest {

    @Test
    fun `chunkedPairs of an empty list is empty`() {
        assertEquals(emptyList<Pair<Int, Int>>(), emptyList<Int>().chunkedPairs())
    }

    @Test
    fun `chunkedPairs groups an even-length list into pairs`() {
        assertEquals(listOf(1 to 2, 3 to 4), listOf(1, 2, 3, 4).chunkedPairs())
    }

    @Test
    fun `chunkedPairs drops a trailing element without a partner`() {
        assertEquals(listOf(1 to 2), listOf(1, 2, 3).chunkedPairs())
    }

    @Test
    fun `chunkedPairs on a sequence drops a trailing element without a partner`() {
        assertEquals(listOf(1 to 2, 3 to 4), sequenceOf(1, 2, 3, 4, 5).chunkedPairs().toList())
    }
}
