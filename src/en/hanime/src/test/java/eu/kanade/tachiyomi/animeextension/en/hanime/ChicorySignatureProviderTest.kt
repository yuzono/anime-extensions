package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.test.runTest
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
            runTest { provider.getSignature() }
        }
    }

    @Test
    fun initializeWithGarbageBinaryThrowsSignatureException() {
        val garbage = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val provider = ChicorySignatureProvider(garbage)
        assertFailsWith<SignatureException> {
            runTest { provider.getSignature() }
        }
    }

    @Test
    fun initializeWithTruncatedWasmMagicThrowsSignatureException() {
        // Just the WASM magic number (0x00 0x61 0x73 0x6D) with no version bytes
        val truncatedMagic = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val provider = ChicorySignatureProvider(truncatedMagic)
        assertFailsWith<SignatureException> {
            runTest { provider.getSignature() }
        }
    }

    @Test
    fun initializeWithWasmMagicAndWrongVersionThrowsSignatureException() {
        // WASM magic + wrong version (should be 0x01 0x00 0x00 0x00)
        val wrongVersion = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x02, 0x00, 0x00, 0x00)
        val provider = ChicorySignatureProvider(wrongVersion)
        assertFailsWith<SignatureException> {
            runTest { provider.getSignature() }
        }
    }

    // ── close() resets state ───────────────────────────────────────────

    @Test
    fun getSignatureAfterCloseThrowsSignatureException() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        provider.close()
        var exception: Throwable? = null
        runTest {
            exception = try {
                provider.getSignature()
                null
            } catch (e: Throwable) {
                e
            }
        }
        assertTrue(exception is SignatureException)
        assertTrue(exception!!.message!!.contains("closed", ignoreCase = true))
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
            runTest { provider.getSignature() }
        }
    }

    @Test
    fun getSignatureAutoInitializesWithGarbageBinary() {
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte())
        val provider = ChicorySignatureProvider(garbage)
        assertFailsWith<SignatureException> {
            runTest { provider.getSignature() }
        }
    }

    // ── Re-initialization after close ──────────────────────────────────

    @Test
    fun reinitializeAfterCloseThrowsSignatureException() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        provider.close()
        // Re-initializing after close should throw SignatureException about being closed
        var exception: Throwable? = null
        runTest {
            exception = try {
                provider.getSignature()
                null
            } catch (e: Throwable) {
                e
            }
        }
        assertTrue(exception is SignatureException)
        assertTrue(exception!!.message!!.contains("closed", ignoreCase = true))
    }

    // ── SignatureException properties ──────────────────────────────────

    @Test
    fun signatureExceptionFromInitializeContainsDescriptiveMessage() {
        val provider = ChicorySignatureProvider(byteArrayOf())
        var exception: Throwable? = null
        runTest {
            exception = try {
                provider.getSignature()
                null
            } catch (e: Throwable) {
                e
            }
        }
        assertTrue(exception is SignatureException)
        assertTrue(
            exception!!.message?.contains("Chicory") == true || exception!!.message?.contains("WASM") == true || exception!!.message?.contains("Failed") == true,
            "SignatureException message should reference the failure context, got: ${exception!!.message}",
        )
    }
}
