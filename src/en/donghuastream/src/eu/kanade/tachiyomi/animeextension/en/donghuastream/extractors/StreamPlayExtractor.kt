package eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors

import android.util.MalformedJsonException
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.UrlUtils
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class StreamPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).execute().asJsoup()

        return document.select("#servers a").parallelCatchingFlatMapBlocking { element ->
            extractAndDecodeFromDocument(element.attr("href"), "$prefix ${element.text()} ")
        }
    }

    fun decodePackedJavaScript(encodedString: String): String {
        // Extract the `p` parameter (the actual JavaScript assignments)
        val pPattern = Pattern.compile("\\('([^']+)',")
        val pMatcher = pPattern.matcher(encodedString)
        val p = if (pMatcher.find()) pMatcher.group(1) else ""

        // Extract the `a` and `c` parameters (the two numbers)
        val numbersPattern = Pattern.compile(",(\\d+),(\\d+),")
        val numbersMatcher = numbersPattern.matcher(encodedString)
        val (a, c) = if (numbersMatcher.find()) {
            numbersMatcher.group(1)!!.toInt() to numbersMatcher.group(2)!!.toInt()
        } else {
            null to null
        }

        // Extract the `k` list correctly by capturing the string before .split('|')
        val kPattern = Pattern.compile(",$a,$c,\'([^\']+)\'\\.split\\('\\|'\\)")
        val kMatcher = kPattern.matcher(encodedString)
        val kList = if (kMatcher.find()) {
            kMatcher.group(1)!!.split("|")
        } else {
            emptyList()
        }

        // Perform the obfuscation replacement
        val result = obfuscationReplacer(p, a ?: 0, c ?: 0, kList)

        // Extract kaken and create URL
        val kakenPattern = Pattern.compile("window\\.kaken=\"([^\"]+)\";")
        val kakenMatcher = kakenPattern.matcher(result)
        val kaken = if (kakenMatcher.find()) kakenMatcher.group(1) else ""

        return "https://play.streamplay.co.in/api/?$kaken"
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

    fun extractAndDecodeFromDocument(url: String, prefix: String): List<Video> {
        try {
            val document = client.newCall(
                GET(url, headers),
            ).execute().asJsoup()

            // Find script containing the packed code
            val script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")

            if (script != null) {
                val scriptContent = script.data()
                val apiUrl = decodePackedJavaScript(scriptContent)

                val apiHeaders = headers.newBuilder().apply {
                    add("Accept", "application/json, text/javascript, */*; q=0.01")
                    add("Host", apiUrl.toHttpUrl().host)
                    add("Referer", url)
                    add("X-Requested-With", "XMLHttpRequest")
                }.build()

                val apiResponse = client.newCall(
                    GET("$apiUrl&_=${System.currentTimeMillis() / 1000}", headers = apiHeaders),
                ).execute().parseAs<APIResponse>()

                val subtitleList = apiResponse.tracks?.let { t ->
                    t.map { Track(it.file, it.label) }
                } ?: emptyList()

                return apiResponse.sources.flatMap { source ->
                    val sourceUrl = UrlUtils.fixUrl(source.file)
                    if (source.type == "hls") {
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
            }
        } catch (_: MalformedJsonException) { }
        return emptyList()
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
        )

        @Serializable
        data class TrackObject(
            val file: String,
            val label: String,
        )
    }
}
