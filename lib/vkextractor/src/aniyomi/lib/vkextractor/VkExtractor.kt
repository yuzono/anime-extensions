package aniyomi.lib.vkextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.toHex
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.security.MessageDigest

class VkExtractor(private val client: OkHttpClient, headers: Headers) {
    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/vk.py
    private val documentHeaders = headers.newBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    private val videoHeaders = headers.newBuilder()
        .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
        .add("Origin", VK_URL)
        .add("Referer", "$VK_URL/")
        .build()

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoId = extractVideoId(url) ?: return emptyList()

        val apiVideos = getVideosViaApi(videoId)
        if (apiVideos.isNotEmpty()) {
            return apiVideos.map {
                Video(it.url, "${prefix}${it.quality}", it.url, videoHeaders)
            }
        }

        val embedUrl = if (url.contains("video_ext.php")) {
            url
        } else {
            val parts = videoId.split("_")
            if (parts.size == 2) "$VK_URL/video_ext.php?oid=${parts[0]}&id=${parts[1]}&autoplay=0" else url
        }

        val htmlContent = handleWafChallenge(embedUrl)
        return extractVideosFromHtml(htmlContent, prefix)
    }

    private suspend fun handleWafChallenge(url: String): String {
        val response = client.newCall(GET(url, documentHeaders)).await()
        val responseUrl = response.request.url.toString()

        if (responseUrl.contains("429.html") || response.code == 429) {
            response.close()
            val cookies = client.cookieJar.loadForRequest(response.request.url)
            val hash429Cookie = cookies.firstOrNull { it.name == "hash429" }?.value

            if (hash429Cookie != null) {
                val hash429 = md5(hash429Cookie)
                val challengeUrl = response.request.url.newBuilder()
                    .addQueryParameter("key", hash429)
                    .build()
                    .toString()

                client.newCall(GET(challengeUrl, documentHeaders)).awaitSuccess().close()

                return client.newCall(GET(url, documentHeaders)).awaitSuccess().bodyString()
            } else {
                throw Exception("hash429 cookie not found, cannot bypass WAF")
            }
        } else {
            return response.bodyString()
        }
    }

    private suspend fun getVideosViaApi(videoId: String): List<RawVideo> {
        val body = FormBody.Builder()
            .add("act", "show")
            .add("al", "1")
            .add("video", videoId)
            .build()

        val apiHeaders = documentHeaders.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()

        return try {
            val cleanJson = client.newCall(POST(VK_API_URL, apiHeaders, body)).awaitSuccess()
                .bodyString()
                .substringAfter("<!--")

            parseVideoUrls(cleanJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractVideosFromHtml(html: String, prefix: String): List<Video> = parseVideoUrls(html).map {
        Video(it.url, "${prefix}${it.quality}", it.url, videoHeaders)
    }

    private fun parseVideoUrls(text: String): List<RawVideo> {
        val videos = mutableListOf<RawVideo>()
        val patterns = listOf(
            """"url(\d+)":"(.*?)"""".toRegex(),
            """"mp4_(\d+)":"(.*?)"""".toRegex(),
        )

        patterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val quality = match.groupValues[1] + "p"
                val url = match.groupValues[2].replace("\\/", "/")
                if (url.startsWith("http")) {
                    videos.add(RawVideo(url, quality))
                }
            }
        }

//        val hlsRegex = """"hls":"(.*?)"""".toRegex()
//        hlsRegex.find(text)?.let {
//            val url = it.groupValues[1].replace("\\/", "/")
//            videos.add(RawVideo(url, "HLS"))
//        }

        return videos
            .distinctBy { it.quality }
            .sortedByDescending { it.quality.removeSuffix("p").toIntOrNull() ?: 0 }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "oid=(-?\\d+).*?id=(\\d+)".toRegex(),
            "video(-?\\d+_\\d+)".toRegex(),
            "clip(-?\\d+_\\d+)".toRegex(),
        )

        for (regex in patterns) {
            val match = regex.find(url) ?: continue
            return if (match.groupValues.size == 3) {
                "${match.groupValues[1]}_${match.groupValues[2]}"
            } else {
                match.groupValues[1]
            }
        }
        return null
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).toHex()
    }

    data class RawVideo(val url: String, val quality: String)

    companion object {
        private const val VK_URL = "https://vk.com"
        private const val VK_API_URL = "https://vk.com/al_video.php?act=show"
    }
}
