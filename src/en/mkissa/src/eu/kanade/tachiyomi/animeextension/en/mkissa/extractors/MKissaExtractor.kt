package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

class MKissaExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        // The DASH CDN 403s any request with a Referer, and an unset header set makes the player
        // fall back to the source's, which has one.
        private val DASH_HEADERS = Headers.headersOf("Accept", "*/*")

        private val NO_HEADERS = Headers.headersOf()
    }

    private fun bytesIntoHumanReadable(bytes: Long): String {
        val kilobyte: Long = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000
        val terabyte = gigabyte * 1000
        return when {
            bytes < 0 -> "$bytes bits/s"
            bytes < kilobyte -> "$bytes b/s"
            bytes < megabyte -> "${bytes / kilobyte} kb/s"
            bytes < gigabyte -> String.format(Locale.US, "%.2f mb/s", bytes.toDouble() / megabyte)
            bytes < terabyte -> String.format(Locale.US, "%.2f gb/s", bytes.toDouble() / gigabyte)
            else -> String.format(Locale.US, "%.2f tb/s", bytes.toDouble() / terabyte)
        }
    }

    suspend fun videoFromUrl(url: String, name: String, endPoint: String): List<Video> {
        val linkJson = client.get(endPoint + url.replace("/clock?", "/clock.json?"), NO_HEADERS)
            .parseAs<VideoLink>()

        return linkJson.links.parallelCatchingFlatMap { link ->
            val subtitles = link.subtitles?.map { sub ->
                val label = sub.label?.let { " - $it" } ?: ""
                Track(sub.src, Locale(sub.lang).displayLanguage + label)
            }.orEmpty()

            when {
                link.mp4 == true -> listOf(
                    Video(
                        videoUrl = link.link,
                        videoTitle = "Original ($name - ${link.resolutionStr})",
                        subtitleTracks = subtitles,
                    ),
                )

                link.hls == true -> {
                    val masterHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", link.link.toHttpUrl().host)
                        .add("Origin", endPoint)
                        .add("Referer", "$endPoint/")
                        .build()

                    playlistUtils.extractFromHls(
                        link.link,
                        masterHeaders = masterHeaders,
                        videoHeaders = masterHeaders,
                        videoNameGen = { quality -> "$quality ($name - ${link.resolutionStr})" },
                        subtitleList = subtitles,
                    )
                }

                link.crIframe == true -> link.portData?.streams?.parallelCatchingFlatMap { stream ->
                    val hardsub = if (stream.hardsubLang.isEmpty()) "" else " - Hardsub: ${stream.hardsubLang}"
                    when (stream.format) {
                        "adaptive_dash" -> listOf(
                            Video(
                                videoUrl = stream.url,
                                videoTitle = "Original (AC - Dash$hardsub)",
                                subtitleTracks = subtitles,
                            ),
                        )

                        "adaptive_hls" -> playlistUtils.extractFromHls(
                            stream.url,
                            masterHeaders = headers,
                            videoHeaders = headers,
                            videoNameGen = { quality -> "$quality (AC - HLS$hardsub)" },
                            subtitleList = subtitles,
                        )

                        else -> emptyList()
                    }
                }.orEmpty()

                link.dash == true -> {
                    val audioList = link.rawUrls?.audios?.map {
                        Track(it.url, bytesIntoHumanReadable(it.bandwidth))
                    }.orEmpty()

                    link.rawUrls?.vids?.map {
                        Video(
                            videoUrl = it.url,
                            videoTitle = "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth)}",
                            resolution = it.height,
                            headers = DASH_HEADERS,
                            audioTracks = audioList,
                            subtitleTracks = subtitles,
                        )
                    }.orEmpty()
                }

                else -> emptyList()
            }
        }
    }

    @Serializable
    class VideoLink(
        val links: List<Link>,
    ) {
        @Serializable
        class Link(
            val link: String,
            val hls: Boolean? = null,
            val mp4: Boolean? = null,
            val dash: Boolean? = null,
            val crIframe: Boolean? = null,
            val resolutionStr: String,
            val subtitles: List<Subtitles>? = null,
            val rawUrls: RawUrl? = null,
            val portData: Stream? = null,
        ) {
            @Serializable
            class Subtitles(
                val lang: String,
                val src: String,
                val label: String? = null,
            )

            @Serializable
            class Stream(
                val streams: List<StreamObject>,
            ) {
                @Serializable
                class StreamObject(
                    val format: String,
                    val url: String,
                    @SerialName("hardsub_lang") val hardsubLang: String,
                )
            }

            @Serializable
            class RawUrl(
                val vids: List<DashStreamObject>? = null,
                val audios: List<DashStreamObject>? = null,
            ) {
                @Serializable
                class DashStreamObject(
                    val bandwidth: Long,
                    val height: Int,
                    val url: String,
                )
            }
        }
    }
}
