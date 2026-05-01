package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import aniyomi.lib.autoUnpacker
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.Deobfuscator.base64Rot13ReverseUnmix
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.Deobfuscator.partsRegex
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class CloseloadExtractor(private val client: OkHttpClient, private val headers: Headers) {
    suspend fun videosFromUrl(url: String, name: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val script = doc.selectFirst("script:containsData(eval):containsData(PlayerInit)")?.data()
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

        runCatching { tryAjaxPost(unpackedScript, hostUrl) }

        val subtitles = doc.select("track[src]").map {
            Track(it.absUrl("src"), it.attr("label").ifEmpty { it.attr("srclang") })
        }

        return listOf(
            Video(playlistUrl, name, playlistUrl, videoHeaders, subtitleTracks = subtitles),
        )
    }

    private suspend fun tryAjaxPost(script: String, hostUrl: String) {
        val hash = script.getProperty("hash:")
        val url = script.getProperty("url:").let {
            UrlUtils.fixUrl(it, hostUrl)
        } ?: return

        val body = FormBody.Builder().add("hash", hash).build()

        client.newCall(POST(url, headers, body)).await().close()
    }

    private fun String.getProperty(before: String) = substringAfter("$before\"").substringBefore('"')
}
