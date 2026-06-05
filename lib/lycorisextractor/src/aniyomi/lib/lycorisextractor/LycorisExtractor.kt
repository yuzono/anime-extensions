package aniyomi.lib.lycorisextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class LycorisExtractor(private val client: OkHttpClient) {

    companion object {
        private const val GETLNKURL = "https://www.lycoris.cafe/api/watch/getVideoLink"

        private const val DECRYPTURL = "https://www.lycoris.cafe/api/watch/decryptVideoLink"

        private const val DECRYPT_API_KEY = "303a897d-sd12-41a8-84d1-5e4f5e208878"
    }

    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/lycoris.py
    suspend fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        val document = client.newCall(
            GET(url, headers = headers),
        ).awaitSuccess().useAsJsoup()

        val script =
            document.selectFirst("script[type='application/json']")?.data() ?: return emptyList()

        val scriptData = script.parseAs<ScriptBody>()

        val data = scriptData.body.parseAs<ScriptEpisode>()

        val linkList = fetchAndDecodeVideo(client, headers, data.episodeInfo.id.toString())

        linkList.FHD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 1080p", it))
        }
        linkList.HD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 720p", it))
        }
        linkList.SD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 480p", it))
        }
        return videos
    }

    private suspend fun fetchAndDecodeVideo(client: OkHttpClient, headers: Headers, episodeId: String): VideoLinksApi {
        val decryptHeaders = headers.newBuilder()
            .add("x-api-key", DECRYPT_API_KEY)
            .add("Content-Type", "application/json")
            .build()

        val url: HttpUrl = GETLNKURL.toHttpUrl().newBuilder()
            .addQueryParameter("id", episodeId)
            .build()

        val encryptedText = client.newCall(GET(url))
            .awaitSuccess().bodyString()

        val textByte = encryptedText.toByteArray(Charsets.ISO_8859_1)

        val base64Data = Base64.encodeToString(textByte, Base64.DEFAULT)

        val jsonObject = JSONObject()
        jsonObject.put("encoded", base64Data)

        return client.newCall(POST(DECRYPTURL, headers = decryptHeaders, body = jsonObject.toJsonRequestBody()))
            .awaitSuccess()
            .parseAs<VideoLinksApi>()
    }

    private suspend fun checkLinks(client: OkHttpClient, link: String): Boolean {
        if (!link.contains("https://")) return false

        client.newCall(GET(link)).await().use { response ->
            return response.code == 200
        }
    }

    @Serializable
    data class ScriptBody(
        val body: String,
    )

    @Serializable
    data class ScriptEpisode(
        val episodeInfo: EpisodeInfo,
    )

    @Serializable
    data class EpisodeInfo(
        val id: Int? = null,
        val FHD: String? = null,
        val HD: String? = null,
        val SD: String? = null,
    )

    @Serializable
    data class VideoLinksApi(
        val HD: String? = null,
        val SD: String? = null,
        val FHD: String? = null,
        val Source: String? = null,
        val SourceMKV: String? = null,
    )
}
