package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import aniyomi.lib.hlsdash.HlsDashServer
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Builds one [Video] per quality of the Octopus VP9/CMAF stream. Each variant is handed to the
 * player as a DASH manifest by [HlsDashServer] so that seeking works.
 */
class OctopusExtractor(private val client: OkHttpClient) {

    suspend fun extractOctopusStream(sourceUrl: String, episodeUrl: String): List<Video> {
        val masterUrl = sourceUrl.toHttpUrl().let { url ->
            if (url.pathSegments.lastOrNull() == "playlist.m3u8") {
                url.newBuilder()
                    .setPathSegment(url.pathSize - 1, "playlist_vp9.m3u8")
                    .build()
            } else {
                url
            }
        }
        val videoHeaders = buildCdnHeaders(episodeUrl)
        val subtitles = listOfNotNull(masterUrl.resolve("s/en.vtt")?.let { Track(it.toString(), "English") })

        val lines = client.get(masterUrl, videoHeaders).bodyString().lines()

        val audioGroups = lines
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { parseAttributes(it) }
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrEmpty() }
            .groupBy { it["GROUP-ID"] }

        val videos = mutableListOf<Video>()
        var streamInfo: Map<String, String>? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF:") -> streamInfo = parseAttributes(line)
                line.startsWith("#") -> Unit
                else -> {
                    val info = streamInfo ?: continue
                    streamInfo = null
                    val videoUrl = masterUrl.resolve(line)?.toString() ?: continue
                    val audioUrl = selectAudio(masterUrl, audioGroups[info["AUDIO"]] ?: audioGroups.values.flatten())
                    videos += Video(
                        videoTitle = info["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()?.let { "${it}p" } ?: "Auto",
                        videoUrl = HlsDashServer.register(client, videoHeaders, videoUrl, audioUrl),
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
        }
        return videos
    }

    private fun selectAudio(masterUrl: HttpUrl, group: List<Map<String, String>>?): String? {
        val rendition = group?.firstOrNull { it["DEFAULT"] == "YES" }
            ?: group?.firstOrNull { it["AUTOSELECT"] == "YES" }
            ?: group?.firstOrNull()
        return rendition?.get("URI")?.let { masterUrl.resolve(it)?.toString() }
    }

    private fun parseAttributes(line: String): Map<String, String> = ATTRIBUTE_REGEX
        .findAll(line.substringAfter(':'))
        .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    private fun buildCdnHeaders(episodeUrl: String): Headers {
        val origin = episodeUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        return Headers.Builder()
            .add("Referer", episodeUrl)
            .add("Origin", origin)
            .add("Accept-Encoding", "identity")
            .add("Cache-Control", "no-transform")
            .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
            .build()
    }

    companion object {
        private val ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }
}
