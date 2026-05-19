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
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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
    private val SharedPreferences.episodeSortOrder by preferences.delegate(PREF_EPISODE_SORT_KEY, PREF_EPISODE_SORT_DEFAULT)
    private val SharedPreferences.descriptionTruncation by preferences.delegate(PREF_DESCRIPTION_TRUNCATE_KEY, PREF_DESCRIPTION_TRUNCATE_DEFAULT)

    companion object {
        const val PREFIX_SEARCH = "miruro:"

        private val PIPE_KEY = "71951034f8fbcf53d89db52ceb3dc22c"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private const val PREF_PROVIDER_KEY = "preferred_provider"
        private const val PREF_PROVIDER_TITLE = "Preferred Provider"
        private val PREF_PROVIDER_ENTRIES = listOf("Kiwi", "Telli", "Bee", "Bun", "Hop", "Ally", "Dune", "Nun", "Kuz")
        private val PREF_PROVIDER_VALUES = listOf("kiwi", "telli", "bee", "bun", "hop", "ally", "dune", "nun", "kuz")
        private const val PREF_PROVIDER_DEFAULT = "kiwi"

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

        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        private const val JIKAN_API_URL = "https://api.jikan.moe/v4"

        // Mirror domains are fetched from https://miruro.com/
        private const val PREF_MIRROR_KEY = "preferred_mirror"
        private const val PREF_MIRROR_TITLE = "Preferred mirror"
        private val MIRROR_ENTRIES = listOf("miruro.tv", "miruro.to", "miruro.bz", "miruro.ru")
        private val MIRROR_VALUES = MIRROR_ENTRIES.map { "https://www.$it" }
        private val PREF_MIRROR_DEFAULT = MIRROR_VALUES.first()
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
                JSONObject(response.use(::decryptResponse))
            }

            val media = jsonObj.optJSONObject("media") ?: jsonObj

            val id = media.optInt("id", 0)
            val malId = media.optInt("idMal", 0).takeIf { it > 0 }
            if (id > 0) cachedAnimeMeta = AnimeMeta(id, malId)

            val anime = parseAnimeFromMedia(media)
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
        if (params.tags.isNotEmpty()) {
            val tagsArray = JSONArray()
            params.tags.forEach { tagsArray.put(it) }
            queryParams.put("tag", tagsArray)
        }

        return buildPipeRequest("search/browse", "GET", query = queryParams)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response, fallbackKeys = listOf("results", "data"))

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = buildPipeRequest("info/${anime.url}", "GET")

    override fun animeDetailsParse(response: Response): SAnime {
        val jsonObj = JSONObject(response.use(::decryptResponse))
        val media = jsonObj.optJSONObject("media") ?: jsonObj
        val titleObj = media.optJSONObject("title") ?: JSONObject()

        val anilistId = media.optInt("id", 0)
        val malId = media.optInt("idMal", 0).takeIf { it > 0 }
        if (anilistId > 0) {
            val existing = cachedAnimeMeta
            if (existing != null && existing.anilistId == anilistId) {
                if (malId != null) existing.malId = malId
            } else {
                cachedAnimeMeta = AnimeMeta(anilistId, malId)
            }
        }

        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitle(titleObj, titleStyle)

        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))
        val coverUrl = thumbnail.ifEmpty { bannerImage }

        val description = if (preferences.stripHtml) {
            media.optString("description", "")
                .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
                .replace("</p>".toRegex(RegexOption.IGNORE_CASE), "\n")
                .replace("<[^>]+>".toRegex(), "")
                .trim()
        } else {
            media.optString("description", "")
        }

        val truncatedDescription = preferences.descriptionTruncation.toIntOrNull()?.let { limit ->
            if (limit > 0 && description.length > limit) {
                val cutIndex = description.lastIndexOf(' ', limit)
                if (cutIndex > limit * 2 / 3) {
                    description.substring(0, cutIndex) + "…"
                } else {
                    description.substring(0, limit) + "…"
                }
            } else {
                description
            }
        } ?: description

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
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        val studio = extractMainStudio(media.opt("studios"))

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
        val anilistId = anime.url.toInt()
        currentAnilistId = anilistId
        val query = buildPipeQuery(
            "anilistId" to anilistId,
        )
        return buildPipeRequest("episodes", "GET", query = query)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonObj = JSONObject(response.use(::decryptResponse))

        val providers = jsonObj.optJSONObject("providers") ?: return emptyList()
        val preferredProvider = preferences.preferredProvider
        val preferredSubType = preferences.preferredSubType
        val mergeAcrossProviders = preferences.mergeAcrossProviders

        val anilistId = currentAnilistId ?: extractAnilistIdFromPipeRequest(response.request.url.toString())

        val fillerEpisodes = if (preferences.markFillers || preferences.hideFillers) {
            resolveFillerEpisodes(anilistId, providers, preferredProvider)
        } else {
            emptySet()
        }

        // Determine which provider to use as primary — auto-fallback if preferred has no episodes
        val availableProviders = providers.keys().asSequence().toList()
        val primaryProvider = if (providers.optJSONObject(preferredProvider)?.optJSONObject("episodes") != null) {
            preferredProvider
        } else {
            availableProviders.firstOrNull { key ->
                providers.optJSONObject(key)?.optJSONObject("episodes") != null
            } ?: return emptyList()
        }

        val episodes = mutableListOf<SEpisode>()
        val providerData = providers.optJSONObject(primaryProvider)
        if (providerData != null) {
            episodes.addAll(parseEpisodesFromProvider(providerData, primaryProvider, preferredSubType, fillerEpisodes))
        }

        if (mergeAcrossProviders && episodes.isNotEmpty()) {
            val preferredNumbers = episodes.map { it.episode_number }.toSet()
            for (providerKey in availableProviders) {
                if (providerKey == primaryProvider) continue
                val otherProviderData = providers.optJSONObject(providerKey) ?: continue
                val otherEpisodes = parseEpisodesFromProvider(otherProviderData, providerKey, preferredSubType, fillerEpisodes)
                for (ep in otherEpisodes) {
                    if (ep.episode_number !in preferredNumbers) {
                        episodes.add(ep)
                    }
                }
            }
        } else if (episodes.isEmpty()) {
            // Primary provider had no usable episodes — try remaining providers
            for (providerKey in availableProviders) {
                if (providerKey == primaryProvider) continue
                val otherProviderData = providers.optJSONObject(providerKey) ?: continue
                val otherEpisodes = parseEpisodesFromProvider(otherProviderData, providerKey, preferredSubType, fillerEpisodes)
                if (otherEpisodes.isNotEmpty()) {
                    episodes.addAll(otherEpisodes)
                    if (!mergeAcrossProviders) break
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

    private fun parseEpisodesFromProvider(
        providerData: JSONObject,
        provider: String,
        preferredSubType: String,
        fillerEpisodes: Set<Float> = emptySet(),
    ): List<SEpisode> {
        val episodesObj = providerData.optJSONObject("episodes") ?: return emptyList()

        val subTypes = when (provider) {
            "kiwi" -> listOf("sub", "dub")
            "bee" -> listOf("ssub", "sub", "dub")
            "telli" -> listOf("sub", "dub")
            "bun" -> listOf("sub", "dub")
            "hop" -> listOf("sub", "dub")
            "ally" -> listOf("sub", "dub")
            "dune" -> listOf("sub")
            "nun" -> listOf("sub", "dub")
            "kuz" -> listOf("sub", "dub")
            else -> listOf("sub", "dub")
        }

        val episodeMap = mutableMapOf<Float, MutableMap<String, String>>()
        val episodeMeta = mutableMapOf<Float, Pair<Double, String>>()

        for (subType in subTypes) {
            val typeEpisodes = episodesObj.optJSONArray(subType) ?: continue
            for (i in 0 until typeEpisodes.length()) {
                val epJson = typeEpisodes.getJSONObject(i)
                val number = epJson.optDouble("number", 0.0).toFloat()
                val id = epJson.optString("id", "")
                val title = epJson.optString("title", "")

                episodeMap.getOrPut(number) { mutableMapOf() }[subType] = id
                if (number !in episodeMeta) {
                    episodeMeta[number] = epJson.optDouble("number", 0.0) to title
                }
            }
        }

        if (episodeMap.isEmpty()) return emptyList()

        return episodeMap.keys.mapNotNull { number ->
            val subTypeIds = episodeMap[number] ?: return@mapNotNull null
            val (rawNumber, title) = episodeMeta[number] ?: return@mapNotNull null
            buildMergedEpisode(rawNumber, title, provider, preferredSubType, subTypeIds, subTypes, fillerEpisodes)
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
        }

        val scanlatorLabel = when (defaultSubType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            "h-sub" -> "Hard Sub"
            "embed" -> "Embed"
            else -> defaultSubType.replaceFirstChar { it.uppercase() }
        }

        val isFiller = fillerEpisodes.contains(number.toFloat())

        val providerLabel = provider.replaceFirstChar { it.uppercase() }

        return SEpisode.create().apply {
            episode_number = number.toFloat()
            name = if (title.isNotEmpty()) "Episode ${number.toInt()}: $title" else "Episode ${number.toInt()}"
            setUrlWithoutDomain(episodeIdObj.toString())
            scanlator = "$providerLabel • $scanlatorLabel" + if (isFiller) " • Filler" else ""
        }
    }

    // ============================ Video Links ============================

    @Volatile
    private var currentEpisodeData: JSONObject? = null

    @Volatile
    private var currentAnilistId: Int? = null

    private data class AnimeMeta(
        val anilistId: Int,
        var malId: Int? = null,
        var fillerEpisodes: Set<Float>? = null,
        var airingSchedule: Map<Float, Long>? = null,
    )

    @Volatile
    private var cachedAnimeMeta: AnimeMeta? = null

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeData = JSONObject(episode.url)
        currentEpisodeData = episodeData
        val query = buildPipeQuery(
            "episodeId" to episodeData.getString("episodeId"),
            "provider" to episodeData.getString("provider"),
            "category" to episodeData.getString("defaultSubType"),
        )
        return buildPipeRequest("sources", "GET", query = query)
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeData = currentEpisodeData
        val provider = episodeData?.optString("provider", "") ?: ""
        val subTypesObj = episodeData?.optJSONObject("subTypes")
        val defaultSubType = episodeData?.optString("defaultSubType", "sub") ?: "sub"

        val videos = mutableListOf<Video>()

        try {
            videos.addAll(parseStreamsFromResponse(response, defaultSubType))
        } catch (e: Exception) {
            Log.e("Miruro", "Failed to parse primary stream ($provider/$defaultSubType): ${e.message}")
        }

        if (!preferences.includeAllSubTypes || subTypesObj == null || subTypesObj.length() <= 1) return videos

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
                client.newCall(request).awaitSuccess().use { resp ->
                    parseStreamsFromResponse(resp, subTypeKey)
                }
            },
        )

        return videos
    }

    private fun parseStreamsFromResponse(response: Response, subType: String?): List<Video> {
        val json = try {
            response.use(::decryptResponse)
        } catch (e: Exception) {
            Log.e("Miruro", "Failed to decrypt stream response: ${e.message}")
            return emptyList()
        }
        val jsonObj = JSONObject(json)
        val streamsArray = jsonObj.optJSONArray("streams") ?: return emptyList()

        val subTypeLabel = when (subType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            null -> null
            else -> subType.replaceFirstChar { it.uppercase() }
        }

        val videos = mutableListOf<Video>()

        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.getJSONObject(i)
            val type = stream.optString("type", "")
            if (type != "hls") continue

            val url = stream.optString("url", "")
            if (url.isEmpty()) continue

            val qualityStr = stream.optString("quality", "")
            val quality = qualityStr.toIntOrNull() ?: 0
            val resolution = stream.optJSONObject("resolution")
            val width = resolution?.optInt("width", 0) ?: 0
            val height = resolution?.optInt("height", 0) ?: 0
            val codec = stream.optString("codec", "")
            val audio = stream.optString("audio", "")
            val fansub = stream.optString("fansub", "")
            val referer = stream.optString("referer", "https://kwik.cx/")

            val qualityLabel = buildString {
                append("${quality}p")
                if (subTypeLabel != null) append(" $subTypeLabel")
                if (width > 0 && height > 0) append(" - ${width}x$height")
                if (codec.isNotEmpty()) append(" $codec")
                if (audio.isNotEmpty()) append(" $audio")
                if (fansub.isNotEmpty()) append(" $fansub")
            }

            val videoHeaders = headers.newBuilder().set("Referer", referer).build()
            videos.add(Video(url, qualityLabel, url, videoHeaders))
        }

        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.preferredQuality
        val subTypeLabel = when (preferences.preferredSubType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            else -> preferences.preferredSubType.replaceFirstChar { it.uppercase() }
        }

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(subTypeLabel) }
                .thenByDescending {
                    val q = Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    when {
                        q == quality.toIntOrNull() -> 100000
                        q > 0 -> q
                        it.quality.contains(quality) -> 99999
                        else -> 0
                    }
                },
        )
    }

    // ============================== URL ==============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/watch/${anime.url}"

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
        }

        screen.addListPreference(
            key = PREF_PROVIDER_KEY,
            title = PREF_PROVIDER_TITLE,
            entries = PREF_PROVIDER_ENTRIES,
            entryValues = PREF_PROVIDER_VALUES,
            default = PREF_PROVIDER_DEFAULT,
            summary = "%s",
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

    // ============================== Filler ===============================

    private fun resolveFillerEpisodes(anilistId: Int?, providers: JSONObject, preferredProvider: String): Set<Float> {
        if (anilistId == null) return emptySet()

        val meta = cachedAnimeMeta
        if (meta != null && meta.anilistId == anilistId && meta.fillerEpisodes != null) {
            return meta.fillerEpisodes!!
        }

        val existing = meta?.takeIf { it.anilistId == anilistId }
        val malId = existing?.malId
            ?: fetchMalId(anilistId)

        if (existing != null) {
            existing.malId = malId
        } else if (malId != null) {
            cachedAnimeMeta = AnimeMeta(anilistId, malId)
        }

        if (malId == null) {
            existing?.let { it.fillerEpisodes = emptySet() }
            return emptySet()
        }

        val maxEp = findMaxEpisodeNumber(providers, preferredProvider)
        val fillers = fetchFillerEpisodes(malId, maxEp)

        cachedAnimeMeta?.takeIf { it.anilistId == anilistId }?.let { it.fillerEpisodes = fillers }
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
        val query = $$"""
        query media($id: Int, $type: MediaType) {
            Media(id: $id, type: $type) {
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
        anilistClient.newCall(anilistMalIdRequest(anilistId)).execute()
            .parseAs<AnilistMalIdResponse>().data.media.idMal
    } catch (e: Exception) {
        Log.e("Miruro", "Failed to resolve MAL ID: ${e.message}")
        null
    }

    private fun anilistAiringScheduleRequest(anilistId: Int): Request {
        val query = $$"""
            query media($id: Int) {
                Media(id: $id, type: ANIME) {
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
        val existing = cachedAnimeMeta?.takeIf { it.anilistId == anilistId }
        if (existing?.airingSchedule != null) return existing.airingSchedule!!

        val result = try {
            anilistClient.newCall(anilistAiringScheduleRequest(anilistId)).execute()
                .body.string()
        } catch (e: Exception) {
            Log.e("Miruro", "Failed to fetch airing schedule: ${e.message}")
            return emptyMap()
        }

        val jsonObj = try {
            JSONObject(result)
        } catch (_: Exception) {
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

        cachedAnimeMeta?.takeIf { it.anilistId == anilistId }?.let { it.airingSchedule = schedule }
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
                ).execute()
                    .parseAs<JikanEpisodesDto>()
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
        } catch (_: Exception) {
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
                else -> put(key, value.toString())
            }
        }
    }

    private fun decryptResponse(response: Response): String {
        val obfuscated = response.header("x-obfuscated") ?: "1"
        val bodyBytes = response.body.bytes()

        val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
        if (obfuscated != "2") {
            return bodyStr
        }

        if (bodyStr.isEmpty()) {
            throw Exception("Empty response from server")
        }

        return try {
            val decoded = Base64.decode(bodyStr, Base64.URL_SAFE)
            val data = decoded.mapIndexed { i, b ->
                (b.toInt() xor PIPE_KEY[i % PIPE_KEY.size].toInt()).toByte()
            }.toByteArray()

            GZIPInputStream(java.io.ByteArrayInputStream(data)).use { gzipStream ->
                gzipStream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            throw Exception("Failed to decrypt response from server: ${e.message}")
        }
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
        val json = response.use(::decryptResponse)

        val mediaArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONArray("media")
                ?: fallbackKeys.firstNotNullOfOrNull { jsonObj.optJSONArray(it) }
                ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until mediaArray.length()).map { i ->
            parseAnimeFromMedia(mediaArray.getJSONObject(i))
        }

        return AnimesPage(animeList, animeList.size >= 20)
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

    private fun parseAnimeFromMedia(media: JSONObject): SAnime {
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

@Serializable
class AnilistMalIdResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            @SerialName("idMal") val idMal: Int? = null,
        )
    }
}

@Serializable
class JikanEpisodesDto(
    val data: List<JikanEpisodeDataDto>,
    val pagination: JikanPaginationDto,
) {
    @Serializable
    class JikanEpisodeDataDto(
        @SerialName("mal_id") val number: Int,
        val filler: Boolean,
    )

    @Serializable
    class JikanPaginationDto(
        @SerialName("has_next_page") val hasNextPage: Boolean,
    )
}
