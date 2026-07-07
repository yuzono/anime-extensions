package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import android.util.Log
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.StrmupExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.TurboVidHlsExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class Anikyuu :
    AnimeStream(
        "pt-BR",
        "Anikyuu",
        "https://anikyuu.to",
    ) {
    private val tag by lazy { javaClass.simpleName }

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val strmupExtractor by lazy { StrmupExtractor(client, headers) }
    private val turboVidHlsExtractor by lazy { TurboVidHlsExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // The real synopsis lives in .entry-content[itemprop=description] (inside .bixbox.synp).
    // div.info-content also contains a .desc with SEO spam that appears earlier in the DOM,
    // so the base selector picks the wrong one. Target the synopsis explicitly.
    override val animeDescriptionSelector = ".entry-content[itemprop=description]"

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val lowerName = name.lowercase()
        Log.d(tag, "Fetching videos from: $url")

        var videos: List<Video> = when {
            "filemoon" in url -> filemoonExtractor.videosFromUrl(url, headers = headers, referer = baseUrl)
            "strmup.to" in url -> strmupExtractor.videosFromUrl(url)
            "byse" in url -> filemoonExtractor.videosFromUrl(url, headers = headers, referer = baseUrl)
            "emturbovid.com" in url -> turboVidHlsExtractor.getVideos(url)
            "turbovidhls.com" in url -> turboVidHlsExtractor.getVideos(url)
            "filemoon" in lowerName -> filemoonExtractor.videosFromUrl(url, headers = headers, referer = baseUrl)

            else -> emptyList()
        }

        if (videos.isEmpty()) {
            Log.d(tag, "No videos from specific extractors, trying universal extractor: $url")
            videos = universalExtractor.videosFromUrl(url, headers, name)
        }

        if (videos.isEmpty()) {
            Log.w(tag, "No videos found for: $url")
        }

        return videos
    }
}
