package keiyoushi.lib.cloudscraper

import app.cash.quickjs.QuickJs
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val QUICKJS_EXECUTOR = ThreadPoolExecutor(
    4,
    4,
    60L,
    TimeUnit.SECONDS,
    LinkedBlockingQueue(),
    { r -> Thread(r, "quickjs-worker").apply { isDaemon = true } },
)

internal fun <T> withQuickJsTimeout(timeoutMs: Long, block: (QuickJs) -> T): T {
    val engine = QuickJs.create()
    try {
        val future = QUICKJS_EXECUTOR.submit<T> { block(engine) }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            engine.close()
            throw CloudscraperException(
                CloudscraperError.ENGINE_TIMEOUT,
                "QuickJS evaluation exceeded ${timeoutMs}ms timeout",
            )
        } catch (e: StackOverflowError) {
            engine.close()
            throw CloudscraperException(
                CloudscraperError.STACK_OVERFLOW,
                "QuickJS script triggered stack overflow — challenge script too deeply recursive",
                e,
            )
        }
    } finally {
        try {
            engine.close()
        } catch (_: Exception) { }
    }
}
