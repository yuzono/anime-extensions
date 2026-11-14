package eu.kanade.tachiyomi.animeextension.fr.nakanime

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Nakanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Nakanime"

    override val baseUrl = "https://nakanime.tv"

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val apiHeaders = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Solves Cloudflare challenges via WebView and reuses the clearance cookie. */
    override val client = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!isSafeUrl(request.url.toString())) {
                throw IOException("Blocked unsafe URL: ${request.url}")
            }
            chain.proceed(request)
        }
        .build()

    /** Cache of `animeId -> season numbers`. */
    private val seasonCache = ConcurrentHashMap<String, List<Int>>()

    /** Uses the anime page first, then falls back to the schedule API. */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val fromHtml = runCatching {
            client.newCall(episodeListRequest(anime)).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                episodeListParse(response)
            }
        }.getOrNull()

        if (!fromHtml.isNullOrEmpty()) return fromHtml

        return scheduleEpisodes(anime)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/catalog/search?sort=popularity&page=$page&per_page=32", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response, splitSeasons = true)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/catalog/search?sort=trending&page=$page&per_page=32", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response, splitSeasons = true)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = encode(query)
        val sort = if (query.isBlank()) "trending" else "relevance"
        return GET("$baseUrl/api/catalog/search?q=$encoded&sort=$sort&page=$page&per_page=32", apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response, splitSeasons = true)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        // The HTML page is Cloudflare-protected, so use the catalog API instead.
        val id = animeIdFromUrl(anime.url)
        val seasonQuery = seasonParam(anime.url)?.let { "&_season=$it" }.orEmpty()
        val encoded = encode(anime.title.replace(SEASON_SUFFIX_REGEX, ""))
        return GET("$baseUrl/api/catalog/search?q=$encoded&sort=relevance&page=1&per_page=32&_id=$id$seasonQuery", apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val wantedId = response.request.url.queryParameter("_id")
        val season = response.request.url.queryParameter("_season")?.toIntOrNull()
        val root = JSONObject(response.decrypted())
        val data = root.optJSONArray("data") ?: JSONArray()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            if (item.optString("id") == wantedId) return animeFromJson(item).withSeason(season)
        }
        error("Anime $wantedId not found in catalog results")
    }

    private fun SAnime.withSeason(season: Int?): SAnime = if (season == null) {
        this
    } else {
        apply {
            title = "$title - Saison $season"
            season_number = season.toDouble()
            url = "$url&season=$season"
        }
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val clean = anime.url.substringBefore('?').removePrefix("/")
        return "$baseUrl/anime/$clean"
    }

    // ============================== Episodes ==============================

    /** The anime page embeds all seasons and episodes in `<script id="anime-data">`. */
    override fun episodeListRequest(anime: SAnime): Request {
        val path = anime.url.substringBefore('?').removePrefix(baseUrl).trim().removePrefix("/")
        val season = seasonParam(anime.url)?.let { "?season=$it" }.orEmpty()
        return GET("$baseUrl/anime/$path$season", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.request.url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val seasonFilter = response.request.url.queryParameter("season")?.toIntOrNull()

        val html = response.body.string()
        val raw = ANIME_DATA_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val animeJson = runCatching { JSONObject(raw).optJSONObject("anime") }.getOrNull() ?: return emptyList()

        val seasonNumbers = mutableMapOf<Int, Int>()
        val seasons = animeJson.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seasons.length()) {
            val season = seasons.getJSONObject(i)
            seasonNumbers[season.optInt("id")] = season.optInt("number", 1)
        }

        val now = System.currentTimeMillis()
        val episodes = mutableListOf<RawEpisode>()
        val list = animeJson.optJSONArray("episodesList") ?: return emptyList()

        for (i in 0 until list.length()) {
            val ep = list.getJSONObject(i)
            val epId = ep.optLong("id")
            if (epId == 0L) continue

            val airedAt = parseIsoDate(ep.optString("airedAt"))
            val hasSources = ep.optBoolean("hasSources", false)

            // Hide unaired episodes.
            if (!hasSources && airedAt > now) continue

            val season = seasonNumbers[ep.optInt("seasonId")] ?: 1
            if (seasonFilter != null && season != seasonFilter) continue
            val number = ep.optDouble("number", 0.0)
            // optString returns the literal "null" for a JSON null.
            val title = ep.stringOrNull("titleFr") ?: ep.stringOrNull("title").orEmpty()

            episodes += RawEpisode(
                id = epId,
                season = season,
                number = number,
                title = title,
                airedAt = airedAt,
                languages = ep.optJSONArray("languages").toStringList(),
            )
        }

        return buildEpisodes(animeId, episodes)
    }

    /** Cloudflare-free fallback: the schedule API, limited to 90-day windows. */
    private fun scheduleEpisodes(anime: SAnime): List<SEpisode> {
        val animeId = animeIdFromUrl(anime.url)
        if (animeId.isEmpty()) return emptyList()
        val animeIdInt = animeId.toIntOrNull() ?: return emptyList()

        val params = anime.url.substringAfter('?', "")
            .split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val today = LocalDate.now(ZoneOffset.UTC)
        val year = params["y"]?.toIntOrNull()?.takeIf { it in 1950..today.year } ?: (today.year - 10)
        val startMonth = when (params["s"].orEmpty().uppercase(Locale.ROOT)) {
            "WINTER" -> 1
            "SPRING" -> 4
            "SUMMER" -> 7
            "FALL", "AUTUMN" -> 10
            else -> 1
        }
        val expected = params["c"]?.toIntOrNull() ?: 0
        val seasonFilter = seasonParam(anime.url)

        val maxWindows = minOf(
            MAX_SCHEDULE_WINDOWS,
            ((today.year - year + 1) * 4 + 8).coerceAtLeast(8),
        )

        val episodes = LinkedHashMap<Long, RawEpisode>()
        var from = LocalDate.of(year, startMonth, 1)
        var windows = 0

        while (windows < maxWindows && !from.isAfter(today)) {
            val to = minOf(from.plusDays(90), today.plusDays(1))
            val body = runCatching { apiGet("/api/schedule?from=$from&to=$to") }
                .getOrElse { if (windows == 0) throw it else break }

            collectSchedule(body, animeId, episodes)
            if (expected > 0 && episodes.size >= expected) break

            from = to.plusDays(1)
            windows++
        }

        return buildEpisodes(
            animeIdInt,
            episodes.values.filter { seasonFilter == null || it.season == seasonFilter },
        )
    }

    private fun collectSchedule(body: String, animeId: String, out: MutableMap<Long, RawEpisode>) {
        val startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val days = runCatching { JSONObject(body).optJSONArray("days") }.getOrNull() ?: return

        for (d in 0 until days.length()) {
            val day = days.getJSONObject(d)
            val dayMillis = parseDay(day.optString("date"))
            // The schedule only exposes a date: skip today to avoid listing episodes
            // that have not aired yet. The HTML path uses airedAt/hasSources instead.
            if (dayMillis >= startOfToday) continue

            val eps = day.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val ep = eps.getJSONObject(i)
                val anime = ep.optJSONObject("anime") ?: continue
                if (anime.optString("id") != animeId) continue
                if (anime.optBoolean("isUpcoming", false)) continue

                val epId = ep.optLong("id")
                if (epId == 0L || out.containsKey(epId)) continue

                out[epId] = RawEpisode(
                    id = epId,
                    season = ep.optInt("seasonNumber", 1),
                    number = ep.optDouble("number", 0.0),
                    title = ep.stringOrNull("title").orEmpty(),
                    airedAt = dayMillis,
                    languages = ep.optJSONArray("languages").toStringList(),
                )
            }
        }
    }

    private fun buildEpisodes(animeId: Int, episodes: Collection<RawEpisode>): List<SEpisode> {
        val ordered = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        val mergeSeasons = preferences.getBoolean("merge_seasons", true)

        val result = if (mergeSeasons) {
            // Continuous numbering across all seasons.
            ordered.mapIndexed { index, raw ->
                raw.toEpisode(animeId, (index + 1).toFloat(), index + 1, showSeason = true)
            }
        } else if (ordered.map { it.season }.distinct().size > 1) {
            // The entry was not split by season (e.g. split lookup failed): keep
            // seasons distinguishable and avoid duplicate episode numbers.
            ordered.mapIndexed { index, raw ->
                raw.toEpisode(animeId, (index + 1).toFloat(), index + 1, showSeason = true)
            }
        } else {
            // No season offset: Aniyomi would report thousands of missing episodes.
            ordered.map { raw ->
                raw.toEpisode(animeId, raw.number.toFloat(), raw.number.toInt(), showSeason = false)
            }
        }

        // Newest first.
        return result.reversed()
    }

    private fun RawEpisode.toEpisode(
        animeId: Int,
        episodeNumber: Float,
        displayNumber: Int,
        showSeason: Boolean,
    ): SEpisode = SEpisode.create().apply {
        url = "$animeId/$id/$season/${number.toInt()}"
        this.episode_number = episodeNumber
        date_upload = airedAt
        // Languages are displayed under the episode name by Aniyomi.
        scanlator = languages.joinToString(", ").ifBlank { null }
        name = buildString {
            if (showSeason) {
                append("Saison ")
                append(season)
                append(" · ")
            }
            append("Épisode ")
            append(displayNumber)
            appendTitle(title)
        }
    }

    private fun StringBuilder.appendTitle(title: String) {
        if (title.isNotEmpty() && !EPISODE_TITLE_REGEX.matches(title)) {
            append(" - ")
            append(title)
        }
    }

    /** Reject non-HTTPS and local/private hosts returned by the API. */
    private fun isSafeUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.port != 443) return false

        val host = parsed.host
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host.contains(':')) return false
        if (!host.matches(IPV4_REGEX)) return true

        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        val first = parts[0]
        val second = parts[1]
        return !(
            first == 0 || first == 10 || first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                first >= 224
            )
    }

    private fun apiGet(path: String): String = network.client.newCall(GET(baseUrl + path, apiHeaders)).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        response.decrypted()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split('/')
        val animeId = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val episodeId = parts.getOrNull(1)?.toLongOrNull() ?: 0L

        val payload = JSONObject().apply {
            put("title", episode.name)
            put("anime_id", animeId)
            put("turnstile_token", "")
            put("episode_id", episodeId)
        }

        val request = POST(
            "$baseUrl/api/sources/anime",
            apiHeaders,
            payload.toString().toRequestBody(jsonMediaType),
        )

        val servers = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val sources = JSONArray(response.decrypted())
            (0 until sources.length()).map { sources.getJSONObject(it) }
        }

        val langPref = preferences.getString("preferred_language", "VOSTFR") ?: "VOSTFR"
        val matching = servers.filter {
            langPref == "all" || it.optString("language").equals(langPref, ignoreCase = true)
        }

        return matching.ifEmpty { servers }.mapNotNull { server ->
            val host = server.optString("host").ifBlank { "source" }
            val language = server.optString("language")
            val url = server.optString("url")
            if (!isSafeUrl(url)) return@mapNotNull null
            Hoster(
                hosterName = "[$language] $host",
                hosterUrl = url,
                internalData = host,
            )
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val extractor = preferences.getString(PREF_EXTRACTOR_KEY, PREF_EXTRACTOR_DEFAULT) ?: PREF_EXTRACTOR_DEFAULT
        if (extractor == PREF_EXTRACTOR_DEFAULT) return this

        val wanted = extractor.replace(".", "").lowercase(Locale.ROOT)
        return sortedWith(
            compareByDescending { it.internalData.replace(".", "").lowercase(Locale.ROOT) == wanted },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = when (hoster.internalData.lowercase(Locale.ROOT)) {
        "vidmoly" -> VidMolyExtractor(client, headers).videosFromUrl(hoster.hosterUrl, hoster.hosterName)
        "voe" -> VoeExtractor(client, headers).videosFromUrl(hoster.hosterUrl, hoster.hosterName)
        "ok.ru", "okru" -> OkruExtractor(client).videosFromUrl(hoster.hosterUrl, hoster.hosterName)
        else -> emptyList()
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    private fun Response.decrypted(): String {
        val url = request.url
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return decrypt(body.bytes(), path)
    }

    private fun decrypt(data: ByteArray, path: String): String {
        val key = keyStream(path)
        val output = ByteArray(data.size)
        for (i in data.indices) {
            output[i] = (data[i].toInt() xor key[i % KEY_SIZE]).toByte()
        }
        return String(output, Charsets.UTF_8)
    }

    /** API responses are XOR-encrypted with a key derived from the request path. */
    private fun keyStream(path: String): IntArray {
        val seed = SALT + path
        return IntArray(KEY_SIZE) { offset ->
            var value = 0
            for (char in seed) {
                value = (value * 31 + char.code + offset) and 0xFF
            }
            value
        }
    }

    private fun parseAnimesPage(response: Response, splitSeasons: Boolean = false): AnimesPage {
        val root = JSONObject(response.decrypted())
        val data = root.optJSONArray("data") ?: JSONArray()

        var animes = (0 until data.length()).map { animeFromJson(data.getJSONObject(it)) }

        // The site groups seasons under one anime, so split it when needed.
        if (splitSeasons && !preferences.getBoolean("merge_seasons", true)) {
            animes = animes.splitBySeason()
        }

        val meta = root.optJSONObject("meta")
        val page = meta?.optInt("page", 1) ?: 1
        val lastPage = meta?.optInt("total_pages", page) ?: page

        return AnimesPage(animes, page < lastPage)
    }

    /** One entry per season for catalog results when season merging is disabled. */
    private fun List<SAnime>.splitBySeason(): List<SAnime> = chunked(SEASON_LOOKUP_CONCURRENCY).flatMap { chunk ->
        chunk.parallelCatchingFlatMapBlocking { anime ->
            val seasons = runCatching { fetchSeasonNumbers(anime) }
                .onFailure { Log.e("Nakanime", "Season lookup failed for ${anime.title}", it) }
                .getOrDefault(emptyList())
            if (seasons.size <= 1) listOf(anime) else seasons.map { anime.asSeason(it) }
        }
    }

    private fun fetchSeasonNumbers(anime: SAnime): List<Int> {
        val id = animeIdFromUrl(anime.url)
        seasonCache[id]?.let { return it }

        val path = anime.url.substringBefore('?').removePrefix(baseUrl).trim().removePrefix("/")
        val html = client.newCall(GET("$baseUrl/anime/$path", headers)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body.string()
        }

        val raw = ANIME_DATA_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val seasons = runCatching { JSONObject(raw).optJSONObject("anime")?.optJSONArray("seasons") }
            .getOrNull() ?: return emptyList()

        val numbers = (0 until seasons.length())
            .mapNotNull { i -> seasons.optJSONObject(i)?.optInt("number")?.takeIf { it > 0 } }
            .distinct()
            .sorted()

        if (numbers.isNotEmpty()) seasonCache[id] = numbers
        return numbers
    }

    private fun SAnime.asSeason(number: Int): SAnime = SAnime.create().apply {
        val source = this@asSeason
        url = "${source.url}&season=$number"
        title = "${source.title} - Saison $number"
        thumbnail_url = source.thumbnail_url
        description = source.description
        genre = source.genre
        status = source.status
        season_number = number.toDouble()
    }

    private fun animeFromJson(o: JSONObject): SAnime = SAnime.create().apply {
        val animeId = o.optString("id")
        val slug = o.optString("slug")
        val year = o.optInt("season_year", 0)
        val season = o.optString("season")
        val count = o.optInt("episode_count", 0)

        url = "/$animeId/$slug?y=$year&s=$season&c=$count"
        title = o.stringOrNull("title").orEmpty()
        thumbnail_url = o.stringOrNull("poster_url") ?: o.stringOrNull("backdrop_url") ?: ""
        description = o.stringOrNull("overview").orEmpty()
        genre = o.optJSONArray("genres").toStringList().joinToString(", ")

        status = when (o.optString("status").uppercase(Locale.ROOT)) {
            "FINISHED", "ENDED" -> SAnime.COMPLETED
            "RELEASING", "ONGOING" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun animeIdFromUrl(url: String): String = url.substringBefore('?').trim().removePrefix("/").substringBefore('/')

    private fun seasonParam(url: String): Int? = url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("season=") }
        ?.substringAfter('=')
        ?.toIntOrNull()

    private fun parseIsoDate(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun parseDay(value: String): Long = runCatching {
        LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** `optString` returns the literal "null" for JSON nulls; filter them out. */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optString(i).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Langue préférée"
            entries = arrayOf("VOSTFR", "VF", "Toutes")
            entryValues = arrayOf("VOSTFR", "VF", "all")
            setDefaultValue("VOSTFR")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "merge_seasons"
            title = "Fusionner les saisons"
            summary = "Coché : un seul résultat par anime et numérotation continue. Décoché : un résultat et une fiche par saison (Saison 1, Saison 2...)"
            setDefaultValue(true)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_EXTRACTOR_KEY
            title = "Extracteur préféré"
            entries = EXTRACTORS
            entryValues = EXTRACTOR_VALUES
            setDefaultValue(PREF_EXTRACTOR_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private data class RawEpisode(
        val id: Long,
        val season: Int,
        val number: Double,
        val title: String,
        val airedAt: Long,
        val languages: List<String>,
    )

    companion object {
        private const val SALT = "nkapiv1"
        private const val KEY_SIZE = 32
        private const val MAX_SCHEDULE_WINDOWS = 160
        private const val SEASON_LOOKUP_CONCURRENCY = 8

        private const val PREF_EXTRACTOR_KEY = "preferred_extractor"
        private const val PREF_EXTRACTOR_DEFAULT = "auto"
        private val EXTRACTORS = arrayOf("Automatique", "Voe", "VidMoly", "Ok.ru")
        private val EXTRACTOR_VALUES = arrayOf("auto", "voe", "vidmoly", "ok.ru")

        private val ANIME_DATA_REGEX = Regex("""<script[^>]*id="anime-data"[^>]*>([\s\S]*?)</script>""")
        private val EPISODE_TITLE_REGEX = Regex("(?i)^(épisode|episode)\\s*\\d+$")
        private val SEASON_SUFFIX_REGEX = Regex("""\s+-\s+Saison\s+\d+$""")
        private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
