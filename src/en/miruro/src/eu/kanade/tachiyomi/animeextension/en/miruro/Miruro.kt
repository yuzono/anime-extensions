package eu.kanade.tachiyomi.animeextension.en.miruro

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.zip.GZIPInputStream

class Miruro : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Miruro.tv"

    override val baseUrl = "https://www.miruro.tv"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREFIX_SEARCH = "miruro:"

        private val PIPE_KEY = "71951034f8fbcf53d89db52ceb3dc22c"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private const val PREF_PROVIDER_KEY = "preferred_provider"
        private const val PREF_PROVIDER_TITLE = "Preferred Provider"
        private val PREF_PROVIDER_ENTRIES = arrayOf("Kiwi", "Bee")
        private val PREF_PROVIDER_VALUES = arrayOf("kiwi", "bee")
        private const val PREF_PROVIDER_DEFAULT = "kiwi"

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub/Dub"
        private val PREF_SUB_TYPE_ENTRIES = arrayOf("Sub", "Dub")
        private val PREF_SUB_TYPE_VALUES = arrayOf("sub", "dub")
        private const val PREF_SUB_TYPE_DEFAULT = "sub"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_TITLE = "Title Display Style"
        private val PREF_TITLE_STYLE_ENTRIES = arrayOf("User Preferred", "Romaji", "English", "Native")
        private val PREF_TITLE_STYLE_VALUES = arrayOf("userPreferred", "romaji", "english", "native")
        private const val PREF_TITLE_STYLE_DEFAULT = "userPreferred"
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val query = buildJsonObject(
            "type" to "ANIME",
            "page" to page,
            "perPage" to 20,
            "sort" to "POPULARITY_DESC",
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = decryptResponse(response)

        val mediaArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONArray("media") ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until mediaArray.length()).map { i ->
            parseAnimeFromMedia(mediaArray.getJSONObject(i))
        }

        return AnimesPage(animeList, animeList.size >= 20)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val query = buildJsonObject(
            "page" to page,
            "perPage" to 20,
        )
        return buildPipeRequest("schedule", "GET", query = query)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val json = decryptResponse(response)

        val scheduleArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONArray("data") ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until scheduleArray.length()).mapNotNull { i ->
            val entry = scheduleArray.getJSONObject(i)
            val media = entry.optJSONObject("media") ?: entry
            parseAnimeFromMedia(media)
        }

        return AnimesPage(animeList, animeList.size >= 20)
    }

    // ============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val anilistId = query.removePrefix(PREFIX_SEARCH)
            val request = buildPipeRequest("info/$anilistId", "GET")
            val response = client.newCall(request).awaitSuccess()
            val jsonObj = JSONObject(decryptResponse(response))
            response.close()

            val media = jsonObj.optJSONObject("media") ?: jsonObj
            val anime = parseAnimeFromMedia(media)
            return AnimesPage(listOf(anime), false)
        }

        val request = searchAnimeRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            val queryParams = buildJsonObject(
                "query" to query,
                "type" to "ANIME",
                "page" to page,
                "perPage" to 20,
            )
            return buildPipeRequest("search", "GET", query = queryParams)
        }

        val params = MiruroFilters.getSearchParameters(filters)

        val queryParams = buildJsonObject(
            "type" to "ANIME",
            "page" to page,
            "perPage" to 20,
        )

        // Apply filters — only add non-default values
        if (params.sort != "all") queryParams.put("sort", params.sort)
        if (params.season != "all") queryParams.put("season", params.season)
        if (params.year != "all") queryParams.put("year", params.year.toInt())
        if (params.status != "all") queryParams.put("status", params.status)
        if (params.genres.isNotEmpty()) {
            val genresArray = JSONArray()
            params.genres.forEach { genresArray.put(it) }
            queryParams.put("genre", genresArray)
        }
        if (params.formats.isNotEmpty()) {
            val formatsArray = JSONArray()
            params.formats.forEach { formatsArray.put(it) }
            queryParams.put("format", formatsArray)
        }

        return buildPipeRequest("search/browse", "GET", query = queryParams)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val json = decryptResponse(response)

        val mediaArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONArray("media")
                ?: jsonObj.optJSONArray("results")
                ?: jsonObj.optJSONArray("data")
                ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until mediaArray.length()).map { i ->
            parseAnimeFromMedia(mediaArray.getJSONObject(i))
        }

        return AnimesPage(animeList, animeList.size >= 20)
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return buildPipeRequest("info/${anime.url}", "GET")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val jsonObj = JSONObject(decryptResponse(response))
        val media = jsonObj.optJSONObject("media") ?: jsonObj
        val titleObj = media.optJSONObject("title") ?: JSONObject()

        val titleStyle = preferences.getString(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
            ?: PREF_TITLE_STYLE_DEFAULT
        val title = when (titleStyle) {
            "romaji" -> titleObj.optString("romaji", "")
            "english" -> titleObj.optString("english", "")
            "native" -> titleObj.optString("native", "")
            else -> titleObj.optString("userPreferred", titleObj.optString("romaji", ""))
        }

        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))
        val coverUrl = thumbnail.ifEmpty { bannerImage }

        val description = media.optString("description", "")

        val genresArray = media.optJSONArray("genres")
        val genres = if (genresArray != null) {
            (0 until genresArray.length()).mapNotNull { genresArray.optString(it) }.joinToString(", ")
        } else {
            ""
        }

        val statusStr = media.optString("status", "")
        val status = when (statusStr.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        val studio = extractMainStudio(media.opt("studios"))

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = coverUrl
            this.description = description
            genre = genres
            this.status = status
            author = studio
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val query = buildJsonObject(
            "anilistId" to anime.url.toInt(),
        )
        return buildPipeRequest("episodes", "GET", query = query)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonObj = JSONObject(decryptResponse(response))

        val providers = jsonObj.optJSONObject("providers") ?: return emptyList()
        val preferredProvider = preferences.getString(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
            ?: PREF_PROVIDER_DEFAULT
        val preferredSubType = preferences.getString(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)
            ?: PREF_SUB_TYPE_DEFAULT

        val episodes = mutableListOf<SEpisode>()

        val providerData = providers.optJSONObject(preferredProvider)
        if (providerData != null) {
            episodes.addAll(parseEpisodesFromProvider(providerData, preferredProvider, preferredSubType))
        }

        if (episodes.isEmpty()) {
            for (providerKey in providers.keys()) {
                if (providerKey == preferredProvider || providerKey == "hop") continue
                val otherProviderData = providers.getJSONObject(providerKey)
                val otherEpisodes = parseEpisodesFromProvider(otherProviderData, providerKey, preferredSubType)
                if (otherEpisodes.isNotEmpty()) {
                    episodes.addAll(otherEpisodes)
                    break
                }
            }
        }

        return episodes.reversed()
    }

    private fun parseEpisodesFromProvider(
        providerData: JSONObject,
        provider: String,
        preferredSubType: String,
    ): List<SEpisode> {
        val episodesObj = providerData.optJSONObject("episodes") ?: return emptyList()

        val subTypes = when (provider) {
            "kiwi" -> listOf("sub", "dub")
            "bee" -> listOf("ssub", "sub", "dub")
            else -> listOf("sub", "dub")
        }

        val preferredTypeEpisodes = episodesObj.optJSONArray(preferredSubType)
        if (preferredTypeEpisodes != null && preferredTypeEpisodes.length() > 0) {
            return (0 until preferredTypeEpisodes.length()).map { i ->
                parseEpisode(preferredTypeEpisodes.getJSONObject(i), provider, preferredSubType)
            }
        }

        for (subType in subTypes) {
            if (subType == preferredSubType) continue
            val typeEpisodes = episodesObj.optJSONArray(subType)
            if (typeEpisodes != null && typeEpisodes.length() > 0) {
                return (0 until typeEpisodes.length()).map { i ->
                    parseEpisode(typeEpisodes.getJSONObject(i), provider, subType)
                }
            }
        }

        return emptyList()
    }

    private fun parseEpisode(epJson: JSONObject, provider: String, subType: String): SEpisode {
        val id = epJson.optString("id", "")
        val number = epJson.optDouble("number", 0.0)
        val title = epJson.optString("title", "")

        val episodeIdObj = JSONObject().apply {
            put("episodeId", id)
            put("provider", provider)
            put("subType", subType)
        }

        val scanlatorLabel = when (subType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            else -> subType.replaceFirstChar { it.uppercase() }
        }

        return SEpisode.create().apply {
            episode_number = number.toFloat()
            name = if (title.isNotEmpty()) "Episode ${number.toInt()}: $title" else "Episode ${number.toInt()}"
            setUrlWithoutDomain(episodeIdObj.toString())
            scanlator = scanlatorLabel
        }
    }

    // ============================ Video Links ============================

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeData = JSONObject(episode.url)
        val query = buildJsonObject(
            "episodeId" to episodeData.getString("episodeId"),
            "provider" to episodeData.getString("provider"),
            "category" to episodeData.getString("subType"),
        )
        return buildPipeRequest("sources", "GET", query = query)
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonObj = JSONObject(decryptResponse(response))

        val streamsArray = jsonObj.optJSONArray("streams") ?: return emptyList()
        val videos = mutableListOf<Video>()

        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.getJSONObject(i)
            val type = stream.optString("type", "")

            // Only process HLS streams, skip embed type
            if (type != "hls") continue

            val url = stream.optString("url", "")
            if (url.isEmpty()) continue

            val quality = stream.optInt("quality", 0)
            val resolution = stream.optJSONObject("resolution")
            val width = resolution?.optInt("width", 0) ?: 0
            val height = resolution?.optInt("height", 0) ?: 0
            val codec = stream.optString("codec", "")
            val audio = stream.optString("audio", "")
            val fansub = stream.optString("fansub", "")
            val referer = stream.optString("referer", "https://kwik.cx/")

            val qualityLabel = buildString {
                append("${quality}p")
                if (width > 0 && height > 0) append(" - ${width}x$height")
                if (codec.isNotEmpty()) append(" $codec")
                if (audio.isNotEmpty()) append(" $audio")
                if (fansub.isNotEmpty()) append(" $fansub")
            }

            val videoHeaders = Headers.headersOf("Referer", referer)
            videos.add(Video(url, qualityLabel, url, videoHeaders))
        }

        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            ?: PREF_QUALITY_DEFAULT
        val subType = preferences.getString(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)
            ?: PREF_SUB_TYPE_DEFAULT
        val provider = preferences.getString(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
            ?: PREF_PROVIDER_DEFAULT

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(subType, ignoreCase = true) },
                { it.quality.contains(provider, ignoreCase = true) },
            ),
        ).reversed()
    }

    // ============================== URL ==============================

    override fun getAnimeUrl(anime: SAnime): String {
        return "$baseUrl/watch/${anime.url}"
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = MiruroFilters.FILTER_LIST

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = PREF_PROVIDER_TITLE
            entries = PREF_PROVIDER_ENTRIES
            entryValues = PREF_PROVIDER_VALUES
            setDefaultValue(PREF_PROVIDER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_TYPE_KEY
            title = PREF_SUB_TYPE_TITLE
            entries = PREF_SUB_TYPE_ENTRIES
            entryValues = PREF_SUB_TYPE_VALUES
            setDefaultValue(PREF_SUB_TYPE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_STYLE_KEY
            title = PREF_TITLE_STYLE_TITLE
            entries = PREF_TITLE_STYLE_ENTRIES
            entryValues = PREF_TITLE_STYLE_VALUES
            setDefaultValue(PREF_TITLE_STYLE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Helpers ==============================

    private fun buildPipeRequest(
        path: String,
        method: String = "GET",
        query: JSONObject = JSONObject(),
        body: JSONObject = JSONObject(),
    ): Request {
        val payload = JSONObject().apply {
            put("path", path)
            put("method", method)
            put("query", query)
            put("body", if (body.length() == 0) JSONObject.NULL else body)
            put("version", "0.2.0")
        }

        val jsonBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(jsonBytes, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").replace("=", "")

        return GET(
            "$baseUrl/api/secure/pipe?e=$encoded",
            headers = Headers.headersOf(
                "Accept",
                "*/*",
                "Referer",
                "$baseUrl/",
            ),
        )
    }

    private fun buildJsonObject(vararg pairs: Pair<String, Any?>): JSONObject {
        return JSONObject().apply {
            for ((key, value) in pairs) {
                if (value == null) continue
                when (value) {
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Double -> put(key, value)
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
    }

    private fun decryptResponse(response: Response): String {
        val obfuscated = response.header("x-obfuscated") ?: "1"
        val bodyBytes = response.body?.bytes() ?: throw Exception("Empty response body")

        // Base64url decode
        val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
        val padded = bodyStr.replace("-", "+").replace("_", "/")
            .let { if (it.length % 4 != 0) it + "=".repeat(4 - it.length % 4) else it }

        if (obfuscated != "2") {
            return bodyStr
        }

        val decoded = Base64.decode(padded, Base64.NO_WRAP)

        val data = decoded.mapIndexed { i, b ->
            (b.toInt() xor PIPE_KEY[i % PIPE_KEY.size].toInt()).toByte()
        }.toByteArray()

        // Gzip decompress
        GZIPInputStream(java.io.ByteArrayInputStream(data)).use { gzipStream ->
            return gzipStream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun extractCoverImage(coverImage: Any?): String {
        return when (coverImage) {
            is JSONObject -> coverImage.optString("extraLarge", "")
                .ifEmpty { coverImage.optString("large", "") }
                .ifEmpty { coverImage.optString("medium", "") }
            is String -> coverImage
            else -> ""
        }
    }

    private fun extractBannerImage(bannerImage: Any?): String {
        return when (bannerImage) {
            is String -> bannerImage
            else -> ""
        }
    }

    private fun extractMainStudio(studios: Any?): String {
        val edges = when (studios) {
            is JSONObject -> studios.optJSONArray("edges")
            is JSONArray -> studios
            else -> return ""
        } ?: return ""

        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optBoolean("isMain", false)) {
                return edge.optJSONObject("node")?.optString("name", "") ?: ""
            }
        }
        return edges.optJSONObject(0)?.optJSONObject("node")?.optString("name", "") ?: ""
    }

    private fun parseAnimeFromMedia(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val titleStyle = preferences.getString(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
            ?: PREF_TITLE_STYLE_DEFAULT
        val title = when (titleStyle) {
            "romaji" -> titleObj.optString("romaji", "")
            "english" -> titleObj.optString("english", "")
            "native" -> titleObj.optString("native", "")
            else -> titleObj.optString("userPreferred", titleObj.optString("romaji", ""))
        }

        val id = media.optInt("id", 0).toString()
        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = thumbnail.ifEmpty { bannerImage }
            setUrlWithoutDomain(id)
        }
    }
}
