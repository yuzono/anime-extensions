package aniyomi.lib.dailymotionextractor

import aniyomi.lib.hlsdash.HlsDashServer
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val DAILYMOTION_URL = "https://www.dailymotion.com"
        private const val GRAPHQL_URL = "https://graphql.api.dailymotion.com"
    }

    private fun headersBuilder(block: Headers.Builder.() -> Unit = {}) = headers.newBuilder()
        .add("Accept", "*/*")
        .set("Referer", "$DAILYMOTION_URL/")
        .set("Origin", DAILYMOTION_URL)
        .apply { block() }
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - ", baseUrl: String = "", password: String? = null): List<Video> {
        val htmlString = client.newCall(GET(url)).execute().body.string()

        val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
        val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
        val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

        val videoQuery = url.toHttpUrl().run {
            queryParameter("video") ?: pathSegments.last()
        }

        val jsonUrl = "$DAILYMOTION_URL/player/metadata/video/$videoQuery?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = client.newCall(GET(jsonUrl)).execute().parseAs<DailyQuality>()

        return when {
            parsed.qualities != null && parsed.error == null -> videosFromDailyResponse(parsed, prefix)
            parsed.error?.type == "password_protected" && parsed.id != null -> {
                videosFromProtectedUrl(url, prefix, parsed.id, htmlString, ts, v1st, baseUrl, password)
            }
            else -> emptyList()
        }
    }

    private fun videosFromProtectedUrl(
        url: String,
        prefix: String,
        videoId: String,
        htmlString: String,
        ts: String,
        v1st: String,
        baseUrl: String,
        password: String?,
    ): List<Video> {
        val postUrl = "$GRAPHQL_URL/oauth/token"
        val clientId = htmlString.substringAfter("client_id\":\"").substringBefore('"')
        val clientSecret = htmlString.substringAfter("client_secret\":\"").substringBefore('"')
        val scope = htmlString.substringAfter("client_scope\":\"").substringBefore('"')

        val tokenBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("traffic_segment", ts)
            .add("visitor_id", v1st)
            .add("grant_type", "client_credentials")
            .add("scope", scope)
            .build()

        val tokenResponse = client.newCall(POST(postUrl, headersBuilder(), tokenBody)).execute()
        val tokenParsed = tokenResponse.parseAs<TokenResponse>()

        val idUrl = "$GRAPHQL_URL/"
        val idHeaders = headersBuilder {
            set("Accept", "application/json, text/plain, */*")
            add("Authorization", "${tokenParsed.tokenType} ${tokenParsed.accessToken}")
        }

        val idData = """
            {
               "query":"query playerPasswordQuery(${'$'}videoId:String!,${'$'}password:String!){video(xid:${'$'}videoId,password:${'$'}password){id xid}}",
               "variables":{
                  "videoId":"$videoId",
                  "password":"$password"
               }
            }
        """.trimIndent().toJsonRequestBody()

        val idResponse = client.newCall(POST(idUrl, idHeaders, idData)).execute()
        val idParsed = idResponse.parseAs<ProtectedResponse>().data.video

        val dmvk = htmlString.substringAfter("\"dmvk\":\"").substringBefore('"')
        val getVideoIdUrl = "$DAILYMOTION_URL/player/metadata/video/${idParsed.xid}?embedder=${"$baseUrl/"}&locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val getVideoIdHeaders = headersBuilder {
            add("Cookie", "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.accessToken}")
            set("Referer", url)
        }

        val parsed = client.newCall(GET(getVideoIdUrl, getVideoIdHeaders)).execute()
            .parseAs<DailyQuality>()

        return videosFromDailyResponse(parsed, prefix, getVideoIdHeaders)
    }

    private fun videosFromDailyResponse(parsed: DailyQuality, prefix: String, playlistHeaders: Headers? = null): List<Video> {
        val masterUrl = parsed.qualities?.auto?.firstOrNull()?.url
            ?: return emptyList()

        val subtitleList = parsed.subtitles?.data?.map {
            Track(it.urls.first(), it.label)
        } ?: emptyList()

        val masterHeaders = playlistHeaders ?: headersBuilder()

        val videos = playlistUtils.extractFromHls(
            masterUrl,
            masterHeadersGen = { _, _ -> masterHeaders },
            videoHeadersGen = { _, _, _ -> masterHeaders },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )

        // Newer uploads are fMP4 HLS with audio in separate `#EXT-X-MEDIA:TYPE=AUDIO` renditions.
        // Serve each video/audio pair as DASH, keeping the default audio first and all renditions selectable.
        if (parsed.isFmp4) {
            return videos.flatMap { video ->
                // Without a separate audio rendition, keep HLS so any embedded audio is preserved.
                if (video.audioTracks.isEmpty()) return@flatMap listOf(video)

                val audios = video.audioTracks
                audios.map { audio ->
                    val dashUrl = HlsDashServer.register(client, masterHeaders, video.videoUrl, audio.url)
                    Video(
                        videoUrl = dashUrl,
                        videoTitle = video.videoTitle + if (audios.size > 1) " - ${audio.lang}" else "",
                        headers = masterHeaders,
                        subtitleTracks = video.subtitleTracks,
                    )
                }
            }
        }

        // Otherwise offer the master playlist first so the player resolves any audio group itself
        // instead of relying on external audio tracks.
        val firstVideo = videos.firstOrNull() ?: return videos
        if (firstVideo.audioTracks.isEmpty()) return videos

        val autoVideo = Video(
            url = masterUrl,
            quality = "${prefix}Auto",
            videoUrl = masterUrl,
            headers = masterHeaders,
            subtitleTracks = firstVideo.subtitleTracks,
        )
        return listOf(autoVideo) + videos
    }
}
