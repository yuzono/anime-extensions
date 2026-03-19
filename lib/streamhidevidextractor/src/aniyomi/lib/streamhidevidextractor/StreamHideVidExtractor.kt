package aniyomi.lib.streamhidevidextractor

import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamHideVidExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val fileRegex by lazy { Regex("""(?:file|src):"([^"]+)"""") }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamHideVid - $quality" }): List<Video> {
        val doc = client.newCall(GET(getEmbedUrl(url), headers)).execute().useAsJsoup()

        val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()
            ?.let { script ->
                if (script.contains("eval(function(p,a,c")) {
                    JsUnpacker.unpackAndCombine(script)
                } else {
                    script
                }
            }
        val masterUrl = scriptBody
            ?.substringAfter("source", "")
            ?.let {
                fileRegex.find(it)?.groupValues?.get(1)
            }
            ?.takeIf(String::isNotBlank)
            ?.let { UrlUtils.fixUrl(it, url) }
            ?: return emptyList()

        return playlistUtils.extractFromHls(masterUrl, url, videoNameGen = videoNameGen)
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}
