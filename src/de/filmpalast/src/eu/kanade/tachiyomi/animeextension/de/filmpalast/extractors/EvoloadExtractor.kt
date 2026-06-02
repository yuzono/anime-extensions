package eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.toJsonRequestBody
import okhttp3.OkHttpClient

class EvoloadExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = url.substringAfter("https://evoload.io/e/")
        val csrvToken =
            client.newCall(GET("https://csrv.evosrv.com/captcha?m412548=")).execute().body.string() // whatever that is
        val captchaPass = client.newCall(GET("https://cd2.evosrv.com/html/jsx/e.jsx")).execute().toString()
            .substringAfter("var captcha_pass = '").substringBefore("'")
        val file = client.newCall(
            POST(
                "https://evoload.io/SecurePlayer",
                body = "{\"code\":\"$id\",\"token\":\"ok\",\"csrv_token\":\"$csrvToken\",\"pass\":\"$captchaPass\",\"reff\":\"https://filmpalast.to/\"}".toJsonRequestBody(),
            ),
        ).execute().body.string()

        if (file.contains("backup")) {
            val videoUrl = file.substringAfter("\"encoded_src\":\"").substringBefore("\",")
            when {
                !file.substringAfter("\"xstatus\":\"").substringBefore("\",").contains("del") -> {
                    val video = Video(url, quality, videoUrl)
                    videoList.add(video)
                }
            }
        } else {
            val videoUrl = file.substringAfter("\"src\":\"").substringBefore("\",")
            when {
                !file.substringAfter("\"xstatus\":\"").substringBefore("\",").contains("del") -> {
                    val video = Video(url, quality, videoUrl)
                    videoList.add(video)
                }
            }
        }
        return videoList
    }
}
