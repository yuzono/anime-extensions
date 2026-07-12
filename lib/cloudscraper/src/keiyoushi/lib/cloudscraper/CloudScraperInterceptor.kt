package keiyoushi.lib.cloudscraper

import android.util.Log
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Hybrid Cloudflare bypass interceptor — headless JSD fast path with a
 * WebView fallback that actually works for modern Cloudflare.
 *
 * **The headless reality:** modern Cloudflare managed challenges and
 * Turnstile widgets cannot be solved in a headless QuickJS environment —
 * the fingerprint signals CF collects (real canvas/WebGL rendering, pointer
 * events, focus state) are not reproducible without a real browser engine.
 * The old `ManagedChallengeSolver` and `LegacyIuamSolver` were removed for
 * this reason; they produced zero production wins.
 *
 * **What this interceptor actually does:**
 * 1. Checks the in-memory [CookieCache] for a valid `cf_clearance` for the
 *    request's host. If present and still accepted by CF, short-circuits.
 * 2. Otherwise proceeds with the original request.
 * 3. If CF responds with a challenge (403/503 + `Server: cloudflare`, or
 *    `cf-mitigated: challenge`), detects the challenge type:
 *    - **JSD** (`window.__CF$cv$params`) — attempt the headless [JsdSolver]
 *      as a fast path. If it succeeds, cache the cookie and retry through
 *      the full chain.
 *    - **UNSOLVABLE** (managed / Turnstile / legacy / unclassifiable) —
 *      go straight to the WebView fallback.
 * 4. On JSD failure with a transient error, fall back to WebView too —
 *    the JSD path is now a *fast path optimization*, never a hard wall.
 *
 * **TLS note:** an earlier revision of this interceptor installed an
 * impersonated TLS client (Chrome JA3 fingerprint via the BouncyCastle-based
 * `impersonator-bctls` library) on the JSD solve path. That library was
 * fundamentally broken on Android — BCJSSE raises `TlsFatalAlert` during
 * the TLS 1.3 handshake on every modern Cloudflare edge (see upstream
 * issues zhkl0228/impersonator#8 and #9), so the impersonated `solveClient`
 * never even reached the JSD script-fetch stage. The fast path was removed
 * and the solve client inherited the outer [client]'s default OkHttp/Android
 * TLS stack for a period.
 *
 * **Current approach (reinstated):** [solveClient] now layers
 * [keiyoushi.lib.tlsspoof.SpoofedTlsSupport] onto [client]'s builder. This
 * installs the upstream [org.conscrypt:conscrypt-android] JSSE provider at
 * JCA position 1 — Conscrypt IS Chromium's TLS stack (same source tree,
 * different build from Android's bundled variant) and ships GREASE / ALPS /
 * the X25519MLKEM768 post-quantum extension Chrome 131 advertises. The
 * resulting JA3/JA4 is Chromium-adjacent rather than byte-identical to
 * Chrome on Windows (a per-byte match requires NDK/JNI native code), but it
 * is non-Android-default and is sufficient for Cloudflare edges that have
 * escalated to reject the Android-default fingerprint. The full rationale,
 * including the upstream-issue references and the NDK-vs-pure-JVM tradeoff,
 * lives in `:lib:tlsspoof/TlsSpoof.kt`. The WebView fallback itself still
 * uses real Android WebView Chromium TLS via [CloudflareInterceptor]; only
 * its sibling OkHttp client carrying cookies back through the jar uses the
 * spoofed TLS profile.
 *
 * The post-solve retry goes through [Interceptor.Chain.proceed] **not** the
 * solve client, so the rest of the network chain (zstd decompression, rate
 * limiting, etc.) still runs on the response. This interceptor must be added
 * as a **network interceptor** (not an app interceptor) — see the Miruro
 * wiring for the canonical placement.
 *
 * **WebView fallback:**
 * Delegates to [CloudflareInterceptor] (rewritten, lives in
 * `:lib:cloudflareinterceptor`). That interceptor launches a real Android
 * WebView, loads the challenged URL, lets Cloudflare's JS run in a real
 * Chromium engine, and harvests `cf_clearance` from the WebView's
 * [android.webkit.CookieManager]. Acquired cookies are pulled back into
 * this interceptor's [CookieCache] so subsequent requests skip the WebView.
 *
 * **Unsupported:** if the WebView fallback still cannot clear the challenge
 * (e.g. interactive Turnstile with no auto-pass), throws
 * [CloudscraperException] with [CloudscraperError.SOLVE_FAILED].
 *
 * @param client the base OkHttpClient used for original-request detection
 *   and as the cookie-jar source for the WebView fallback's harvested cookies
 * @param cookieCache optional cookie cache for reusing cf_clearance across requests
 * @param userAgent the User-Agent string sent with challenge-solving requests;
 *   cf_clearance is tied to the UA that earned it, so this must match the
 *   requests issued by your normal client AND the UA the WebView fallback
 *   uses when clearing the challenge. Should be a **real, current** Chrome
 *   version — Chrome 131 on Windows is the canonical default here.
 * @param maxRetries maximum number of JSD retry attempts on transient failures;
 *   default 2. WebView fallback is always attempted once after JSD gives up.
 */
class CloudScraperInterceptor(
    private val client: OkHttpClient,
    private val cookieCache: CookieCache = CookieCache(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val maxRetries: Int = 2,
) : Interceptor {

    /**
     * Internal client used by the JSD fast path for the sensor-script fetch
     * and the challenge POST.
     *
     * Internal retry requests dispatched through [solveClient] are tagged
     * with [SKIP_HEADER] so that re-entering this interceptor short-circuits
     * (otherwise [solveClient] would recursively invoke us on every call,
     * since we are a network interceptor of [client]).
     *
     * Solving the actual Cloudflare challenge POST or fetching the JSD script
     * happens through this client; solving then returns the cleaned response
     * (which has already passed through [client]'s downstream interceptors
     * — including zstd/gzip decompression — by construction, so it can be
     * returned directly to the caller without an additional chain.proceed).
     *
     * **Spoofed TLS:** the solve client is built from [client]'s builder with
     * [keiyoushi.lib.tlsspoof.SpoofedTlsSupport.applyTo] layered on top, so
     * the script fetch and challenge POST run through the upstream Conscrypt
     * JSSE provider (see `:lib:tlsspoof/TlsSpoof.kt`). Cloudflare's edge
     * rejects the Android-default TLS fingerprint on sites that have
     * escalated edge-level blocking; the JSD solve call therefore *must* use
     * a Chromium-adjacent TLS fingerprint or it will be turned away before
     * the challenge JS even runs.
     */
    private val solveClient: OkHttpClient = buildSpoofedClient(client)

    /**
     * Builds a sibling [OkHttpClient] from [base] that uses the Chrome 131
     * TLS profile (cipher-suite ordering + the upstream Conscrypt JSSE
     * provider) instead of Android's default Conscrypt fingerprint.
     *
     * The returned client inherits [base]'s cookie jar, dispatcher,
     * interceptors, and timeouts (because `newBuilder()` preserves all of
     * them). It only differs in its `SSLContext` provider and
     * `connectionSpecs`. See [keiyoushi.lib.tlsspoof.SpoofedTlsSupport] for
     * the spec details.
     *
     * Used by [solveClient] and [webviewFallbackClient] — both Cloudflare-
     * facing legs of this interceptor benefit from the spoofed TLS profile
     * because they hit either the CF challenge endpoint or the challenged
     * origin directly.
     */
    private fun buildSpoofedClient(base: OkHttpClient): OkHttpClient = keiyoushi.lib.tlsspoof.SpoofedTlsSupport.applyTo(base.newBuilder()).build()

    /**
     * WebView fallback interceptor, lazily constructed. Cookies it acquires
     * via CookieManager + OkHttp cookie jar sync are pulled back into this
     * interceptor's [cookieCache] by [solveWebViewCookies], so subsequent
     * requests skip both the JSD path and the WebView.
     */
    private val webviewFallback: CloudflareInterceptor by lazy {
        CloudflareInterceptor(client, userAgent = userAgent)
    }

    private val webviewFallbackClient: OkHttpClient by lazy {
        // Spoofed TLS applied here too: the WebView fallback issues requests
        // to the same challenged origin via OkHttp (the WebView itself loads
        // via the system WebView TLS, but the post-solve cookie sync back
        // through `client.cookieJar` happens via this OkHttp client — and
        // any non-WebView origin fetch must present the spoofed fingerprint).
        buildSpoofedClient(client).newBuilder()
            .addNetworkInterceptor(webviewFallback)
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val host = url.host

        // Skip-header short-circuit: internal retry requests dispatched by
        // solveClient carry SKIP_HEADER so they pass through us without
        // re-entering the challenge/solve logic (otherwise solveClient would
        // infinitely recurse, since we are a network interceptor of the
        // `client` it was built from). On this path we call chain.proceed()
        // exactly once and return — same as if we weren't installed.
        if (originalRequest.header(SKIP_HEADER) != null) {
            val cleaned = originalRequest.newBuilder()
                .removeHeader(SKIP_HEADER)
                .build()
            return chain.proceed(cleaned)
        }

        // ── 1. Single detection / passthrough via chain.proceed ────────
        // We attach any cached CF cookies here so the request goes upstream
        // with them attached; if the cache is fresh we get the cleared page
        // in one shot. Detection runs through chain.proceed (NOT solveClient)
        // because chain.proceed uses the OUTER client's TLS — works for any
        // host (AniList, Cloudfront, …). The solve/retry leg below uses the
        // same outer TLS via [solveClient]; it is only reached once we KNOW
        // the host returned a Cloudflare challenge.
        val cachedCookies = cookieCache.getMatching(url)
        val detectionRequest = if (cachedCookies != null) {
            attachCookies(originalRequest, cachedCookies)
        } else {
            originalRequest
        }
        val detectionResponse = chain.proceed(detectionRequest)

        if (!isCloudflareChallenge(detectionResponse)) {
            return detectionResponse
        }

        // ── Block-page rejection ───────────────────────────────────────
        // Cloudflare WAF block pages return 403 with `Server: cloudflare`
        // (same headers as a JSD challenge), but are NOT solvable — no
        // challenge cookie can clear a WAF block rule. We peek the body
        // to distinguish blocks from challenges here, before entering the
        // solving pipeline (lock acquisition, JSD solver, WebView fallback).
        //
        // The body is peeked (not consumed) so [solveAndCacheCookies] can
        // re-peek it later for challenge-type detection. If the page is a
        // block, we return the raw 403 upstream and let the caller handle it.
        val bodySample = detectionResponse.peekBody(BLOCK_CHECK_BODY_SIZE).string()
        if (isBlockPage(bodySample)) {
            Log.w(TAG, "Cloudflare block page detected for $host — not a solvable challenge, passing through 403")
            return detectionResponse
        }

        // Cache was stale (we tried with cached cookies but still got a
        // challenge). Drop the cache entry so the next round solves fresh.
        if (cachedCookies != null) {
            cookieCache.remove(url)
            Log.d(TAG, "Cached cf_clearance for $host was stale, re-solving")
        }

        // ── 2. Per-host lock contention ───────────────────────────────
        if (!cookieCache.lockForHost(host)) {
            // Wait for the in-flight solver to either publish cookies or
            // release the lock. Bail on lock-release so a hard-failed solver
            // doesn't pin this waiter for the full SOLVE_WAIT_MS.
            detectionResponse.close()
            Log.d(TAG, "Waiting for in-flight solve for $host")
            val deadline = System.currentTimeMillis() + SOLVE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val retryCookies = cookieCache.getMatching(url)
                if (retryCookies != null) {
                    return solveClientRetry(originalRequest, retryCookies)
                }
                if (!cookieCache.isSolvingForHost(host)) {
                    Log.d(TAG, "In-flight solve for $host finished without publishing cookies — bailing wait")
                    break
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            // detectionResponse was closed above; re-fetch via solveClient so
            // the caller gets an open response (SKIP_HEADER short-circuits us).
            return solveClientPassThrough(originalRequest)
        }

        // ── 3. We hold the lock — solve and return via solveClient ────
        // solveAndCacheCookies throws on solving failure; the propagated
        // exception skips OkHttp's "exactly once" check (that check only runs
        // when intercept() returns normally) so callers see the real
        // CloudscraperException rather than a masked IllegalStateException.
        return try {
            val solvedCookies = solveAndCacheCookies(originalRequest, detectionResponse, url)
            detectionResponse.close()
            solveClientRetry(originalRequest, solvedCookies)
        } finally {
            runCatching { detectionResponse.close() }
            cookieCache.unlockForHost(host)
        }
    }

    /**
     * Re-issues `originalRequest` with `cookies` attached via [solveClient],
     * tagged with [SKIP_HEADER] so the inner pass through our own interceptor
     * short-circuits. The returned [Response] has already traversed [client]'s
     * full network interceptor chain (rate limit, decompression, etc.) — so it
     * is fully processable upstream and the outer `intercept()` does NOT need
     * a second `chain.proceed()`.
     *
     * Throws [CloudscraperException] if the solved cookies don't clear the
     * challenge — surfaces a real solve-failure cause (not OkHttp's
     * IllegalStateException noise) since solveClient.newCall().execute()
     * returns a normal Response that the outer intercept's caller sees.
     *
     * **Chain-field stripping:** the inner `solveClient.newCall().execute()`
     * call runs the entire OkHttp chain a SECOND time (RetryAndFollowUp →
     * Bridge → Cache → Connect → network interceptors → CallServer). The
     * inner [okhttp3.internal.cache.CacheInterceptor] assigns a
     * `networkResponse` on the returned Response to mark it as the network
     * origin for the inner call.
     *
     * When this Response is then returned from this network interceptor to
     * the **outer** OkHttp chain (which contains its own
     * `CacheInterceptor`), the outer `CacheInterceptor.intercept` calls
     * `Response.Builder.networkResponse(stripBody(networkResponseArg))`.
     * `okhttp3.Response$Builder.checkSupportResponse` enforces the invariant
     * `networkResponseArg.networkResponse() == null` — but we just gave it a
     * Response whose own `networkResponse()` is non-null (the inner-call
     * marker). That throws
     * `IllegalArgumentException: networkResponse.networkResponse != null`,
     * which propagates out through `UncaughtExceptionInterceptor` as an
     * `IOException`, killing the request.
     *
     * We therefore strip the inner-call provenance fields (`networkResponse`,
     * `cacheResponse`, `priorResponse`) before returning, so the outer
     * `CacheInterceptor` treats the Response as a clean network origin. The
     * `body`/`headers`/`code`/etc. travel through untouched. This is the
     * same transformation OkHttp performs inside `CacheInterceptor.stripBody`
     * for its own assembled Response — we're just applying it earlier so the
     * outer chain sees a "raw" network response rather than a wrapped one.
     */
    private fun solveClientRetry(originalRequest: Request, cookies: List<Cookie>): Response {
        val retryRequest = originalRequest.newBuilder()
            .header(SKIP_HEADER, "1")
            .header("User-Agent", userAgent)
            .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
        val startedAt = System.nanoTime()
        val response = solveClient.newCall(retryRequest).execute()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        if (isCloudflareChallenge(response)) {
            Log.w(
                TAG,
                "solveClientRetry: still-Cloudflare code=${response.code} host=${originalRequest.url.host} " +
                    "(${elapsedMs}ms) — server=${response.header("Server") ?: "?"} " +
                    "cf-mitigated=${response.header("cf-mitigated") ?: "?"}",
            )
            response.close()
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "Challenge solved but Cloudflare still blocking ${originalRequest.url} — " +
                    "may require TLS fingerprint matching or site uses unsolvable challenge variant",
            )
        }
        if (response.code in 400..599) {
            Log.w(
                TAG,
                "solveClientRetry: non-cf upstream error code=${response.code} " +
                    "host=${originalRequest.url.host} (${elapsedMs}ms) — passing through to caller",
            )
        } else {
            Log.d(
                TAG,
                "solveClientRetry: ok code=${response.code} host=${originalRequest.url.host} (${elapsedMs}ms)",
            )
        }
        return stripInnerCallProvenance(response)
    }

    private fun solveClientPassThrough(originalRequest: Request): Response {
        val passRequest = originalRequest.newBuilder()
            .header(SKIP_HEADER, "1")
            .header("User-Agent", userAgent)
            .build()
        return stripInnerCallProvenance(solveClient.newCall(passRequest).execute())
    }

    /**
     * Clears the `networkResponse`/`cacheResponse`/`priorResponse` provenance
     * fields on a Response produced by an inner [OkHttpClient.newCall] so it
     * can be safely returned from a network interceptor to its outer OkHttp
     * chain. See [solveClientRetry] for the rationale.
     */
    private fun stripInnerCallProvenance(response: Response): Response = response.newBuilder()
        .networkResponse(null)
        .cacheResponse(null)
        .priorResponse(null)
        .build()

    /**
     * Runs the appropriate solver (JSD fast path with WebView fallback,
     * or WebView directly for unsolvable challenges), persists acquired
     * cookies to [cookieCache], and returns them as a list of [Cookie]
     * for the caller to attach to the final `chain.proceed(...)` call.
     *
     * Does NOT issue any request through the interceptor chain — the
     * caller is responsible for the single `chain.proceed(requestWithCookies)`
     * that returns the cleared response to upstream.
     */
    private fun solveAndCacheCookies(
        originalRequest: Request,
        challengeResponse: Response,
        url: HttpUrl,
    ): List<Cookie> {
        val page = challengeResponse.peekBody(CHALLENGE_BODY_CAP).string()

        // Extract cookies from the challenge response (e.g. __cf_bm) so they
        // can be forwarded with the challenge POST — the platform associates
        // sensor submissions with the initial session via these cookies.
        val responseCookies = JsdSolver.extractCookies(challengeResponse.headers("Set-Cookie"))

        // Also forward cookies FROM the original request (e.g. __cf_bm from an
        // earlier session that Cloudflare already knows). These aren't in the
        // response's Set-Cookie because the browser already had them, but the
        // challenge POST still needs them so Cloudflare can link the sensor
        // submission to the same session that triggered the challenge.
        val requestCookies = originalRequest.header("Cookie")
            ?.split(";")
            ?.mapNotNull { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    trimmed.substring(0, eq) to trimmed.substring(eq + 1)
                } else {
                    null
                }
            }?.toMap() ?: emptyMap()

        // Merge: response cookies (more recent) take precedence over request cookies
        val mergedCookies = requestCookies + responseCookies
        if (mergedCookies.isNotEmpty()) {
            Log.d(TAG, "Challenge merged cookies: ${mergedCookies.keys}")
        }

        val info = ChallengeDetector.detect(page)
        Log.i(TAG, "Detected Cloudflare challenge type: ${info.type} for ${url.host}")

        return when (info.type) {
            ChallengeType.JSD -> {
                try {
                    solveJsdCookies(url, info, mergedCookies)
                } catch (e: CloudscraperException) {
                    Log.w(TAG, "JSD solve failed (${e.error}), falling back to WebView for $url")
                    solveWebViewCookies(url, originalRequest, mergedCookies)
                }
            }
            ChallengeType.UNSOLVABLE -> {
                // Managed / Turnstile / legacy / unclassifiable → WebView directly.
                solveWebViewCookies(url, originalRequest, mergedCookies)
            }
        }
    }

    /**
     * Headless JSD fast-path solver. Returns the cookies Cloudflare issued
     * on a successful challenge POST (cf_clearance + any siblings), and
     * caches them in [cookieCache] for cross-request reuse.
     */
    private fun solveJsdCookies(
        url: HttpUrl,
        info: ChallengeInfo,
        initialCookies: Map<String, String>,
    ): List<Cookie> {
        val rawCvParams = info.rawCvParams
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "JSD challenge detected but rawCvParams is null",
            )

        val solver = JsdSolver(solveClient, userAgent, maxRetries = maxRetries)
        val result = solver.solve(url, rawCvParams, initialCookies)

        cacheResult(url, result.cfClearance, result.cookies)

        // Materialize the solved cookies as okhttp3.Cookie instances so the
        // caller can attach them to the final chain.proceed request.
        return result.cookies.entries.mapNotNull { (name, value) ->
            Cookie.parse(url, "$name=$value; Domain=${url.host}; Path=/")
        }
    }

    /**
     * WebView fallback — delegates to [CloudflareInterceptor] which launches a
     * real Android WebView, lets Cloudflare's JS run in Chromium, harvests
     * `cf_clearance` (and `__cf_bm`) from the WebView cookie jar, and retries
     * the original request with the cleared cookies.
     *
     * Invoked as a network interceptor on a fresh [OkHttpClient] built from
     * [client] (which preserves the shared cookie jar). The fallback executes
     * the request; on a Cloudflare challenge it solves via WebView, syncs the
     * acquired cookies into [client]'s cookie jar, and returns the cleared
     * response. We discard that response (we cannot return it — the outer
     * [intercept] must call `chain.proceed(...)` exactly once to satisfy
     * OkHttp's network-interceptor invariant) and instead pull the cookies
     * back from [client]'s cookie jar so the caller can attach them to the
     * single post-solve `chain.proceed(requestWithCookies)`.
     *
     * Side effect: pulls any CF cookies the WebView acquired into this
     * interceptor's [cookieCache] so subsequent requests skip the WebView.
     */
    private fun solveWebViewCookies(
        url: HttpUrl,
        originalRequest: Request,
        initialCookies: Map<String, String>,
    ): List<Cookie> {
        // Decorate the request with the merged CF cookies we already have so
        // Cloudflare associates the WebView load with the same session that
        // triggered the challenge. The WebView fallback will further attach
        // cookies it acquires.
        val baseRequest = if (initialCookies.isEmpty()) {
            originalRequest
        } else {
            originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .header(
                    "Cookie",
                    initialCookies.entries.joinToString("; ") { (n, v) -> "$n=$v" },
                )
                .build()
        }

        val clearedResponse = try {
            webviewFallbackClient.newCall(baseRequest).execute()
        } catch (e: Exception) {
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "WebView fallback network call failed for $url: ${e.message}",
                e,
            )
        }

        // If the fallback still couldn't clear (e.g. interactive Turnstile with
        // no auto-pass), close and bubble as SOLVE_FAILED. We deliberately use
        // SOLVE_FAILED (not UNSOLVABLE_CHALLENGE) so the caller's error taxonomy
        // distinguishes "we tried and couldn't" from "we never tried".
        if (isCloudflareChallenge(clearedResponse)) {
            val code = clearedResponse.code
            clearedResponse.close()
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "WebView fallback did not clear Cloudflare challenge for $url (post-solve HTTP $code)",
            )
        }

        // The fallback response itself cannot be returned to the caller (the
        // outer intercept() must issue the final response via its own
        // chain.proceed). Close it; the cookies (synced by the fallback into
        // client.cookieJar) are what we carry forward.
        clearedResponse.close()

        // Pull CF cookies from the cookie jar; cache them and return to caller.
        val jarCookies = try {
            client.cookieJar.loadForRequest(url)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load WebView-acquired cookies from jar: ${e.message}")
            emptyList()
        }
        val cfCookies = jarCookies.filter {
            it.name == "cf_clearance" || it.name.startsWith("__cf_") || it.name.startsWith("cf_")
        }
        if (cfCookies.isEmpty()) {
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "WebView fallback cleared the response but produced no CF cookies for $url",
            )
        }
        cacheResult(
            url,
            cfClearance = cfCookies.firstOrNull { it.name == "cf_clearance" }?.value,
            cookies = cfCookies.associate { it.name to it.value },
        )
        return cfCookies
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

        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val SOLVE_WAIT_MS = 20_000L

        private const val POLL_INTERVAL_MS = 100L

        private const val CHALLENGE_BODY_CAP = 5L * 1024 * 1024

        /** Max bytes to read for the block-page check in [isBlockPage]. */
        private const val BLOCK_CHECK_BODY_SIZE = 4096L

        private const val SKIP_HEADER = "X-Cloudscraper-Skip"

        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        /**
         * Header-level Cloudflare check — returns true if the response looks
         * like ANY Cloudflare challenge or block (403/503 + CF server header).
         *
         * This is deliberately broad because the header alone cannot distinguish
         * a WAF block from a solvable JSD challenge. The caller must also check
         * [isBlockPage] on the response body before entering the solve pipeline.
         */
        private fun isCloudflareChallenge(response: Response): Boolean {
            val isError = response.code in ERROR_CODES
            val isCfServer = response.header("Server") in SERVER_CHECK
            val isMitigated = response.header("cf-mitigated").equals("challenge", ignoreCase = true)
            return (isError && isCfServer) || (isError && isMitigated)
        }
    }

    /**
     * Body-level check — detects Cloudflare WAF block pages (e.g. "Sorry, you
     * have been blocked") that return 403 with `Server: cloudflare` headers but
     * are NOT solvable challenges.
     *
     * Block pages use the `cf-error-details` CSS class wrapper and contain
     * "blocked" language in the title or heading. Solvable challenge pages use
     * the `cf-browser-verification` wrapper and "Just a moment..." language.
     *
     * @param html sample of the response body (first ~4 KB is sufficient)
     * @return true if the page is a Cloudflare WAF block (not solvable)
     */
    private fun isBlockPage(html: String): Boolean = html.contains("cf-error-details") || html.contains("have been blocked")
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
