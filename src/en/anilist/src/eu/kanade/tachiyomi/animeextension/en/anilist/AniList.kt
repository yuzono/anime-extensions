package eu.kanade.tachiyomi.animeextension.en.anilist

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal val MAL_API_URLS = listOf(
    "https://api.tenrai.org/v1",
    "https://api.jikan.moe/v4",
    "https://jikanfortheweebs.midnightignite.me/v4",
)

class AniList :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniList"

    override val baseUrl = "https://anilist.co"

    private val apiUrl = "https://graphql.anilist.co"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    override val client = network.client.newBuilder()
        .addInterceptor(::authInterceptor)
        .addInterceptor(::rateLimitBackoffInterceptor)
        .rateLimit(85, 1.minutes, 700.milliseconds) { it.host == "graphql.anilist.co" }
        .rateLimit(1, 1.seconds) {
            it.host == "api.tenrai.org" ||
                it.host == "api.jikan.moe" ||
                it.host == "jikanfortheweebs.midnightignite.me"
        }
        .build()

    private val json: Json by lazy {
        Json(Injekt.get<Json>()) {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }

    private val preferences by getPreferencesLazy()

    @Volatile
    private var cachedMappings: List<Mapping>? = null

    private fun getMappings(): List<Mapping> {
        cachedMappings?.let { return it }

        return try {
            client.newCall(
                GET("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json", headers),
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.parseAs<List<Mapping>>().also { cachedMappings = it }
            }
        } catch (e: Exception) {
            Log.e("AniList", "Failed to fetch anime mappings: ${e.message}")
            emptyList()
        }
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authToken = preferences.getString(PREF_AUTH_TOKEN_KEY, "")?.trim().orEmpty()

        if (authToken.isNotBlank() && request.url.toString().startsWith(apiUrl)) {
            val token = if (authToken.startsWith("Bearer", ignoreCase = true)) authToken else "Bearer $authToken"
            val newRequest = request.newBuilder()
                .header("Authorization", token)
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(request)
    }

    private fun rateLimitBackoffInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 429) {
            val currentSec = System.currentTimeMillis() / 1000L
            val retry = response.header("Retry-After")?.toIntOrNull() ?: -1
            var reset = response.header("X-RateLimit-Reset")?.toLongOrNull() ?: 0L
            if (reset > 10_000_000_000L) {
                reset /= 1000L
            }
            val waitFromReset = if (reset > currentSec) (reset - currentSec).toInt() else 0
            val actualWait = when {
                retry > 0 -> retry
                waitFromReset > 0 -> waitFromReset
                else -> 60
            }

            response.close()
            throw IOException("AniList rate limit exceeded. Please wait $actualWait seconds before retrying.")
        }

        return response
    }

    // ============================== Popular ===============================

    private fun createSortRequest(
        sort: String,
        page: Int,
        status: String? = null,
    ): Request {
        val variables = SortVariables(
            page = page,
            perPage = PER_PAGE,
            sort = listOf(sort),
            type = "ANIME",
            status = status,
            isAdult = if (!preferences.allowAdult) false else null,
        )
        return graphQLPost(
            apiUrl,
            headers,
            query = getSortQuery(),
            variables = variables,
            json = json,
        )
    }

    override fun popularAnimeRequest(page: Int): Request = createSortRequest("TRENDING_DESC", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.parseGraphQLAs<PagesResponse.PagesData>(json).page
        val titleLang = preferences.titleLang
        val animeList = page.media.mapNotNull { it.toSAnimeOrNull(titleLang) }
        return AnimesPage(animeList, page.pageInfo.hasNextPage)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = client.newCall(popularAnimeRequest(page)).awaitSuccess().use(::popularAnimeParse)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = createSortRequest("START_DATE_DESC", page, "RELEASING")

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = client.newCall(latestUpdatesRequest(page)).awaitSuccess().use(::latestUpdatesParse)

    // =============================== Search ===============================

    @Volatile
    private var cachedViewerUsername: String? = null

    @Volatile
    private var cachedViewerToken: String? = null

    private val personalListPageMappings = ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>>()
    private val personalListMutexes = ConcurrentHashMap<String, Mutex>()

    private suspend fun getOrFetchUsername(): String {
        val prefUsername = preferences.getString(PREF_USERNAME_KEY, "")?.trim().orEmpty()
        if (prefUsername.isNotBlank()) return prefUsername

        val authToken = preferences.getString(PREF_AUTH_TOKEN_KEY, "")?.trim().orEmpty()
        if (authToken.isBlank()) {
            throw Exception("Please set your AniList username or API token in extension settings to use personal lists.")
        }

        if (cachedViewerToken == authToken && cachedViewerUsername != null) {
            return cachedViewerUsername!!
        }

        val request = graphQLPost(
            apiUrl,
            headers,
            query = "{ Viewer { name } }",
            json = json,
        )
        val name = client.newCall(request).awaitSuccess().use { response ->
            response.parseGraphQLAs<ViewerResponse.ViewerData>(json).viewer?.name
        }

        if (!name.isNullOrBlank()) {
            cachedViewerUsername = name
            cachedViewerToken = authToken
            return name
        }

        throw Exception("Failed to resolve AniList username from provided API token.")
    }

    private suspend fun getPersonalListAnime(page: Int, listFilter: Filters.AniListListFilter, query: String = ""): AnimesPage {
        val username = getOrFetchUsername()
        val titleLang = preferences.titleLang
        val allowAdult = preferences.allowAdult
        val status = listFilter.getStatus()
        val cacheKey = "$username:${status ?: "ALL"}:$query"
        val mutex = personalListMutexes.getOrPut(cacheKey) { Mutex() }

        return mutex.withLock {
            val pageMap = personalListPageMappings.getOrPut(cacheKey) { ConcurrentHashMap() }
            if (page == 1) {
                pageMap.clear()
                pageMap[1] = 1
            }

            var currentAniListPage = pageMap[page] ?: page
            val animeList = mutableListOf<SAnime>()
            var hasNextPage = false

            while (animeList.isEmpty()) {
                val variables = PersonalListVariables(
                    userName = username,
                    type = "ANIME",
                    status = status,
                    page = currentAniListPage,
                    perPage = 50,
                )
                val request = graphQLPost(
                    apiUrl,
                    headers,
                    query = getPersonalListQuery(),
                    variables = variables,
                    json = json,
                )
                val personalListPage = client.newCall(request).awaitSuccess().use { response ->
                    response.parseGraphQLAs<PersonalListResponse.PersonalListData>(json).page
                }

                personalListPage.mediaList.forEach { entry ->
                    val media = entry.media ?: return@forEach
                    if (!allowAdult && media.isAdult) return@forEach
                    media.toSAnimeOrNull(titleLang)?.let(animeList::add)
                }

                hasNextPage = personalListPage.pageInfo.hasNextPage
                currentAniListPage++

                if (!hasNextPage) break
            }

            pageMap[page + 1] = currentAniListPage
            AnimesPage(animeList, hasNextPage)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)

        val effectiveYear = if (params.season.isNotBlank() && params.year.isBlank()) {
            Calendar.getInstance().get(Calendar.YEAR).toString()
        } else {
            params.year
        }

        val yearParam = if (params.season.isBlank() && effectiveYear.isNotBlank()) "$effectiveYear%" else null
        val seasonParam = if (params.season.isNotBlank() && effectiveYear.isNotBlank()) params.season else null
        val seasonYearParam = if (params.season.isNotBlank() && effectiveYear.isNotBlank()) effectiveYear.toIntOrNull() else null
        val sortParam = if (params.sort.isNotBlank()) listOf(params.sort) else null

        val variables = SearchVariables(
            page = page,
            perPage = PER_PAGE,
            sort = sortParam,
            type = "ANIME",
            search = query.trim().takeIf { it.isNotBlank() },
            genres = params.genres.takeIf { it.isNotEmpty() },
            format = params.format.takeIf { it.isNotEmpty() },
            year = yearParam,
            season = seasonParam,
            seasonYear = seasonYearParam,
            status = params.status.takeIf { it.isNotBlank() },
            countryOfOrigin = params.country.takeIf { it.isNotBlank() },
            isAdult = if (!preferences.allowAdult) false else null,
        )

        return graphQLPost(
            apiUrl,
            headers,
            query = getSortQuery(),
            variables = variables,
            json = json,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val listFilter = filters.firstOrNull { it is Filters.AniListListFilter } as? Filters.AniListListFilter
        if (listFilter != null && listFilter.isActive()) {
            return getPersonalListAnime(page, listFilter, query)
        }
        return client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess().use(::searchAnimeParse)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val currentTime = System.currentTimeMillis() / 1000L
        val lastRefresh = detailsLastRefreshed[anime.url] ?: 0L

        if (currentTime - lastRefresh < refreshInterval) {
            return anime.apply {
                if (coverList.isNotEmpty()) {
                    thumbnail_url = coverList[coverIndex]
                    coverIndex = (coverIndex + 1) % coverList.size
                }
            }
        }

        val freshAnime = client.newCall(animeDetailsRequest(anime)).awaitSuccess().use(::animeDetailsParse)
        detailsLastRefreshed[anime.url] = currentTime
        return freshAnime
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.toIntOrNull() ?: throw IllegalArgumentException("Invalid anime id: ${anime.url}")
        return graphQLPost(
            apiUrl,
            headers,
            query = getDetailsQuery(),
            variables = MediaVariables(id = id, type = "ANIME"),
            json = json,
        )
    }

    private var coverList = emptyList<String>()
    private var coverIndex = 0
    private var currentAnime = ""
    private val detailsLastRefreshed = mutableMapOf<String, Long>()
    private val episodesLastRefreshed = mutableMapOf<String, Long>()
    private val episodeListMap = mutableMapOf<String, List<SEpisode>>()
    private val refreshInterval = 15

    private val coverProviders by lazy { CoverProviders(client, headers) }

    override fun animeDetailsParse(response: Response): SAnime {
        val titleLang = preferences.titleLang
        val animeData = response.parseGraphQLAs<DetailsResponse.DetailsData>(json).media
        val anime = animeData.toSAnime(titleLang)

        if (currentAnime != anime.url) {
            currentAnime = ""
            val type = if (animeData.format == "MOVIE") "movies" else "tv"

            val animeId = anime.url.toIntOrNull()
            val mapping = getMappings().firstOrNull { it.anilistId == animeId }
            val malId = mapping?.malId?.toString()
            val tvdbId = mapping?.thetvdbId?.toString()

            coverList = buildList {
                add(anime.thumbnail_url ?: "")
                malId?.let { addAll(coverProviders.getMALCovers(malId)) }
                tvdbId?.let { addAll(coverProviders.getFanartCovers(tvdbId, type)) }
            }.filter { it.isNotEmpty() }

            currentAnime = anime.url
            coverIndex = 0
        }

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val currentTime = System.currentTimeMillis() / 1000L
        val lastRefresh = episodesLastRefreshed[anime.url] ?: 0L
        val cachedEpisodes = episodeListMap[anime.url]

        if (cachedEpisodes != null && (currentTime - lastRefresh < refreshInterval)) {
            return cachedEpisodes
        }

        val freshEpisodes = client.newCall(episodeListRequest(anime)).awaitSuccess().use(::episodeListParse)
        episodesLastRefreshed[anime.url] = currentTime
        episodeListMap[anime.url] = freshEpisodes
        return freshEpisodes
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.toIntOrNull() ?: throw IllegalArgumentException("Invalid anime id: ${anime.url}")
        return graphQLPost(
            apiUrl,
            headers,
            query = getMalIdQuery(),
            variables = MediaVariables(id = id, type = "ANIME"),
            json = json,
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseGraphQLAs<AnilistToMalResponse.DataObject>(json).media
        if (data.status == "NOT_YET_RELEASED") {
            return emptyList()
        }

        val malId = data.idMal
        val anilistId = data.id

        val episodeData = client.newCall(anilistEpisodeRequest(anilistId)).execute().use {
            it.parseGraphQLAs<AniListEpisodeResponse.DataObject>(json).media
        }
        val episodeCount = episodeData.nextAiringEpisode?.episode?.minus(1)
            ?: episodeData.episodes ?: 0

        if (malId != null) {
            val episodeList = try {
                getFromMal(malId, episodeCount)
            } catch (e: Exception) {
                Log.e("Anilist-Ext", "Failed to get episodes from mal: ${e.message}")
                null
            }

            if (!episodeList.isNullOrEmpty()) {
                return episodeList
            }
        }

        return List(episodeCount) {
            val epNumber = it + 1

            SEpisode.create().apply {
                name = "Episode $epNumber"
                episode_number = epNumber.toFloat()
                url = "$epNumber"
            }
        }.reversed()
    }

    private fun anilistEpisodeRequest(anilistId: Int): Request = graphQLPost(
        apiUrl,
        headers,
        query = getEpisodeQuery(),
        variables = MediaVariables(id = anilistId, type = "ANIME"),
        json = json,
    )

    private fun parseDate(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0L
        val cleanDate = dateString.trim()
        return try {
            val normalized = cleanDate
                .replace(REGEX_Z, "+0000")
                .replace(REGEX_TZ_OFFSET, "$1$2")
                .replace(REGEX_MILLIS, "$1")
            val parsed = DATE_FORMAT_TZ.tryParse(normalized)
            if (parsed != 0L) {
                parsed
            } else {
                val dateWithoutOffset = if (cleanDate.length >= 19) cleanDate.substring(0, 19) else cleanDate
                DATE_FORMAT_UTC.tryParse(dateWithoutOffset)
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun getSingleEpisodeFromMal(malId: Int): List<SEpisode> {
        for (baseUrl in MAL_API_URLS) {
            try {
                val animeData = client.newCall(
                    GET("$baseUrl/anime/$malId", headers),
                ).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.parseAs<JikanAnimeDto>().data
                } ?: continue

                return listOf(
                    SEpisode.create().apply {
                        name = "Episode 1"
                        episode_number = 1F
                        date_upload = parseDate(animeData.aired.from)
                        url = "1"
                    },
                )
            } catch (_: Exception) {
                // Try next mirror
            }
        }
        return emptyList()
    }

    private fun getFromMal(malId: Int, episodeCount: Int): List<SEpisode>? {
        var isSingleEpisodeAnime = false

        for (baseUrl in MAL_API_URLS) {
            try {
                val markFillers = preferences.markFiller
                val episodeList = mutableListOf<SEpisode>()

                var hasNextPage = true
                var page = 1
                while (hasNextPage) {
                    val data = client.newCall(
                        GET("$baseUrl/anime/$malId/episodes?page=$page", headers),
                    ).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} from $baseUrl on page $page")
                        }
                        response.parseAs<JikanEpisodesDto>()
                    }

                    if (data.pagination.lastPage == 1 && data.data.isEmpty()) {
                        isSingleEpisodeAnime = true
                        break
                    }

                    episodeList.addAll(
                        data.data.map { ep ->
                            val airedOn = parseDate(ep.aired)
                            val fullName = ep.title?.let { "Ep. ${ep.number} - $it" } ?: "Episode ${ep.number}"
                            val scanlatorText = if (markFillers && ep.filler) "Filler episode" else null

                            SEpisode.create().apply {
                                date_upload = airedOn
                                episode_number = ep.number.toFloat()
                                url = ep.number.toString()
                                name = SANITY_REGEX.replace(fullName) { m -> m.groupValues[1] }
                                scanlator = scanlatorText
                            }
                        },
                    )

                    hasNextPage = data.pagination.hasNextPage
                    page++
                }

                if (episodeList.isNotEmpty()) {
                    (episodeList.size + 1..episodeCount).forEach {
                        episodeList.add(
                            SEpisode.create().apply {
                                episode_number = it.toFloat()
                                url = "$it"
                                name = "Ep. $it"
                            },
                        )
                    }

                    return episodeList.filter { it.episode_number <= episodeCount }.sortedBy { -it.episode_number }
                }
            } catch (e: Exception) {
                Log.w("AniList", "Failed to get episodes from $baseUrl: ${e.message}")
            }
        }

        if (isSingleEpisodeAnime) {
            val singleEpisode = getSingleEpisodeFromMal(malId)
            if (singleEpisode.isNotEmpty()) return singleEpisode
        }

        return null
    }

    // ============================== Seasons ===============================

    override fun seasonListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw UnsupportedOperationException("AniList is a tracker, not a streaming service.")

    override suspend fun getVideoList(hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException("AniList is a tracker, not a streaming service.")

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun videoListRequest(hoster: Hoster): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    companion object {
        private val SANITY_REGEX by lazy { Regex("""^Ep. \d+ - (Episode \d+)$""") }
        private val REGEX_Z by lazy { Regex("Z$") }
        private val REGEX_TZ_OFFSET by lazy { Regex("([+-]\\d{2}):(\\d{2})$") }
        private val REGEX_MILLIS by lazy { Regex("\\.\\d+([+-]\\d{4})$") }

        private const val PER_PAGE = 20

        private const val PREF_USERNAME_KEY = "pref_anilist_username"
        private const val PREF_AUTH_TOKEN_KEY = "pref_anilist_auth_token"

        private const val MARK_FILLERS_KEY = "preferred_mark_fillers"
        private const val MARK_FILLERS_DEFAULT = true

        private const val PREF_ALLOW_ADULT_KEY = "preferred_allow_adult"
        private const val PREF_ALLOW_ADULT_DEFAULT = false

        private const val PREF_TITLE_LANG_KEY = "preferred_title"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"

        private val DATE_FORMAT_TZ by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        }
        private val DATE_FORMAT_UTC by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }

    private val SharedPreferences.markFiller
        get() = getBoolean(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)

    private val SharedPreferences.allowAdult
        get() = getBoolean(PREF_ALLOW_ADULT_KEY, PREF_ALLOW_ADULT_DEFAULT)

    private val SharedPreferences.titleLang
        get() = getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME_KEY
            title = "AniList Username"
            summary = "Enter your username to browse your public lists without an API token, or leave blank if using an API token."
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_AUTH_TOKEN_KEY
            title = "AniList API Token"
            summary = """
                Paste your API token to access your personal/private lists (username is automatically resolved).
                Generate token: Settings → Developer → New Client with Redirect URL:
                https://anilist.co/api/v2/oauth/pin
                Then open:
                https://anilist.co/api/v2/oauth/authorize?client_id=[CLIENT_ID]&response_type=token
            """.trimIndent()
            setDefaultValue("")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOW_ADULT_KEY
            title = "Allow adult content"
            setDefaultValue(PREF_ALLOW_ADULT_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred title language"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "english", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(MARK_FILLERS_DEFAULT)
        }.also(screen::addPreference)
    }
}
