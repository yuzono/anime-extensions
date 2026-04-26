package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ─────────────────────────────────────────────────────────────────────────────
// Signature data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents a single hanime.tv API signature bundle.
 *
 * @property signature 64-character hex string sent as the `x-signature` header.
 * @property time       Unix timestamp string sent as the `x-time` header.
 * @property createdAt  Epoch-millis timestamp of when this signature was obtained,
 *                       used to determine expiry.
 */
data class Signature(
    val signature: String,
    val time: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * Returns `true` when this signature is older than [ttlMs] milliseconds.
     * The default TTL is intentionally shorter than the server-side 5-minute
     * window to avoid serving stale signatures.
     */
    fun isExpired(ttlMs: Long = SIGNATURE_TTL_MS): Boolean = System.currentTimeMillis() - createdAt > ttlMs

    companion object {
        /** Default signature TTL: 4 minutes (expires before the 5-minute server-side limit). */
        const val SIGNATURE_TTL_MS = 4 * 60 * 1000L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Signature provider interface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Supplies fresh [Signature] instances for authenticating hanime.tv API requests.
 *
 * Implementations may be expensive (e.g. WebView load, WASM execution), so
 * callers should prefer wrapping the provider with [SignatureCache].
 */
interface SignatureProvider {

    /**
     * Obtain a fresh signature.  May involve heavy work such as loading a
     * WebView or executing WASM — avoid calling on the main thread.
     */
    suspend fun getSignature(): Signature

    /** Human-readable label for this provider (useful in logs / preferences). */
    val name: String

    /** Release any held resources (WebView, memory, etc.). Default is a no-op. */
    fun close() {}
}

// ─────────────────────────────────────────────────────────────────────────────
// Signature cache
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Time-based caching decorator for any [SignatureProvider].
 *
 * Returns a previously fetched signature as long as it has not exceeded [ttlMs].
 * Uses a coroutine [Mutex] so concurrent callers share a single refresh rather
 * than each triggering an expensive signature computation.
 *
 * @param delegate The underlying provider that performs the actual signature work.
 * @param ttlMs    How long a cached signature is considered valid.
 *                  Defaults to [Signature.SIGNATURE_TTL_MS].
 */
class SignatureCache(
    private val delegate: SignatureProvider,
    private val ttlMs: Long = Signature.SIGNATURE_TTL_MS,
) : SignatureProvider {

    override val name: String get() = "Cached(${delegate.name})"

    @Volatile
    private var cached: Signature? = null

    private val lock = Mutex()

    override suspend fun getSignature(): Signature {
        // Fast path — cached signature is still valid
        cached?.let { sig ->
            if (!sig.isExpired(ttlMs)) return sig
        }

        // Slow path — acquire lock, double-check, then refresh
        return lock.withLock {
            cached?.let { sig ->
                if (!sig.isExpired(ttlMs)) return@withLock sig
            }

            val fresh = delegate.getSignature()
            cached = fresh
            fresh
        }
    }

    /**
     * Force-clear the cached signature so the next call fetches a fresh one.
     *
     * Acquires the lock to prevent races with [getSignature]'s double-check
     * pattern — without the lock, an in-flight refresh could overwrite the
     * invalidation, leaving a stale signature in the cache.
     */
    suspend fun invalidate() {
        lock.withLock { cached = null }
    }

    override fun close() {
        delegate.close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Signature headers helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds the set of HTTP headers that hanime.tv requires alongside every
 * authenticated API request.
 */
object SignatureHeaders {

    /**
     * Construct the full header map from a [Signature].
     *
     * Includes the two dynamic signature headers (`x-signature`, `x-time`)
     * plus the static headers that the server expects.
     */
    fun build(signature: Signature): Map<String, String> = mapOf(
        "x-signature" to signature.signature,
        "x-time" to signature.time,
        "x-signature-version" to "web2",
        "x-session-token" to "",
        "x-user-license" to "",
        "x-csrf-token" to "",
        "x-license" to "",
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Domain-specific exception
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thrown when signature extraction fails or times out.
 *
 * @param message Human-readable description of the failure.
 * @param cause The underlying exception, if any.
 */
class SignatureException(message: String, cause: Throwable? = null) : Exception(message, cause)
