package keiyoushi.lib.cloudscraper

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Solves legacy Cloudflare IUAM (I'm Under Attack Mode) challenges and
 * V1 classic JS challenges.
 *
 * Legacy IUAM challenges present a page with a `<form id="challenge-form">`
 * containing hidden inputs like `jschl_vc` (verification code) and
 * `jschl_answer` (a math expression result). The answer is computed from
 * a JavaScript expression in the page plus the domain name, and the form
 * is then submitted with a `pass` parameter.
 *
 * V1 challenges use a similar math-based computation but with a
 * `setTimeout(function(){...})` pattern that builds the answer.
 *
 * Both are rare on modern Cloudflare but still occasionally appear
 * on older CF configurations.
 *
 * @param client the OkHttpClient to use for HTTP requests
 * @param userAgent the User-Agent string — must match the one used for normal requests
 */
class LegacyIuamSolver(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    data class Result(
        val cfClearance: String?,
        val cookies: Map<String, String>,
    )

    /**
     * Solves a legacy IUAM or V1 challenge using pre-extracted metadata.
     *
     * @param url the original request URL
     * @param info challenge metadata from [ChallengeDetector]
     */
    fun solve(url: HttpUrl, info: ChallengeInfo): Result {
        // Try V1 approach first if we have the script/expression
        if (info.v1Script != null && info.v1AnswerExpr != null) {
            return solveV1(url, info)
        }

        // Fall back to legacy IUAM approach
        return solveLegacyIuam(url, info)
    }

    // ── V1 challenge ─────────────────────────────────────────────────

    private fun solveV1(url: HttpUrl, info: ChallengeInfo): Result {
        val answer = computeV1Answer(url, info.v1AnswerExpr!!)
            ?: throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "Could not compute V1 challenge answer",
            )

        Log.d(TAG, "V1 answer computed: $answer")

        val cookies = submitChallenge(url, info, answer.toString())

        return Result(
            cfClearance = cookies["cf_clearance"],
            cookies = cookies,
        )
    }

    /**
     * Computes the V1 challenge answer by evaluating the final expression
     * with the domain name, then formatting to 10 decimal places.
     *
     * Pattern from go-cfscraper:
     *   var t = '<domain>';
     *   var result = (<expression>).toFixed(10);
     */
    private fun computeV1Answer(url: HttpUrl, expression: String): Double? {
        var capturedResult: Double? = null
        withQuickJsTimeout(SCRIPT_TIMEOUT_MS) { engine ->
            engine.evaluate("globalThis.console = { log: function(){}, warn: function(){}, error: function(){} };")

            // Domain must be sanitized to prevent JS injection
            val safeDomain = url.host
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "")
                .replace("\r", "")

            val fullScript = """
                |var t = '$safeDomain';
                |var result = ($expression).toFixed(10);
                |result;
            """.trimMargin()

            try {
                val rawResult = engine.evaluate(fullScript)
                capturedResult = when (rawResult) {
                    is Number -> rawResult.toDouble()
                    is String -> rawResult.toDoubleOrNull()
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "V1 JS evaluation failed", e)
            }
        }
        return capturedResult
    }

    // ── Legacy IUAM challenge ────────────────────────────────────────

    private fun solveLegacyIuam(url: HttpUrl, info: ChallengeInfo): Result {
        val vc = info.jschlVc
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "Could not extract jschl_vc from page",
            )
        val pass = info.pass
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "Could not extract pass from page",
            )

        val answer = computeLegacyAnswer(url, info)
            ?: throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "Could not compute jschl_answer",
            )

        Log.d(TAG, "Legacy IUAM: vc=$vc, answer=$answer")

        val cookies = submitChallenge(url, info, answer)

        return Result(
            cfClearance = cookies["cf_clearance"],
            cookies = cookies,
        )
    }

    /**
     * Computes the jschl_answer by evaluating the challenge JavaScript in QuickJS.
     * Uses the full browser environment to handle obfuscated scripts.
     */
    private fun computeLegacyAnswer(url: HttpUrl, info: ChallengeInfo): String? {
        var capturedResult: String? = null
        withQuickJsTimeout(SCRIPT_TIMEOUT_MS) { engine ->
            // Install full browser environment
            BrowserEnvironment.install(engine, userAgent, url.toString())

            try {
                // The challenge script uses document.getElementById to set the answer.
                // Our document shim already handles 'jschl-answer' / 'jschl_answer' IDs.
                // We also need to intercept any .submit() calls to prevent navigation.

                // The script may reference the challenge form directly
                engine.evaluate(
                    """
                    |var __cf_submit_called = false;
                    |var __cf_original_form = document.getElementById('challenge-form');
                    |if (__cf_original_form) {
                    |    __cf_original_form.submit = function() {
                    |        __cf_submit_called = true;
                    |    };
                    |}
                    """.trimMargin(),
                )

                // Find and evaluate the challenge script from the page source
                // (we need to re-extract it since info doesn't carry the full page)
                // The caller should have the full page, but we work with what we have
                info.v1Script?.let { script ->
                    // Clean up the script — remove submit/redirect logic
                    val cleanScript = script
                        .replace(Regex("""\.submit\(\)"""), "")
                        .replace(Regex("""window\.location\.href\s*=.*"""), "")
                        .replace(Regex("""document\.location\s*=.*"""), "")

                    engine.evaluate(cleanScript)
                }

                // Extract answer from the document element
                val answerEl = engine.evaluate(
                    "var _el = document.getElementById('jschl-answer') || document.getElementById('jschl_answer'); _el ? _el.value : '';",
                ) as? String

                if (!answerEl.isNullOrEmpty() && answerEl != "0" && answerEl != "undefined") {
                    // The answer is typically the computed value + domain name length
                    capturedResult = try {
                        val raw = answerEl.toDouble()
                        // Add domain length (common CF pattern: answer = computed + host.length)
                        // But the script should have handled this — only add if clearly missing
                        raw.toString()
                    } catch (_: NumberFormatException) {
                        answerEl
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy IUAM JS evaluation failed", e)
            }
        }
        return capturedResult
    }

    // ── Challenge submission ──────────────────────────────────────────

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Submits the challenge form and extracts cookies.
     * Handles both V1 and legacy IUAM forms.
     */
    private fun submitChallenge(
        originalUrl: HttpUrl,
        info: ChallengeInfo,
        jschlAnswer: String,
    ): Map<String, String> {
        // Determine submit URL
        val submitUrl = info.formAction?.let { action ->
            resolveUrl(originalUrl, action)
        } ?: run {
            // Default: /cdn-cgi/l/chk_jschl
            "${originalUrl.scheme}://${originalUrl.host}/cdn-cgi/l/chk_jschl"
        }

        // Build form data
        val formData = buildString {
            append("jschl_vc=")
            append(encodeURIComponent(info.jschlVc ?: ""))
            append("&pass=")
            append(encodeURIComponent(info.pass ?: ""))
            append("&jschl_answer=")
            append(encodeURIComponent(jschlAnswer))

            // Include r-value if present
            info.rValue?.let { r ->
                append("&r=")
                append(encodeURIComponent(r))
            }
        }

        val request = Request.Builder()
            .url(submitUrl)
            .header("User-Agent", userAgent)
            .header("Referer", originalUrl.toString())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(okhttp3.RequestBody.create(null, formData))
            .build()

        return noRedirectClient.newCall(request).execute().use { response ->
            val cookies = mutableMapOf<String, String>()
            JsdSolver.extractCookies(response.headers("Set-Cookie")).forEach { (k, v) ->
                cookies[k] = v
            }

            // Follow redirect manually to capture additional cookies
            if (response.code in 300..399) {
                response.header("Location")?.let { loc ->
                    val redirectUrl = if (loc.startsWith("http")) loc else "${originalUrl.scheme}://$loc"
                    val redirectRequest = Request.Builder()
                        .url(redirectUrl)
                        .header("User-Agent", userAgent)
                        .build()

                    try {
                        client.newCall(redirectRequest).execute().use { redirectResponse ->
                            JsdSolver.extractCookies(redirectResponse.headers("Set-Cookie")).forEach { (k, v) ->
                                cookies[k] = v
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to follow legacy IUAM redirect", e)
                    }
                }
            }

            cookies
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun resolveUrl(base: HttpUrl, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        if (relative.startsWith("javascript:") || relative.startsWith("data:")) {
            return base.toString()
        }
        val resolved = base.resolve(relative) ?: return relative
        return resolved.toString()
    }

    private fun encodeURIComponent(s: String): String = URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("%7E", "~")

    companion object {
        private const val TAG = "CloudScraper/LegacyIUAM"
        private const val SCRIPT_TIMEOUT_MS = 10_000L
    }
}
