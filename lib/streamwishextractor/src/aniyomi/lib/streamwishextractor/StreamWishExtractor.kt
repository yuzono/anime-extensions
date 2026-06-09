package aniyomi.lib.streamwishextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun canHandleUrl(url: String): Boolean = STREAM_WISH_REGEX.containsMatchIn(url)

    suspend fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "${prefix.replaceFirstChar(Char::titlecase)}: $it" }

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish: $quality" }): List<Video> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        var doc = client.newCall(GET(embedUrl, headers)).awaitSuccess().useAsJsoup()
        val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
        if (scriptElement != null) {
            val destination = if (embedUrl.host in RULES_SERVERS) {
                MAIN_SERVERS.randomOrNull()
            } else {
                DMCA_SERVERS.randomOrNull()
            } ?: return emptyList()

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
            } ?: return emptyList()

        val masterUrl = M3U8_REGEX.find(scriptBody)?.value ?: return emptyList()

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

    private fun getEmbedUrl(url: String): String = if (url.contains("/f/")) {
        val videoId = url.substringAfter("/f/")
        "https://streamwish.com/$videoId"
    } else {
        url
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
        private val DMCA_SERVERS = arrayOf("hgplaycdn.com", "hglamioz.com", "niramirus.com", "playnixes.com", "medixiru.com")
        private val MAIN_SERVERS = arrayOf("hanerix.com", "audinifer.com", "vibuxer.com", "masukestin.com")
        private val RULES_SERVERS = arrayOf("dhcplay.com", "hglink.to", "hgcloud.to")
        private val STREAM_WISH_REGEX by lazy { Regex("""(?://|\.)((?:(?:stream|flas|obey|sfast|str|embed|[mads]|cdn|asn|player|hls)?wish(?:embed|fast|only|srv)?|ajmidyad|atabkhha|atabknha|atabknhk|atabknhs|abkrzkr|abkrzkz|vidmoviesb|kharabnahs|hayaatieadhab|cilootv|tuktukcinema|doodporn|ankrzkz|volvovideo|strmwis|ankrznm|yadmalik|khadhnayad|hailindihg|eghjrutf|eghzrutw|playembed|egsyxurh|egtpgrvh|uqloads|javsw|cinemathek|trgsfjll|fsdcmo|guxhag|anime4low|mohahhda|ma2d|dancima|swhoi|gsfqzmqu|jodwish|swdyu|katomen|iplayerhls|hlsflast|4yftwvrdz7|ghbrisk|eb8gfmjn71|cybervynx|edbrdl7pab|stbhg|dhcplay|gradehgplus|tryzendm|dumbalag|hg(?:bazooka|link|cloud)|haxloppd|daviod|kravaxxa|aiavh|uasopt)\.(?:com|to|sbs|pro|xyz|store|top|site|online|me|shop|fun|click))/(?:e/|f/|d/)?([0-9a-zA-Z$:/.]+)""") }
        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
        private val FIX_TRACKS_REGEX by lazy { Regex("""(?<!")(file|kind|label)(?!")""") }
    }
}
