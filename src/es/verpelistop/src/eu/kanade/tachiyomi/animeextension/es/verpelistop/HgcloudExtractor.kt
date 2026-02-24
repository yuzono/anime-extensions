package eu.kanade.tachiyomi.animeextension.es.verpelistop

import aniyomi.lib.playlistutils.PlaylistUtils
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * URL:
 * - https://hgcloud.to/e/n092gp8c6vr0
 * - https://audinifer.com/e/n092gp8c6vr0
 */
class HgcloudExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Regex by lazy { Regex(""""([^"]+\.m3u8[^"]*)"""") }

    suspend fun videosFromUrl(url: String, prefix: String = "Hgcloud"): List<Video> {
        // hgcloud is redirecting to audinifer
        val redirectUrl = url.replace("hgcloud.to", "audinifer.com")
        val document = client.newCall(GET(redirectUrl)).awaitSuccess().use { it.asJsoup() }

        val script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)?.ifEmpty { null }
            ?: return emptyList()

        val masterPlaylists = m3u8Regex.findAll(script).mapNotNull {
            it.groupValues.getOrNull(1)
        }

        return masterPlaylists.flatMap { m3u8 ->
            playlistUtils.extractFromHls(
                m3u8,
                referer = redirectUrl,
                videoNameGen = { "$prefix: $it" },
            )
        }.toList()
    }
}
