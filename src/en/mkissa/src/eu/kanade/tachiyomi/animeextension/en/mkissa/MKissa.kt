package eu.kanade.tachiyomi.animeextension.en.mkissa

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
import eu.kanade.tachiyomi.animeextension.en.mkissa.extractors.MKissaExtractor
import eu.kanade.tachiyomi.animeextension.en.mkissa.extractors.UniExtractor
import eu.kanade.tachiyomi.animeextension.en.mkissa.extractors.VidnestExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.appendGraphQLParams
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.graphQLBody
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.delay
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import kotlin.time.Duration.Companion.seconds

class MKissa :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MKissa"

    override val id = 4709139914729853090L

    override val baseUrl by lazy { "${preferences.siteUrl}/anime" }

    private val apiUrl by lazy { preferences.apiUrl }

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        if (getString(PREF_SITE_DOMAIN_KEY, null) == LEGACY_SITE_DOMAIN) {
            edit().putString(PREF_SITE_DOMAIN_KEY, PREF_SITE_DOMAIN_DEFAULT).apply()
        }
        if (getString(PREF_DOMAIN_KEY, null) == LEGACY_API_DOMAIN) {
            edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
        }
        // The settings screen persists the alt-hoster default on first open, so hosters added
        // later stay disabled for anyone who has opened it. Enable them once.
        if (!getBoolean(PREF_ALT_HOSTER_MIGRATED_KEY, false)) {
            val editor = edit().putBoolean(PREF_ALT_HOSTER_MIGRATED_KEY, true)
            getStringSet(PREF_ALT_HOSTER_KEY, null)?.let {
                editor.putStringSet(PREF_ALT_HOSTER_KEY, it + ADDED_ALT_HOSTERS)
            }
            editor.apply()
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val postHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", GRAPHQL_ORIGIN)
            .add("Referer", "$GRAPHQL_ORIGIN/")
            .build()
    }

    private val playerHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .set("Referer", "$PLAYER_DOMAIN/")
            .build()
    }

    private suspend inline fun <reified V : Any> graphQL(query: String, variables: V): String {
        val body = graphQLBody(query = query, variables = variables)
        return postGraphQL(body)
    }

    // Opening an entry fires details, episodes and related at once, which trips the API's per-IP
    // throttle. It answers with "try again in N seconds", so wait that long instead of failing.
    private suspend fun postGraphQL(body: RequestBody): String {
        repeat(MAX_RATE_LIMIT_RETRIES) {
            val response = client.post("$apiUrl/api", postHeaders, body).bodyString()
            val waitSeconds = RATE_LIMIT_REGEX.find(response)?.groupValues?.get(1)?.toLongOrNull()
                ?: return response
            delay((waitSeconds + 1).coerceAtMost(MAX_RATE_LIMIT_WAIT_SECONDS).seconds)
        }
        return client.post("$apiUrl/api", postHeaders, body).bodyString()
    }

    // A throttled or failed query comes back as `{"errors": [...], "data": {"show": null}}`; surface
    // the server's message instead of a JSON decoding error or silently missing fields.
    private inline fun <reified T> String.parseResult(): T {
        keyManager.apiErrorMessage(this)?.let { throw Exception(it) }
        return parseAs<T>()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val variables = PopularVariables(type = "anime", size = PAGE_SIZE, dateRange = 7, page = page)
        val recommendations = graphQL(POPULAR_QUERY, variables).parseResult<PopularResult>()
            .data.queryPopular.recommendations

        val animes = recommendations.mapNotNull { it.anyCard?.toSAnime() }
        return AnimesPage(animes, recommendations.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = searchShows(
        SearchVariables(
            search = SearchInput(allowAdult = true, allowUnknown = true),
            limit = PAGE_SIZE,
            page = page,
            translationType = preferences.subPref,
            countryOrigin = "ALL",
        ),
    )

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = Filters.getSearchParameters(filters)

        val search = SearchInput(
            allowAdult = true,
            allowUnknown = true,
            query = query.ifBlank { null },
            sortBy = params.sortBy.takeIf { it.isNotBlank() && it != "Recent" },
            season = params.season.takeIf { it.isNotBlank() && it != "all" },
            year = params.releaseYear.toIntOrNull(),
            genres = params.genres,
            excludeGenres = params.genres?.let { emptyList() },
            types = params.types,
        )

        return searchShows(
            SearchVariables(
                search = search,
                limit = PAGE_SIZE,
                page = page,
                translationType = preferences.subPref,
                countryOrigin = params.origin,
            ),
        )
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    private suspend fun searchShows(variables: SearchVariables): AnimesPage {
        val animes = graphQL(SEARCH_QUERY, variables).parseResult<SearchResult>()
            .data.shows.edges.map { it.toSAnime() }
        return AnimesPage(animes, animes.size == PAGE_SIZE)
    }

    // ============================== Related ===============================

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val genres = anime.genre?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.ifEmpty { null }
            ?: return emptyList()

        val variables = SearchVariables(
            search = SearchInput(allowAdult = true, allowUnknown = true, genres = genres),
            limit = PAGE_SIZE,
            page = 1,
            translationType = preferences.subPref,
        )
        return searchShows(variables).animes
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "${preferences.siteUrl}/anime/${anime.showId}"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val show = graphQL(DETAILS_QUERY, ShowIdVariables(anime.showId)).parseResult<DetailsResult>().data.show

        return SAnime.create().apply {
            genre = show.genres?.joinToString()
            status = parseStatus(show.status)
            author = show.studios?.firstOrNull()
            description = buildString {
                append(
                    Jsoup.parseBodyFragment(show.description?.replace("<br>", "br2n") ?: "")
                        .text()
                        .replace("br2n", "\n"),
                )
                append("\n\n")
                append("Type: ${show.type ?: "Unknown"}")
                append("\nAired: ${show.season?.quarter ?: "-"} ${show.season?.year ?: "-"}")
                append("\nScore: ${show.score ?: "-"}★")
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val subPref = preferences.subPref
        val show = graphQL(EPISODES_QUERY, ShowIdVariables(anime.showId)).parseResult<SeriesResult>().data.show

        val episodes = if (subPref == "sub") {
            show.availableEpisodesDetail.sub
        } else {
            show.availableEpisodesDetail.dub
        }

        return episodes.orEmpty().map { ep ->
            val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")

            SEpisode.create().apply {
                episode_number = ep.toFloatOrNull() ?: 0F
                name = "Episode $numName ($subPref)"
                url = EpisodeVariables(EpisodeVariables.Variables(show.id, subPref, ep)).toJsonString()
            }
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val vars = episode.url.parseAs<EpisodeVariables>().variables
        return "${preferences.siteUrl}/anime/${vars.showId}/p-${vars.episodeString}-${vars.translationType}"
    }

    // ============================== Hosters ===============================

    private val keyManager by lazy {
        MKissaKeyManager(client, headers, preferences, preferences.siteUrl, apiUrl)
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosterSelection = preferences.getHosters
        val altHosterSelection = preferences.getAltHosters

        val servers = fetchSourceUrls(episode).mapNotNull { source ->
            val videoUrl = source.sourceUrl.decryptSource()
            val sourceName = source.sourceName.lowercase()

            val internalName = if (videoUrl.startsWith("/apivtwo/")) {
                INTERNAL_HOSTER_MATCHERS
                    .filter { (name, pattern) -> name in hosterSelection && pattern.containsMatchIn(sourceName) }
                    .maxByOrNull { (name, _) -> name.length }
                    ?.first
            } else {
                null
            }

            val data = when {
                internalName != null -> HosterData(videoUrl, EXTRACTOR_INTERNAL, internalName, source.priority)

                "player" in altHosterSelection && source.type == "player" ->
                    HosterData(videoUrl, EXTRACTOR_PLAYER, "player", source.priority)

                else -> {
                    val mapping = HOSTER_MAPPINGS.firstOrNull { (altHoster, urlMatches) ->
                        (altHoster.lowercase() in hosterSelection || altHoster in altHosterSelection) &&
                            videoUrl.containsAny(urlMatches)
                    } ?: return@mapNotNull null
                    HosterData(videoUrl, mapping.first, mapping.first.lowercase(), source.priority)
                }
            }

            source.sourceName to data
        }

        // Some episodes list the same server more than once. Drop exact repeats, and number the
        // remaining ones (separate uploads) so they can be told apart.
        val seen = mutableMapOf<String, Int>()
        return servers.distinctBy { (_, data) -> data.url }.map { (name, data) ->
            val count = seen.merge(name, 1, Int::plus)!!
            Hoster(hosterName = if (count > 1) "$name ($count)" else name, internalData = data.toJsonString())
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefServer = preferences.prefServer

        return map { it to it.internalData.parseAs<HosterData>() }
            .sortedWith(
                compareByDescending<Pair<Hoster, HosterData>> { (_, data) ->
                    prefServer != PREF_SERVER_DEFAULT && data.serverKey == prefServer
                }.thenByDescending { (_, data) -> data.priority },
            )
            .map { (hoster, _) -> hoster }
    }

    private suspend fun fetchSourceUrls(episode: SEpisode): List<Episode.SourceUrl> {
        val encryptionChangedError = Exception("MKissa changed its stream encryption; update the extension")
        var lastError: Throwable? = null
        var buildHealed = false

        repeat(MAX_KEY_ATTEMPTS) { attempt ->
            val material = runCatching { keyManager.material(forceRefresh = attempt > 0) }
                .getOrElse {
                    lastError = it
                    return@repeat
                }

            val responseBody = runCatching {
                client.get(streamUrl(episode, material), streamHeaders(material), CacheControl.FORCE_NETWORK).bodyString()
            }.getOrElse {
                lastError = it
                null
            }

            if (responseBody != null) {
                val tobeparsed = runCatching {
                    responseBody.parseAs<EncryptedEpisodeResult>().data.tobeparsed
                }.getOrNull()

                if (tobeparsed.isNullOrBlank()) {
                    keyManager.apiErrorMessage(responseBody)?.let { throw Exception(it) }
                }

                when {
                    !tobeparsed.isNullOrBlank() -> {
                        runCatching { keyManager.decrypt(tobeparsed, material)?.parseAs<DecryptedEpisodeResult>() }
                            .getOrNull()
                            ?.let { return it.episode?.sourceUrls.orEmpty() }
                    }

                    !keyManager.isCryptoError(responseBody) -> {
                        runCatching { responseBody.parseAs<EpisodeResult>().data.episode?.sourceUrls.orEmpty() }
                            .getOrNull()
                            ?.let { return it }
                    }
                }

                lastError = encryptionChangedError

                if (attempt >= 1 && !buildHealed && keyManager.isCryptoError(responseBody)) {
                    keyManager.invalidateBuild()
                    buildHealed = true
                }
            }
            keyManager.invalidate()
        }

        throw lastError ?: encryptionChangedError
    }

    private fun streamUrl(episode: SEpisode, material: MKissaKeyManager.Material): HttpUrl {
        val extensions = StreamExtensions(
            persistedQuery = StreamExtensions.PersistedQuery(version = 1, sha256Hash = STREAM_HASH),
            k = ANIME_LANE,
            aaReq = keyManager.aaReq(material),
        )

        return apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .appendGraphQLParams(
                query = STREAM_QUERY,
                variables = episode.url.parseAs<EpisodeVariables>().variables,
                extensions = extensions.toJsonElement(),
            )
            .build()
    }

    private fun streamHeaders(material: MKissaKeyManager.Material) = headers.newBuilder()
        .set("x-build-id", material.buildId)
        .build()

    // =============================== Videos ===============================

    private val mkissaExtractor by lazy { MKissaExtractor(client, headers) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uniExtractor by lazy { UniExtractor(client, headers) }
    private val vidnestExtractor by lazy { VidnestExtractor(client, headers) }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.parseAs<HosterData>()
        val url = data.url

        val videos = when (data.extractor) {
            EXTRACTOR_INTERNAL -> mkissaExtractor.videoFromUrl(url, hoster.hosterName, PLAYER_DOMAIN)
            EXTRACTOR_PLAYER -> listOf(Video(videoUrl = url, videoTitle = "Original", headers = playerHeaders))
            "vidstreaming" -> gogoStreamExtractor.videosFromUrl(url.replace(LEADING_SLASHES_REGEX, "https://"))
            "doodstream" -> doodExtractor.videosFromUrl(url)
            "okru" -> okruExtractor.videosFromUrl(url)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
            "streamlare" -> streamlareExtractor.videosFromUrl(url)
            "Fm-Hls" -> filemoonExtractor.videosFromUrl(url, prefix = "Fm-Hls:")
            "streamwish" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            "uni" -> uniExtractor.videosFromUrl(url, preferences.siteUrl.toHttpUrl().host)
            "vidnest" -> vidnestExtractor.videosFromUrl(url, "${preferences.siteUrl}/")
            else -> emptyList()
        }

        val quality = preferences.quality
        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { it.resolution ?: it.videoTitle.resolution() },
        )
    }

    // ============================= Utilities ==============================

    private val SAnime.showId: String
        get() = url.substringBefore(URL_SEPARATOR)

    private fun ShowCard.toSAnime(): SAnime = SAnime.create().apply {
        title = when (preferences.titleStyle) {
            "romaji" -> name
            "eng" -> englishName
            else -> nativeName
        } ?: name
        thumbnail_url = this@toSAnime.thumbnail?.let(::thumbnailUrl)
        url = listOf(id, slugTime.orEmpty(), name.slugify()).joinToString(URL_SEPARATOR)
    }

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#") -> substring(1) to 0
            else -> this to null
        }

        if (hexPayload.length % 2 != 0) return this
        val size = hexPayload.length / 2
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            val hi = hexPayload[i * 2].digitToIntOrNull(16) ?: return this
            val lo = hexPayload[i * 2 + 1].digitToIntOrNull(16) ?: return this
            bytes[i] = ((hi shl 4) or lo).toByte()
        }

        if (keyType == null) {
            for (mask in XOR_MASKS) {
                val chars = CharArray(size) { idx -> ((bytes[idx].toInt() and 0xFF) xor mask).toChar() }
                val decoded = String(chars)
                if (decoded.contains("/clock") || decoded.contains("http")) return decoded
            }
            return this
        }

        val mask = XOR_MASKS[keyType]
        val chars = CharArray(size) { idx -> ((bytes[idx].toInt() and 0xFF) xor mask).toChar() }
        return String(chars)
    }

    private fun String.resolution(): Int = RESOLUTION_REGEX.find(this)?.value?.toIntOrNull() ?: 0

    private fun parseStatus(string: String?): Int = when (string) {
        "Releasing" -> SAnime.ONGOING
        "Finished" -> SAnime.COMPLETED
        "Not Yet Released" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun String.slugify(): String = replace(NON_ALPHANUMERIC_REGEX, "-")
        .replace(REPEATED_DASH_REGEX, "-")
        .lowercase()

    private fun thumbnailUrl(url: String): String = if (url.startsWith("https://")) {
        THUMBNAIL_PROXY.format(url.removePrefix("https://"))
    } else {
        THUMBNAIL_PROXY_SUB.format(url)
    }

    private fun String.containsAny(keywords: List<String>): Boolean = keywords.any { this.contains(it) }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    companion object {
        private const val PAGE_SIZE = 26
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val URL_SEPARATOR = "<&sep>"

        private const val EXTRACTOR_INTERNAL = "internal"
        private const val EXTRACTOR_PLAYER = "player"

        private val INTERAL_HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls",
        )

        private val INTERNAL_HOSTER_MATCHERS = INTERAL_HOSTER_NAMES.map {
            it.lowercase() to Regex("""\b${Regex.escape(it.lowercase())}\b""")
        }

        private val HOSTER_MAPPINGS = listOf(
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc", "playtaku", "vidcloud"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru", "okru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
            "Fm-Hls" to listOf("bysekoze.com", "fastmoon", "filemoon", "moonplayer"),
            "streamwish" to listOf("wish"),
            "uni" to listOf("uns.bio"),
            "vidnest" to listOf("vidnest"),
        )

        private val ALT_HOSTER_NAMES = arrayOf(
            "player",
            "vidstreaming",
            "okru",
            "mp4upload",
            "streamlare",
            "doodstream",
            "streamwish",
            "uni",
            "vidnest",
        )

        private const val THUMBNAIL_PROXY = "https://wp.youtube-anime.com/%s?w=250"
        private const val THUMBNAIL_PROXY_SUB = "https://wp.youtube-anime.com/aln.youtube-anime.com/%s?w=250"

        private const val PREF_SITE_DOMAIN_KEY = "preferred_site_domain"
        private const val PREF_SITE_DOMAIN_DEFAULT = "https://mkissa.to"

        private const val LEGACY_SITE_DOMAIN = "https://allmanga.to"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://api.mkissa.net"
        private const val LEGACY_API_DOMAIN = "https://api.allanime.day"

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

        private val HOSTER_NAMES = INTERAL_HOSTER_NAMES + "Fm-Hls"
        private val PREF_HOSTER_ENTRY_VALUES = HOSTER_NAMES.map {
            it.lowercase()
        }.toTypedArray()
        private val PREF_HOSTER_DEFAULT = setOf("default", "ac", "ak", "kir", "si-hls", "s-mp4", "ac-hls", "fm-hls")

        private const val PREF_ALT_HOSTER_KEY = "alt_hoster_selection"
        private const val PREF_ALT_HOSTER_MIGRATED_KEY = "alt_hoster_selection_uni_vidnest"
        private val ADDED_ALT_HOSTERS = setOf("uni", "vidnest")

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

        private const val MAX_RATE_LIMIT_RETRIES = 3
        private const val MAX_RATE_LIMIT_WAIT_SECONDS = 10L
        private val RATE_LIMIT_REGEX = Regex("""Too many requests, please try again in (\d+) seconds""")

        private val RESOLUTION_REGEX = Regex("""\d{3,4}""")
        private val LEADING_SLASHES_REGEX = Regex("^//")
        private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-zA-Z0-9]""")
        private val REPEATED_DASH_REGEX = Regex("""-{2,}""")

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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_DOMAIN_KEY
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf("mkissa.to")
            entryValues = arrayOf("https://mkissa.to")
            setDefaultValue(PREF_SITE_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("api.mkissa.net")
            entryValues = arrayOf("https://api.mkissa.net")
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
            entries = HOSTER_NAMES
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
