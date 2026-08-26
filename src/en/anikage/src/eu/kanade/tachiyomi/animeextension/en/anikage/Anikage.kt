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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.associate
import kotlin.collections.isNotEmpty
import kotlin.time.Duration.Companion.seconds

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc"

    override val lang: String = "en"

    override val supportsLatest: Boolean = true

    override val disableRelatedAnimesBySearch = true

    override val name: String = "Anikage"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 3, 1.seconds)
        .build()

    private val preferences by getPreferencesLazy()

    override fun getFilterList(): AnimeFilterList = AnikageFilters.FILTER_LIST

    override fun popularAnimeRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "popularity")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", "true")
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
        requestUrl.addQueryParameter("limit", "25")
        if (query != "") requestUrl.addQueryParameter("q", query)
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
            requestUrl.addQueryParameter("yearMin", searchParams.releaseYear)
            requestUrl.addQueryParameter("yearMax", searchParams.releaseYear)
        }
        if (searchParams.genres.isNotEmpty()) {
            requestUrl.addQueryParameter("genres", searchParams.genres.joinToString(","))
        }
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "updated")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun latestUpdatesParse(response: Response) = parseAnime(response)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val info = response.parseAs<AnimeInfoResponse>().anime

        val titleName = if (preferences.titleStyle == "english") {
            info.title.english ?: info.title.romaji!!
        } else {
            info.title.romaji!!
        }
        val studioNames = info.studios
            .filter { it.isAnimationStudio }
            .ifEmpty { info.studios }
            .map { it.name }

        return SAnime.create().apply {
            title = titleName
            description = info.description?.cleanDescription()
            thumbnail_url = info.coverImage?.let { it.extraLarge ?: it.large ?: it.medium }
            author = studioNames.joinToString()
            update_strategy = if (info.status == "FINISHED") {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            status = when (info.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // Related Anime

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val info = response.parseAs<AnimeInfoResponse>().anime
        val currentSlug = info.slug

        fun RelatedAnimeTitle.preferred() = if (preferences.titleStyle == "english") {
            english ?: romaji
        } else {
            romaji ?: english
        }

        fun relatedStatus(status: String?) = when (status) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        return buildList {
            info.relations.mapNotNull { rel ->
                if (rel.slug == currentSlug) return@mapNotNull null
                val title = rel.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rel.slug}")
                    this.title = title
                    thumbnail_url = rel.coverImage
                    status = relatedStatus(rel.status)
                    genre = listOfNotNull(rel.format, rel.relationType).joinToString()
                }
            }.let(::addAll)

            info.recommendations.mapNotNull { rec ->
                if (rec.slug == currentSlug) return@mapNotNull null
                val title = rec.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rec.slug}")
                    this.title = title
                    thumbnail_url = rec.coverImage
                    status = relatedStatus(rec.status)
                    genre = rec.format
                }
            }.let(::addAll)
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

    /**
     * Retrieves the list of [SEpisode] for the given [SAnime].
     */

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

    /**
     * Retrieves available video sources for an episode.
     *
     * It checks each provider for an available video source and uses the provider name when creating the video quality label
     *
     * eg : https://anikage.cc/api/media/anime/oui9FXBSdF/episodes/1/sources?lang=sub&provider=megg
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val isDubPreferred = preferences.subOrDub == "dub"
        val primaryLabel = if (isDubPreferred) "Dub" else "Sub"
        val secondaryLabel = if (isDubPreferred) "Sub" else "Dub"
        val preferredSource = if (isDubPreferred) preferences.dubSource else preferences.subSource
        val otherSource = if (isDubPreferred) preferences.subSource else preferences.dubSource

        val primaryProviders = SUB_PROVIDER.sortedByDescending { it.contains(preferredSource) }
        val secondaryProviders = DUB_PROVIDER.sortedByDescending { it.contains(otherSource) }

        val providers = getEpisodeServers(episode)
            .takeIf { it.isNotEmpty() }
            ?.associate { it.providerId to it.subTypes }
            ?.let { availability ->
                primaryProviders.filter { availability[it].orEmpty().contains(primaryLabel.lowercase()) }
                    .map { primaryLabel to it } +
                    secondaryProviders.filter { availability[it].orEmpty().contains(secondaryLabel.lowercase()) }
                        .map { secondaryLabel to it }
            }
            ?: (primaryProviders.map(primaryLabel::to) + secondaryProviders.map(secondaryLabel::to))

        val playlistUtils = PlaylistUtils(client, headers)

        return providers.parallelCatchingFlatMap { (type, provider) ->
            val episodeData = client.newCall(
                GET(videoListRequestUrl(episode, provider), headers),
            )
                .awaitSuccess()
                .parseAs<EpisodeSource>()

            val tracks = episodeData.subtitles.map {
                Track("https://gg.akage.lol/m3u8/${it.file}", it.label)
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

    private fun serversListUrl(episode: SEpisode): String = "$baseUrl${episode.url}".substringBefore("/sources") + "/servers"

    private suspend fun getEpisodeServers(episode: SEpisode): List<ServerInfo> = try {
        client.newCall(GET(serversListUrl(episode), headers))
            .awaitSuccess()
            .parseAs<EpisodeServers>()
            .servers
    } catch (e: Exception) {
        emptyList()
    }

    private fun String.cleanDescription(): String = Jsoup.parse(replace(DESCRIPTION_BR_REGEX, "\n"))
        .wholeText()
        .trim()

    /**
     * Builds the URL used to retrieve episode details for an anime.
     *
     * Example: https://anikage.cc/api/media/anime/oui9FXBSdF/episodes
     */

    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/media/anime/$this/episodes"

    /**
     * Builds the URL used to retrieve source details for an episode.
     *
     * The episode number is used to identify the episode, and the returned
     * source details can be used to obtain the available video URLs.
     */
    private fun animeEpisodeUrlFormat(id: String, number: Int): String = "$baseUrl/api/media/anime/$id/episodes/$number/sources"

    /**
     * Parses the API response and converts the anime data into an [AnimesPage].
     */
    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<AnikageResponse>()

        val animes = jsonData.data.map {
            val id = it.slug
            val titleFormat = preferences.titleStyle
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji!!
            } else {
                it.title.romaji!!
            }
            val coverUrl = it.coverImage?.let { c -> c.extraLarge ?: c.large ?: c.medium }

            SAnime.create().apply {
                setUrlWithoutDomain("/anime/info/$id")
                thumbnail_url = coverUrl
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

        return AnimesPage(animes, jsonData.hasNext)
    }

    /**
     * Builds a GET request with the headers required by the Anikage API.
     */
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
            entries = listOf("English", "Romaji"),
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
            entries = listOf("Sub", "Dub"),
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
        private val DESCRIPTION_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        private const val ANIKAGE_API = "https://anikage.cc/api/media/anime/browse"
        private val ANIKAGE_API_URL by lazy { ANIKAGE_API.toHttpUrl() }
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private val provider = listOf(
            "koto",
            "neko",
            "uwu",
            "kiwi",
            "megg",
            "dib",
            "wave",
        )
        private val SUB_PROVIDER = provider
        private val DUB_PROVIDER = provider

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "koto"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "koto"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
