package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import kotlin.getValue

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc/"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val name: String = "Anikage"

    companion object {

        private const val PREF_SITE_DOMAIN_KEY = "preferred_site_domain"
        private const val PREF_SITE_DOMAIN_DEFAULT = "https://anikage.cc"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://graphql.anilist.co/"
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private const val PREF_API_KEY = "private_api_key"
        private const val PREF_API_DEFAULT = "x9f2k7m4q1w8e3r6t5y0"

        private val SUB_PROVIDER = arrayOf("uwu", "mochi", "mimi", "kami", "vee", "shiro", "wave", "zaza")
        private val DUB_PROVIDER = arrayOf("uwu", "mochi", "kami")

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "mochi"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "mochi"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
    }

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()

    private val apiUrl by lazy { preferences.apiUrl }

    override fun getFilterList(): AnimeFilterList = AnikageFilters.FILTER_LIST

    override fun popularAnimeRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "ANIME")
                put("page", page)
                put("sort", "TRENDING_DESC")
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }

        return buildPost(data)
    }

    override fun popularAnimeParse(response: Response) = parseAnime(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = AnikageFilters.getSearchParameters(filters)
        val data = buildJsonObject {
            putJsonObject("variables") {
                if (query != "") put("search", query)
                if (searchParams.origin != "ALL") put("countryOfOrigin", searchParams.origin)
                if (searchParams.types != "ALL") put("format_in", searchParams.types)
                if (searchParams.releaseYear != "ALL") put("seasonYear", searchParams.releaseYear.toInt())
                if (searchParams.genres.count() != 0) {
                    putJsonArray("genre_in") {
                        searchParams.genres.forEach {
                            add(it)
                        }
                    }
                }
                put("page", page)
                put("sort", searchParams.sortBy)
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }
        return buildPost(data)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "ANIME")
                put("page", page)
                put("sort", "ID_DESC")
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }
        return buildPost(data)
    }

    override fun latestUpdatesParse(response: Response) = parseAnime(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val soup = response.asJsoup()
        val studioTag = soup.selectFirst("div.flex.uppercase")
        val studioNameDiv = studioTag?.nextElementSibling()

        val authorName = studioNameDiv
            ?.select("span.cursor-default")
            ?.eachText()?.joinToString(", ").orEmpty()

        val statusName = soup.selectFirst("span.uppercase.font-semibold")?.text()

        return SAnime.create().apply {
            author = authorName
            update_strategy = if (statusName == "Finished") {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            status = when (statusName) {
                "Finished" -> SAnime.COMPLETED
                "Airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.split("/").last()
        val token = makeToken(id.toInt())
        val getHeaders = headersBuilder()
            .add("Referer", anime.url)
            .add("Origin", preferences.siteUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(token.animeEpisodeBuilder(), headers = getHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer").orEmpty()
        val animeId = referer.substringAfterLast("/")

        val provider = if (preferences.subOrDub == "dub")
            preferences.dubSource else preferences.subSource

        val jsonResponse = response.parseAs<List<EpisodeResult>>()
        val episode = jsonResponse.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = if(!it.title.isNullOrBlank()) {
                    "Episode ${it.number} - ${it.title}"
                } else {
                    "Episode ${it.number}"
                }
                date_upload = 0L
                url = animeEpisodeUrlFormat(
                    animeId.toInt(),
                    provider ?: "mochi",
                    it.number,
                    preferences.subOrDub ?: "sub",
                )
            }
        }
        return episode
    }

    // Video Links

    override fun videoListRequest(episode: SEpisode): Request {
        val animeId = episode.url.substringAfterLast("/").substringBefore("?")

        val provider = if (preferences.subOrDub == "dub")
            preferences.dubSource else preferences.subSource

        val token = makeSourcesToken(
            animeId.toInt(),
            episode.episode_number.toInt(),
            provider ?: "mochi",
        )

        val getHeaders = headersBuilder()
            .add("Referer", episode.url)
            .add("Origin", preferences.siteUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(token.episodeUrlBuilder(), headers = getHeaders)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).await()
        val jsonResponse = response.parseAs<EpisodeSource>()

        val getHeaders = headersBuilder()
            .add("Referer", episode.url)
            .add("Origin", preferences.siteUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        val tracks = jsonResponse.subtitles.map {
            Track(it.url, it.lang)
        }

        val videos = jsonResponse.sources.map {
            Video(
                url = it.url,
                quality = it.quality,
                videoUrl = it.url,
                subtitleTracks = tracks,
                headers = getHeaders,
            )
        }
        return videos
    }

    // Utils

    fun Int.animeUrlBuilder(): String = "anime/info/$this"
    fun String.animeEpisodeBuilder(): String = "${preferences.siteUrl}/api/anime/episodes/$this"
    fun String.episodeUrlBuilder(): String = "${preferences.siteUrl}/api/anime/sources/$this"

    fun animeEpisodeUrlFormat(id: Int, host: String, episodeId: Int, type: String): String =
            "${preferences.siteUrl}/anime/watch/$id?host=$host&ep=$episodeId&type=$type"

    fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<GraphQLResult>()

        val media = jsonData.data.page.media

        val pageInfo = jsonData.data.page.pageInfo
        val hasNextPage = pageInfo.hasNextPage

        val animes = media.map {
            val id = it.id

            SAnime.create().apply {
                url = id.animeUrlBuilder()
                thumbnail_url = it.coverImage.extraLarge
                title = it.title.english ?: it.title.romaji
                description = it.description
                status = when (it.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                genre = it.genres.joinToString(", ")
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_DOMAIN_KEY
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf(preferences.siteUrl)
            entryValues = arrayOf(preferences.siteUrl)
            setDefaultValue(PREF_SITE_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf(preferences.apiUrl)
            entryValues = arrayOf(preferences.apiUrl)
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = "Enable NSFW Content"
            setDefaultValue(PREF_ADULT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_KEY
            title = "API key (requires app restart)"
            setDefaultValue(PREF_API_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_ISSUBORDUB_SOURCE
            title = "Sub or Dub?"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_ISSUBORDUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_SOURCE
            title = "Preferred Sub Server"
            entries = SUB_PROVIDER
            entryValues = SUB_PROVIDER
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DUB_SOURCE
            title = "Preferred Dub Server"
            entries = DUB_PROVIDER
            entryValues = DUB_PROVIDER
            setDefaultValue(PREF_DUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    fun makeSourcesToken(
        animeId: Int,
        ep: Int,
        provider: String,
        type: String = preferences.subOrDub ?: "sub",
    ): String {
        val payload = JSONObject().apply {
            put("id", animeId)
            put("epNum", ep)
            put("host", provider)
            put("type", type)
            put("_t", (System.currentTimeMillis() / 1000).toString())
        }.toString()

        val raw = payload.toByteArray(Charsets.UTF_8)
        val key = preferences.apiKey.toByteArray()

        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return Base64.encodeToString(out, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    fun makeToken(animeId: Int, refresh: Boolean = false): String {
        val payload = JSONObject().apply {
            put("id", animeId)
            put("refresh", refresh.toString().lowercase())
            put("_t", (System.currentTimeMillis() / 1000).toString())
        }.toString()

        val raw = payload.toByteArray(Charsets.UTF_8)

        val out = ByteArray(raw.size)

        val key = preferences.apiKey.toByteArray()

        for (i in raw.indices) {
            out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return Base64.encodeToString(out, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    private fun buildPost(dataObject: JsonObject): Request {
        val payload = json.encodeToString(dataObject)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val siteUrl = preferences.siteUrl
        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", apiUrl.toHttpUrl().host)
            add("Origin", siteUrl)
            add("Referer", "$apiUrl/")
        }.build()

        return POST(apiUrl, headers = postHeaders, body = payload)
    }

    private val SharedPreferences.siteUrl
        get() = getString(PREF_SITE_DOMAIN_KEY, PREF_SITE_DOMAIN_DEFAULT)!!

    private val SharedPreferences.apiUrl
        get() = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private val SharedPreferences.isAdult
        get() = getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)

    private val SharedPreferences.apiKey
        get() = getString(PREF_API_KEY, PREF_API_DEFAULT)!!

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)
}
