package keiyoushi.lib.cloudscraper

import android.util.Log

/**
 * Detects the type of Cloudflare challenge from the HTML response body
 * and extracts the metadata needed for the JSD fast path.
 *
 * The taxonomy is deliberately binary:
 * - **JSD** — silent `__CF$cv$params` sensor challenge. The headless JSD
 *   solver (`JsdSolver`) attempts this; on failure the hybrid
 *   `CloudScraperInterceptor` falls back to WebView.
 * - **UNSOLVABLE** — everything else (managed v2/v3 "Just a moment…",
 *   legacy IUAM, Turnstile widget, V1 classic JS trace, or an
 *   unclassifiable 403/503 from a CF server). The interceptor routes
 *   these directly to the WebView fallback.
 *
 * The old multi-type taxonomy (MANAGED / LEGACY_IUAM / TURNSTILE) carried
 * metadata that only the dead `ManagedChallengeSolver` and
 * `LegacyIuamSolver` consumed. Both solvers were removed because headless
 * QuickJS cannot reproduce the fingerprint signals modern CF collects
 * (real canvas/WebGL rendering, pointer events, focus state). Keeping
 * those fields would be dead weight and a maintenance trap.
 *
 * Detection uses compiled regex patterns for efficient, robust parsing —
 * no naive `.contains()` checks that could match false positives.
 */
object ChallengeDetector {

    private val TAG = "CloudScraper/Detect"

    // ── JSD detection ────────────────────────────────────────────────

    /** JSD sensor challenge: `window.__CF$cv$params = {...}` */
    private val JSD_REGEX = Regex("window\\.__CF\\\$cv\\\$params\\s*=\\s*(\\{[^}]+\\})")

    /** JSD main.js script URL reference: `src='...main.js...'` */
    private val JSD_MAINJS_REGEX = Regex("""src=['"]([^'"]*main\.js[^'"]*)['"]""")

    /**
     * Analyzes the HTML of a Cloudflare challenge page.
     *
     * @return [ChallengeInfo] with the detected [ChallengeType] and, for JSD,
     *   the raw `__CF$cv$params` string needed by [JsdSolver]
     */
    fun detect(html: String): ChallengeInfo {
        // Reject Cloudflare WAF block pages early. Blocks look like JSD
        // challenges at the header level (403 + Server: cloudflare) and may
        // even contain __CF\$cv\$params (injected into a hidden iframe for
        // analytics). They are NOT solvable — no challenge cookie clears a
        // WAF block rule — and the synthetic-param path below would
        // incorrectly classify them as JSD.
        if (html.contains("cf-error-details") || html.contains("have been blocked")) {
            Log.d(TAG, "Cloudflare block page detected — not a solvable challenge, returning UNSOLVABLE")
            return ChallengeInfo(type = ChallengeType.UNSOLVABLE)
        }

        // JSD — the only challenge type we can attempt headlessly.
        JSD_REGEX.find(html)?.let { match ->
            val rawParams = match.groupValues[1]
            if (rawParams.contains("\"a\"") || rawParams.contains("'a'") || rawParams.contains("a:")) {
                return ChallengeInfo(
                    type = ChallengeType.JSD,
                    rawCvParams = rawParams,
                )
            }
            // Variant that only carries `r` / `t` — rebuild synthetic a/s/h params.
            if (rawParams.contains("\"r\"") || rawParams.contains("'r'") || rawParams.contains("r:")) {
                val mainJsUrl = JSD_MAINJS_REGEX.find(html)?.groupValues?.get(1)
                    ?: "/cdn-cgi/challenge-platform/scripts/jsd/main.js"
                val syntheticParams = buildSyntheticCvParams(rawParams, mainJsUrl)
                if (syntheticParams != null) {
                    return ChallengeInfo(
                        type = ChallengeType.JSD,
                        rawCvParams = syntheticParams,
                    )
                }
            }
        }

        // Everything else routes to WebView via UNSOLVABLE. We no longer
        // try to distinguish managed v2/v3 / legacy IUAM / Turnstile from
        // each other — none of those distinctions are actionable in the
        // headless path, and the WebView fallback doesn't need them.
        Log.d(TAG, "Non-JSD challenge (managed/turnstile/legacy) — deferring to WebView fallback")
        return ChallengeInfo(type = ChallengeType.UNSOLVABLE)
    }

    /**
     * Builds synthetic JSD params from an `r`/`t` variant __CF$cv$params object.
     * Maps: `r` → `a`, `mainJsUrl` → `s`, derived `h` from `a`.
     * Returns a JSON string parseable by JsdSolver.parseCvParams, or null if `r` not found.
     */
    private fun buildSyntheticCvParams(rawParams: String, mainJsUrl: String): String? {
        val rMatch = Regex("""['"]?r['"]?\s*:\s*['"]([^'"]+)['"]""").find(rawParams) ?: return null
        val a = rMatch.groupValues[1]
        val h = a.takeLast(8)
        return """{"a":"$a","s":"$mainJsUrl","h":"$h"}"""
    }
}

/**
 * Cloudflare challenge classification. Binary by design:
 * - [JSD] — solvable headlessly via [JsdSolver] (fast path)
 * - [UNSOLVABLE] — routes to the WebView fallback
 */
enum class ChallengeType {
    /** Silent JSD sensor challenge using `__CF$cv$params` — solvable without browser. */
    JSD,

    /**
     * Anything we cannot solve headlessly: managed v2/v3 ("Just a moment…"),
     * legacy IUAM, Turnstile widget, V1 classic JS trace, or an unclassifiable
     * CF 403/503. The interceptor falls back to a WebView solve.
     */
    UNSOLVABLE,
}

/**
 * Parsed challenge metadata extracted from the HTML page.
 *
 * Only [rawCvParams] is populated — it is the sole field the surviving
 * JSD path consumes. All other fields (form action, `jschl_vc`, `pass`,
 * `r`-value, V1 script/answer expressions, Turnstile site key) were
 * stripped because they were only consumed by the removed managed and
 * legacy solvers.
 */
data class ChallengeInfo(
    val type: ChallengeType,
    /** Raw `__CF$cv$params` object string (JSD only). */
    val rawCvParams: String? = null,
)
