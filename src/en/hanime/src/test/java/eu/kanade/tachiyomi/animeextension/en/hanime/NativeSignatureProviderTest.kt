package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSignatureProviderTest {

    /** Test subclass that pins the timestamp to a known value for deterministic verification. */
    private class TestNativeSignatureProvider(
        private val fixedTimestamp: Long,
    ) : NativeSignatureProvider() {
        override val timestampProvider: () -> Long = { fixedTimestamp }
    }

    private val provider = NativeSignatureProvider()

    // ── name property ──────────────────────────────────────────────────

    @Test
    fun testNameIsNative() {
        assertEquals("native", provider.name, "Provider name must be 'native'")
    }

    // ── Signature format ───────────────────────────────────────────────

    @Test
    fun testSignatureFormat() = runTest {
        val sig = provider.getSignature()
        assertTrue(
            sig.signature.matches(Regex("^[0-9a-f]{64}$")),
            "Signature must be 64 lowercase hex characters, got: ${sig.signature}",
        )
    }

    // ── Timestamp is current ───────────────────────────────────────────

    @Test
    fun testTimestampIsCurrent() = runTest {
        val sig = provider.getSignature()
        val expectedTimestamp = System.currentTimeMillis() / 1000L
        val actualTimestamp = sig.time.toLong()
        val tolerance = 5L // ±5 seconds
        assertTrue(
            Math.abs(actualTimestamp - expectedTimestamp) <= tolerance,
            "Timestamp $actualTimestamp must be within ±${tolerance}s of $expectedTimestamp",
        )
    }

    // ── Known signature vector ─────────────────────────────────────────

    @Test
    fun testKnownSignatureVector() = runTest {
        val fixedTimestamp = 1700000000L
        val expectedHash = "bc2348fd32ead4c3d79e87ad1abecd3759e573bf2f31024f7313a2fe054703ee"
        val testProvider = TestNativeSignatureProvider(fixedTimestamp)

        val sig = testProvider.getSignature()

        assertEquals(
            expectedHash,
            sig.signature,
            "Provider output must match pre-computed SHA-256 vector for timestamp $fixedTimestamp",
        )
        assertEquals(
            "1700000000",
            sig.time,
            "Signature time must equal the fixed timestamp as a string",
        )
    }

    // ── close() is no-op ───────────────────────────────────────────────

    @Test
    fun testCloseIsNoOp() {
        // close() must not throw
        provider.close()
    }

    // ── Multiple signatures can differ ─────────────────────────────────

    @Test
    fun testMultipleSignaturesDiffer() = runTest {
        val sig1 = provider.getSignature()
        // Wait for the timestamp to advance (1 second)
        Thread.sleep(1000L)
        val sig2 = provider.getSignature()
        // After waiting 1s, timestamps should differ (and therefore signatures)
        assertFalse(
            sig1.time == sig2.time && sig1.signature == sig2.signature,
            "Signatures 1 second apart should differ (time1=${sig1.time}, time2=${sig2.time})",
        )
    }

    // ── Signature matches direct SHA-256 computation ───────────────────

    @Test
    fun testSignatureWithKnownTimestamp() = runTest {
        val fixedTimestamp = 1700000000L
        val expectedHash = "bc2348fd32ead4c3d79e87ad1abecd3759e573bf2f31024f7313a2fe054703ee"
        val testProvider = TestNativeSignatureProvider(fixedTimestamp)

        val sig = testProvider.getSignature()

        assertEquals(
            expectedHash,
            sig.signature,
            "Signature from test provider must match pre-computed SHA-256 hex",
        )
        assertEquals(64, sig.signature.length, "Signature must be 64 characters")
    }
}
