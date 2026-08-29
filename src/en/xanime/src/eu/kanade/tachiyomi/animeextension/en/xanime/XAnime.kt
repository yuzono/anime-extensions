package eu.kanade.tachiyomi.animeextension.en.xanime

import android.util.LruCache
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.AUDIO_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.AudioFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.EP_COUNT_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.EpCountFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.GenreGroup
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.SEASON_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.SORT_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.STATUS_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.SeasonFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.SortFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.StatusFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.TYPE_MAP
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.TypeFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.YearFromFilter
import eu.kanade.tachiyomi.animeextension.en.xanime.Filters.YearToFilter
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class XAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "XAnime"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl: String get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN)!!

    private val cryptoClient: OkHttpClient = client.newBuilder()
        .rateLimit(5)
        .addInterceptor(Crypto())
        .build()

    private val api = Queries(cryptoClient, { baseUrl }, headers)

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val slugCache by lazy { LruCache<String, String>(256) }

    private val epSlugCache by lazy { LruCache<String, String>(1024) }

    private fun rememberSlug(aniId: String, slug: String?) {
        if (aniId.isNotBlank() && !slug.isNullOrBlank()) slugCache.put(aniId, slug)
    }

    private fun rememberEpSlug(epId: String?, slug: String?) {
        if (!epId.isNullOrBlank() && !slug.isNullOrBlank()) epSlugCache.put(epId, slug)
    }

    // ========================== Popular & Latest ==========================

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchSearchAnime(page, "", "field_score", getFilterList())

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchSearchAnime(page, "", "field_update", getFilterList())

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("http")) {
            val url = query.toHttpUrlOrNull() ?: return AnimesPage(emptyList(), false)
            val knownHosts = DOMAIN_VALUES.map { it.toHttpUrl().host }
            if (url.host !in knownHosts) return AnimesPage(emptyList(), false)

            val titleIndex = url.pathSegments.indexOf("title")
            val aniId = url.pathSegments.getOrNull(titleIndex + 1)
                ?.substringBefore("-")
                ?.takeIf { titleIndex != -1 && it.isNotBlank() }
                ?: return AnimesPage(emptyList(), false)

            return getSearchAnime(page, "$PREFIX_ID$aniId", filters)
        }

        if (query.startsWith(PREFIX_ID)) {
            val id = query.substringAfter(PREFIX_ID)
            if (id.isBlank()) return AnimesPage(emptyList(), false)
            val anime = SAnime.create().apply { this.url = id }
            return runCatching {
                val details = getAnimeDetails(anime)
                if (details.title.isBlank()) throw Exception("Anime not found")
                listOf(details)
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val sortby = filters.firstInstanceOrNull<SortFilter>()?.getValue(SORT_MAP) ?: "field_date_create"

        return fetchSearchAnime(page, query, sortby, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private suspend fun fetchSearchAnime(page: Int, query: String, sortby: String, filters: AnimeFilterList): AnimesPage {
        val genres = filters.firstInstanceOrNull<GenreGroup>()
        val incGenres = genres?.state
            ?.filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            ?.map { GENRE_MAP[it.name] ?: it.name }
            ?: emptyList()
        val excGenres = genres?.state
            ?.filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            ?.map { GENRE_MAP[it.name] ?: it.name }
            ?: emptyList()

        val searchSelect = SearchSelect(
            word = query,
            sortby = sortby,
            page = page,
            incGenres = incGenres,
            excGenres = excGenres,
            type = filters.firstInstanceOrNull<TypeFilter>()?.getValue(TYPE_MAP) ?: "",
            origStatus = filters.firstInstanceOrNull<StatusFilter>()?.getValue(STATUS_MAP) ?: "",
            sources = filters.firstInstanceOrNull<AudioFilter>()?.getValue(AUDIO_MAP) ?: "",
            epTotal = filters.firstInstanceOrNull<EpCountFilter>()?.getValue(EP_COUNT_MAP) ?: "",
            season = filters.firstInstanceOrNull<SeasonFilter>()?.getValue(SEASON_MAP) ?: "",
            yearFrom = filters.firstInstanceOrNull<YearFromFilter>()?.state?.takeIf { it.isNotBlank() },
            yearTo = filters.firstInstanceOrNull<YearToFilter>()?.state?.takeIf { it.isNotBlank() },
        )

        val res = api.searchAnime(searchSelect)
        val searchData = res.searchData ?: return AnimesPage(emptyList(), false)
        val animes = searchData.items.map { item ->
            item.toSAnime(baseUrl).also { anime -> rememberSlug(anime.url, item.slug) }
        }
        val hasNext = (searchData.paging?.next ?: 0) != 0
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        listOf(
            SortFilter(),
            GenreGroup(),
            TypeFilter(),
            StatusFilter(),
            AudioFilter(),
            EpCountFilter(),
            SeasonFilter(),
            YearFromFilter(),
            YearToFilter(),
        ),
    )

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val node = api.getAnimeDetails(anime.url) ?: throw Exception("Anime not found")
        rememberSlug(anime.url, node.slug)
        return node.toSAnimeDetails(baseUrl)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override fun getAnimeUrl(anime: SAnime): String {
        val slug = slugCache.get(anime.url)?.let { "-$it" } ?: ""
        return "$baseUrl/title/${anime.url}$slug"
    }

    // ============================== Related Anime ==============================

    override val disableRelatedAnimesBySearch = true

    override fun relatedAnimeListRequest(anime: SAnime): Request = api.getRelatedAnimeRequest(anime.url)

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val response = cryptoClient.newCall(relatedAnimeListRequest(anime)).awaitSuccess()
        return relatedAnimeListParse(response)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val node = response.parseAs<GraphQlResponse<RelatedResponse>>().data?.node
            ?: return emptyList()
        val currentId = node.data?.aniId

        return buildList {
            node.relations.forEach { rel ->
                val aniId = rel.aniId?.takeIf { it.isNotBlank() } ?: return@forEach

                if (aniId == currentId) return@forEach
                if (any { it.url == aniId }) return@forEach

                add(rel.toSAnime(aniId, baseUrl))
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val firstPage = api.getEpisodes(anime.url, 1).episodesData ?: return@coroutineScope emptyList()
        val pages = firstPage.paging?.pages ?: 1
        val semaphore = Semaphore(4)

        val remainingPages = (2..pages)
            .map { page ->
                async { semaphore.withPermit { api.getEpisodes(anime.url, page).episodesData?.items } }
            }
            .awaitAll()
            .filterNotNull()

        (listOf(firstPage.items) + remainingPages)
            .flatten()
            .distinctBy { it.epId }
            .map { item -> item.toSEpisode().also { rememberEpSlug(item.epId, item.epSlug) } }
            .sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode): String {
        val aniId = episode.url.substringBefore("/")
        val epId = episode.url.substringAfter("/")
        val aniSlug = slugCache.get(aniId)?.let { "-$it" } ?: ""
        val epSlug = epSlugCache.get(epId)?.let { "-$it" } ?: ""
        return "$baseUrl/title/$aniId$aniSlug/$epId$epSlug"
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epId = episode.url.substringAfter("/")
        val res = api.getVideoUrl(epId)
        val sources = res.videoUrlData?.data?.sourcesList ?: emptyList()

        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        val videos = sources.parallelCatchingFlatMapBlocking { source ->
            val srcData = source.data ?: return@parallelCatchingFlatMapBlocking emptyList()

            val srcType = srcData.srcType?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            } ?: "Unknown"

            if (excludedTypes.any { it.equals(srcType, ignoreCase = true) }) return@parallelCatchingFlatMapBlocking emptyList()

            val trackList = srcData.tracks.mapNotNull { track ->
                track.trackPath?.let { path ->
                    val url = if (path.startsWith("http")) path else baseUrl + path
                    val isDefault = track.default?.toString()?.contains("true", ignoreCase = true) ?: false
                    val label = track.label ?: "Unknown"
                    Track(url, if (isDefault) "$label (Default)" else label)
                }
            }

            buildList {
                srcData.souPath?.let { path ->
                    val url = if (path.startsWith("http")) path else baseUrl + path
                    val serverName = srcData.srcName ?: "Unknown"
                    addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = url,
                            videoNameGen = { quality -> "$srcType - $serverName: $quality" },
                            subtitleList = trackList,
                        ),
                    )
                }

                srcData.m3u8Lists.forEach { m3u8 ->
                    m3u8.iframe?.let { path ->
                        val url = if (path.startsWith("http")) path else baseUrl + path
                        addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = url,
                                videoNameGen = { quality -> "$srcType - ${m3u8.name ?: "Unknown"}: $quality" },
                                subtitleList = trackList,
                            ),
                        )
                    }
                }
            }
        }

        return videos.sort()
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { getQualityNumeric(it.quality) }
                .thenByDescending { it.quality.contains(type, ignoreCase = true) },
        )
    }

    private fun getQualityNumeric(quality: String): Int = QUALITY_REGEX.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = QUALITY_VALUES,
            entryValues = QUALITY_KEYS,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            entries = TYPE_VALUES,
            entryValues = TYPE_VALUES,
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio formats to hide",
            entries = TYPE_VALUES,
            entryValues = TYPE_VALUES,
            default = emptySet(),
        )
    }

    companion object {
        const val PREFIX_ID = "id:"

        private val DOMAIN_VALUES = listOf("https://xanime.me", "https://xanime.app")
        private val DOMAIN_ENTRIES = listOf("xanime.me", "xanime.app")
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN = DOMAIN_VALUES[0]

        private val QUALITY_VALUES = listOf("1080p", "720p", "480p", "360p")
        private val QUALITY_KEYS = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "Sub"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
        private val TYPE_VALUES = listOf("Sub", "Raw", "Dub")
    }
}
