package eu.kanade.tachiyomi.animeextension.all.stremio

import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.AddonManager
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonResource
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.CatalogDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.ExtraType
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstance
import keiyoushi.utils.get
import keiyoushi.utils.getSwitchPreference
import keiyoushi.utils.post
import keiyoushi.utils.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.apache.commons.text.StringSubstitutor

class Stremio : Source() {
    override val migration: SharedPreferences.() -> Unit = {
        val addons = getString(ADDONS_KEY, ADDONS_DEFAULT)!!
            .replace(" ", "\n")
        edit().putString(ADDONS_KEY, addons).apply()
    }

    override var baseUrl by LazyMutable { preferences.webUIUrl }

    override val lang = "all"

    override val name = "Stremio"

    override val supportsLatest = false

    private val addonDelegate = preferences.delegate(ADDONS_KEY, ADDONS_DEFAULT)
    private val authKeyDelegate = preferences.delegate(AUTHKEY_KEY, "")

    // KMK -->
    private val addonManager by lazy {
        AddonManager(addonDelegate, authKeyDelegate)
    }
    private suspend fun addons() = addonManager.getAddons(this)
    // KMK <--

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val popularCatalog = addons().firstNotNullOfOrNull { addon ->
            addon.manifest.catalogs.firstOrNull { catalog ->
                catalog.extra.orEmpty().none { it.isRequired == true }
            }?.copy(transportUrl = addon.getTransportUrl().toString())
        } ?: throw Exception("No valid catalog addons found")

        try {
            setCatalogList(addons())
        } catch (_: Exception) { }

        return getSearchAnime(
            page,
            "",
            AnimeFilterList(
                CatalogFilter(
                    arrayOf("unused" to popularCatalog),
                    0,
                ),
            ),
        )
    }

    // =============================== Search ===============================

    private var nextSkip: Int = 0

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = filters.ifEmpty { getFilterList() }

        filterList.filterIsInstance<LibraryTypeFilter>().firstOrNull()?.let {
            if (it.getSelection().isNotBlank()) {
                return getLibraryAnime(page, query, filters)
            }
        }

        val catalogFilter = filterList.filterIsInstance<CatalogFilter>().firstOrNull()
            ?: throw Exception("No catalog selected. Select one in filters")

        selectedCatalogIndex = catalogFilter.state
        val selectedCatalog = catalogFilter.getSelection()
        setGenreList(selectedCatalog)

        val selectedGenre = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.getSelection()

        if (selectedCatalog.hasRequired(ExtraType.GENRE) && selectedGenre?.isNotEmpty() != true) {
            throw Exception("Selected catalog requires a genre. Select one in filters")
        }

        if (selectedCatalog.hasRequired(ExtraType.SEARCH) && query.isBlank()) {
            throw Exception("Selected catalog requires a search term.")
        }

        if (selectedCatalog.extra.orEmpty().none { it.type == ExtraType.SEARCH } && query.isNotBlank()) {
            throw Exception("Selected catalog does not support searching.")
        }

        val response = client.get(
            selectedCatalog.transportUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("catalog")
                addPathSegment(selectedCatalog.type)
                addPathSegment(selectedCatalog.id)

                val extraProps = buildList {
                    if (page > 1 && selectedCatalog.extra.orEmpty().any { it.type == ExtraType.SKIP }) {
                        add("skip=$nextSkip")
                    }

                    when {
                        query.isNotBlank() -> add("search=$query")
                        !selectedGenre.isNullOrBlank() -> add("genre=$selectedGenre")
                    }
                }

                if (extraProps.isNotEmpty()) {
                    addPathSegment(extraProps.joinToString("&"))
                }
            }.build().toString() +
                ".json",
            headers,
        )

        val data = response.parseAs<CatalogListDto>()
        val entries = data.metas.map { it.toSAnime() }

        if (page == 1) {
            nextSkip = entries.size
        } else {
            nextSkip += entries.size
        }

        return AnimesPage(entries, data.hasMore == true)
    }

    private fun getLibraryAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val libraryType = filters.firstInstance<LibraryTypeFilter>().getSelection()
        val librarySort = filters.firstInstance<LibrarySortFilter>().getSelection()

        val filteredEntries = libraryItems!!.filter { entry ->
            if (libraryType == "all") {
                return@filter true
            }

            entry.type.equals(libraryType, true)
        }
            .filter { it.name.contains(query, true) }
            .sortedWith(librarySort)

        val entries = filteredEntries.subList(
            (page - 1) * LIBRARY_PAGE_SIZE,
            (page * LIBRARY_PAGE_SIZE).coerceAtMost(filteredEntries.size),
        )
            .map {
                it.toSAnime()
            }

        return AnimesPage(entries, filteredEntries.size > page * LIBRARY_PAGE_SIZE)
    }

    // From https://github.com/Stremio/stremio-core
    private fun List<LibraryItemDto>.sortedWith(librarySort: LibrarySort): List<LibraryItemDto> = sortedWith { a, b ->
        when (librarySort) {
            LibrarySort.LAST_WATCHED -> b.state.lastWatched.compareTo(a.state.lastWatched)

            LibrarySort.AZ -> a.name.lowercase().compareTo(b.name.lowercase())

            LibrarySort.ZA -> b.name.lowercase().compareTo(a.name.lowercase())

            LibrarySort.MOST_WATCHED -> b.state.timesWatched.compareTo(a.state.timesWatched)

            LibrarySort.WATCHED -> compareValuesBy(
                b,
                a,
                { it.watched() },
                { it.state.lastWatched },
                { it.ctime },
            )

            LibrarySort.NOT_WATCHED -> compareValuesBy(
                a,
                b,
                { it.watched() },
                { it.state.lastWatched },
                { it.ctime },
            )
        }
    }

    // =============================== Filters ==============================

    open class UriPartFilter<T>(
        name: String,
        private val vals: Array<Pair<String, T>>,
        state: Int = 0,
    ) : AnimeFilter.Select<String>(
        name,
        vals.map { it.first }.toTypedArray(),
        state,
    ) {
        fun getSelection(): T = vals[state].second
    }

    private var selectedCatalogIndex: Int = 0
    private var catalogList: Array<Pair<String, CatalogDto>>? = null

    private fun setCatalogList(addons: List<AddonDto>) {
        catalogList = addons.flatMap { addon ->
            addon.manifest.catalogs.map { catalog ->
                buildString {
                    append(addon.manifest.name)
                    append(" - ")
                    append(catalog.type.replaceFirstChar(Char::titlecase))
                    append(" - ")
                    append(catalog.name ?: "N/A")
                    append(" (")

                    catalog.extra.orEmpty().forEach { e ->
                        if (e.type == ExtraType.SEARCH || e.type == ExtraType.GENRE) {
                            append(
                                e.type.name.first().let { c ->
                                    if (e.isRequired == true) c.uppercase() else c.lowercase()
                                },
                            )
                        }
                    }

                    append(")")
                }.removeSuffix(" ()") to catalog.copy(transportUrl = addon.getTransportUrl().toString())
            }
        }.toTypedArray()
    }

    private var genreList: Array<Pair<String, String>>? = null

    private fun setGenreList(catalog: CatalogDto) {
        val genreExtra = catalog.extra?.firstOrNull { it.type == ExtraType.GENRE }
        val genreOptions = genreExtra?.options

        genreList = if (genreOptions?.isNotEmpty() == true) {
            buildList {
                if (genreExtra.isRequired != true) {
                    add("Any" to "")
                }

                addAll(
                    genreOptions.map { it to it },
                )
            }.toTypedArray()
        } else {
            null
        }
    }

    private var libraryItems: List<LibraryItemDto>? = null
    private var filtersState = FilterState.Unfetched
    private var filterAttempts = 0

    private enum class FilterState {
        Fetching,
        Fetched,
        Unfetched,
    }

    private suspend fun fetchLibrary() {
        if (filtersState == FilterState.Unfetched && filterAttempts < 3) {
            filtersState = FilterState.Fetching
            filterAttempts

            try {
                val body = buildJsonObject {
                    put("all", true)
                    put("authKey", preferences.authKey)
                    put("collection", "libraryItem")
                    putJsonArray("ids") {}
                }.toRequestBody()

                libraryItems = client.post(
                    "$API_URL/api/datastoreGet",
                    body = body,
                    headers = headers,
                ).parseAs<ResultDto<List<LibraryItemDto>>>().result
                    .filterNot { it.removed }

                libraryTypes = libraryItems.orEmpty().map { it.type }.distinct()

                filtersState = FilterState.Fetched
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to fetch library items", e)
                filtersState = FilterState.Unfetched
            }
        }
    }

    class CatalogFilter(
        values: Array<Pair<String, CatalogDto>>,
        selected: Int,
    ) : UriPartFilter<CatalogDto>("Catalog", values, selected)

    class GenreFilter(
        values: Array<Pair<String, String>>,
    ) : UriPartFilter<String>("Genre", values)

    private var libraryTypes: List<String>? = null

    class LibraryTypeFilter(
        values: Array<Pair<String, String>>,
    ) : UriPartFilter<String>("Library Type", values)

    enum class LibrarySort {
        LAST_WATCHED,
        AZ,
        ZA,
        MOST_WATCHED,
        WATCHED,
        NOT_WATCHED,
    }

    class LibrarySortFilter :
        UriPartFilter<LibrarySort>(
            "Library Sort",
            arrayOf(
                "Last Watched" to LibrarySort.LAST_WATCHED,
                "A-Z" to LibrarySort.AZ,
                "Z-A" to LibrarySort.ZA,
                "Most Watched" to LibrarySort.MOST_WATCHED,
                "Watched" to LibrarySort.WATCHED,
                "Not Watched" to LibrarySort.NOT_WATCHED,
            ),
        )

    override fun getFilterList(): AnimeFilterList {
        if (preferences.authKey.isNotBlank() && preferences.fetchLibrary) {
            scope.launch { fetchLibrary() }
        }

        val filters = buildList<AnimeFilter<*>> {
            if (catalogList == null) {
                add(AnimeFilter.Header("Press 'Reset' after loading Popular to load catalogs"))
            } else {
                add(CatalogFilter(catalogList!!, selectedCatalogIndex))
            }

            add(AnimeFilter.Separator())
            add(AnimeFilter.Header("Press 'Filter', then 'Reset' after selecting a catalog to load genres, if present"))

            if (genreList != null) {
                add(GenreFilter(genreList!!))
            }

            if (preferences.authKey.isNotBlank() && preferences.fetchLibrary) {
                add(AnimeFilter.Separator())

                if (libraryItems == null) {
                    add(AnimeFilter.Header("Press 'Reset' to attempt fetching library items"))
                } else {
                    val validLibraryTypes = buildList {
                        add("None" to "")
                        add("All" to "all")
                        addAll(
                            libraryTypes.orEmpty().map {
                                it.replaceFirstChar { char ->
                                    char.titlecase()
                                } to it
                            },
                        )
                    }.toTypedArray()

                    add(
                        AnimeFilter.Header(
                            "Select anything except 'None' to search your library. Note: overrides and ignores other filters",
                        ),
                    )
                    add(LibraryTypeFilter(validLibraryTypes))
                    add(LibrarySortFilter())
                }
            }

            add(AnimeFilter.Header(""))
        }

        return AnimeFilterList(filters)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String {
        val (type, id) = anime.url.split("-", limit = 2)

        return baseUrl.toHttpUrl().newBuilder()
            .fragment("/detail/$type/$id")
            .build().toString()
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, id) = anime.url.split("-", limit = 2)

        val validAddons = addons().filter {
            it.manifest.isValidResource(AddonResource.META, type, id)
        }

        validAddons.forEach { addon ->
            getMeta(addon, type, id)?.let {
                return it.toSAnime()
            }
        }

        return anime
    }

    private suspend fun getMeta(addonDto: AddonDto, type: String, id: String): MetaDto? = try {
        client.get(
            addonDto.getTransportUrl().newBuilder().apply {
                addPathSegment("meta")
                addPathSegment(type)
                addPathSegment(id)
            }.build().toString() +
                ".json",
            headers,
        ).parseAs<MetaResultDto>().meta
    } catch (_: Exception) {
        null
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (type, id) = episode.url.split("-", limit = 2)
        val entryId = id.substringBefore(":")

        return baseUrl.toHttpUrl().newBuilder()
            .fragment("/detail/$type/$entryId/$id")
            .build().toString()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, id) = anime.url.split("-", limit = 2)

        if (type.equals("movie", true)) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "$type-$id"
                },
            )
        }

        val validAddons = addons().filter {
            it.manifest.isValidResource(AddonResource.META, type, id)
        }

        val skipSeason0 = preferences.skipSeason0
        val nameTemplate = preferences.nameTemplate
        val scanlatorTemplate = preferences.scanlatorTemplate

        validAddons.forEach { addon ->
            getMeta(addon, type, id)?.let { meta ->
                // Tv
                if (type.equals("tv", true) && meta.streams?.isNotEmpty() == true) {
                    val stream = meta.streams.first()
                    return listOf(
                        SEpisode.create().apply {
                            name = "${stream.description ?: ""} (${stream.name})".replace("()", "").trim()
                            episode_number = 1F
                            url = "$type-$id"
                        },
                    )
                }

                // Other
                meta.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
                    return videos
                        .filterNot { skipSeason0 && it.season == 0 }
                        .sortedWith(
                            compareBy(
                                { it.season ?: 1 },
                                { it.episode ?: 1 },
                            ),
                        )
                        .reversed()
                        .map { it.toSEpisode(nameTemplate, scanlatorTemplate, type) }
                }
            }
        }

        return emptyList()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (type, id) = episode.url.split("-", limit = 2)

        val subtitles = getSubtitleList(type, id)
        val serverUrl = preferences.serverUrl.takeIf { it.isNotEmpty() }

        return addons()
            .filter { it.manifest.isValidResource(AddonResource.STREAM, type, id) }
            .parallelCatchingFlatMap { addon ->
                val url = addon.getTransportUrl().newBuilder().apply {
                    addPathSegment("stream")
                    addPathSegment(type)
                    addPathSegment(id)
                }.build().toString() +
                    ".json"

                client.get(url, headers)
                    .parseAs<StreamResultDto>()
                    .streams
                    .map { v -> v.toVideo(serverUrl, subtitles) }
            }
            .filterNotNull()
    }

    private suspend fun getSubtitleList(type: String, id: String): List<Track> = addons()
        .filter { it.manifest.isValidResource(AddonResource.SUBTITLES, type, id) }
        .parallelCatchingFlatMap { addon ->
            val url = addon.getTransportUrl().newBuilder().apply {
                addPathSegment("subtitles")
                addPathSegment(type)
                addPathSegment(id)
            }.build().toString() +
                ".json"

            client.get(url, headers)
                .parseAs<SubtitleResultDto>()
                .subtitles
                .map { s -> Track(url = s.url, lang = "(${addon.manifest.name}) ${s.lang}") }
        }

    // ============================= Utilities ==============================

    companion object {
        const val API_URL = "https://api.strem.io"
        private const val LIBRARY_PAGE_SIZE = 100

        private const val WEBUI_URL_KEY = "host_url"
        private const val WEBUI_URL_DEFAULT = "https://app.strem.io/shell-v4.4"

        private const val SERVER_URL_KEY = "server_url"
        private const val SERVER_URL_DEFAULT = ""

        const val ADDONS_KEY = "addons"
        const val ADDONS_DEFAULT = ""

        private const val EMAIL_KEY = "email"
        private const val EMAIL_DEFAULT = ""

        private const val PASSWORD_KEY = "password"
        private const val PASSWORD_DEFAULT = ""

        private const val PREF_SKIP_SEASON_0_KEY = "pref_skip_season_0"
        private const val PREF_SKIP_SEASON_0_DEFAULT = false

        private const val PREF_FETCH_LIBRARY_KEY = "pref_fetch_library"
        private const val PREF_FETCH_LIBRARY_DEFAULT = false

        const val AUTHKEY_KEY = ""

        private val SUBSTITUTE_VALUES = mapOf(
            "name" to "",
            "episodeNumber" to "",
            "seasonNumber" to "",
            "description" to "",
        )
        private val STRING_SUBSTITUTOR = StringSubstitutor(SUBSTITUTE_VALUES, "{", "}").apply {
            isEnableUndefinedVariableException = true
        }
        private val SUBSTITUTE_DIALOG_MESSAGE = """
        |Supported placeholders:
        |- {name}: Episode name
        |- {episodeNumber}: Episode number
        |- {seasonNumber}: Season number
        |- {description}: Episode description
        |If you wish to place some text between curly brackets, place the escape character "$"
        |before the opening curly bracket, e.g. ${'$'}{name}.
        """.trimMargin()

        private const val PREF_EPISODE_NAME_TEMPLATE_KEY = "pref_episode_name_template"
        private const val PREF_EPISODE_NAME_TEMPLATE_DEFAULT = "Season {seasonNumber} Ep. {episodeNumber} - {name}"

        private const val PREF_SCANLATOR_NAME_TEMPLATE_KEY = "pref_scanlator_name_template"
        private const val PREF_SCANLATOR_NAME_TEMPLATE_DEFAULT = ""

        private const val LOG_TAG = "Stremio"
    }

    // ============================ Preferences =============================

    private var SharedPreferences.authKey by authKeyDelegate
    private val SharedPreferences.webUIUrl by preferences.delegate(WEBUI_URL_KEY, WEBUI_URL_DEFAULT)
    private val SharedPreferences.serverUrl by preferences.delegate(SERVER_URL_KEY, SERVER_URL_DEFAULT)
    private val SharedPreferences.email by preferences.delegate(EMAIL_KEY, EMAIL_DEFAULT)
    private val SharedPreferences.password by preferences.delegate(PASSWORD_KEY, PASSWORD_DEFAULT)
    private val SharedPreferences.nameTemplate by preferences.delegate(
        PREF_EPISODE_NAME_TEMPLATE_KEY,
        PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
    )
    private val SharedPreferences.scanlatorTemplate by preferences.delegate(
        PREF_SCANLATOR_NAME_TEMPLATE_KEY,
        PREF_SCANLATOR_NAME_TEMPLATE_DEFAULT,
    )
    private val SharedPreferences.skipSeason0 by preferences.delegate(
        PREF_SKIP_SEASON_0_KEY,
        PREF_SKIP_SEASON_0_DEFAULT,
    )
    private val SharedPreferences.fetchLibrary by preferences.delegate(
        PREF_FETCH_LIBRARY_KEY,
        PREF_FETCH_LIBRARY_DEFAULT,
    )

    private fun SharedPreferences.clearCredentials() {
        edit()
            .remove(AUTHKEY_KEY)
            .apply()
    }

    private fun SharedPreferences.clearLogin() {
        edit()
            .remove(EMAIL_KEY)
            .remove(PASSWORD_KEY)
            .apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loginJob: Job? = null
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val logOutSummary: (String) -> String = {
            if (it.isBlank()) {
                "Currently not logged in"
            } else {
                "Press to log out"
            }
        }
        val logOutPref = screen.getSwitchPreference(
            key = "_unused",
            default = false,
            title = "Log out",
            summary = logOutSummary(preferences.authKey),
            restartRequired = true,
            enabled = preferences.authKey.isNotBlank(),
            onChange = { pref, _ ->
                pref.setEnabled(false)
                pref.summary = logOutSummary("")

                preferences.clearCredentials()
                preferences.clearLogin()

                true
            },
        )

        val getLibrarySummary: (String) -> String = {
            if (it.isBlank()) {
                "Currently not logged in"
            } else {
                "Please keep disabled if not used to reduce number of requests"
            }
        }
        val getLibraryPref = screen.getSwitchPreference(
            key = PREF_FETCH_LIBRARY_KEY,
            default = PREF_FETCH_LIBRARY_DEFAULT,
            title = "Fetch library",
            summary = getLibrarySummary(preferences.authKey),
            enabled = preferences.authKey.isNotBlank(),
        )

        val webUiSummary: (String) -> String = { it.ifBlank { "WebUI url (used only for WebView)" } }
        screen.addEditTextPreference(
            key = WEBUI_URL_KEY,
            title = "WebUI Url",
            default = WEBUI_URL_DEFAULT,
            summary = webUiSummary(baseUrl),
            getSummary = webUiSummary,
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = { "The URL is invalid, malformed, or ends with a slash" },
        ) { baseUrl = it }

        val serverUrlSummary: (String) -> String = { it.ifBlank { "Server url for torrent streams (optional)" } }
        screen.addEditTextPreference(
            key = SERVER_URL_KEY,
            title = "Server Url",
            default = SERVER_URL_DEFAULT,
            summary = serverUrlSummary(preferences.serverUrl),
            getSummary = serverUrlSummary,
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = { "The URL is invalid, malformed, or ends with a slash" },
        )

        val isValidAddon: (String) -> Boolean = {
            it.endsWith("/manifest.json") &&
                (it.startsWith("stremio://") || it.startsWith("http"))
        }
        screen.addEditTextPreference(
            key = ADDONS_KEY,
            default = ADDONS_DEFAULT,
            title = "Addons",
            summary = "Manually specify addons, overrides addons from logged in user",
            dialogMessage = "Separate manifest urls with a newline",
            validate = {
                val urls = it.split("\n")
                urls.all(isValidAddon)
            },
            validationMessage = {
                val urls = it.split("\n")
                val invalidUrl = urls.firstOrNull { url -> !isValidAddon(url) } ?: it
                "'$invalidUrl' is not a valid stremio addon"
            },
        )

        fun onCompleteLogin(result: Boolean) {
            logOutPref.setEnabled(result)
            logOutPref.summary = logOutSummary(if (result) "unused" else "")
            getLibraryPref.setEnabled(result)
            getLibraryPref.summary = getLibrarySummary(if (result) "unused" else "")

            if (!result) {
                preferences.clearCredentials()
                preferences.clearLogin()
            }
        }

        fun logIn() {
            logOutPref.setEnabled(false)
            logOutPref.summary = "Loading..."
            preferences.clearCredentials()

            loginJob?.cancel()
            loginJob = scope.launch {
                try {
                    val body = buildJsonObject {
                        put("email", preferences.email)
                        put("facebook", false)
                        put("password", preferences.password)
                        put("type", "Login")
                    }.toRequestBody()

                    val loginDto = client.post("$API_URL/api/login", body = body).parseAs<ResultDto<LoginDto>>()
                    val authKey = loginDto.result.authKey

                    displayToast("Login successful")
                    handler.post {
                        preferences.authKey = authKey
                        onCompleteLogin(true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    Log.e(LOG_TAG, "Failed to login", e)
                    displayToast("Login failed")
                    handler.post {
                        onCompleteLogin(false)
                    }
                }
            }
        }

        val emailSummary: (String) -> String = { it.ifBlank { "Log in with account (optional)" } }
        screen.addEditTextPreference(
            key = EMAIL_KEY,
            title = "Email",
            default = EMAIL_DEFAULT,
            summary = emailSummary(preferences.email),
            getSummary = emailSummary,
            dialogMessage = "Email address",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        ) {
            if (preferences.password.isNotBlank()) {
                logIn()
            }
        }

        val passwordSummary: (String) -> String = {
            if (it.isBlank()) "The user account password" else "•".repeat(it.length)
        }
        screen.addEditTextPreference(
            key = PASSWORD_KEY,
            title = "Password",
            default = PASSWORD_DEFAULT,
            summary = passwordSummary(preferences.password),
            getSummary = passwordSummary,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) {
            if (preferences.email.isNotBlank()) {
                logIn()
            }
        }

        screen.addEditTextPreference(
            key = PREF_EPISODE_NAME_TEMPLATE_KEY,
            title = "Episode name format",
            summary = "Customize how episode names appear",
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
            dialogMessage = SUBSTITUTE_DIALOG_MESSAGE,
            validate = {
                try {
                    STRING_SUBSTITUTOR.replace(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = { "Invalid episode name format" },
        )

        screen.addEditTextPreference(
            key = PREF_SCANLATOR_NAME_TEMPLATE_KEY,
            title = "Scanlator format",
            summary = "Customize how scanlator appear",
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_SCANLATOR_NAME_TEMPLATE_DEFAULT,
            dialogMessage = SUBSTITUTE_DIALOG_MESSAGE,
            validate = {
                try {
                    STRING_SUBSTITUTOR.replace(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = { "Invalid scanlator format" },
        )

        screen.addSwitchPreference(
            key = PREF_SKIP_SEASON_0_KEY,
            default = PREF_SKIP_SEASON_0_DEFAULT,
            title = "Skip season 0",
            summary = "Filter out specials",
        )

        screen.addPreference(logOutPref)
        screen.addPreference(getLibraryPref)
    }
}
