package aniyomi.lib.streamwishextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.lib.synchrony.Deobfuscator
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val dmcaServersRegex = """dmca\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val mainServersRegex = """main\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val rulesServersRegex = """rules\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    suspend fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "$prefix - $it" }

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<Video> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        val id = getEmbedId(url)

        // If id is already an absolute URL, avoid iterating over all DOMAINS and reuse it directly.
        val isAbsoluteId = id.startsWith("https://") || id.startsWith("http://")
        val domainsToTry = if (isAbsoluteId) listOf("") else DOMAINS

        for (domain in domainsToTry) {
            val fullUrl = UrlUtils.fixUrl(id, "https://$domain") ?: continue
            try {
                val response = client.newCall(GET(fullUrl, headers)).await()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val body = response.bodyString()
                if (body.isBlank()) continue
                var doc = Jsoup.parse(body)

                val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
                if (scriptElement != null) {
                    val scriptUrl = scriptElement.absUrl("src")
                    val scriptContent = client.newCall(GET(scriptUrl, headers)).awaitSuccess().bodyString()

                    val deobfuscatedScript = runCatching { Deobfuscator.deobfuscateScript(scriptContent) }.getOrNull()
                        ?: continue

                    val dmcaServers = extractServerList(dmcaServersRegex, deobfuscatedScript)

                    val mainServers = extractServerList(mainServersRegex, deobfuscatedScript)

                    val rulesServers = extractServerList(rulesServersRegex, deobfuscatedScript)

                    val destination = if (embedUrl.host in rulesServers) {
                        mainServers.randomOrNull()
                    } else {
                        dmcaServers.randomOrNull()
                    } ?: continue

                    val redirectedUrl = embedUrl.newBuilder()
                        .host(destination)
                        .build()
                        .toString()

                    doc = client.newCall(GET(getEmbedUrl(redirectedUrl), headers)).awaitSuccess().useAsJsoup()
                }

                val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()
                    ?.let { script ->
                        if (script.contains("eval(function(p,a,c")) {
                            JsUnpacker.unpackAndCombine(script)
                        } else {
                            script
                        }
                    }

                val masterUrl = scriptBody?.let {
                    M3U8_REGEX.find(it)?.value
                }

                if (masterUrl != null) {
                    val subtitleList = extractSubtitles(scriptBody)

                    return playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = masterUrl.toHttpUrlOrNull()
                            ?.let { "${it.scheme}://${it.host}/" }
                            ?: "https://${url.toHttpUrl().host}/",
                        videoNameGen = videoNameGen,
                        subtitleList = playlistUtils.fixSubtitles(subtitleList),
                    )
                }
            } catch (_: Exception) {
                if (isAbsoluteId) return emptyList()
            }
        }

        return emptyList()
    }

    private fun extractServerList(regex: Regex, script: String): List<String> = regex.find(script)?.groupValues?.get(1)
        ?.split(",")
        ?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    private fun getEmbedUrl(url: String): String = if (url.contains("/f/")) {
        val videoId = url.substringAfter("/f/")
        "https://streamwish.com/$videoId"
    } else {
        url
    }

    private fun getEmbedId(url: String): String {
        val regex = Regex(""".*/[efd]/([a-zA-Z0-9]+)""")
        val match = regex.find(url)

        val id = match?.groupValues?.get(1)
            ?: return url

        // Prevent redirect
        return id
    }

    private fun extractSubtitles(script: String): List<Track> = try {
        val subtitleStr = script
            .substringAfter("tracks")
            .substringAfter("[")
            .substringBefore("]")
        val fixedSubtitleStr = FIX_TRACKS_REGEX.replace(subtitleStr) { match ->
            "\"${match.value}\""
        }

        json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .map { Track(it.file, it.label ?: "") }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    companion object {
        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
        private val FIX_TRACKS_REGEX by lazy { Regex("""(?<!")(file|kind|label)(?!")""") }
        private val DOMAINS = listOf(
            "streamwish.com",
            "niramirus.com",
            "medixiru.com",
        )
    }
}
