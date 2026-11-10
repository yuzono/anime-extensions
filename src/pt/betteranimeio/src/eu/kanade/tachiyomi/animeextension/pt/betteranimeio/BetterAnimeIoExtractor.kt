package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import android.util.Base64
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

class BetterAnimeIoExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    suspend fun extractVideosFromApi(encodedSource: String): List<Video> = try {
        val decodedSource = Base64.decode(encodedSource, Base64.DEFAULT)
        val token = String(decodedSource).reversed()
        val url = "$BLOGGER_URL$token"

        bloggerExtractor.videosFromUrl(url, headers)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val BLOGGER_URL = "https://www.blogger.com/video.g?token="
    }
}
