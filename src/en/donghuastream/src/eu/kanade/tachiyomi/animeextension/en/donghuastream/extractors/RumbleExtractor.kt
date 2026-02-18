package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class RumbleExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val id = extractRumbleId(url) ?: return emptyList()
        val sourceUrl = "https://rumble.com/hls-vod/$id/playlist.m3u8"
        return playlistUtils.extractFromHls(sourceUrl, referer = url, subtitleList = emptyList(), videoNameGen = { q -> "$prefix $q" })
    }

    private val regex by lazy { Regex("""rumble\.com/embed/v([a-zA-Z0-9]+)""") }

    fun extractRumbleId(url: String): String? = regex.find(url)?.groupValues?.get(1)
}
