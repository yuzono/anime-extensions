package eu.kanade.tachiyomi.animeextension.en.miruro

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Browser-fingerprint request-shaping interceptor for the miruro.tv edge.
 *
 * ## Why this exists
 *
 * Miruro's Cloudflare edge enforces a **WAF custom-rule block** (not a
 * managed challenge — `cf-mitigated: challenge` is not set; the body is the
 * "Sorry, you have been blocked" page with `cf-error-details` and no
 * `cf-mitigated` header). Live curl against `https://www.miruro.tv/` with a
 * bare Chrome `User-Agent` returns 200 OK; the *same* UA + `Accept` + a
 * `Referer` against `/api/secure/pipe?e=<base64url>` returns 403. The WAF
 * rule is rejecting pipe-API requests whose header set does not match a real
 * Chrome browser's `fetch()`. The miruro.tv frontend itself sends **no**
 * custom headers on its GET pipe fetch — every browser-shape header
 * (`sec-fetch-*`, `sec-ch-ua*`, `Origin`, `Accept-Language`,
 * `Accept-Encoding` advertising gzip/deflate/br/zstd) is provided for free by
 * the browser. An OkHttp client does not send any of those by default, so the
 * WAF flags it as a bot. (This interceptor advertises only `gzip, deflate, br`
 * — see [header-contract] below for why `zstd` is intentionally omitted.)
 *
 * The previous attempt routed every request through `CloudScraperInterceptor`
 * (the in-repo lib `keiyoushi.lib.cloudscraper`), but that interceptor's
 * `isBlockPage` path passes through block pages **unresolved by design** —
 * there is no challenge to solve. Routing through it cannot fix a custom-rule
 * block. The only fix is to send the headers a real Chrome browser would
 * send, so the WAF rule does not trip in the first place.
 *
 * ## What it does
 *
 * For every request that flows through the [client][Miruro.client] network
 * interceptor chain, this interceptor classifies the request by URL path:
 *
 * - **Pipe context** — path begins with `/api/` (the `/api/secure/pipe`
 *   call). Shaped as a same-origin CORS XHR-style fetch: `Sec-Fetch-Dest: empty`,
 *   `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: same-origin`, `Origin` set.
 * - **Navigate context** — anything else (the warm-up `GET /` and
 *   `GET /watch/<id>` calls). Shaped as a toplevel navigation:
 *   `Sec-Fetch-Dest: document`, `Sec-Fetch-Mode: navigate`, no `Origin`.
 *
 * In both contexts the interceptor **overwrites** the WAF-fingerprint
 * headers (Accept-Encoding / Accept-Language / User-Agent / Sec-Ch-Ua /
 * Sec-Ch-Ua-Mobile / Sec-Ch-Ua-Platform / Sec-Fetch-Dest / Sec-Fetch-Mode /
 * Sec-Fetch-Site / Origin) and **fills-if-absent** the body-shape headers
 * (Accept / Referer).
 *
 * The split is deliberate:
 * - WAF-fingerprint headers must always be the Chrome 148 desktop values so
 *   the WAF rule never trips, regardless of what the caller passes in.
 * - Body-shape headers should reflect caller intent where possible (e.g. a
 *   POST body upload should be allowed to set its own `Content-Type`), but
 *   sane browser defaults should be provided when the caller omits them.
 *
 * ## Design decisions (user-approved)
 *
 * 1. **Owns User-Agent.** Always overwrites `User-Agent` with
 *    [Miruro.USER_AGENT] (Chrome 148 on Windows). The sec-ch-ua brand claim
 *    below matches that UA string.
 * 2. **Overwrites Accept-Encoding with `gzip, deflate, br`.** The
 *    OkHttp BridgeInterceptor only fills `gzip` if absent and never advertises
 *    `br`; miruro's WAF/edge responds with `content-encoding: br` on pipe
 *    responses, which is then transparently decompressed by the host app's
 *    `okhttp3.CompressionInterceptor` (brotli-aware) before any downstream
 *    interceptor or the body extractor sees the bytes. An earlier revision
 *    advertised `zstd` here and relied on a per-extension
 *    `ZstdDecompressInterceptor` backed by `zstd-jni`, but the `zstd-jni`
 *    native library (`libzstd-jni-*.so`) proved not to be packaged into the
 *    extension APK — `ZstdInputStream`'s static initializer raised
 *    `NoClassDefFoundError` on real Android devices. Dropping `zstd` from the
 *    advertised list sidesteps the native-lib packaging issue entirely while
 *    still advertising the same browser-shape encoding list a real Chrome
 *    desktop browser would send (Chrome advertises `gzip, deflate, br, zstd`;
 *    `zstd` is opt-in only and its absence is not a WAF bot signal — verified
 *    via live curl against the pipe endpoint, which returns 200 +
 *    `x-obfuscated: 2` regardless of whether `zstd` is included in the list).
 * 3. **Accept-Language `en-US,en;q=0.9`.** The previous warmup code used
 *    `q=0.5`; the browser default is `q=0.9`. The `0.5` value is itself a
 *    bot signal.
 * 4. **Origin only on pipe context.** Real Chrome does not send `Origin` on
 *    a toplevel `GET` (cold navigation); it does send it on CORS fetches.
 *    Sending `Origin` on a warmup GET would itself be a bot signal.
 * 5. **Sec-Fetch-Site: `none` on cold navigate.** A warmup GET with no
 *    caller-supplied `Referer` mirrors a fresh browser tab. Setting `none`
 *    here matches what real Chrome sends.
 *
 * ## What it does NOT do
 *
 * - It does **not** touch `Cookie` — the cookie jar (`JavaNetCookieJar` on
 *   `super.client`, applied by BridgeInterceptor) handles cookie
 *   attach/persist. The warm-up visits performed by [Miruro.ensureBaseVisit]
 *   and [Miruro.ensureWatchPageVisited] populate the jar, and this interceptor
 *   passes cookies through untouched.
 * - It does **not** mutate the `Response`. Pure request shaping.
 * - It does **not** log — [Miruro.MiruroDebugInterceptor] logs every miruro
 *   request/response already.
 *
 * ## Ordering in the network interceptor chain
 *
 * Wired first in [Miruro.client]'s builder (followed only by
 * [Miruro.MiruroDebugInterceptor]); on the request leg it runs before the
 * debug interceptor, on the response leg it sees the response *last* — which
 * is fine because this interceptor does not read the response body at all.
 * Brotli/gzip response decompression is handled by the host app's
 * `okhttp3.CompressionInterceptor`, which sits even further towards the
 * connection, transparently decoding any `content-encoding: br` or
 * `content-encoding: gzip` body before it reaches our extractor or the debug
 * interceptor.
 *
 * This runs as a **network** interceptor, placing it after `BridgeInterceptor`
 * — that's required because Bridge interceptors run before network
 * interceptors, and Bridge pre-fills `Accept-Encoding: gzip` if the header is
 * absent. To advertise `gzip, deflate, br` instead, this interceptor must
 * **overwrite** that value (not merely fill-if-absent), which is only possible
 * on the network-interceptor side of the chain (Bridge's headers have already
 * been attached and are visible to `request.header(...)` here).
 *
 * ## Class visibility
 *
 * `internal` — visible only inside the `:src:en:miruro` module. Same package
 * as [Miruro], so it can read [Miruro.USER_AGENT] (an `internal const` in the
 * Miruro companion object) without any import.
 */
internal class MiruroBrowserFingerprintInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isPipeContext = original.url.encodedPath.removePrefix("/").startsWith("api/")
        val refererPresent = original.header("Referer") != null

        val builder = original.newBuilder()

        // ── OVERWRITE: WAF-fingerprint headers (both contexts) ──────────────
        // These MUST be the Chrome 148 desktop values so the WAF custom-rule
        // block does not trip, regardless of what the caller passed in.
        builder.header("Accept-Encoding", "gzip, deflate, br")
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("User-Agent", Miruro.USER_AGENT)
        builder.header("Sec-Ch-Ua", SEC_CH_UA)
        builder.header("Sec-Ch-Ua-Mobile", SEC_CH_UA_MOBILE)
        builder.header("Sec-Ch-Ua-Platform", SEC_CH_UA_PLATFORM)

        if (isPipeContext) {
            // Pipe = same-origin CORS XHR-style fetch.
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("Origin", "https://www.miruro.tv")

            // Fill-if-absent: body-shape defaults for an API fetch.
            if (original.header("Accept") == null) {
                builder.header("Accept", "*/*")
            }
            if (original.header("Referer") == null) {
                builder.header("Referer", "https://www.miruro.tv/")
            }
        } else {
            // Navigate context = toplevel GET (warm-up visit).
            builder.header("Sec-Fetch-Dest", "document")
            builder.header("Sec-Fetch-Mode", "navigate")
            // A cold warmup with no caller-supplied Referer mirrors a fresh
            // browser tab → real Chrome sends `Sec-Fetch-Site: none`. If a
            // Referer somehow IS present (it shouldn't be after the buildPipe
            // / warmup edits, but be correct anyway), use `same-origin`.
            builder.header("Sec-Fetch-Site", if (refererPresent) "same-origin" else "none")
            // Do NOT set Origin — real browsers omit it on toplevel GET.
            // Do NOT force a Referer — keep Sec-Fetch-Site: none correct.

            // Fill-if-absent: body-shape defaults for a toplevel navigation.
            if (original.header("Accept") == null) {
                builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
        }

        return chain.proceed(builder.build())
    }

    private companion object {
        // Brand claim exactly matching Miruro.USER_AGENT (Chrome 148 on Windows).
        // Real Chrome sends `"Not_A Brand";v="24"` (the underscore-A is literal,
        // not a typo — Chrome uses the brand-list rotation to detect header
        // tampering; the underscore variant is one of the rotated brand strings).
        private const val SEC_CH_UA = "\"Chromium\";v=\"148\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"148\""
        private const val SEC_CH_UA_MOBILE = "?0"
        private const val SEC_CH_UA_PLATFORM = "\"Windows\""
    }
}
