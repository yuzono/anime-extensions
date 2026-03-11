package eu.kanade.tachiyomi.animeextension.tr.anizm.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class AincradExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val hash = url.substringAfterLast("video/").substringBefore("/")
        val body = FormBody.Builder()
            .add("hash", hash)
            .add("r", "https://anizm.net/")
            .build()

        val headers = headers.newBuilder()
            .set("Origin", DOMAIN)
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val req = POST("$DOMAIN/player/index.php?data=$hash&do=getVideo", headers, body)
        val data = client.newCall(req).awaitSuccess().parseAs<ResponseDto>()
        return playlistUtils.extractFromHls(
            data.securedLink!!,
            referer = url,
            videoNameGen = { "Aincrad - $it" },
        )
    }

    @Serializable
    data class ResponseDto(val securedLink: String?)

    companion object {
        private const val DOMAIN = "https://anizmplayer.com"
    }
}
