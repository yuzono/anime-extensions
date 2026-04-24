package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChicorySignatureProviderTest {

    // ── name property ──────────────────────────────────────────────────

    @Test
    fun namePropertyIsChicoryInterpreter() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        assertEquals("ChicoryInterpreter", provider.name, "Provider name must be 'ChicoryInterpreter'")
    }

    // ── Initialize with invalid binaries ───────────────────────────────

    @Test
    fun initializeWithEmptyBinaryThrowsSignatureException() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    @Test
    fun initializeWithGarbageBinaryThrowsSignatureException() {
        val garbage = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val provider = ChicorySignatureProvider(garbage)
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    @Test
    fun initializeWithTruncatedWasmMagicThrowsSignatureException() {
        // Just the WASM magic number (0x00 0x61 0x73 0x6D) with no version bytes
        val truncatedMagic = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val provider = ChicorySignatureProvider(truncatedMagic)
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    @Test
    fun initializeWithWasmMagicAndWrongVersionThrowsSignatureException() {
        // WASM magic + wrong version (should be 0x01 0x00 0x00 0x00)
        val wrongVersion = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x02, 0x00, 0x00, 0x00)
        val provider = ChicorySignatureProvider(wrongVersion)
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    // ── close() resets state ───────────────────────────────────────────

    @Test
    fun closeResetsToUninitializedState() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        // close() should work even without initialization
        provider.close()

        // After close(), the provider should be in uninitialized state
        // We verify this indirectly — calling initialize() after close()
        // should not short-circuit (it would throw SignatureException
        // because the binary is empty, not return silently)
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    @Test
    fun closeIsIdempotent() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        // Calling close() multiple times must not throw
        provider.close()
        provider.close()
        provider.close()
    }

    // ── getSignature auto-initializes ──────────────────────────────────

    @Test
    fun getSignatureAutoInitializesWithInvalidBinary() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        // getSignature() should call initialize() internally, which will
        // fail because the binary is empty
        assertFailsWith<SignatureException> {
            kotlinx.coroutines.runBlocking {
                provider.getSignature()
            }
        }
    }

    @Test
    fun getSignatureAutoInitializesWithGarbageBinary() {
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte())
        val provider = ChicorySignatureProvider(garbage)
        assertFailsWith<SignatureException> {
            kotlinx.coroutines.runBlocking {
                provider.getSignature()
            }
        }
    }

    // ── Re-initialization after close ──────────────────────────────────

    @Test
    fun reinitializeAfterCloseThrowsOnInvalidBinary() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        provider.close()
        // Re-initializing with the same invalid binary should still fail
        assertFailsWith<SignatureException> {
            provider.initialize()
        }
    }

    // ── SignatureException properties ──────────────────────────────────

    @Test
    fun signatureExceptionFromInitializeContainsDescriptiveMessage() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        try {
            provider.initialize()
            throw AssertionError("Expected SignatureException to be thrown")
        } catch (e: SignatureException) {
            assertTrue(
                e.message?.contains("Chicory") == true || e.message?.contains("WASM") == true || e.message?.contains("Failed") == true,
                "SignatureException message should reference the failure context, got: ${e.message}",
            )
        }
    }
}
