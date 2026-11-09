package aniyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.unpacker.Unpacker
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<Video> {
        val targetUrl = normalizeUrl(url)
        val host = try {
            targetUrl.toHttpUrl().host
        } catch (_: Exception) {
            "mixdrop.ag"
        }

        val effectiveReferer = if (referer.isNotBlank() && referer != DEFAULT_REFERER) {
            referer
        } else {
            "https://$host/"
        }

        val headers = Headers.headersOf(
            "Referer",
            effectiveReferer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        )

        val doc = try {
            client.newCall(GET(targetUrl, headers)).execute().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val rawVideoUrl = MDCORE_WURL_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: unpacked.substringAfter("Core.wurl=\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            ?: return emptyList()

        val videoUrl = when {
            rawVideoUrl.startsWith("https://") || rawVideoUrl.startsWith("http://") -> rawVideoUrl
            rawVideoUrl.startsWith("//") -> "https:$rawVideoUrl"
            else -> "https://$rawVideoUrl"
        }

        val rawSub = MDCORE_SUB_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: unpacked.substringAfter("Core.remotesub=\"", "").substringBefore('"').takeIf { it.isNotBlank() }

        val subs = rawSub
            ?.takeIf(String::isNotBlank)
            ?.let {
                runCatching {
                    listOf(Track(URLDecoder.decode(it, "utf-8"), "sub"))
                }.getOrDefault(emptyList())
            }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        return listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = quality,
                headers = headers,
                subtitleTracks = subs + externalSubs,
            ),
        )
    }

    fun videosFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ) = videoFromUrl(url, lang, prefix, externalSubs, referer)

    private fun normalizeUrl(url: String): String = url
        .replace("mixdrop.co", "mixdrop.ag")
        .replace("mixdrop.to", "mixdrop.ag")
        .replace("mixdrop.sx", "mixdrop.ag")
        .replace("mixdrop.bz", "mixdrop.ag")
        .replace("mixdrop.ch", "mixdrop.ag")
        .replace("/f/", "/e/")

    companion object {
        private val MDCORE_WURL_REGEX = Regex("""Core\.wurl\s*=\s*["']([^"']+)["']""")
        private val MDCORE_SUB_REGEX = Regex("""Core\.remotesub\s*=\s*["']([^"']+)["']""")
    }
}

private const val DEFAULT_REFERER = "https://mixdrop.ag/"
