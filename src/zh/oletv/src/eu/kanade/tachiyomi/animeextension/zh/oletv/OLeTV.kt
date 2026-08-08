package eu.kanade.tachiyomi.animeextension.zh.oletv

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OLeTV : AnimeHttpSource() {
    override val name = "OLeTV"
    override val baseUrl = "https://www.olevod.tv"
    override val lang = "zh"
    override val supportsLatest = true

    private val apiUrl = "https://api.olelive.com"
    private val json: Json by injectLazy()
    private val webView by lazy { OLeWebView(headers) }
    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
    }
    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private fun apiGet(path: String): Request {
        val url = (apiUrl + path).toHttpUrl().newBuilder()
            .addQueryParameter("_vv", webView.signature())
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeRequest(page: Int) = apiGet("/v1/pub/vod/list/true/3/0/0/0/0/0/hot/$page/20")
    override fun latestUpdatesRequest(page: Int) = apiGet("/v1/pub/vod/list/true/3/0/0/0/0/0/update/$page/20")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = apiGet("/v1/pub/index/search/${java.net.URLEncoder.encode(query, "UTF-8")}/vod/0/$page/20")

    override fun popularAnimeParse(response: Response) = parseList(response)
    override fun latestUpdatesParse(response: Response) = parseList(response)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.decryptedData().obj()
        val lists = data["data"]?.arr().orEmpty().flatMap { group ->
            group.obj()["list"]?.arr().orEmpty()
        }
        val total = data.int("total")
        return AnimesPage(lists.mapNotNull(::parseAnime), total > lists.size)
    }

    private fun parseList(response: Response): AnimesPage {
        val data = response.decryptedData().obj()
        val items = data["list"]?.arr().orEmpty()
        return AnimesPage(items.mapNotNull(::parseAnime), data.int("total") > items.size)
    }

    private fun parseAnime(element: JsonElement): SAnime? {
        val item = element.obj()
        val id = item.int("id")
        if (id == 0) return null
        val type = item.int("typeId1").takeIf { it > 0 } ?: item.int("pidId").takeIf { it > 0 } ?: 1
        return SAnime.create().apply {
            url = "/details-$type-$id.html"
            title = item.str("name").ifBlank { item.str("title") }
            thumbnail_url = imageUrl(item.str("pic").ifBlank { item.str("picThumb") }.ifBlank { item.str("img") })
            description = item.str("content")
            genre = item.str("class")
            status = if (item.str("remarks").contains("完")) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = apiGet("/v1/pub/vod/detail/${anime.url.substringAfterLast('-').substringBefore('.')}/false")

    override fun animeDetailsParse(response: Response): SAnime {
        val item = response.decryptedData().obj()
        return parseAnime(item)!!.apply {
            author = item.str("actor")
            artist = item.str("director")
            genre = listOf(item.str("class"), item.str("area"), item.str("year")).filter { it.isNotBlank() }.joinToString()
            description = item.str("content")
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val item = response.decryptedData().obj()
        val id = item.int("id")
        val type = item.int("typeId1").takeIf { it > 0 } ?: 1
        return item["urls"]?.arr().orEmpty().mapIndexed { position, element ->
            val episode = element.obj()
            val level = episode.int("index").takeIf { it > 0 } ?: position + 1
            SEpisode.create().apply {
                url = "/player/vod/$type-$id-$level.html"
                name = episode.str("title").ifBlank { "第 $level 集" }
                episode_number = level.toFloat()
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, videoHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val hls = webView.hls(response.request.url.toString())
        if (hls.isBlank()) return emptyList()
        return playlistUtils.extractFromHls(
            hls,
            referer = "$baseUrl/",
            videoNameGen = { "OLeTV - $it" },
        )
    }

    private fun Response.decryptedData(): JsonElement {
        val envelope = json.parseToJsonElement(body.string()).jsonObject
        val data = envelope["data"]
            ?: error((envelope["msg"] as? JsonPrimitive)?.content ?: "OLeTV API 返回空数据")

        return when (data) {
            is JsonObject, is JsonArray -> data
            is JsonPrimitive -> {
                val value = data.content
                require(value.isNotBlank()) { "OLeTV API 返回空数据" }
                runCatching { json.parseToJsonElement(value) }
                    .getOrElse { json.parseToJsonElement(decrypt(value)) }
            }
        }
    }

    private fun decrypt(value: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val md5 = MessageDigest.getInstance("MD5").digest(date.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = md5.substring(8, 24).toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return cipher.doFinal(Base64.decode(value, Base64.DEFAULT)).toString(Charsets.UTF_8)
    }

    private fun imageUrl(path: String): String? = path.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else "https://static.olelive.com/${it.trimStart('/')}"
    }

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.int(key: String) = str(key).toIntOrNull() ?: 0
}
