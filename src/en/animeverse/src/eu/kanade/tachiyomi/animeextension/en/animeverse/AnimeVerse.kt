package eu.kanade.tachiyomi.animeextension.en.animeverse

import android.annotation.SuppressLint
import android.util.Base64
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

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

    private fun cleanQuality(q: String): String {
        Regex("""(\d{3,4})p""").find(q)?.let { return it.value }
        Regex("""\dx(\d+)""").find(q)?.groupValues?.get(1)?.let { return "${it}p" }
        return q.substringBefore(" - ").substringBefore("(").trim()
    }

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

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/trending?period=week&page=$page")

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
        val malId = o.int("malId")
        val malLink = if (malId > 0) "[**MAL**](https://myanimelist.net/anime/$malId)" else null

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
            malLink,
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
        val malId = o.int("malId")

        return episodes
            .mapNotNull { it as? JsonObject }
            .groupBy { it.int("number") }
            .map { (num, epList) ->
                val kinds = epList.mapNotNull { it.string("kind")?.uppercase() }.distinct().sorted().joinToString(", ")
                val payload = buildJsonObject {
                    put("slug", slug)
                    put("ep", num)
                    put("malId", malId)
                    put(
                        "items",
                        buildJsonArray {
                            epList.forEach { epObj ->
                                add(
                                    buildJsonObject {
                                        put("id", epObj.string("id").orEmpty())
                                        put("kind", epObj.string("kind") ?: "sub")
                                    },
                                )
                            }
                        },
                    )
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
        val malId = payload.int("malId")
        val items = (payload["items"] as? JsonArray) ?: return emptyList()
        val seenUrls = mutableSetOf<String>()
        val videos = mutableListOf<Video>()
        val hosterExclusion = preferences.getStringSet(PREF_HOSTER_EXCLUDE_KEY, PREF_HOSTER_EXCLUDE_DEFAULT)!!

        for (item in items) {
            val o = item.jsonObject
            val id = o.string("id").orEmpty()
            val kind = (o.string("kind") ?: "sub").uppercase()
            val kindPath = kind.lowercase()

            for (serverName in SERVERS) {
                val serverLabel = SERVERS_DISPLAY[SERVERS.indexOf(serverName)]

                // Skip excluded hosts
                if (hosterExclusion.contains(serverLabel)) continue

                try {
                    // Handle Chiki / MegaPlay directly using malId
                    if (serverName == "chiki" && malId > 0) {
                        val megaplayUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$kindPath"
                        try {
                            val megaplayId = extractMegaplayId(megaplayUrl)

                            if (!megaplayId.isNullOrEmpty()) {
                                val megaplayVideos = fetchMegaplayVideos(megaplayId, megaplayUrl, kind, serverLabel)
                                megaplayVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                            }
                        } catch (_: Exception) {}
                        continue
                    }

                    // Handle AnimeVerse and Choi via API
                    val apiUrl = buildString {
                        append("$baseUrl/api/v1/anime/$slug/stream/$epNum")
                        append("?server=$serverName")
                        if (id.isNotEmpty()) append("&id=$id")
                    }

                    val streamResp = client.newCall(GET(apiUrl)).execute()
                    val streamObjects = when (val streamBody = streamResp.bodyString().parseAs<JsonElement>(json)) {
                        is JsonArray -> streamBody.mapNotNull { it as? JsonObject }
                        is JsonObject -> listOf(streamBody)
                        else -> emptyList()
                    }

                    for (streamObj in streamObjects) {
                        val streamPath = streamObj.string("stream") ?: continue

                        // Handle Choi / Vidnest
                        if (streamPath.contains("vidnest.fun")) {
                            val vidnestVideos = fetchVidnestVideos(streamPath, kind, serverLabel)
                            vidnestVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                        } else if (streamPath.startsWith("/hianime/")) {
                            val vidnestUrl = "https://new.vidnest.fun$streamPath"
                            val vidnestVideos = fetchVidnestVideos(vidnestUrl, kind, serverLabel)
                            vidnestVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                        } else if (streamPath.matches(Regex("^\\d+$")) && serverName.equals("choi", ignoreCase = true)) {
                            val vidnestUrl = "https://new.vidnest.fun/hianime/anime/$streamPath/$epNum/$kindPath"
                            val vidnestVideos = fetchVidnestVideos(vidnestUrl, kind, serverLabel)
                            vidnestVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                        }
                        // Fallback for MegaPlay if AnimeVerse returns a link directly
                        else if (isMegaplayStream(streamPath)) {
                            val fullUrl = if (streamPath.startsWith("http")) {
                                streamPath
                            } else {
                                val host = streamObj.string("host").orEmpty()
                                if (host.isNotEmpty()) "https://$host$streamPath" else "https://megaplay.buzz$streamPath"
                            }

                            var megaplayId = when {
                                fullUrl.contains("id=") -> fullUrl.substringAfter("id=").substringBefore("&").substringBefore("\"").trim()
                                streamPath.matches(Regex("^\\d+$")) -> streamPath
                                else -> ""
                            }

                            if (megaplayId.isEmpty()) {
                                megaplayId = extractMegaplayId(fullUrl) ?: ""
                            }

                            if (megaplayId.isNotEmpty()) {
                                val megaplayVideos = fetchMegaplayVideos(megaplayId, fullUrl, kind, serverLabel)
                                megaplayVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                            }
                        }
                        // Standard AnimeVerse Direct streams (Removed Proxy due to 404)
                        else {
                            val videoHeaders = Headers.Builder()
                                .add("Referer", "$baseUrl/")
                                .add("Origin", baseUrl)
                                .build()

                            val directUrl = when {
                                streamPath.startsWith("/r/") -> {
                                    runCatching { String(base64UrlDecode(streamPath.substringAfter("/r/").substringBefore("."))) }.getOrDefault("")
                                }
                                streamPath.startsWith("http") -> streamPath
                                else -> ""
                            }

                            if (directUrl.startsWith("http")) {
                                val video = Video(directUrl, "$kind - $serverLabel", directUrl, videoHeaders)
                                if (seenUrls.add(video.url)) videos.add(video)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Server might be offline or unavailable, skip it
                }
            }
        }

        return videos
    }

    // ========================= MegaPlay / VidWish ==========================

    private fun extractBaseUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf("/", schemeEnd + 3)
        return if (pathStart > 0) url.substring(0, pathStart) else url
    }

    private fun isMegaplayStream(path: String): Boolean = path.contains("/stream/mal/") ||
        path.contains("/stream/ani/") ||
        path.contains("/stream/s-") ||
        path.contains("megaplay") ||
        path.contains("vidwish")

    private fun extractMegaplayId(pageUrl: String): String? {
        val pageHeaders = Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Referer", "$baseUrl/")
            .add("Sec-Fetch-Dest", "iframe")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "cross-site")
            .build()

        val pageResp = client.newCall(GET(pageUrl, pageHeaders)).execute()
        val pageBody = pageResp.bodyString()

        return Regex("""data-id\s*=\s*["']([^"']+)["']""").find(pageBody)?.groupValues?.get(1)
    }

    private fun extractM3u8FromSources(sources: JsonElement): String? = when (sources) {
        is JsonObject -> sources["file"]?.jsonPrimitive?.contentOrNull
        is JsonArray -> sources.firstOrNull()?.let {
            when (it) {
                is JsonObject -> it["file"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> it.contentOrNull
                else -> null
            }
        }
        is JsonPrimitive -> sources.contentOrNull
        else -> null
    }

    private fun fetchMegaplayVideos(id: String, referer: String, kind: String, serverName: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val base = extractBaseUrl(referer)
            val sourcesUrl = "$base/stream/getSources?id=$id&id=$id"

            val headers = Headers.Builder()
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", referer)
                .add("Origin", base)
                .add("Sec-Fetch-Dest", "empty")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "same-origin")
                .build()

            val resp = client.newCall(GET(sourcesUrl, headers)).execute()
            if (!resp.isSuccessful) return videos

            val obj = resp.bodyString().parseAs<JsonElement>(json) as? JsonObject ?: return videos

            val masterUrl = extractM3u8FromSources(obj["sources"] ?: return videos)
                ?.takeIf { it.startsWith("http") }
                ?: return videos

            val subtitleTracks = (obj["tracks"] as? JsonArray)
                ?.mapNotNull { trackEl ->
                    val trackObj = trackEl as? JsonObject ?: return@mapNotNull null
                    val file = trackObj.string("file") ?: return@mapNotNull null
                    val label = trackObj.string("label") ?: ""
                    val trackKind = trackObj.string("kind") ?: ""
                    if (trackKind.equals("captions", true)) Track(file, label) else null
                } ?: emptyList()

            videos.addAll(
                playlistUtils.extractFromHls(
                    masterUrl,
                    videoNameGen = { q -> "$kind - $serverName - ${cleanQuality(q)}" },
                    subtitleList = subtitleTracks,
                    referer = "$base/",
                ),
            )
        } catch (_: Exception) {
            // Network error, skip this source
        }
        return videos
    }

    // ========================= Vidnest Extractor ==========================

    private val vidnestCustomAlphabet = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/"
    private val standardAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun decryptVidNestData(encryptedData: String): String {
        val translated = encryptedData.map { char ->
            val index = vidnestCustomAlphabet.indexOf(char)
            if (index >= 0) standardAlphabet[index] else char
        }.joinToString("")

        return String(Base64.decode(translated, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun fetchVidnestVideos(url: String, kind: String, serverName: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val headers = Headers.Builder()
                .add("Referer", "https://vidnest.fun/")
                .add("Origin", "https://vidnest.fun")
                .build()

            val resp = client.newCall(GET(url, headers)).execute()
            val body = resp.bodyString()

            try {
                val jsonElement = body.parseAs<JsonElement>(json)
                val sourcesArr = if (jsonElement is JsonObject) {
                    jsonElement["source"] as? JsonArray ?: jsonElement["sources"] as? JsonArray
                } else {
                    null
                }

                if (sourcesArr != null) {
                    for (source in sourcesArr) {
                        val srcObj = source as? JsonObject ?: continue
                        val file = srcObj.string("file") ?: srcObj.string("url") ?: continue
                        val quality = srcObj.string("quality") ?: srcObj.string("label") ?: ""
                        val type = srcObj.string("type") ?: ""
                        val label = buildString {
                            append(kind)
                            append(" - ")
                            append(serverName)
                            if (quality.isNotBlank()) {
                                append(" - ")
                                append(quality)
                            }
                            if (type.equals("hls", ignoreCase = true)) {
                                append(" - HLS")
                            }
                        }
                        videos.add(Video(file, label, file, headers))
                    }
                }
            } catch (_: Exception) {
                if (body.contains("#EXTM3U") || url.contains(".m3u8")) {
                    videos.add(Video(url, "$kind - $serverName - HLS", url, headers))
                } else if (url.contains(".mp4")) {
                    videos.add(Video(url, "$kind - $serverName - MP4", url, headers))
                }
            }
        } catch (_: Exception) {
            // Network error, skip this source
        }
        return videos
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_EXCLUDE_KEY
            title = PREF_HOSTER_EXCLUDE_TITLE
            entries = SERVERS_DISPLAY
            entryValues = SERVERS_DISPLAY
            setDefaultValue(PREF_HOSTER_EXCLUDE_DEFAULT)
            summary = "Choose which hosts you want to exclude"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_USE_ALT_TITLE = "use_alt_title"
        private const val PREF_USE_ALT_TITLE_DEFAULT = false

        private val SERVERS = arrayOf("animeverse", "choi", "chiki")
        private val SERVERS_DISPLAY = arrayOf("AnimeVerse", "Choi", "Chiki")

        private const val PREF_HOSTER_EXCLUDE_KEY = "hoster_exclusion"
        private const val PREF_HOSTER_EXCLUDE_TITLE = "Excluded Hosts"
        private val PREF_HOSTER_EXCLUDE_DEFAULT = emptySet<String>()
    }
}
