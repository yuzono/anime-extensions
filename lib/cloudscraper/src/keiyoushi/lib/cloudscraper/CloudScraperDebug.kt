package keiyoushi.lib.cloudscraper

import android.util.Log

/**
 * Compile-time + runtime config for cloudscraper diagnostics.
 *
 * Cloudflare JSD sensor scripts are several hundred KB and contain
 * per-variant obfuscation. Logging the full script and dozens of
 * diagnostic snapshots to logcat on every attempt is noisy and slow.
 * Instead, we gate three diagnostic tiers behind runtime flags:
 *
 * - **always** — one-line decision logs (`JSD attempt 1/2 failed`,
 *   `detected challenge type`, `cached cf_clearance stale`).
 *   Always emitted via `Log.i/Log.w/Log.e`.
 * - **verbose** — short diagnostic snapshots at key timeline points
 *   (timer queue length, sensor payload size, extracted D-array entry
 *   counts). Useful while debugging a specific site but fine to leave
 *   off in production.
 * - **trace** — full payloads, full challenge script dump, full API
 *   call trace (xhr.open / fetch / postMessage). NEVER leave on in
 *   production—will fill logcat with megabyte-sized dumps.
 *
 * Flags are read at runtime (not compile-time) so a developer can flip
 * them on without rebuilding cloudscraper itself — set the system
 * property `cloudscraper.verbose` or `cloudscraper.trace` to `true`
 * (default false). On device, use
 *
 *     adb shell setprop log.tag.CloudScraperVerbose VERBOSE
 *
 * The mirrored `Log.isLoggable` check honors `adb shell setprop
 * persist.log.tag.CloudScraperVerbose VERBOSE`.
 */
internal object CloudScraperDebug {

    /** One-line decision logs — always on. */
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    /** Verbose: short diagnostic snapshots at timeline boundaries. */
    fun verbose(tag: String, msg: String) {
        if (!VERBOSE) return
        Log.v(tag, msg)
    }

    /**
     * Trace: dumps of full payloads / full challenge scripts.
     * Off by default — respect [traceEnabled] to avoid building huge
     * strings when trace is disabled.
     */
    val traceEnabled: Boolean get() = TRACE

    /**
     * Logs [block] only if trace logging is enabled, prefixing the
     * output with [label]. The block is NOT evaluated when trace is off,
     * so expensive .toString() / substring operations on MB-scale
     * payloads are skipped.
     */
    fun trace(tag: String, label: String, block: () -> String) {
        if (!TRACE) return
        Log.d(tag, "$label: ${block()}")
    }

    /** Lazy-init verbose flag. */
    private val VERBOSE: Boolean by lazy {
        System.getProperty("cloudscraper.verbose")?.toBoolean()
            ?: Log.isLoggable("CloudScraperVerbose", Log.VERBOSE)
    }

    private val TRACE: Boolean by lazy {
        System.getProperty("cloudscraper.trace")?.toBoolean()
            ?: Log.isLoggable("CloudScraperTrace", Log.VERBOSE)
    }
}
