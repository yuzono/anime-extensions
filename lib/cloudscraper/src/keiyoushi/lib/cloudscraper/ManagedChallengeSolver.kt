package keiyoushi.lib.cloudscraper

import android.util.Log
import app.cash.quickjs.QuickJs
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Attempts to solve Cloudflare managed v2/v3 challenges ("Just a moment…")
 * using a QuickJS DOM shim, following the approach pioneered by go-cfscraper.
 *
 * The managed challenge flow:
 * 1. Page contains inline `<script>` blocks with `window._cf_chl_opt` or
 *    references to `/cdn-cgi/challenge-platform/...`
 * 2. The script runs in-browser, computing a `jschl_answer` and submitting
 *    a challenge form via POST
 * 3. The response sets `cf_clearance`
 *
 * In a headless environment, we:
 * 1. Extract all `<script>` blocks from the challenge page
 * 2. Run them in QuickJS with a comprehensive DOM shim (BrowserEnvironment)
 * 3. After a delay (simulating the 4s setTimeout CF uses), extract the answer
 *    from the dummy `document.getElementById('jschl-answer').value`
 * 4. Submit the challenge form with the computed answer
 *
 * **Success is not guaranteed.** Modern managed challenges increasingly
 * rely on browser features that are hard to shim (WebGL fingerprinting,
 * canvas rendering, etc.). This solver will throw [CloudscraperException]
 * with [CloudscraperError.UNSOLVABLE_CHALLENGE] when it cannot produce an answer.
 *
 * @param client the OkHttpClient to use for HTTP requests
 * @param userAgent the User-Agent string — must match the one used for normal requests
 */
class ManagedChallengeSolver(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    data class Result(
        val cfClearance: String?,
        val cookies: Map<String, String>,
    )

    /**
     * Attempts to solve a managed v2/v3 challenge.
     *
     * @param url the original request URL
     * @param page the full HTML of the challenge page
     * @param info challenge metadata from [ChallengeDetector]
     * @return [Result] with the cf_clearance cookie and any other cookies
     */
    fun solve(url: HttpUrl, page: String, info: ChallengeInfo): Result {
        // Extract all script blocks from the page
        val scriptBlocks = extractScriptBlocks(page)
        if (scriptBlocks.isEmpty()) {
            throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "No script blocks found in managed challenge page",
            )
        }

        // Security: check total script size
        val totalSize = scriptBlocks.sumOf { it.length }
        if (totalSize > MAX_TOTAL_SCRIPT_CHARS) {
            throw CloudscraperException(
                CloudscraperError.SCRIPT_TOO_LARGE,
                "Total challenge script size ($totalSize chars) exceeds limit ($MAX_TOTAL_SCRIPT_CHARS)",
            )
        }

        Log.d(TAG, "Extracted ${scriptBlocks.size} script blocks ($totalSize chars total)")

        // Try to compute the answer via QuickJS DOM shim
        val answer = computeAnswerViaShim(url, page, scriptBlocks, info)
            ?: throw CloudscraperException(
                CloudscraperError.UNSOLVABLE_CHALLENGE,
                "Managed challenge could not be solved via QuickJS DOM shim. " +
                    "Use WebView-based CloudflareInterceptor for this site.",
            )

        Log.d(TAG, "Managed challenge answer: $answer")

        // Submit the challenge form with the computed answer
        val cookies = submitChallenge(url, info, answer)

        return Result(
            cfClearance = cookies["cf_clearance"],
            cookies = cookies,
        )
    }

    // ── Script extraction ────────────────────────────────────────────

    /** Regex for script blocks including those with `window._cf_chl_opt` */
    private val v2ScriptRegex = Regex("""(?s)<script[^>]*>(.*?window\._cf_chl_opt.*?)</script>""")

    /** Generic script block extractor */
    private val scriptBlockRegex = Regex("""(?s)<script[^>]*>(.*?)</script>""")

    private fun extractScriptBlocks(page: String): List<String> {
        // First try to find the specific v2/v3 challenge scripts
        val v2Matches = v2ScriptRegex.findAll(page).map { it.groupValues[1] }.toList()
        if (v2Matches.isNotEmpty()) return v2Matches

        // Fallback: extract all script blocks that reference CDN CGI paths
        val allScripts = scriptBlockRegex.findAll(page).map { it.groupValues[1] }.toList()
        return allScripts.filter { script ->
            script.contains("/cdn-cgi/challenge-platform/") ||
                script.contains("challenge-form") ||
                script.contains("jschl") ||
                script.contains("_cf_chl_opt") ||
                script.contains("cf_chl_rc") ||
                script.contains("cf_chl_net")
        }
    }

    // ── QuickJS DOM shim ─────────────────────────────────────────────

    private fun computeAnswerViaShim(
        url: HttpUrl,
        page: String,
        scriptBlocks: List<String>,
        info: ChallengeInfo,
    ): String? {
        var capturedResult: String? = null
        withQuickJsTimeout(ENGINE_TIMEOUT_MS) { engine ->
            // Install full browser environment with the challenge page URL
            BrowserEnvironment.install(engine, userAgent, url.toString())

            // Inject challenge options if present in the page
            injectChallengeOptions(engine, page)

            // Stub the challenge form to prevent actual submission
            engine.evaluate(
                """
                |(function() {
                |    var form = document.getElementById('challenge-form');
                |    if (form) {
                |        form.submit = function() {
                |            globalThis.__cf_form_submitted = true;
                |        };
                |    }
                |    var form2 = document.getElementById('challenge-platform');
                |    if (form2) {
                |        form2.submit = function() {
                |            globalThis.__cf_form_submitted = true;
                |        };
                |    }
                |})();
                """.trimMargin(),
            )

            // Initialize answer capture vars
            engine.evaluate("globalThis.__cf_form_submitted = false;")

            // Execute all challenge script blocks in the same VM context
            var scriptErrors = 0
            for ((index, script) in scriptBlocks.withIndex()) {
                try {
                    // Replace document.getElementById('challenge-form') references
                    // with a neutral stub (prevents null errors)
                    val cleanedScript = script
                        .replace(
                            """document.getElementById('challenge-form');""",
                            "({});",
                        )
                        .replace(
                            """document.getElementById("challenge-form");""",
                            "({});",
                        )

                    engine.evaluate(cleanedScript)
                } catch (e: Exception) {
                    scriptErrors++
                    Log.d(TAG, "Script block $index failed: ${e.message?.take(100)}")
                    // Continue — one failing block doesn't mean all fail
                }
            }

            Log.d(TAG, "Executed ${scriptBlocks.size} scripts ($scriptErrors errors)")

            // Let timers fire — CF often uses setTimeout(fn, 4000)
            try {
                engine.evaluate("__cf_run_timers()")
            } catch (_: Exception) { }

            // Try to extract the answer from the document element
            try {
                val answer = engine.evaluate(
                    "var _el = document.getElementById('jschl-answer') || document.getElementById('jschl_answer'); _el ? _el.value : '';",
                ) as? String

                if (!answer.isNullOrEmpty() && answer != "undefined" && answer != "0") {
                    capturedResult = answer
                }
            } catch (_: Exception) { }

            // Try checking if V1 expression is available
            info.v1AnswerExpr?.let { expr ->
                if (capturedResult != null) return@let
                try {
                    val safeDomain = url.host.replace("'", "\\'").replace("\n", "").replace("\r", "")
                    val result = engine.evaluate(
                        """
                        |var t = '$safeDomain';
                        |($expr).toFixed(10);
                        """.trimMargin(),
                    ) as? String

                    if (!result.isNullOrEmpty() && result != "undefined") {
                        capturedResult = result
                    }
                } catch (_: Exception) { }
            }

            // Last resort: check if the form was "submitted" and check cookie value
            if (capturedResult == null) {
                try {
                    val cookieVal = engine.evaluate("document.cookie") as? String
                    if (cookieVal != null && cookieVal.contains("cf_clearance")) {
                        val match = Regex("""cf_clearance=([^;]+)""").find(cookieVal)
                        if (match != null) {
                            // We got a cf_clearance from the script directly!
                            capturedResult = "cookie:${match.groupValues[1]}"
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return capturedResult
    }

    /**
     * Extracts and injects `window._cf_chl_opt` from the page if present.
     * These options are used by the challenge script to configure itself.
     */
    private fun injectChallengeOptions(engine: QuickJs, page: String) {
        val optRegex = Regex("""window\._cf_chl_opt\s*=\s*(\{[^}]+\})""")
        optRegex.find(page)?.let { match ->
            try {
                val normalized = match.groupValues[1]
                    .replace("'", "\"")
                    .replace(Regex("""(\w+)\s*:""")) { """${it.groupValues[1]}": """ }
                engine.evaluate("window._cf_chl_opt = $normalized;")
            } catch (e: Exception) {
                Log.d(TAG, "Failed to inject _cf_chl_opt: ${e.message}")
            }
        }
    }

    // ── Challenge submission ─────────────────────────────────────────

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun submitChallenge(
        originalUrl: HttpUrl,
        info: ChallengeInfo,
        jschlAnswer: String,
    ): Map<String, String> {
        // If the answer is a cookie captured from the script, return it directly
        if (jschlAnswer.startsWith("cookie:")) {
            val cookieValue = jschlAnswer.removePrefix("cookie:")
            return mapOf("cf_clearance" to cookieValue)
        }

        // Build form submission
        val submitUrl = info.formAction?.let { action ->
            resolveUrl(originalUrl, action)
        } ?: buildDefaultSubmitUrl(originalUrl)

        val formData = buildString {
            info.rValue?.let { r ->
                append("r=")
                append(encodeURIComponent(r))
                append("&")
            }
            info.jschlVc?.let { vc ->
                append("jschl_vc=")
                append(encodeURIComponent(vc))
                append("&")
            }
            info.pass?.let { p ->
                append("pass=")
                append(encodeURIComponent(p))
                append("&")
            }
            append("jschl_answer=")
            append(encodeURIComponent(jschlAnswer))
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
                    val redirectUrl = resolveUrl(originalUrl, loc)
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
                        Log.w(TAG, "Failed to follow managed challenge redirect", e)
                    }
                }
            }

            cookies
        }
    }

    private fun buildDefaultSubmitUrl(url: HttpUrl): String = "${url.scheme}://${url.host}$MODERN_CHALLENGE_SUBMIT_PATH"

    // ── Helpers ──────────────────────────────────────────────────────

    private fun resolveUrl(base: HttpUrl, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        val resolved = base.resolve(relative) ?: return relative
        return resolved.toString()
    }

    private fun encodeURIComponent(s: String): String = URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("%7E", "~")

    companion object {
        private const val TAG = "CloudScraper/Managed"
        private const val ENGINE_TIMEOUT_MS = 15_000L
        private const val MAX_TOTAL_SCRIPT_CHARS = 2_000_000
        private const val MODERN_CHALLENGE_SUBMIT_PATH = "/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1"
    }
}
