@file:Suppress(
    "unused_parameter",
    "RedundantSuspendModifier",
    "UnusedReceiverParameter",
    "FunctionName",
)

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

private val DEFAULT_CACHE_CONTROL: CacheControl = throw Exception("Stub!")
private val DEFAULT_HEADERS: Headers = throw Exception("Stub!")
private val DEFAULT_BODY: RequestBody = throw Exception("Stub!")

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = throw Exception("Stub!")

/**
 * @since extensions-lib 14
 */
fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = throw Exception("Stub!")

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = throw Exception("Stub!")

/**
 * Send a GET request
 *
 * @since extensions-lib 16
 *
 * @param url [String]          the url to send the request to
 * @param headers [Headers]     the request headers, defaults to [DEFAULT_HEADERS]; pass the source's `headers` explicitly
 * @param cache [CacheControl]  the cache used, defaults to the one provided by the app
 */
suspend fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = throw Exception("Stub!")

/**
 * Send a GET request
 *
 * @since extensions-lib 16
 *
 * @param url [HttpUrl]         the url to send the request to
 * @param headers [Headers]     the request headers, defaults to [DEFAULT_HEADERS]; pass the source's `headers` explicitly
 * @param cache [CacheControl]  the cache used, defaults to the one provided by the app
 */
suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = throw Exception("Stub!")

/**
 * Send a POST request
 *
 * @since extensions-lib 16
 *
 * @param url [String]          the url to send the request to
 * @param headers [Headers]     the request headers, defaults to [DEFAULT_HEADERS]; pass the source's `headers` explicitly
 * @param body [RequestBody]    the request body, defaults to the one provided by the app
 * @param cache [CacheControl]  the cache used, defaults to the one provided by the app
 */
suspend fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = throw Exception("Stub!")
