package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.sibnetextractor.SibnetExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder

class YummyAnime : AnimeHttpSource() {

    override val name = "YummyAnime"
    override val baseUrl = "https://ru.yummyani.me"
    override val lang = "ru"
    override val supportsLatest = true

    private val apiUrl = "https://api.yani.tv"
    private val json: Json by injectLazy()
    private val appToken = "o0nap18m_7a0od86"
    private val sibnetExtractor by lazy { SibnetExtractor(client) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android)")
        .add("Accept", "application/json")
        .add("X-Application", appToken)

    // ─── Popular ─────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET("$apiUrl/anime/catalog?limit=20&offset=$offset", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["response"]?.jsonObject?.get("data")?.jsonArray
            ?: return AnimesPage(emptyList(), false)

        val animes = data.map { element ->
            val obj = element.jsonObject
            SAnime.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = obj["anime_url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["poster"]?.jsonObject?.get("big")?.jsonPrimitive?.content?.fixProtocol()
            }
        }
        return AnimesPage(animes, animes.size == 20)
    }

    // ─── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$apiUrl/search?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["response"]?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animes = data.map { element ->
            val obj = element.jsonObject
            SAnime.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = obj["anime_url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["poster"]?.jsonObject?.get("big")?.jsonPrimitive?.content?.fixProtocol()
            }
        }
        return AnimesPage(animes, false)
    }

    // ─── Anime Details ────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val obj = json.parseToJsonElement(response.body.string())
            .jsonObject["response"]?.jsonObject ?: return SAnime.create()

        return SAnime.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            description = obj["description"]?.jsonPrimitive?.content
            genre = obj["genres"]?.jsonArray
                ?.joinToString { it.jsonObject["title"]?.jsonPrimitive?.content ?: "" }
            status = when (obj["anime_status"]?.jsonObject?.get("value")?.jsonPrimitive?.content) {
                "0" -> SAnime.COMPLETED
                "1" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            author = obj["studios"]?.jsonArray
                ?.joinToString { it.jsonObject["title"]?.jsonPrimitive?.content ?: "" }
            thumbnail_url = obj["poster"]?.jsonObject?.get("huge")?.jsonPrimitive?.content?.fixProtocol()
        }
    }

    // ─── Episodes ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url}?need_videos=true", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeSlug = response.request.url.pathSegments.last()
        val obj = json.parseToJsonElement(response.body.string())
            .jsonObject["response"]?.jsonObject ?: return emptyList()

        val videos = obj["videos"]?.jsonArray ?: return emptyList()

        return videos
            .groupBy { it.jsonObject["number"]?.jsonPrimitive?.content ?: "1" }
            .map { (num, _) ->
                SEpisode.create().apply {
                    name = "Серия $num"
                    episode_number = num.toFloatOrNull() ?: 1f
                    url = "$animeSlug|$num"
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ─── Videos ──────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request {
        val (animeSlug, episodeNum) = episode.url.split("|", limit = 2)
        return GET("$apiUrl/anime/$animeSlug?need_videos=true&episode=$episodeNum", headers)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode))
        .awaitSuccess()
        .use { videoListParseAsync(it) }

    private suspend fun videoListParseAsync(response: Response): List<Video> {
        val episodeNum = response.request.url.queryParameter("episode") ?: return emptyList()

        val obj = json.parseToJsonElement(response.body.string())
            .jsonObject["response"]?.jsonObject ?: return emptyList()

        val allVideos = obj["videos"]?.jsonArray ?: return emptyList()

        val episodeVideos = allVideos.filter { element ->
            element.jsonObject["number"]?.jsonPrimitive?.content == episodeNum
        }

        return episodeVideos.parallelCatchingFlatMap { element ->
            val obj2 = element.jsonObject
            val dubbing = obj2["data"]?.jsonObject?.get("dubbing")?.jsonPrimitive?.content ?: "Unknown"
            val player = obj2["data"]?.jsonObject?.get("player")?.jsonPrimitive?.content ?: ""
            val rawFrame = obj2["iframe_url"]?.jsonPrimitive?.content ?: return@parallelCatchingFlatMap emptyList()
            val iframeUrl = rawFrame.fixProtocol()

            when {
                player.contains("Kodik", ignoreCase = true) -> kodikVideoLinks(iframeUrl, dubbing)
                else -> fallbackVideoLinks(iframeUrl, dubbing)
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> = emptyList()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun extractHlsQualities(
        masterUrl: String,
        dubbing: String,
        playerName: String,
        videoHeaders: Headers,
    ): List<Video> = playlistUtils.extractFromHls(
        masterUrl,
        masterHeaders = videoHeaders,
        videoHeaders = videoHeaders,
        videoNameGen = { quality -> "$dubbing ($quality $playerName)" },
    ).ifEmpty {
        listOf(Video(masterUrl, "$dubbing ($playerName)", masterUrl, headers = videoHeaders))
    }

    // ─── Kodik Player ────────────────────────────────────────────────────────
    // Optimizations:
    //   • Decoding via pure Kotlin (without loading JS script and QuickJs)
    //   • QuickJs is used only as a fallback, one instance for all qualities
    //   • Ready URLs from /ftor are returned directly as Video — without unnecessary m3u8 requests

    private val atobRegex = Regex("atob\\([^\"]")

    private fun kodikVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val kodikHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .add("X-Application", appToken)
            .build()

        val page = runCatching {
            client.newCall(GET(iframeUrl, kodikHeaders)).execute().useAsJsoup()
        }.getOrNull() ?: return emptyList()

        val urlParamsScript = page.select("script").firstOrNull { it.data().contains("urlParams") }?.data()
            ?: return emptyList()

        val formData = runCatching {
            val raw = urlParamsScript.substringAfter("urlParams = '").substringBefore("'")
            json.decodeFromString(KodikFormData.serializer(), raw)
        }.getOrNull() ?: return emptyList()

        if (formData.dSign.isEmpty()) return emptyList()

        // Format: //{host}/{type}/{id}/{hash}/720p?...
        val urlParts = iframeUrl.removePrefix("https://").removePrefix("http://").split('/')
        if (urlParts.size < 4) return emptyList()
        val videoType = urlParts[1]
        val videoId = urlParts[2]
        val videoHash = urlParts[3]

        val postBody = FormBody.Builder()
            .add("d", formData.d)
            .add("d_sign", URLDecoder.decode(formData.dSign, "utf-8"))
            .add("pd", formData.pd)
            .add("pd_sign", URLDecoder.decode(formData.pdSign, "utf-8"))
            .add("ref", URLDecoder.decode(formData.ref, "utf-8"))
            .add("ref_sign", URLDecoder.decode(formData.refSign, "utf-8"))
            .add("type", videoType)
            .add("id", videoId)
            .add("hash", videoHash)
            .build()

        val postHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .add("X-Application", appToken)
            .build()

        val kodikData = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://${formData.pd}/ftor")
                    .post(postBody)
                    .headers(postHeaders)
                    .build(),
            ).execute().parseAs<KodikData>()
        }.getOrNull() ?: return emptyList()

        val hlsHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .add("X-Application", appToken)
            .build()

        val qualityMap = mapOf(
            "360" to kodikData.links.ugly,
            "480" to kodikData.links.bad,
            "720" to kodikData.links.good,
        )

        // --- Step 1: Try fast Kotlin decoding (no QuickJs, no HTTP) ---
        // Kodik historically uses: reverse(base64url_normalize(src)) → base64_decode → URL
        val kotlinResults = qualityMap.mapNotNull { (qualityName, links) ->
            val encodedSrc = links.firstOrNull()?.src ?: return@mapNotNull null
            val decoded = decodeKodikKotlin(encodedSrc) ?: return@mapNotNull null
            Triple(qualityName, decoded, links)
        }

        if (kotlinResults.size == qualityMap.size) {
            // All qualities are decoded without QuickJs — we return them immediately
            return kotlinResults.flatMap { (qualityName, hlsUrl, _) ->
                if (hlsUrl.contains(".mpd")) {
                    PlaylistUtils(client, headers).extractFromDash(
                        hlsUrl,
                        { res: String -> "$dubbing (${qualityName}p Kodik - $res)" },
                        hlsHeaders,
                        hlsHeaders,
                    )
                } else {
                    listOf(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
                }
            }
        }

        // --- Step 2: fallback — QuickJs, one instance per quality ---
        val scriptUrl = page.selectFirst("script[src*=player_single]")?.attr("abs:src")
            ?: return kotlinResults.map { (q, url, _) ->
                Video(url, "$dubbing (${q}p Kodik)", url, headers = hlsHeaders)
            }

        val jsScript = runCatching {
            client.newCall(GET(scriptUrl, kodikHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val atobMatch = atobRegex.find(jsScript) ?: return emptyList()

        var encodeScript = "("
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atobMatch.range.last until jsScript.length) {
            val char = jsScript[i]
            when (char) {
                '(', '{' -> deque.addFirst(char)
                ')', '}' -> if (deque.isNotEmpty()) deque.removeFirst()
            }
            encodeScript += char
            if (deque.isEmpty()) break
        }

        // One QuickJs for all qualities
        return QuickJs.create().use { qjs ->
            qualityMap.flatMap { (qualityName, links) ->
                val encodedSrc = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val base64Url = runCatching {
                    qjs.evaluate("t='$encodedSrc'; $encodeScript").toString()
                }.getOrNull() ?: return@flatMap emptyList()

                val hlsUrl = Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8).fixProtocol()
                // If it's DASH, parse the mpd
                if (hlsUrl.contains(".mpd")) {
                    return@flatMap PlaylistUtils(client, headers).extractFromDash(
                        hlsUrl,
                        { res: String -> "$dubbing (${qualityName}p Kodik - $res)" },
                        hlsHeaders,
                        hlsHeaders,
                    )
                }

                // Ready-made URLs from Kodik are already of a specific quality, not a master playlist
                listOf(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
            }
        }
    }

    // Fast Kotlin decoding of Kodik without JS.
    // Kodik encodes a URL like this: base64url(reverse(url)), i.e. to decode:
    //   1. Normalize base64url → base64 (- → +, \_ → /)
    //   2. Reverse the string
    //   3. Decode base64
    // Returns null if the result does not look like a valid URL (fallback to QuickJs).

    private fun decodeKodikKotlin(encoded: String): String? = runCatching {
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val reversed = normalized.reversed()
        val decoded = Base64.decode(reversed, Base64.DEFAULT).toString(Charsets.UTF_8)
        val url = decoded.fixProtocol()
        // Check that the URL is correct and not garbage
        if (url.startsWith("http") && url.contains(".")) url else null
    }.getOrNull()

    // ─── Fallback ─────────────────────────────────────────────────────────────

    private fun fallbackVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(iframeUrl, headers)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        // Let's try to parse Sibnet (the embedded player uses player.src)
        if (iframeUrl.contains("sibnet.ru") || body.contains("player.src")) {
            // Trying to find a videoid to send signals (player sends kibana/catch requests according to the logs)
            val videoId = Regex("videoid=(\\d+)").find(body)?.groupValues?.get(1)
                ?: Regex("videoid=(\\d+)").find(iframeUrl)?.groupValues?.get(1)

            if (!videoId.isNullOrBlank()) {
                runCatching {
                    val rn = (Math.random() * 1_0000_0000).toInt()
                    val catchUrl = "https://vst.sibnet.ru/catch?event=load&val=null&videoid=$videoId&referrer=$iframeUrl&rn=$rn"
                    client.newCall(GET(catchUrl, headers.newBuilder().set("Referer", iframeUrl).build())).execute().close()
                }
            }

            // We will use the general Sibnet extractor, if it is available
            val sibVideos = runCatching { sibnetExtractor.videosFromUrl(iframeUrl, "$dubbing (Sibnet) ") }.getOrNull()
            if (!sibVideos.isNullOrEmpty()) return sibVideos
        }

        // Let's check the DASH first
        val mpd = Regex("""https?://[^"'\\s\\\\]+\\.mpd[^"'\\s\\\\]*""").find(body)?.value
        if (mpd != null) {
            val videoHeaders = Headers.Builder()
                .add("Referer", iframeUrl)
                .add("Origin", iframeUrl.toOrigin())
                .add("User-Agent", "Mozilla/5.0 (Android)")
                .add("X-Application", appToken)
                .build()

            return PlaylistUtils(client, headers).extractFromDash(
                mpd,
                { res: String -> "$dubbing (DASH $res)" },
                videoHeaders,
                videoHeaders,
            )
        }

        val stream = Regex("""https?://[^"'\\s]+\\.m3u8[^"'\\s]*""").find(body)?.value
            ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", iframeUrl)
            .add("Origin", iframeUrl.toOrigin())
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .add("X-Application", appToken)
            .build()

        return listOf(Video(stream, "$dubbing (Unknown)", stream, headers = videoHeaders))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun String.fixProtocol(): String = if (startsWith("//")) "https:$this" else this

    private fun String.toOrigin(): String = Regex("^(https?://[^/]+)").find(this)?.groupValues?.get(1) ?: this
}

// ─── Kodik DTOs ──────────────────────────────────────────────────────────────

@Serializable
private data class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
private data class KodikVideoInfo(val src: String)

@Serializable
private data class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
)

@Serializable
private data class KodikData(val links: KodikVideoQuality)
