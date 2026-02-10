package eu.kanade.tachiyomi.animeextension.en.anilist

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AniList :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniList"

    override val baseUrl = "https://anilist.co"

    private val apiUrl = "https://graphql.anilist.co"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimitHost("https://api.jikan.moe".toHttpUrl(), 1)
        .build()

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val mappings by lazy {
        client.newCall(
            GET("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json", headers),
        ).execute().parseAs<List<Mapping>>()
    }

    // ============================== Popular ===============================

    private fun createSortRequest(
        sort: String,
        page: Int,
        extraVar: Pair<String, String>? = null,
    ): Request {
        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", PER_PAGE)
            put("sort", sort)
            put("type", "ANIME")
            extraVar?.let { put(extraVar.first, extraVar.second) }
            if (!preferences.allowAdult) put("isAdult", false)
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getSortQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun popularAnimeRequest(page: Int): Request = createSortRequest("TRENDING_DESC", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val titleLang = preferences.titleLang
        val page = response.parseAs<PagesResponse>().data.page
        val hasNextPage = page.pageInfo.hasNextPage
        val animeList = page.media.map { it.toSAnime(titleLang) }

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = createSortRequest("START_DATE_DESC", page, Pair("status", "RELEASING"))

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)

        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", PER_PAGE)
            put("sort", params.sort)
            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") {
                    params.genres.forEach { add(it) }
                }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") {
                    params.format.forEach { add(it) }
                }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                throw Exception("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                put("seasonYear", params.year)
            }

            if (params.status.isNotBlank()) {
                put("status", params.status)
            }

            if (params.country.isNotBlank()) {
                put("countryOfOrigin", params.country)
            }

            put("type", "ANIME")
            if (!preferences.allowAdult) put("isAdult", false)
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getSortQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val currentTime = System.currentTimeMillis() / 1000L
        val lastRefresh = lastRefreshed.getOrDefault(anime.url, 0L)

        val newAnime = if (currentTime - lastRefresh < refreshInterval) {
            anime.apply {
                thumbnail_url = coverList[coverIndex]
                coverIndex = (coverIndex + 1) % coverList.size
            }
        } else {
            super.getAnimeDetails(anime)
        }
        lastRefreshed[anime.url] = currentTime
        return newAnime
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val variablesObject = buildJsonObject {
            put("id", anime.url.toInt())
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getDetailsQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    private var coverList = emptyList<String>()
    private var coverIndex = 0
    private var currentAnime = ""
    private val lastRefreshed = mutableMapOf<String, Long>()
    private val episodeListMap = mutableMapOf<String, List<SEpisode>>()
    private val refreshInterval = 15

    private val coverProviders by lazy { CoverProviders(client, headers) }

    override fun animeDetailsParse(response: Response): SAnime {
        val titleLang = preferences.titleLang
        val animeData = response.parseAs<DetailsResponse>().data.media
        val anime = animeData.toSAnime(titleLang)

        if (currentAnime != anime.url) {
            currentAnime = ""
            val type = if (animeData.format == "MOVIE") "movies" else "tv"

            val data = mappings.firstOrNull {
                it.anilistId == anime.url.toInt()
            }
            val malId = data?.malId?.toString()
            val tvdbId = data?.thetvdbId?.toString()

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
        val lastRefresh = lastRefreshed.getOrDefault(anime.url, 0L)

        val episodeList = if (currentTime - lastRefresh < refreshInterval) {
            episodeListMap.getOrDefault(anime.url, emptyList())
        } else {
            super.getEpisodeList(anime)
        }

        episodeListMap[anime.url] = episodeList
        return episodeList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val variablesObject = buildJsonObject {
            put("id", anime.url.toInt())
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getMalIdQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<AnilistToMalResponse>().data.media
        if (data.status == "NOT_YET_RELEASED") {
            return emptyList()
        }

        val malId = data.idMal
        val anilistId = data.id

        val episodeData = client.newCall(anilistEpisodeRequest(anilistId)).execute()
            .parseAs<AniListEpisodeResponse>().data.media
        val episodeCount = episodeData.nextAiringEpisode?.episode?.minus(1)
            ?: episodeData.episodes ?: 0

        if (malId != null) {
            val episodeList = try {
                getFromMal(malId, episodeCount)
            } catch (e: Exception) {
                Log.e("Anilist-Ext", "Failed to get episodes from mal: ${e.message}")
                null
            }

            if (episodeList != null) {
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

    private fun anilistEpisodeRequest(anilistId: Int): Request {
        val variablesObject = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getEpisodeQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    private fun getSingleEpisodeFromMal(malId: Int): List<SEpisode> {
        val animeData = client.newCall(
            GET("https://api.jikan.moe/v4/anime/$malId", headers),
        ).execute().parseAs<JikanAnimeDto>().data

        return listOf(
            SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1F
                date_upload = parseDate(animeData.aired.from)
                url = "1"
            },
        )
    }

    private fun getFromMal(malId: Int, episodeCount: Int): List<SEpisode> {
        val markFillers = preferences.markFiller
        val episodeList = mutableListOf<SEpisode>()

        var hasNextPage = true
        var page = 1
        while (hasNextPage) {
            val data = client.newCall(
                GET("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page", headers),
            ).execute().parseAs<JikanEpisodesDto>()

            if (data.pagination.lastPage == 1 && data.data.isEmpty()) {
                return getSingleEpisodeFromMal(malId)
            }

            episodeList.addAll(
                data.data.map { ep ->
                    val airedOn = ep.aired?.let { parseDate(it) } ?: -1L
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

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    companion object {
        private val SANITY_REGEX by lazy { Regex("""^Ep. \d+ - (Episode \d+)${'$'}""") }

        private const val PER_PAGE = 20

        private const val MARK_FILLERS_KEY = "preferred_mark_fillers"
        private const val MARK_FILLERS_DEFAULT = true

        private const val PREF_ALLOW_ADULT_KEY = "preferred_allow_adult"
        private const val PREF_ALLOW_ADULT_DEFAULT = false

        private const val PREF_TITLE_LANG_KEY = "preferred_title"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
    }

    private val SharedPreferences.markFiller
        get() = getBoolean(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)

    private val SharedPreferences.allowAdult
        get() = getBoolean(PREF_ALLOW_ADULT_KEY, PREF_ALLOW_ADULT_DEFAULT)

    private val SharedPreferences.titleLang
        get() = getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(MARK_FILLERS_DEFAULT)
        }.also(screen::addPreference)
    }
}
