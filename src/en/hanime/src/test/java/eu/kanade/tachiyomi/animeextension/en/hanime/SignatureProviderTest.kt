package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureProviderTest {

    // ── Signature data class ─────────────────────────────────────────────

    @Test
    fun signatureConstructionSetsFields() {
        val sig = Signature("abc", "1234567890", 1000L)
        assertEquals("abc", sig.signature, "signature field must match constructor arg")
        assertEquals("1234567890", sig.time, "time field must match constructor arg")
        assertEquals(1000L, sig.createdAt, "createdAt field must match constructor arg")
    }

    @Test
    fun signatureDefaultCreatedAtIsCurrentTime() {
        val before = System.currentTimeMillis()
        val sig = Signature("abc", "1234567890")
        val after = System.currentTimeMillis()

        assertTrue(
            sig.createdAt in before..after,
            "Default createdAt must be within the time window of construction",
        )
    }

    @Test
    fun isExpiredWithFreshSignatureReturnsFalse() {
        val sig = Signature("abc", "1234567890", System.currentTimeMillis())
        assertFalse(sig.isExpired(), "Fresh signature must not be expired with default TTL")
    }

    @Test
    fun isExpiredWithOldSignatureReturnsTrue() {
        // Signature created at epoch (1970) — definitely expired
        val sig = Signature("abc", "1234567890", 0L)
        assertTrue(sig.isExpired(), "Signature from epoch must be expired")
    }

    @Test
    fun isExpiredWithCustomTTL() {
        val now = System.currentTimeMillis()

        // Signature 2 minutes old, TTL = 1 minute → expired
        val twoMinutesAgo = now - (2 * 60 * 1000L)
        val expiredSig = Signature("abc", "1234567890", twoMinutesAgo)
        assertTrue(expiredSig.isExpired(ttlMs = 1 * 60 * 1000L), "2-min-old signature must be expired with 1-min TTL")

        // Signature 2 minutes old, TTL = 5 minutes → not expired
        val freshSig = Signature("abc", "1234567890", twoMinutesAgo)
        assertFalse(freshSig.isExpired(ttlMs = 5 * 60 * 1000L), "2-min-old signature must not be expired with 5-min TTL")
    }

    @Test
    fun signatureTTLConstant() {
        assertEquals(4 * 60 * 1000L, Signature.SIGNATURE_TTL_MS, "SIGNATURE_TTL_MS must be 240000L")
    }

    // ── SignatureHeaders ─────────────────────────────────────────────────

    @Test
    fun buildReturnsCorrectHeaders() {
        val sig = Signature("test_sig_value", "1609459200", 1000L)
        val headers = SignatureHeaders.build(sig)

        assertEquals("test_sig_value", headers["x-signature"], "x-signature must match signature field")
        assertEquals("1609459200", headers["x-time"], "x-time must match time field")
        assertEquals("web2", headers["x-signature-version"], "x-signature-version must be 'web2'")
        assertEquals("", headers["x-session-token"], "x-session-token must be empty")
        assertEquals("", headers["x-user-license"], "x-user-license must be empty")
        assertEquals("", headers["x-csrf-token"], "x-csrf-token must be empty")
        assertEquals("", headers["x-license"], "x-license must be empty")
        assertEquals("application/json", headers["content-type"], "content-type must be application/json")
        assertEquals("application/json", headers["accept"], "accept must be application/json")
    }

    @Test
    fun buildReturnsExactly9Headers() {
        val sig = Signature("abc", "123", 1000L)
        val headers = SignatureHeaders.build(sig)
        assertEquals(9, headers.size, "Header map must contain exactly 9 entries")
    }

    // ── SignatureCache ───────────────────────────────────────────────────

    /** Test double for SignatureProvider that tracks call count. */
    private class TestSignatureProvider : SignatureProvider {
        override val name = "Test"
        var callCount = 0
        private var sig = Signature("a" + "b".repeat(63), "1234567890") // 64-char sig

        override suspend fun getSignature(): Signature {
            callCount++
            return sig
        }
    }

    @Test
    fun cacheDelegatesOnFirstCall() = runTest {
        val delegate = TestSignatureProvider()
        val cache = SignatureCache(delegate)

        assertEquals(0, delegate.callCount, "Delegate must not be called before getSignature()")

        val sig = cache.getSignature()
        assertNotNull(sig, "Cache must return a signature")
        assertEquals(1, delegate.callCount, "Delegate must be called exactly once after first getSignature()")
    }

    @Test
    fun cacheReturnsCachedSignatureWithoutRedelegating() = runTest {
        val delegate = TestSignatureProvider()
        val cache = SignatureCache(delegate, ttlMs = 60_000L) // 1-minute TTL

        // First call delegates
        val sig1 = cache.getSignature()
        assertEquals(1, delegate.callCount)

        // Second call should use cache
        val sig2 = cache.getSignature()
        assertEquals(1, delegate.callCount, "Second call must NOT re-delegate — should use cache")
        assertEquals(sig1, sig2, "Cached signature must be the same object")
    }

    @Test
    fun cacheNameIncludesDelegateName() {
        val delegate = TestSignatureProvider()
        val cache = SignatureCache(delegate)

        assertEquals("Cached(Test)", cache.name, "Cache name must be 'Cached(DelegateName)'")
    }

    @Test
    fun invalidateForcesReDelegation() = runTest {
        val delegate = TestSignatureProvider()
        val cache = SignatureCache(delegate, ttlMs = 60_000L)

        // First call
        cache.getSignature()
        assertEquals(1, delegate.callCount)

        // Invalidate cache
        cache.invalidate()

        // Next call should re-delegate
        cache.getSignature()
        assertEquals(2, delegate.callCount, "After invalidate(), next call must re-delegate")
    }

    // ── SignatureException ───────────────────────────────────────────────

    @Test
    fun signatureExceptionWithMessage() {
        val ex = SignatureException("test error")
        assertEquals("test error", ex.message, "Exception message must match")
        assertNull(ex.cause, "Cause must be null when not provided")
    }

    @Test
    fun signatureExceptionWithMessageAndCause() {
        val rootCause = RuntimeException("root problem")
        val ex = SignatureException("test error", rootCause)
        assertEquals("test error", ex.message, "Exception message must match")
        assertNotNull(ex.cause, "Cause must not be null")
        assertTrue(ex.cause is RuntimeException, "Cause must be a RuntimeException")
        assertEquals("root problem", ex.cause!!.message, "Cause message must match")
    }

    @Test
    fun signatureExceptionIsAnException() {
        val ex: Exception = SignatureException("test")
        assertNotNull(ex, "SignatureException must be constructable as an Exception")
    }
}
