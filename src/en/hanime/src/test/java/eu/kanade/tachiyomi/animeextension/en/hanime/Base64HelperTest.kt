package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Base64HelperTest {

    @BeforeTest
    fun setUp() {
        Base64Provider.instance = JvmBase64
    }

    @Test
    fun jvmBase64DecodesStandardInput() {
        val input = "SGVsbG8gV29ybGQ=" // "Hello World"
        val decoded = decodeBase64(input)
        assertEquals("Hello World", decoded.toString(Charsets.UTF_8))
    }

    @Test
    fun jvmBase64EncodesBytesToString() {
        val input = "Hello World".toByteArray(Charsets.UTF_8)
        val encoded = encodeBase64(input)
        assertTrue(encoded.contains("SGVsbG8gV29ybGQ"))
    }

    @Test
    fun decodeBase64HandlesWasmMagicNumberBytes() {
        // WASM magic: 0x00 0x61 0x73 0x6D = "\0asm"
        val wasmMagicBase64 = "AGFzbQE" // 0x00 0x61 0x73 0x6D 0x01
        val decoded = decodeBase64(wasmMagicBase64)
        assertEquals(0x00, decoded[0].toInt() and 0xFF)
        assertEquals(0x61, decoded[1].toInt() and 0xFF)
        assertEquals(0x73, decoded[2].toInt() and 0xFF)
        assertEquals(0x6D, decoded[3].toInt() and 0xFF)
    }

    @Test
    fun jvmBase64RoundTripsEncodeThenDecode() {
        val original = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val encoded = encodeBase64(original)
        val decoded = decodeBase64(encoded)
        assertTrue(original.contentEquals(decoded))
    }
}
