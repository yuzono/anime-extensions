package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MailRuExtractor(private val client: OkHttpClient, private val headers: Headers) {

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val metaUrl = document.selectFirst("script:containsData(metadataUrl)")?.let {
            it.data().substringAfter("metadataUrl\":\"").substringBefore("\"").replace("^//".toRegex(), "https://")
        } ?: return emptyList()

        val metaHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Host", url.toHttpUrl().host)
            .add("Referer", url)
            .build()

        client.newCall(GET(metaUrl, headers = metaHeaders)).awaitSuccess().use { metaResponse ->
            val metaJson = metaResponse.parseAs<MetaResponse>()
            val videoKey = metaResponse.headers.firstOrNull {
                it.first.equals("set-cookie", true) && it.second.startsWith("video_key", true)
            }?.second?.substringBefore(";") ?: ""

            return metaJson.videos.map {
                val videoUrl = it.url
                    .replace("^//".toRegex(), "https://")
                    .replace(".mp4", ".mp4/stream.mpd")

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Cookie", videoKey)
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                Video(videoUrl, "${prefix}Mail.ru ${it.key}", videoUrl, headers = videoHeaders)
            }
        }
    }

    @Serializable
    data class MetaResponse(
        val videos: List<VideoObject>,
    ) {
        @Serializable
        data class VideoObject(
            val url: String,
            val key: String,
        )
    }
}
