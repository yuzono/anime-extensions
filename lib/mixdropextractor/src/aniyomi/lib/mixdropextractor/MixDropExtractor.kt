package aniyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {

    fun canHandleUrl(url: String) = MIX_DROP_REGEX.containsMatchIn(url)

    fun videoFromUrl(
        url: String,
        quality: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
    ): List<Video> {
        val headers = Headers.headersOf(
            "Referer",
            url,
            "Upgrade-Insecure-Requests",
            "1",
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
        )
        val doc = client.newCall(GET(url, headers = headers)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val videoUrl = "https:" + VIDEO_URL_REGEX.find(unpacked)?.value

        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = "${prefix}MixDrop: ${quality.ifBlank { "Mirror" }}"

        return Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subs + externalSubs).let(::listOf)
    }

    companion object {
        private val MIX_DROP_REGEX by lazy { Regex("""(?://|\.)((?:mi*1*xdro*p\d*(?:jmk)?|md(?:3b0j6hj|bekjwqa|fx9dc8n|y48tn97|zsmutpcvykb))\.(?:c[ho]m?|top?|bz|gl|club|click|vc|ag|pw|net|is|s[ibx]|nu|m[sy]|ps))/[fe]/(\w+)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""[^\s"'<>\\]{7,}\.mp4(?:\?[^"'<>\\]*)?""") }
    }
}
