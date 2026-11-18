package keiyoushi.source.stream

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.IStatus
import org.nanohttpd.protocols.http.response.Response

/**
 * Proxy for apps that don't start one (Anikku, older Animiru). It runs for the rest of the process,
 * so urls handed to external players and queued downloads keep working.
 */
internal object SelfProxy {
    private var server: SelfProxyServer? = null
    private var lastPort = 0

    @Synchronized
    fun ensureStarted(): Int {
        server?.takeIf { it.isAlive }?.let { return it.listeningPort }

        // Same port as before if it died, so earlier urls keep working
        val started = start(lastPort) ?: start(0) ?: throw IllegalStateException("Failed to start the stream proxy")
        server = started
        lastPort = started.listeningPort
        return lastPort
    }

    private fun start(port: Int): SelfProxyServer? {
        val server = SelfProxyServer(port)
        return try {
            server.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        } catch (e: Exception) {
            server.stop() // the socket is open even when binding failed
            if (port != 0) Log.w("StreamProxy", "Port $port unavailable, picking another", e)
            null
        }
    }
}

internal class SelfProxyServer(port: Int) : NanoHTTPD("127.0.0.1", port) {
    override fun handle(session: IHTTPSession): Response {
        val served = ServedResponse.serve(
            base = "http://127.0.0.1:$listeningPort/".toHttpUrl(),
            method = session.method.name,
            path = session.uri,
            // Kept from an earlier request on the same connection when this one has none
            query = session.queryParameterString.takeIf { session.parameters.isNotEmpty() },
            headers = session.headers,
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
