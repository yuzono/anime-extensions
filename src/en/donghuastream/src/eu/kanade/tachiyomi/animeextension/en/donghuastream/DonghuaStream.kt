package eu.kanade.tachiyomi.animeextension.en.donghuastream

import eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors.StreamPlayExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class DonghuaStream :
    AnimeStream(
        "en",
        "DonghuaStream",
        "https://donghuastream.org",
    ) {
    override val fetchFilters: Boolean
        get() = false

    // ============================ Video Links =============================

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val streamPlayExtractor by lazy { StreamPlayExtractor(client, headers) }

    private val okRuPlayExtractor by lazy { OkruExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix = prefix)
            url.contains("streamplay") -> streamPlayExtractor.videosFromUrl(url, prefix = prefix)
            url.contains("ok.ru") -> okRuPlayExtractor.videosFromUrl(url, prefix = prefix)
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
