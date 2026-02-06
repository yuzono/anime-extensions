package eu.kanade.tachiyomi.animeextension.en.kisskh

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class KissKH :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "KissKH"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private var subDecryptor by LazyMutable { SubDecryptor(client, headers, baseUrl) }

    override val supportsRelatedAnimes = false

    /* Popular Animes */

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonData: String): AnimesPage {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]?.jsonPrimitive?.int
        val page = jObject["page"]?.jsonPrimitive?.int
        val hasNextPage = if (lastPage != null && page != null) {
            page < lastPage
        } else {
            false
        }
        val animeList = jObject["data"]?.jsonArray?.mapNotNull { item ->
            SAnime.create().apply {
                title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val animeId = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val titleURI = title.replace(titleUriRegex, "-")
                url = "/Drama/$titleURI?id=$animeId"
                thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            }
        } ?: emptyList()
        return AnimesPage(animeList, hasNextPage)
    }

    /* Latest */

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=2&pageSize=40")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseLatestAnimeJson(responseString)
    }

    private fun parseLatestAnimeJson(jsonData: String) = parsePopularAnimeJson(jsonData)

    /* Search */

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/api/DramaList/Search?q=$query&type=0")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonData: String): AnimesPage {
        val animeList = json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            SAnime.create().apply {
                title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val animeId = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val titleURI = title.replace(titleUriRegex, "-")
                url = "/Drama/$titleURI?id=$animeId"
                thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            }
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    /* Details */

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("id=").substringBefore("&")
        return GET("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        return parseAnimeDetailsParseJson(responseString)
    }

    private fun parseAnimeDetailsParseJson(jsonData: String): SAnime = SAnime.create().apply {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        jObject.jsonObject["title"]?.jsonPrimitive?.content?.let { title = it }
        jObject.jsonObject["status"]?.jsonPrimitive?.content?.let { status = parseStatus(it) }
        jObject.jsonObject["description"]?.jsonPrimitive?.content?.let { description = it }
        jObject.jsonObject["thumbnail"]?.jsonPrimitive?.content?.let { thumbnail_url = it }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    /* Episodes */

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        return parseEpisodePage(responseString)
    }

    private fun parseEpisodePage(jsonData: String): List<SEpisode> {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val type = jObject["type"]?.jsonPrimitive?.content
        val episodesCount = jObject["episodesCount"]?.jsonPrimitive?.int ?: 1
        val episodeList = jObject["episodes"]?.jsonArray?.mapNotNull { item ->
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val number = item.jsonObject["number"]?.jsonPrimitive?.content?.replace(".0", "") ?: "1"
            SEpisode.create().apply {
                url = id
                item.jsonObject["number"]?.jsonPrimitive?.float?.let { episode_number = it }
                when {
                    type.isNullOrBlank() -> {
                        name = "Video $number"
                    }

                    (type.contains("Hollywood") && episodesCount == 1) || type.contains("Movie") -> {
                        name = "Movie"
                    }

                    type.contains("Anime") || type.contains("TVSeries") ||
                        (type.contains("Hollywood") && episodesCount > 1) -> {
                        name = "Episode $number"
                    }
                }
            }
        } ?: emptyList()
        return episodeList
    }

    // Video Extractor
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val kkey = requestVideoKey(episode.url)
        val url = "$baseUrl/api/DramaList/Episode/${episode.url}.png?err=false&ts=&time=&kkey=$kkey"
        val videoListRequest = GET(url, headers)
        return client.newCall(videoListRequest)
            .awaitSuccess()
            .use { response ->
                val id = response.request.url.toString()
                    .substringAfter("Episode/").substringBefore(".png")
                videosFromElement(response, id)
            }
    }

    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response) = throw UnsupportedOperationException()

    private suspend fun videosFromElement(response: Response, id: String): List<Video> {
        val jsonData = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val videoUrl = jObject["Video"]?.jsonPrimitive?.content ?: return emptyList()

        val kkey = requestSubKey(id)
        val subData = client.newCall(GET("$baseUrl/api/Sub/$id?kkey=$kkey")).awaitSuccess().use { it.body.string() }

        val subList = coroutineScope {
            (runCatching { json.decodeFromString<JsonArray>(subData) }.getOrNull() ?: emptyList()).map { item ->
                async {
                    val suburl = item.jsonObject["src"]?.jsonPrimitive?.content ?: return@async null
                    val lang = item.jsonObject["label"]?.jsonPrimitive?.content ?: "Unknown"

                    runCatching {
                        if (suburl.contains(".txt")) {
                            subDecryptor.getSubtitles(suburl, lang)
                        } else {
                            Track(suburl, lang)
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        return listOf(
            Video(
                UrlUtils.fixUrl(videoUrl),
                "FirstParty",
                UrlUtils.fixUrl(videoUrl),
                subtitleTracks = subList,
                headers = Headers.headersOf("referer", "$baseUrl/", "origin", baseUrl),
            ),
        )
    }

    private suspend fun requestVideoKey(id: String): String {
        val url = "${BuildConfig.KISSKH_API}$id&version=2.8.10"
        return client.newCall(GET(url, headers)).awaitSuccess().use { it.parseAs<Key>().key }
    }

    private suspend fun requestSubKey(id: String): String {
        val url = "${BuildConfig.KISSKH_SUB_API}$id&version=2.8.10"
        return client.newCall(GET(url, headers)).awaitSuccess().use { it.parseAs<Key>().key }
    }

    @Serializable
    data class Key(
        val id: String,
        val version: String,
        val key: String,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            subDecryptor = SubDecryptor(client, headers, baseUrl)
        }
    }

    private val titleUriRegex by lazy { Regex("[^a-zA-Z0-9]") }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "kisskh.ovh",
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}
