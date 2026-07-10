package eu.kanade.tachiyomi.animeextension.en.allanime

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.EpisodeResult.DataEpisode.Episode.SourceUrl
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.AllAnimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class AllAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AllAnime"

    override val baseUrl by lazy { "${preferences.siteUrl}/anime" }

    private val apiUrl by lazy { preferences.apiUrl }

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "anime")
                put("size", PAGE_SIZE)
                put("dateRange", 7)
                put("page", page)
            }
            put("query", POPULAR_QUERY)
        }
        return buildPost(data)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PopularResult>()

        val animeList = parsed.data.queryPopular.recommendations.mapNotNull {
            if (it.anyCard == null) return@mapNotNull null
            SAnime.create().apply {
                title = when (preferences.titleStyle) {
                    "romaji" -> it.anyCard.name
                    "eng" -> it.anyCard.englishName
                    else -> it.anyCard.nativeName
                } ?: it.anyCard.name
                thumbnail_url = it.anyCard.thumbnail?.let(::thumbnailUrl)
                url = "${it.anyCard.id}<&sep>${it.anyCard.slugTime ?: ""}<&sep>${it.anyCard.name.slugify()}"
            }
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("allowAdult", true)
                    put("allowUnknown", true)
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", preferences.subPref)
                put("countryOrigin", "ALL")
            }
            put("query", SEARCH_QUERY)
        }
        return buildPost(data)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnime(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filters = AllAnimeFilters.getSearchParameters(filters)

        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    if (query.isNotBlank()) put("query", query)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                    filters.sortBy.takeIf { it != "Recent" && it.isNotBlank() }?.let { put("sortBy", it) }
                    filters.season.takeIf { it != "all" && it.isNotBlank() }?.let { put("season", it) }
                    filters.releaseYear.toIntOrNull()?.let { put("year", it) }
                    if (filters.genres != "all" && filters.genres.isNotBlank()) {
                        put("genres", filters.genres.parseAs<JsonElement>())
                        put("excludeGenres", buildJsonArray { })
                    }
                    if (filters.types != "all" && filters.types.isNotBlank()) put("types", filters.types.parseAs<JsonElement>())
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", preferences.subPref)
                put("countryOrigin", filters.origin)
            }
            put("query", SEARCH_QUERY)
        }
        return buildPost(data)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val genres = anime.genre!!
            .split(",")
            .map { it.trim() }
            .toJsonString()
        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("allowAdult", true)
                    put("allowUnknown", true)
                    put("genres", genres.parseAs<JsonElement>())
                }
                put("limit", PAGE_SIZE)
                put("page", 1)
                put("translationType", preferences.subPref)
            }
            put("query", SEARCH_QUERY)
        }
        return buildPost(data)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = parseAnime(response).animes

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AllAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("_id", anime.url.split("<&sep>").first())
            }
            put("query", DETAILS_QUERY)
        }
        return buildPost(data)
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val (id, time, slug) = anime.url.split("<&sep>")
        val slugTime = if (time.isNotEmpty()) "-st-$time" else time
        val siteUrl = preferences.siteUrl

        return "$siteUrl/bangumi/$id"
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val show = response.parseAs<DetailsResult>().data.show

        return SAnime.create().apply {
            genre = show.genres?.joinToString()
            status = parseStatus(show.status)
            author = show.studios?.firstOrNull()
            description = buildString {
                append(
                    Jsoup.parseBodyFragment(
                        show.description?.replace("<br>", "br2n") ?: "",
                    ).text().replace("br2n", "\n"),
                )
                append("\n\n")
                append("Type: ${show.type ?: "Unknown"}")
                append("\nAired: ${show.season?.quarter ?: "-"} ${show.season?.year ?: "-"}")
                append("\nScore: ${show.score ?: "-"}★")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("_id", anime.url.split("<&sep>").first())
            }
            put("query", EPISODES_QUERY)
        }
        return buildPost(data)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val subPref = preferences.subPref
        val medias = response.parseAs<SeriesResult>()

        val episodesDetail = if (subPref == "sub") {
            medias.data.show.availableEpisodesDetail.sub!!
        } else {
            medias.data.show.availableEpisodesDetail.dub!!
        }

        return episodesDetail.map { ep ->
            val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")

            SEpisode.create().apply {
                episode_number = ep.toFloatOrNull() ?: 0F
                name = "Episode $numName ($subPref)"
                url = buildJsonObject {
                    putJsonObject("variables") {
                        put("showId", medias.data.show.id)
                        put("translationType", subPref)
                        put("episodeString", ep)
                    }
                }.toJsonString()
            }
        }
    }

    // ============================ Video Links =============================

    private val keyManager by lazy {
        AllAnimeKeyManager(client, headers, preferences)
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    // videoListRequest no longer yields a usable URL, so point "Open in WebView" at the show page.
    override fun getEpisodeUrl(episode: SEpisode): String {
        val variables = episode.url.parseAs<JsonObject>()["variables"]!!.jsonObject
        val showId = variables["showId"]!!.jsonPrimitive.content
        return "${preferences.siteUrl}/bangumi/$showId"
    }

    private fun videoListRequest(episode: SEpisode, material: AllAnimeKeyManager.Material): Request {
        val variables = episode.url.parseAs<JsonObject>()["variables"]!!.jsonObject

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", STREAM_HASH)
            }
            put("aaReq", keyManager.aaReq(material))
        }

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables.toJsonString())
            addQueryParameter("extensions", extensions.toJsonString())
        }.build().toString()

        return GET(url, headers)
    }

    private val allAnimeExtractor by lazy { AllAnimeExtractor(client, headers) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val sourceUrls = fetchSourceUrls(episode)

        val hosterSelection = preferences.getHosters
        val altHosterSelection = preferences.getAltHosters

        val mappings = listOf(
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc", "playtaku", "vidcloud"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru", "okru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
            "filemoon" to listOf("filemoon", "moonplayer"),
            "streamwish" to listOf("wish"),
        )

        val serverList = mutableListOf<Server>()
        sourceUrls.forEach { video ->
            val videoUrl = video.sourceUrl.decryptSource()

            val matchingMapping = mappings.firstOrNull { (altHoster, urlMatches) ->
                altHosterSelection.contains(altHoster) && videoUrl.containsAny(urlMatches)
            }

            when {
                videoUrl.startsWith("/apivtwo/") && INTERAL_HOSTER_NAMES.any {
                    Regex("""\b${it.lowercase()}\b""").find(video.sourceName.lowercase()) != null &&
                        hosterSelection.contains(it.lowercase())
                } ->
                    Server(videoUrl, "internal ${video.sourceName}", video.priority)
                        .let(serverList::add)

                altHosterSelection.contains("player") && video.type == "player" ->
                    Server(videoUrl, "player@${video.sourceName}", video.priority)
                        .let(serverList::add)

                matchingMapping != null ->
                    Server(videoUrl, matchingMapping.first, video.priority)
                        .let(serverList::add)
            }
        }

        val iframeEndpoint = PLAYER_DOMAIN

        return serverList.parallelCatchingFlatMap { server ->
            val sName = server.sourceName
            when {
                sName.startsWith("internal ") -> {
                    allAnimeExtractor.videoFromUrl(server.sourceUrl, server.sourceName, iframeEndpoint)
                }

                sName.startsWith("player@") -> {
                    val videoHeaders = headers.newBuilder().apply {
                        add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                        add("Host", server.sourceUrl.toHttpUrl().host)
                        add("Referer", "$iframeEndpoint/")
                    }.build()

                    Video(
                        server.sourceUrl,
                        "Original (player ${server.sourceName.substringAfter("player@")})",
                        server.sourceUrl,
                        headers = videoHeaders,
                    ).let(::listOf)
                }

                sName == "vidstreaming" -> {
                    gogoStreamExtractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))
                }

                sName == "dood" -> {
                    doodExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "okru" -> {
                    okruExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "mp4upload" -> {
                    mp4uploadExtractor.videosFromUrl(server.sourceUrl, headers)
                }

                sName == "streamlare" -> {
                    streamlareExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "filemoon" -> {
                    filemoonExtractor.videosFromUrl(server.sourceUrl, prefix = "Filemoon:")
                }

                sName == "streamwish" -> {
                    streamwishExtractor.videosFromUrl(server.sourceUrl, videoNameGen = { "StreamWish:$it" })
                }

                else -> emptyList()
            }.map { v -> Pair(v, server.priority) }
        }
            .let(::prioritySort)
    }

    // ============================= Utilities ==============================

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#") -> substring(1) to 0
            else -> this to null
        }

        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this // Fail fast and return the original string instead of a corrupted decryption
        }

        if (keyType == null) {
            XOR_MASKS.forEach { mask ->
                val decrypted = String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
                if (decrypted.contains("/clock") || decrypted.contains("http")) return decrypted
            }
            return this
        }

        val mask = XOR_MASKS[keyType]
        return String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
    }

    private fun prioritySort(pList: List<Pair<Video, Float>>): List<Video> {
        val prefServer = preferences.prefServer
        val quality = preferences.quality
        val subPref = preferences.subPref

        return pList.sortedWith(
            compareBy(
                { if (prefServer == "site_default") it.second else it.first.quality.contains(prefServer, true) },
                { it.first.quality.contains(quality, true) },
                { it.first.quality.contains(subPref, true) },
            ),
        ).reversed()
            .map { t -> t.first }
    }

    private fun buildPost(dataObject: JsonObject): Request {
        val payload = dataObject.toJsonString().toJsonBody()

        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", apiUrl.toHttpUrl().host)
            add("Origin", GRAPHQL_ORIGIN)
            add("Referer", "$GRAPHQL_ORIGIN/")
        }.build()

        return POST("$apiUrl/api", headers = postHeaders, body = payload)
    }

    data class Server(
        val sourceUrl: String,
        val sourceName: String,
        val priority: Float,
    )

    private fun parseStatus(string: String?): Int = when (string) {
        "Releasing" -> SAnime.ONGOING
        "Finished" -> SAnime.COMPLETED
        "Not Yet Released" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun String.slugify(): String = this.replace("""[^a-zA-Z0-9]""".toRegex(), "-")
        .replace("""-{2,}""".toRegex(), "-")
        .lowercase()

    private fun parseAnime(response: Response): AnimesPage {
        val parsed = response.parseAs<SearchResult>()

        val animeList = parsed.data.shows.edges.map { ani ->
            SAnime.create().apply {
                title = when (preferences.titleStyle) {
                    "romaji" -> ani.name
                    "eng" -> ani.englishName
                    else -> ani.nativeName
                } ?: ani.name
                thumbnail_url = ani.thumbnail?.let(::thumbnailUrl)
                url = "${ani.id}<&sep>${ani.slugTime ?: ""}<&sep>${ani.name.slugify()}"
            }
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    private fun thumbnailUrl(url: String): String = if (url.startsWith("https://")) {
        THUMBNAIL_PROXY.format(url.removePrefix("https://"))
    } else {
        THUMBNAIL_PROXY_SUB.format(url)
    }

    private fun String.containsAny(keywords: List<String>): Boolean = keywords.any { this.contains(it) }

    // The aaReq token fails two ways: partB/epoch rotated (a refetch fixes it) or the client
    // mask rotated with a new site build (needs a re-extract). Remediate in that order,
    // retrying between each.
    private suspend fun fetchSourceUrls(episode: SEpisode): List<SourceUrl> {
        var maskHealed = false

        repeat(MAX_KEY_ATTEMPTS) { attempt ->
            val material = keyManager.material(forceRefresh = attempt > 0)

            // A failed request (or a response we can't turn into sources) is treated as a key
            // failure and remediated below, rather than surfacing a raw error to the user.
            val responseBody = runCatching {
                client.newCall(videoListRequest(episode, material)).awaitSuccess().bodyString()
            }.getOrNull()

            if (responseBody != null) {
                val tobeparsed = runCatching {
                    responseBody.parseAs<EncryptedEpisodeResult>().data.tobeparsed
                }.getOrNull()

                when {
                    // A decrypted but empty result is legitimate.
                    !tobeparsed.isNullOrBlank() -> {
                        runCatching { keyManager.decrypt(tobeparsed, material)?.parseAs<DecryptedEpisodeResult>() }
                            .getOrNull()
                            ?.let { return it.episode?.sourceUrls.orEmpty() }
                    }
                    // No payload and no crypto error: an older, unencrypted show.
                    !keyManager.isCryptoError(responseBody) -> {
                        runCatching { responseBody.parseAs<EpisodeResult>().data.episode?.sourceUrls.orEmpty() }
                            .getOrNull()
                            ?.let { return it }
                    }
                }
            }

            // Attempt 1 already refetched partB + epoch; if that still failed, the mask itself
            // rotated, so re-extract it before the final attempt.
            if (attempt == 1 && !maskHealed) {
                maskHealed = keyManager.healMask()
            }
            keyManager.invalidate()
        }

        throw Exception("AllAnime changed its stream encryption; update the extension")
    }

    companion object {
        private const val PAGE_SIZE = 26
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private val INTERAL_HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls",
        )

        private val ALT_HOSTER_NAMES = arrayOf(
            "player",
            "vidstreaming",
            "okru",
            "mp4upload",
            "streamlare",
            "doodstream",
            "filemoon",
            "streamwish",
        )

        private const val THUMBNAIL_PROXY = "https://wp.youtube-anime.com/%s?w=250"
        private const val THUMBNAIL_PROXY_SUB = "https://wp.youtube-anime.com/aln.youtube-anime.com/%s?w=250"

        private const val PREF_SITE_DOMAIN_KEY = "preferred_site_domain"
        private const val PREF_SITE_DOMAIN_DEFAULT = "https://allmanga.to"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://api.allanime.day"

        // The site's hardcoded default iframe host (was previously served by /getVersion,
        // now removed). Used as the base for legacy internal/player servers.
        private const val PLAYER_DOMAIN = "https://allanime.day"

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = arrayOf("Site Default") +
            INTERAL_HOSTER_NAMES.sliceArray(1 until INTERAL_HOSTER_NAMES.size) +
            ALT_HOSTER_NAMES
        private val PREF_SERVER_ENTRY_VALUES = arrayOf("site_default") +
            INTERAL_HOSTER_NAMES.sliceArray(1 until INTERAL_HOSTER_NAMES.size).map {
                it.lowercase()
            }.toTypedArray() +
            ALT_HOSTER_NAMES
        private const val PREF_SERVER_DEFAULT = "site_default"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val PREF_HOSTER_ENTRY_VALUES = INTERAL_HOSTER_NAMES.map {
            it.lowercase()
        }.toTypedArray()
        private val PREF_HOSTER_DEFAULT = setOf("default", "ac", "ak", "kir", "luf-mp4", "si-hls", "s-mp4", "ac-hls")

        private const val PREF_ALT_HOSTER_KEY = "alt_hoster_selection"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf(
            "2160p",
            "1440p",
            "1080p",
            "720p",
            "480p",
            "360p",
            "240p",
            "80p",
        )
        private val PREF_QUALITY_ENTRY_VALUES = PREF_QUALITY_ENTRIES.map {
            it.substringBefore("p")
        }.toTypedArray()
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_DEFAULT = "romaji"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_DEFAULT = "sub"

        private const val MAX_KEY_ATTEMPTS = 3

        // XOR keys indexed by source-URL prefix type: '--'=3  '#-'=2  '##'=1  '-#'=4  '#'=0
        private val XOR_KEYS = arrayOf(
            "allanimenews",
            "1234567890123456789",
            "1234567890123456789012345",
            "s5feqxw21",
            "feqx1",
        )

        private val XOR_MASKS = XOR_KEYS.map { key ->
            key.fold(0) { mask, ch -> mask xor ch.code }
        }.toIntArray()
    }

    // ============================== Settings ==============================

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_DOMAIN_KEY
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf("allmanga.to")
            entryValues = arrayOf("https://allmanga.to")
            setDefaultValue(PREF_SITE_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("api.allanime.day")
            entryValues = arrayOf("https://api.allanime.day")
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Video Server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRY_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = INTERAL_HOSTER_NAMES
            entryValues = PREF_HOSTER_ENTRY_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_ALT_HOSTER_KEY
            title = "Enable/Disable Alternative Hosts"
            entries = ALT_HOSTER_NAMES
            entryValues = ALT_HOSTER_NAMES
            setDefaultValue(ALT_HOSTER_NAMES.toSet())
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_STYLE_KEY
            title = "Preferred Title Style"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "eng", "native")
            setDefaultValue(PREF_TITLE_STYLE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Prefer subs or dubs?"
            entries = arrayOf("Subs", "Dubs")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private val SharedPreferences.subPref
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.apiUrl
        get() = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private val SharedPreferences.siteUrl
        get() = getString(PREF_SITE_DOMAIN_KEY, PREF_SITE_DOMAIN_DEFAULT)!!

    private val SharedPreferences.titleStyle
        get() = getString(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.prefServer
        get() = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

    private val SharedPreferences.getHosters
        get() = getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

    private val SharedPreferences.getAltHosters
        get() = getStringSet(PREF_ALT_HOSTER_KEY, ALT_HOSTER_NAMES.toSet())!!
}
