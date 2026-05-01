package eu.kanade.tachiyomi.animeextension.en.allanime.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

class AllAnimeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun bytesIntoHumanReadable(bytes: Long): String {
        val kilobyte: Long = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000
        val terabyte = gigabyte * 1000
        return if (bytes in 0 until kilobyte) {
            "$bytes b/s"
        } else if (bytes in kilobyte until megabyte) {
            (bytes / kilobyte).toString() + " kb/s"
        } else if (bytes in megabyte until gigabyte) {
            (bytes / megabyte).toString() + " mb/s"
        } else if (bytes in gigabyte until terabyte) {
            (bytes / gigabyte).toString() + " gb/s"
        } else if (bytes >= terabyte) {
            (bytes / terabyte).toString() + " tb/s"
        } else {
            "$bytes bits/s"
        }
    }

    suspend fun videoFromUrl(url: String, name: String, endPoint: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val resp = client.newCall(
            GET(endPoint + url.replace("/clock?", "/clock.json?")),
        ).awaitSuccess()

        val linkJson = resp.parseAs<VideoLink>()

        for (link in linkJson.links) {
            val subtitles = link.subtitles?.map { sub ->
                val label = sub.label?.let { " - $it" } ?: ""
                Track(sub.src, Locale(sub.lang).displayLanguage + label)
            } ?: emptyList()

            if (link.mp4 == true) {
                videoList.add(
                    Video(
                        link.link,
                        "Original ($name - ${link.resolutionStr})",
                        link.link,
                        subtitleTracks = subtitles,
                    ),
                )
            } else if (link.hls == true) {
                val masterHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", link.link.toHttpUrl().host)
                    .add("Origin", endPoint)
                    .add("Referer", "$endPoint/")
                    .build()

                videoList.addAll(
                    playlistUtils.extractFromHls(
                        link.link,
                        masterHeaders = masterHeaders,
                        videoHeaders = masterHeaders,
                        videoNameGen = { quality -> "$quality ($name - ${link.resolutionStr})" },
                        subtitleList = subtitles,
                    ),
                )
            } else if (link.crIframe == true) {
                link.portData!!.streams.forEach {
                    if (it.format == "adaptive_dash") {
                        videoList.add(
                            Video(
                                it.url,
                                "Original (AC - Dash${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})",
                                it.url,
                                subtitleTracks = subtitles,
                            ),
                        )
                    } else if (it.format == "adaptive_hls") {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                it.url,
                                masterHeaders = headers,
                                videoHeaders = headers,
                                videoNameGen = { quality -> "$quality (AC - HLS${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})" },
                                subtitleList = subtitles,
                            ),
                        )
                    }
                }
            } else if (link.dash == true) {
                val audioList = link.rawUrls?.audios?.map {
                    Track(it.url, bytesIntoHumanReadable(it.bandwidth))
                } ?: emptyList()
                link.rawUrls?.vids?.map {
                    Video(it.url, "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth)}", it.url, audioTracks = audioList, subtitleTracks = subtitles)
                }?.let(videoList::addAll)
            }
        }

        return videoList
    }

    @Serializable
    data class VersionResponse(
        val episodeIframeHead: String,
    )

    @Serializable
    data class VideoLink(
        val links: List<Link>,
    ) {
        @Serializable
        data class Link(
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
            data class Subtitles(
                val lang: String,
                val src: String,
                val label: String? = null,
            )

            @Serializable
            data class Stream(
                val streams: List<StreamObject>,
            ) {
                @Serializable
                data class StreamObject(
                    val format: String,
                    val url: String,
                    val audio_lang: String,
                    val hardsub_lang: String,
                )
            }

            @Serializable
            data class RawUrl(
                val vids: List<DashStreamObject>? = null,
                val audios: List<DashStreamObject>? = null,
            ) {
                @Serializable
                data class DashStreamObject(
                    val bandwidth: Long,
                    val height: Int,
                    val url: String,
                )
            }
        }
    }
}
