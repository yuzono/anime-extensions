package keiyoushi.lib.cloudscraper

import okhttp3.Cookie
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Thread-safe cache for Cloudflare cookies, keyed by host.
 *
 * Improvements over a simple map:
 * - **Respects cookie `expiresAt`** — uses the cookie's own expiry to filter stale entries.
 * - **Per-host locks** — prevents concurrent duplicate solve attempts for the same host.
 *   Callers must call [lockForHost]/[unlockForHost] around solve attempts.
 * - **Multiple cookies per host** — stores all cookies from the challenge response,
 *   not just `cf_clearance`. This is important because some CF configurations
 *   also set `__cf_bm` and other cookies that must be replayed.
 *
 * @param defaultTtlMillis default TTL in milliseconds when cookie has no explicit expiry;
 *   Cloudflare typically sets cf_clearance with ~30 min expiry, so 25 min is safe
 */
class CookieCache {

    private data class HostEntry(
        val cookies: List<Cookie>,
        val storedAt: Long,
    )

    private val store = ConcurrentHashMap<String, HostEntry>()
    private val hostLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Stores cookies for the given URL's host.
     * Replaces any previously stored cookies for this host.
     */
    fun put(url: HttpUrl, cookies: List<Cookie>) {
        val nonSession = cookies.filter { it.persistent }
        if (nonSession.isEmpty()) return
        store[url.host] = HostEntry(nonSession, System.currentTimeMillis())
    }

    /**
     * Stores a single cookie for the given URL's host.
     * Merges with existing cookies — replaces any cookie with the same name.
     */
    fun put(url: HttpUrl, cookie: Cookie) {
        put(url, listOf(cookie))
    }

    /**
     * Merges cookies into the existing entry for this host.
     * Cookies with matching names are replaced; others are kept.
     */
    fun merge(url: HttpUrl, newCookies: List<Cookie>) {
        hostLocks.getOrPut(url.host) { ReentrantLock() }.lock()
        try {
            val existing = store[url.host]?.cookies ?: emptyList()
            val merged = buildList {
                // Keep existing cookies not overridden by new ones
                addAll(
                    existing.filter { existingCookie ->
                        newCookies.none { it.name == existingCookie.name }
                    },
                )
                addAll(newCookies)
            }
            store[url.host] = HostEntry(merged, System.currentTimeMillis())
        } finally {
            hostLocks[url.host]?.unlock()
        }
    }

    /**
     * Retrieves non-expired cookies for the given URL's host.
     * Returns null if no cookies are cached or all have expired.
     */
    fun get(url: HttpUrl): List<Cookie>? {
        val entry = store[url.host] ?: return null
        val now = System.currentTimeMillis()
        val valid = entry.cookies.filter { cookie ->
            cookie.expiresAt > now
        }
        if (valid.isEmpty()) {
            store.remove(url.host)
            return null
        }
        return valid
    }

    /**
     * Retrieves non-expired cookies for the given URL's host that match the URL.
     * Uses [Cookie.matches] to ensure domain and path constraints are respected.
     */
    fun getMatching(url: HttpUrl): List<Cookie>? {
        val all = get(url) ?: return null
        val matching = all.filter { it.matches(url) }
        return if (matching.isEmpty()) null else matching
    }

    /**
     * Removes the cached cookies for the given URL's host.
     */
    fun remove(url: HttpUrl) {
        store.remove(url.host)
    }

    /**
     * Clears all cached cookies.
     */
    fun clear() {
        store.clear()
    }

    /**
     * Checks whether there is a valid cached cf_clearance for the given host.
     */
    fun hasCfClearance(url: HttpUrl): Boolean = get(url)?.any { it.name == "cf_clearance" } == true

    // ── Per-host locking ─────────────────────────────────────────────

    /**
     * Acquires the per-host lock to prevent concurrent duplicate solve attempts.
     * Returns `true` if the lock was acquired (caller should proceed with solving),
     * `false` if another thread is already solving for this host (caller should wait or skip).
     */
    fun lockForHost(host: String): Boolean {
        val lock = hostLocks.getOrPut(host) { ReentrantLock() }
        return lock.tryLock()
    }

    /**
     * Releases the per-host lock after a solve attempt completes.
     */
    fun unlockForHost(host: String) {
        hostLocks[host]?.unlock()
    }

    /**
     * Checks whether a solve is currently in progress for the given host.
     */
    fun isSolvingForHost(host: String): Boolean {
        val lock = hostLocks[host] ?: return false
        return lock.isLocked
    }
}
