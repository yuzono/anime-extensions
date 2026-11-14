package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import keiyoushi.network.get
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

class VideoResolver(
    private val client: OkHttpClient,
    private val headerCandidates: List<Headers>,

) {

    suspend fun resolve(videoUrl: String): ResolvedVideo {
        var failure: IOException? = null
        for (headers in headerCandidates) {
            try {
                if (urlExists(videoUrl, headers)) return ResolvedVideo(videoUrl, headers)
            } catch (error: IOException) {
                failure = error
            }
        }
        failure?.let { throw it }
        throw IOException("Media URL is unavailable")
    }

    private suspend fun urlExists(url: String, headers: Headers): Boolean {
        currentCoroutineContext().ensureActive()
        val probeHeaders = headers.newBuilder().set("Range", "bytes=0-0").build()
        return client.get(url, probeHeaders, cacheControl = CacheControl.Builder().build(), ensureSuccess = false).use {
            currentCoroutineContext().ensureActive()
            when {
                it.isSuccessful -> true
                it.code == 404 || it.code == 410 -> false
                else -> throw IOException("Cannot determine media availability: HTTP ${it.code}")
            }
        }
    }

    data class ResolvedVideo(
        val url: String,
        val headers: Headers,
    )
}
