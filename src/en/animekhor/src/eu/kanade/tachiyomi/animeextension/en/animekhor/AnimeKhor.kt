package eu.kanade.tachiyomi.animeextension.en.animekhor

import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.TurbovidExtractor
import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.VidaraExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class AnimeKhor :
    AnimeStream(
        "en",
        "AnimeKhor",
        "https://animekhor.org",
    ) {
    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = super.animeDetailsParse(document).apply {
        description = getAnimeDescription(document)
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.useAsJsoup()
        val episodes = doc.select(episodeListSelector()).map(::episodeFromElement)
        val firstEp = episodes.firstOrNull() ?: return episodes
        val latestUploadDate = doc.selectFirst("time[itemprop=dateModified]")?.attr("datetime")
            ?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).tryParse(it) }
            ?: 0L
        if (latestUploadDate > 0L) {
            val diff = abs(latestUploadDate - firstEp.date_upload)
            if (firstEp.date_upload == 0L || diff < 86_400_000L * 2) {
                firstEp.date_upload = latestUploadDate
            }
        }
        return episodes
    }

    // ============================ Extractors ==============================

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val streamWishExtractor by lazy {
        val docHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        StreamWishExtractor(client, docHeaders)
    }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidaraExtractor by lazy { VidaraExtractor(client, headers) }
    private val turbovidExtractor by lazy { TurbovidExtractor(client, headers) }

    // ============================ Video Links =============================

    override suspend fun getHosterUrl(encodedData: String): String = try {
        super.getHosterUrl(encodedData)
    } catch (_: Exception) {
        ""
    }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        if (url.isBlank()) return emptyList()
        val prefix = "$name - "
        return when {
            url.contains("dailymotion.com") || name.contains("VidPlayer", true) -> {
                val cleanUrl = if (url.contains("dailymotion.com/embed/embed-")) {
                    val id = url.substringAfter("dailymotion.com/embed/embed-").substringBefore(".")
                    "https://www.dailymotion.com/embed/video/$id"
                } else {
                    url
                }
                dailymotionExtractor.videosFromUrl(cleanUrl, prefix = prefix)
            }

            url.contains("vidara") || url.contains("vidvara") || name.contains("DaraPlayer", true) -> {
                vidaraExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("turbovid") || url.contains("turboviplay") || url.contains("emturbovid") -> {
                turbovidExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("ok.ru") -> {
                okruExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("rumble.com") -> {
                rumbleExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("ahvsh.com") || url.contains("vidhide") || name.contains("streamhide", true) -> {
                vidHideExtractor.videosFromUrl(url) { "$prefix$it" }
            }

            url.contains("wish") -> {
                streamWishExtractor.videosFromUrl(url, prefix)
            }

            url.contains("mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefix)
            }

            url.contains("dood") || url.contains("do0od") || url.contains("ds2play") -> {
                doodExtractor.videosFromUrl(url, quality = prefix)
            }

            else -> emptyList()
        }
    }
}
