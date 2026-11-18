package keiyoushi.source.stream

import android.util.Log
import keiyoushi.source.AniSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// http://127.0.0.1:<port>/_kp/<source id>/<scheme>/<host[:port]>/<path>?<query>
// The real url stays in the path so relative playlist urls resolve inside the proxy. Its path is
// escaped once more, so it survives the percent-decoding NanoHTTPD applies to request paths.
internal object ProxyUrl {
    private const val MARKER = "_kp"

    const val ANIMIRU_PLACEHOLDER = "http://localhost:1"

    fun encode(base: HttpUrl, sourceId: Long, target: HttpUrl): HttpUrl {
        val host = if (':' in target.host) "[${target.host}]" else target.host
        val authority = if (target.port == HttpUrl.defaultPort(target.scheme)) host else "$host:${target.port}"
        return base.newBuilder()
            .encodedPath("/")
            .addPathSegment(MARKER)
            .addPathSegment(sourceId.toString())
            .addPathSegment(target.scheme)
            .addPathSegment(authority)
            .apply { target.encodedPathSegments.forEach { addEncodedPathSegment(escape(it)) } }
            .encodedQuery(target.encodedQuery)
            .build()
    }

    fun isProxyUrl(url: HttpUrl): Boolean = (url.host == "localhost" || url.host == "127.0.0.1") &&
        url.pathSegments.firstOrNull() == MARKER

    /** [escaped] is false for a url whose path a local server already decoded once. */
    fun decode(url: HttpUrl, escaped: Boolean = true): Pair<Long, HttpUrl>? {
        if (!isProxyUrl(url)) return null
        val segments = url.encodedPathSegments
        if (segments.size < 5) return null
        val sourceId = segments[1].toLongOrNull() ?: return null
        val scheme = segments[2].takeIf { it == "http" || it == "https" } ?: return null
        val authority = url.pathSegments[3]
        val path = segments.drop(4).joinToString("/") { if (escaped) unescape(it) else it }
        val query = url.encodedQuery?.let { "?$it" }.orEmpty()
        val target = "$scheme://$authority/$path$query".toHttpUrlOrNull() ?: return null
        return sourceId to target
    }

    private val escaped = Regex("%25|%2B")

    private fun escape(segment: String) = segment.replace("%", "%25").replace("+", "%2B")

    private fun unescape(segment: String) = escaped.replace(segment) { if (it.value == "%25") "%" else "+" }

    fun baseOf(url: HttpUrl): HttpUrl = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
}

internal object StreamSources {
    private val sources = ConcurrentHashMap<Long, AniSource>()

    fun register(source: AniSource) {
        sources[source.id] = source
    }

    operator fun get(id: Long): AniSource? = sources[id]
}

/** Serves proxy urls requested through the source's client in-process. */
internal class ProxyUrlInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val (sourceId, target) = ProxyUrl.decode(request.url) ?: return chain.proceed(request)
        val source = StreamSources[sourceId] ?: return chain.proceed(request.newBuilder().url(target).build())
        val response = chain.proceed(request.newBuilder().url(target).build())
        return StreamPipeline.rewritePlaylist(source, ProxyUrl.baseOf(request.url), target, response)
    }
}

internal class StreamDispatchInterceptor(private val interceptors: List<StreamInterceptor>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val interceptor = interceptors.firstOrNull { it.handles(url) } ?: return chain.proceed(chain.request())
        return interceptor.intercept(chain)
    }
}

internal object StreamPipeline {
    private val skippedRequestHeaders = setOf(
        "host", "connection", "keep-alive", "proxy-connection", "te", "upgrade", "transfer-encoding",
        "accept-encoding", // let OkHttp handle compression
        "remote-addr", "http-client-ip", // added by NanoHTTPD
    )

    val passedResponseHeaders = listOf(
        "Content-Range",
        "Accept-Ranges",
        "Last-Modified",
        "ETag",
        "Cache-Control",
        "Content-Disposition",
    )

    fun fetch(base: HttpUrl, method: String, path: String, query: String?, headers: Map<String, String>): Response? {
        val url = base.newBuilder().encodedPath(path).encodedQuery(query).build()
        val (sourceId, target) = ProxyUrl.decode(url, escaped = false) ?: return null
        val source = StreamSources[sourceId] ?: return null
        if (!source.handlesStream(target)) return null
        val request = Request.Builder()
            .url(target)
            .apply {
                headers.forEach { (name, value) ->
                    if (name.lowercase() !in skippedRequestHeaders) header(name, value)
                }
            }
            .method(if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "GET", null)
            .build()
        val response = source.client.newCall(request).execute()
        return rewritePlaylist(source, base, target, response)
    }

    // Urls a StreamInterceptor handles become proxy urls, the rest absolute
    // (a root-relative url would escape the proxy path)
    fun rewritePlaylist(source: AniSource, base: HttpUrl, target: HttpUrl, response: Response): Response {
        if (response.code != 200 || response.request.method != "GET") return response
        val kind = playlistKind(target, response) ?: return response
        val body = response.body.string()
        val playlistUrl = response.request.url
        val toUrl = { url: HttpUrl ->
            if (source.handlesStream(url)) ProxyUrl.encode(base, source.id, url).toString() else url.toString()
        }
        val rewritten = when (kind) {
            PlaylistKind.Hls -> rewriteHls(body) { raw -> playlistUrl.resolve(raw)?.let(toUrl) ?: raw }
            PlaylistKind.Dash -> rewriteDash(body, playlistUrl, toUrl)
        }
        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(rewritten.toResponseBody(response.body.contentType() ?: kind.mimeType.toMediaTypeOrNull()))
            .build()
    }

    private enum class PlaylistKind(val mimeType: String) {
        Hls("application/vnd.apple.mpegurl"),
        Dash("application/dash+xml"),
    }

    private fun playlistKind(url: HttpUrl, response: Response): PlaylistKind? {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val path = url.encodedPath.lowercase()
        if ("mpegurl" in contentType || path.endsWith(".m3u8")) return PlaylistKind.Hls
        if ("dash+xml" in contentType || path.endsWith(".mpd")) return PlaylistKind.Dash
        if (contentType.startsWith("video/") || contentType.startsWith("audio/")) return null
        val head = response.peekBody(512).string().trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("#EXTM3U") -> PlaylistKind.Hls
            "<MPD" in head -> PlaylistKind.Dash
            else -> null
        }
    }

    private val hlsUriAttribute = Regex("""URI="([^"]+)"""")

    internal fun rewriteHls(playlist: String, rewrite: (String) -> String): String = playlist.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("#") -> hlsUriAttribute.replace(line) { "URI=\"${rewrite(it.groupValues[1])}\"" }
            else -> rewrite(trimmed)
        }
    }

    private val xmlTag = Regex("""<(/?)([\w:]+)([^>]*?)(/?)>""")
    private val dashUrlAttribute = Regex("""\b(media|initialization|sourceURL|href)="([^"]+)"""")

    // A width like $Number%05d$ must reach the player as is, not escaped by the proxy encoding
    private val escapedDashFormat = Regex("""\$(\w+)%25(0\d+d)\$""")

    /** Base urls of an open element: from its parent, and after its own first `BaseURL`. */
    private class DashScope(val inherited: HttpUrl) {
        var effective = inherited
        var hasBaseUrl = false
    }

    /**
     * Rewrites `BaseURL`s and absolute or root-relative urls in [manifest] with [toUrl], resolved
     * against the `BaseURL`s above them. Other relative urls stay relative, so the player still
     * applies them per `Representation`, unless their `..` would climb out of the proxy path.
     */
    internal fun rewriteDash(manifest: String, manifestUrl: HttpUrl, toUrl: (HttpUrl) -> String): String {
        val rewrite = { base: HttpUrl, raw: String ->
            base.resolve(raw.xmlUnescape())
                ?.let(toUrl)
                ?.replace(escapedDashFormat) { "$" + it.groupValues[1] + "%" + it.groupValues[2] + "$" }
                ?.xmlEscape()
        }
        val scopes = ArrayDeque<DashScope>().apply { addLast(DashScope(manifestUrl)) }
        val out = StringBuilder()
        var last = 0
        for (tag in xmlTag.findAll(manifest)) {
            val text = manifest.substring(last, tag.range.first)
            val (closing, name, attributes, selfClosing) = tag.destructured
            if (closing == "/" && name == "BaseURL" && scopes.size > 2 && text.isNotBlank()) {
                // Siblings are alternatives: each resolves against the parent's inherited base
                val parent = scopes[scopes.size - 2]
                val raw = text.trim()
                if (!parent.hasBaseUrl) {
                    parent.effective = parent.inherited.resolve(raw.xmlUnescape()) ?: parent.inherited
                    parent.hasBaseUrl = true
                }
                out.append(rewrite(parent.inherited, raw) ?: text)
            } else {
                out.append(text)
            }

            if (closing == "/") {
                out.append(tag.value)
                if (scopes.size > 1) scopes.removeLast()
            } else {
                val base = scopes.last().effective
                val rewritten = dashUrlAttribute.replace(attributes) { m ->
                    val raw = m.groupValues[2]
                    val keep = !raw.startsWith("/") && !raw.contains("://") && !climbsAboveRoot(base, raw)
                    if (keep) m.value else rewrite(base, raw)?.let { "${m.groupValues[1]}=\"$it\"" } ?: m.value
                }
                out.append(if (attributes.isEmpty()) tag.value else tag.value.replace(attributes, rewritten))
                if (selfClosing.isEmpty()) scopes.addLast(DashScope(base))
            }
            last = tag.range.last + 1
        }
        out.append(manifest.substring(last))
        return out.toString()
    }

    // More ".." than directories above it: the player would climb into the proxy path itself
    private fun climbsAboveRoot(base: HttpUrl, raw: String): Boolean {
        val ups = raw.split('/').takeWhile { it == ".." }.size
        return ups > 0 && ups > base.pathSegments.size - 1
    }

    private fun String.xmlUnescape() = replace("&amp;", "&")
    private fun String.xmlEscape() = replace("&", "&amp;")
}

/** A response for either NanoHTTPD version. [length] is -1 when unknown. */
internal class ServedResponse(
    val code: Int,
    val status: String,
    val mimeType: String?,
    val headers: List<Pair<String, String>>,
    val body: InputStream,
    val length: Long,
) {
    companion object {
        fun serve(
            base: HttpUrl,
            method: String,
            path: String,
            query: String?,
            headers: Map<String, String>,
            onDone: () -> Unit,
        ): ServedResponse {
            val response = try {
                StreamPipeline.fetch(base, method, path, query, headers)
            } catch (e: Exception) {
                Log.e("StreamProxy", "Failed to fetch $path", e)
                onDone()
                return text(502, "Bad Gateway", e.toString())
            } ?: run {
                onDone()
                return text(404, "Not Found", "Not a stream proxy url")
            }
            val isHead = method.equals("HEAD", ignoreCase = true)
            val length = if (isHead) response.header("Content-Length")?.toLongOrNull() ?: -1 else response.body.contentLength()
            val body = object : FilterInputStream(response.body.byteStream()) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        response.close()
                    } finally {
                        onDone()
                    }
                }
            }
            return ServedResponse(
                code = response.code,
                status = "${response.code} ${response.message.ifEmpty { "Status" }}",
                mimeType = response.body.contentType()?.toString(),
                headers = StreamPipeline.passedResponseHeaders.mapNotNull { name -> response.header(name)?.let { name to it } },
                body = body,
                length = length,
            )
        }

        private fun text(code: Int, reason: String, message: String): ServedResponse {
            val bytes = message.toByteArray()
            return ServedResponse(code, "$code $reason", "text/plain", emptyList(), ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }
}
