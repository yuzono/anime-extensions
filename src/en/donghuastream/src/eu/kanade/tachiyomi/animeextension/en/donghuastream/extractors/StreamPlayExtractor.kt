package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.UrlUtils
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().use { it.asJsoup() }

        return document.select("#servers a").parallelCatchingFlatMapBlocking { element ->
            extractAndDecodeFromDocument(element.attr("href"), "$prefix ${element.text()} ")
        }
    }

    private val pRegex by lazy { Regex("\\('([^']+)',") }
    private val numbersRegex by lazy { Regex(",(\\d+),(\\d+),") }
    private val kakenRegex by lazy { Regex("window\\.kaken ?= ?\"([^\"]+)\";") }

    fun decodePackedJavaScript(encodedString: String): String? {
        // Extract the `p` parameter (the actual JavaScript assignments)
        val p = pRegex.find(encodedString)?.groupValues?.get(1) ?: ""

        // Extract the `a` and `c` parameters (the two numbers)
        val (a, c) = numbersRegex.find(encodedString)?.groupValues?.let {
            it[1].toInt() to it[2].toInt()
        } ?: (null to null)

        // Extract the `k` list correctly by capturing the string before .split('|')
        val kRegex = Regex(",$a,$c,\'([^\']+)\'\\.split\\('\\|'\\)")
        val kList = kRegex.find(encodedString)?.groupValues?.get(1)?.split("|")
            ?: emptyList()

        // Perform the obfuscation replacement
        val result = obfuscationReplacer(p, a ?: 0, c ?: 0, kList)

        // Extract kaken
        val kaken = kakenRegex.find(result)?.groupValues?.get(1)

        return kaken
    }

    private fun obfuscationReplacer(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var currentC = c

        while (currentC > 0) {
            currentC--
            if (k.getOrNull(currentC)?.isNotEmpty() == true) {
                val pattern = "\\b${baseN(currentC, a)}\\b".toRegex()
                result = result.replace(pattern, k[currentC])
            }
        }
        return result
    }

    private fun baseN(num: Int, base: Int, numerals: String = "0123456789abcdefghijklmnopqrstuvwxyz"): String {
        if (num == 0) return numerals[0].toString()
        var number = num
        var result = ""
        while (number > 0) {
            result = numerals[number % base] + result
            number /= base
        }
        return result
    }

    /**
     * Server 3 has issue with playlist compatibility, it only plays the first segment
     */
    fun extractAndDecodeFromDocument(url: String, prefix: String): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().use { it.asJsoup() }

        // Find script containing the packed code
        val packedScript = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")

        val kaken = if (packedScript != null) {
            val scriptContent = packedScript.data()
            decodePackedJavaScript(scriptContent)
        } else {
            // For mobile UA, it's non-packed
            document.selectFirst("script:containsData(window.kaken)")
                ?.data()?.let {
                    // Extract kaken
                    kakenRegex.find(it)?.groupValues?.get(1)
                }
        } ?: return emptyList()

        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", httpUrl.host)
            add("Origin", "${httpUrl.scheme}://${httpUrl.host}")
            add("Referer", url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val apiResponse = client.newCall(
            POST(
                "https://play.streamplay.co.in/api/",
                headers = apiHeaders,
                body = kaken.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
            ),
        ).execute().use { it.parseAs<APIResponse>() }

        val subtitleList = apiResponse.tracks?.let { t ->
            t.map { Track(it.file, it.label) }
        } ?: emptyList()

        val videos = apiResponse.sources.flatMap { source ->
            val sourceUrl = UrlUtils.fixUrl(source.videoUrl)
            if (source.type == "hls" && sourceUrl.endsWith("master.m3u8")) {
                playlistUtils.extractFromHls(sourceUrl, referer = url, subtitleList = subtitleList, videoNameGen = { q -> "$prefix$q (StreamPlay)" })
            } else {
                listOf(
                    Video(
                        sourceUrl,
                        "$prefix (StreamPlay) Original",
                        sourceUrl,
                        headers = headers,
                        subtitleTracks = subtitleList,
                    ),
                )
            }
        }
        return videos
    }

    @Serializable
    data class APIResponse(
        val sources: List<SourceObject>,
        val tracks: List<TrackObject>? = null,
    ) {
        @Serializable
        data class SourceObject(
            val file: String,
            val label: String,
            val type: String,
        ) {
            val videoUrl: String
                get() = file.replace("master.txt", "master.m3u8")
        }

        @Serializable
        data class TrackObject(
            val file: String,
            val label: String,
        )
    }
}
