package keiyoushi.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {

    private val firstHttpsRegex by lazy { Regex("""^.*(?=https?://)""") }
    private val VIDEO_URL_REGEX by lazy {
        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8|mpd|webm|mkv)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
    }
    private val M3U8_URL_REGEX by lazy {
        Regex("""https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
    }
    private val MPD_URL_REGEX by lazy {
        Regex("""https?://[^\s"'<>]+\.mpd(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
    }

    fun fixUrl(url: String): String? = when {
        url.isEmpty() -> null
        // Do not fix JSON objects when passed as urls.
        url.startsWith("{\"") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") && url.substringAfter("://").contains("://") ->
            url.replaceFirst(firstHttpsRegex, "")
        url.startsWith("http") -> url
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

    fun isVideoUrl(url: String): Boolean = url.contains(VIDEO_URL_REGEX)

    fun isM3u8Url(url: String): Boolean = url.contains(M3U8_URL_REGEX)

    fun isMpdUrl(url: String): Boolean = url.contains(MPD_URL_REGEX)

    fun extractVideoUrls(text: String): List<String> = VIDEO_URL_REGEX.findAll(text)
        .map { it.value }
        .distinct()
        .toList()

    fun extractM3u8Urls(text: String): List<String> = M3U8_URL_REGEX.findAll(text)
        .map { it.value }
        .distinct()
        .toList()

    fun extractMpdUrls(text: String): List<String> = MPD_URL_REGEX.findAll(text)
        .map { it.value }
        .distinct()
        .toList()

    fun fixJsonUrl(url: String): String = url
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\/", "/")
}
