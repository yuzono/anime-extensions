package eu.kanade.tachiyomi.animeextension.en.animekhor.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient

class TurbovidExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val pageHeaders = headers.newBuilder()
            .set("Referer", "https://animekhor.org/")
            .build()
        val html = client.newCall(GET(url, pageHeaders)).awaitSuccess().bodyString()

        var urlPlay = URL_PLAY_REGEX.find(html)?.groupValues?.get(1)
        var urlSub = URL_SUB_REGEX.find(html)?.groupValues?.get(1)

        if (urlPlay == null) {
            val hunterMatch = HUNTER_REGEX.find(html)
            if (hunterMatch != null) {
                val h = hunterMatch.groupValues[1]
                val n = hunterMatch.groupValues[3]
                val t = hunterMatch.groupValues[4].toIntOrNull() ?: 0
                val e = hunterMatch.groupValues[5].toIntOrNull() ?: 0
                val unpacked = unpackHunter(h, n, t, e)

                urlPlay = URL_PLAY_REGEX.find(unpacked)?.groupValues?.get(1)
                urlSub = urlSub ?: URL_SUB_REGEX.find(unpacked)?.groupValues?.get(1)
            }
        }

        if (urlPlay.isNullOrBlank()) return emptyList()

        val subtitles = if (!urlSub.isNullOrBlank()) {
            listOf(Track(urlSub, "Subtitles"))
        } else {
            emptyList()
        }

        return if (urlPlay.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                urlPlay,
                referer = url,
                subtitleList = subtitles,
                videoNameGen = { "$prefix$it" },
            )
        } else {
            val videoHeaders = headers.newBuilder()
                .set("Referer", url)
                .build()
            listOf(
                Video(
                    videoUrl = urlPlay,
                    videoTitle = "${prefix}Turbovid",
                    headers = videoHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    private fun unpackHunter(h: String, n: String, t: Int, e: Int): String {
        val delimiter = n.getOrNull(e) ?: return ""
        val chunks = h.split(delimiter)
        if (chunks.size <= 1) return ""

        val bytes = ByteArray(chunks.size - 1)
        for (idx in 0 until chunks.size - 1) {
            var s = chunks[idx]
            for (j in n.indices) {
                s = s.replace(n[j].toString(), j.toString())
            }
            val charCode = s.toIntOrNull(e)?.minus(t) ?: return ""
            bytes[idx] = charCode.toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private val URL_PLAY_REGEX by lazy { Regex("""urlPlay\s*=\s*['"]([^'"]+)""") }
        private val URL_SUB_REGEX by lazy { Regex("""urlSub\s*=\s*['"]([^'"]+)""") }
        private val HUNTER_REGEX by lazy {
            Regex("""}\s*\(\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")
        }
    }
}
