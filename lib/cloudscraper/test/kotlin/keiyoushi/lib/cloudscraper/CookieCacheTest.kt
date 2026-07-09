package keiyoushi.lib.cloudscraper

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CookieCache].
 *
 * The cache stores cf_clearance and other CF cookies per-host with
 * expiry-aware retrieval and per-host locking for concurrent solve
 * coordination.
 */
class CookieCacheTest {

    private val url = "https://miruro.tv/api/secure/pipe?e=test".toHttpUrl()
    private val clearanceCookie = Cookie.parse(
        url,
        "cf_clearance=abc123; Domain=miruro.tv; Path=/; Secure; Max-Age=1800",
    )!!

    // ── Basic put/get ─────────────────────────────────────────────────

    @Test
    fun `put and get non-expired cookie`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val result = cache.get(url)
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("cf_clearance", result[0].name)
        assertEquals("abc123", result[0].value)
    }

    @Test
    fun `put and getMatching returns cookie`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val result = cache.getMatching(url)
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    @Test
    fun `get returns null when no cookie stored`() {
        val cache = CookieCache()
        assertNull(cache.get(url))
    }

    @Test
    fun `get returns null after cookie expiry`() {
        val cache = CookieCache()
        val expiredCookie = Cookie.parse(
            url,
            "cf_clearance=expired; Domain=miruro.tv; Path=/; Secure; Max-Age=0",
        )!!
        cache.put(url, expiredCookie)

        // The cookie should already be expired (Max-Age=0 means expires immediately)
        val result = cache.get(url)
        assertNull(result, "Expired cookies should not be returned")
    }

    // ── Multiple cookies ──────────────────────────────────────────────

    @Test
    fun `put stores multiple cookies for same host`() {
        val cache = CookieCache()
        val bmCookie = Cookie.parse(
            url,
            "__cf_bm=xyz789; Domain=miruro.tv; Path=/; Secure; Max-Age=600",
        )!!

        cache.put(url, listOf(clearanceCookie, bmCookie))

        val result = cache.get(url)
        assertNotNull(result)
        assertEquals(2, result.size)
    }

    // ── Merge ─────────────────────────────────────────────────────────

    @Test
    fun `merge adds new cookies to existing entries`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val bmCookie = Cookie.parse(
            url,
            "__cf_bm=xyz789; Domain=miruro.tv; Path=/; Secure; Max-Age=600",
        )!!

        cache.merge(url, listOf(bmCookie))

        val result = cache.get(url)
        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("abc123", result.find { it.name == "cf_clearance" }?.value)
        assertEquals("xyz789", result.find { it.name == "__cf_bm" }?.value)
    }

    @Test
    fun `merge replaces existing cookie with same name`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val updatedCookie = Cookie.parse(
            url,
            "cf_clearance=newvalue; Domain=miruro.tv; Path=/; Secure; Max-Age=1800",
        )!!

        cache.merge(url, listOf(updatedCookie))

        val result = cache.get(url)
        assertNotNull(result)
        assertEquals("newvalue", result.find { it.name == "cf_clearance" }?.value)
    }

    // ── Remove ────────────────────────────────────────────────────────

    @Test
    fun `remove clears cookies for host`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        cache.remove(url)
        assertNull(cache.get(url))
    }

    // ── Clear ─────────────────────────────────────────────────────────

    @Test
    fun `clear removes all cookies`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val url2 = "https://other-site.com/page".toHttpUrl()
        val otherCookie = Cookie.parse(
            url2,
            "cf_clearance=xyz; Domain=other-site.com; Path=/; Secure; Max-Age=1800",
        )!!
        cache.put(url2, otherCookie)

        cache.clear()

        assertNull(cache.get(url))
        assertNull(cache.get(url2))
    }

    // ── hasCfClearance ────────────────────────────────────────────────

    @Test
    fun `hasCfClearance returns true when cf_clearance exists`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        assertTrue(cache.hasCfClearance(url))
    }

    @Test
    fun `hasCfClearance returns false when only non-cf cookies exist`() {
        val cache = CookieCache()
        val bmCookie = Cookie.parse(
            url,
            "__cf_bm=xyz789; Domain=miruro.tv; Path=/; Secure; Max-Age=600",
        )!!
        cache.put(url, bmCookie)

        assertTrue(!cache.hasCfClearance(url))
    }

    // ── Per-host locking ──────────────────────────────────────────────

    @Test
    fun `lockForHost acquires lock successfully`() {
        val cache = CookieCache()
        assertTrue(cache.lockForHost("miruro.tv"))
    }

    @Test
    fun `lockForHost returns false when already locked`() {
        val cache = CookieCache()
        cache.lockForHost("miruro.tv")
        assertTrue(!cache.lockForHost("miruro.tv"))
    }

    @Test
    fun `unlockForHost releases lock`() {
        val cache = CookieCache()
        cache.lockForHost("miruro.tv")
        cache.unlockForHost("miruro.tv")
        assertTrue(cache.lockForHost("miruro.tv"))
    }

    @Test
    fun `isSolvingForHost returns true when locked`() {
        val cache = CookieCache()
        cache.lockForHost("miruro.tv")
        assertTrue(cache.isSolvingForHost("miruro.tv"))
    }

    @Test
    fun `isSolvingForHost returns false when unlocked`() {
        val cache = CookieCache()
        cache.lockForHost("miruro.tv")
        cache.unlockForHost("miruro.tv")
        assertTrue(!cache.isSolvingForHost("miruro.tv"))
    }

    // ── Host isolation ────────────────────────────────────────────────

    @Test
    fun `different hosts have independent cache entries`() {
        val cache = CookieCache()
        cache.put(url, clearanceCookie)

        val otherUrl = "https://anilist.co/graphql".toHttpUrl()
        assertNull(cache.get(otherUrl))
    }

    @Test
    fun `different hosts have independent locks`() {
        val cache = CookieCache()
        cache.lockForHost("miruro.tv")

        assertTrue(cache.lockForHost("anilist.co"))
        cache.unlockForHost("anilist.co")
        assertTrue(cache.lockForHost("anilist.co"))
    }
}
