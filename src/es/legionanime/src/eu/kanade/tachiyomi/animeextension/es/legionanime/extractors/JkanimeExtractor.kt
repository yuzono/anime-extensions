package eu.kanade.tachiyomi.animeextension.es.legionanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class JkanimeExtractor(
    private val client: OkHttpClient,
) {

    suspend fun getNozomiFromUrl(url: String, prefix: String = ""): Video? {
        val dataKeyHeaders = Headers.Builder().add("Referer", url).build()
        val doc = client.newCall(GET(url, dataKeyHeaders)).awaitSuccess().useAsJsoup()
        val dataKey = doc.select("form input[value]").attr("value")

        val gsplayBody = "data=$dataKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        val location = client.newCall(POST("https://jkanime.net/gsplay/redirect_post.php", dataKeyHeaders, gsplayBody))
            .awaitSuccess().use { it.request.url.toString() }
        val postKey = location.substringAfter("player.html#")

        val nozomiBody = "v=$postKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val nozomiResponse = client.newCall(POST("https://jkanime.net/gsplay/api.php", body = nozomiBody))
            .awaitSuccess().bodyString()
        val nozomiUrl = JSONObject(nozomiResponse).getString("file")
        if (nozomiUrl.isNotBlank()) {
            return Video(nozomiUrl, "${prefix}Nozomi", nozomiUrl)
        }
        return null
    }

    suspend fun getDesuFromUrl(url: String, prefix: String = ""): Video? {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val script = document.selectFirst("script:containsData(var parts = {)")!!.data()
        val streamUrl = script.substringAfter("url: '").substringBefore("'")
        if (streamUrl.isNotBlank()) {
            return Video(streamUrl, "${prefix}Desu", streamUrl)
        }
        return null
    }

    suspend fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url.replace(".com", ".tv"))).awaitSuccess().useAsJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)")!!.data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")
        return try {
            if (client.newCall(GET(videoURl)).awaitSuccess().use { it.code } == 200) videoURl else ""
        } catch (_: Exception) {
            ""
        }
    }
}
