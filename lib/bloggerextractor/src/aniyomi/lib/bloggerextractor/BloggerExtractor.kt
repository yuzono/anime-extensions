package aniyomi.lib.bloggerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class BloggerExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String, headers: Headers, suffix: String = ""): List<Video> {
        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()

        return getStreamVideos(body, headers, suffix)
            .ifEmpty { getRpcVideos(url, body, headers, suffix) }
    }

    private fun getStreamVideos(body: String, headers: Headers, suffix: String = ""): List<Video> {
        if (body.contains("errorContainer")) return emptyList()

        return body
            .substringAfter("\"streams\":[", "")
            .substringBefore("]")
            .split("},")
            .mapNotNull {
                val videoUrl = it.substringAfter("\"play_url\":\"").substringBefore('"')
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val format = it.substringAfter("\"format_id\":").substringBefore('}')
                Video(videoUrl, "Blogger - ${qualityFromFormat(format)} $suffix".trimEnd(), videoUrl, headers)
            }
    }

    /**
     * Extract videos from the RPC URL
     * Based on https://github.com/FightFarewellFearless/AniFlix/blob/4b07254fc0051664691fd2f3c001dbd6b43e18ad/src/utils/scrapers/animeSeries.ts#L445
     */
    private suspend fun getRpcVideos(
        url: String,
        body: String,
        headers: Headers,
        suffix: String = "",
    ): List<Video> {
        val token = url.toHttpUrl().queryParameter("token")?.takeIf(String::isNotBlank) ?: return emptyList()

        val formSessionId = body.substringAfter("FdrFJe\":\"").substringBefore("\"")
        val blogId = body.substringAfter("cfb2h\":\"").substringBefore("\"")
        val requestId = ((System.currentTimeMillis() / 1000L) % 86400L).toString()

        val rpcUrl = BLOGGER_BASE.toHttpUrl().newBuilder()
            .addPathSegments("_/BloggerVideoPlayerUi/data/batchexecute")
            .addQueryParameter("rpcids", "WcwnYd")
            .addQueryParameter("source-path", "/video.g")
            .addQueryParameter("f.sid", formSessionId)
            .addQueryParameter("bl", blogId)
            .addQueryParameter("hl", "en-US")
            .addQueryParameter("_reqid", requestId)
            .addQueryParameter("rt", "c")
            .build()
            .toString()

        val rpcBody =
            "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D&".toRequestBody()
        val rpcHeaders = Headers.headersOf(
            "accept", "*/*",
            "accept-language", "en-US,en;q=0.9",
            "content-type", "application/x-www-form-urlencoded;charset=UTF-8",
            "priority", "u=1, i",
            "sec-fetch-dest", "empty",
            "sec-fetch-mode", "cors",
            "sec-fetch-site", "same-origin",
            "User-Agent", headers["User-Agent"] ?: "",
            "x-same-domain", "1",
            "Referer", BLOGGER_BASE,
        )

        val rpcString = client.newCall(POST(rpcUrl, body = rpcBody, headers = rpcHeaders))
            .awaitSuccess().bodyString()

        if (!rpcString.contains("https://")) return emptyList()

        return rpcString
            .substringAfter("[[\\\"", "")
            .substringBefore("]]]")
            .let { "\\\"$it]" }
            .split("],[")
            .mapNotNull {
                val videoUrl = it.substringAfter("\\\"", "")
                    .substringBefore("\\\"")
                    .takeIf(String::isNotBlank)
                    ?.let(::decodeDoubleEscapedJson)
                    ?: return@mapNotNull null

                val format = it.substringAfter("[").substringBefore("]")
                val quality = qualityFromFormat(format)
                Video(videoUrl, "Blogger - $quality $suffix".trimEnd(), videoUrl, headers)
            }
    }

    private fun decodeDoubleEscapedJson(value: String): String? = runCatching {
        // The RPC response wraps the URL in JSON double-escaped strings,
        // so it needs two decoding passes: escaped -> JSON string -> actual value
        val first = "\"$value\"".parseAs<String>()
        "\"$first\"".parseAs<String>()
    }.getOrNull()

    private fun qualityFromFormat(format: String): String = when (format) {
        "7" -> "240p"
        "18" -> "360p"
        "22" -> "720p"
        "37" -> "1080p"
        else -> "Unknown"
    }

    companion object {
        private const val BLOGGER_BASE = "https://www.blogger.com/"
    }
}
