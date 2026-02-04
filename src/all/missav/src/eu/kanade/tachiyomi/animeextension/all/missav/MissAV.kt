package eu.kanade.tachiyomi.animeextension.all.missav

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class MissAV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MissAV"

    override val lang = "all"

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override val supportsLatest = true

    private var docHeaders by LazyMutable {
        newHeaders()
    }

    private fun newHeaders(): Headers = headers.newBuilder().apply {
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
    }.build()

    private var playlistExtractor by LazyMutable {
        PlaylistUtils(client, docHeaders)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en/today-hot?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/en/new?page=$page", docHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val genre = filters.firstInstanceOrNull<GenreList>()?.selected
            if (query.isNotEmpty()) {
                addEncodedPathSegments("en/search")
                addPathSegment(query.trim())
            } else if (genre != null) {
                addEncodedPathSegments(genre)
            } else {
                addEncodedPathSegments("en/new")
            }
            filters.firstInstanceOrNull<SortFilter>()?.selected?.let {
                addQueryParameter("sort", it)
            }
            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        if (document.selectFirst("div[x-data*=handleRecommendResponse]") != null) {
            val url = response.request.url
            val pathSegments = url.pathSegments
            val queryStr = pathSegments.getOrNull(pathSegments.indexOf("search") + 1)
                ?: throw Exception("Failed to parse search query from URL: $url")
            val query = URLDecoder.decode(queryStr, StandardCharsets.UTF_8.name())
            val page = url.queryParameter("page")?.toIntOrNull() ?: 1
            client.newCall(fallbackApiSearch(query, page))
                .execute().use {
                    if (!it.isSuccessful) {
                        Log.e("MissAv", "Failed to fetch search results: ${it.code}")
                        throw Exception("No more results found")
                    }

                    val data = it.body.string().parseAs<RecommendationsResponse>()
                    recommMap[query] = data.recommId
                    return data.toAnimePage()
                }
        }

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    private val recommMap: MutableMap<String, String> = ConcurrentHashMap()
    private val jsonMime by lazy { "application/json; charset=utf-8".toMediaTypeOrNull() }

    private fun fallbackApiSearch(query: String, page: Int): Request {
        val recommId = recommMap[query]
        return if (page == 1 || recommId == null) {
            val body = MissAvApi.searchData(query)
                .toRequestBody(jsonMime)
            POST(MissAvApi.searchURL(getUuid()), docHeaders, body)
        } else {
            val body = MissAvApi.recommData
                .toRequestBody(jsonMime)
            POST(MissAvApi.recommURL(recommId), docHeaders, body)
        }
    }

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val body = MissAvApi.relatedData(getUuid(), anime.url.substringAfterLast("/"))
            .toRequestBody(jsonMime)

        return POST(MissAvApi.relatedURL(), docHeaders, body)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val data = response.body.string().parseAs<List<RelatedResponse>>()
        return data.flatMap { it.toAnimeList() }
    }

    override fun String.stripKeywordForRelatedAnimes(): List<String> = replace(regexSpecialCharacters, " ")
        .split(regexWhitespace)
        .map {
            // remove number only
            it.replace(regexNumberOnly, "")
                .lowercase()
        }
        // exclude single character
        .filter { it.length > 1 }

    override fun getFilterList() = getFilters()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val jpTitle = document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover = document.selectFirst("video.player")?.attr("abs:data-poster")

        return SAnime.create().apply {
            title = document.selectFirst("h1.text-base")!!.text()
            genre = document.getInfo("/genres/")
            author = listOfNotNull(
                document.getInfo("/directors/"),
                document.getInfo("/makers/"),
            ).joinToString()
            artist = document.getInfo("/actresses/")
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst("div.mb-1")?.text()?.also { append("$it\n") }

                document.getInfo("/labels/")?.also { append("\nLabel: $it") }
                document.getInfo("/series/")?.also { append("\nSeries: $it") }

                document.select("div.text-secondary:not(:has(a)):has(span)")
                    .eachText()
                    .forEach { append("\n$it") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else {
                siteCover
            }
        }
    }

    private fun Element.getInfo(urlPart: String) = select("div.text-secondary > a[href*=$urlPart]")
        .eachText()
        .joinToString()
        .takeIf(String::isNotBlank)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Episode"
        },
    )

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val playlists = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(Unpacker::unpack)?.ifEmpty { null }
            ?: return emptyList()

        val masterPlaylist = playlists.substringAfter("source=\"").substringBefore("\";")

        return playlistExtractor.extractFromHls(masterPlaylist, referer = "$baseUrl/")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_ENTRIES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = newHeaders()
            playlistExtractor = PlaylistUtils(client, docHeaders)
        }

        screen.addListPreference(
            key = PREF_QUALITY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    private fun getUuid(): String = preferences.getString(PREF_UUID_KEY, null) ?: synchronized(this) {
        // Double-check pattern to avoid generating UUID if another thread already did
        preferences.getString(PREF_UUID_KEY, null) ?: run {
            val uuid = MissAvApi.generateUUID()
            preferences.edit().putString(PREF_UUID_KEY, uuid).apply()
            uuid
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private val PREF_DOMAIN_ENTRIES = listOf("https://missav.live", "https://missav.ai", "https://missav.ws")
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRIES.first()

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()

        private const val PREF_UUID_KEY = "missav_uuid"

        private val regexWhitespace = Regex("\\s+")
        private val regexSpecialCharacters =
            Regex("([-.!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        private val regexNumberOnly = Regex("^\\d+$")
    }
}
