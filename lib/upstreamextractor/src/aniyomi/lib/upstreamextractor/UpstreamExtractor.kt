package aniyomi.lib.upstreamextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UpstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> = runCatching {
        val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")!!.data()
        val masterUrl = JsUnpacker.unpackAndCombine(jsE)!!.substringAfter("{file:\"").substringBefore("\"}")
        PlaylistUtils(client).extractFromHls(masterUrl, videoNameGen = { "${prefix}Upstream - $it" })
    }.getOrDefault(emptyList())
}
