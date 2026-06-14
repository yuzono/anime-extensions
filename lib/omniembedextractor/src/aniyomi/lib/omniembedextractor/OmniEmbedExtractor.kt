package aniyomi.lib.omniembedextractor

import android.util.Log
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.kwikextractor.KwikExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

private const val TAG = "OmniEmbedExtractor"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

private enum class EmbedType {
    OKRU,
    VK,
    DOOD,
    STREAMTAPE,
    MP4UPLOAD,
    STREAMWISH,
    FILEMOON,
    KWIK,
    UNKNOWN,
}

private val EMBED_DOMAIN_MAP = mapOf(
    // VK-family
    "ok.ru" to EmbedType.OKRU,
    "vk.com" to EmbedType.VK,
    // Doodstream
    "doodstream.com" to EmbedType.DOOD,
    "dood.wf" to EmbedType.DOOD,
    "doodstream" to EmbedType.DOOD,
    // Streamtape
    "streamtape.com" to EmbedType.STREAMTAPE,
    "streamtape.net" to EmbedType.STREAMTAPE,
    // Mp4Upload
    "mp4upload.com" to EmbedType.MP4UPLOAD,
    // StreamWish (+ mirrors)
    "streamwish.com" to EmbedType.STREAMWISH,
    "niramirus.com" to EmbedType.STREAMWISH,
    "medixiru.com" to EmbedType.STREAMWISH,
    // Filemoon
    "filemoon.sx" to EmbedType.FILEMOON,
    "filemoon.to" to EmbedType.FILEMOON,
    // Kwik
    "kwik.cx" to EmbedType.KWIK,
)

/**
 * Universal embed video extractor that detects the embed domain from a URL and
 * delegates to the appropriate per-host extractor.
 *
 * Supported embed domains:
 * - ok.ru, vk.com → OkruExtractor / VkExtractor
 * - doodstream.*, dood.wf → DoodExtractor
 * - streamtape.com, streamtape.net → StreamTapeExtractor
 * - mp4upload.com → Mp4uploadExtractor
 * - streamwish.com, niramirus.com, medixiru.com → StreamWishExtractor
 * - filemoon.sx, filemoon.to → FilemoonExtractor
 * - kwik.cx → KwikExtractor
 */
class OmniEmbedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    // Lazy-initialized per-host extractors
    private val okruExtractor by lazy { OkruExtractor(client, headers) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val kwikExtractor by lazy { KwikExtractor(client, headers) }

    /**
     * Detect the embed type from a URL and extract videos.
     *
     * @param embedUrl the third-party embed URL
     * @param referer the referer of the page that embeds this URL
     * @param qualityLabel the quality label prefix for the extracted videos
     * @param subtitles subtitle tracks to attach to the extracted videos
     * @return a list of [Video] objects, or empty if extraction fails
     */
    fun extractVideos(
        embedUrl: String,
        referer: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> {
        val embedType = detectEmbedType(embedUrl)
        Log.d(TAG, "extractVideos: type=$embedType url=${embedUrl.take(100)}")

        return try {
            when (embedType) {
                EmbedType.OKRU -> extractFromOkru(embedUrl, qualityLabel)
                EmbedType.VK -> extractFromVk(embedUrl, qualityLabel)
                EmbedType.DOOD -> extractFromDood(embedUrl, qualityLabel)
                EmbedType.STREAMTAPE -> extractFromStreamtape(embedUrl, qualityLabel)
                EmbedType.MP4UPLOAD -> extractFromMp4upload(embedUrl, qualityLabel)
                EmbedType.STREAMWISH -> extractFromStreamwish(embedUrl, qualityLabel)
                EmbedType.FILEMOON -> extractFromFilemoon(embedUrl, qualityLabel)
                EmbedType.KWIK -> extractFromKwik(embedUrl, qualityLabel, subtitles)
                EmbedType.UNKNOWN -> {
                    Log.w(TAG, "Unknown embed domain: ${embedUrl.take(100)}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract from ${embedType.name}: ${e.message}")
            emptyList()
        }
    }

    private fun detectEmbedType(url: String): EmbedType {
        val host = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            url.substringAfter("://").substringBefore("/").substringBefore("?").lowercase()
        }

        for ((domain, type) in EMBED_DOMAIN_MAP) {
            if (host == domain || host.endsWith(".$domain")) {
                return type
            }
        }
        return EmbedType.UNKNOWN
    }

    // ============================== OKRU ==============================

    private fun extractFromOkru(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        okruExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== VK ==============================

    private fun extractFromVk(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vkExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== DOODSTREAM ==============================

    private fun extractFromDood(embedUrl: String, qualityLabel: String): List<Video> = doodExtractor.videosFromUrl(embedUrl, quality = qualityLabel)

    // ============================== STREAMTAPE ==============================

    private fun extractFromStreamtape(embedUrl: String, qualityLabel: String): List<Video> = streamtapeExtractor.videosFromUrl(embedUrl, quality = qualityLabel)

    // ============================== MP4UPLOAD ==============================

    private fun extractFromMp4upload(embedUrl: String, qualityLabel: String): List<Video> {
        val mp4Headers = Headers.Builder()
            .set("Referer", "https://mp4upload.com/")
            .build()
        return mp4uploadExtractor.videosFromUrl(embedUrl, mp4Headers, prefix = qualityLabel)
    }

    // ============================== STREAMWISH ==============================

    private fun extractFromStreamwish(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamwishExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== FILEMOON ==============================

    private fun extractFromFilemoon(embedUrl: String, qualityLabel: String): List<Video> {
        val fmHeaders = Headers.Builder()
            .set("Referer", embedUrl)
            .set("User-Agent", USER_AGENT)
            .build()
        return filemoonExtractor.videosFromUrl(embedUrl, prefix = "$qualityLabel ", headers = fmHeaders)
    }

    // ============================== KWIK ==============================

    private fun extractFromKwik(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> = kwikExtractor.videosFromUrl(
        url = embedUrl,
        prefix = qualityLabel,
        subtitleList = subtitles,
    )
}
