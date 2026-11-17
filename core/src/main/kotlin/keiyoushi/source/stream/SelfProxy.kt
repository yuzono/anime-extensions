package keiyoushi.source.stream

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.IStatus
import org.nanohttpd.protocols.http.response.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy for apps that don't start one (Anikku, older Animiru). Stops when idle and no player is
 * open; reuses its port on restart so earlier urls keep working.
 */
internal object SelfProxy {
    private const val IDLE_TIMEOUT_MS = 120_000L
    private const val IDLE_CHECK_SECONDS = 30L

    private var server: SelfProxyServer? = null
    private var lastPort = 0
    private var idleCheck: ScheduledFuture<*>? = null

    private val activeRequests = AtomicInteger()

    @Volatile
    private var lastActivity = 0L

    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "StreamProxy-idle").apply { isDaemon = true }
        }
    }

    @Synchronized
    fun ensureStarted(): Int {
        lastActivity = System.currentTimeMillis()
        server?.takeIf { it.isAlive }?.let { return it.listeningPort }

        PlayerTracker.ensureRegistered()
        val started = start(lastPort) ?: start(0) ?: throw IllegalStateException("Failed to start the stream proxy")
        server = started
        lastPort = started.listeningPort
        if (idleCheck == null) {
            idleCheck = scheduler.scheduleWithFixedDelay(::stopIfIdle, IDLE_CHECK_SECONDS, IDLE_CHECK_SECONDS, TimeUnit.SECONDS)
        }
        return lastPort
    }

    private fun start(port: Int): SelfProxyServer? = try {
        SelfProxyServer(port).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
    } catch (e: Exception) {
        if (port != 0) Log.w("StreamProxy", "Port $port unavailable, picking another", e)
        null
    }

    fun requestStarted() {
        activeRequests.incrementAndGet()
        lastActivity = System.currentTimeMillis()
    }

    fun requestEnded() {
        activeRequests.decrementAndGet()
        lastActivity = System.currentTimeMillis()
    }

    @Synchronized
    private fun stopIfIdle() {
        val current = server ?: return
        val idleFor = System.currentTimeMillis() - lastActivity
        if (activeRequests.get() > 0 || PlayerTracker.isPlayerOpen || idleFor < IDLE_TIMEOUT_MS) return

        current.stop()
        server = null
        idleCheck?.cancel(false)
        idleCheck = null
    }
}

internal class SelfProxyServer(port: Int) : NanoHTTPD("127.0.0.1", port) {
    override fun handle(session: IHTTPSession): Response {
        SelfProxy.requestStarted()
        val served = ServedResponse.serve(
            base = "http://127.0.0.1:$listeningPort/".toHttpUrl(),
            method = session.method.name,
            path = session.uri,
            query = session.queryParameterString,
            headers = session.headers,
            onDone = SelfProxy::requestEnded,
        )
        val status = object : IStatus {
            override fun getDescription() = served.status
            override fun getRequestStatus() = served.code
        }
        val response = if (served.length >= 0) {
            Response.newFixedLengthResponse(status, served.mimeType, served.body, served.length)
        } else {
            Response.newChunkedResponse(status, served.mimeType, served.body)
        }
        served.headers.forEach { (name, value) -> response.addHeader(name, value) }
        response.setUseGzip(false)
        return response
    }
}

/** Keeps the proxy up while a player is open, even if paused. */
internal object PlayerTracker {
    private val application: Application by injectLazy()
    private val openPlayers = AtomicInteger()

    @Volatile
    private var registered = false

    val isPlayerOpen: Boolean get() = openPlayers.get() > 0

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            try {
                openPlayers.set(countOpenPlayers())
                application.registerActivityLifecycleCallbacks(Callbacks)
            } catch (e: Exception) {
                Log.e("StreamProxy", "Failed to track the player", e)
            }
        }
    }

    private fun isPlayer(activity: Any) = activity.javaClass.name.endsWith(".ui.player.PlayerActivity")

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun countOpenPlayers(): Int = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val records = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread) as Map<*, *>
        records.values.count { record ->
            val activity = record!!.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(record)
            activity != null && isPlayer(activity)
        }
    }.getOrDefault(0)

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (isPlayer(activity)) openPlayers.incrementAndGet()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (isPlayer(activity) && openPlayers.decrementAndGet() < 0) openPlayers.set(0)
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
