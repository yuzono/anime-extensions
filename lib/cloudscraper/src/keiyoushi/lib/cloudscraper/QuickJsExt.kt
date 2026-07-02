package keiyoushi.lib.cloudscraper

import app.cash.quickjs.QuickJs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Executes a block of QuickJS operations with a time limit.
 *
 * QuickJs has no built-in timeout API ([setTimeout] does not exist on the class).
 * This helper runs the entire QuickJS session on a dedicated thread and enforces
 * a wall-clock timeout via [Future.get]. On timeout, the engine is [closed][QuickJs.close]
 * (which releases the native context) and a [CloudscraperException] with
 * [CloudscraperError.ENGINE_TIMEOUT] is thrown.
 *
 * **Usage:** Wrap the `QuickJs.create().use { }` block:
 * ```kotlin
 * val result = withQuickJsTimeout(timeoutMs) { engine ->
 *     engine.evaluate("...")
 *     engine.evaluate("...") as String
 * }
 * ```
 *
 * @param timeoutMs maximum wall-clock time in milliseconds for the entire QuickJS session
 * @param block the operations to perform on the QuickJS engine
 * @return the result of [block]
 * @throws CloudscraperException with [CloudscraperError.ENGINE_TIMEOUT] if the time limit is exceeded
 */
internal fun <T> withQuickJsTimeout(timeoutMs: Long, block: (QuickJs) -> T): T {
    val engine = QuickJs.create()
    try {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<T> { block(engine) }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Close the engine to release native resources and unblock the thread
            engine.close()
            throw CloudscraperException(
                CloudscraperError.ENGINE_TIMEOUT,
                "QuickJS evaluation exceeded ${timeoutMs}ms timeout",
            )
        } finally {
            executor.shutdownNow()
        }
    } finally {
        // Ensure engine is closed if block threw before we could close on timeout
        try { engine.close() } catch (_: Exception) { }
    }
}
