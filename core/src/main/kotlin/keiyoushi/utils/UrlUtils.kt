package keiyoushi.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {

    private val firstHttpsRegex by lazy { Regex("""^.*(?=https?://)""") }

    fun fixUrl(url: String): String? = when {
        url.isEmpty() -> null
        url.startsWith("http") ||
            // Do not fix JSON objects when passed as urls.
            url.startsWith("{\"") -> url
        url.startsWith("//") -> "https:$url"
        else -> url.replaceFirst(firstHttpsRegex, "")
    }

    fun fixUrl(url: String, baseUrl: String): String? {
        val baseHttpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        return when {
            url.isEmpty() -> null
            url.startsWith("http") ||
                // Do not fix JSON objects when passed as urls.
                url.startsWith("{\"") -> url
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
