package eu.kanade.tachiyomi.animeextension.en.animekhor

import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import kotlinx.coroutines.runBlocking

class AnimeKhor :
    AnimeStream(
        "en",
        "AnimeKhor",
        "https://animekhor.org",
    ) {
    // ============================ Video Links =============================

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ahvsh.com") || name.equals("streamhide", true) -> {
                runBlocking { VidHideExtractor(client, headers).videosFromUrl(url) { "$prefix$it" } }
            }

            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url, prefix = prefix)
            }

            url.contains("streamwish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Referer", "$baseUrl/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, prefix)
            }

            // TODO: Videos won't play
//            url.contains("animeabc.xyz") -> {
//                AnimeABCExtractor(client, headers).videosFromUrl(url, prefix = prefix)
//            }
            else -> emptyList()
        }
    }
}
