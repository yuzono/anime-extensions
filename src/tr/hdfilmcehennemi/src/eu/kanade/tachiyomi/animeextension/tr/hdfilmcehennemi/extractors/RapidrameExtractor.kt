package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.Deobfuscator.base64Rot13ReverseUnmix
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.Deobfuscator.partsRegex
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Serializable
class TrackDto(val file: String, val label: String, val language: String, val kind: String)

class RapidrameExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val tracksRegex by lazy { Regex("""tracks:\s*(\[[^]]*?]),""") }

    suspend fun videosFromUrl(url: String, label: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val script = doc.selectFirst("script:containsData(eval)")?.data()
            ?: return emptyList()

        val unpackedScript = autoUnpacker(script) ?: return emptyList()
        val parts = partsRegex.find(unpackedScript)?.groupValues?.get(1)?.split(",")
            ?: return emptyList()
        val playlistUrl = base64Rot13ReverseUnmix(parts.toTypedArray())

        val hostUrl = "https://" + url.toHttpUrl().host
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", hostUrl)
            .build()

        val subtitles = tracksRegex.find(script)?.groupValues?.get(1)
            ?.parseAs<List<TrackDto>>()
            ?.filter { track -> track.kind == "captions" }
            ?.mapNotNull { track ->
                UrlUtils.fixUrl(track.file, hostUrl)?.let { trackUrl ->
                    Track(trackUrl, "[${track.language}] ${track.label}")
                }
            } ?: emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl,
            videoHeaders = videoHeaders,
            masterHeaders = videoHeaders,
            subtitleList = subtitles,
            videoNameGen = { "$label - $it" },
        )
    }
}
