package aniyomi.lib.okruextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = Headers.EMPTY) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("ultra", "2160p"),
            Pair("quad", "1440p"),
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p"),
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    suspend fun videosFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?: return emptyList<Video>()

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            else -> videosFromJson(videoString, prefix, fixQualities)
        }
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)
        ?.let { "$prefix $this" }
        ?: this

    private fun String.extractLink(attr: String) = Regex("""$attr(\\*")\s*:\s*\1(.*?)\1""").find(this)?.groupValues?.get(2)
        ?.replace(Regex("""\\+u0026"""), "&")
        ?: ""

    private fun videosFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val arrayData = Regex("""videos\\*"\s*:\s*\\*\[(.*?)(?:\]|$)""").find(videoString)?.groupValues?.get(1)
            ?: return emptyList()

        return arrayData.split(Regex("""\{\s*\\*"name\\*"\s*:\s*\\*"""")).reversed().mapNotNull { data ->
            val videoUrl = data.extractLink("url")
            val quality = data.substringBefore("\"").trimEnd('\\').let {
                if (fixQualities) fixQuality(it) else it
            }
            val videoQuality = "Okru:$quality".addPrefix(prefix)

            if (videoUrl.startsWith("https://")) {
                Video(videoUrl, videoQuality, videoUrl)
            } else {
                null
            }
        }
    }
}
