package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * This uses Java Time APIs and is the legacy method, kept
 * for compatibility reasons with existing extensions.
 *
 * Examples:
 *
 * httpUrl = "https://api.anime.com".toHttpUrl(), permits = 5, period = 1, unit = seconds  =>  5 requests per second to api.anime.com
 * httpUrl = "https://cdn.thumbnails.com".toHttpUrl(), permits = 10, period = 2, unit = minutes  =>  10 requests per 2 minutes to cdn.thumbnails.com
 *
 * @since extension-lib 13
 *
 * @param httpUrl [HttpUrl] The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Long]     The limiting duration. Defaults to 1.
 * @param unit [TimeUnit]   The unit of time for the period. Defaults to seconds.
 */
@Suppress("unused_parameter")
@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = throw Exception("Stub!")

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 *
 * httpUrl = "https://api.anime.com".toHttpUrl(), permits = 5, period = 1.seconds =>  5 requests per second to api.anime.com
 * httpUrl = "https://cdn.thumbnails.com".toHttpUrl(), permits = 10, period = 2.minutes  =>  10 requests per 2 minutes to cdn.thumbnails.com
 *
 * @since extension-lib 14
 *
 * @param httpUrl [HttpUrl] The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
@Suppress("unused_parameter")
@Deprecated(
    message = "Default rate limiting implementation is no longer provided. Source developers are now " +
        "responsible for implementing their own rate limiting logic if desired, to prevent forks " +
        "from bypassing it.",
    replaceWith = ReplaceWith("this"),
)
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = throw Exception("Stub!")

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 *
 * url = "https://api.anime.com", permits = 5, period = 1.seconds =>  5 requests per second to api.anime.com
 * url = "https://cdn.thumbnails.com", permits = 10, period = 2.minutes  =>  10 requests per 2 minutes to cdn.thumbnails.com
 *
 * @since extension-lib 14
 *
 * @param url [String]      The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
@Suppress("unused_parameter")
@Deprecated(
    message = "Default rate limiting implementation is no longer provided. Source developers are now " +
        "responsible for implementing their own rate limiting logic if desired, to prevent forks " +
        "from bypassing it.",
    replaceWith = ReplaceWith("this"),
)
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = throw Exception("Stub!")
