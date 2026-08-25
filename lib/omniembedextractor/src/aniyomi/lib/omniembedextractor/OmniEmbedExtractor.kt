package aniyomi.lib.omniembedextractor

import android.util.Log
import aniyomi.lib.amazonextractor.AmazonExtractor
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.fusevideoextractor.FusevideoExtractor
import aniyomi.lib.kwikextractor.KwikExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.streamdavextractor.StreamDavExtractor
import aniyomi.lib.streamhubextractor.StreamHubExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamplayextractor.StreamPlayExtractor
import aniyomi.lib.streamsilkextractor.StreamSilkExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamupextractor.StreamupExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vidoextractor.VidoExtractor
import aniyomi.lib.vkextractor.VkExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.vudeoextractor.VudeoExtractor
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

    // Tier 1: Simple extractors
    VOE,
    STREAMLARE,
    STREAMHUB,
    VIDGUARD,
    SENDVID,
    STREAMDAV,
    STREAMSILK,
    VIDO,
    VUDEO,
    UPSTREAM,
    SIBNET,
    RUMBLE,
    AMAZON,
    FUSEVIDEO,
    LULU,
    BUZZHEAVIER,
    FASTREAM,
    VIDBOM,
    PIXELDRAIN,
    MIXDROP,

    // Tier 2: Suspend extractors
    VIDMOLY,
    VIDHIDE,
    STREAMPLAY,
    STREAMUP,
    UQLOAD,
    BLOGGER,
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
    // Tier 1: Simple extractors
    "voe.sx" to EmbedType.VOE,
    "voe.to" to EmbedType.VOE,
    "voe.sg" to EmbedType.VOE,
    "voe.pm" to EmbedType.VOE,
    "voe.sh" to EmbedType.VOE,
    "voe.st" to EmbedType.VOE,
    "voe.cloud" to EmbedType.VOE,
    "streamlare.com" to EmbedType.STREAMLARE,
    "slwatch.co" to EmbedType.STREAMLARE,
    "streamhub.gg" to EmbedType.STREAMHUB,
    "vidguard.app" to EmbedType.VIDGUARD,
    "vidguard.io" to EmbedType.VIDGUARD,
    "vgf.play" to EmbedType.VIDGUARD,
    "sendvid.com" to EmbedType.SENDVID,
    "streamdav.com" to EmbedType.STREAMDAV,
    "streamsilk.com" to EmbedType.STREAMSILK,
    "vido.lol" to EmbedType.VIDO,
    "vidoza.net" to EmbedType.VIDO,
    "vudeo.co" to EmbedType.VUDEO,
    "upstream.to" to EmbedType.UPSTREAM,
    "sibnet.ru" to EmbedType.SIBNET,
    "rumble.com" to EmbedType.RUMBLE,
    "amazon.com" to EmbedType.AMAZON,
    "fusevideo.com" to EmbedType.FUSEVIDEO,
    "luluvdo.com" to EmbedType.LULU,
    "buzzheavier.com" to EmbedType.BUZZHEAVIER,
    "fastream.to" to EmbedType.FASTREAM,
    "vidbom.com" to EmbedType.VIDBOM,
    "vidbem.com" to EmbedType.VIDBOM,
    "vidbm.com" to EmbedType.VIDBOM,
    "vedpom.com" to EmbedType.VIDBOM,
    "pixeldrain.com" to EmbedType.PIXELDRAIN,
    "mixdrop.ag" to EmbedType.MIXDROP,
    "mixdrop.co" to EmbedType.MIXDROP,
    "mixdrop.to" to EmbedType.MIXDROP,
    // Tier 2: Suspend extractors
    "vidmoly.to" to EmbedType.VIDMOLY,
    "vidmoly.biz" to EmbedType.VIDMOLY,
    "vidhide.com" to EmbedType.VIDHIDE,
    "streamplay.co.in" to EmbedType.STREAMPLAY,
    "streamup.cc" to EmbedType.STREAMUP,
    "uqload.co" to EmbedType.UQLOAD,
    "uqload.is" to EmbedType.UQLOAD,
    "blogger.com" to EmbedType.BLOGGER,
    "bp.blogspot.com" to EmbedType.BLOGGER,
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
 * - voe.sx, voe.to, etc. → VoeExtractor
 * - streamlare.com, slwatch.co → StreamlareExtractor
 * - streamhub.gg → StreamHubExtractor
 * - vidguard.app, vidguard.io → VidGuardExtractor
 * - sendvid.com → SendvidExtractor
 * - streamdav.com → StreamDavExtractor
 * - streamsilk.com → StreamSilkExtractor
 * - vido.lol, vidoza.net → VidoExtractor
 * - vudeo.co → VudeoExtractor
 * - upstream.to → UpstreamExtractor
 * - sibnet.ru → SibnetExtractor
 * - rumble.com → RumbleExtractor
 * - amazon.com → AmazonExtractor
 * - fusevideo.com → FusevideoExtractor
 * - luluvdo.com → LuluExtractor
 * - buzzheavier.com → BuzzheavierExtractor
 * - fastream.to → FastreamExtractor
 * - vidbom.com, vidbem.com, vidbm.com, vedpom.com → VidBomExtractor
 * - pixeldrain.com → PixelDrainExtractor
 * - mixdrop.ag, mixdrop.co, mixdrop.to → MixDropExtractor
 * - vidmoly.to, vidmoly.biz → VidMolyExtractor
 * - vidhide.com → VidHideExtractor
 * - streamplay.co.in → StreamPlayExtractor
 * - streamup.cc → StreamupExtractor
 * - uqload.co, uqload.is → UqloadExtractor
 * - blogger.com, bp.blogspot.com → BloggerExtractor
 */
class OmniEmbedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    // Lazy-initialized per-host extractors
    // Original extractors
    private val okruExtractor by lazy { OkruExtractor(client, headers) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val kwikExtractor by lazy { KwikExtractor(client, headers) }

    // Tier 1: Simple extractors
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val streamHubExtractor by lazy { StreamHubExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamDavExtractor by lazy { StreamDavExtractor(client) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client, headers) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val amazonExtractor by lazy { AmazonExtractor(client) }
    private val fusevideoExtractor by lazy { FusevideoExtractor(client, headers) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val buzzheavierExtractor by lazy { BuzzheavierExtractor(client, headers) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    // Tier 2: Suspend extractors (use runBlocking)
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamPlayExtractor by lazy { StreamPlayExtractor(client, headers) }
    private val streamupExtractor by lazy { StreamupExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    /**
     * Detect the embed type from a URL and extract videos.
     *
     * @param embedUrl the third-party embed URL
     * @param qualityLabel the quality label prefix for the extracted videos
     * @param subtitles subtitle tracks to attach to the extracted videos
     * @return a list of [Video] objects, or empty if extraction fails
     */
    fun extractVideos(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> {
        val embedType = detectEmbedType(embedUrl)
        Log.d(TAG, "extractVideos: type=$embedType url=${embedUrl.take(100)}")

        return try {
            when (embedType) {
                // Original extractors
                EmbedType.OKRU -> extractFromOkru(embedUrl, qualityLabel)
                EmbedType.VK -> extractFromVk(embedUrl, qualityLabel)
                EmbedType.DOOD -> extractFromDood(embedUrl, qualityLabel)
                EmbedType.STREAMTAPE -> extractFromStreamtape(embedUrl, qualityLabel)
                EmbedType.MP4UPLOAD -> extractFromMp4upload(embedUrl, qualityLabel)
                EmbedType.STREAMWISH -> extractFromStreamwish(embedUrl, qualityLabel)
                EmbedType.FILEMOON -> extractFromFilemoon(embedUrl, qualityLabel)
                EmbedType.KWIK -> extractFromKwik(embedUrl, qualityLabel, subtitles)
                // Tier 1: Simple extractors
                EmbedType.VOE -> extractFromVoe(embedUrl, qualityLabel)
                EmbedType.STREAMLARE -> extractFromStreamlare(embedUrl, qualityLabel)
                EmbedType.STREAMHUB -> extractFromStreamhub(embedUrl, qualityLabel)
                EmbedType.VIDGUARD -> extractFromVidguard(embedUrl, qualityLabel)
                EmbedType.SENDVID -> extractFromSendvid(embedUrl, qualityLabel)
                EmbedType.STREAMDAV -> extractFromStreamdav(embedUrl, qualityLabel)
                EmbedType.STREAMSILK -> extractFromStreamsilk(embedUrl, qualityLabel)
                EmbedType.VIDO -> extractFromVido(embedUrl, qualityLabel)
                EmbedType.VUDEO -> extractFromVudeo(embedUrl, qualityLabel)
                EmbedType.UPSTREAM -> extractFromUpstream(embedUrl, qualityLabel)
                EmbedType.SIBNET -> extractFromSibnet(embedUrl, qualityLabel)
                EmbedType.RUMBLE -> extractFromRumble(embedUrl, qualityLabel)
                EmbedType.AMAZON -> extractFromAmazon(embedUrl, qualityLabel)
                EmbedType.FUSEVIDEO -> extractFromFusevideo(embedUrl, qualityLabel)
                EmbedType.LULU -> extractFromLulu(embedUrl, qualityLabel)
                EmbedType.BUZZHEAVIER -> extractFromBuzzheavier(embedUrl, qualityLabel)
                EmbedType.FASTREAM -> extractFromFastream(embedUrl, qualityLabel)
                EmbedType.VIDBOM -> extractFromVidbom(embedUrl)
                EmbedType.PIXELDRAIN -> extractFromPixeldrain(embedUrl, qualityLabel)
                EmbedType.MIXDROP -> extractFromMixdrop(embedUrl, qualityLabel)
                // Tier 2: Suspend extractors
                EmbedType.VIDMOLY -> extractFromVidmoly(embedUrl, qualityLabel)
                EmbedType.VIDHIDE -> extractFromVidhide(embedUrl, qualityLabel)
                EmbedType.STREAMPLAY -> extractFromStreamplay(embedUrl, qualityLabel)
                EmbedType.STREAMUP -> extractFromStreamup(embedUrl, qualityLabel)
                EmbedType.UQLOAD -> extractFromUqload(embedUrl, qualityLabel)
                EmbedType.BLOGGER -> extractFromBlogger(embedUrl, qualityLabel, subtitles)
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

    // ============================== Original extractors ==============================

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

    // ============================== Tier 1: Simple extractors ==============================

    // ============================== VOE ==============================

    private fun extractFromVoe(embedUrl: String, qualityLabel: String): List<Video> = voeExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== STREAMLARE ==============================

    private fun extractFromStreamlare(embedUrl: String, qualityLabel: String): List<Video> = streamlareExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== STREAMHUB ==============================

    private fun extractFromStreamhub(embedUrl: String, qualityLabel: String): List<Video> = streamHubExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== VIDGUARD ==============================

    private fun extractFromVidguard(embedUrl: String, qualityLabel: String): List<Video> = vidGuardExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== SENDVID ==============================

    private fun extractFromSendvid(embedUrl: String, qualityLabel: String): List<Video> = sendvidExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== STREAMDAV ==============================

    private fun extractFromStreamdav(embedUrl: String, qualityLabel: String): List<Video> = streamDavExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== STREAMSILK ==============================

    private fun extractFromStreamsilk(embedUrl: String, qualityLabel: String): List<Video> = streamSilkExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== VIDO ==============================

    private fun extractFromVido(embedUrl: String, qualityLabel: String): List<Video> = vidoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== VUDEO ==============================

    private fun extractFromVudeo(embedUrl: String, qualityLabel: String): List<Video> = vudeoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== UPSTREAM ==============================

    private fun extractFromUpstream(embedUrl: String, qualityLabel: String): List<Video> = upstreamExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== SIBNET ==============================

    private fun extractFromSibnet(embedUrl: String, qualityLabel: String): List<Video> = sibnetExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== RUMBLE ==============================

    private fun extractFromRumble(embedUrl: String, qualityLabel: String): List<Video> = rumbleExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== AMAZON ==============================

    private fun extractFromAmazon(embedUrl: String, qualityLabel: String): List<Video> = amazonExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== FUSEVIDEO ==============================

    private fun extractFromFusevideo(embedUrl: String, qualityLabel: String): List<Video> = fusevideoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== LULU ==============================

    private fun extractFromLulu(embedUrl: String, qualityLabel: String): List<Video> = luluExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== BUZZHEAVIER ==============================

    private fun extractFromBuzzheavier(embedUrl: String, qualityLabel: String): List<Video> = buzzheavierExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== FASTREAM ==============================

    private fun extractFromFastream(embedUrl: String, qualityLabel: String): List<Video> = fastreamExtractor.videosFromUrl(embedUrl, prefix = "$qualityLabel ")

    // ============================== VIDBOM ==============================

    private fun extractFromVidbom(embedUrl: String): List<Video> = vidBomExtractor.videosFromUrl(embedUrl)

    // ============================== PIXELDRAIN ==============================

    private fun extractFromPixeldrain(embedUrl: String, qualityLabel: String): List<Video> = pixelDrainExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== MIXDROP ==============================

    private fun extractFromMixdrop(embedUrl: String, qualityLabel: String): List<Video> = mixDropExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    // ============================== Tier 2: Suspend extractors ==============================

    // ============================== VIDMOLY ==============================

    private fun extractFromVidmoly(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vidMolyExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== VIDHIDE ==============================

    private fun extractFromVidhide(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vidHideExtractor.videosFromUrl(embedUrl, videoNameGen = { quality -> "$qualityLabel VidHide - $quality" })
    }

    // ============================== STREAMPLAY ==============================

    private fun extractFromStreamplay(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamPlayExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== STREAMUP ==============================

    private fun extractFromStreamup(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamupExtractor.getVideosFromUrl(embedUrl, headers, qualityLabel)
    }

    // ============================== UQLOAD ==============================

    private fun extractFromUqload(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        uqloadExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    // ============================== BLOGGER ==============================

    private fun extractFromBlogger(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> = runBlocking {
        bloggerExtractor.videosFromUrl(embedUrl, headers, suffix = qualityLabel)
    }
}
