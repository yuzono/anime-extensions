package keiyoushi.utils

import okhttp3.OkHttpClient
import okhttp3.Request

object M3u8Utils {

    data class M3u8Stream(
        val quality: String,
        val url: String,
        val resolution: String? = null,
        val bandwidth: Long? = null,
    )

    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+x\d+)""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
    private val QUALITY_REGEX = Regex("""(\d+)p""")
    private val STREAM_URL_REGEX = Regex("""(?m)^(?!#).+\.m3u8.*$""")
    private val SUBTITLE_URI_REGEX = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)" """.trimMargin())

    fun parseMasterPlaylist(playlist: String): List<M3u8Stream> {
        val streams = mutableListOf<M3u8Stream>()
        val lines = playlist.lines()

        var currentResolution: String? = null
        var currentBandwidth: Long? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    currentResolution = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)
                    currentBandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                }
                !line.startsWith("#") && line.isNotBlank() && currentResolution != null -> {
                    val quality = extractQualityFromResolution(currentResolution)
                    streams.add(
                        M3u8Stream(
                            quality = quality,
                            url = line,
                            resolution = currentResolution,
                            bandwidth = currentBandwidth,
                        ),
                    )
                    currentResolution = null
                    currentBandwidth = null
                }
            }
        }

        return streams.sortedByDescending { it.bandwidth ?: 0 }
    }

    fun extractSubtitles(playlist: String): List<Pair<String, String>> =
        SUBTITLE_URI_REGEX.findAll(playlist).map {
            it.groupValues[1] to it.groupValues[2]
        }.toList()

    suspend fun fetchMasterPlaylist(client: OkHttpClient, masterUrl: String): List<M3u8Stream> {
        val request = Request.Builder().url(masterUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        return parseMasterPlaylist(body)
    }

    private fun extractQualityFromResolution(resolution: String): String {
        val height = resolution.substringAfter("x").toIntOrNull() ?: return resolution
        return "${height}p"
    }
}
