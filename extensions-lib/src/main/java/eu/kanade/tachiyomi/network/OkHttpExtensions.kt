package eu.kanade.tachiyomi.network

import okhttp3.Call
import okhttp3.Response

suspend fun Call.await(): Response = throw Exception("Stub!")

/**
 * @since extensions-lib 14
 */
suspend fun Call.awaitSuccess(): Response = throw Exception("Stub!")

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @since extensions-lib 14
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
