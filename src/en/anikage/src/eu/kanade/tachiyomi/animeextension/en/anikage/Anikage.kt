package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Anikage :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest: Boolean = true

    override val disableRelatedAnimesBySearch = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    private val preferences by getPreferencesLazy()

    /**
     * The neko provider serves AniNeko streams whose segments are wrapped
     * in a fake PNG header, so they must pass through a local proxy that
     * strips it before playback.
     */
    private val localProxy by lazy { LocalProxy(client) }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Filters.fetchFilters(client, baseUrl, preferences, headers)
            }
        }
    }

    // ============================== Popular ===============================

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

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "updated")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", "true")
        }

        return buildGet(requestUrl.build())
    }

    override fun latestUpdatesParse(response: Response) = parseAnime(response)

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = Filters.build(preferences)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = Filters.getSearchParameters(filters)
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("limit", "25")
        if (query.isNotBlank()) requestUrl.addQueryParameter("q", query)
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
        if (searchParams.tags.isNotEmpty()) {
            requestUrl.addQueryParameter("tags", searchParams.tags.joinToString(","))
        }
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", "true")
        }

        return buildGet(requestUrl.build())
    }

    override fun searchAnimeParse(response: Response) = parseAnime(response)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(
        anime.url.removeSuffix("/").substringAfterLast("/").animeInfoUrl(),
        headers = headers,
    )

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = response.parseAs<AnimeInfoResponse>().anime

        return SAnime.create().apply {
            val titleStyle = preferences.titleStyle
            title = if (titleStyle == "english") {
                animeData.title.english ?: animeData.title.romaji ?: animeData.title.native.orEmpty()
            } else {
                animeData.title.romaji ?: animeData.title.english ?: animeData.title.native.orEmpty()
            }
            thumbnail_url = animeData.coverImage?.extraLarge
                ?: animeData.coverImage?.large
                ?: animeData.coverImage?.medium
            genre = animeData.genres.joinToString()
            status = when (animeData.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "NOT_YET_RELEASED" -> SAnime.LICENSED
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
            description = animeData.description?.cleanDescription()
            author = animeData.studios.firstOrNull { it.isAnimationStudio }?.name
                ?: animeData.studios.firstOrNull()?.name
            update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ============================== Episodes ==============================

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

        return episodesData.reversed().map {
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
    }

    // =========================== Video Links ==============================

    private fun videoListRequestUrl(episode: SEpisode, lang: String, provider: String): String = "$baseUrl${episode.url}?lang=$lang&provider=$provider"

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

        val excludedServers = preferences.excludedServers
        val excludedTypes = preferences.excludedTypes
        val activeProviders = providers.filter { it.second !in excludedServers && it.first.lowercase() !in excludedTypes }

        val playlistUtils = PlaylistUtils(client, headers)

        val videos = activeProviders.parallelCatchingFlatMap { (type, provider) ->
            val episodeData = client.newCall(
                GET(videoListRequestUrl(episode, type.lowercase(), provider), headers),
            )
                .awaitSuccess()
                .parseAs<EpisodeSource>()

            val tracks = episodeData.subtitles.map {
                Track("https://og.bakayaro.live/stream/${it.file}", it.label)
            }

            episodeData.sources.parallelCatchingFlatMap { source ->
                val videoUrl = source.episodeSourceUrl()
                if (source.isM3U8 == true) {
                    val effectiveUrl = if (provider == "neko") {
                        localProxy.getProxyUrl(videoUrl, headers)
                    } else {
                        videoUrl
                    }
                    playlistUtils.extractFromHls(
                        playlistUrl = effectiveUrl,
                        masterHeaders = headers,
                        videoHeaders = headers,
                        videoNameGen = { "$type - $provider - ${source.quality} - $it" },
                        subtitleList = tracks,
                    )
                } else {
                    listOf(
                        Video(
                            videoUrl = videoUrl,
                            videoTitle = "$type - $provider - ${source.quality}",
                            subtitleTracks = tracks,
                            headers = headers,
                        ),
                    )
                }
            }
        }

        val preferredQuality = preferences.quality
        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) },
        )
    }

    private fun serversListUrl(episode: SEpisode): String = "$baseUrl${episode.url}".substringBefore("/sources") + "/servers"

    private suspend fun getEpisodeServers(episode: SEpisode): List<ServerInfo> = try {
        client.newCall(GET(serversListUrl(episode), headers))
            .awaitSuccess()
            .parseAs<EpisodeServers>()
            .servers
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // ============================= Utilities ==============================

    private fun String.cleanDescription(): String = Jsoup.parse(replace(DESCRIPTION_BR_REGEX, "\n"))
        .wholeText()
        .trim()

    private fun String.animeUrlFormat(): String = "$baseUrl/api/media/anime/$this"

    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/media/anime/$this/episodes"

    private fun animeEpisodeUrlFormat(id: String, number: Int): String = "$baseUrl/api/media/anime/$id/episodes/$number/sources"

    private fun String.animeInfoUrl(): String = "$baseUrl/api/media/anime/$this"

    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<AnikageResponse>()

        val animes = jsonData.data.map {
            SAnime.create().apply {
                val titleStyle = preferences.titleStyle
                title = if (titleStyle == "english") {
                    it.title.english ?: it.title.romaji ?: it.title.native.orEmpty()
                } else {
                    it.title.romaji ?: it.title.english ?: it.title.native.orEmpty()
                }
                thumbnail_url = it.coverImage?.extraLarge
                    ?: it.coverImage?.large
                    ?: it.coverImage?.medium
                setUrlWithoutDomain(it.slug.animeUrlFormat())
            }
        }

        return AnimesPage(animes, jsonData.hasNext)
    }

    private fun buildGet(url: HttpUrl): Request = GET(url, headers)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SITE_TITLE_FORMAT,
            title = "Preferred Title Style",
            entries = listOf("English", "Romaji"),
            entryValues = listOf("english", "romaji"),
            default = PREF_SITE_TITLE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Choose which servers you want to exclude"
            entries = SUB_PROVIDER.toTypedArray()
            entryValues = SUB_PROVIDER.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addListPreference(
            key = PREF_ISSUBORDUB_SOURCE,
            title = "Sub or Dub?",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
            default = PREF_ISSUBORDUB_DEFAULT,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_TYPES_KEY
            title = "Exclude Types"
            summary = "Choose which audio types you want to exclude"
            entries = listOf("Sub", "Dub").toTypedArray()
            entryValues = listOf("sub", "dub").toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Enable NSFW Content",
            summary = "Show adult content in search results and popular anime",
            default = PREF_ADULT_DEFAULT,
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

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.excludedServers
        get() = getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()).orEmpty()

    private val SharedPreferences.excludedTypes
        get() = getStringSet(PREF_EXCLUDE_TYPES_KEY, emptySet()).orEmpty()

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        private val DESCRIPTION_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        private const val ANIKAGE_API = "https://anikage.cc/api/media/anime/browse"
        private val ANIKAGE_API_URL by lazy { ANIKAGE_API.toHttpUrl() }
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private val provider = listOf(
            "koto",
            "kage",
            "kite",
            "ken",
            "neko",
            "uwu",
            "kiwi",
            "megg",
            "wave",
            "zen",
            "dib",
        )
        private val SUB_PROVIDER = provider
        private val DUB_PROVIDER = provider

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "koto"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "koto"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_EXCLUDE_SERVERS_KEY = "excluded_servers"
        private const val PREF_EXCLUDE_TYPES_KEY = "excluded_types"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
