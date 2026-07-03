package eu.kanade.tachiyomi.animeextension.en.animeverse

import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
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
import keiyoushi.utils.applicationContext
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File

class AnimeVerse :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeVerse"
    override val baseUrl = "https://animeverse.to"
    override val lang = "en"
    override val supportsLatest = true
    override val disableRelatedAnimesBySearch = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences = getPreferences()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val useAltTitle: Boolean
        get() = preferences.getBoolean(PREF_USE_ALT_TITLE, PREF_USE_ALT_TITLE_DEFAULT)

    private val fingerprint: String by lazy {
        preferences.getString("fp_json", null) ?: run {
            val ua = System.getProperty("http.agent")
                ?.replace("\"", "\\\"")
                ?.replace("\\", "\\\\")
                ?: "Mozilla/5.0"
            """{"ua":"$ua","language":"en-US","timezone":"UTC","hw":8,"screen":"1920x1080x24","canvas":"kW9_MAWuv_3eBlyA7DxVWY","webgl":"Google Inc. (NVIDIA)|ANGLE (NVIDIA, GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)"}"""
        }.also { preferences.edit().putString("fp_json", it).apply() }
    }

    // ======================= Client + Interceptor =========================

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain -> authInterceptor(chain, network.client, fingerprint, baseUrl) }
            .build()
    }

    // ========================= Catalog & Schedule Cache ==========================

    private val cacheTtl = 24 * 60 * 60 * 1000L // 24 hours
    private val cacheLock = Any()

    @Volatile
    private var catalogCache: List<JsonElement>? = null
    private var catalogCacheLoadedTime: Long = 0L
    private val catalogCacheFile: File by lazy {
        applicationContext.cacheDir.resolve("source_$id/catalog.json")
    }

    private fun getCatalog(): List<JsonElement> = synchronized(cacheLock) {
        val now = System.currentTimeMillis()
        val forceRefresh = preferences.getBoolean(PREF_FORCE_REFRESH_CACHE, false)

        if (forceRefresh) {
            catalogCache = null
            catalogCacheFile.delete()
            preferences.edit().putBoolean(PREF_FORCE_REFRESH_CACHE, false).apply()
        }

        catalogCache?.takeIf { now - catalogCacheLoadedTime < cacheTtl }?.let { return it }

        if (catalogCacheFile.exists()) {
            val lastMod = catalogCacheFile.lastModified()
            if (now - lastMod < cacheTtl) {
                runCatching {
                    val fileArr = extractArray(catalogCacheFile.readText().parseAs<JsonElement>(json))
                    if (fileArr.isNotEmpty()) {
                        catalogCache = fileArr
                        catalogCacheLoadedTime = lastMod
                        return fileArr
                    }
                }
            }
        }

        try {
            val (body, arr) = client.newCall(GET("$baseUrl/api/v1/catalog")).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.bodyString()
                body to extractArray(body.parseAs<JsonElement>(json))
            }

            if (arr.isNotEmpty()) {
                saveJsonToFile(catalogCacheFile, body, arr)
                catalogCache = arr
                catalogCacheLoadedTime = now
                return arr
            }
        } catch (e: Exception) {
            if (catalogCacheFile.exists()) {
                runCatching {
                    val fileArr = extractArray(catalogCacheFile.readText().parseAs<JsonElement>(json))
                    if (fileArr.isNotEmpty()) {
                        catalogCache = fileArr
                        catalogCacheLoadedTime = catalogCacheFile.lastModified()
                        return fileArr
                    }
                }
            }
            throw Exception("Failed to fetch catalog: ${e.message}")
        }

        return emptyList()
    }

    private fun getSchedule(day: String): List<JsonElement> = synchronized(cacheLock) {
        val scheduleFile = applicationContext.cacheDir.resolve("source_$id/schedule_$day.json")
        val now = System.currentTimeMillis()

        if (scheduleFile.exists() && now - scheduleFile.lastModified() < cacheTtl) {
            runCatching {
                val fileArr = extractArray(scheduleFile.readText().parseAs<JsonElement>(json))
                if (fileArr.isNotEmpty()) return fileArr
            }
        }

        try {
            val (body, arr) = client.newCall(GET("$baseUrl/api/v1/schedule?day=$day")).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.bodyString()
                body to extractArray(body.parseAs<JsonElement>(json))
            }

            if (arr.isNotEmpty()) {
                saveJsonToFile(scheduleFile, body, arr)
                return arr
            }
        } catch (_: Exception) {
            if (scheduleFile.exists()) {
                runCatching {
                    val fileArr = extractArray(scheduleFile.readText().parseAs<JsonElement>(json))
                    if (fileArr.isNotEmpty()) return fileArr
                }
            }
        }
        emptyList()
    }

    private fun saveJsonToFile(file: File, body: String, list: List<JsonElement>) {
        if (list.isEmpty()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(body)
        } catch (_: Exception) {}
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/trending?period=week&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val arr = extractArray(root)
        val hasNext = (root as? JsonObject)?.get("hasNext")?.jsonPrimitive?.booleanOrNull == true

        val catalogArr = getCatalog()

        val animes = arr.map { el ->
            val o = el.jsonObject
            val id = o.string("id")
            val slug = o.string("slug")

            val catEl = catalogArr.firstOrNull {
                val catO = it.jsonObject
                (id != null && catO.string("id") == id) || (slug != null && catO.string("slug") == slug)
            } ?: el

            jsonToAnime(catEl, useAltTitle, baseUrl)
        }

        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/recent")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val root = response.bodyString().parseAs<JsonElement>(json)
        val items = extractArray(root)
        val catalogArr = getCatalog()

        val animes = items.map { el ->
            val o = el.jsonObject
            val seriesSlug = o.string("seriesSlug")

            val catEl = seriesSlug?.let { slug ->
                catalogArr.firstOrNull { it.jsonObject.string("slug") == slug }
            }

            if (catEl != null) {
                val sAnime = jsonToAnime(catEl, useAltTitle, baseUrl)
                val recentInfo = o.string("language")?.uppercase() ?: o.string("releaseTime")
                if (!recentInfo.isNullOrEmpty()) {
                    sAnime.genre = if (!sAnime.genre.isNullOrEmpty()) "${sAnime.genre}, $recentInfo" else recentInfo
                }
                sAnime
            } else {
                recentToAnime(el, baseUrl)
            }
        }

        return AnimesPage(animes, false)
    }

    // ============================== Search ==============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val dayFilter = filters.filterIsInstance<ScheduleDayFilter>().firstOrNull()
        val day = dayFilter?.getValue()
        val q = query.lowercase()

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val genreMode = filters.filterIsInstance<GenreModeFilter>().firstOrNull()?.isAnd() ?: false
        val includedGenres = genreFilter?.state?.filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }?.map { it.name }?.toSet() ?: emptySet()
        val excludedGenres = genreFilter?.state?.filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }?.map { it.name }?.toSet() ?: emptySet()

        val studioFilter = filters.filterIsInstance<StudioFilter>().firstOrNull()
        val selectedStudios = studioFilter?.state?.filter { it.state }?.map { it.name }?.toSet() ?: emptySet()
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.getValue() ?: ""
        val season = filters.filterIsInstance<SeasonFilter>().firstOrNull()?.getValue() ?: ""
        val ratingLabel = filters.filterIsInstance<RatingLabelFilter>().firstOrNull()?.getValue() ?: ""

        val isFilterApplied = q.isNotBlank() || includedGenres.isNotEmpty() || excludedGenres.isNotEmpty() ||
            selectedStudios.isNotEmpty() || year.isNotEmpty() || season.isNotEmpty() || ratingLabel.isNotEmpty()

        return if (!day.isNullOrEmpty()) {
            val scheduleArr = getSchedule(day)
            val catalogArr = getCatalog()

            val filteredSchedule = if (!isFilterApplied) {
                scheduleArr
            } else {
                val matchingSlugs = catalogArr.filter { el ->
                    val o = el.jsonObject
                    val matchesQuery = q.isBlank() || o.string("searchTitle")?.lowercase()?.contains(q) == true ||
                        o.string("title")?.lowercase()?.contains(q) == true ||
                        o.string("alternativeTitle")?.lowercase()?.contains(q) == true
                    val genres = o.stringArray("genres")
                    val matchesGenre = if (genreMode) {
                        includedGenres.all { it in genres } &&
                            (excludedGenres.isEmpty() || genres.intersect(excludedGenres).isEmpty())
                    } else {
                        (includedGenres.isEmpty() || genres.intersect(includedGenres).isNotEmpty()) &&
                            (excludedGenres.isEmpty() || genres.intersect(excludedGenres).isEmpty())
                    }
                    val studios = o.stringArray("studios")
                    val matchesStudio = selectedStudios.isEmpty() || studios.intersect(selectedStudios).isNotEmpty()
                    val matchesYear = year.isEmpty() || o.int("year").toString() == year
                    val matchesSeason = season.isEmpty() || o.string("premiered")?.startsWith(season, ignoreCase = true) == true
                    val matchesRating = ratingLabel.isEmpty() || o.string("ratingLabel") == ratingLabel

                    matchesQuery && matchesGenre && matchesStudio && matchesYear && matchesSeason && matchesRating
                }.mapNotNull { it.jsonObject.string("slug") }.toSet()

                scheduleArr.filter { el ->
                    el.jsonObject.string("seriesSlug") in matchingSlugs
                }
            }

            val animes = filteredSchedule.map { el ->
                val o = el.jsonObject
                val seriesSlug = o.string("seriesSlug")

                val catEl = seriesSlug?.let { slug ->
                    catalogArr.firstOrNull { it.jsonObject.string("slug") == slug }
                }

                if (catEl != null) {
                    val sAnime = jsonToAnime(catEl, useAltTitle, baseUrl)
                    val recentInfo = o.string("language")?.uppercase() ?: o.string("releaseTime")
                    if (!recentInfo.isNullOrEmpty()) {
                        sAnime.genre = if (!sAnime.genre.isNullOrEmpty()) "${sAnime.genre}, $recentInfo" else recentInfo
                    }
                    sAnime
                } else {
                    recentToAnime(el, baseUrl)
                }
            }

            AnimesPage(animes, false)
        } else {
            val catalogArr = getCatalog()

            val filtered = catalogArr.filter { el ->
                val o = el.jsonObject
                val matchesQuery = q.isBlank() || o.string("searchTitle")?.lowercase()?.contains(q) == true ||
                    o.string("title")?.lowercase()?.contains(q) == true ||
                    o.string("alternativeTitle")?.lowercase()?.contains(q) == true
                val genres = o.stringArray("genres")
                val matchesGenre = if (genreMode) {
                    includedGenres.all { it in genres } &&
                        (excludedGenres.isEmpty() || genres.intersect(excludedGenres).isEmpty())
                } else {
                    (includedGenres.isEmpty() || genres.intersect(includedGenres).isNotEmpty()) &&
                        (excludedGenres.isEmpty() || genres.intersect(excludedGenres).isEmpty())
                }
                val studios = o.stringArray("studios")
                val matchesStudio = selectedStudios.isEmpty() || studios.intersect(selectedStudios).isNotEmpty()
                val matchesYear = year.isEmpty() || o.int("year").toString() == year
                val matchesSeason = season.isEmpty() || o.string("premiered")?.startsWith(season, ignoreCase = true) == true
                val matchesRating = ratingLabel.isEmpty() || o.string("ratingLabel") == ratingLabel

                matchesQuery && matchesGenre && matchesStudio && matchesYear && matchesSeason && matchesRating
            }

            val sorted = if (q.isBlank()) {
                filtered.sortedByDescending { it.jsonObject.double("rating") }
            } else {
                filtered
            }

            AnimesPage(sorted.map { jsonToAnime(it, useAltTitle, baseUrl) }, false)
        }
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val keywords = anime.title.stripKeywordForRelatedAnimes()
        if (keywords.isEmpty()) return emptyList()

        val catalog = getCatalog()
        val currentSlug = anime.slug()
        val currentGenres = anime.genre?.split(", ")?.map { it.trim() }?.toSet() ?: emptySet()

        return catalog.asSequence()
            .map { it.jsonObject }
            .filter { it.string("slug") != currentSlug }
            .map { o ->
                val searchTitle = if (useAltTitle) {
                    o.string("alternativeTitle")?.takeIf { it.isNotEmpty() } ?: o.string("title")
                } else {
                    o.string("title")
                }?.lowercase().orEmpty()

                val titleMatchScore = keywords.count { searchTitle.contains(it) }

                val genres = o.stringArray("genres")
                val sharedGenres = if (currentGenres.isNotEmpty()) genres.intersect(currentGenres).size else 0

                Triple(o, titleMatchScore, sharedGenres)
            }
            .filter { (_, titleMatchScore, sharedGenres) -> titleMatchScore >= 2 || sharedGenres >= 1 }
            .sortedWith(
                compareByDescending<Triple<JsonObject, Int, Int>> { it.second }
                    .thenByDescending { it.third },
            )
            .take(24)
            .map { jsonToAnime(it.first, useAltTitle, baseUrl) }
            .toList()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/series/${anime.slug()}")

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.encodedPath.substringAfter("/series/")

        val o = client.newCall(GET("$baseUrl/api/v1/anime/$slug")).execute().use { res ->
            res.bodyString().parseAs<JsonElement>(json) as? JsonObject
        } ?: throw Exception("Invalid anime data")

        val networkId = o.string("id")
        val cat = if (!networkId.isNullOrEmpty()) {
            getCatalog().firstOrNull { it.jsonObject.string("id") == networkId }?.jsonObject
        } else {
            getCatalog().firstOrNull { it.jsonObject.string("slug") == slug }?.jsonObject
        }

        val rating = o.double("rating")
        val synopsis = o.string("synopsis").orEmpty()
        val ratingLine = formatRating(rating)
        val epCount = (o["episodes"] as? JsonArray)?.size ?: 0

        val mainTitle = cat?.string("title")?.takeIf { it.isNotEmpty() } ?: o.string("title") ?: "Unknown"
        val altTitle = cat?.string("alternativeTitle")?.takeIf { it.isNotEmpty() && it != mainTitle }
            ?: o.string("alternativeTitle")?.takeIf { it.isNotEmpty() && it != mainTitle }

        val displayTitle = if (useAltTitle) altTitle ?: mainTitle else mainTitle

        val genres = cat?.stringArray("genres")?.takeIf { it.isNotEmpty() }?.joinToString()
        val studios = cat?.stringArray("studios")?.takeIf { it.isNotEmpty() }?.joinToString()
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
            thumbnail_url = resolveImage(baseUrl, o.string("cover") ?: o.string("thumb"))
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
                val kinds = epList.mapNotNull { it.string("kind")?.uppercase() }.distinct().sorted().joinToString()
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

                if (hosterExclusion.contains(serverLabel)) continue

                try {
                    // Handle Chiki / MegaPlay directly using malId
                    if (serverName == "chiki" && malId > 0) {
                        val megaplayUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$kindPath"
                        try {
                            val megaplayId = extractMegaplayId(client, megaplayUrl, baseUrl)

                            if (!megaplayId.isNullOrEmpty()) {
                                val megaplayVideos = fetchMegaplayVideos(client, playlistUtils, megaplayId, megaplayUrl, kind, serverLabel)
                                megaplayVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                            }
                        } catch (_: Exception) {}
                        continue
                    }

                    // Handle AnimeVerse via API
                    val apiUrl = buildString {
                        append("$baseUrl/api/v1/anime/$slug/stream/$epNum")
                        append("?server=$serverName")
                        if (id.isNotEmpty()) append("&id=$id")
                    }

                    val streamObjects = client.newCall(GET(apiUrl)).execute().use { res ->
                        when (val streamBody = res.bodyString().parseAs<JsonElement>(json)) {
                            is JsonArray -> streamBody.mapNotNull { it as? JsonObject }
                            is JsonObject -> listOf(streamBody)
                            else -> emptyList()
                        }
                    }

                    for (streamObj in streamObjects) {
                        val streamPath = streamObj.string("stream") ?: continue

                        // Fallback for MegaPlay if AnimeVerse returns a link directly
                        if (isMegaplayStream(streamPath)) {
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
                                megaplayId = extractMegaplayId(client, fullUrl, baseUrl) ?: ""
                            }

                            if (megaplayId.isNotEmpty()) {
                                val megaplayVideos = fetchMegaplayVideos(client, playlistUtils, megaplayId, fullUrl, kind, serverLabel)
                                megaplayVideos.filter { seenUrls.add(it.url) }.also { videos.addAll(it) }
                            }
                        }
                        // Standard Direct streams (Only process if it's a direct http/https link)
                        else if (streamPath.startsWith("http")) {
                            val videoHeaders = Headers.Builder()
                                .add("Referer", "$baseUrl/")
                                .add("Origin", baseUrl)
                                .build()

                            val video = Video(streamPath, "$kind - $serverLabel", streamPath, videoHeaders)
                            if (seenUrls.add(video.url)) videos.add(video)
                        }
                    }
                } catch (_: Exception) {
                    // Server might be offline or unavailable, skip it
                }
            }
        }

        return videos
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList {
        val genres = mutableSetOf<String>()
        val studios = mutableSetOf<String>()
        val years = mutableSetOf<String>()
        val ratingLabels = mutableSetOf<String>()
        val seasons = mutableSetOf<String>()

        if (catalogCacheFile.exists()) {
            runCatching {
                val catalogArr = extractArray(catalogCacheFile.readText().parseAs<JsonElement>(json))
                catalogArr.forEach { el ->
                    val o = el.jsonObject
                    genres.addAll(o.stringArray("genres"))
                    studios.addAll(o.stringArray("studios"))
                    o.int("year").takeIf { it > 0 }?.let { years.add(it.toString()) }
                    o.string("ratingLabel")?.takeIf { it.isNotEmpty() }?.let { ratingLabels.add(it) }
                    o.string("premiered")?.substringBefore(" ")?.takeIf { it.isNotEmpty() }?.let { seasons.add(it) }
                }
            }
        }

        val filters = mutableListOf<AnimeFilter<*>>(
            ScheduleDayFilter(),
        )

        if (genres.isNotEmpty()) {
            filters.add(GenreModeFilter())
            filters.add(GenreFilter(genres.sortedWith(String.CASE_INSENSITIVE_ORDER)))
            filters.add(StudioFilter(studios.sortedWith(String.CASE_INSENSITIVE_ORDER)))
            filters.add(SeasonFilter(seasons.sortWithNA()))
            filters.add(YearFilter(years.sortedDescending()))
            filters.add(RatingLabelFilter(ratingLabels.sortRatingLabels()))
        } else {
            filters.add(AnimeFilter.Header("Press 'Reset' to load filters"))
            filters.add(AnimeFilter.Separator())
        }

        return AnimeFilterList(filters)
    }

    private fun Iterable<String>.sortWithNA(): List<String> = this.filter { !it.equals("N/A", true) }

    private fun Iterable<String>.sortRatingLabels(): List<String> {
        val order = listOf("G", "PG", "PG-13", "R - 17+", "R+", "Rx", "Safe to watch")
        return this.sortedWith(
            compareBy<String> { label ->
                val index = order.indexOfFirst { label.contains(it, ignoreCase = true) }
                if (index == -1) Int.MAX_VALUE else index
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it },
        )
    }

    private class ScheduleDayFilter :
        AnimeFilter.Select<String>(
            "Schedule Day",
            arrayOf("None", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        ) {
        private val apiValues = arrayOf("", "mon", "tue", "wed", "thu", "fri", "sat", "sun")
        fun getValue(): String = apiValues[state]
    }

    private class GenreFilter(values: List<String>) : AnimeFilter.Group<GenreTriState>("Genres", values.map { GenreTriState(it) })

    private class GenreTriState(name: String) : AnimeFilter.TriState(name)

    private class GenreModeFilter : AnimeFilter.Select<String>("Genre Mode", arrayOf("OR", "AND"), 0) {
        fun isAnd(): Boolean = state == 1
    }

    private class StudioFilter(values: List<String>) : AnimeFilter.Group<StudioCheckBox>("Studios", values.map { StudioCheckBox(it, false) })
    private class StudioCheckBox(name: String, state: Boolean) : AnimeFilter.CheckBox(name, state)

    private class YearFilter(private val years: List<String>) : AnimeFilter.Select<String>("Year", arrayOf("None") + years.toTypedArray()) {
        fun getValue(): String = if (state == 0) "" else years[state - 1]
    }

    private class SeasonFilter(private val seasons: List<String>) : AnimeFilter.Select<String>("Season", arrayOf("None") + seasons.toTypedArray()) {
        fun getValue(): String = if (state == 0) "" else seasons[state - 1]
    }

    private class RatingLabelFilter(private val labels: List<String>) : AnimeFilter.Select<String>("Rating Label", arrayOf("None") + labels.toTypedArray()) {
        fun getValue(): String = if (state == 0) "" else labels[state - 1]
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
            key = PREF_FORCE_REFRESH_CACHE,
            title = "Force Refresh Catalog Cache",
            summary = "Clears the local catalog file and fetches a fresh one on the next catalog request.",
            default = false,
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

        private val SERVERS = arrayOf("animeverse", "chiki")
        private val SERVERS_DISPLAY = arrayOf("AnimeVerse", "Chiki")

        private const val PREF_HOSTER_EXCLUDE_KEY = "hoster_exclusion"
        private const val PREF_HOSTER_EXCLUDE_TITLE = "Excluded Hosts"
        private val PREF_HOSTER_EXCLUDE_DEFAULT = emptySet<String>()

        private const val PREF_FORCE_REFRESH_CACHE = "force_refresh_cache"
    }
}
