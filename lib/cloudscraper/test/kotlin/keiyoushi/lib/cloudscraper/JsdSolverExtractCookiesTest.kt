package keiyoushi.lib.cloudscraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [JsdSolver.extractCookies].
 *
 * Validates extraction of name=value pairs from Set-Cookie headers
 * that come back from the Cloudflare challenge POST.
 */
class JsdSolverExtractCookiesTest {

    @Test
    fun `extractCookies parses cf_clearance cookie`() {
        val headers = listOf(
            "cf_clearance=abc123def456; Domain=.example.com; Path=/; Secure; HttpOnly; SameSite=None",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals(1, cookies.size)
        assertEquals("abc123def456", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies parses __cf_bm cookie`() {
        val headers = listOf(
            "__cf_bm=xyz789; Domain=.example.com; Path=/; Expires=Wed, 08 Jul 2026 12:00:00 GMT; Secure; HttpOnly",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals(1, cookies.size)
        assertEquals("xyz789", cookies["__cf_bm"])
    }

    @Test
    fun `extractCookies parses multiple cookies from multiple Set-Cookie headers`() {
        val headers = listOf(
            "cf_clearance=abc123; Domain=.example.com; Path=/; Secure",
            "__cf_bm=xyz789; Domain=.example.com; Path=/; Secure",
            "another_cookie=val; Domain=.example.com; Path=/",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals(3, cookies.size)
        assertEquals("abc123", cookies["cf_clearance"])
        assertEquals("xyz789", cookies["__cf_bm"])
        assertEquals("val", cookies["another_cookie"])
    }

    @Test
    fun `extractCookies handles cookies with values containing special characters`() {
        val headers = listOf(
            "cf_clearance=abc.def-ghi_jkl; Domain=.example.com; Path=/",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals("abc.def-ghi_jkl", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies returns empty map for empty header list`() {
        val cookies = JsdSolver.extractCookies(emptyList())
        assertTrue(cookies.isEmpty())
    }

    @Test
    fun `extractCookies ignores malformed cookie header`() {
        val headers = listOf(
            "not-a-valid-cookie-header",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertTrue(cookies.isEmpty())
    }

    @Test
    fun `extractCookies handles cookie with no value`() {
        val headers = listOf(
            "cf_clearance=; Domain=.example.com; Path=/",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals("", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies overrides duplicate cookie names with last value`() {
        val headers = listOf(
            "cf_clearance=first; Domain=.example.com; Path=/",
            "cf_clearance=second; Domain=.example.com; Path=/",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals("second", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies handles CF-style multiple cookies joined by newline`() {
        // Some CF responses combine Set-Cookie headers with newlines
        val headers = listOf(
            "cf_clearance=abc; Domain=.example.com; Path=/; Secure",
            "__cf_bm=def; Domain=.example.com; Path=/; Secure; HttpOnly",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals(2, cookies.size)
        assertEquals("abc", cookies["cf_clearance"])
        assertEquals("def", cookies["__cf_bm"])
    }

    @Test
    fun `extractCookies respects the name=value part before first semicolon`() {
        val headers = listOf(
            "cf_clearance=abc; Path=/; Domain=.example.com; Secure; HttpOnly; SameSite=Lax; Expires=Wed, 08 Jul 2026 23:59:59 GMT",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals("abc", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies handles cookie with multiple equals signs in value`() {
        val headers = listOf(
            "cf_clearance=abc=def==; Domain=.example.com; Path=/",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals("abc=def==", cookies["cf_clearance"])
    }

    @Test
    fun `extractCookies handles real-world CF challenge redirect Set-Cookie`() {
        val headers = listOf(
            "__cf_bm=Hx0YhVmxVEP4xabYWzVJ7_2w6eOAoIbR4HafT0TIhso-1720444800-1.0.1.1-AbcDef; path=/; expires=Wed, 08-Jul-26 12:00:00 GMT; domain=.miruro.tv; httponly; samesite=none; secure",
        )

        val cookies = JsdSolver.extractCookies(headers)

        assertEquals(
            "Hx0YhVmxVEP4xabYWzVJ7_2w6eOAoIbR4HafT0TIhso-1720444800-1.0.1.1-AbcDef",
            cookies["__cf_bm"],
        )
    }
}
