package eu.kanade.tachiyomi.lib.megamaxmultiserver

import android.util.Log
import eu.kanade.tachiyomi.lib.megamaxmultiserver.dto.IframeResponse
import eu.kanade.tachiyomi.lib.megamaxmultiserver.dto.LeechResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.UrlUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.math.abs

class MegaMaxMultiServer(private val client: OkHttpClient, private val headers: Headers) {

    fun extractUrls(url: String): List<Provider> {
        val type = if ("/iframe/" in url) "mirror" else "leech"
        val newHeaders = headers.newBuilder()
            .add("X-Inertia", "true")
            .add("X-Inertia-Partial-Component", "files/$type/video")
            .add("X-Inertia-Partial-Data", "streams")
            .add("X-Inertia-Version", getInertiaVersion(url))
            .build()
        val response = client.newCall(GET(url, newHeaders)).execute()
        val urls = mutableListOf<Provider>()
        if (type == "mirror") {
            val resolved = response.parseAs<IframeResponse>()
            resolved.props.streams.data.forEach {
                val quality = it.resolution.substringAfter("x").let(::stnQuality)
                val size = it.size.let(::convertSize)
                it.mirrors.forEach { mirror ->
                    val link = UrlUtils.fixUrl(mirror.link)
                    urls += Provider(link, mirror.driver, quality, size)
                }
            }
        } else {
            val resolved = response.parseAs<LeechResponse>()
            resolved.props.streams.data.forEach {
                val size = it.size.let(::convertSize)
                urls += Provider(it.file, "Leech", it.label.substringBefore(" "), size)
            }
        }
        return urls
    }

    private fun getInertiaVersion(url: String): String {
        val response = client.newCall(GET(url, headers)).execute()
        response.body.use { body ->
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                VERSION_REGEX.find(line)?.groupValues?.get(1)?.let { return it }
            }
        }
        Log.e("MegaMaxMultiServer", "Inertia version not found for URL: $url")
        return ""
    }

    data class Provider(val url: String, val name: String, val quality: String, val size: String)

    private fun stnQuality(quality: String): String {
        val intQuality = quality.toIntOrNull() ?: return quality
        val standardQualities = listOf(144, 240, 360, 480, 720, 1080)
        val result = standardQualities.minByOrNull { abs(it - intQuality) } ?: return quality
        return "${result}p"
    }

    private fun convertSize(bits: Long): String {
        val bytes = bits / 8
        return when {
            bytes >= 1 shl 30 -> "%.2f GB".format(bytes / (1 shl 30).toDouble())
            bytes >= 1 shl 20 -> "%.2f MB".format(bytes / (1 shl 20).toDouble())
            bytes >= 1 shl 10 -> "%.2f KB".format(bytes / (1 shl 10).toDouble())
            else -> "$bytes bytes"
        }
    }

    companion object {
        private val VERSION_REGEX = Regex(""","version":"(.*?)",""")
    }
}
