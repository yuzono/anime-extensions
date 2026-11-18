package keiyoushi.source.stream

import eu.kanade.tachiyomi.animesource.model.HttpServer
import fi.iki.elonen.NanoHTTPD
import keiyoushi.annotation.AnimiruOnly
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress

/** Started and stopped by Animiru, see `createHttpServer()`. */
@OptIn(AnimiruOnly::class)
internal class AnimiruProxyServer : HttpServer() {
    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // Animiru's server listens on every interface
        if (!isLoopback(session.headers["remote-addr"])) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Forbidden")
        }
        val served = ServedResponse.serve(
            base = "http://localhost:$listeningPort/".toHttpUrl(),
            method = session.method.name,
            path = session.uri,
            query = session.queryParameterString.takeIf { session.parameters.isNotEmpty() },
            headers = session.headers,
        )
        val status = object : NanoHTTPD.Response.IStatus {
            override fun getDescription() = served.status
            override fun getRequestStatus() = served.code
        }
        val response = if (served.length >= 0) {
            newFixedLengthResponse(status, served.mimeType, served.body, served.length)
        } else {
            newChunkedResponse(status, served.mimeType, served.body)
        }
        served.headers.forEach { (name, value) -> response.addHeader(name, value) }
        response.setGzipEncoding(false)
        return response
    }

    // A numeric address, so no lookup
    private fun isLoopback(address: String?) = address != null && runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
}
