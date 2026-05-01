package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertFalse(sig.isExpired(ttlMs = 90 * 1000L), "Fresh signature must not be expired with 90s TTL")
    }

    @Test
    fun isExpiredWithOldSignatureReturnsTrue() {
        // Signature created at epoch (1970) — definitely expired
        val sig = Signature("abc", "1234567890", 0L)
        assertTrue(sig.isExpired(ttlMs = 90 * 1000L), "Signature from epoch must be expired")
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
    }

    @Test
    fun buildReturnsExactly7Headers() {
        val sig = Signature("abc", "123", 1000L)
        val headers = SignatureHeaders.build(sig)
        assertEquals(7, headers.size, "Header map must contain exactly 7 entries")
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

    // ── Signature.validate() ─────────────────────────────────────────

    @Test
    fun `validate accepts well-formed signature`() {
        val sig = Signature(
            signature = "a3f2b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a4",
            time = (System.currentTimeMillis() / 1000L).toString(),
        )
        sig.validate()
    }

    @Test
    fun `validate rejects invalid hex characters`() {
        val sig = Signature(
            signature = "g3f2b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a4",
            time = (System.currentTimeMillis() / 1000L).toString(),
        )
        val ex = assertFailsWith<SignatureException> {
            sig.validate()
        }
        assertTrue(ex.message!!.contains("Invalid signature format"))
    }

    @Test
    fun `validate rejects wrong length signature`() {
        val sig = Signature(
            signature = "a3f2b4",
            time = (System.currentTimeMillis() / 1000L).toString(),
        )
        val ex = assertFailsWith<SignatureException> {
            sig.validate()
        }
        assertTrue(ex.message!!.contains("Invalid signature format"))
    }

    @Test
    fun `validate rejects non-numeric timestamp`() {
        val sig = Signature(
            signature = "a3f2b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a4",
            time = "not-a-number",
        )
        val ex = assertFailsWith<SignatureException> {
            sig.validate()
        }
        assertTrue(ex.message!!.contains("Invalid timestamp"))
    }

    @Test
    fun `validate rejects timestamp too far in the future`() {
        val sig = Signature(
            signature = "a3f2b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a4",
            time = ((System.currentTimeMillis() / 1000L) + 120L).toString(),
        )
        val ex = assertFailsWith<SignatureException> {
            sig.validate()
        }
        assertTrue(ex.message!!.contains("too far from current time"))
    }

    @Test
    fun `validate accepts timestamp slightly in the future within clock skew tolerance`() {
        val sig = Signature(
            signature = "a3f2b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a4",
            time = ((System.currentTimeMillis() / 1000L) + 30L).toString(),
        )
        sig.validate()
    }
}
