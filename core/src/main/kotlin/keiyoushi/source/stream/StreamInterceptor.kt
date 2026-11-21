package keiyoushi.source.stream

import okhttp3.HttpUrl
import okhttp3.Interceptor

/**
 * Interceptor for video streams, e.g. decrypting segments. Videos, tracks and playlist urls it
 * [handles] are served through a local proxy so players outside OkHttp get the same response.
 */
interface StreamInterceptor : Interceptor {
    fun handles(url: HttpUrl): Boolean
}
