package eu.kanade.tachiyomi.animeextension.en.animeverse

import android.annotation.SuppressLint
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

class AnimeVerse :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeVerse"
    override val baseUrl = "https://animeverse.to"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    private val fingerprint: String by lazy {
        preferences.getString("fp_json", null) ?: run {
            val ua = System.getProperty("http.agent")
                ?.replace("\"", "\\\"")
                ?.replace("\\", "\\\\")
                ?: "Mozilla/5.0"
            """{"ua":"$ua","language":"en-US","timezone":"UTC","hw":8,"screen":"1920x1080x24","canvas":"kW9_MAWuv_3eBlyA7DxVWY","webgl":"Google Inc. (NVIDIA)|ANGLE (NVIDIA, GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)"}"""
        }.also { preferences.edit().putString("fp_json", it).apply() }
    }

    // ============================== Auth ==============================

    private val lock = Any()
    private var sessionCookie = ""
    private var authKey = ""
    private var authExpires = 0L

    private fun getAuth(): Pair<String, String> = synchronized(lock) {
        if (authKey.isNotEmpty() && System.currentTimeMillis() / 1000 < authExpires) {
            return authKey to sessionCookie
        }

        val body = """{"fp":$fingerprint}""".toJsonBody()

        val sessionReq = Request.Builder()
            .url("$baseUrl/api/v1/session")
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        val sessionResp = network.client.newCall(sessionReq).execute()
        val respBody = sessionResp.bodyString()

        if (!sessionResp.isSuccessful) {
            invalidateAuth()
            throw Exception("Session failed (${sessionResp.code}): $respBody")
        }

        sessionResp.headers("Set-Cookie")
            .firstOrNull { it.startsWith("av_session=") }
            ?.substringAfter("=")?.substringBefore(";")
            ?.let { sessionCookie = it }

        val obj = try {
            respBody.parseAs<JsonElement>(json).jsonObject
        } catch (_: Exception) {
            invalidateAuth()
            throw Exception("Invalid session JSON: $respBody")
        }

        val key = obj["clientAuthKey"]?.jsonPrimitive?.contentOrNull
        if (key.isNullOrEmpty()) {
            invalidateAuth()
            throw Exception("No clientAuthKey in response: $respBody")
        }

        authKey = key
        authExpires = obj["expiresAt"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() / 1000 + 3600)
        sessionResp.close()

        authKey to sessionCookie
    }

    private fun invalidateAuth() = synchronized(lock) {
        authKey = ""
        authExpires = 0L
        sessionCookie = ""
    }

    // ======================= Client + Interceptor =========================

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(::authInterceptor)
            .build()
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.encodedPath.startsWith("/api/v1/")) return chain.proceed(req)

        val signed = sign(req)
        val resp = chain.proceed(signed)
        if (resp.code != 401) return resp
        resp.close()

        invalidateAuth()
        return chain.proceed(sign(req))
    }

    private fun sign(request: Request): Request {
        val (key, cookie) = getAuth()
        if (key.isEmpty()) return request

        val ts = System.currentTimeMillis().toString()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(base64UrlDecode(key), "HmacSHA256"))
        }
        val sig = base64UrlEncode(
            mac.doFinal("${request.method}|${request.url.encodedPath}|$ts".toByteArray()).copyOf(16),
        )
        return request.newBuilder()
            .header("x-av-ts", ts)
            .header("x-av-sig", sig)
            .header("Cookie", "av_session=$cookie")
            .build()
    }

    // =========================== Helpers ==============================

    private fun SAnime.slug(): String = url.substringAfter("/series/")

    private fun decodeStreamBase64(path: String): String = runCatching {
        String(base64UrlDecode(path.substringAfter("/v/").substringBefore(".")))
    }.getOrDefault("")

    private fun resolveImage(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("http")) return path
        if (path.startsWith("/i/")) {
            runCatching { String(base64UrlDecode(path.substringAfter("/i/"))) }
                .getOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return "$baseUrl$path"
    }

    @SuppressLint("DefaultLocale")
    private fun formatRating(rating10: Double): String {
        if (rating10 <= 0) return ""
        val fullStars = (rating10 / 2.0).roundToInt().coerceIn(0, 5)
        val emptyStars = 5 - fullStars
        val stars = "★".repeat(fullStars) + "☆".repeat(emptyStars)
        return "$stars ${String.format("%.2f", rating10)}"
    }

    private fun base64UrlEncode(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun base64UrlDecode(str: String): ByteArray = Base64.decode(str, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.double(key: String): Double = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun JsonObject.stringArray(key: String): List<String> = this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun jsonToAnime(el: JsonElement): SAnime {
        val o = el.jsonObject
        val genres = o.stringArray("genres")
        val studios = o.stringArray("studios")
        val mainTitle = o.string("title") ?: "Unknown"
        val altTitle = o.string("alternativeTitle")?.takeIf { it.isNotEmpty() }
        val useAlt = preferences.getBoolean(PREF_USE_ALT_TITLE, PREF_USE_ALT_TITLE_DEFAULT)

        return SAnime.create().apply {
            title = if (useAlt) altTitle ?: mainTitle else mainTitle
            url = "/series/${o.string("slug")}"
            thumbnail_url = resolveImage(o.string("cover") ?: o.string("thumb"))
            author = studios.takeIf { it.isNotEmpty() }?.joinToString(", ")
            genre = genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
            status = SAnime.UNKNOWN
        }
    }

    private fun recentToAnime(el: JsonElement): SAnime {
        val o = el.jsonObject
        return SAnime.create().apply {
            title = o.string("seriesTitle") ?: "Unknown"
            url = "/series/${o.string("seriesSlug")}"
            thumbnail_url = resolveImage(o.string("thumb"))
            genre = o.string("language")?.uppercase()
            status = SAnime.UNKNOWN
        }
    }

    private fun extractArray(root: JsonElement): List<JsonElement> = when (root) {
        is JsonArray -> root
        is JsonObject -> root.values.filterIsInstance<JsonArray>().firstOrNull()
            ?: emptyList()
        else -> emptyList()
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/trending?period=today&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val arr = extractArray(root)
        // Only paginate if the API explicitly says there's more
        val hasNext = (root as? JsonObject)
            ?.get("hasNext")?.jsonPrimitive?.booleanOrNull == true
        return AnimesPage(arr.map(::jsonToAnime), hasNext)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/recent")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json).jsonObject
        val items = root["items"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        return AnimesPage(items.map(::recentToAnime), false)
    }

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/api/v1/catalog?q=${URLEncoder.encode(query, "UTF-8")}")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val arr = extractArray(root)
        val q = response.request.url.queryParameter("q")?.lowercase().orEmpty()

        val filtered = if (q.isBlank()) {
            arr
        } else {
            arr.filter { el ->
                val o = el.jsonObject
                o.string("searchTitle")?.lowercase()?.contains(q) == true ||
                    o.string("title")?.lowercase()?.contains(q) == true
            }
        }

        return AnimesPage(filtered.map(::jsonToAnime), false)
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/series/${anime.slug()}")

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.encodedPath.substringAfter("/series/")

        val o = client.newCall(GET("$baseUrl/api/v1/anime/$slug"))
            .execute().bodyString()
            .parseAs<JsonElement>(json)
            .jsonObject

        val arr = client.newCall(GET("$baseUrl/api/v1/catalog"))
            .execute().bodyString()
            .let { extractArray(it.parseAs<JsonElement>(json)) }
        val cat = arr.firstOrNull { it.jsonObject.string("slug") == slug }?.jsonObject

        val rating = o.double("rating")
        val synopsis = o.string("synopsis").orEmpty()
        val ratingLine = formatRating(rating)
        val epCount = o["episodes"]?.jsonArray?.size ?: 0

        val mainTitle = o.string("title") ?: "Unknown"
        val altTitle = cat?.string("alternativeTitle")?.takeIf { it.isNotEmpty() && it != mainTitle }
        val useAlt = preferences.getBoolean(PREF_USE_ALT_TITLE, PREF_USE_ALT_TITLE_DEFAULT)
        val displayTitle = if (useAlt) altTitle ?: mainTitle else mainTitle

        val genres = cat?.stringArray("genres")?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val studios = cat?.stringArray("studios")?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val premiered = cat?.string("premiered")
        val animeType = cat?.string("type") ?: o.string("type")
        val ratingLabel = o.string("ratingLabel")

        val header = listOfNotNull(ratingLine)

        // If we used the alt title as the main title, show the original in the footer
        val footerAltLine = if (displayTitle == altTitle) {
            "**Original:** $mainTitle"
        } else {
            altTitle?.let { "**Alt:** $it" }
        }

        val footer = listOfNotNull(
            footerAltLine,
            animeType?.let { "**Type:** $it" },
            premiered?.let { "**Premiered:** $it" },
            ratingLabel?.let { "**Rating:** $it" },
            if (epCount > 0) "**Episodes:** $epCount" else null,
        )

        val description = listOf(header.joinToString("\n"), synopsis, footer.joinToString("\n"))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

        return SAnime.create().apply {
            title = displayTitle
            url = "/series/${o.string("slug")}"
            thumbnail_url = resolveImage(o.string("cover") ?: o.string("thumb"))
            this.description = description
            author = studios
            genre = genres
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/api/v1/anime/${anime.slug()}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val o = response.bodyString().parseAs<JsonElement>(json).jsonObject
        val episodes = o["episodes"]?.jsonArray ?: return emptyList()
        val slug = o.string("slug").orEmpty()

        return episodes
            .map { it.jsonObject }
            .groupBy { it.int("number") }
            .map { (num, variants) ->
                val payload = buildJsonObject {
                    put("slug", slug)
                    put("ep", num)
                    put(
                        "streams",
                        buildJsonArray {
                            variants.forEach { v ->
                                addJsonObject {
                                    put("k", v.string("kind") ?: "")
                                    put("s", v.string("stream") ?: "")
                                }
                            }
                        },
                    )
                }.toString()
                SEpisode.create().apply {
                    episode_number = num.toFloat()
                    name = "Episode $num"
                    url = base64UrlEncode(payload.toByteArray())
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================== Videos ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/?_d=${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val encoded = response.request.url.queryParameter("_d") ?: return emptyList()
        val payload = String(base64UrlDecode(encoded)).parseAs<JsonElement>(json).jsonObject
        val slug = payload.string("slug").orEmpty()
        val epNum = payload.int("ep")
        val preferDirect = preferences.getBoolean(PREF_DIRECT_MP4, PREF_DIRECT_MP4_DEFAULT)

        val freshData = client.newCall(GET("$baseUrl/api/v1/anime/$slug"))
            .execute().bodyString()
            .parseAs<JsonElement>(json)
            .jsonObject

        val allEpisodes = freshData["episodes"]?.jsonArray ?: return emptyList()
        val streams = allEpisodes.map { it.jsonObject }.filter { it.int("number") == epNum }
        if (streams.isEmpty()) return emptyList()

        val cookie = synchronized(lock) { sessionCookie }
        val referer = "$baseUrl/series/$slug/$epNum"

        return streams.map { ep ->
            val kind = (ep.string("kind") ?: "sub").uppercase()
            val streamPath = ep.string("stream") ?: return@map emptyList()
            val directUrl = decodeStreamBase64(streamPath)

            val directVideo = if (directUrl.isNotEmpty()) {
                Video(directUrl, "$kind - Direct", directUrl)
            } else {
                null
            }

            val proxiedVideo = Video(
                "$baseUrl$streamPath",
                "$kind - Proxied",
                "$baseUrl$streamPath",
                Headers.Builder()
                    .add("Referer", referer)
                    .add("Cookie", "av_session=$cookie")
                    .build(),
            )

            if (preferDirect) {
                listOfNotNull(directVideo, proxiedVideo)
            } else {
                listOfNotNull(proxiedVideo, directVideo)
            }
        }.flatten()
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = PREF_USE_ALT_TITLE,
            title = "Use Alternative Titles",
            summary = "Prefer alternative/English titles over original. Falls back to original.",
            default = PREF_USE_ALT_TITLE_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_DIRECT_MP4,
            title = "Prefer Direct MP4",
            summary = "Use direct Base64 decoded MP4 stream instead of proxy.",
            default = PREF_DIRECT_MP4_DEFAULT,
        )
    }

    companion object {
        private const val PREF_USE_ALT_TITLE = "use_alt_title"
        private const val PREF_USE_ALT_TITLE_DEFAULT = false
        private const val PREF_DIRECT_MP4 = "direct_mp4"
        private const val PREF_DIRECT_MP4_DEFAULT = false
    }
}
