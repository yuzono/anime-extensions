package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.util.Base64
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

class YummyAnimeSource : AnimeHttpSource() {

    override val name = "YummyAnime"
    override val baseUrl = "https://ru.yummyani.me"
    override val lang = "ru"
    override val supportsLatest = true

    private val apiUrl = "https://api.yani.tv"
    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android)")
        .add("Accept", "application/json")

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
                player.contains("Alloha", ignoreCase = true) -> allohaVideoLinks(iframeUrl, dubbing)
                else -> fallbackVideoLinks(iframeUrl, dubbing)
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> = emptyList()

    // ─── HLS quality parser ───────────────────────────────────────────────────
    // Используется только для Alloha (Kodik уже даёт готовые URL по качествам)

    private fun extractHlsQualities(
        masterUrl: String,
        dubbing: String,
        playerName: String,
        videoHeaders: Headers,
    ): List<Video> {
        val master = runCatching {
            client.newCall(GET(masterUrl, videoHeaders)).execute().body.string()
        }.getOrNull() ?: return listOf(Video(masterUrl, "$dubbing ($playerName)", masterUrl, headers = videoHeaders))

        if (!master.contains("#EXT-X-STREAM-INF")) {
            return listOf(Video(masterUrl, "$dubbing ($playerName)", masterUrl, headers = videoHeaders))
        }

        val baseUrl = masterUrl.substringBeforeLast("/")
        val videos = mutableListOf<Video>()
        val lines = master.lines()

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue

            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)
            val label = if (height != null) "${height}p" else "unknown"

            val segmentLine = lines.getOrNull(i + 1)?.trim() ?: continue
            if (segmentLine.startsWith("#") || segmentLine.isEmpty()) continue

            val segUrl = when {
                segmentLine.startsWith("http") -> segmentLine
                segmentLine.startsWith("//") -> "https:$segmentLine"
                else -> "$baseUrl/$segmentLine"
            }
            videos.add(Video(segUrl, "$dubbing ($label $playerName)", segUrl, headers = videoHeaders))
        }

        return videos.ifEmpty {
            listOf(Video(masterUrl, "$dubbing ($playerName)", masterUrl, headers = videoHeaders))
        }
    }

    // ─── Alloha Player ───────────────────────────────────────────────────────

    private fun allohaVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val allohaHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .add("Accept", "text/html,application/xhtml+xml,*/*")
            .build()

        val body = runCatching {
            client.newCall(GET(iframeUrl, allohaHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        // Alloha может хранить URL в разных форматах JS/JSON
        val streamUrl =
            // JSON: "hls": "https://..."
            Regex(""""hls"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(body)?.groupValues?.get(1)
                // JS: file: "..." или file:"..."
                ?: Regex("""[Ff]ile\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)""").find(body)?.groupValues?.get(1)
                // JS: src: "..."
                ?: Regex("""[Ss]rc\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)""").find(body)?.groupValues?.get(1)
                // Alloha config-объект: {..." url":"https://..."
                ?: Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(body)?.groupValues?.get(1)
                // Последний шанс: голая ссылка
                ?: Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(body)?.value
                ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", iframeUrl)
            .add("Origin", iframeUrl.toOrigin())
            .add("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        return extractHlsQualities(streamUrl, dubbing, "Alloha", videoHeaders)
    }

    // ─── Kodik Player ────────────────────────────────────────────────────────
    // Оптимизации:
    //   • Декодирование через чистый Kotlin (без загрузки JS-скрипта и QuickJs)
    //   • QuickJs используется только как fallback, один экземпляр на все качества
    //   • Готовые URL от /ftor возвращаются напрямую как Video — без лишних m3u8-запросов

    private val atobRegex = Regex("atob\\([^\"]")

    private fun kodikVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val kodikHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("User-Agent", "Mozilla/5.0 (Android)")
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

        // Формат: //{host}/{type}/{id}/{hash}/720p?...
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
            .build()

        val qualityMap = mapOf(
            "360" to kodikData.links.ugly,
            "480" to kodikData.links.bad,
            "720" to kodikData.links.good,
        )

        // --- Шаг 1: пробуем быстрое Kotlin-декодирование (без QuickJs, без HTTP) ---
        // Kodik исторически использует: reverse(base64url_normalize(src)) → base64_decode → URL
        val kotlinResults = qualityMap.mapNotNull { (qualityName, links) ->
            val encodedSrc = links.firstOrNull()?.src ?: return@mapNotNull null
            val decoded = decodeKodikKotlin(encodedSrc) ?: return@mapNotNull null
            Triple(qualityName, decoded, links)
        }

        if (kotlinResults.size == qualityMap.size) {
            // Все качества декодированы без QuickJs — возвращаем сразу
            return kotlinResults.map { (qualityName, hlsUrl, _) ->
                Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders)
            }
        }

        // --- Шаг 2: fallback — QuickJs, один экземпляр на все качества ---
        val scriptUrl = page.selectFirst("script[src*=player_single]")?.attr("abs:src")
            ?: return kotlinResults.map { (q, url, _) ->
                Video(url, "$dubbing (${q}p Kodik)", url, headers = hlsHeaders)
            }

        val jsScript = runCatching {
            client.newCall(GET(scriptUrl)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val atobMatch = atobRegex.find(jsScript) ?: return emptyList()

        var encodeScript = ""
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atobMatch.range.last..jsScript.length) {
            val char = jsScript[i]
            when (char) {
                '(', '{' -> deque.addFirst(char)
                ')', '}' -> if (deque.isNotEmpty()) deque.removeFirst()
            }
            if (deque.isNotEmpty()) encodeScript += char else break
        }

        // Один QuickJs для всех качеств
        return QuickJs.create().use { qjs ->
            qualityMap.flatMap { (qualityName, links) ->
                val encodedSrc = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val base64Url = runCatching {
                    qjs.evaluate("t='$encodedSrc'; $encodeScript").toString()
                }.getOrNull() ?: return@flatMap emptyList()

                val hlsUrl = Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8).fixProtocol()
                // Готовые URL от Kodik — это уже конкретное качество, не master playlist
                listOf(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
            }
        }
    }

    /**
     * Быстрое Kotlin-декодирование Kodik без JS.
     * Kodik кодирует URL так: base64url(reverse(url)), т.е. чтобы декодировать:
     *   1. Нормализуем base64url → base64 (- → +, _ → /)
     *   2. Переворачиваем строку
     *   3. Декодируем base64
     * Возвращает null если результат не похож на валидный URL (fallback на QuickJs).
     */
    private fun decodeKodikKotlin(encoded: String): String? = runCatching {
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val reversed = normalized.reversed()
        val decoded = Base64.decode(reversed, Base64.DEFAULT).toString(Charsets.UTF_8)
        val url = decoded.fixProtocol()
        // Проверяем что получился URL, а не мусор
        if (url.startsWith("http") && url.contains(".")) url else null
    }.getOrNull()

    // ─── Fallback ─────────────────────────────────────────────────────────────

    private fun fallbackVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(iframeUrl, headers)).execute().body.string()
        }.getOrNull() ?: return emptyList()
        val stream = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(body)?.value
            ?: return emptyList()
        return listOf(Video(stream, "$dubbing (Unknown)", stream))
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
