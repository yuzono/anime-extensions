package aniyomi.lib.uqloadextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    companion object {
        const val BASE_URL = "https://uqload.is/"

        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
        private val packedSourceRegex by lazy { Regex(""""((?:https?:/)?/[^"]*(?:\.m3u8|\.mp4)[^"]*)"""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()

        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"
        val videoHeaders = Headers.headersOf("Referer", fixedUrl)

        val script = doc.selectFirst("script:containsData(sources:)")?.data()
        if (script != null) {
            val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
                .takeIf(String::isNotBlank)
                ?.takeIf { it.startsWith("http") }
            if (videoUrl != null) {
                return videosFromSource(videoUrl, quality, fixedUrl, videoHeaders)
            }
        }

        val packed = doc.select("script")
            .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
            ?.html()
            ?.let(::autoUnpacker)
            ?: return emptyList()

        val sources = packedSourceRegex.findAll(packed).mapNotNull { match ->
            val link = match.groupValues[1]
            when {
                link.startsWith("http") -> link
                link.startsWith("//") -> "https:$link"
                else -> BASE_URL.dropLast(1) + link
            }.takeIf { it.toHttpUrlOrNull() != null }
        }.distinct().toList()

        return sources.flatMap { videosFromSource(it, quality, fixedUrl, videoHeaders) }
            .distinctBy { it.url }
    }

    /**
     * Multi-quality streams are served as a single `_,l,n,h,.urlset/master.m3u8` playlist, so the
     * variants have to be extracted to expose anything more than one unlabelled entry. Falls back
     * to the master playlist itself when it cannot be read.
     */
    private fun videosFromSource(
        videoUrl: String,
        quality: String,
        referer: String,
        videoHeaders: Headers,
    ): List<Video> {
        if (!videoUrl.contains(".m3u8")) {
            return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
        }

        return playlistUtils.extractFromHls(
            videoUrl,
            referer = referer,
            // A single-stream playlist has no resolution to report and is named "Video";
            // keep the plain label there so those entries read as they did before.
            videoNameGen = { variant -> if (variant == "Video") quality else "$quality - $variant" },
        ).ifEmpty { listOf(Video(videoUrl, quality, videoUrl, videoHeaders)) }
    }
}
