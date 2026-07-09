package keiyoushi.lib.cloudscraper

import android.util.Log
import app.cash.quickjs.QuickJs
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "QuickJsExt"

/** JVM thread stack size for QuickJS worker threads (16 MB). */
private const val WORKER_STACK_SIZE = 16L * 1024 * 1024

private val QUICKJS_EXECUTOR = ThreadPoolExecutor(
    4,
    4,
    5L,
    TimeUnit.SECONDS,
    LinkedBlockingQueue(),
    { r -> Thread(null, r, "quickjs-worker", WORKER_STACK_SIZE).apply { isDaemon = true } },
)

/**
 * Creates a [QuickJs] engine and runs [block] on a dedicated worker thread
 * with a 16 MB stack, timing out after [timeoutMs].
 *
 * **Both `QuickJs.create()` and `evaluate()` must run on the same
 * 16 MB-stack thread** — the CashApp binding sizes QuickJS's internal
 * 1 MB C-stack limit from the creating thread, so creating the engine
 * on a small-stack thread and evaluating on a large-stack worker
 * causes an instant stack overflow on the very first evaluate call.
 */
internal fun <T> withQuickJsTimeout(timeoutMs: Long, block: (QuickJs) -> T): T {
    // Create engine AND run the block on the same large-stack worker thread
    val future = QUICKJS_EXECUTOR.submit<T> {
        val thread = Thread.currentThread()
        Log.d(TAG, "Worker thread: ${thread.name}")
        val engine = QuickJs.create()
        try {
            block(engine)
        } finally {
            try {
                engine.close()
            } catch (_: Exception) { }
        }
    }
    try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        throw CloudscraperException(
            CloudscraperError.ENGINE_TIMEOUT,
            "QuickJS evaluation exceeded ${timeoutMs}ms timeout",
        )
    } catch (e: java.util.concurrent.ExecutionException) {
        val cause = e.cause
        when {
            cause is StackOverflowError -> throw CloudscraperException(
                CloudscraperError.STACK_OVERFLOW,
                "QuickJS script triggered stack overflow — challenge script too deeply recursive",
                cause,
            )
            cause is CloudscraperException -> throw cause
            cause != null -> throw CloudscraperException(
                CloudscraperError.ENGINE_TIMEOUT,
                "QuickJS evaluation failed: ${cause.message}",
                cause,
            )
            else -> throw CloudscraperException(
                CloudscraperError.ENGINE_TIMEOUT,
                "QuickJS evaluation failed",
                e,
            )
        }
    } catch (e: StackOverflowError) {
        throw CloudscraperException(
            CloudscraperError.STACK_OVERFLOW,
            "QuickJS script triggered stack overflow — challenge script too deeply recursive",
            e,
        )
    }
}
