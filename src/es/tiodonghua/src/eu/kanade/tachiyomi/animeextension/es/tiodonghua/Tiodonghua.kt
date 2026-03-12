package eu.kanade.tachiyomi.animeextension.es.tiodonghua

import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class Tiodonghua :
    AnimeStream(
        "es",
        "Tiodonghua.com",
        "https://anime.tiodonghua.com",
    ) {

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> = when (name) {
        "Okru" -> okruExtractor.videosFromUrl(url)
        "Voe" -> voeExtractor.videosFromUrl(url)
        "YourUpload" -> youruploadExtractor.videoFromUrl(url, headers)
        "MixDrop" -> mixdropExtractor.videosFromUrl(url)
        else -> emptyList()
    }

    override val fetchFilters: Boolean
        get() = false
}
