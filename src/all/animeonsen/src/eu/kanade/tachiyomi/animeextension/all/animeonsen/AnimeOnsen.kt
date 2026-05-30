package eu.kanade.tachiyomi.animeextension.all.animeonsen

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.VideoData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeOnsen :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeOnsen"

    override val baseUrl = "https://www.animeonsen.xyz"

    private val apiUrl = "https://api.animeonsen.xyz/v4"

    override val lang = "all"

    override val supportsLatest = false

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(AOAPIInterceptor(network.client))
            .build()
    }

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", AO_USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-site")

    private val preferredTitle: String
        get() = preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT)!!

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$apiUrl/content/index?start=${(page - 1) * 30}&limit=30", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = response.parseAs<AnimeListResponse>()
        val animes = responseJson.content.map { it.toSAnime() }
        val hasNextPage = responseJson.cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimeOnsenFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // When there's search text, override filters and use the search endpoint
        if (query.isNotBlank()) {
            return GET("$apiUrl/search/$query", headers)
        }

        // No search text: use Genre filter
        val genre = filters.firstInstanceOrNull<AnimeOnsenFilters.GenreFilter>()?.getValue()

        return if (!genre.isNullOrBlank()) {
            // A genre is selected, reroute to /genre/slug endpoint
            GET("$apiUrl/content/index/genre/$genre", headers)
        } else {
            // "All" selected, fallback to standard index with pagination
            val start = (page - 1) * 30
            GET("$apiUrl/content/index?start=$start&limit=30", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()

        return if (requestUrl.contains("/search/") || requestUrl.contains("/genre/")) {
            val searchResult = response.parseAs<SearchResponse>().result
            val results = searchResult.map { it.toSAnime() }
            AnimesPage(results, false)
        } else {
            val responseJson = response.parseAs<AnimeListResponse>()
            val animes = responseJson.content.map { it.toSAnime() }
            val hasNextPage = responseJson.cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
            AnimesPage(animes, hasNextPage)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime) = GET("$apiUrl/content/${anime.url}/extensive", headers)

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/details/${anime.url}"

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val details = response.parseAs<AnimeDetails>()
        url = details.content_id
        title = when (preferredTitle) {
            "english" -> details.content_title_en ?: details.content_title!!
            else -> details.content_title ?: details.content_title_en!!
        }
        status = parseStatus(details.mal_data?.status)
        author = details.mal_data?.studios?.joinToString { it.name }
        genre = details.mal_data?.genres?.joinToString { it.name }
        description = details.mal_data?.synopsis
        thumbnail_url = "$apiUrl/image/210x300/${details.content_id}"
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = GET("$apiUrl/content/${anime.url}/episodes", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val contentId = response.request.url.toString().substringBeforeLast("/episodes")
            .substringAfterLast("/")
        val responseJson = response.parseAs<Map<String, EpisodeDto>>()
        return responseJson.map { (epNum, item) ->
            SEpisode.create().apply {
                url = "$contentId/video/$epNum"
                episode_number = epNum.toFloat()
                name = "Episode $epNum: ${item.name}"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val videoData = response.parseAs<VideoData>()
        val videoUrl = videoData.uri.stream
        val subtitleLangs = videoData.metadata.subtitles

        val subs = videoData.uri.subtitles.sortSubs().map { (langPrefix, subUrl) ->
            val language = subtitleLangs[langPrefix]!!
            Track(subUrl, language)
        }

        val video = Video(videoUrl, "Default (720p)", videoUrl, headers, subtitleTracks = subs)
        return listOf(video)
    }

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/content/${episode.url}", headers)

    override fun videoUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = PREF_TITLE_TITLE
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue(PREF_TITLE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()) {
        "finished_airing" -> SAnime.COMPLETED
        else -> SAnime.ONGOING
    }

    private fun AnimeListItem.toSAnime() = SAnime.create().apply {
        url = content_id
        title = when (preferredTitle) {
            "english" -> content_title_en ?: content_title!!
            else -> content_title ?: content_title_en!!
        }
        thumbnail_url = "$apiUrl/image/210x300/$content_id"
    }

    private fun Map<String, String>.sortSubs(): List<Map.Entry<String, String>> {
        val sub = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

        return entries.sortedWith(
            compareBy { it.key.contains(sub) },
        ).reversed()
    }
}

const val AO_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.3"

// Title Preferences
private const val PREF_TITLE_KEY = "preferred_title"
private const val PREF_TITLE_TITLE = "Preferred Title Language"
private const val PREF_TITLE_DEFAULT = "romanji"
private val PREF_TITLE_ENTRIES = arrayOf("Romanji", "English")
private val PREF_TITLE_VALUES = arrayOf("romanji", "english")

// Subtitle Preferences
private const val PREF_SUB_KEY = "preferred_subLang"
private const val PREF_SUB_TITLE = "Preferred sub language"
const val PREF_SUB_DEFAULT = "en-US"
private val PREF_SUB_ENTRIES = arrayOf(
    "العربية", "Deutsch", "English", "Español (Spain)",
    "Español (Latin)", "Français", "Italiano",
    "Português (Brasil)", "Русский",
)
private val PREF_SUB_VALUES = arrayOf(
    "ar-ME", "de-DE", "en-US", "es-ES",
    "es-LA", "fr-FR", "it-IT",
    "pt-BR", "ru-RU",
)
