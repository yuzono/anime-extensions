package keiyoushi.lib.cloudscraper

import android.util.Log

/**
 * Detects the type of Cloudflare challenge from the HTML response body
 * and extracts challenge metadata needed for solving.
 *
 * Uses compiled regex patterns for efficient, robust parsing — no
 * naive `.contains()` checks that could match false positives.
 */
object ChallengeDetector {

    private val TAG = "CloudScraper/Detect"

    // ── Detection patterns ──────────────────────────────────────────

    /** JSD sensor challenge: `window.__CF$cv$params = {...}` */
    private val JSD_REGEX = Regex("""window\.__CF${'$'}cv${'$'}params\s*=\s*(\{[^}]+\})""")

    /** V2/V3 managed challenge: `/cdn-cgi/challenge-platform/` path in scripts */
    private val MANAGED_V2_REGEX = Regex("""/cdn-cgi/challenge-platform/""")

    /** V1 classic JS challenge: `/cdn-cgi/images/trace/jsch/` path */
    private val V1_TRACE_REGEX = Regex("""/cdn-cgi/images/trace/jsch/""")

    /** Legacy IUAM: `jschl_vc` or `jschl_answer` form fields */
    private val LEGACY_IUAM_REGEX = Regex("""name=["']jschl_vc["']""")

    /** Turnstile widget: `.cf-turnstile` class or `challenges.cloudflare.com/turnstile` */
    private val TURNSTILE_REGEX = Regex("""(?:class=["'][^"']*cf-turnstile|challenges\.cloudflare\.com/turnstile)""")

    /** CAPTCHA site key: `data-sitekey="..."` */
    private val SITEKEY_REGEX = Regex("""data-sitekey="([^"]+)"""")

    // ── Metadata extraction patterns ───────────────────────────────

    /** Challenge form action URL */
    private val FORM_ACTION_REGEX =
        Regex("""<form[^>]*class="challenge-form"[^>]*id="challenge-form"[^>]*action="([^"]+)"""")

    /** Legacy hidden inputs */
    private val JSCHL_VC_REGEX = Regex("""name="jschl_vc"\s+value="(\w+)"""")
    private val PASS_REGEX = Regex("""name="pass"\s+value="([^"]+)"""")

    /** r-value: old format `<input name="r" value="...">` */
    private val R_VALUE_OLD_REGEX = Regex("""name="r"\s+value="([^"]+)"""")

    /** r-value: modern format `r: '...'` in JS */
    private val R_VALUE_MODERN_REGEX = Regex("""r:\s*'([^']+)'""")

    /** V1 challenge script: `setTimeout(function(){ var s,t,o,p... a.value = ...` */
    private val V1_SCRIPT_REGEX =
        Regex("""setTimeout\(function\(\)\{\s+(var s,t,o,p,b,r,e,a,k,i,n,g,f.+?a\.value =.+?)\r?\n""")

    /** V1 answer expression: `a.value = <expr>.toFixed(10)` */
    private val V1_ANSWER_REGEX = Regex("""a\.value = (.+?)\.toFixed\(10\)""")

    /**
     * Analyzes the HTML of a Cloudflare challenge page.
     *
     * Detection priority (first match wins):
     * 1. **JSD** — silent sensor challenge, most solvable
     * 2. **Managed V2/V3** — modern JS challenge, may be solvable via QuickJS DOM shim
     * 3. **Legacy IUAM** — old math challenge, fully solvable
     * 4. **Turnstile** — interactive CAPTCHA widget, not solvable without browser
     *
     * @return [ChallengeInfo] with the type and all extracted metadata
     */
    fun detect(html: String): ChallengeInfo {
        // JSD sensor challenge — highest priority, most solvable
        JSD_REGEX.find(html)?.let { match ->
            val rawParams = match.groupValues[1]
            return ChallengeInfo(
                type = ChallengeType.JSD,
                rawCvParams = rawParams,
                rValue = extractRValue(html),
            )
        }

        // V2/V3 managed challenge — try DOM shim approach
        if (MANAGED_V2_REGEX.containsMatchIn(html)) {
            return ChallengeInfo(
                type = ChallengeType.MANAGED,
                formAction = FORM_ACTION_REGEX.find(html)?.groupValues?.get(1),
                jschlVc = JSCHL_VC_REGEX.find(html)?.groupValues?.get(1),
                pass = PASS_REGEX.find(html)?.groupValues?.get(1),
                rValue = extractRValue(html),
                v1Script = V1_SCRIPT_REGEX.find(html)?.groupValues?.get(1),
                v1AnswerExpr = V1_ANSWER_REGEX.find(html)?.groupValues?.get(1),
            )
        }

        // V1 classic JS challenge trace marker
        if (V1_TRACE_REGEX.containsMatchIn(html)) {
            return ChallengeInfo(
                type = ChallengeType.MANAGED,
                formAction = FORM_ACTION_REGEX.find(html)?.groupValues?.get(1),
                jschlVc = JSCHL_VC_REGEX.find(html)?.groupValues?.get(1),
                pass = PASS_REGEX.find(html)?.groupValues?.get(1),
                rValue = extractRValue(html),
                v1Script = V1_SCRIPT_REGEX.find(html)?.groupValues?.get(1),
                v1AnswerExpr = V1_ANSWER_REGEX.find(html)?.groupValues?.get(1),
            )
        }

        // Legacy IUAM — old-style jschl_answer form
        if (LEGACY_IUAM_REGEX.containsMatchIn(html)) {
            return ChallengeInfo(
                type = ChallengeType.LEGACY_IUAM,
                formAction = FORM_ACTION_REGEX.find(html)?.groupValues?.get(1),
                jschlVc = JSCHL_VC_REGEX.find(html)?.groupValues?.get(1),
                pass = PASS_REGEX.find(html)?.groupValues?.get(1),
                rValue = extractRValue(html),
                v1Script = V1_SCRIPT_REGEX.find(html)?.groupValues?.get(1),
                v1AnswerExpr = V1_ANSWER_REGEX.find(html)?.groupValues?.get(1),
            )
        }

        // Turnstile — interactive CAPTCHA widget
        if (TURNSTILE_REGEX.containsMatchIn(html)) {
            return ChallengeInfo(
                type = ChallengeType.TURNSTILE,
                sitekey = SITEKEY_REGEX.find(html)?.groupValues?.get(1),
            )
        }

        // Fallback: CF 403/503 but unclassifiable — assume managed
        Log.w(TAG, "Unclassifiable Cloudflare challenge page, falling back to MANAGED")
        return ChallengeInfo(type = ChallengeType.MANAGED)
    }

    private fun extractRValue(html: String): String? = R_VALUE_OLD_REGEX.find(html)?.groupValues?.get(1)
        ?: R_VALUE_MODERN_REGEX.find(html)?.groupValues?.get(1)
}

/**
 * Classification of Cloudflare challenge types.
 */
enum class ChallengeType {
    /** Silent JSD sensor challenge using `__CF$cv$params` — solvable without browser. */
    JSD,

    /** Legacy IUAM challenge with `jschl_answer` — solvable without browser. */
    LEGACY_IUAM,

    /** Managed challenge ("Just a moment…") — may be solvable via QuickJS DOM shim. */
    MANAGED,

    /** Turnstile interactive widget — requires human interaction or CAPTCHA solver. */
    TURNSTILE,
}

/**
 * Parsed challenge metadata extracted from the HTML page.
 * Only the fields relevant to the detected [ChallengeType] will be populated.
 */
data class ChallengeInfo(
    val type: ChallengeType,
    /** Raw `__CF$cv$params` object string (JSD only). */
    val rawCvParams: String? = null,
    /** Form action URL (MANAGED, LEGACY_IUAM). */
    val formAction: String? = null,
    /** `jschl_vc` verification code (MANAGED, LEGACY_IUAM). */
    val jschlVc: String? = null,
    /** `pass` form value (MANAGED, LEGACY_IUAM). */
    val pass: String? = null,
    /** `r` parameter value (MANAGED, LEGACY_IUAM). */
    val rValue: String? = null,
    /** V1 challenge script body (MANAGED if V1 trace detected). */
    val v1Script: String? = null,
    /** V1 answer expression before `.toFixed(10)` (MANAGED if V1). */
    val v1AnswerExpr: String? = null,
    /** Turnstile/CAPTCHA site key (TURNSTILE only). */
    val sitekey: String? = null,
)
