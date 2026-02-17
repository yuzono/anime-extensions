package eu.kanade.tachiyomi.animeextension.es.jkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class JkanimeExtractor(
    private val client: OkHttpClient,
) {

    fun getNozomiFromUrl(url: String, prefix: String = ""): List<Video> {
        val dataKeyHeaders = Headers.Builder().add("Referer", url).build()
        val doc = client.newCall(GET(url, dataKeyHeaders)).execute().asJsoup()
        val dataKey = doc.select("form input[value]").attr("value")

        val gsplayBody = "data=$dataKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        val location = client.newCall(POST("https://jkanime.net/gsplay/redirect_post.php", dataKeyHeaders, gsplayBody))
            .execute().request.url.toString()
        val postKey = location.substringAfter("player.html#")

        val nozomiBody = "v=$postKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val nozomiResponse = client.newCall(POST("https://jkanime.net/gsplay/api.php", body = nozomiBody)).execute()
        val nozomiUrl = nozomiResponse.body.string().parseAs<NozomiResponse>().file ?: return emptyList()

        return listOf(Video(nozomiUrl, "${prefix}Nozomi", nozomiUrl))
    }

    fun parseVideoFromDpPlayer(response: Response, quality: String = ""): List<Video> {
        val document = response.asJsoup()
        val streamUrl = document
            .selectFirst("""script:containsData(new DPlayer\({)""")
            ?.data()?.substringAfter("url: '")
            ?.substringBefore("'") ?: return emptyList()

        return listOf(Video(streamUrl, quality, streamUrl))
    }

    fun getDesuFromUrl(url: String, prefix: String = ""): List<Video> {
        val response = client.newCall(GET(url)).execute()
        return parseVideoFromDpPlayer(response, "${prefix}Desu")
    }

    fun getDesukaFromUrl(url: String, prefix: String = ""): List<Video> {
        val response = client.newCall(GET(url)).execute()
        val contentType = response.header("Content-Type") ?: ""

        if (contentType.startsWith("video/")) {
            val realUrl = response.request.url.toString()
            return listOf(Video(realUrl, "${prefix}Desuka", realUrl))
        }
        return parseVideoFromDpPlayer(response, "${prefix}Desuka")
    }

    fun getMagiFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoUrl = document.selectFirst("""source[src*=".m3u8"]""")?.attr("src") ?: return emptyList()
        return listOf(Video(videoUrl, "${prefix}Magi", videoUrl))
    }

    fun getMediafireFromUrl(url: String, prefix: String = ""): List<Video> {
        val response = client.newCall(GET(url)).execute()
        val downloadUrl = response.asJsoup().selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return listOf(Video(downloadUrl, "${prefix}MediaFire", downloadUrl))
        }
        return emptyList()
    }

    @Serializable
    data class NozomiResponse(val file: String? = null)
}
