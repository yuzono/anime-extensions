package eu.kanade.tachiyomi.lib.vidlandextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidLandExtractor(private val client: OkHttpClient) {

    private val videoRegex by lazy { Regex("""hls[34]":\s*"([^"]*)"""") }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(eval)")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        return videoRegex.find(script)?.groupValues?.get(1)?.let {
            playlistUtils.extractFromHls(it, url, videoNameGen = { quality -> "VidLand: $quality" })
        } ?: emptyList()
    }
}
