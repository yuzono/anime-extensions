package eu.kanade.tachiyomi.animeextension.es.verpelistop

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.formatBytes
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class HexloadExtractor(private val client: OkHttpClient, private val headers: Headers) {

    // Example URL: https://hexload.com/embed-ii21t0gfa9c3/Avatar_Fuego_y_ceniza_LAT.mp4
    private val idRegex by lazy { Regex("""embed-(\w+?)/""") }

    suspend fun videosFromUrl(url: String, prefix: String = "HexLoad"): List<Video> {
        val id = idRegex.find(url)?.groupValues?.getOrNull(1) ?: return emptyList()

        val formBody = FormBody.Builder()
            .add("op", "download3")
            .add("id", id)
            .add("ajax", "1")
            .add("method_free", "1")
            .add("dataType", "json")
            .build()

        val video = client.newCall(
            POST("https://hexload.com/download", headers, body = formBody),
        ).awaitSuccess().parseAs<Data>().result

        val quality = video.size.formatBytes().takeIf { it.isNotBlank() }
            ?.let { "$prefix: $it" }
            ?: prefix

        return listOf(Video(video.url, quality, video.url, headers))
    }

    @Serializable
    private data class Data(
        val result: ResultDto,
    )

    @Serializable
    private data class ResultDto(
        val url: String,
        val md5: String?, // "VPHYYxNMLxKIxBZWE9hl+A",
        val thumb_url: String?, // "https://46ev7agtixoi.droply.top/i/01264/pbv78rlcsagr_t.jpg",
        val content_type: String?, // "video/mp4",
        val size: Long, // 3065200640"
        val image_url: String?, // "https://46ev7agtixoi.droply.top/i/01264/pbv78rlcsagr.jpg",
        val folder: String?, // null,
        val file_name: String?, // "Avatar Fuego y ceniza LAT.mp4"
    )
}
