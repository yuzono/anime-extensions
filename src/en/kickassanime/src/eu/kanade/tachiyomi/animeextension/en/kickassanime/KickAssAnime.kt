package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.AnimeInfoDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.LanguagesDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.PopularItemDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.PopularResponseDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.RecentsResponseDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.ServersDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors.KickAssAnimeExtractor
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
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class KickAssAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "KickAssAnime"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    private val apiUrl by lazy { "$baseUrl/api/show" }

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        clearBaseUrl()
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$apiUrl/popular?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<PopularResponseDto>()
        val animes = data.result.map(::popularAnimeFromObject)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val hasNext = data.page_count > page
        return AnimesPage(animes, hasNext)
    }

    private fun popularAnimeFromObject(anime: PopularItemDto): SAnime = SAnime.create().apply {
        val useEnglish = preferences.getBoolean(PREF_USE_ENGLISH_KEY, PREF_USE_ENGLISH_DEFAULT)
        title = when {
            anime.title_en.isNotBlank() && useEnglish -> anime.title_en
            else -> anime.title
        }
        setUrlWithoutDomain("/${anime.slug}")
        thumbnail_url = "$baseUrl/${anime.poster.url}"
    }

    // ============================== Episodes ==============================
    private fun episodeListRequest(anime: SAnime, page: Int, lang: String) = GET("$apiUrl${anime.url}/episodes?page=$page&lang=$lang")

    private fun getEpisodeResponse(anime: SAnime, page: Int, lang: String): EpisodeResponseDto = client.newCall(episodeListRequest(anime, page, lang))
        .execute()
        .parseAs()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        // Fetch what languages are available for this anime
        val languages = client.newCall(
            GET("$apiUrl${anime.url}/language"),
        ).awaitSuccess().parseAs<LanguagesDto>().result

        val prefLang = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT)!!
        val pref2ndLang = preferences.getString(PREF_AUDIO_LANG_KEY_2ND, PREF_AUDIO_LANG_DEFAULT_2ND)!!

        // Try preferred language first, then others
        val langOrder = languages
            .sortedWith(
                compareBy(
                    { it != prefLang },
                    { it != pref2ndLang },
                ),
            )

        var foundEpisodes: List<SEpisode>? = null

        for (lang in langOrder) {
            val firstResponse = withContext(Dispatchers.IO) {
                runCatching {
                    getEpisodeResponse(anime, 1, lang)
                }.getOrNull()
            }
            if (firstResponse == null || firstResponse.result.isEmpty()) continue

            val items = runCatching {
                val deferredPages = List(firstResponse.pages.drop(1).size) { idx ->
                    async(Dispatchers.IO) { getEpisodeResponse(anime, idx + 2, lang).result }
                }
                firstResponse.result + deferredPages.awaitAll().flatten()
            }.getOrNull()

            if (!items.isNullOrEmpty()) {
                foundEpisodes = items.map {
                    SEpisode.create().apply {
                        name = "Ep. ${it.episode_string} - ${it.title}"
                        url = "${anime.url}/ep-${it.episode_string}-${it.slug}"
                        episode_number = it.episode_string.toFloatOrNull() ?: 0F
                        scanlator = lang.getLocale()
                    }
                }.reversed()
                break
            }
        }

        // If nothing was found, return empty list
        foundEpisodes ?: emptyList()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        TODO("Not yet implemented")
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val url = apiUrl + episode.url.replace("/ep-", "/episode/ep-")
        return GET(url)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videos = response.parseAs<ServersDto>()
        val extractor = KickAssAnimeExtractor(client, json, headers)
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        val videoList = videos.servers.mapNotNull {
            if (!hosterSelection.contains(it.name)) return@mapNotNull null
            runCatching { extractor.videosFromUrl(it.src, it.name) }.getOrNull()
        }.flatten()

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList
    }

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime) = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime) = GET(apiUrl + anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val languages = client.newCall(
            GET("${response.request.url}/language"),
        ).execute().parseAs<LanguagesDto>()
        val anime = response.parseAs<AnimeInfoDto>()
        return SAnime.create().apply {
            val useEnglish = preferences.getBoolean(PREF_USE_ENGLISH_KEY, PREF_USE_ENGLISH_DEFAULT)
            title = when {
                anime.title_en.isNotBlank() && useEnglish -> anime.title_en
                else -> anime.title
            }
            setUrlWithoutDomain("/${anime.slug}")
            thumbnail_url = "$baseUrl/${anime.poster.url}"
            genre = anime.genres.joinToString()
            status = anime.status.parseStatus()
            description = buildString {
                anime.synopsis?.let { append(it + "\n\n") }
                append("Available Dub Languages: ${languages.result.joinToString(", ") { t -> t.getLocale() }}\n")
                append(
                    "Season: ${anime.season.replaceFirstChar {
                        if (it.isLowerCase()) {
                            it.titlecase(
                                Locale.ROOT,
                            )
                        } else {
                            it.toString()
                        }
                    }}\n",
                )
                append("Year: ${anime.year}")
            }
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    private fun searchAnimeParse(response: Response, page: Int): AnimesPage {
        val path = response.request.url.encodedPath
        return if (path.endsWith("api/fsearch") || path.endsWith("/anime")) {
            val data = response.parseAs<SearchResponseDto>()
            val animes = data.result.map(::popularAnimeFromObject)
            AnimesPage(animes, page < data.maxPage)
        } else if (path.endsWith("/recent")) {
            latestUpdatesParse(response)
        } else {
            popularAnimeParse(response)
        }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: KickAssAnimeFilters.FilterSearchParams): Request {
        val newHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", "$baseUrl/anime")
            .build()

        if (filters.subPage.isNotBlank()) return GET("$baseUrl/api/${filters.subPage}?page=$page", headers = newHeaders)

        val encodedFilters = if (filters.filters == "{}") "" else Base64.encodeToString(filters.filters.encodeToByteArray(), Base64.NO_WRAP)

        return if (query.isBlank()) {
            val url = buildString {
                append(baseUrl)
                append("/api/anime")
                append("?page=$page")
                if (encodedFilters.isNotEmpty()) append("&filters=$encodedFilters")
            }

            GET(url, headers = newHeaders)
        } else {
            val data = buildJsonObject {
                put("page", page)
                put("query", query)
                if (encodedFilters.isNotEmpty()) put("filters", encodedFilters)
            }.toString().toRequestBody("application/json".toMediaType())

            POST("$baseUrl/api/fsearch", body = data, headers = newHeaders)
        }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/api/show/$slug"))
                .awaitSuccess()
                .use(::searchAnimeBySlugParse)
        } else {
            val params = KickAssAnimeFilters.getSearchParameters(filters)
            return client.newCall(searchAnimeRequest(page, query, params))
                .awaitSuccess()
                .let { response ->
                    searchAnimeParse(response, page)
                }
        }
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = KickAssAnimeFilters.FILTER_LIST

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<RecentsResponseDto>()
        val animes = data.result.map(::popularAnimeFromObject)
        return AnimesPage(animes, data.hadNext)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$apiUrl/recent?type=all&page=$page")

    // ============================= Utilities ==============================

    private fun String.getLocale(): String = LOCALE.firstOrNull { it.first == this }?.second ?: ""

    private fun String.parseStatus() = when (this) {
        "finished_airing" -> SAnime.COMPLETED
        "currently_airing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(server, true) },
                { Regex("""([\d,]+) [KMGTPE]B/s""").find(it.quality)?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull() ?: 0F },
            ),
        ).reversed()
    }

    private fun SharedPreferences.clearBaseUrl(): SharedPreferences {
        if (getString(PREF_DOMAIN_KEY, "")!! in PREF_DOMAIN_ENTRY_VALUES) {
            return this
        }
        edit()
            .remove(PREF_DOMAIN_KEY)
            .putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            .apply()
        return this
    }

    companion object {
        private val SERVERS = arrayOf("VidStreaming", "DuckStream", "BirdStream")

        private val LOCALE = listOf(
            Pair("ja-JP", "Japanese"),
            Pair("en-US", "English"),
            Pair("es-ES", "Spanish (España)"),
            Pair("ko-KR", "Korean"),
        )

        const val PREFIX_SEARCH = "slug:"

        private const val PREF_USE_ENGLISH_KEY = "pref_use_english"
        private const val PREF_USE_ENGLISH_TITLE = "Use English titles"
        private const val PREF_USE_ENGLISH_SUMMARY = "Show Titles in English instead of Romanji when possible."
        private const val PREF_USE_ENGLISH_DEFAULT = false

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("240p", "360p", "480p", "720p", "1080p", "2160p")

        private const val PREF_AUDIO_LANG_KEY = "preferred_audio_lang"
        private const val PREF_AUDIO_LANG_TITLE = "Preferred audio language"
        private val PREF_AUDIO_LANG_DEFAULT = LOCALE[0].first

        private const val PREF_AUDIO_LANG_KEY_2ND = "preferred_audio_lang_2nd"
        private const val PREF_AUDIO_LANG_TITLE_2ND = "Secondary preferred audio language"
        private val PREF_AUDIO_LANG_DEFAULT_2ND = LOCALE[1].first

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private val PREF_SERVER_DEFAULT = SERVERS[0]
        private val PREF_SERVER_VALUES = SERVERS

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"

        // Check domains here: https://kickassanime.cx/
        private val PREF_DOMAIN_ENTRIES = arrayOf(
            "kickass-anime.ru",
            "kickass-anime.ro",
            "kaa.to",
            "kaa.rs",
            "kaa.si",
        )
        private val PREF_DOMAIN_ENTRY_VALUES = PREF_DOMAIN_ENTRIES.map { "https://$it" }.toTypedArray()
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRY_VALUES[0]

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_HOSTER_TITLE = "Enable/Disable Hosts"
        private val PREF_HOSTER_DEFAULT = SERVERS.toSet()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRY_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_ENGLISH_KEY
            title = PREF_USE_ENGLISH_TITLE
            summary = PREF_USE_ENGLISH_SUMMARY
            setDefaultValue(PREF_USE_ENGLISH_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_LANG_KEY
            title = PREF_AUDIO_LANG_TITLE
            entries = LOCALE.map { it.second }.toTypedArray()
            entryValues = LOCALE.map { it.first }.toTypedArray()
            setDefaultValue(PREF_AUDIO_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_LANG_KEY_2ND
            title = PREF_AUDIO_LANG_TITLE_2ND
            entries = LOCALE.map { it.second }.toTypedArray()
            entryValues = LOCALE.map { it.first }.toTypedArray()
            setDefaultValue(PREF_AUDIO_LANG_DEFAULT_2ND)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_VALUES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = SERVERS
            entryValues = SERVERS
            setDefaultValue(PREF_HOSTER_DEFAULT)
        }.also(screen::addPreference)
    }
}
