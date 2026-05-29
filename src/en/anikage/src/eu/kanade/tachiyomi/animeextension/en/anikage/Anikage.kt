package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.catchingFlatMap
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc"

    override val lang: String = "en"

    override val supportsLatest: Boolean = true

    override val name: String = "Anikage"

    override val client = network.client.newBuilder()
        .rateLimit(5, 1L, TimeUnit.SECONDS)
        .build()

    private val preferences by getPreferencesLazy()

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
                if (searchParams.season != "ALL") put("season", searchParams.season)
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
        val soup = response.useAsJsoup()
        val studioTag = soup.selectFirst("div.flex.uppercase")
        val studioNameDiv = studioTag?.nextElementSibling()

        val englishName = soup.selectFirst("h1.text-center.tracking-tighter")?.text()?.takeIf(String::isNotBlank)
        val romajiName = soup.selectFirst("h2.text-center.line-clamp-2")?.text()?.takeIf(String::isNotBlank)

        val titleName = if (preferences.titleStyle == "english") {
            englishName ?: romajiName
        } else {
            romajiName ?: englishName
        }

        val authorName = studioNameDiv
            ?.select("span.cursor-default")
            ?.eachText()?.joinToString()

        val statusName = soup.selectFirst("span.uppercase.font-semibold")?.text()

        return SAnime.create().apply {
            titleName?.let { title = it }
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

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.split("/").last().toIntOrNull() ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")
        val token = makeToken(animeId)
        val getHeaders = headersBuilder()
            .add("Referer", "$baseUrl${anime.url}")
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        val episodeListRequest = GET(token.animeEpisodeBuilder(), headers = getHeaders)

        val episodesData = client.newCall(episodeListRequest)
            .awaitSuccess()
            .parseAs<List<EpisodeResult>>()

        val provider = if (preferences.subOrDub == "dub") {
            preferences.dubSource
        } else {
            preferences.subSource
        }

        val episode = episodesData.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = if (!it.title.isNullOrBlank()) {
                    "Episode ${it.number} - ${it.title}"
                } else {
                    "Episode ${it.number}"
                }
                date_upload = 0L
                url = animeEpisodeUrlFormat(
                    animeId,
                    provider,
                    it.number,
                    preferences.subOrDub,
                )
            }
        }
        return episode
    }

    // Video Links

    private fun videoListRequestUrl(episode: SEpisode, provider: String): String {
        val animeId = episode.url.substringAfterLast("/").substringBefore("?")

        val token = makeSourcesToken(
            animeId.toIntOrNull()!!,
            episode.episode_number.toInt(),
            provider,
        )

        return token.episodeUrlBuilder()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val providers = if (preferences.subOrDub == "dub") {
            DUB_PROVIDER
                .sortedBy { it.contains(preferences.dubSource) }
                .associateBy { "Dub" } +
                SUB_PROVIDER
                    .sortedBy { it.contains(preferences.subSource) }
                    .associateBy { "Sub" }
        } else {
            SUB_PROVIDER.sortedBy { it.contains(preferences.subSource) }
                .associateBy { "Sub" } +
                DUB_PROVIDER.sortedBy { it.contains(preferences.dubSource) }
                    .associateBy { "Dub" }
        }

        val getHeaders = headersBuilder()
            .add("Referer", episode.url)
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return providers.toList().catchingFlatMap { (type, provider) ->
            val episodeData = client.newCall(
                GET(videoListRequestUrl(episode, provider), getHeaders),
            )
                .awaitSuccess()
                .parseAs<EpisodeSource>()

            val providerHeaders = headers.newBuilder().apply {
                episodeData.headers.referer?.let { set("Referer", it) }
                episodeData.headers.origin?.let { set("Origin", it) }
                episodeData.headers.userAgent?.let { set("User-Agent", it) }
            }.build()

            val tracks = episodeData.subtitles.map {
                Track(it.url, it.lang)
            }

            episodeData.sources.map {
                Video(
                    url = it.url,
                    quality = "$type - $provider - ${it.quality}",
                    videoUrl = it.url,
                    subtitleTracks = tracks,
                    headers = providerHeaders,
                )
            }
        }
    }

    // Utils

    private fun Int.animeUrlBuilder(): String = "/anime/info/$this"
    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/anime/episodes/$this"
    private fun String.episodeUrlBuilder(): String = "$baseUrl/api/anime/sources/$this"

    private fun animeEpisodeUrlFormat(id: Int, host: String, episodeId: Int, type: String): String = "$baseUrl/anime/watch/$id?host=$host&ep=$episodeId&type=$type"

    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<GraphQLResult>()

        val media = jsonData.data.page.media

        val pageInfo = jsonData.data.page.pageInfo
        val hasNextPage = pageInfo.hasNextPage

        val animes = media.map {
            val id = it.id
            val titleFormat = preferences.titleStyle
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji
            } else {
                it.title.romaji
            }

            SAnime.create().apply {
                setUrlWithoutDomain(id.animeUrlBuilder())
                thumbnail_url = it.coverImage.extraLarge
                title = titleName
                description = it.description
                status = when (it.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                genre = it.genres.joinToString()
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    private fun makeSourcesToken(
        animeId: Int,
        ep: Int,
        provider: String,
        type: String = preferences.subOrDub,
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

    private fun makeToken(animeId: Int, refresh: Boolean = false): String {
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
        val payload = dataObject.toJsonRequestBody()

        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", ANILIST_API.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", "$ANILIST_API/")
        }.build()

        return POST(ANILIST_API, headers = postHeaders, body = payload)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SITE_TITLE_FORMAT,
            title = "Preferred Title Style",
            entries = listOf("english", "romaji"),
            entryValues = listOf("english", "romaji"),
            default = PREF_SITE_TITLE_DEFAULT,
            summary = "%s",
        )

        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Enable NSFW Content",
            summary = "Show adult content in search results and popular anime",
            default = PREF_ADULT_DEFAULT,
        )

        screen.addEditTextPreference(
            key = PREF_API_KEY,
            title = "API key",
            default = "",
            summary = "Private API key",
        )

        screen.addListPreference(
            key = PREF_ISSUBORDUB_SOURCE,
            title = "Sub or Dub?",
            entries = listOf("sub", "dub"),
            entryValues = listOf("sub", "dub"),
            default = PREF_ISSUBORDUB_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_SOURCE,
            title = "Preferred Sub Server",
            entries = SUB_PROVIDER,
            entryValues = SUB_PROVIDER,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DUB_SOURCE,
            title = "Preferred Dub Server",
            entries = DUB_PROVIDER,
            entryValues = DUB_PROVIDER,
            default = PREF_DUB_DEFAULT,
            summary = "%s",
        )
    }

    private val SharedPreferences.titleStyle
        get() = getString(PREF_SITE_TITLE_FORMAT, PREF_SITE_TITLE_DEFAULT)!!

    private val SharedPreferences.isAdult
        get() = getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)

    private val SharedPreferences.apiKey
        get() = getString(PREF_API_KEY, "")?.takeIf(String::isNotBlank) ?: PREF_API_DEFAULT

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)!!

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)!!

    companion object {

        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private const val PREF_API_KEY = "private_api_key"
        private const val PREF_API_DEFAULT = "x9f2k7m4q1w8e3r6t5y0"

        private val SUB_PROVIDER = listOf(
            "uwu",
            "beep",
            "mochi",
            "miku",
            "mimi",
            "vee",
            "kiwi",
            "yuki",

            "kami",
            "shiro",
            "wave",
            "zaza",
        )
        private val DUB_PROVIDER = listOf(
            "mochi",
            "miku",
            "mimi",
            "kiwi",
            "yuki",

            "uwu",
            "kami",
        )

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "uwu"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "miku"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
