package eu.kanade.tachiyomi.animeextension.pt.animeito.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeItoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val encodedScript = playerDoc.selectFirst("script:containsData(AnimeiTo.Run)")
            ?.data()

        val script = if (encodedScript != null) {
            val decodedData = encodedScript.substringAfter("(").substringBefore(")")
                .replace(Regex("\"\\s*\\+\\s*\""), "") // Remove concatenation
                .replace(Regex("[^A-Za-z0-9+/=]"), "") // Remove non-base64 characters
                .let { String(Base64.decode(it, Base64.DEFAULT)) }
            Unpacker.unpack(decodedData).ifEmpty { return emptyList() }
        } else {
            playerDoc.selectFirst("script:containsData(const player)")?.data()
                ?: return emptyList()
        }

        return if ("googlevideo" in script) {
            script.substringAfter("sources:").substringBefore("]")
                .split("{")
                .drop(1)
                .map {
                    val videoUrl = it.substringAfter("file\":\"").substringBefore('"')
                    val quality = it.substringAfter("label\":\"").substringBefore('"')
                    Video(videoUrl, "Animei.to - $quality", videoUrl, headers)
                }
        } else {
            val masterPlaylistUrl = script.substringAfter("sources:")
                .substringAfter("file\":\"")
                .substringBefore('"')

            playlistUtils.extractFromHls(masterPlaylistUrl, videoNameGen = { "Animei.to - $it" })
        }
    }
}
