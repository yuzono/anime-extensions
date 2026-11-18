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
import java.io.IOException
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
            .encodedFragment(target.encodedFragment)
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
        val fragment = url.encodedFragment?.let { "#$it" }.orEmpty()
        val target = "$scheme://$authority/$path$query$fragment".toHttpUrlOrNull() ?: return null
        return sourceId to target
    }

    private val escaped = Regex("%25|%2B")

    fun escape(segment: String) = segment.replace("%", "%25").replace("+", "%2B")

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
        val targetRequest = request.newBuilder().url(target).build()
        val response = chain.proceed(targetRequest)
        return StreamPipeline.rewritePlaylist(source, ProxyUrl.baseOf(request.url), target, response) {
            chain.proceed(targetRequest.newBuilder().removeHeader("Range").build())
        }
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
        return rewritePlaylist(source, base, target, response) {
            source.client.newCall(request.newBuilder().removeHeader("Range").build()).execute()
        }
    }

    // Urls a StreamInterceptor handles become proxy urls, the rest absolute
    // (a root-relative url would escape the proxy path)
    fun rewritePlaylist(
        source: AniSource,
        base: HttpUrl,
        target: HttpUrl,
        response: Response,
        fetchWhole: () -> Response,
    ): Response = try {
        rewritePlaylistOrNull(source, base, target, response, fetchWhole) ?: response
    } catch (e: Exception) {
        response.close()
        throw e
    }

    private fun rewritePlaylistOrNull(
        source: AniSource,
        base: HttpUrl,
        target: HttpUrl,
        original: Response,
        fetchWhole: () -> Response,
    ): Response? {
        if (original.request.method != "GET" || (original.code != 200 && original.code != 206)) return null
        val kind = playlistKind(target, original) ?: return null
        // A playlist must be rewritten whole: fetch it again without the player's Range
        val response = if (original.code == 206) {
            original.close()
            fetchWhole().also {
                if (it.code != 200) {
                    it.close()
                    throw IOException("Playlist $target not available in full")
                }
            }
        } else {
            original
        }
        val body = response.body.string()
        val playlistUrl = response.request.url
        val toUrl = { url: HttpUrl ->
            if (source.handlesStream(url)) ProxyUrl.encode(base, source.id, url).toString() else url.toString()
        }
        val rewritten = when (kind) {
            PlaylistKind.Hls -> rewriteHls(body) { raw -> playlistUrl.resolve(raw)?.let(toUrl) ?: raw }
            PlaylistKind.Dash -> rewriteDash(body, playlistUrl, redirected = playlistUrl != target, toUrl = toUrl)
        }
        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Range")
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
    private val dashUrlAttribute = Regex("""\b(media|initialization|sourceURL|href)(\s*=\s*)(["'])(.+?)\3""")
    private val dashToken = Regex("""\$[^$]*\$""")

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
    internal fun rewriteDash(
        manifest: String,
        manifestUrl: HttpUrl,
        redirected: Boolean = false,
        toUrl: (HttpUrl) -> String,
    ): String {
        val rewrite = { base: HttpUrl, raw: String ->
            base.resolve(raw.xmlUnescape())
                ?.let(toUrl)
                ?.replace(escapedDashFormat) { "$" + it.groupValues[1] + "%" + it.groupValues[2] + "$" }
                ?.xmlEscape()
        }
        val scopes = ArrayDeque<DashScope>().apply { addLast(DashScope(manifestUrl)) }
        // The player resolves kept relative urls against the url it requested, so after a redirect
        // the manifest needs its final location as a base, unless it declares its own
        val firstPeriod = manifest.indexOf("<Period").takeIf { it >= 0 } ?: manifest.length
        val addBase = redirected && !manifest.substring(0, firstPeriod).contains("<BaseURL")
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
                    val (attribute, equals, quote, raw) = m.destructured
                    val keep = !raw.startsWith("/") && !raw.contains("://") && !climbsAboveRoot(base, raw)
                    val url = if (keep) escapeKept(raw) else rewrite(base, raw)
                    url?.let { "$attribute$equals$quote$it$quote" } ?: m.value
                }
                out.append(if (attributes.isEmpty()) tag.value else tag.value.replace(attributes, rewritten))
                if (addBase && name == "MPD" && selfClosing.isEmpty()) {
                    out.append("<BaseURL>").append(rewrite(manifestUrl, manifestUrl.toString())).append("</BaseURL>")
                }
                if (selfClosing.isEmpty()) scopes.addLast(DashScope(base))
            }
            last = tag.range.last + 1
        }
        out.append(manifest.substring(last))
        return out.toString()
    }

    // The player appends a kept url to the proxy path, which the local server decodes once
    private fun escapeKept(raw: String): String {
        val path = raw.substringBefore('?')
        val escaped = buildString {
            var last = 0
            dashToken.findAll(path).forEach {
                append(ProxyUrl.escape(path.substring(last, it.range.first))).append(it.value)
                last = it.range.last + 1
            }
            append(ProxyUrl.escape(path.substring(last)))
        }
        return escaped + raw.substring(path.length)
    }

    // Climbs above the directories of base at any point: the player would land in the proxy path
    private fun climbsAboveRoot(base: HttpUrl, raw: String): Boolean {
        var depth = 0
        var lowest = 0
        raw.substringBefore('?').substringBefore('#').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> lowest = minOf(lowest, --depth)
                else -> depth++
            }
        }
        return -lowest > base.pathSegments.size - 1
    }

    private val xmlEntity = Regex("""&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);""")

    // One pass, so "&amp;#38;" stays "&#38;"
    private fun String.xmlUnescape() = xmlEntity.replace(this) { m ->
        when (val entity = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
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
        ): ServedResponse {
            val response = try {
                StreamPipeline.fetch(base, method, path, query, headers)
            } catch (e: Exception) {
                Log.e("StreamProxy", "Failed to fetch $path", e)
                return text(502, "Bad Gateway", e.toString(), method)
            } ?: return text(404, "Not Found", "Not a stream proxy url", method)
            val isHead = method.equals("HEAD", ignoreCase = true)
            val length = if (isHead) response.header("Content-Length")?.toLongOrNull() ?: -1 else response.body.contentLength()
            return ServedResponse(
                code = response.code,
                status = "${response.code} ${response.message.ifEmpty { "Status" }}",
                mimeType = response.body.contentType()?.toString(),
                headers = StreamPipeline.passedResponseHeaders.mapNotNull { name -> response.header(name)?.let { name to it } },
                body = response.body.byteStream(),
                length = length,
            )
        }

        // The local servers write a body even for HEAD
        private fun text(code: Int, reason: String, message: String, method: String): ServedResponse {
            val bytes = if (method.equals("HEAD", ignoreCase = true)) ByteArray(0) else message.toByteArray()
            return ServedResponse(code, "$code $reason", "text/plain", emptyList(), ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }
}
