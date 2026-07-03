package keiyoushi.lib.cloudscraper

import android.util.Log
import com.github.zhkl0228.impersonator.ImpersonatorFactory
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Lightweight Cloudflare bypass interceptor that solves challenges without a WebView.
 *
 * This interceptor uses QuickJS to evaluate Cloudflare's JavaScript challenges in a
 * headless environment, avoiding the heavy overhead of launching an Android WebView.
 *
 * **TLS fingerprint spoofing:**
 * Internally uses [ImpersonatorFactory] to create an [SSLContext] with Chrome's TLS/JA3
 * fingerprint via BouncyCastle. All challenge-solving requests and cookie-carrying retries
 * use this impersonated SSLContext, so Cloudflare sees a Chrome TLS handshake.
 *
 * **Supported challenge types:**
 * - **JSD** (JavaScript Detection) — the silent `__CF$cv$params` sensor challenge
 * - **Legacy IUAM** (`jschl_answer`) — old-style math challenges
 * - **Managed V2/V3** — modern "Just a moment…" challenges (best-effort via QuickJS DOM shim;
 *   may not work on all sites due to increasing reliance on full browser features)
 *
 * **Unsupported challenge types** (will throw [CloudscraperException] with
 * [CloudscraperError.UNSOLVABLE_CHALLENGE]):
 * - **Turnstile** — interactive widget requiring human interaction or CAPTCHA solver
 *
 * @param client the base OkHttpClient used for initial request detection
 * @param impersonatedClient optional pre-configured OkHttpClient with spoofed TLS fingerprints;
 *   if null, one is created internally with Android Chrome's TLS/JA3 fingerprint via
 *   [ImpersonatorFactory]. This client is used for all challenge-solving requests
 *   and the final retry with cookies
 * @param cookieCache optional cookie cache for reusing cf_clearance across requests;
 *   defaults to a new [CookieCache] with 25-minute TTL
 * @param userAgent the User-Agent string sent with challenge-solving requests;
 *   cf_clearance is tied to the UA that earned it, so this must match your normal requests
 * @param attemptManagedChallenge whether to attempt solving managed v2/v3 challenges
 *   via the QuickJS DOM shim; default `true`. Set to `false` to immediately fail
 *   on managed challenges (faster, avoids wasted QuickJS CPU time on sites that
 *   need a real browser)
 * @param maxRetries maximum number of retry attempts when solving fails with a
 *   transient error; default 2
 */
class CloudScraperInterceptor(
    private val client: OkHttpClient,
    private val impersonatedClient: OkHttpClient? = null,
    private val cookieCache: CookieCache = CookieCache(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val attemptManagedChallenge: Boolean = true,
    private val maxRetries: Int = 2,
) : Interceptor {

    /**
     * Internal client with Chrome TLS/JA3 fingerprint spoofing.
     * Used for all challenge-solving requests and cookie-carrying retries.
     */
    private val solveClient: OkHttpClient by lazy {
        impersonatedClient ?: createImpersonatedClient()
    }

    private fun createImpersonatedClient(): OkHttpClient {
        val api = ImpersonatorFactory.android()
        val sslContext = api.newSSLContext(null, null)

        val trustManager = run {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val host = url.host

        // ── 1. Check cookie cache ────────────────────────────────────
        val cachedCookies = cookieCache.getMatching(url)
        if (cachedCookies != null) {
            val requestWithCookies = attachCookies(originalRequest, cachedCookies)
            val cachedResponse = chain.proceed(requestWithCookies)
            if (!isCloudflareChallenge(cachedResponse)) {
                return cachedResponse
            }
            cachedResponse.close()
            cookieCache.remove(url)
            Log.d(TAG, "Cached cf_clearance for $host was stale, re-solving")
        }

        // ── 2. Proceed with original request ────────────────────────
        val originalResponse = chain.proceed(originalRequest)

        if (!isCloudflareChallenge(originalResponse)) {
            return originalResponse
        }

        // ── 3. Per-host lock to prevent duplicate solves ──────────────
        if (!cookieCache.lockForHost(host)) {
            // Another thread is already solving for this host.
            // Wait briefly, then retry with whatever cookies they obtained.
            originalResponse.close()
            Thread.sleep(SOLVE_WAIT_MS)
            val retryCookies = cookieCache.getMatching(url)
            if (retryCookies != null) {
                val retryRequest = attachCookies(originalRequest, retryCookies)
                return chain.proceed(retryRequest)
            }
            // Still no cookies — proceed without (will likely fail)
            return chain.proceed(originalRequest)
        }

        try {
            return solveChallenge(chain, originalRequest, originalResponse, url)
        } finally {
            cookieCache.unlockForHost(host)
        }
    }

    // ── Challenge solving ────────────────────────────────────────────

    private fun solveChallenge(
        chain: Interceptor.Chain,
        originalRequest: Request,
        originalResponse: Response,
        url: HttpUrl,
    ): Response {
        val page = originalResponse.peekBody(Long.MAX_VALUE).string()
        originalResponse.close()

        val info = ChallengeDetector.detect(page)

        Log.i(TAG, "Detected Cloudflare challenge type: ${info.type} for ${url.host}")

        return when (info.type) {
            ChallengeType.JSD -> solveJsd(chain, originalRequest, url, info)
            ChallengeType.LEGACY_IUAM -> solveLegacyIuam(chain, originalRequest, url, info)
            ChallengeType.MANAGED -> solveManaged(chain, originalRequest, url, page, info)
            ChallengeType.TURNSTILE -> throw CloudscraperException(
                CloudscraperError.UNSOLVABLE_CHALLENGE,
                "Turnstile challenge cannot be solved without a browser. " +
                    "Use WebView-based CloudflareInterceptor for this site.",
            )
        }
    }

    private fun solveJsd(
        chain: Interceptor.Chain,
        originalRequest: Request,
        url: HttpUrl,
        info: ChallengeInfo,
    ): Response {
        val rawCvParams = info.rawCvParams
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "JSD challenge detected but rawCvParams is null",
            )

        val solver = JsdSolver(solveClient, userAgent, maxRetries = maxRetries)
        val result = solver.solve(url, rawCvParams)

        cacheResult(url, result.cfClearance, result.cookies)

        return retryWithCookies(chain, originalRequest, url, result.cookies)
    }

    private fun solveLegacyIuam(
        chain: Interceptor.Chain,
        originalRequest: Request,
        url: HttpUrl,
        info: ChallengeInfo,
    ): Response {
        val solver = LegacyIuamSolver(solveClient, userAgent)
        val result = solver.solve(url, info)

        cacheResult(url, result.cfClearance, result.cookies)

        return retryWithCookies(chain, originalRequest, url, result.cookies)
    }

    private fun solveManaged(
        chain: Interceptor.Chain,
        originalRequest: Request,
        url: HttpUrl,
        page: String,
        info: ChallengeInfo,
    ): Response {
        if (!attemptManagedChallenge) {
            throw CloudscraperException(
                CloudscraperError.UNSOLVABLE_CHALLENGE,
                "Managed challenge detected but attemptManagedChallenge=false. " +
                    "Use WebView-based CloudflareInterceptor for this site.",
            )
        }

        val solver = ManagedChallengeSolver(solveClient, userAgent)
        val result = solver.solve(url, page, info)

        cacheResult(url, result.cfClearance, result.cookies)

        return retryWithCookies(chain, originalRequest, url, result.cookies)
    }

    // ── Cookie handling ──────────────────────────────────────────────

    private fun cacheResult(url: HttpUrl, cfClearance: String?, cookies: Map<String, String>) {
        if (cfClearance != null) {
            val cookie = Cookie.parse(
                url,
                "cf_clearance=$cfClearance; Domain=${url.host}; Path=/; Secure",
            )
            if (cookie != null) {
                cookieCache.put(url, cookie)
                Log.d(TAG, "Cached cf_clearance for ${url.host}")
            }
        }

        // Also cache any other CF cookies (e.g. __cf_bm)
        val otherCookies = cookies.entries
            .filter { (name, _) -> name != "cf_clearance" && (name.startsWith("cf_") || name.startsWith("__cf_")) }
            .mapNotNull { (name, value) ->
                Cookie.parse(url, "$name=$value; Domain=${url.host}; Path=/")
            }
        if (otherCookies.isNotEmpty()) {
            cookieCache.merge(url, otherCookies)
        }
    }

    private fun retryWithCookies(
        chain: Interceptor.Chain,
        originalRequest: Request,
        url: HttpUrl,
        cookies: Map<String, String>,
    ): Response {
        val cookieHeaderValue = cookies.entries.joinToString("; ") { (name, value) ->
            "$name=$value"
        }

        val retryRequest = originalRequest.newBuilder()
            .header("User-Agent", userAgent)
            .header("Cookie", cookieHeaderValue)
            .build()

        // Use the impersonated client directly for the retry to ensure the TLS
        // fingerprint matches Chrome. Going through chain.proceed() would use the
        // user's client which may have a Java/Android TLS fingerprint.
        val response = solveClient.newCall(retryRequest).execute()

        if (isCloudflareChallenge(response)) {
            response.close()
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "Challenge solved but Cloudflare still blocking — " +
                    "may require TLS fingerprint matching or site uses unsolvable challenge variant",
            )
        }

        return response
    }

    private fun attachCookies(request: Request, cookies: List<Cookie>): Request {
        val existingCookies = Cookie.parseAll(request.url, request.headers)
        val merged = buildList {
            // Keep existing cookies not overridden by cached ones
            addAll(
                existingCookies.filter { existing ->
                    cookies.none { it.name == existing.name }
                },
            )
            addAll(cookies)
        }
        return request.newBuilder()
            .header("Cookie", merged.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    companion object {
        private const val TAG = "CloudScraper"
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Time to wait when another thread is solving for the same host. */
        private const val SOLVE_WAIT_MS = 500L

        private fun isCloudflareChallenge(response: Response): Boolean = response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK
    }
}

// ── Error taxonomy ───────────────────────────────────────────────────

/**
 * Error classification for Cloudscraper failures.
 *
 * - **Transient** errors ([isTransient]=true): may succeed on retry
 *   (network issues, parse failures, expired cookies)
 * - **Permanent** errors ([isTransient]=false): will not succeed on retry
 *   (unsolvable challenge type, browser required)
 */
enum class CloudscraperError(val isTransient: Boolean) {
    /** Failed to parse challenge parameters from the page HTML. */
    CHALLENGE_PARSE_FAILED(true),

    /** Failed to fetch the challenge script from CDN. */
    SCRIPT_FETCH_FAILED(true),

    /** The challenge script exceeded the size limit. */
    SCRIPT_TOO_LARGE(false),

    /** The script ran but did not produce a sensor payload. */
    NO_SENSOR_PAYLOAD(true),

    /** The challenge POST did not return a cf_clearance cookie. */
    NO_CLEARANCE_COOKIE(true),

    /** Challenge was solved but Cloudflare still blocks (wrong TLS fingerprint, etc.). */
    SOLVE_FAILED(true),

    /** The challenge type cannot be solved without a browser. */
    UNSOLVABLE_CHALLENGE(false),

    /** QuickJS engine timed out during script evaluation. */
    ENGINE_TIMEOUT(true),

    /** QuickJS script triggered a stack overflow — challenge script too deeply recursive. */
    STACK_OVERFLOW(true),
}

/**
 * Typed exception for Cloudscraper failures.
 * Carries a [CloudscraperError] code for programmatic error handling.
 */
class CloudscraperException(
    val error: CloudscraperError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
