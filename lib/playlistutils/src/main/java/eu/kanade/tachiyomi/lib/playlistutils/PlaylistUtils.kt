package eu.kanade.tachiyomi.lib.playlistutils

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.commonEmptyHeaders
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

class PlaylistUtils(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {

    // ================================ M3U8 ================================

    /**
     * Extracts videos from a .m3u8 file.
     *
     * @param playlistUrl the URL of the HLS playlist
     * @param referer the referer header value to be sent in the HTTP request (default: "")
     * @param masterHeaders header for the master playlist
     * @param videoHeaders headers for each video
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video (default: identity function)
     * @param subtitleList a list of subtitle tracks associated with the HLS playlist, will append to subtitles present in the m3u8 playlist (default: empty list)
     * @param audioList a list of audio tracks associated with the HLS playlist, will append to audio tracks present in the m3u8 playlist (default: empty list)
     * @return a list of Video objects
     */
    fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeaders: Headers,
        videoHeaders: Headers,
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<Video> {
        return extractFromHls(
            playlistUrl,
            referer,
            { _, _ -> masterHeaders },
            { _, _, _ -> videoHeaders },
            videoNameGen,
            subtitleList,
            audioList,
            toStandardQuality,
        )
    }

    /**
     * Extracts videos from a .m3u8 file.
     *
     * @param playlistUrl the URL of the HLS playlist
     * @param referer the referer header value to be sent in the HTTP request (default: "")
     * @param masterHeadersGen a function that generates headers for the master playlist request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the master playlist request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video (default: identity function)
     * @param subtitleList a list of subtitle tracks associated with the HLS playlist, will append to subtitles present in the m3u8 playlist (default: empty list)
     * @param audioList a list of audio tracks associated with the HLS playlist, will append to audio tracks present in the m3u8 playlist (default: empty list)
     * @return a list of Video objects
     */
    fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<Video> {
        val masterHeaders = masterHeadersGen(headers, referer)

        val masterPlaylist = client.newCall(GET(playlistUrl, masterHeaders)).execute()
            .body.string()

        // Check if there isn't multiple streams available
        if (PLAYLIST_SEPARATOR !in masterPlaylist) {
            return listOf(
                Video(
                    playlistUrl,
                    videoNameGen("Video"),
                    playlistUrl,
                    headers = masterHeaders,
                    subtitleTracks = subtitleList,
                    audioTracks = audioList,
                ),
            )
        }

        val playlistHttpUrl = playlistUrl.toHttpUrl()

        val masterUrlBasePath = playlistHttpUrl.newBuilder().apply {
            removePathSegment(playlistHttpUrl.pathSize - 1)
            addPathSegment("")
            query(null)
            fragment(null)
        }.build().toString()

        // Get subtitles
        val subtitleTracks = subtitleList + SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        // Get audio tracks
        val audioTracks = audioList + AUDIO_REGEX.findAll(masterPlaylist).mapNotNull {
            Track(
                getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        /**
         * Stream might have multiple sub-streams separated by [PLAYLIST_SEPARATOR]. Template:
         *
         * #EXTM3U
         * #EXT-X-STREAM-INF:BANDWIDTH=150000,RESOLUTION=416x234,CODECS="avc1.42e00a,mp4a.40.2"
         * http://example.com/low/index.m3u8
         * #EXT-X-STREAM-INF:BANDWIDTH=240000,RESOLUTION=416x234,CODECS="avc1.42e00a,mp4a.40.2"
         * http://example.com/lo_mid/index.m3u8
         * #EXT-X-STREAM-INF:BANDWIDTH=440000,RESOLUTION=416x234,CODECS="avc1.42e00a,mp4a.40.2"
         * http://example.com/hi_mid/index.m3u8
         * #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=640x360,CODECS="avc1.42e00a,mp4a.40.2"
         * http://example.com/high/index.m3u8
         * #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
         * http://example.com/audio/index.m3u8
         *
         * #EXTM3U
         * #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Italian",DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE="ita",URI="https://vixcloud.co/playlist/274438?type=audio&rendition=ita&token=rE-R01nYsIM8a4NkBowCtQ&expires=1752746791&edge=sc-u13-01"
         * #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,LANGUAGE="eng",URI="https://vixcloud.co/playlist/274438?type=audio&rendition=eng&token=rE-R01nYsIM8a4NkBowCtQ&expires=1752746791&edge=sc-u13-01"
         * #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English [CC]",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,LANGUAGE="eng",URI="https://vixcloud.co/playlist/274438?type=subtitle&rendition=3-eng&token=rE-R01nYsIM8a4NkBowCtQ&expires=1752746791&edge=sc-u13-01"
         * #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,LANGUAGE="eng",URI="https://vixcloud.co/playlist/274438?type=subtitle&rendition=4-eng&token=rE-R01nYsIM8a4NkBowCtQ&expires=1752746791&edge=sc-u13-01"
         * #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Italian",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,LANGUAGE="ita",URI="https://vixcloud.co/playlist/274438?type=subtitle&rendition=5-ita&token=rE-R01nYsIM8a4NkBowCtQ&expires=1752746791&edge=sc-u13-01"
         * #EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=854x480,AUDIO="audio",SUBTITLES="subs"
         * https://vixcloud.co/playlist/274438?type=video&rendition=480p&token=9vYfo_rGTzt6ns19gvR0NQ&expires=1752746791&edge=sc-u13-01
         * #EXT-X-STREAM-INF:BANDWIDTH=2150000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1280x720,AUDIO="audio",SUBTITLES="subs"
         * https://vixcloud.co/playlist/274438?type=video&rendition=720p&token=9d2Xva5pQQA4zpQdLk1_sw&expires=1752746791&edge=sc-u13-01
         * #EXT-X-STREAM-INF:BANDWIDTH=4500000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
         * https://vixcloud.co/playlist/274438?type=video&rendition=1080p&token=xEfP4QUI9tG-E6whlvwsig&expires=1752746791&edge=sc-u13-01
         *
         */
        return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull { stream ->
            val codec = CODECS_REGEX.find(stream)?.groupValues?.get(1)
            if (!codec.isNullOrBlank()) {
                // FIXME: Why skip mp4a?
                if (codec.startsWith("mp4a")) return@mapNotNull null
            }

            val resolution = RESOLUTION_REGEX.find(stream)
                ?.groupValues?.get(1)
                ?.let { resolution ->
                    val standardQuality = QUALITY_REGEX.find(resolution)
                        ?.groupValues?.get(1)
                        ?.let { toStandardQuality(it) }

                    if (!standardQuality.isNullOrBlank()) {
                        "$standardQuality ($resolution)"
                    } else {
                        resolution
                    }
                }
            val bandwidth = BANDWIDTH_REGEX.find(stream)
                    ?.groupValues?.get(1)
                    ?.toLongOrNull()
            val bandwidthFormatted = bandwidth
                    ?.let(::formatBytes)
            val streamName = listOfNotNull(resolution, bandwidthFormatted).joinToString(" - ")
                .takeIf { it.isNotBlank() }
                ?: "Video"

            val videoUrl = stream.substringAfter("\n").substringBefore("\n").let { url ->
                getAbsoluteUrl(url, playlistUrl, masterUrlBasePath)?.trimEnd()
            } ?: return@mapNotNull null

            bandwidth to Video(
                url = videoUrl,
                quality = videoNameGen(streamName),
                videoUrl = videoUrl,
                headers = videoHeadersGen(headers, referer, videoUrl),
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
            )
        }
            .sortedByDescending { (bandwidth, _) ->
                bandwidth ?: 0L
            }
            .map { (_, video) -> video }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> playlistUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
                .substringBeforeLast("/") + url
            else -> masterBase + url
        }
    }

    fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers {
        return baseHeaders.newBuilder().apply {
            set("Accept", "*/*")
            if (referer.isNotEmpty()) {
                set("Origin", "https://${referer.toHttpUrl().host}")
                set("Referer", referer)
            }
        }.build()
    }

    // ================================ DASH ================================

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video
     * @param mpdHeaders the headers to be sent in the HTTP request for the MPD file
     * @param videoHeaders the headers to be sent in the HTTP requests for video segments
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
    @Suppress("unused")
    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        mpdHeaders: Headers,
        videoHeaders: Headers,
        referer: String = mpdUrl.toDefaultReferer(),
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<Video> {
        return extractFromDash(
            mpdUrl,
            { videoRes, bandwidth ->
                videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}"
            },
            referer,
            { _, _ -> mpdHeaders },
            { _, _, _ -> videoHeaders },
            subtitleList,
            audioList,
            toStandardQuality,
        )
    }

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality
     *     - The parameter `quality` represents the quality of the video
     *     - Returns the custom name for the video, with ` - <BANDWIDTH>` added to the end
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param mpdHeadersGen a function that generates headers for the .mpd request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the .mpd request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video segment request (default: generateMasterHeaders(baseHeaders, referer))
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        referer: String = mpdUrl.toDefaultReferer(),
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<Video> {
        return extractFromDash(
            mpdUrl,
            { videoRes, bandwidth ->
                videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}"
            },
            referer,
            mpdHeadersGen,
            videoHeadersGen,
            subtitleList,
            audioList,
            toStandardQuality,
        )
    }

    /**
     * Extracts video information from a DASH .mpd file.
     *
     * @param mpdUrl the URL of the .mpd file
     * @param videoNameGen a function that generates a custom name for each video based on its quality and bandwidth
     *     - The parameter `quality` represents the quality of the video segment
     *     - The parameter `bandwidth` represents the bandwidth of the video segment, in bytes
     *     - Returns the custom name for the video
     * @param referer the referer header value to be sent in the HTTP requests (default: "")
     * @param mpdHeadersGen a function that generates headers for the .mpd request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - Returns the updated headers for the .mpd request (default: generateMasterHeaders(baseHeaders, referer))
     * @param videoHeadersGen a function that generates headers for each video request
     *     - The first parameter `baseHeaders` represents the class constructor `headers`
     *     - The second parameter `referer` represents the referer header value
     *     - The third parameter `videoUrl` represents the URL of the video
     *     - Returns the updated headers for the video segment request (default: generateMasterHeaders(baseHeaders, referer))
     * @param subtitleList a list of subtitle tracks associated with the DASH file, will append to subtitles present in the dash file (default: empty list)
     * @param audioList a list of audio tracks associated with the DASH file, will append to audio tracks present in the dash file (default: empty list)
     * @return a list of Video objects
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String, String) -> String,
        referer: String = mpdUrl.toDefaultReferer(),
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        toStandardQuality: (String) -> String = { quality ->
            stnQuality(quality)
        },
    ): List<Video> {
        val mpdHeaders = mpdHeadersGen(headers, referer)

        val doc = client.newCall(GET(mpdUrl, mpdHeaders)).execute()
            .asJsoup()

        // Get audio tracks
        val audioTracks = audioList + doc.select("Representation[mimetype~=audio]").map { audioSrc ->
            val bandwidth = audioSrc.attr("bandwidth").toLongOrNull()
            Track(audioSrc.text(), formatBytes(bandwidth))
        }

        return doc.select("Representation[mimetype~=video]").map { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val res = videoSrc.attr("height")
                .let(toStandardQuality)
                .let { "$it (${videoSrc.attr("width")}x${videoSrc.attr("height")})" }
            val videoUrl = videoSrc.text()

            Video(
                videoUrl,
                videoNameGen(res, bandwidth),
                videoUrl,
                audioTracks = audioTracks,
                subtitleTracks = subtitleList,
                headers = videoHeadersGen(headers, referer, videoUrl),
            )
        }
    }

    private fun formatBytes(bytes: Long?): String {
        return when {
            bytes == null -> ""
            bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes/s"
            bytes == 1L -> "$bytes byte/s"
            else -> ""
        }
    }

    // ============================= Utilities ==============================

    private fun String.toDefaultReferer(): String {
        return try {
            toHttpUrl().run { "$scheme://$host/" }
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    private fun stnQuality(quality: String): String {
        val intQuality = quality.trim().toIntOrNull() ?: return quality
        val result = STANDARD_QUALITIES.minByOrNull { abs(it - intQuality) } ?: intQuality
        return "${result}p"
    }

    private fun cleanSubtitleData(matchResult: MatchResult): String {
        val lineCount = matchResult.groupValues[1].count { it == '\n' }
        return "\n" + "&nbsp;\n".repeat(lineCount - 1)
    }

    fun fixSubtitles(subtitleList: List<Track>): List<Track> {
        return subtitleList.mapNotNull {
            try {
                val subData = client.newCall(GET(it.url)).execute().body.string()

                val file = File.createTempFile("subs", "vtt")
                    .also(File::deleteOnExit)

                file.writeText(FIX_SUBTITLE_REGEX.replace(subData, ::cleanSubtitleData))
                val uri = Uri.fromFile(file)

                Track(uri.toString(), it.lang)
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private val FIX_SUBTITLE_REGEX = Regex("""${'$'}(\n{2,})(?!(?:\d+:)*\d+(?:\.\d+)?\s-+>\s(?:\d+:)*\d+(?:\.\d+)?)""", RegexOption.MULTILINE)

        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"

        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }

        private val CODECS_REGEX by lazy { Regex("""CODECS="([^"]+)"""") }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=([xX\d]+)""") }
        private val QUALITY_REGEX by lazy { Regex("""[xX](\d+)""") }
        private val BANDWIDTH_REGEX by lazy { Regex("""BANDWIDTH=(\d+)""") }

        private val STANDARD_QUALITIES = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}
