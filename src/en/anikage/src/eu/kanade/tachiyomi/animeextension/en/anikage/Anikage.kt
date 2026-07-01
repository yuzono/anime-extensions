package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc"

    override val lang: String = "en"

    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes = false

    override val name: String = "Anikage"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 5, 1.seconds)
        .build()

    private val preferences by getPreferencesLazy()

    override fun getFilterList(): AnimeFilterList = AnikageFilters.FILTER_LIST

    override fun popularAnimeRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "popularity")
        requestUrl.addQueryParameter("per_page", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("include_adult", "true")
        }

        return buildGet(requestUrl.build())
    }

    override fun popularAnimeParse(response: Response) = parseAnime(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = AnikageFilters.getSearchParameters(filters)
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("per_page", "25")
        if (query != "") requestUrl.addQueryParameter("query", query)
        if (searchParams.sortBy.isNotEmpty()) {
            requestUrl.addQueryParameter("sort", searchParams.sortBy)
        }
        if (searchParams.status != "ALL") {
            requestUrl.addQueryParameter("status", searchParams.status)
        }
        if (searchParams.season != "ALL") {
            requestUrl.addQueryParameter("season", searchParams.season)
        }
        if (searchParams.origin != "ALL") {
            requestUrl.addQueryParameter("country", searchParams.origin)
        }
        if (searchParams.types != "ALL") {
            requestUrl.addQueryParameter("format", searchParams.types)
        }
        if (searchParams.releaseYear != "ALL") {
            requestUrl.addQueryParameter("seasonYear", searchParams.releaseYear)
        }
        if (searchParams.genres.isNotEmpty()) {
            requestUrl.addQueryParameter("genres", searchParams.genres.joinToString(","))
        }
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("include_adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "updated")
        requestUrl.addQueryParameter("per_page", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("include_adult", true.toString())
        }

        return buildGet(requestUrl.build())
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

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        val getHeaders = headersBuilder()
            .add("Referer", "$baseUrl${anime.url}")
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(animeId.animeEpisodeBuilder(), headers = getHeaders)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")

        val episodesData = client.newCall(episodeListRequest(anime))
            .awaitSuccess()
            .parseAs<List<EpisodeResult>>()

        val episode = episodesData.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = if (!it.title.isNullOrBlank()) {
                    "Episode ${it.number} - ${it.title}"
                } else {
                    "Episode ${it.number}"
                }
                date_upload = DATE_FORMAT.tryParse(it.airDate)
                setUrlWithoutDomain(
                    animeEpisodeUrlFormat(
                        animeId,
                        it.number,
                    ),
                )
            }
        }
        return episode
    }

    // Video Links

    private fun videoListRequestUrl(episode: SEpisode, provider: String): String = "$baseUrl${episode.url}?lang=${preferences.subOrDub}&provider=$provider"

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val providers = if (preferences.subOrDub == "dub") {
            DUB_PROVIDER
                .sortedByDescending { it.contains(preferences.dubSource) }
                .map { "Dub" to it } +
                SUB_PROVIDER
                    .sortedByDescending { it.contains(preferences.subSource) }
                    .map { "Sub" to it }
        } else {
            SUB_PROVIDER.sortedByDescending { it.contains(preferences.subSource) }
                .map { "Sub" to it } +
                DUB_PROVIDER.sortedByDescending { it.contains(preferences.dubSource) }
                    .map { "Dub" to it }
        }

        val playlistUtils = PlaylistUtils(client, headers)

        return providers.toList().parallelCatchingFlatMap { (type, provider) ->
            val episodeData = client.newCall(
                GET(videoListRequestUrl(episode, provider), headers),
            )
                .awaitSuccess()
                .parseAs<EpisodeSource>()

            val tracks = episodeData.subtitles.map {
                Track(it.file, it.label)
            }

            episodeData.sources.parallelCatchingFlatMap { source ->
                val videoUrl = source.episodeSourceUrl()
                if (source.isM3U8 == true) {
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        masterHeaders = headers,
                        videoHeaders = headers,
                        videoNameGen = { "$type - $provider - ${source.quality} - $it" },
                        subtitleList = tracks,
                    )
                } else {
                    Video(
                        url = videoUrl,
                        quality = "$type - $provider - ${source.quality}",
                        videoUrl = videoUrl,
                        subtitleTracks = tracks,
                        headers = headers,
                    ).let(::listOf)
                }
            }
        }
    }

    // Utils

    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/media/anime/$this/episodes"
    private fun animeEpisodeUrlFormat(id: String, number: Int): String = "$baseUrl/api/media/anime/$id/episodes/$number/sources"

    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<AnikageResponse>()
        val hasNextPage = jsonData.hasNextPage

        val animes = jsonData.results.map {
            val id = it.slug
            val titleFormat = preferences.titleStyle
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji
            } else {
                it.title.romaji
            }

            SAnime.create().apply {
                setUrlWithoutDomain("/anime/info/$id")
                thumbnail_url = it.coverImage.extraLarge
                title = titleName
                description = null
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

    private fun buildGet(url: HttpUrl): Request {
        val postHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            set("Host", ANIKAGE_API_URL.host)
            set("Origin", baseUrl)
            set("Referer", "$ANIKAGE_API/")
        }.build()

        return GET(url, headers = postHeaders)
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

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)!!

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)!!

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

        private const val ANIKAGE_API = "https://anikage.cc/api/media/anime/advanced-search"
        private val ANIKAGE_API_URL by lazy { ANIKAGE_API.toHttpUrl() }
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private val SUB_PROVIDER = listOf(
            "megg",
            "miko",
            "anya",
            "verse",
            "neko",
        )
        private val DUB_PROVIDER = listOf(
            "megg",
            "miko",
            "anya",
            "verse",
            "neko",
        )

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "megg"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "megg"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
