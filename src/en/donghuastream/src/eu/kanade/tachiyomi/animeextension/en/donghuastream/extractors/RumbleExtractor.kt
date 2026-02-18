package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class RumbleExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val sourceUrl = "https://rumble.com/hls-vod/${extractRumbleId(url)!!}/playlist.m3u8"
        return playlistUtils.extractFromHls(sourceUrl, referer = url, subtitleList = emptyList(), videoNameGen = { q -> "$prefix $q" })
    }

    fun extractRumbleId(url: String): String? {
        val regex = Regex("""rumble\.com/embed/v([a-zA-Z0-9]+)""")
        return regex.find(url)?.groupValues?.get(1)
    }
}
