package keiyoushi.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {

    private val firstHttpsRegex by lazy { Regex("""^.*(?=https?://)""") }

    // An origin with an absolute URL glued onto it: `http://example.comhttps://example.com/path`.
    // A scheme can't occur inside a host, so valid URLs never match.
    private val gluedOriginRegex by lazy { Regex("""^https?://[^/?#]*(?=https?://)""") }

    fun fixUrl(url: String): String? = when {
        url.isEmpty() -> null
        // Do not fix JSON objects when passed as urls.
        url.startsWith("{\"") -> url
        url.startsWith("http") -> url.replaceFirst(gluedOriginRegex, "")
        url.startsWith("//") -> "https:$url"
        else -> url.replaceFirst(firstHttpsRegex, "")
    }

    fun fixUrl(url: String, baseUrl: String): String? {
        val baseHttpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        return when {
            url.isEmpty() -> null
            // Do not fix JSON objects when passed as urls.
            url.startsWith("{\"") -> url
            url.startsWith("http") -> url.replaceFirst(gluedOriginRegex, "")
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                // Will be: http[s]://<domain>/<url>
                baseHttpUrl.newBuilder().encodedPath("/").build().toString()
                    .substringBeforeLast("/") + url
            }
            else -> {
                // Will be: http[s]://<domain>/<base paths>/<url>
                val basePath = baseHttpUrl.newBuilder().apply {
                    removePathSegment(baseHttpUrl.pathSize - 1)
                    addPathSegment("")
                    query(null)
                    fragment(null)
                }.build().toString()
                basePath + url
            }
        }
    }
}
