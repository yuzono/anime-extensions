package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class TurboVidHlsExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistExtractor by lazy { PlaylistUtils(client, headers) }

    fun getVideos(url: String): List<Video> {
        return try {
            val document = client.newCall(GET(url, headers)).execute().asJsoup()

            val dataHash = document.selectFirst("#video_player[data-hash]")
                ?.attr("data-hash")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val urlPlay = when {
                !dataHash.isNullOrEmpty() -> dataHash
                else -> {
                    val script = document.selectFirst("script:containsData(urlplay)")?.data()
                    URLPLAY.find(script.orEmpty())?.groupValues?.get(1)
                }
            } ?: return emptyList()

            val resolved = when {
                urlPlay.contains(".m3u8", ignoreCase = true) -> urlPlay
                urlPlay.toHttpUrlOrNull() != null -> "$urlPlay/master.m3u8"
                else -> urlPlay
            }

            if (resolved.toHttpUrlOrNull() == null) {
                return emptyList()
            }

            playlistExtractor.extractFromHls(resolved, url, videoNameGen = { quality -> "TurboVidHls: $quality" })
                .distinctBy { it.url }
        } catch (e: Exception) {
            Log.e("TurboVidHlsExtractor", "Failed to extract videos from $url", e)
            emptyList()
        }
    }

    companion object {
        private val URLPLAY = Regex("""urlPlay\s*=\s*['"]([^'"]+)""")
    }
}
