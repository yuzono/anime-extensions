package eu.kanade.tachiyomi.animeextension.en.animeverse

import android.annotation.SuppressLint
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
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

    // ========================= Catalog Cache ==========================

    @Volatile
    private var catalogCache: List<JsonElement>? = null
    private var catalogCacheTime: Long = 0L
    private val catalogCacheLock = Any()

    private val catalogCacheTtl = 5 * 60 * 1000L // 5 minutes

    private fun getCatalog(existingResponse: Response? = null): List<JsonElement> = synchronized(catalogCacheLock) {
        val now = System.currentTimeMillis()
        catalogCache?.takeIf { now - catalogCacheTime < catalogCacheTtl }?.let { return it }

        val arr = if (existingResponse != null) {
            extractArray(existingResponse.bodyString().parseAs<JsonElement>(json))
        } else {
            val resp = client.newCall(GET("$baseUrl/api/v1/catalog")).execute()
            val list = extractArray(resp.bodyString().parseAs<JsonElement>(json))
            resp.close()
            list
        }

        catalogCache = arr
        catalogCacheTime = now
        arr
    }

    // =========================== Helpers ==============================

    private fun SAnime.slug(): String = url.substringAfter("/series/")

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

    private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

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
            genre = o.string("language")?.uppercase() ?: o.string("releaseTime")
            status = SAnime.UNKNOWN
        }
    }

    private fun extractArray(root: JsonElement): List<JsonElement> = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["items"] ?: root["data"] ?: root.values.firstOrNull { it is JsonArray })
            as? JsonArray ?: emptyList()
        else -> emptyList()
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/trending?period=today&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val arr = extractArray(root)
        val hasNext = (root as? JsonObject)
            ?.get("hasNext")?.jsonPrimitive?.booleanOrNull == true
        return AnimesPage(arr.map(::jsonToAnime), hasNext)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/recent")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val items = extractArray(root)
        return AnimesPage(items.map(::recentToAnime), false)
    }

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val dayFilter = filters.filterIsInstance<ScheduleDayFilter>().firstOrNull()
        val day = dayFilter?.getValue()

        return if (!day.isNullOrEmpty()) {
            val url = if (query.isNotBlank()) {
                "$baseUrl/api/v1/schedule?day=$day&q=${URLEncoder.encode(query, "UTF-8")}"
            } else {
                "$baseUrl/api/v1/schedule?day=$day"
            }
            GET(url)
        } else {
            val fragment = if (query.isNotBlank()) URLEncoder.encode(query, "UTF-8") else ""
            GET("$baseUrl/api/v1/catalog#$fragment")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()

        return if (url.contains("/api/v1/schedule")) {
            val root = response.bodyString().parseAs<JsonElement>(json)
            val items = extractArray(root)
            val q = response.request.url.queryParameter("q")?.lowercase().orEmpty()

            val filtered = if (q.isBlank()) {
                items
            } else {
                val catalogArr = getCatalog()

                val matchingSlugs = catalogArr.filter { el ->
                    val o = el.jsonObject
                    o.string("searchTitle")?.lowercase()?.contains(q) == true ||
                        o.string("title")?.lowercase()?.contains(q) == true ||
                        o.string("alternativeTitle")?.lowercase()?.contains(q) == true
                }.mapNotNull { it.jsonObject.string("slug") }.toSet()

                items.filter { el ->
                    val o = el.jsonObject
                    o.string("seriesTitle")?.lowercase()?.contains(q) == true ||
                        o.string("seriesSlug") in matchingSlugs
                }
            }
            AnimesPage(filtered.map(::recentToAnime), false)
        } else {
            val catalogArr = getCatalog(response)
            val q = URLDecoder.decode(response.request.url.fragment ?: "", "UTF-8").lowercase()

            val filtered = if (q.isBlank()) {
                catalogArr
            } else {
                catalogArr.filter { el ->
                    val o = el.jsonObject
                    o.string("searchTitle")?.lowercase()?.contains(q) == true ||
                        o.string("title")?.lowercase()?.contains(q) == true ||
                        o.string("alternativeTitle")?.lowercase()?.contains(q) == true
                }
            }

            AnimesPage(filtered.map(::jsonToAnime), false)
        }
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/series/${anime.slug()}")

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.encodedPath.substringAfter("/series/")

        val o = client.newCall(GET("$baseUrl/api/v1/anime/$slug"))
            .execute().bodyString()
            .parseAs<JsonElement>(json) as? JsonObject
            ?: throw Exception("Invalid anime data")

        val cat = getCatalog().firstOrNull { it.jsonObject.string("slug") == slug }?.jsonObject

        val rating = o.double("rating")
        val synopsis = o.string("synopsis").orEmpty()
        val ratingLine = formatRating(rating)
        val epCount = (o["episodes"] as? JsonArray)?.size ?: 0

        val mainTitle = o.string("title") ?: "Unknown"
        val altTitle = cat?.string("alternativeTitle")?.takeIf { it.isNotEmpty() && it != mainTitle }
        val useAlt = preferences.getBoolean(PREF_USE_ALT_TITLE, PREF_USE_ALT_TITLE_DEFAULT)
        val displayTitle = if (useAlt) altTitle ?: mainTitle else mainTitle

        val genres = cat?.stringArray("genres")?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val studios = cat?.stringArray("studios")?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val premiered = cat?.string("premiered")
        val year = cat?.int("year")?.takeIf { it > 0 }
        val animeType = cat?.string("type") ?: o.string("type")
        val ratingLabel = o.string("ratingLabel")

        val header = listOfNotNull(ratingLine)

        val footerAltLine = if (displayTitle == altTitle) {
            "**Original:** $mainTitle"
        } else {
            altTitle?.let { "**Alt:** $it" }
        }

        val footer = listOfNotNull(
            footerAltLine,
            animeType?.let { "**Type:** $it" },
            premiered?.let { "**Premiered:** $it" },
            year?.let { "**Year:** $it" },
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
        val o = response.bodyString().parseAs<JsonElement>(json) as? JsonObject ?: return emptyList()
        val episodes = o["episodes"] as? JsonArray ?: return emptyList()
        val slug = o.string("slug").orEmpty()

        return episodes
            .mapNotNull { it as? JsonObject }
            .groupBy { it.int("number") }
            .map { (num, epList) ->
                val kinds = epList.mapNotNull { it.string("kind")?.uppercase() }.distinct().sorted().joinToString(", ")
                val payload = buildJsonObject {
                    put("slug", slug)
                    put("ep", num)
                }.toString()
                SEpisode.create().apply {
                    episode_number = num.toFloat()
                    name = "Episode $num"
                    url = base64UrlEncode(payload.toByteArray())
                    scanlator = kinds.ifEmpty { null }
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================== Videos ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/?_d=${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val encoded = response.request.url.queryParameter("_d") ?: return emptyList()
        val payload = String(base64UrlDecode(encoded)).parseAs<JsonElement>(json) as? JsonObject ?: return emptyList()
        val slug = payload.string("slug").orEmpty()
        val epNum = payload.int("ep")

        val freshData = client.newCall(GET("$baseUrl/api/v1/anime/$slug"))
            .execute().bodyString()
            .parseAs<JsonElement>(json) as? JsonObject ?: return emptyList()

        val allEpisodes = freshData["episodes"] as? JsonArray ?: return emptyList()
        val streams = allEpisodes.mapNotNull { it as? JsonObject }.filter { it.int("number") == epNum }
        if (streams.isEmpty()) return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()

        return streams.mapNotNull { ep ->
            val kind = (ep.string("kind") ?: "sub").uppercase()
            val streamUrl = ep.string("stream") ?: return@mapNotNull null

            Video(streamUrl, kind, streamUrl, videoHeaders)
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ScheduleDayFilter(),
    )

    private class ScheduleDayFilter :
        AnimeFilter.Select<String>(
            "Schedule Day",
            arrayOf("None", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        ) {
        private val apiValues = arrayOf("", "mon", "tue", "wed", "thu", "fri", "sat", "sun")
        fun getValue(): String = apiValues[state]
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addSwitchPreference(
            key = PREF_USE_ALT_TITLE,
            title = "Use Alternative Titles",
            summary = "Prefer alternative/English titles over original. Falls back to original.",
            default = PREF_USE_ALT_TITLE_DEFAULT,
        )
    }

    companion object {
        private const val PREF_USE_ALT_TITLE = "use_alt_title"
        private const val PREF_USE_ALT_TITLE_DEFAULT = false
    }
}
