package keiyoushi.lib.cloudscraper

import android.util.Log
import app.cash.quickjs.QuickJs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private val scriptTimeoutMs: Long = 8_000L,
) {

    data class Result(
        val cfClearance: String?,
        val cookies: Map<String, String>,
    )

    /**
     * Result of QuickJS script execution.
     * Contains the sensor payload and the challenge endpoint URL the script POSTed to.
     */
    private data class SensorResult(
        val payload: String,
        /** The challenge endpoint URL captured from the script's XHR. Null if unavailable. */
        val challengeUrl: String?,
    )

    /**
     * Solves a JSD challenge from the given page HTML.
     * Retries up to [maxRetries] times on transient failures.
     *
     * @param url the original request URL
     * @param rawCvParams the raw `__CF$cv$params` JSON string extracted from the page
     * @return [Result] with the cf_clearance cookie and any other cookies
     */
    fun solve(
        url: HttpUrl,
        rawCvParams: String,
        initialCookies: Map<String, String> = emptyMap(),
    ): Result {
        val params = parseCvParams(rawCvParams)
            ?: throw CloudscraperException(
                CloudscraperError.CHALLENGE_PARSE_FAILED,
                "Could not parse __CF${'$'}cv${'$'}params",
            )

        Log.d(TAG, "JSD params: a=${params.a}, s=${params.s}, h=${params.h}")

        var lastError: CloudscraperException? = null

        for (attempt in 1..maxRetries) {
            try {
                return solveAttempt(url, params, initialCookies)
            } catch (e: CloudscraperException) {
                lastError = e
                Log.w(TAG, "JSD solve attempt $attempt/$maxRetries failed: ${e.message}")
                // RETRYABLE_ERRORS excludes script-content errors (NO_SENSOR_PAYLOAD,
                // NO_CLEARANCE_COOKIE, etc.) — those are deterministic for a fixed
                // script+env, so retrying only burns another scriptTimeoutMs.
                if (e.error !in RETRYABLE_ERRORS) throw e
            }
        }

        throw lastError ?: CloudscraperException(
            CloudscraperError.SOLVE_FAILED,
            "JSD challenge solve failed after $maxRetries attempts",
        )
    }

    private fun solveAttempt(
        url: HttpUrl,
        params: CvParams,
        initialCookies: Map<String, String> = emptyMap(),
    ): Result {
        // Fetch the JSD sensor script
        val scriptUrl = resolveUrl(url, params.s)
        val script = fetchScript(scriptUrl, originalUrl = url, initialCookies = initialCookies)
            ?: throw CloudscraperException(
                CloudscraperError.SCRIPT_FETCH_FAILED,
                "Failed to fetch JSD script from $scriptUrl",
            )

        Log.d(TAG, "JSD script fetched: ${script.length} chars")

        // Execute the script in QuickJS with browser environment
        val sensorResult = executeInQuickJs(url, params, script)

        Log.d(TAG, "Sensor data generated: ${sensorResult.payload.length} chars")

        // The script-captured URL (from the BrowserEnvironment XHR/fetch/sendBeacon
        // shim) is authoritative when present — it's whatever path the JSD
        // script actually wanted to POST to. Resolve it against the page URL
        // because the shim stores `this._u` from `XMLHttpRequest.open(m, u, ...)`
        // verbatim, which is typically a relative path like
        // `/cdn-cgi/challenge-platform/h/<h>/orch/jsd/<a>` on the same origin.
        val challengeUrl = when {
            sensorResult.challengeUrl.isNullOrBlank() -> {
                // Fallback: modern CF JSD endpoint shape. The previous
                // `/orch/jsd/v1` constant 404s on current Cloudflare — the
                // platform now expects the challenge token `a` in the path
                // segment after `jsd`, not a fixed `v1` identifier.
                resolveUrl(
                    url,
                    "/cdn-cgi/challenge-platform/h/${params.h}/orch/jsd/${params.a}",
                )
            }
            else -> resolveUrl(url, sensorResult.challengeUrl)
        }
        Log.d(TAG, "Challenge POST URL: $challengeUrl (captured=${sensorResult.challengeUrl != null})")

        val result = postChallenge(
            originalUrl = url,
            challengeUrl = challengeUrl,
            params = params,
            sensorData = sensorResult.payload,
            initialCookies = initialCookies,
        )

        if (result.cfClearance != null) {
            return Result(cfClearance = result.cfClearance, cookies = result.cookies)
        }

        if (result.httpCode in 300..399) {
            Log.d(TAG, "Challenge returned ${result.httpCode} — may have progressed")
        }

        // 4xx = CF rejected the script's output → hard fail, don't retry.
        if (result.httpCode in 400..499) {
            throw CloudscraperException(
                CloudscraperError.SOLVE_FAILED,
                "Challenge POST to $challengeUrl returned ${result.httpCode} " +
                    "(capturedUrl=${sensorResult.challengeUrl ?: "null"}, fallbackUsed=${sensorResult.challengeUrl.isNullOrBlank()}), " +
                    "Cloudflare rejected sensor data",
            )
        }

        throw CloudscraperException(
            CloudscraperError.NO_CLEARANCE_COOKIE,
            "Challenge POST to $challengeUrl returned ${result.httpCode} " +
                "(capturedUrl=${sensorResult.challengeUrl ?: "null"}, fallbackUsed=${sensorResult.challengeUrl.isNullOrBlank()}), " +
                "no cf_clearance",
        )
    }

    /** Structured result from a single challenge POST attempt. */
    private data class PostResult(
        val cfClearance: String?,
        val cookies: Map<String, String>,
        val httpCode: Int,
    )

    // ── CvParams parsing ────────────────────────────────────────────

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses `window.__CF$cv$params` from the raw JS object string.
     * Uses kotlinx.serialization for proper JSON handling.
     */
    internal fun parseCvParams(rawParams: String): CvParams? {
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
            Log.w(TAG, "Failed to parse __CF${'$'}cv${'$'}params", e)
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

    private fun fetchScript(
        scriptUrl: String,
        originalUrl: HttpUrl? = null,
        initialCookies: Map<String, String> = emptyMap(),
    ): String? {
        return try {
            val requestBuilder = Request.Builder()
                .url(scriptUrl)
                .header("User-Agent", userAgent)
                .header("Referer", originalUrl?.toString() ?: scriptUrl)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.5")

            // Forward challenge session cookies so Cloudflare can associate
            // the script fetch with the same session that triggered the challenge.
            if (initialCookies.isNotEmpty()) {
                val cookieHeader = initialCookies.entries.joinToString("; ") { (name, value) ->
                    "$name=$value"
                }
                requestBuilder.header("Cookie", cookieHeader)
            }

            val request = requestBuilder.build()

            // Use the same client for script fetch as the challenge POST so
            // Cloudflare sees a consistent TLS fingerprint across both requests.
            // The challenge ID is embedded in the script at serving time and is
            // tied to the requesting connection's TLS fingerprint — if we fetch
            // with a different client, the embedded ID won't match any active
            // challenge for the client doing the POST.
            client.newCall(request).execute().use { response ->
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

    /**
     * Extracts deobfuscation pieces from the JSD script.
     *
     * Returns pieces for synthetic deobfuscation, handling variable
     * function names (R/c, D/G) and offsets (355, 108) across CF variants.
     */
    internal data class DeobfuscationPieces(
        val dString: String,
        val rName: String,
        val rFuncDecl: String,
        val rotationCall: String,
        val dName: String,
        val offset: Int,
    )

    /**
     * Extracts pieces from a JSD script for synthetic deobfuscation.
     * Handles `function D(jW){return jW=\`...\`.split(';')}`,
     * `function G(FB){...}\`.split(\`;\`)}`, `function E(IZ){...}` variants,
     * with R-function (W, R, c, etc.) anywhere in the script.
     */
    internal fun extractDeobfuscationPieces(script: String): DeobfuscationPieces? {
        // 1. Find the D-array function — any function with `return PARAM=\`...\`.split(separator)`
        //    Accept ';' or ";" or `;` as separator
        val dMatch = D_FUNC_REGEX.find(script) ?: run {
            Log.d(TAG, "No D-array function found in script")
            return null
        }
        val dName = dMatch.groupValues[1]
        val dString = dMatch.groupValues[3]
        Log.d(TAG, "D-function match: name=$dName, fullMatch=${dMatch.value.take(120)}, dString.len=${dString.length}, dString.preview=${dString.take(80)}")

        // 2. Find the R-like function anywhere in the script.
        //    Pattern: `function NAME(PARAMS){return PARAM=PARAM-OFFSET, DNAME()...`
        val rFuncRegex = Regex(
            """function\s+(\w+)\s*\([^)]*\)\s*\{return\s+(\w+)\s*=\s*\2\s*-\s*(\d+)\s*,[^}]*$dName\s*\(""",
        )
        val rMatch = rFuncRegex.find(script) ?: run {
            Log.w(TAG, "No R-function referencing '$dName' found in script")
            return null
        }
        val rName = rMatch.groupValues[1]
        val offset = rMatch.groupValues[3].toIntOrNull() ?: return null

        val rFuncStart = rMatch.range.first
        val rFuncEnd = findMatchingBrace(script, rFuncStart)
        if (rFuncEnd < 0) return null
        val rFuncDecl = script.substring(rFuncStart, rFuncEnd + 1)

        // 3. Extract the rotation call: `function(...){...}(DNAME,CHECKSUM)`
        //    CF variants may wrap it as `(function(...){...})(D,CC)` or bare
        //    `function(...){...}(D,CC)`. Search the entire script for any
        //    function(...){...} and filter by the suffix (DNAME,CHKSUM).
        val rotCandidates = ROT_CALL_REGEX.findAll(script).toList()
        Log.d(TAG, "Rotation candidates found: ${rotCandidates.size}")
        var rotationCall: String? = null
        for ((idx, candidate) in rotCandidates.withIndex()) {
            val braceIdx = candidate.value.indexOf('{')
            if (braceIdx < 0) continue
            val bodyEnd = findMatchingBrace(script, candidate.range.first + braceIdx)
            if (bodyEnd < 0) continue
            val afterClose = script.substring(bodyEnd + 1)
            val callMatch = Regex("""\s*\(\s*$dName\s*,(\s*\d+\s*)\)""").find(afterClose)
            if (callMatch != null) {
                rotationCall = script.substring(
                    candidate.range.first,
                    bodyEnd + 1 + callMatch.range.last + 1,
                )
                Log.d(TAG, "Rotation call FOUND at candidate $idx, checksum=${callMatch.groupValues[1]}")
                break
            }
        }
        if (rotationCall == null) {
            Log.w(TAG, "No rotation call referencing '$dName' found in script (screened ${rotCandidates.size} candidates)")
            return null
        }

        return DeobfuscationPieces(
            dString = dString,
            rName = rName,
            rFuncDecl = rFuncDecl,
            rotationCall = rotationCall,
            dName = dName,
            offset = offset,
        )
    }

    /**
     * Finds the position of the matching closing brace `}` for a brace at [braceStart].
     * Returns -1 if no matching brace found.
     */
    private fun findMatchingBrace(s: String, braceStart: Int): Int {
        var depth = 0
        var i = braceStart
        var foundOpen = false
        while (i < s.length) {
            when (s[i]) {
                '{' -> {
                    depth++
                    foundOpen = true
                }
                '}' -> {
                    depth--
                    if (foundOpen && depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Runs the JSD script's D-array deobfuscation to discover which window
     * properties the script checks, then pre-populates them so the script
     * can find its required configuration.
     *
     * Works dynamically across CF variants by extracting the D-array function,
     * R-like function, and rotation call from the script itself rather than
     * assuming specific function names or offsets.
     */
    private fun resolveAndPrepopulate(engine: app.cash.quickjs.QuickJs, script: String) {
        val pieces = extractDeobfuscationPieces(script)
        if (pieces == null) {
            Log.d(TAG, "Could not extract deobfuscation pieces, skipping dependency resolution")
            return
        }

        Log.d(TAG, "D-string length: ${pieces.dString.length}, offset: ${pieces.offset}, dName: ${pieces.dName}")

        val dStringEscaped = pieces.dString
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        // Define the R-like and D-like functions in QuickJS, then run the rotation.
        // The rotation call may be `(function(...){...})(D,CHK)` or bare
        // `function(...){...}(D,CHK)`. Bare anonymous function expressions
        // cause "function name expected" when evaluated standalone, so wrap
        // in parens to force expression context.
        val rotationExpr = if (pieces.rotationCall.startsWith("(")) {
            pieces.rotationCall
        } else {
            Log.d(TAG, "Wrapping rotation call in parentheses for standalone evaluation")
            "(${pieces.rotationCall})"
        }
        engine.evaluate(
            """
            ${pieces.rFuncDecl}
            function ${pieces.dName}(p){return p="$dStringEscaped".split(';'),${pieces.dName}=function(){return p},${pieces.dName}()}
            $rotationExpr
            """.trimIndent(),
        )

        // Dump the rotated D-array and pre-populate all valid property names
        val tsExpr = "__cf_btoa(String(Math.floor(Date.now()/1000) - 10))"
        engine.evaluate(
            """
            var __cf_arr = ${pieces.dName}();
            var __cf_ts = $tsExpr;
            for(var __cf_i=0;__cf_i<__cf_arr.length;__cf_i++){
                var __cf_n=__cf_arr[__cf_i];
                if(typeof __cf_n!=='string'||__cf_n.length<2) continue;
                if(__cf_n==='__cf_btoa'||__cf_n==='__cf_addEvt'||__cf_n==='__cf_dispatchEvt') continue;
                if(__cf_n==='Object'||__cf_n==='Array'||__cf_n==='Function'||__cf_n==='String'||__cf_n==='Number'||__cf_n==='Boolean'||__cf_n==='Math'||__cf_n==='Date'||__cf_n==='RegExp'||__cf_n==='Error'||__cf_n==='Promise'||__cf_n==='Set'||__cf_n==='Map'||__cf_n==='Symbol'||__cf_n==='Proxy'||__cf_n==='Reflect'||__cf_n==='JSON'||__cf_n==='Int32Array'||__cf_n==='Uint8Array'||__cf_n==='ArrayBuffer'||__cf_n==='DataView'||__cf_n==='isNaN'||__cf_n==='isFinite'||__cf_n==='parseInt'||__cf_n==='parseFloat'||__cf_n==='encodeURI'||__cf_n==='decodeURI'||__cf_n==='NaN'||__cf_n==='Infinity'||__cf_n==='console'||__cf_n==='window'||__cf_n==='globalThis'||__cf_n==='self'||__cf_n==='this') continue;
                // Skip DOM element names — the script uses these as method targets (not property checks)
                if(__cf_n==='script'||__cf_n==='iframe'||__cf_n==='body'||__cf_n==='head') continue;
                // Only skip entries starting with chars that can never be object keys
                if(__cf_n.charCodeAt(0)===47||__cf_n.charCodeAt(0)===46||__cf_n.charCodeAt(0)===58||__cf_n.charCodeAt(0)===64||__cf_n.charCodeAt(0)===35) continue;
                // Don't check for spaces/hyphens/slashes/dots — these are valid bracket-notation property names
                // (e.g. "display: none", "http-code:", "error on cf_chl_props", "1|3|2|0|5|4")
                // that the JSD script accesses via globalThis[name].
                if(typeof globalThis[__cf_n]!=='undefined'&&globalThis[__cf_n]!==null){
                    // __CF${'$'}cv${'$'}params is pre-set by BrowserEnvironment with a potentially stale t value.
                    // Refresh its t field to current time so the script's A() time check passes.
                    if(__cf_n==='__CF${'$'}cv${'$'}params'&&typeof globalThis[__cf_n].t!=='undefined'){
                        globalThis[__cf_n].t=__cf_ts;
                    }
                    continue;
                }
                globalThis[__cf_n]={t:__cf_ts,i:30,interval:30,updates:0,r:'',__cf_stub:1};
                if(typeof window!=='undefined') window[__cf_n]=globalThis[__cf_n];
            }
            """.trimIndent(),
        )
        Log.d(TAG, "D-array pre-population complete (${pieces.dName} length: evaluated)")

        // Clean up global R-like and D-like so they don't interfere with the script's own
        // hoisted declarations inside the IIFE
        engine.evaluate("delete globalThis.${pieces.rName};")
        engine.evaluate("delete globalThis.${pieces.dName};")

        Log.d(TAG, "Global dependency pre-population complete")
    }

    private fun executeInQuickJs(
        url: HttpUrl,
        params: CvParams,
        script: String,
    ): SensorResult {
        // Security: cap script size to prevent DoS
        if (script.length > MAX_SCRIPT_CHARS) {
            throw CloudscraperException(
                CloudscraperError.SCRIPT_TOO_LARGE,
                "JSD script exceeds ${MAX_SCRIPT_CHARS}char limit (${script.length} chars)",
            )
        }

        val deadlineMs = System.currentTimeMillis() + scriptTimeoutMs
        return withQuickJsTimeout(scriptTimeoutMs) { engine ->
            val t0 = System.nanoTime()
            fun elapsedMs(): Long = (System.nanoTime() - t0) / 1_000_000

            // Full challenge script dump — only when trace logging is enabled.
            CloudScraperDebug.trace(TAG, "script (len=${script.length})") { script }

            // Install browser environment shims
            BrowserEnvironment.install(engine, userAgent, url.toString())
            CloudScraperDebug.verbose(TAG, "Timeline [${elapsedMs()}ms]: BrowserEnvironment installed")

            if (CloudScraperDebug.traceEnabled) {
                val installTimers = engine.evaluate(
                    "try{JSON.stringify({ptLen:__cf_pt.length,piLen:__cf_pi.length,st_counter:__cf_st_counter,fire_count:__cf_fire_count})}catch(e){'err:'+e.message}",
                ) as? String
                Log.v(TAG, "Timers post-install: $installTimers")
                val stCheck = engine.evaluate(
                    "try{JSON.stringify({setTimeoutType:typeof setTimeout,winSetTimeoutEq:setTimeout===__cf_setTimeout,globalSetTimeoutEq:globalThis.setTimeout===__cf_setTimeout})}catch(e){'err:'+e.message}",
                ) as? String
                Log.v(TAG, "setTimeout alias check: $stCheck")
            }

            // Inject __CF$cv$params so the script can read them.
            // Script accesses via q=this||self=globalThis, NOT window (=win, separate obj).
            engine.evaluate(
                "globalThis.__CF${'$'}cv${'$'}params = { a: ${jsonString(params.a)}, s: ${jsonString(params.s)}, h: ${jsonString(params.h)} };",
            )
            // Mirror to window for diagnostics that read window.__CF$cv$params
            engine.evaluate("window.__CF${'$'}cv${'$'}params = globalThis.__CF${'$'}cv${'$'}params;")

            // Add a .t property (base64 timestamp) and .i (interval) to the
            // injected params, in case the script reads them via g() -> __CF$cv$params
            engine.evaluate(
                """
                globalThis.__CF${'$'}cv${'$'}params.t = __cf_btoa(String(Math.floor(Date.now()/1000) - 10));
                globalThis.__CF${'$'}cv${'$'}params.i = 30;
                window.__CF${'$'}cv${'$'}params = globalThis.__CF${'$'}cv${'$'}params;
                """.trimIndent(),
            )

            // _cf_chl_opt must be accessible from both window AND globalThis.
            // The script header does `window._cf_chl_opt = {JPsB0:'b'}`, but the
            // IIFE reads it via `N[g(666)]` = globalThis._cf_chl_opt.
            // A getter/setter on globalThis delegates to window so both always agree.
            engine.evaluate(
                """
                (function(){
                  var _accessed = {};
                  var _storage = window._cf_chl_opt || {};
                  Object.defineProperty(globalThis, '_cf_chl_opt', {
                    get: function() { return _storage; },
                    set: function(v) { _storage = v; },
                    configurable: true, enumerable: true
                  });
                  Object.defineProperty(window, '_cf_chl_opt', {
                    get: function() { return _storage; },
                    set: function(v) { _storage = v; },
                    configurable: true, enumerable: true
                  });
                  globalThis.__cf_chl_opt_accessed = _accessed;
                })();
                """.trimIndent(),
            )

            CloudScraperDebug.verbose(TAG, "Timeline [${elapsedMs()}ms]: __CF" + "${'$'}cv${'$'}params injected, calling resolveAndPrepopulate")

            resolveAndPrepopulate(engine, script)
            CloudScraperDebug.verbose(TAG, "Timeline [${elapsedMs()}ms]: resolveAndPrepopulate done")

            // o() returns V[Fm(528)] || V[Fm(583)].pp — both ultimately the cv$params object.
            // The script reads .r (URL suffix), .u/.ut (optional payload fields) from it.
            // Set defaults to avoid undefined in XHR URL construction.
            engine.evaluate(
                """
                try{
                  if(globalThis.__CF${'$'}cv${'$'}params) {
                    globalThis.__CF${'$'}cv${'$'}params.r = '';
                    globalThis.__CF${'$'}cv${'$'}params.u = '';
                    globalThis.__CF${'$'}cv${'$'}params.ut = '';
                    globalThis.__CF${'$'}cv${'$'}params.pp = null;
                  }
                } catch(e){}
                """.trimIndent(),
            )

            if (CloudScraperDebug.traceEnabled) {
                val preSensorTimers = engine.evaluate(
                    "try{JSON.stringify({ptLen:__cf_pt.length,piLen:__cf_pi.length,st_counter:__cf_st_counter})}catch(e){'err:'+e.message}",
                ) as? String
                Log.v(TAG, "Timers post-prepopulate: $preSensorTimers")
            }

            // Initialize sensor payload capture
            engine.evaluate("globalThis.__cf_sensor_payload = ''; globalThis.__cf_sensor_url = '';")

            engine.evaluate(
                """
                globalThis.__cf_diag={};
                Object.defineProperty(document,'__cf_readyState',{get:function(){return document.readyState}});
                var _origAddEvt=__cf_addEvt;
                __cf_addEvt=function(el,type,fn,opts){
                  __cf_diag['addEvt_'+type]=(__cf_diag['addEvt_'+type]||0)+1;
                  __cf_diag['fnType_'+type]=typeof fn;
                  return _origAddEvt(el,type,fn,opts);
                };
                var _origDispatchEvt=__cf_dispatchEvt;
                __cf_dispatchEvt=function(el,evt){
                  __cf_diag['dispatch_'+evt.type]=(__cf_diag['dispatch_'+evt.type]||0)+1;
                  return _origDispatchEvt(el,evt);
                };
                """.trimIndent(),
            )

            // Trace interceptors (after BrowserEnvironment.install so we override, not get overridden).
            // Install trace wrappers only when trace mode is enabled — the wrappers add a
            // JSON.stringify(.substring) JSON path on every API call, which adds notable
            // overhead on scripts with hundreds of timer registrations.
            if (CloudScraperDebug.traceEnabled) {
                engine.evaluateTraceInterceptors()
            }

            // Run the JSD sensor script
            var scriptError: String? = null
            try {
                engine.evaluate(script)
                CloudScraperDebug.verbose(TAG, "Timeline [${elapsedMs()}ms]: JSD script executed cleanly (${script.length} chars)")
            } catch (e: Exception) {
                scriptError = e.message
                CloudScraperDebug.w(TAG, "Timeline [${elapsedMs()}ms]: JSD script execution error: ${e.message}")
            }

            if (CloudScraperDebug.traceEnabled) {
                val postScriptTimers = engine.evaluate(
                    "try{JSON.stringify({ptLen:__cf_pt.length,piLen:__cf_pi.length,st_counter:__cf_st_counter,fire_count:__cf_fire_count})}catch(e){'err:'+e.message}",
                ) as? String
                Log.v(TAG, "Timers post-script: $postScriptTimers")
            }

            // DOMContentLoaded: the script's A() function checks document.readyState.
            // With readyState='loading', A() registers a DOMContentLoaded listener
            // then continues assigning variables in its switch cases. We schedule
            // the event via setTimeout(0) so it fires during the timer loop after
            // A() has fully returned and all case assignments are complete.
            engine.evaluate(
                """
                (function(){
                  __cf_setTimeout(function(){
                    var evt={type:'DOMContentLoaded',bubbles:true,cancelable:true};
                    __cf_dispatchEvt(win.document,evt);
                    __cf_dispatchEvt(win,evt);
                  },0);
                })();
                """.trimIndent(),
            )
            CloudScraperDebug.verbose(TAG, "Scheduled DOMContentLoaded for timer loop")

            var prevRemaining = Int.MAX_VALUE
            var stalledRounds = 0
            for (round in 1..MAX_TIMER_ROUNDS) {
                if (System.currentTimeMillis() >= deadlineMs) break
                val payloadNow = engine.evaluate(
                    "typeof globalThis.__cf_sensor_payload === 'string' ? globalThis.__cf_sensor_payload.length : 0",
                ) as? Int ?: 0
                if (payloadNow > 0) break
                val remaining = try {
                    engine.evaluate("typeof __cf_run_timers === 'function' ? __cf_run_timers() : 0") as? Int ?: 0
                } catch (e: Exception) {
                    CloudScraperDebug.w(TAG, "Timeline [${elapsedMs()}ms]: Timer round $round error: ${e.message}")
                    break
                }
                CloudScraperDebug.verbose(TAG, "Timeline [${elapsedMs()}ms]: Timer round $round: $remaining timers remaining")
                if (remaining == 0) break
                // Bail if queue is not shrinking across rounds (setInterval re-arming).
                if (remaining >= prevRemaining) {
                    stalledRounds++
                    if (stalledRounds >= STALL_TOLERANCE) break
                } else {
                    stalledRounds = 0
                }
                prevRemaining = remaining
            }

            val tTotal = elapsedMs()
            CloudScraperDebug.verbose(TAG, "Timeline [${tTotal}ms]: Diagnostics collection complete")

            // Extract the captured payload and challenge URL
            val payload = engine.evaluate("globalThis.__cf_sensor_payload") as? String
            if (payload.isNullOrEmpty()) {
                throw CloudscraperException(
                    CloudscraperError.NO_SENSOR_PAYLOAD,
                    "JSD script did not produce a sensor payload. " +
                        "The script may require browser features not available in QuickJS.",
                )
            }

            val rawUrl = engine.evaluate("globalThis.__cf_sensor_url") as? String
            // __cf_sensor_url is initialized to '' at line 582. Treat empty/blank as null
            // so the fallback in solveAttempt works correctly.
            val challengeUrl = if (rawUrl.isNullOrBlank()) null else rawUrl
            if (challengeUrl == null) {
                CloudScraperDebug.w(TAG, "Sensor URL not captured from script execution — will fall back to default endpoint")
            } else {
                CloudScraperDebug.verbose(TAG, "Sensor URL captured: $challengeUrl")
            }

            SensorResult(payload = payload, challengeUrl = challengeUrl)
        }
    }

    /** Installs trace wrappers around fetch/XHR/postMessage/timers/bindFunctions. */
    private fun QuickJs.evaluateTraceInterceptors() {
        evaluate(
            """
            var __cf_trace=[];
            var _origFetch=globalThis.fetch;
            globalThis.fetch=function(){__cf_trace.push('fetch:'+JSON.stringify(arguments).substring(0,200));return _origFetch.apply(this,arguments);};
            var _origXHROpen=XMLHttpRequest.prototype.open;
            var _origXHRSend=XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open=function(){__cf_trace.push('xhr.open:'+JSON.stringify(Array.from(arguments)).substring(0,200));return _origXHROpen.apply(this,arguments);};
            XMLHttpRequest.prototype.send=function(){__cf_trace.push('xhr.send:'+JSON.stringify(arguments).substring(0,200));return _origXHRSend.apply(this,arguments);};
            var _origPostMessage=postMessage;
            globalThis.postMessage=function(){__cf_trace.push('postMessage:'+JSON.stringify(arguments).substring(0,200));return _origPostMessage.apply(this,arguments);};
            var _origWinPostMessage=window.postMessage;
            window.postMessage=function(){__cf_trace.push('win.postMessage:'+JSON.stringify(arguments).substring(0,200));return _origWinPostMessage.apply(this,arguments);};
            var _origSendBeacon=navigator.sendBeacon;
            navigator.sendBeacon=function(){__cf_trace.push('sendBeacon:'+JSON.stringify(Array.from(arguments)).substring(0,200));return _origSendBeacon.apply(this,arguments);};
            var _origSetTimeout=setTimeout;
            setTimeout=function(){__cf_trace.push('setTimeout:'+arguments[1]);return _origSetTimeout.apply(this,arguments);};
            var _origSetInterval=setInterval;
            setInterval=function(){__cf_trace.push('setInterval:'+arguments[1]);return _origSetInterval.apply(this,arguments);};
            var _origAddEventListener=window.addEventListener;
            window.addEventListener=function(type,fn,opts){__cf_trace.push('addEventListener:'+type);return _origAddEventListener.apply(this,arguments);};
            var _origGetElementById=document.getElementById;
            document.getElementById=function(id){__cf_trace.push('getElementById:'+id);return _origGetElementById.call(document,id);};
            var _origCreateElement=document.createElement;
            document.createElement=function(tag){__cf_trace.push('createElement:'+tag);return _origCreateElement.call(document,tag);};
            var _origGetElementsByTagName=document.getElementsByTagName;
            document.getElementsByTagName=function(tag){__cf_trace.push('getElementsByTagName:'+tag);return _origGetElementsByTagName.call(document,tag);};
            var _origQuerySelector=document.querySelector;
            document.querySelector=function(sel){__cf_trace.push('querySelector:'+sel);return _origQuerySelector.call(document,sel);};
            var _origGetContext=HTMLCanvasElement.prototype.getContext;
            HTMLCanvasElement.prototype.getContext=function(type){__cf_trace.push('getContext:'+type);return _origGetContext.call(this,type);};
            var _origToDataURL=HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL=function(){__cf_trace.push('toDataURL:'+arguments[0]);return _origToDataURL.apply(this,arguments);};
            var _origGetComputedStyle=window.getComputedStyle;
            window.getComputedStyle=function(el){__cf_trace.push('getComputedStyle:'+(el&&el.tagName));return _origGetComputedStyle.apply(this,arguments);};
            """.trimIndent(),
        )
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
        initialCookies: Map<String, String> = emptyMap(),
    ): PostResult {
        Log.d(TAG, "POSTing sensor to: $challengeUrl (body len=${sensorData.length})")

        if (initialCookies.isEmpty()) {
            Log.w(TAG, "NO cookies forwarded to challenge POST — challenge session may not be associated")
        } else {
            Log.d(TAG, "Forwarding ${initialCookies.size} cookie(s): ${initialCookies.keys}")
        }

        val requestBuilder = Request.Builder()
            .url(challengeUrl)
            .header("User-Agent", userAgent)
            .header("Referer", originalUrl.toString())
            .header("Origin", originalUrl.scheme + "://" + originalUrl.host)
            .header("Accept", "*/*")
            .header("Content-Type", "text/plain;charset=UTF-8")
            .header("CF-Challenge", params.a)
            .post(okhttp3.RequestBody.create(null, sensorData))

        // Forward cookies from the challenge response (e.g. __cf_bm) so the
        // challenge platform can associate the sensor POST with the same session.
        if (initialCookies.isNotEmpty()) {
            val cookieHeader = initialCookies.entries.joinToString("; ") { (name, value) ->
                "$name=$value"
            }
            requestBuilder.header("Cookie", cookieHeader)
        }

        val request = requestBuilder.build()

        return noRedirectClient.newCall(request).execute().use { response ->
            val cookies = extractCookies(response.headers("Set-Cookie")).toMutableMap()

            if (!response.isSuccessful && response.code !in 300..399) {
                val body = response.peekBody(1024L).string()
                Log.w(TAG, "Challenge POST returned ${response.code}, body: $body")
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

            val cfClearance = cookies["cf_clearance"]
            PostResult(
                cfClearance = cfClearance,
                cookies = cookies,
                httpCode = response.code,
            )
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

        internal const val MAX_TIMER_ROUNDS = 24
        internal const val STALL_TOLERANCE = 3

        internal val RETRYABLE_ERRORS = setOf(
            CloudscraperError.SCRIPT_FETCH_FAILED,
            CloudscraperError.ENGINE_TIMEOUT,
        )

        internal val D_FUNC_REGEX = Regex(
            """function\s+(\w+)\s*\(\s*(\w+)\s*\)\s*\{return\s+\2\s*=\s*`([^`]+)`\s*\.\s*split\s*\(\s*(['"`]);\4\s*\)""",
        )
        internal val ROT_CALL_REGEX = Regex("""function\s*\([^)]*\)\s*\{""")

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
