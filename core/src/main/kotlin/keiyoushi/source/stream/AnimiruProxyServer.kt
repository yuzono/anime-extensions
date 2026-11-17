package keiyoushi.source.stream

import eu.kanade.tachiyomi.animesource.model.HttpServer
import fi.iki.elonen.NanoHTTPD
import keiyoushi.annotation.AnimiruOnly
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Started and stopped by Animiru, see `createHttpServer()`. */
@OptIn(AnimiruOnly::class)
internal class AnimiruProxyServer : HttpServer() {
    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val served = ServedResponse.serve(
            base = "http://localhost:$listeningPort/".toHttpUrl(),
            method = session.method.name,
            path = session.uri,
            query = session.queryParameterString,
            headers = session.headers,
            onDone = {},
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
        return response
    }
}
