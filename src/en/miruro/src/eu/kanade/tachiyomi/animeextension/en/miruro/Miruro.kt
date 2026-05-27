package eu.kanade.tachiyomi.animeextension.en.miruro

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.decodeHex
import keiyoushi.utils.delegate
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class Miruro :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Miruro.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override var baseUrl by LazyMutable { preferences.preferredMirror }

    private val SharedPreferences.preferredMirror by preferences.delegate(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT)
    private val SharedPreferences.markFillers by preferences.delegate(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT)
    private val SharedPreferences.hideFillers by preferences.delegate(PREF_HIDE_FILLERS_KEY, PREF_HIDE_FILLERS_DEFAULT)
    private val SharedPreferences.includeAllSubTypes by preferences.delegate(PREF_INCLUDE_ALL_SUB_TYPES_KEY, PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT)
    private val SharedPreferences.stripHtml by preferences.delegate(PREF_STRIP_HTML_KEY, PREF_STRIP_HTML_DEFAULT)
    private val SharedPreferences.mergeAcrossProviders by preferences.delegate(PREF_MERGE_PROVIDERS_KEY, PREF_MERGE_PROVIDERS_DEFAULT)
    private val SharedPreferences.preferredTitleStyle by preferences.delegate(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
    private val SharedPreferences.preferredProvider by preferences.delegate(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
    private val SharedPreferences.preferredSubType by preferences.delegate(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)
    private val SharedPreferences.preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.preferredStreamFormat by preferences.delegate(PREF_STREAM_FORMAT_KEY, PREF_STREAM_FORMAT_DEFAULT)
    private val SharedPreferences.episodeSortOrder by preferences.delegate(PREF_EPISODE_SORT_KEY, PREF_EPISODE_SORT_DEFAULT)
    private val SharedPreferences.descriptionTruncation by preferences.delegate(PREF_DESCRIPTION_TRUNCATE_KEY, PREF_DESCRIPTION_TRUNCATE_DEFAULT)
    private val SharedPreferences.showProviderInScanlator by preferences.delegate(PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY, PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT)

    private val extractor by lazy {
        MiruroExtractor(client, PIPE_KEY, headers)
    }

    @Volatile
    private var siteConfig: ConfigResponseDto? = null

    private fun fetchConfig(): ConfigResponseDto = synchronized(this) {
        siteConfig?.let { return it }

        return try {
            val request = buildPipeRequest("config", "GET")
            val json = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("Miruro", "Config endpoint returned ${response.code}, using defaults")
                    return defaultConfig
                }
                extractor.decryptResponse(response)
            }
            if (json.isEmpty()) {
                Log.w("Miruro", "Config response was empty, using defaults")
                return defaultConfig
            }
            val config = jsonParser.decodeFromString<ConfigResponseDto>(json)
            siteConfig = config
            Log.i("Miruro", "Fetched site config: ${config.providerOrder.size} providers, order=${config.providerOrder}")
            config
        } catch (e: Exception) {
            Log.w("Miruro", "Failed to fetch config: ${e.message}, using defaults")
            defaultConfig
        }
    }

    private fun getProviderOrder(): List<String> = try {
        fetchConfig().providerOrder.ifEmpty { PREF_PROVIDER_VALUES }
    } catch (e: Exception) {
        Log.w("Miruro", "Failed to get provider order: ${e.message}")
        PREF_PROVIDER_VALUES
    }

    private val defaultConfig = ConfigResponseDto(
        streaming = mapOf(
            "kiwi" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, download = true),
                visible = true,
                player = "native",
            ),
            "telli" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true),
                parent = "kiwi",
                relationship = "embed",
                visible = false,
                player = "iframe",
            ),
            "bee" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, ssub = true, thumbnails = true),
                visible = true,
                player = "native",
                fallback = 3,
            ),
            "bun" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, ssub = true),
                parent = "bee",
                relationship = "embed",
                visible = true,
                player = "iframe",
            ),
            "hop" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(ssub = true, thumbnails = true),
                visible = true,
                player = "native",
                fallback = 4,
            ),
            "nun" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true),
                parent = "ally",
                relationship = "embed",
                visible = true,
                player = "iframe",
            ),
            "dune" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(ssub = true),
                visible = true,
                player = "native",
            ),
            "ally" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, download = true),
                visible = true,
                player = "native",
            ),
        ),
        providerOrder = PREF_PROVIDER_VALUES,
    )

    companion object {
        const val PREFIX_SEARCH = "miruro:"

        private val PIPE_KEY = "71951034f8fbcf53d89db52ceb3dc22c".decodeHex()

        private const val PREF_PROVIDER_KEY = "preferred_provider"
        private const val PREF_PROVIDER_TITLE = "Preferred Provider"

        private val PREF_PROVIDER_ENTRIES = listOf("AnimePahe", "GogoAnime (embed)", "Anikoto", "Anikoto (embed)", "Zoro", "9Anime (embed)", "AnimeKai", "9Anime")
        private val PREF_PROVIDER_VALUES = listOf("kiwi", "telli", "bee", "bun", "hop", "nun", "dune", "ally")
        private const val PREF_PROVIDER_DEFAULT = "kiwi"

        private val PROVIDER_DISPLAY_NAMES = mapOf(
            "kiwi" to "AnimePahe",
            "telli" to "GogoAnime",
            "bee" to "Anikoto",
            "bun" to "Anikoto",
            "hop" to "Zoro",
            "nun" to "9Anime",
            "dune" to "AnimeKai",
            "ally" to "9Anime",
        )

        fun providerDisplayName(alias: String): String = PROVIDER_DISPLAY_NAMES[alias] ?: alias.replaceFirstChar { it.uppercase() }

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub/Dub"
        private val PREF_SUB_TYPE_ENTRIES = listOf("Sub", "Dub", "Soft Sub")
        private val PREF_SUB_TYPE_VALUES = listOf("sub", "dub", "ssub")
        private const val PREF_SUB_TYPE_DEFAULT = "sub"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_STREAM_FORMAT_KEY = "preferred_stream_format"
        private const val PREF_STREAM_FORMAT_TITLE = "Preferred Stream Format"
        private val PREF_STREAM_FORMAT_ENTRIES = listOf("HLS", "MP4", "All")
        private val PREF_STREAM_FORMAT_VALUES = listOf("hls", "mp4", "all")
        private const val PREF_STREAM_FORMAT_DEFAULT = "hls"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_TITLE = "Title Display Style"
        private val PREF_TITLE_STYLE_ENTRIES = listOf("User Preferred", "Romaji", "English", "Native")
        private val PREF_TITLE_STYLE_VALUES = listOf("userPreferred", "romaji", "english", "native")
        private const val PREF_TITLE_STYLE_DEFAULT = "userPreferred"

        private const val PREF_MARK_FILLERS_KEY = "mark_filler_episodes"
        private const val PREF_MARK_FILLERS_TITLE = "Mark filler episodes"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HIDE_FILLERS_KEY = "hide_filler_episodes"
        private const val PREF_HIDE_FILLERS_TITLE = "Hide filler episodes"
        private const val PREF_HIDE_FILLERS_DEFAULT = false

        private const val PREF_INCLUDE_ALL_SUB_TYPES_KEY = "include_all_sub_types"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_TITLE = "Include all sub/dub streams"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT = true

        private const val PREF_STRIP_HTML_KEY = "strip_html_descriptions"
        private const val PREF_STRIP_HTML_TITLE = "Strip HTML from descriptions"
        private const val PREF_STRIP_HTML_DEFAULT = true

        private const val PREF_MERGE_PROVIDERS_KEY = "merge_across_providers"
        private const val PREF_MERGE_PROVIDERS_TITLE = "Merge episodes across providers"
        private const val PREF_MERGE_PROVIDERS_DEFAULT = true

        private const val PREF_EPISODE_SORT_KEY = "episode_sort_order"
        private const val PREF_EPISODE_SORT_TITLE = "Episode List Order"
        private val PREF_EPISODE_SORT_ENTRIES = listOf("Descending (Newest First)", "Ascending (Oldest First)")
        private val PREF_EPISODE_SORT_VALUES = listOf("descending", "ascending")
        private const val PREF_EPISODE_SORT_DEFAULT = "descending"

        private const val PREF_DESCRIPTION_TRUNCATE_KEY = "description_truncation"
        private const val PREF_DESCRIPTION_TRUNCATE_TITLE = "Description Truncation"
        private val PREF_DESCRIPTION_TRUNCATE_ENTRIES = listOf("No Limit", "500 characters", "300 characters", "150 characters")
        private val PREF_DESCRIPTION_TRUNCATE_VALUES = listOf("0", "500", "300", "150")
        private const val PREF_DESCRIPTION_TRUNCATE_DEFAULT = "0"

        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY = "show_provider_in_scanlator"
        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_TITLE = "Show provider names in scanlator"
        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT = false

        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        private const val JIKAN_API_URL = "https://api.jikan.moe/v4"

        private const val PREF_MIRROR_KEY = "preferred_mirror"
        private const val PREF_MIRROR_TITLE = "Preferred mirror"
        private val MIRROR_ENTRIES = listOf("miruro.tv", "miruro.to", "miruro.bz", "miruro.ru")
        private val MIRROR_VALUES = MIRROR_ENTRIES.map { "https://www.$it" }
        private val PREF_MIRROR_DEFAULT = MIRROR_VALUES.first()

        private val TRANSIENT_RETRY_CODES = setOf(429, 502, 503, 504)

        private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val CLOSE_P_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        val SCANLATOR_SUB_TYPES = setOf("sub", "dub")
        val SUB_TYPE_DISPLAY_ORDER = listOf("sub", "dub", "ssub", "h-sub", "embed")
    }

    private val jikanClient: OkHttpClient = network.client.newBuilder()
        .rateLimitHost("$JIKAN_API_URL/".toHttpUrl(), permits = 1, period = 1, unit = TimeUnit.SECONDS)
        .build()

    private val anilistClient: OkHttpClient = network.client.newBuilder()
        .rateLimitHost("$ANILIST_GRAPHQL_URL/".toHttpUrl(), permits = 2, period = 1, unit = TimeUnit.SECONDS)
        .build()

    // ============================== Trending ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val query = buildPipeQuery(
            "type" to "ANIME",
            "status" to "RELEASING",
            "page" to page,
            "perPage" to 20,
            "sort" to "TRENDING_DESC",
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val query = buildPipeQuery(
            "type" to "ANIME",
            "status" to "RELEASING",
            "page" to page,
            "perPage" to 20,
            "sort" to "UPDATED_AT_DESC",
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    // ============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val anilistId = query.removePrefix(PREFIX_SEARCH)
            val request = buildPipeRequest("info/$anilistId", "GET")
            val jsonObj = client.newCall(request).awaitSuccess().use { response ->
                JSONObject(extractor.decryptResponse(response))
            }

            val media = jsonObj.optJSONObject("media") ?: jsonObj

            val id = media.optInt("id", 0)
            val malId = media.optInt("idMal", 0).takeIf { it > 0 }
            if (id > 0) getOrCreateMeta(id, malId)

            val anime = parseAnimeFromMediaObj(media)
            return AnimesPage(listOf(anime), false)
        }

        val request = searchAnimeRequest(page, query, filters)
        return client.newCall(request).awaitSuccess()
            .use(::searchAnimeParse)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            val perPage = 20
            val queryParams = buildPipeQuery(
                "q" to query,
                "type" to "ANIME",
                "limit" to perPage,
                "offset" to (page - 1) * perPage,
            )
            return buildPipeRequest("search", "GET", query = queryParams)
        }

        val params = MiruroFilters.getSearchParameters(filters)

        val queryParams = buildPipeQuery(
            "type" to "ANIME",
            "page" to page,
            "perPage" to 20,
        )

        if (params.sort != "all") queryParams.put("sort", params.sort)
        if (params.season != "all") queryParams.put("season", params.season)
        if (params.year != "all") queryParams.put("year", params.year.toInt())
        if (params.status != "all") queryParams.put("status", params.status)
        if (params.genres.isNotEmpty()) {
            val genresArray = JSONArray()
            params.genres.forEach { genresArray.put(it) }
            queryParams.put("genre", genresArray)
        }
        if (params.excludedGenres.isNotEmpty()) {
            val excludedGenresArray = JSONArray()
            params.excludedGenres.forEach { excludedGenresArray.put(it) }
            queryParams.put("excludedGenre", excludedGenresArray)
        }
        if (params.formats.isNotEmpty()) {
            val formatsArray = JSONArray()
            params.formats.forEach { formatsArray.put(it) }
            queryParams.put("format", formatsArray)
        }
        if (params.tags.isNotEmpty()) {
            val tagsArray = JSONArray()
            params.tags.forEach { tagsArray.put(it) }
            queryParams.put("tag", tagsArray)
        }
        if (params.excludedTags.isNotEmpty()) {
            val excludedTagsArray = JSONArray()
            params.excludedTags.forEach { excludedTagsArray.put(it) }
            queryParams.put("excludedTag", excludedTagsArray)
        }
        if (params.dubLanguage != "all") queryParams.put("dub", params.dubLanguage)

        return buildPipeRequest("search/browse", "GET", query = queryParams)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response, fallbackKeys = listOf("results", "data"))

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = buildPipeRequest("info/${anime.url}", "GET")

    override fun animeDetailsParse(response: Response): SAnime {
        val json = validateResponse(response).use { extractor.decryptResponse(it) }
        // /info/{id} wraps anime data under a "media" key alongside tvdb/tmdb/schedule/mappings
        val mediaJson = try {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONObject("media")?.toString() ?: json
        } catch (_: Exception) {
            json
        }
        val dto = try {
            jsonParser.decodeFromString<AnimeMediaDto>(mediaJson)
        } catch (_: Exception) {
            val jsonObj = JSONObject(mediaJson)
            val mediaObj = jsonObj.optJSONObject("media") ?: jsonObj
            return parseAnimeDetailsFromJsonObj(mediaObj)
        }

        val anilistId = dto.id
        if (anilistId > 0) {
            val existing = getMeta(anilistId)
            if (existing != null && dto.malId != null) {
                existing.malId = dto.malId
            } else if (existing == null) {
                getOrCreateMeta(anilistId, dto.malId)
            }
        }

        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitleFromDto(dto.title, titleStyle)

        val coverUrl = dto.coverImage?.let { cover ->
            cover.extraLarge?.ifEmpty { null }
                ?: cover.large?.ifEmpty { null }
                ?: cover.medium?.ifEmpty { null }
        } ?: dto.bannerImage?.ifEmpty { null } ?: ""

        val rawDescription = dto.description ?: ""
        val description = if (preferences.stripHtml) stripHtml(rawDescription) else rawDescription
        val truncatedDescription = truncateDescription(description)

        val genres = dto.genres.takeIf { it.isNotEmpty() }?.joinToString()

        val status = when (dto.status?.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        val studio = dto.studios?.edges
            ?.firstOrNull { it.isMain }
            ?.node?.name?.ifEmpty { null } ?: ""

        return SAnime.create().apply {
            title.takeIf(String::isNotBlank)?.let { this.title = it }
            thumbnail_url = coverUrl
            this.description = truncatedDescription
            genre = genres
            this.status = status
            author = studio
        }
    }

    private fun parseAnimeDetailsFromJsonObj(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitle(titleObj, titleStyle)

        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))
        val coverUrl = thumbnail.ifEmpty { bannerImage }

        val rawDescription = media.optString("description", "")
        val description = if (preferences.stripHtml) stripHtml(rawDescription) else rawDescription
        val truncatedDescription = truncateDescription(description)

        val genresArray = media.optJSONArray("genres")
        val genres = if (genresArray != null) {
            (0 until genresArray.length()).mapNotNull { genresArray.optString(it) }.joinToString()
        } else {
            null
        }

        val statusStr = media.optString("status", "")
        val status = when (statusStr.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        val studio = extractMainStudio(media.opt("studios"))

        val anilistId = media.optInt("id", 0)
        val malId = media.optInt("idMal", 0).takeIf { it > 0 }
        if (anilistId > 0) {
            val existing = getMeta(anilistId)
            if (existing != null && malId != null) {
                existing.malId = malId
            } else if (existing == null) {
                getOrCreateMeta(anilistId, malId)
            }
        }

        return SAnime.create().apply {
            title.takeIf(String::isNotBlank)?.let { this.title = it }
            thumbnail_url = coverUrl
            this.description = truncatedDescription
            genre = genres
            this.status = status
            author = studio
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val anilistId = anime.url.toIntOrNull()
            ?: throw IOException("Invalid anime URL: ${anime.url}")
        val query = buildPipeQuery(
            "anilistId" to anilistId,
        )
        return buildPipeRequest("episodes", "GET", query = query)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonObj = JSONObject(validateResponse(response).use { extractor.decryptResponse(it) })

        val providers = jsonObj.optJSONObject("providers") ?: return emptyList()
        val preferredProvider = preferences.preferredProvider
        val preferredSubType = preferences.preferredSubType
        val mergeAcrossProviders = preferences.mergeAcrossProviders
        val showProvider = preferences.showProviderInScanlator

        val anilistId = extractAnilistIdFromPipeRequest(response.request.url.toString())

        val fillerEpisodes = if (preferences.markFillers || preferences.hideFillers) {
            resolveFillerEpisodes(anilistId, providers, preferredProvider)
        } else {
            emptySet()
        }

        val availableProviders = providers.keys().asSequence().toList()
        val primaryProvider = if (providers.optJSONObject(preferredProvider)?.optJSONObject("episodes") != null) {
            preferredProvider
        } else {
            availableProviders.firstOrNull { key ->
                providers.optJSONObject(key)?.optJSONObject("episodes") != null
            } ?: return emptyList()
        }

        val crossProviderMap = mutableMapOf<Float, MutableMap<String, MutableMap<String, String>>>()
        val episodeMetaMap = mutableMapOf<Float, Pair<Double, String>>()
        val providerSubTypesMap = mutableMapOf<String, List<String>>()

        for (providerKey in availableProviders) {
            val providerData = providers.optJSONObject(providerKey) ?: continue
            val episodesObj = providerData.optJSONObject("episodes") ?: continue
            val subTypes = episodesObj.keys().asSequence().toList()
            providerSubTypesMap[providerKey] = subTypes

            for (subType in subTypes) {
                val typeEpisodes = episodesObj.optJSONArray(subType) ?: continue
                for (i in 0 until typeEpisodes.length()) {
                    val epJson = typeEpisodes.getJSONObject(i)
                    val number = epJson.optDouble("number", 0.0).toFloat()
                    val id = epJson.optString("id", "")
                    val title = epJson.optString("title", "")

                    val providerEpIds = crossProviderMap.getOrPut(number) { mutableMapOf() }
                        .getOrPut(providerKey) { mutableMapOf<String, String>() }
                    providerEpIds[subType] = id

                    if (number !in episodeMetaMap) {
                        episodeMetaMap[number] = epJson.optDouble("number", 0.0) to title
                    }
                }
            }
        }

        val episodes = mutableListOf<SEpisode>()
        val seenNumbers = mutableSetOf<Float>()

        val providersToProcess = mutableListOf<String>()
        providersToProcess.add(primaryProvider)
        val providerOrder = getProviderOrder()
        for (providerKey in providerOrder) {
            if (providerKey != primaryProvider && providerKey in availableProviders) {
                providersToProcess.add(providerKey)
            }
        }
        for (providerKey in availableProviders) {
            if (providerKey != primaryProvider && providerKey !in providersToProcess) {
                providersToProcess.add(providerKey)
            }
        }

        for (providerKey in providersToProcess) {
            if (providerKey != primaryProvider) {
                if (mergeAcrossProviders && episodes.isEmpty()) continue
                if (!mergeAcrossProviders && episodes.isNotEmpty()) break
            }

            crossProviderMap.entries
                .filter { it.value.containsKey(providerKey) }
                .sortedBy { it.key }
                .forEach { (number, providerEpMap) ->
                    if (seenNumbers.add(number)) {
                        val (rawNumber, title) = episodeMetaMap[number] ?: return@forEach
                        val fallbackProviders = providerEpMap.filterKeys { it != providerKey }
                        episodes.add(
                            buildMergedEpisode(
                                rawNumber, title, providerKey, preferredSubType,
                                providerEpMap[providerKey] ?: emptyMap(),
                                providerSubTypesMap[providerKey] ?: emptyList(),
                                fillerEpisodes, showProvider,
                                fallbackProviders, providerSubTypesMap, anilistId,
                            ),
                        )
                    }
                }
        }

        val airingSchedule = if (anilistId != null && anilistId > 0) fetchAiringSchedule(anilistId) else emptyMap()

        val result = if (preferences.episodeSortOrder == "ascending") episodes else episodes.reversed()
        for (ep in result) {
            airingSchedule[ep.episode_number]?.let { ep.date_upload = it }
        }
        return if (preferences.hideFillers) {
            result.filter { !it.scanlator.orEmpty().contains("Filler") }
        } else {
            result
        }
    }

    private fun buildMergedEpisode(
        number: Double,
        title: String,
        provider: String,
        preferredSubType: String,
        subTypeIds: Map<String, String>,
        allSubTypes: List<String>,
        fillerEpisodes: Set<Float>,
        showProvider: Boolean = true,
        fallbackProviders: Map<String, Map<String, String>> = emptyMap(),
        providerSubTypesMap: Map<String, List<String>> = emptyMap(),
        anilistId: Int? = null,
    ): SEpisode {
        val defaultSubType = subTypeIds.keys.firstOrNull { it == preferredSubType }
            ?: allSubTypes.firstOrNull { it in subTypeIds }
            ?: subTypeIds.keys.first()
        val episodeId = subTypeIds[defaultSubType] ?: ""

        val episodeIdObj = JSONObject().apply {
            put("episodeId", episodeId)
            put("provider", provider)
            put("defaultSubType", defaultSubType)
            put("subTypes", JSONObject(subTypeIds))
            if (fallbackProviders.isNotEmpty()) {
                val fallbackObj = JSONObject()
                for ((fbProvider, fbSubTypes) in fallbackProviders) {
                    fallbackObj.put(fbProvider, JSONObject(fbSubTypes))
                }
                put("fallbackProviders", fallbackObj)
            }
            val fbProviderSubTypes = JSONObject()
            for ((fbProvider, fbSubTypeList) in providerSubTypesMap) {
                if (fbProvider == provider || fbProvider !in fallbackProviders) continue
                val arr = JSONArray()
                fbSubTypeList.forEach { arr.put(it) }
                fbProviderSubTypes.put(fbProvider, arr)
            }
            if (fbProviderSubTypes.length() > 0) {
                put("fallbackProviderSubTypes", fbProviderSubTypes)
            }
            anilistId?.let { put("anilistId", it) }
        }

        val allAvailableSubTypes = mutableSetOf<String>()
        allAvailableSubTypes.addAll(subTypeIds.keys)
        for ((_, fbSubTypes) in fallbackProviders) {
            allAvailableSubTypes.addAll(fbSubTypes.keys)
        }

        val scanlatorLabel = allAvailableSubTypes
            .filter { it in SCANLATOR_SUB_TYPES }
            .sortedBy { SUB_TYPE_DISPLAY_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .joinToString(", ") { formatSubTypeLabel(it) }

        val isFiller = fillerEpisodes.contains(number.toFloat())

        val providerLabel = buildString {
            append(providerDisplayName(provider))
            if (fallbackProviders.isNotEmpty()) {
                fallbackProviders.keys.forEach { fbKey ->
                    append(", ")
                    append(providerDisplayName(fbKey))
                }
            }
        }

        return SEpisode.create().apply {
            episode_number = number.toFloat()
            name = if (title.isNotEmpty()) "Episode ${number.toInt()}: $title" else "Episode ${number.toInt()}"
            setUrlWithoutDomain(episodeIdObj.toString())
            scanlator = buildString {
                if (showProvider) append("$providerLabel \u2022 ")
                append(scanlatorLabel)
                if (isFiller) append(" \u2022 Filler")
            }
        }
    }

    // ============================ Video Links ============================

    private class AnimeMeta(
        val anilistId: Int,
        var malId: Int? = null,
        @Volatile var fillerEpisodes: Set<Float>? = null,
        @Volatile var airingSchedule: Map<Float, Long>? = null,
    )

    private val animeMetaCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Int, AnimeMeta>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnimeMeta>?): Boolean = size > 16
        },
    )

    private fun getOrCreateMeta(anilistId: Int, malId: Int? = null): AnimeMeta = synchronized(animeMetaCache) {
        animeMetaCache.getOrPut(anilistId) { AnimeMeta(anilistId, malId) }
    }

    private fun getMeta(anilistId: Int): AnimeMeta? = synchronized(animeMetaCache) {
        animeMetaCache[anilistId]
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeData = JSONObject(episode.url)
        val query = buildPipeQuery(
            "episodeId" to episodeData.getString("episodeId"),
            "provider" to episodeData.getString("provider"),
            "category" to episodeData.getString("defaultSubType"),
            "_ep" to episode.url,
        )
        return buildPipeRequest("sources", "GET", query = query)
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeData = extractEpisodeDataFromPipeRequest(response.request.url.toString())
        val provider = episodeData?.optString("provider", "") ?: ""
        val subTypesObj = episodeData?.optJSONObject("subTypes")
        val defaultSubType = episodeData?.optString("defaultSubType", "sub") ?: "sub"
        val fallbackProviders = episodeData?.optJSONObject("fallbackProviders")
        val fallbackProviderSubTypes = episodeData?.optJSONObject("fallbackProviderSubTypes")

        val videos = mutableListOf<Video>()
        var primaryFailed = false

        // Phase 1: primary provider — retry transient errors, skip permanent failures
        val primaryResponse = if (response.code in TRANSIENT_RETRY_CODES) {
            Log.w("Miruro", "Primary stream returned ${response.code}, retrying...")
            response.close()
            runBlocking { extractor.safePipeApiCall(response.request, maxRetries = 3) }
        } else if (response.code == 444) {
            Log.w("Miruro", "Primary provider $provider returned 444 \u2014 falling back")
            response.close()
            primaryFailed = true
            extractor.recordProviderFailure(provider)
            null
        } else {
            response
        }

        if (!primaryFailed && primaryResponse != null) {
            try {
                videos.addAll(extractor.parseStreamsFromResponse(primaryResponse, defaultSubType, provider))
                if (videos.isNotEmpty()) {
                    extractor.recordProviderSuccess(provider)
                }
            } catch (e: Exception) {
                Log.e("Miruro", "Failed to parse primary stream ($provider/$defaultSubType): ${e.message}")
                primaryFailed = true
                extractor.recordProviderFailure(provider)
            } finally {
                primaryResponse.close()
            }
        }

        // Phase 2: alternate sub-types from primary provider
        if (!primaryFailed && preferences.includeAllSubTypes && subTypesObj != null && subTypesObj.length() > 1) {
            val requests = mutableListOf<Pair<String, Request>>()
            for (subTypeKey in subTypesObj.keys()) {
                if (subTypeKey == defaultSubType) continue
                val episodeId = subTypesObj.optString(subTypeKey, "")
                if (episodeId.isEmpty()) continue

                val query = buildPipeQuery(
                    "episodeId" to episodeId,
                    "provider" to provider,
                    "category" to subTypeKey,
                )
                requests.add(subTypeKey to buildPipeRequest("sources", "GET", query = query))
            }

            videos.addAll(
                requests.parallelCatchingFlatMapBlocking { (subTypeKey, request) ->
                    extractor.safePipeApiCall(request).use { resp ->
                        extractor.parseStreamsFromResponse(resp, subTypeKey, provider)
                    }
                },
            )
        }

        // Phase 3: fallback providers — try on primary failure OR empty results
        if ((primaryFailed || videos.isEmpty()) && fallbackProviders != null) {
            runBlocking {
                val preferredSubType = preferences.preferredSubType
                val fbProvidersJson = buildFallbackProvidersJson(fallbackProviders)
                val fbSubTypesJson = fallbackProviderSubTypes?.let { buildFallbackSubTypesJson(it) }

                val fallbackVideos = extractor.fetchFallbackVideos(
                    provider = provider,
                    fallbackProviders = fbProvidersJson,
                    fallbackProviderSubTypes = fbSubTypesJson,
                    preferredSubType = preferredSubType,
                    prefProviderValues = getProviderOrder(),
                ) { path, method, query ->
                    val jsonQuery = org.json.JSONObject(query.toString())
                    buildPipeRequest(path, method, query = jsonQuery)
                }
                videos.addAll(fallbackVideos)
            }
        }

        if (videos.isEmpty()) {
            Log.w("Miruro", "All providers failed for episode, returning empty video list")
        }

        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.preferredQuality
        val subTypeLabel = formatSubTypeLabel(preferences.preferredSubType)
        val providerName = providerDisplayName(preferences.preferredProvider)
        val streamFormat = preferences.preferredStreamFormat

        val qualityInt = quality.toIntOrNull() ?: 0
        val formatLabel = if (streamFormat != "all") streamFormat.uppercase() else null

        return sortedWith(
            compareByDescending<Video> { formatLabel == null || it.quality.contains(formatLabel) }
                .thenByDescending { it.quality.contains(providerName) }
                .thenByDescending { it.quality.contains(subTypeLabel) }
                .thenByDescending {
                    val q = QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    when {
                        q == qualityInt -> 100000
                        q > 0 -> q
                        it.quality.contains(quality) -> 99999
                        else -> 0
                    }
                },
        )
    }

    // ============================== URL ==============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/watch/${anime.url}"

    override fun getEpisodeUrl(episode: SEpisode): String {
        val episodeData = try {
            JSONObject(episode.url)
        } catch (e: Exception) {
            Log.w("Miruro", "Failed to parse episode URL data: ${e.message}")
            return baseUrl
        }
        val anilistId = episodeData.optInt("anilistId", 0)
        return if (anilistId > 0) "$baseUrl/watch/$anilistId" else baseUrl
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = MiruroFilters.FILTER_LIST

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_MIRROR_KEY,
            title = PREF_MIRROR_TITLE,
            entries = MIRROR_ENTRIES,
            entryValues = MIRROR_VALUES,
            default = PREF_MIRROR_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            siteConfig = null
        }

        screen.addPreference(
            screen.getListPreference(
                key = PREF_PROVIDER_KEY,
                title = PREF_PROVIDER_TITLE,
                entries = PREF_PROVIDER_ENTRIES,
                entryValues = PREF_PROVIDER_VALUES,
                default = PREF_PROVIDER_DEFAULT,
                summary = providerDisplayName(preferences.preferredProvider),
                onChange = { pref, value ->
                    pref.summary = providerDisplayName(value)
                    true
                },
            ),
        )

        screen.addListPreference(
            key = PREF_SUB_TYPE_KEY,
            title = PREF_SUB_TYPE_TITLE,
            entries = PREF_SUB_TYPE_ENTRIES,
            entryValues = PREF_SUB_TYPE_VALUES,
            default = PREF_SUB_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_STREAM_FORMAT_KEY,
            title = PREF_STREAM_FORMAT_TITLE,
            entries = PREF_STREAM_FORMAT_ENTRIES,
            entryValues = PREF_STREAM_FORMAT_VALUES,
            default = PREF_STREAM_FORMAT_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_STYLE_KEY,
            title = PREF_TITLE_STYLE_TITLE,
            entries = PREF_TITLE_STYLE_ENTRIES,
            entryValues = PREF_TITLE_STYLE_VALUES,
            default = PREF_TITLE_STYLE_DEFAULT,
            summary = "%s",
        )

        val markFillersPref = screen.getSwitchPreference(
            key = PREF_MARK_FILLERS_KEY,
            default = PREF_MARK_FILLERS_DEFAULT,
            title = PREF_MARK_FILLERS_TITLE,
            summary = "Requires fetching episode data from Anilist, which may take some time.",
            enabled = !preferences.hideFillers,
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_FILLERS_KEY,
            title = PREF_HIDE_FILLERS_TITLE,
            default = PREF_HIDE_FILLERS_DEFAULT,
            summary = "Hides filler episodes from the episode list.",
            onComplete = { newValue ->
                markFillersPref.setEnabled(!newValue)
            },
        )

        screen.addPreference(markFillersPref)

        screen.addSwitchPreference(
            key = PREF_INCLUDE_ALL_SUB_TYPES_KEY,
            title = PREF_INCLUDE_ALL_SUB_TYPES_TITLE,
            default = PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT,
            summary = "When disabled, only fetches streams for the preferred sub type.",
        )

        screen.addSwitchPreference(
            key = PREF_STRIP_HTML_KEY,
            title = PREF_STRIP_HTML_TITLE,
            default = PREF_STRIP_HTML_DEFAULT,
            summary = "Strips HTML tags from anime descriptions.",
        )

        screen.addSwitchPreference(
            key = PREF_MERGE_PROVIDERS_KEY,
            title = PREF_MERGE_PROVIDERS_TITLE,
            default = PREF_MERGE_PROVIDERS_DEFAULT,
            summary = "Adds episodes from other providers that are missing from the preferred provider.",
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY,
            title = PREF_SHOW_PROVIDER_IN_SCANLATOR_TITLE,
            default = PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT,
            summary = "Shows the provider name in the episode scanlator field.",
        )

        screen.addListPreference(
            key = PREF_EPISODE_SORT_KEY,
            title = PREF_EPISODE_SORT_TITLE,
            entries = PREF_EPISODE_SORT_ENTRIES,
            entryValues = PREF_EPISODE_SORT_VALUES,
            default = PREF_EPISODE_SORT_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DESCRIPTION_TRUNCATE_KEY,
            title = PREF_DESCRIPTION_TRUNCATE_TITLE,
            entries = PREF_DESCRIPTION_TRUNCATE_ENTRIES,
            entryValues = PREF_DESCRIPTION_TRUNCATE_VALUES,
            default = PREF_DESCRIPTION_TRUNCATE_DEFAULT,
            summary = "%s",
        )
    }

    // ============================== Helpers ==============================

    private fun truncateDescription(description: String): String {
        val limit = preferences.descriptionTruncation.toIntOrNull() ?: 0
        if (limit <= 0 || description.length <= limit) return description
        val cutIndex = description.lastIndexOf(' ', limit)
        return if (cutIndex > limit * 2 / 3) {
            description.substring(0, cutIndex) + "\u2026"
        } else {
            description.substring(0, limit) + "\u2026"
        }
    }

    private fun formatSubTypeLabel(subType: String): String = when (subType) {
        "sub" -> "Sub"
        "dub" -> "Dub"
        "ssub" -> "Soft Sub"
        "h-sub" -> "Hard Sub"
        "embed" -> "Embed"
        else -> subType.replaceFirstChar { it.uppercase() }
    }

    private fun buildFallbackProvidersJson(fallbackProviders: JSONObject): kotlinx.serialization.json.JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for (key in fallbackProviders.keys()) {
            val subObj = fallbackProviders.optJSONObject(key) ?: continue
            val subMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            for (subKey in subObj.keys()) {
                subMap[subKey] = kotlinx.serialization.json.JsonPrimitive(subObj.optString(subKey, ""))
            }
            map[key] = kotlinx.serialization.json.JsonObject(subMap)
        }
        return kotlinx.serialization.json.JsonObject(map)
    }

    private fun buildFallbackSubTypesJson(fallbackProviderSubTypes: JSONObject): kotlinx.serialization.json.JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for (key in fallbackProviderSubTypes.keys()) {
            val arr = fallbackProviderSubTypes.optJSONArray(key) ?: continue
            val elements = (0 until arr.length()).map {
                kotlinx.serialization.json.JsonPrimitive(arr.getString(it))
            }
            map[key] = kotlinx.serialization.json.JsonArray(elements)
        }
        return kotlinx.serialization.json.JsonObject(map)
    }

    // ============================== Filler ===============================

    private fun resolveFillerEpisodes(anilistId: Int?, providers: JSONObject, preferredProvider: String): Set<Float> {
        if (anilistId == null) return emptySet()

        val meta = getMeta(anilistId)
        if (meta != null && meta.anilistId == anilistId && meta.fillerEpisodes != null) {
            return meta.fillerEpisodes!!
        }

        val existing = meta?.takeIf { it.anilistId == anilistId }
        val malId = existing?.malId
            ?: fetchMalId(anilistId)

        if (existing != null) {
            existing.malId = malId
        } else if (malId != null) {
            getOrCreateMeta(anilistId, malId)
        }

        if (malId == null) {
            existing?.let { it.fillerEpisodes = emptySet() }
            return emptySet()
        }

        val maxEp = findMaxEpisodeNumber(providers, preferredProvider)
        val fillers = fetchFillerEpisodes(malId, maxEp)

        getMeta(anilistId)?.let { it.fillerEpisodes = fillers }
        return fillers
    }

    private fun findMaxEpisodeNumber(providers: JSONObject, preferredProvider: String): Float {
        val providerData = providers.optJSONObject(preferredProvider) ?: return 0f
        val episodesObj = providerData.optJSONObject("episodes") ?: return 0f
        var max = 0f
        for (key in episodesObj.keys()) {
            val arr = episodesObj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val num = arr.optJSONObject(i)?.optDouble("number", 0.0)?.toFloat() ?: continue
                if (num > max) max = num
            }
        }
        return max
    }

    private fun anilistMalIdRequest(anilistId: Int): Request {
        val query = """
            query media(${'$'}id: Int, ${'$'}type: MediaType) {
                Media(id: ${'$'}id, type: ${'$'}type) {
                    idMal
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        val body = FormBody.Builder()
            .add("query", query)
            .add("variables", kotlinx.serialization.json.Json.encodeToString(variables))
            .build()
        return POST(ANILIST_GRAPHQL_URL, body = body)
    }

    private fun fetchMalId(anilistId: Int): Int? = try {
        anilistClient.newCall(anilistMalIdRequest(anilistId)).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("Miruro", "Anilist MAL ID request failed: ${response.code}")
                return null
            }
            response.parseAs<AnilistMalIdResponse>().data.media.idMal
        }
    } catch (e: Exception) {
        Log.e("Miruro", "Failed to resolve MAL ID: ${e.message}")
        null
    }

    private fun anilistAiringScheduleRequest(anilistId: Int): Request {
        val query = """
            query media(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    nextAiringEpisode { episode airingAt }
                    airingSchedule(notYetAired: false) {
                        nodes { episode airingAt }
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
        }
        val body = FormBody.Builder()
            .add("query", query)
            .add("variables", kotlinx.serialization.json.Json.encodeToString(variables))
            .build()
        return POST(ANILIST_GRAPHQL_URL, body = body)
    }

    private fun fetchAiringSchedule(anilistId: Int): Map<Float, Long> {
        val existing = anilistId.takeIf { it > 0 }?.let { getMeta(it) }?.takeIf { it.anilistId == anilistId }
        if (existing?.airingSchedule != null) return existing.airingSchedule!!

        val result = try {
            anilistClient.newCall(anilistAiringScheduleRequest(anilistId)).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("Miruro", "Anilist airing schedule request failed: ${response.code}")
                    return emptyMap()
                }
                response.body.string()
            }
        } catch (e: Exception) {
            Log.e("Miruro", "Failed to fetch airing schedule: ${e.message}")
            return emptyMap()
        }

        val jsonObj = try {
            JSONObject(result)
        } catch (e: Exception) {
            Log.w("Miruro", "Failed to parse airing schedule JSON: ${e.message}")
            return emptyMap()
        }
        val mediaObj = jsonObj.optJSONObject("data")?.optJSONObject("Media") ?: return emptyMap()

        val schedule = mutableMapOf<Float, Long>()

        mediaObj.optJSONObject("nextAiringEpisode")?.let { next ->
            val ep = next.optInt("episode", -1)
            val airingAt = next.optLong("airingAt", 0)
            if (ep > 0 && airingAt > 0) {
                schedule[ep.toFloat()] = airingAt * 1000L
            }
        }

        val nodes = mediaObj.optJSONObject("airingSchedule")?.optJSONArray("nodes") ?: return schedule
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val ep = node.optInt("episode", -1)
            val airingAt = node.optLong("airingAt", 0)
            if (ep > 0 && airingAt > 0 && !schedule.containsKey(ep.toFloat())) {
                schedule[ep.toFloat()] = airingAt * 1000L
            }
        }

        getMeta(anilistId)?.let { it.airingSchedule = schedule }
        return schedule
    }

    private fun fetchFillerEpisodes(malId: Int, maxEpisode: Float = Float.MAX_VALUE): Set<Float> {
        val fillerEpisodes = mutableSetOf<Float>()
        var page = 1
        var hasNextPage = true
        val maxPages = 10

        while (hasNextPage && page <= maxPages) {
            val result = try {
                jikanClient.newCall(
                    GET("$JIKAN_API_URL/anime/$malId/episodes?page=$page"),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("Miruro", "Jikan episodes request failed: ${response.code}")
                        break
                    }
                    response.parseAs<JikanEpisodesDto>()
                }
            } catch (e: Exception) {
                Log.e("Miruro", "Failed to fetch/parse Jikan episodes: ${e.message}")
                break
            }

            for (ep in result.data) {
                val num = ep.number.toFloat()
                if (num > maxEpisode) {
                    hasNextPage = false
                    break
                }
                if (ep.filler) {
                    fillerEpisodes.add(num)
                }
            }

            if (hasNextPage) {
                hasNextPage = result.pagination.hasNextPage
                page++
            }
        }

        return fillerEpisodes
    }

    // ============================== Pipe API ===============================

    private fun extractAnilistIdFromPipeRequest(url: String): Int? {
        return try {
            val encoded = url.substringAfter("e=", "")
            if (encoded.isEmpty()) return null
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = JSONObject(String(decoded, Charsets.UTF_8))
            val query = payload.optJSONObject("query") ?: return null
            query.optInt("anilistId", -1).takeIf { it > 0 }
        } catch (e: Exception) {
            Log.d("Miruro", "Failed to extract anilistId from pipe URL: ${e.message}")
            null
        }
    }

    private fun extractEpisodeDataFromPipeRequest(url: String): JSONObject? {
        return try {
            val encoded = url.substringAfter("e=", "")
            if (encoded.isEmpty()) return null
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = JSONObject(String(decoded, Charsets.UTF_8))
            val query = payload.optJSONObject("query") ?: return null
            val epDataStr = query.optString("_ep", "")
            if (epDataStr.isEmpty()) return null
            JSONObject(epDataStr)
        } catch (e: Exception) {
            Log.d("Miruro", "Failed to extract episode data from pipe URL: ${e.message}")
            null
        }
    }

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
        val encoded = Base64.encodeToString(jsonBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

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

    private fun buildPipeQuery(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((key, value) in pairs) {
            if (value == null) continue
            when (value) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is JSONArray -> put(key, value)
                is JSONObject -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun stripHtml(input: String): String = input
        .replace(BR_REGEX, "\n")
        .replace(CLOSE_P_REGEX, "\n")
        .replace(HTML_TAG_REGEX, "")
        .trim()

    private fun validateResponse(response: Response): Response {
        val code = response.code
        if (code == 444) {
            response.close()
            throw IOException("Provider does not have this content")
        }
        if (code >= 500) {
            response.close()
            throw IOException("Series not yet available (HTTP $code)")
        }
        return response
    }

    private fun extractCoverImage(coverImage: Any?): String = when (coverImage) {
        is JSONObject -> coverImage.optString("extraLarge", "")
            .ifEmpty { coverImage.optString("large", "") }
            .ifEmpty { coverImage.optString("medium", "") }
        is String -> coverImage
        else -> ""
    }

    private fun extractBannerImage(bannerImage: Any?): String = when (bannerImage) {
        is String -> bannerImage
        else -> ""
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

    private fun parseAnimeListResponse(
        response: Response,
        fallbackKeys: List<String> = emptyList(),
    ): AnimesPage {
        val json = response.use { extractor.decryptResponse(it) }

        var hasNextPage = false
        val mediaArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            val pageInfo = jsonObj.optJSONObject("pageInfo")
            hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false
            jsonObj.optJSONArray("media")
                ?: fallbackKeys.firstNotNullOfOrNull { jsonObj.optJSONArray(it) }
                ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until mediaArray.length()).map { i ->
            parseAnimeFromMediaObj(mediaArray.getJSONObject(i))
        }

        if (!hasNextPage && animeList.size >= 20) {
            hasNextPage = true
        }

        return AnimesPage(animeList, hasNextPage)
    }

    private fun resolveTitle(titleObj: JSONObject, style: String): String {
        val fallbackChain = when (style) {
            "romaji" -> listOf("romaji", "userPreferred", "english", "native")
            "english" -> listOf("english", "romaji", "userPreferred", "native")
            "native" -> listOf("native", "userPreferred", "romaji", "english")
            else -> listOf("userPreferred", "romaji", "english", "native")
        }
        return fallbackChain.firstNotNullOfOrNull { key ->
            titleObj.optString(key, "")
                .takeIf { it.isNotBlank() && it != "null" }
        } ?: ""
    }

    private fun resolveTitleFromDto(titleDto: AnimeMediaDto.TitleDto?, style: String): String {
        if (titleDto == null) return ""
        val fallbackChain = when (style) {
            "romaji" -> listOf(titleDto.romaji, titleDto.userPreferred, titleDto.english, titleDto.native)
            "english" -> listOf(titleDto.english, titleDto.romaji, titleDto.userPreferred, titleDto.native)
            "native" -> listOf(titleDto.native, titleDto.userPreferred, titleDto.romaji, titleDto.english)
            else -> listOf(titleDto.userPreferred, titleDto.romaji, titleDto.english, titleDto.native)
        }
        return fallbackChain.firstNotNullOfOrNull { it?.ifEmpty { null } } ?: ""
    }

    private fun parseAnimeFromMediaObj(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitle(titleObj, titleStyle)

        val id = media.optInt("id", 0).toString()
        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))

        return SAnime.create().apply {
            this.title = title.ifBlank { "Unknown" }
            thumbnail_url = thumbnail.ifEmpty { bannerImage }
            setUrlWithoutDomain(id)
        }
    }
}
