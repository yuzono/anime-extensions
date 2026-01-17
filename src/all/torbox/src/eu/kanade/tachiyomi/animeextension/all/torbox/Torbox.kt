package eu.kanade.tachiyomi.animeextension.all.torbox

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.get
import extensions.utils.Source
import extensions.utils.addEditTextPreference
import extensions.utils.addSetPreference
import extensions.utils.addSwitchPreference
import extensions.utils.delegate
import extensions.utils.formatBytes
import extensions.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.IOException

class Torbox : Source(), UnmeteredSource {

    override val baseUrl = "https://api.torbox.app"

    override val lang = "all"

    override val name = "Torbox"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if ("token" in request.url.queryParameterNames) {
                return@addInterceptor chain.proceed(request)
            }

            val apiKey = preferences.apiKey.ifEmpty {
                throw IOException("Please enter api key in extension settings")
            }

            val authRequest = request.newBuilder()
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            chain.proceed(authRequest)
        }
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page == 1) {
            hasNextPages.replaceAll { true }
        }
        return getSearchAnime(
            page = page,
            query = "",
            filters = AnimeFilterList(
                TypeFilter(),
                SortFilter(SortType.Default),
            ),
        )
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) {
            hasNextPages.replaceAll { true }
        }
        return getSearchAnime(
            page = page,
            query = "",
            filters = AnimeFilterList(
                TypeFilter(),
                SortFilter(SortType.AddedDate, false),
            ),
        )
    }

    // =============================== Search ===============================

    private val hasNextPages = mutableListOf(true, true, true)
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (page == 1) {
            hasNextPages.replaceAll { true }
        }
        val filterList = filters.ifEmpty { getFilterList() }
        val filterTypes = filterList.filterIsInstance<TypeFilter>().first().getSelection()
        val (sortType, ascending) = filterList.filterIsInstance<SortFilter>().first().getSelection()

        val items = coroutineScope {
            TYPES.mapIndexedNotNull { i, type ->
                if (type !in filterTypes) {
                    hasNextPages[i] = false
                    return@mapIndexedNotNull null
                }
                if (!hasNextPages[i]) return@mapIndexedNotNull null

                async {
                    val url = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("v1")
                        addPathSegment("api")
                        addPathSegment(type)
                        addPathSegment("mylist")
                        addQueryParameter("limit", LIMIT.toString())
                        addQueryParameter("offset", ((page - 1) * LIMIT).toString())
                    }.build()

                    client.get(url).parseAs<DataDto<List<ListDataDto>>>()
                        .data
                        .map { it.toInfoDataDto(type) }
                        .also {
                            hasNextPages[i] = it.size == LIMIT
                        }
                }
            }.awaitAll().flatten()
        }

        val comparator = when (sortType) {
            SortType.Default -> null
            SortType.Name -> compareBy<InfoDataDto> { it.name }
            SortType.Size -> compareBy { it.size }
            SortType.AddedDate -> compareBy { it.createdAt }
            SortType.CachedDate -> compareBy { it.cachedAt }
            SortType.LastUpdated -> compareBy { it.updateAt }
            SortType.Progress -> compareBy { it.progress }
            SortType.Ratio -> compareBy { it.ratio }
            SortType.DownloadSpeed -> compareBy { it.downloadSpeed }
            SortType.UploadSpeed -> compareBy { it.uploadSpeed }
            SortType.ETA -> compareBy { it.eta }
        }

        val filteredItems = items
            .filter { it.name.contains(query, true) }

        val sortedItems = comparator?.let {
            if (ascending) {
                filteredItems.sortedWith(it)
            } else {
                filteredItems.sortedWith(it.reversed())
            }
        } ?: filteredItems

        return AnimesPage(sortedItems.map { it.toSAnime(preferences.trimTitleInfo) }, hasNextPages.any { it })
    }

    // ============================== Filters ===============================

    class CheckboxFilter(
        name: String,
        val id: String,
        state: Boolean = true,
    ) : AnimeFilter.CheckBox(name, state)

    open class CheckboxListFilter(
        name: String,
        values: List<CheckboxFilter>,
    ) : AnimeFilter.Group<CheckboxFilter>(name, values) {
        fun getSelection(): List<String> {
            return state.filter { it.state }.map { it.id }
        }
    }

    class TypeFilter : CheckboxListFilter(
        "Types",
        listOf(
            CheckboxFilter("Torrents", TORRENT),
            CheckboxFilter("Web Downloads", WEBDL),
            CheckboxFilter("Usenet Downloads", USENET),
        ),
    )

    class SortFilter(
        sortType: SortType = SortType.Default,
        ascending: Boolean = true,
    ) : AnimeFilter.Sort(
        "Sort by",
        SortType.entries.map { it.displayName }.toTypedArray(),
        Selection(SortType.entries.indexOfFirst { it == sortType }, ascending),
    ) {
        fun getSelection(): Pair<SortType, Boolean> {
            return SortType.entries[state!!.index] to state!!.ascending
        }
    }

    enum class SortType(val displayName: String) {
        Default("Default"),
        Name("Name"),
        Size("Size"),
        AddedDate("Added Date"),
        CachedDate("Cached Date"),
        LastUpdated("Last Updated"),
        Progress("Progress"),
        Ratio("Ration"),
        DownloadSpeed("Download Speed"),
        UploadSpeed("Upload Speed"),
        ETA("ETA"),
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            TypeFilter(),
            SortFilter(),
        )
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val info = json.decodeFromString<InfoDetailsDto>(anime.url)

        var hasNextPage = true
        var page = 0
        while (hasNextPage) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v1")
                addPathSegment("api")
                addPathSegment(info.type)
                addPathSegment("mylist")
                addQueryParameter("limit", LIMIT.toString())
                addQueryParameter("offset", (page * LIMIT).toString())
            }.build()

            val data = client.get(url).parseAs<DataDto<List<ListDataDto>>>().data
            hasNextPage = data.size == LIMIT
            page++

            data.firstOrNull { it.id == info.id }?.let {
                return it.toInfoDataDto(info.type).toSAnime(preferences.trimTitleInfo)
            }
        }

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getSeasonList(anime: SAnime) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val info = json.decodeFromString<InfoDetailsDto>(anime.url)

        return info.files.reversed()
            .filter { if (preferences.filterVideos) it.mimetype.startsWith("video", true) else true }
            .map {
                val extraInfo = buildList(2) {
                    if (preferences.epDetails.contains("Size")) {
                        add(it.size.formatBytes())
                    }
                    if (preferences.epDetails.contains("Mime")) {
                        add(it.mimetype)
                    }
                }

                SEpisode.create().apply {
                    name = if (preferences.trimEpisodeInfo) it.shortName.trimInfo() else it.shortName
                    url = "${info.type},${info.id},${it.id}"
                    scanlator = extraInfo.joinToString(" • ")
                }
            }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (type, id, fileId) = episode.url.split(",")
        val queryParameter = when (type) {
            TORRENT -> "torrent_id"
            WEBDL -> "web_id"
            USENET -> "usenet_id"
            else -> throw IllegalArgumentException("Invalid type: $type")
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("v1")
            addPathSegment("api")
            addPathSegment(type)
            addPathSegment("requestdl")
            addQueryParameter(queryParameter, id)
            addQueryParameter("file_id", fileId)
            addQueryParameter("token", preferences.apiKey)
        }.build()

        val videoUrl = client.get(url).parseAs<DataDto<String>>().data

        return listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = episode.name,
            ),
        ).toHosterList()
    }

    // ============================= Utilities ==============================

    @Suppress("SpellCheckingInspection")
    companion object {
        private const val LIMIT = 1000
        private const val TORRENT = "torrents"
        private const val USENET = "usenet"
        private const val WEBDL = "webdl"
        private val TYPES = listOf(TORRENT, WEBDL, USENET)

        private const val APIKEY_KEY = "apikey"
        private const val APIKEY_DEFAULT = ""

        private const val PREF_EP_DETAILS_KEY = "pref_episode_details_key"
        private val PREF_EP_DETAILS = listOf("Size", "Mime")
        private val PREF_EP_DETAILS_DEFAULT = emptySet<String>()

        private const val VIDEO_KEY = "video"
        private const val VIDEO_DEFAULT = false

        private const val TRIM_TITLE_KEY = "trim_title"
        private const val TRIM_TITLE_DEFAULT = false

        private const val TRIM_EPISODE_KEY = "trim_episode"
        private const val TRIM_EPISODE_DEFAULT = false
    }

    // ============================ Preferences =============================

    private val SharedPreferences.apiKey by preferences.delegate(APIKEY_KEY, APIKEY_DEFAULT)
    private val SharedPreferences.epDetails by preferences.delegate(PREF_EP_DETAILS_KEY, PREF_EP_DETAILS_DEFAULT)
    private val SharedPreferences.filterVideos by preferences.delegate(VIDEO_KEY, VIDEO_DEFAULT)
    private val SharedPreferences.trimTitleInfo by preferences.delegate(TRIM_TITLE_KEY, TRIM_TITLE_DEFAULT)
    private val SharedPreferences.trimEpisodeInfo by preferences.delegate(TRIM_EPISODE_KEY, TRIM_EPISODE_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val apiKeySummary: (String) -> String = {
            if (it.isBlank()) "The user account api key" else "•".repeat(it.length)
        }
        screen.addEditTextPreference(
            key = APIKEY_KEY,
            default = APIKEY_DEFAULT,
            title = "API key",
            summary = apiKeySummary(preferences.apiKey),
            getSummary = apiKeySummary,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        screen.addSetPreference(
            key = PREF_EP_DETAILS_KEY,
            default = PREF_EP_DETAILS_DEFAULT,
            title = "Additional details for episodes",
            summary = "Show additional details about an episode in the scanlator field",
            entries = PREF_EP_DETAILS,
            entryValues = PREF_EP_DETAILS,
        )

        screen.addSwitchPreference(
            key = VIDEO_KEY,
            default = VIDEO_DEFAULT,
            title = "Filter out non-video items",
            summary = "",
        )

        screen.addSwitchPreference(
            key = TRIM_TITLE_KEY,
            default = TRIM_TITLE_DEFAULT,
            title = "Trim info from anime titles",
            summary = "",
        )

        screen.addSwitchPreference(
            key = TRIM_EPISODE_KEY,
            default = TRIM_EPISODE_DEFAULT,
            title = "Trim info from episode titles",
            summary = "",
        )
    }
}
