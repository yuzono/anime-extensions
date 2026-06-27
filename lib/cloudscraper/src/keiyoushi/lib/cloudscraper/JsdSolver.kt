package keiyoushi.lib.cloudscraper

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Solves Cloudflare JSD (JavaScript Detection) sensor challenges using QuickJS.
 *
 * The JSD challenge flow:
 * 1. Page contains `window.__CF$cv$params` with challenge parameters
 * 2. A script at the URL from `__CF$cv$params.s` contains the sensor logic
 * 3. The sensor script collects browser fingerprint data
 * 4. Fingerprint data is LZ-compressed and POSTed to `/cdn-cgi/challenge-platform/...`
 * 5. The response sets `cf_clearance` cookie
 *
 * This solver extracts the params, loads the JSD script into QuickJS with a
 * mocked browser environment, captures the POST that the script would make,
 * replicates it via OkHttp, and extracts the resulting cookie.
 *
 * @param client the OkHttpClient to use for HTTP requests
 * @param userAgent the User-Agent string — must match the one used for normal requests
 * @param maxRetries how many times to retry the challenge solve (default 2)
 * @param scriptTimeoutMs maximum time in ms for QuickJS script evaluation (default 10_000)
 */
class JsdSolver(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val maxRetries: Int = 2,
    private val scriptTimeoutMs: Long = 10_000L,
) {

    data class Result(
        val cfClearance: String?,
        val cookies: Map<String, String>,
    )

    /**
     * Solves a JSD challenge from the given page HTML.
     * Retries up to [maxRetries] times on transient failures.
     *
     * @param url the original request URL
     * @param rawCvParams the raw `__CF$cv$params` JSON string extracted from the page
     * @return [Result] with the cf_clearance cookie and any other cookies
     */
    fun solve(url: HttpUrl, rawCvParams: String): Result {
        val params = parseCvParams(rawCvParams)
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "Could not parse __CF\$cv$params",
            )

        Log.d(TAG, "JSD params: a=${params.a}, s=${params.s}, h=${params.h}")

        var lastError: CloudscraperException? = null

        for (attempt in 1..maxRetries) {
            try {
                return solveAttempt(url, params)
            } catch (e: CloudscraperException) {
                lastError = e
                Log.w(TAG, "JSD solve attempt $attempt/$maxRetries failed: ${e.message}")
                if (e.error == CloudscraperError.UNSOLVABLE_CHALLENGE) throw e
            }
        }

        throw lastError ?: CloudscraperException(
            CloudscraperError.SOLVE_FAILED,
            "JSD challenge solve failed after $maxRetries attempts",
        )
    }

    private fun solveAttempt(url: HttpUrl, params: CvParams): Result {
        // Fetch the JSD sensor script
        val scriptUrl = resolveUrl(url, params.s)
        val script = fetchScript(scriptUrl)
            ?: throw CloudscraperException(
                CloudscraperError.SCRIPT_FETCH_FAILED,
                "Failed to fetch JSD script from $scriptUrl",
            )

        Log.d(TAG, "JSD script fetched: ${script.length} chars")

        // Execute the script in QuickJS with browser environment
        val sensorData = executeInQuickJs(url, params, script)

        Log.d(TAG, "Sensor data generated: ${sensorData.length} chars")

        // POST the sensor data to the challenge endpoint
        val challengeUrl = resolveUrl(url, "/cdn-cgi/challenge-platform/${params.h}/greeting")
        val cookies = postChallenge(url, challengeUrl, params, sensorData)

        val cfClearance = cookies["cf_clearance"]
        if (cfClearance == null) {
            throw CloudscraperException(
                CloudscraperError.NO_CLEARANCE_COOKIE,
                "Challenge POST did not return cf_clearance",
            )
        }

        return Result(cfClearance = cfClearance, cookies = cookies)
    }

    // ── CvParams parsing ────────────────────────────────────────────

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses `window.__CF$cv$params` from the raw JS object string.
     * Uses kotlinx.serialization for proper JSON handling.
     */
    private fun parseCvParams(rawParams: String): CvParams? {
        return try {
            // Normalize JS object syntax to valid JSON:
            // 1. Single-quoted strings → double-quoted
            // 2. Unquoted keys → quoted keys
            var jsonStr = rawParams
                .replace("'", "\"")
                .replace(Regex("""(\w+)\s*:""")) { match ->
                    "\"${match.groupValues[1]}\":"
                }

            val obj = lenientJson.parseToJsonElement(jsonStr) as? JsonObject ?: return null

            val a = obj["a"]?.jsonPrimitive?.content ?: return null
            val s = obj["s"]?.jsonPrimitive?.content ?: return null
            val h = obj["h"]?.jsonPrimitive?.content ?: deriveH(a)

            CvParams(a = a, s = s, h = h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse __CF\$cv$params", e)
            // Fallback to regex-based parsing
            parseCvParamsFallback(rawParams)
        }
    }

    /**
     * Fallback regex-based CvParams parser for malformed JSON.
     */
    private fun parseCvParamsFallback(rawParams: String): CvParams? {
        var a: String? = null
        var s: String? = null
        var h: String? = null

        val kvRegex = """"?(\w+)"?\s*:\s*"([^"]*)"""".toRegex()
        for (match in kvRegex.findAll(rawParams)) {
            when (match.groupValues[1]) {
                "a" -> a = match.groupValues[2]
                "s" -> s = match.groupValues[2]
                "h" -> h = match.groupValues[2]
            }
        }

        if (a != null && s != null) {
            return CvParams(a = a, s = s, h = h ?: deriveH(a))
        }
        return null
    }

    private fun deriveH(a: String): String {
        // The 'h' param is usually present; derive a fallback from 'a' if missing
        return a.takeLast(8)
    }

    // ── Script fetching ──────────────────────────────────────────────

    private fun fetchScript(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", url)
                .header("Accept", "*/*")
                .build()

            scriptClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "JSD script fetch failed: ${response.code}")
                    return null
                }
                response.peekBody(MAX_SCRIPT_SIZE).string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSD script fetch error", e)
            null
        }
    }

    // ── QuickJS execution ───────────────────────────────────────────

    private fun executeInQuickJs(
        url: HttpUrl,
        params: CvParams,
        script: String,
    ): String {
        // Security: cap script size to prevent DoS
        if (script.length > MAX_SCRIPT_CHARS) {
            throw CloudscraperException(
                CloudscraperError.SCRIPT_TOO_LARGE,
                "JSD script exceeds ${MAX_SCRIPT_CHARS}char limit (${script.length} chars)",
            )
        }

        return withQuickJsTimeout(scriptTimeoutMs) { engine ->
            // Install browser environment shims
            BrowserEnvironment.install(engine, userAgent, url.toString())

            // Inject __CF$cv$params so the script can read them
            engine.evaluate(
                "window.__CF\$cv\$params = { a: ${jsonString(params.a)}, s: ${jsonString(params.s)}, h: ${jsonString(params.h)} };",
            )

            // Initialize sensor payload capture
            engine.evaluate("globalThis.__cf_sensor_payload = ''; globalThis.__cf_sensor_url = '';")

            // Run the JSD sensor script
            try {
                engine.evaluate(script)
            } catch (e: Exception) {
                Log.w(TAG, "JSD script execution error: ${e.message}")
                // Non-fatal — the script may have set up the payload before failing
            }

            // Run pending timers (simulates setTimeout callbacks)
            try {
                engine.evaluate("__cf_run_timers()")
            } catch (_: Exception) {
                // Timers may not be available
            }

            // Extract the captured payload
            val payload = engine.evaluate("globalThis.__cf_sensor_payload") as? String
            if (payload.isNullOrEmpty()) {
                throw CloudscraperException(
                    CloudscraperError.NO_SENSOR_PAYLOAD,
                    "JSD script did not produce a sensor payload. " +
                        "The script may require browser features not available in QuickJS.",
                )
            }

            payload
        }
    }

    // ── Challenge POST ───────────────────────────────────────────────

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun postChallenge(
        originalUrl: HttpUrl,
        challengeUrl: String,
        params: CvParams,
        sensorData: String,
    ): Map<String, String> {
        val request = Request.Builder()
            .url(challengeUrl)
            .header("User-Agent", userAgent)
            .header("Referer", originalUrl.toString())
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("CF-Challenge", params.a)
            .post(okhttp3.RequestBody.create(null, sensorData))
            .build()

        return noRedirectClient.newCall(request).execute().use { response ->
            val cookies = extractCookies(response.headers("Set-Cookie"))

            if (!response.isSuccessful && response.code !in 300..399) {
                Log.w(TAG, "Challenge POST returned ${response.code}")
            }

            // Follow redirect manually to capture cookies from the landing page
            if (response.code in 300..399) {
                response.header("Location")?.let { loc ->
                    val redirectUrl = resolveUrl(originalUrl, loc)
                    val redirectRequest = Request.Builder()
                        .url(redirectUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", originalUrl.toString())
                        .build()

                    try {
                        client.newCall(redirectRequest).execute().use { redirectResponse ->
                            cookies.putAll(extractCookies(redirectResponse.headers("Set-Cookie")))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to follow challenge redirect", e)
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
        val resolved = base.resolve(relative) ?: return relative
        return resolved.toString()
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        private const val TAG = "CloudScraper/JSD"
        private const val MAX_BODY = 5L * 1024 * 1024 // 5 MB
        private const val MAX_SCRIPT_SIZE = 5L * 1024 * 1024 // 5 MB
        private const val MAX_SCRIPT_CHARS = 2_000_000 // 2 MB

        /** Script fetch client with longer timeouts to handle slow CF CDN. */
        private val scriptClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Extracts name=value pairs from Set-Cookie headers.
         */
        internal fun extractCookies(setCookieHeaders: List<String>): Map<String, String> {
            val cookies = mutableMapOf<String, String>()
            for (header in setCookieHeaders) {
                val parts = header.split(";").firstOrNull()?.trim()?.split("=", limit = 2)
                if (parts != null && parts.size == 2) {
                    cookies[parts[0].trim()] = parts[1].trim()
                }
            }
            return cookies
        }
    }
}

/**
 * Parsed `__CF$cv$params` values.
 */
@Serializable
internal data class CvParams(
    /** Challenge token/ID. */
    val a: String,
    /** Relative URL to the JSD sensor script. */
    val s: String,
    /** Challenge platform hash. */
    val h: String,
)
