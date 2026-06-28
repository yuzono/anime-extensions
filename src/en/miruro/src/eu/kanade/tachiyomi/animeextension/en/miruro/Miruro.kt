package eu.kanade.tachiyomi.animeextension.en.miruro

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.anilib.AniLib
import aniyomi.lib.anilib.DescriptionOptions
import aniyomi.lib.anilib.FillerType
import aniyomi.lib.anilib.MediaCoverImage
import aniyomi.lib.anilib.MediaSnapshot
import aniyomi.lib.anilib.MediaStudios
import aniyomi.lib.anilib.MediaTitle
import aniyomi.lib.anilib.StudioEdge
import aniyomi.lib.anilib.StudioNode
import aniyomi.lib.anilib.buildDescription
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.decodeHex
import keiyoushi.utils.delegate
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class Miruro :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Miruro.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override var baseUrl by LazyMutable { preferences.preferredMirror }

    private val SharedPreferences.preferredMirror by preferences.delegate(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT)
    private val SharedPreferences.includeAllSubTypes by preferences.delegate(PREF_INCLUDE_ALL_SUB_TYPES_KEY, PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT)
    private val SharedPreferences.stripHtml by preferences.delegate(PREF_STRIP_HTML_KEY, PREF_STRIP_HTML_DEFAULT)
    private val SharedPreferences.mergeAcrossProviders by preferences.delegate(PREF_MERGE_PROVIDERS_KEY, PREF_MERGE_PROVIDERS_DEFAULT)
    private val SharedPreferences.preferredTitleStyle by preferences.delegate(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
    private val SharedPreferences.preferredProvider by preferences.delegate(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
    private val SharedPreferences.preferredSubType by preferences.delegate(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)
    private val SharedPreferences.preferredStreamType by preferences.delegate(PREF_STREAM_TYPE_KEY, PREF_STREAM_TYPE_DEFAULT)
    private val SharedPreferences.preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.episodeSortOrder by preferences.delegate(PREF_EPISODE_SORT_KEY, PREF_EPISODE_SORT_DEFAULT)
    private val SharedPreferences.descriptionTruncation by preferences.delegate(PREF_DESCRIPTION_TRUNCATE_KEY, PREF_DESCRIPTION_TRUNCATE_DEFAULT)
    private val SharedPreferences.showProviderInScanlator by preferences.delegate(PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY, PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT)
    private val SharedPreferences.includeAllProviders by preferences.delegate(PREF_INCLUDE_ALL_PROVIDERS_KEY, PREF_INCLUDE_ALL_PROVIDERS_DEFAULT)
    private val SharedPreferences.fillerDisplayMode by preferences.delegate(PREF_FILLER_DISPLAY_KEY, PREF_FILLER_DISPLAY_DEFAULT)
    private val SharedPreferences.fillerMarkMixed by preferences.delegate(PREF_FILLER_MARK_MIXED_KEY, PREF_FILLER_MARK_MIXED_DEFAULT)

    private val extractor by lazy {
        MiruroExtractor(client, PIPE_KEY, headers) { providerDisplayName(it) }
    }

    private enum class ConfigFetchState {
        NOT_FETCHED,
        FETCHING,
        FETCHED,
        FAILED,
    }

    private data class MirrorCache(
        val entries: List<String>, // display names e.g. "miruro.tv" — only UP mirrors
        val values: List<String>, // full URLs e.g. "https://www.miruro.tv" — only UP mirrors
    )

    private data class ProviderConfigCache(
        val displayNames: Map<String, String>, // alias → base name, e.g. "kiwi" → "AnimePahe"
        val entries: List<String>, // full UI labels, e.g. "AnimePahe (Sub, Download)"
        val values: List<String>,
        val config: ConfigResponseDto?,
    )

    @Volatile
    private var configCache = ProviderConfigCache(
        displayNames = KNOWN_DISPLAY_NAMES,
        entries = DEFAULT_PROVIDER_ENTRIES,
        values = DEFAULT_PROVIDER_VALUES,
        config = null,
    )

    @Volatile
    private var fetchState = ConfigFetchState.NOT_FETCHED

    private var fetchAttempts = 0

    @Volatile
    private var mirrorCache = MirrorCache(
        entries = DEFAULT_MIRROR_ENTRIES,
        values = DEFAULT_MIRROR_VALUES,
    )

    @Volatile
    private var mirrorFetchState = ConfigFetchState.NOT_FETCHED

    private var mirrorFetchAttempts = 0

    private fun providerDisplayName(alias: String): String = configCache.displayNames[alias] ?: alias.replaceFirstChar { it.uppercase() }

    private fun buildProviderBaseName(
        alias: String,
        providerConfig: ConfigResponseDto.ProviderConfigDto?,
    ): String {
        val baseName = KNOWN_DISPLAY_NAMES[alias] ?: alias.replaceFirstChar { it.uppercase() }
        val parent = providerConfig?.parent
        val relationship = providerConfig?.relationship
        return if (parent != null && relationship == "embed") {
            val parentName = KNOWN_DISPLAY_NAMES[parent] ?: parent.replaceFirstChar { it.uppercase() }
            "$baseName (via $parentName)"
        } else {
            baseName
        }
    }

    private fun buildCapabilityLabel(caps: ConfigResponseDto.ProviderCapabilitiesDto?): String {
        if (caps == null) return ""
        val labels = mutableListOf<String>()
        if (caps.sub) labels.add("Sub")
        if (caps.dub) labels.add("Dub")
        if (caps.ssub) labels.add("Soft Sub")
        if (caps.download) labels.add("Download")
        return if (labels.isNotEmpty()) " (${labels.joinToString(", ")})" else ""
    }

    private fun buildProviderLists(
        config: ConfigResponseDto,
    ): Triple<Map<String, String>, List<String>, List<String>> {
        val visibleNativeProviders = config.providerOrder.filter { key ->
            val pc = config.streaming[key]
            pc != null && pc.visible && pc.relationship != "embed"
        }

        val displayNames = mutableMapOf<String, String>()
        displayNames.putAll(KNOWN_DISPLAY_NAMES)
        for (key in visibleNativeProviders) {
            if (key !in displayNames) {
                displayNames[key] = key.replaceFirstChar { it.uppercase() }
            }
            val pc = config.streaming[key]
            if (pc != null) {
                displayNames[key] = buildProviderBaseName(key, pc)
            }
        }

        val entries = visibleNativeProviders.map { alias ->
            val baseName = displayNames[alias] ?: alias.replaceFirstChar { it.uppercase() }
            val caps = config.streaming[alias]?.capabilities
            baseName + buildCapabilityLabel(caps)
        }
        val values = visibleNativeProviders.ifEmpty { DEFAULT_PROVIDER_VALUES }

        return Triple(displayNames, entries, values)
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private var configFetchJob: Job? = null
    private var mirrorFetchJob: Job? = null

    /**
     * Trigger an async config fetch if not already fetched/fetching.
     * Non-blocking — returns immediately. Results are available via [configCache] when done.
     * [onSuccess] is invoked on the main thread after a successful fetch, useful for
     * updating preference UI dynamically.
     */
    private fun launchConfigFetch(forceRefresh: Boolean = false, onSuccess: (() -> Unit)? = null) {
        if (!forceRefresh) {
            if (fetchState == ConfigFetchState.FETCHED || fetchState == ConfigFetchState.FETCHING) return
            if (fetchState == ConfigFetchState.FAILED && fetchAttempts >= MAX_FETCH_ATTEMPTS) {
                logD { "launchConfigFetch: skipping, $fetchAttempts/$MAX_FETCH_ATTEMPTS attempts failed" }
                return
            }
        }

        logD { "launchConfigFetch: forceRefresh=$forceRefresh, currentState=$fetchState, attempts=$fetchAttempts" }
        fetchState = ConfigFetchState.FETCHING
        if (forceRefresh) fetchAttempts = 0
        configFetchJob?.cancel()

        configFetchJob = scope.launch(
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Config fetch failed with exception", throwable)
            },
        ) {
            try {
                val request = buildPipeRequest("config", "GET")
                val json = client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Config endpoint returned ${response.code}, keeping defaults")
                        fetchAttempts++
                        fetchState = ConfigFetchState.FAILED
                        return@launch
                    }
                    extractor.decryptResponse(response)
                }
                if (json.isEmpty()) {
                    Log.w(TAG, "Config response was empty, keeping defaults")
                    fetchAttempts++
                    fetchState = ConfigFetchState.FAILED
                    return@launch
                }

                val config = jsonParser.decodeFromString<ConfigResponseDto>(json)
                val (newDisplayNames, newEntries, newValues) = buildProviderLists(config)

                configCache = ProviderConfigCache(
                    displayNames = newDisplayNames,
                    entries = if (newValues != DEFAULT_PROVIDER_VALUES) newEntries else DEFAULT_PROVIDER_ENTRIES,
                    values = newValues,
                    config = config,
                )
                saveConfigToPrefs(config)
                fetchState = ConfigFetchState.FETCHED

                val visibleNative = config.providerOrder.filter { key ->
                    val pc = config.streaming[key]
                    pc != null && pc.visible && pc.relationship != "embed"
                }
                if (visibleNative.isNotEmpty()) {
                    val currentProvider = preferences.preferredProvider
                    if (currentProvider !in visibleNative) {
                        val newDefault = PREF_PROVIDER_DEFAULT.takeIf { it in visibleNative }
                            ?: visibleNative.firstOrNull()
                            ?: currentProvider
                        Log.i(TAG, "Preferred provider '$currentProvider' no longer available, resetting to '$newDefault'")
                        preferences.edit().putString(PREF_PROVIDER_KEY, newDefault).apply()
                    }
                }

                Log.i(TAG, "Fetched site config: ${config.providerOrder.size} providers (${visibleNative.size} visible+native), order=$visibleNative")

                onSuccess?.let { handler.post(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch config: ${e.message}, keeping defaults")
                fetchAttempts++
                fetchState = ConfigFetchState.FAILED
            }
        }
    }

    /**
     * Returns the cached [ConfigResponseDto] synchronously.
     * If not yet fetched, tries loading from SharedPreferences first,
     * then triggers a background fetch and returns the default config.
     * Never blocks — always returns immediately.
     */
    private fun getConfigSync(): ConfigResponseDto {
        if (fetchState == ConfigFetchState.NOT_FETCHED) {
            logD { "getConfigSync: state=NOT_FETCHED, attempting prefs load then async fetch" }
            loadConfigFromPrefs()?.let { cached ->
                configCache = cached
                logD { "getConfigSync: loaded cached config from prefs (${cached.values.size} providers)" }
            }
            launchConfigFetch()
        }
        val result = configCache.config ?: defaultConfig
        logD { "getConfigSync: returning ${if (configCache.config != null) "fetched" else "default"} config" }
        return result
    }

    // ============================== Mirror Fetch ==============================

    private fun launchMirrorFetch(forceRefresh: Boolean = false, onSuccess: (() -> Unit)? = null) {
        if (!forceRefresh) {
            if (mirrorFetchState == ConfigFetchState.FETCHED || mirrorFetchState == ConfigFetchState.FETCHING) return
            if (mirrorFetchState == ConfigFetchState.FAILED && mirrorFetchAttempts >= MAX_FETCH_ATTEMPTS) {
                logD { "launchMirrorFetch: skipping, $mirrorFetchAttempts/$MAX_FETCH_ATTEMPTS attempts failed" }
                return
            }
        }

        logD { "launchMirrorFetch: forceRefresh=$forceRefresh, currentState=$mirrorFetchState, attempts=$mirrorFetchAttempts" }
        mirrorFetchState = ConfigFetchState.FETCHING
        if (forceRefresh) mirrorFetchAttempts = 0
        mirrorFetchJob?.cancel()

        mirrorFetchJob = scope.launch(
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Mirror fetch failed with exception", throwable)
            },
        ) {
            try {
                val statusJson = statusPageClient.newCall(
                    GET("$STATUS_PAGE_API_URL/status-page/miruro"),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Mirror status page returned ${response.code}, keeping defaults")
                        mirrorFetchAttempts++
                        mirrorFetchState = ConfigFetchState.FAILED
                        return@launch
                    }
                    response.body.string()
                }

                val statusPage = jsonParser.decodeFromString<StatusPageDto>(statusJson)

                val websitesGroup = statusPage.publicGroupList.firstOrNull { it.name == "Websites" }
                if (websitesGroup == null) {
                    Log.w(TAG, "launchMirrorFetch: no 'Websites' group in status page, keeping defaults")
                    mirrorFetchAttempts++
                    mirrorFetchState = ConfigFetchState.FAILED
                    return@launch
                }

                // Fetch heartbeat data to determine which mirrors are UP
                val heartbeatJson = statusPageClient.newCall(
                    GET("$STATUS_PAGE_API_URL/status-page/heartbeat/miruro"),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Mirror heartbeat page returned ${response.code}, using all mirrors without status check")
                        null
                    } else {
                        response.body.string()
                    }
                }

                val upMirrorIds: Set<String>? = heartbeatJson?.let { json ->
                    val heartbeats = jsonParser.decodeFromString<StatusHeartbeatDto>(json)
                    heartbeats.heartbeatList.mapValues { (_, beats) ->
                        beats.lastOrNull()?.status == 1
                    }.filterValues { it }.keys.toSet()
                }

                val knownStreamingDomains = setOf("miruro.com")
                val mirrors = if (upMirrorIds != null) {
                    websitesGroup.monitorList
                        .filter { it.id.toString() in upMirrorIds && it.name !in knownStreamingDomains }
                } else {
                    websitesGroup.monitorList
                        .filter { it.name !in knownStreamingDomains }
                }

                if (mirrors.isEmpty()) {
                    Log.w(TAG, "launchMirrorFetch: no UP mirrors found, keeping defaults")
                    mirrorFetchAttempts++
                    mirrorFetchState = ConfigFetchState.FAILED
                    return@launch
                }

                val entries = mirrors.map { it.name }
                val values = mirrors.map { "https://www.${it.name}" }

                mirrorCache = MirrorCache(entries = entries, values = values)
                saveMirrorsToPrefs(entries, values)
                mirrorFetchState = ConfigFetchState.FETCHED

                val currentMirror = preferences.preferredMirror
                if (currentMirror !in values) {
                    val newDefault = PREF_MIRROR_DEFAULT.takeIf { it in values }
                        ?: values.firstOrNull()
                        ?: currentMirror
                    Log.i(TAG, "Preferred mirror '$currentMirror' no longer available, resetting to '$newDefault'")
                    preferences.edit().putString(PREF_MIRROR_KEY, newDefault).apply()
                    baseUrl = newDefault
                }

                Log.i(TAG, "Fetched mirrors: ${mirrors.size} UP mirrors from status page: $entries")

                onSuccess?.let { handler.post(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch mirrors: ${e.message}, keeping defaults")
                mirrorFetchAttempts++
                mirrorFetchState = ConfigFetchState.FAILED
            }
        }
    }

    private fun getMirrorsSync(): MirrorCache {
        if (mirrorFetchState == ConfigFetchState.NOT_FETCHED) {
            logD { "getMirrorsSync: state=NOT_FETCHED, attempting prefs load then async fetch" }
            loadMirrorsFromPrefs()?.let { cached ->
                mirrorCache = cached
                logD { "getMirrorsSync: loaded cached mirrors from prefs (${cached.values.size} mirrors)" }
            }
            launchMirrorFetch()
        }
        return mirrorCache
    }

    private fun getProviderOrder(): List<String> {
        val config = getConfigSync()
        val result = config.providerOrder.filter { key ->
            val pc = config.streaming[key]
            pc != null && pc.visible && pc.relationship != "embed"
        }.ifEmpty { configCache.values }
        logD { "getProviderOrder: ${result.size} providers: $result" }
        return result
    }

    // ============================== Config Persistence ==============================

    private fun saveConfigToPrefs(config: ConfigResponseDto) {
        try {
            val json = jsonParser.encodeToString(config)
            preferences.edit().putString(PREF_CACHED_CONFIG_KEY, json).apply()
            logD { "saveConfigToPrefs: persisted config (${json.length} bytes, ${config.providerOrder.size} providers)" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist config to preferences: ${e.message}")
        }
    }

    private fun loadConfigFromPrefs(): ProviderConfigCache? {
        return try {
            val json = preferences.getString(PREF_CACHED_CONFIG_KEY, null) ?: return null
            logD { "loadConfigFromPrefs: found cached config (${json.length} bytes)" }
            val config = jsonParser.decodeFromString<ConfigResponseDto>(json)
            val (displayNames, entries, values) = buildProviderLists(config)
            ProviderConfigCache(
                displayNames = displayNames,
                entries = if (values != DEFAULT_PROVIDER_VALUES) entries else DEFAULT_PROVIDER_ENTRIES,
                values = values,
                config = config,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached config from preferences: ${e.message}")
            null
        }
    }

    private fun saveMirrorsToPrefs(entries: List<String>, values: List<String>) {
        try {
            val json = jsonParser.encodeToString(
                CachedMirrorsDto(entries = entries, values = values),
            )
            preferences.edit().putString(PREF_CACHED_MIRRORS_KEY, json).apply()
            logD { "saveMirrorsToPrefs: persisted mirrors (${entries.size} mirrors)" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist mirrors to preferences: ${e.message}")
        }
    }

    private fun loadMirrorsFromPrefs(): MirrorCache? {
        return try {
            val json = preferences.getString(PREF_CACHED_MIRRORS_KEY, null) ?: return null
            logD { "loadMirrorsFromPrefs: found cached mirrors (${json.length} bytes)" }
            val cached = jsonParser.decodeFromString<CachedMirrorsDto>(json)
            if (cached.entries.isEmpty() || cached.values.isEmpty()) return null
            MirrorCache(entries = cached.entries, values = cached.values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached mirrors from preferences: ${e.message}")
            null
        }
    }

    private val defaultConfig = ConfigResponseDto(
        streaming = mapOf(
            "kiwi" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, download = true),
            ),
            "bee" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, ssub = true),
            ),
            "bonk" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, ssub = true, download = true, skipTimes = true),
            ),
            "ally" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, download = true),
            ),
            "moo" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, download = true),
            ),
            "hop" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(ssub = true, thumbnails = true),
            ),
            "pewe" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true),
            ),
            "nun" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true),
                relationship = "embed",
            ),
            "bun" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(ssub = true),
                relationship = "embed",
            ),
            "twin" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true, ssub = true),
                relationship = "embed",
            ),
            "cog" to ConfigResponseDto.ProviderConfigDto(
                capabilities = ConfigResponseDto.ProviderCapabilitiesDto(sub = true),
                relationship = "embed",
            ),
        ),
        providerOrder = DEFAULT_PROVIDER_VALUES,
    )

    companion object {
        const val PREFIX_SEARCH = "miruro:"
        private const val TAG = "Miruro"

        private inline fun logD(msg: () -> String) {
            if (BuildConfig.DEBUG) Log.d(TAG, msg())
        }

        private const val MAX_FETCH_ATTEMPTS = 3
        private const val PREF_CACHED_CONFIG_KEY = "cached_config_json"

        private val PIPE_KEY = "71951034f8fbcf53d89db52ceb3dc22c".decodeHex()

        private const val PREF_PROVIDER_KEY = "preferred_provider"
        private const val PREF_PROVIDER_TITLE = "Preferred Provider"

        private val DEFAULT_PROVIDER_ENTRIES = listOf(
            "AnimePahe (Sub, Download)",
            "Anikoto (Sub, Soft Sub)",
            "AniDao (Soft Sub, Download)",
            "9Anime (Sub, Download)",
            "Moon (Sub, Download)",
            "Zoro (Soft Sub)",
            "Pewe (Hard Sub)",
            "Nun (Hard Sub, Embed)",
            "Bun (Soft Sub, Embed)",
            "Twin (Soft Sub, Embed)",
            "Cog (Hard Sub, Embed)",
        )
        private val DEFAULT_PROVIDER_VALUES = listOf("kiwi", "bee", "bonk", "ally", "moo", "hop", "pewe", "nun", "bun", "twin", "cog")
        private const val PREF_PROVIDER_DEFAULT = "kiwi"

        private val KNOWN_DISPLAY_NAMES = mapOf(
            "kiwi" to "AnimePahe",
            "bee" to "Anikoto",
            "hop" to "Zoro",
            "ally" to "9Anime",
            "bonk" to "AniDao",
            "pewe" to "Pewe",
            "nun" to "Nun",
            "bun" to "Bun",
            "twin" to "Twin",
            "moo" to "Moon",
            "cog" to "Cog",
            "dune" to "Dune",
            "kuz" to "Kuz",
        )

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub/Dub"
        private val PREF_SUB_TYPE_ENTRIES = listOf("Sub", "Dub", "Soft Sub")
        private val PREF_SUB_TYPE_VALUES = listOf("sub", "dub", "ssub")
        private const val PREF_SUB_TYPE_DEFAULT = "sub"

        private const val PREF_STREAM_TYPE_KEY = "preferred_stream_type"
        private const val PREF_STREAM_TYPE_TITLE = "Preferred Stream Type"
        private val PREF_STREAM_TYPE_ENTRIES = listOf("HLS", "Embed", "All")
        private val PREF_STREAM_TYPE_VALUES = listOf("hls", "embed", "all")
        private const val PREF_STREAM_TYPE_DEFAULT = "hls"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private val PREF_QUALITY_ENTRIES = listOf("Highest Available", "1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("0", "1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "0"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_TITLE = "Title Display Style"
        private val PREF_TITLE_STYLE_ENTRIES = listOf("User Preferred", "Romaji", "English", "Native")
        private val PREF_TITLE_STYLE_VALUES = listOf("userPreferred", "romaji", "english", "native")
        private const val PREF_TITLE_STYLE_DEFAULT = "userPreferred"

        private const val PREF_INCLUDE_ALL_SUB_TYPES_KEY = "include_all_sub_types"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_TITLE = "Include all sub/dub streams"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT = true

        private const val PREF_STRIP_HTML_KEY = "strip_html_descriptions"
        private const val PREF_STRIP_HTML_TITLE = "Strip HTML from descriptions"
        private const val PREF_STRIP_HTML_DEFAULT = true

        private const val PREF_MERGE_PROVIDERS_KEY = "merge_across_providers"
        private const val PREF_MERGE_PROVIDERS_TITLE = "Merge episodes across providers"
        private const val PREF_MERGE_PROVIDERS_DEFAULT = true

        private const val PREF_EPISODE_SORT_KEY = "episode_sort_order"
        private const val PREF_EPISODE_SORT_TITLE = "Episode List Order"
        private val PREF_EPISODE_SORT_ENTRIES = listOf("Descending (Newest First)", "Ascending (Oldest First)")
        private val PREF_EPISODE_SORT_VALUES = listOf("descending", "ascending")
        private const val PREF_EPISODE_SORT_DEFAULT = "descending"

        private const val PREF_DESCRIPTION_TRUNCATE_KEY = "description_truncation"
        private const val PREF_DESCRIPTION_TRUNCATE_TITLE = "Description Truncation"
        private val PREF_DESCRIPTION_TRUNCATE_ENTRIES = listOf("No Limit", "750 characters", "500 characters", "300 characters", "150 characters", "75 characters")
        private val PREF_DESCRIPTION_TRUNCATE_VALUES = listOf("0", "750", "500", "300", "150", "75")
        private const val PREF_DESCRIPTION_TRUNCATE_DEFAULT = "0"

        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY = "show_provider_in_scanlator"
        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_TITLE = "Show provider names in scanlator"
        private const val PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT = false

        private const val PREF_INCLUDE_ALL_PROVIDERS_KEY = "include_all_providers"
        private const val PREF_INCLUDE_ALL_PROVIDERS_TITLE = "Include all provider streams"
        private const val PREF_INCLUDE_ALL_PROVIDERS_DEFAULT = false

        private const val PREF_FILLER_DISPLAY_KEY = "filler_display_mode"
        private const val PREF_FILLER_DISPLAY_TITLE = "Filler Episode Handling"
        private val PREF_FILLER_DISPLAY_ENTRIES = listOf("Mark in scanlator", "Hide filler episodes", "Show all (no marks)")
        private val PREF_FILLER_DISPLAY_VALUES = listOf("mark", "hide", "show")
        private const val PREF_FILLER_DISPLAY_DEFAULT = "mark"

        private const val PREF_FILLER_MARK_MIXED_KEY = "filler_mark_mixed"
        private const val PREF_FILLER_MARK_MIXED_TITLE = "Also mark mixed-canon episodes"
        private const val PREF_FILLER_MARK_MIXED_DEFAULT = true

        private const val PREF_MIRROR_KEY = "preferred_mirror"
        private const val PREF_MIRROR_TITLE = "Preferred mirror"
        private val DEFAULT_MIRROR_ENTRIES = listOf("miruro.tv", "miruro.to", "miruro.bz", "miruro.ru")
        private val DEFAULT_MIRROR_VALUES = DEFAULT_MIRROR_ENTRIES.map { "https://www.$it" }
        private val PREF_MIRROR_DEFAULT = DEFAULT_MIRROR_VALUES.first()
        private const val PREF_CACHED_MIRRORS_KEY = "cached_mirrors_json"
        private const val STATUS_PAGE_API_URL = "https://status.miruro.com/api"

        private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val CLOSE_P_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        val SCANLATOR_SUB_TYPES = setOf("sub", "dub", "ssub", "h-sub")
        val SUB_TYPE_DISPLAY_ORDER = listOf("sub", "dub", "ssub", "h-sub")
    }

    private val statusPageClient: OkHttpClient = network.client.newBuilder()
        .rateLimitHost("$STATUS_PAGE_API_URL/".toHttpUrl(), permits = 1, period = 2.seconds)
        .build()

    // ============================== Trending ===============================

    override fun popularAnimeRequest(page: Int): Request {
        logD { "popularAnimeRequest: page=$page" }
        launchConfigFetch()
        val query = buildPipeQuery(
            "type" to "ANIME",
            "status" to "RELEASING",
            "page" to page,
            "perPage" to 20,
            "sort" to "TRENDING_DESC",
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        logD { "latestUpdatesRequest: page=$page" }
        val query = buildPipeQuery(
            "type" to "ANIME",
            "status" to "RELEASING",
            "page" to page,
            "perPage" to 20,
            "sort" to "UPDATED_AT_DESC",
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    // ============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        logD { "getSearchAnime: query='${query.take(80)}', page=$page" }
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            if (url.pathSegments.getOrNull(0) != "watch") {
                throw Exception("Unsupported url")
            }
            val anilistId = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$anilistId", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val anilistId = query.removePrefix(PREFIX_SEARCH)
            logD { "getSearchAnime: prefix search for anilistId=$anilistId" }
            val request = buildPipeRequest("info/$anilistId", "GET")
            val jsonObj = client.newCall(request).awaitSuccess().use { response ->
                JSONObject(extractor.decryptResponse(response))
            }

            val media = jsonObj.optJSONObject("media") ?: jsonObj

            val id = media.optInt("id", 0)
            val malId = media.optInt("idMal", 0).takeIf { it > 0 }
            if (id > 0) getOrCreateMeta(id, malId)

            val anime = parseAnimeFromMediaObj(media)
            return AnimesPage(listOf(anime), false)
        }

        val request = searchAnimeRequest(page, query, filters)
        return client.newCall(request).awaitSuccess()
            .use(::searchAnimeParse)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        launchConfigFetch()
        if (query.isNotEmpty()) {
            logD { "searchAnimeRequest: text query='$query', page=$page" }
            val perPage = 20
            val queryParams = buildPipeQuery(
                "q" to query,
                "type" to "ANIME",
                "limit" to perPage,
                "offset" to (page - 1) * perPage,
            )
            return buildPipeRequest("search", "GET", query = queryParams)
        }

        val params = MiruroFilters.getSearchParameters(filters)

        val queryParams = buildPipeQuery(
            "type" to "ANIME",
            "page" to page,
            "perPage" to 20,
        )

        if (params.sort != "all") queryParams.put("sort", params.sort)
        if (params.season != "all") queryParams.put("season", params.season)
        if (params.year != "all") queryParams.put("year", params.year.toInt())
        if (params.status != "all") queryParams.put("status", params.status)
        if (params.genres.isNotEmpty()) {
            val genresArray = JSONArray()
            params.genres.forEach { genresArray.put(it) }
            queryParams.put("genre", genresArray)
        }
        if (params.excludedGenres.isNotEmpty()) {
            val excludedGenresArray = JSONArray()
            params.excludedGenres.forEach { excludedGenresArray.put(it) }
            queryParams.put("excludedGenre", excludedGenresArray)
        }
        if (params.formats.isNotEmpty()) {
            val formatsArray = JSONArray()
            params.formats.forEach { formatsArray.put(it) }
            queryParams.put("format", formatsArray)
        }
        if (params.tags.isNotEmpty()) {
            val tagsArray = JSONArray()
            params.tags.forEach { tagsArray.put(it) }
            queryParams.put("tag", tagsArray)
        }
        if (params.excludedTags.isNotEmpty()) {
            val excludedTagsArray = JSONArray()
            params.excludedTags.forEach { excludedTagsArray.put(it) }
            queryParams.put("excludedTag", excludedTagsArray)
        }
        if (params.dubLanguage != "all") queryParams.put("dub", params.dubLanguage)

        return buildPipeRequest("search/browse", "GET", query = queryParams)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response, fallbackKeys = listOf("results", "data"))

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = buildPipeRequest("info/${anime.url}", "GET")

    override fun animeDetailsParse(response: Response): SAnime {
        val json = validateResponse(response).use { extractor.decryptResponse(it) }
        logD { "animeDetailsParse: response length=${json.length}" }

        val mediaJson = try {
            val jsonObj = JSONObject(json)
            jsonObj.optJSONObject("media")?.toString() ?: json
        } catch (_: Exception) {
            json
        }
        val anilistId = try {
            JSONObject(mediaJson).let { it.optJSONObject("media") ?: it }.optInt("id", 0)
        } catch (_: Exception) {
            0
        }

        val snapshot = if (anilistId > 0) {
            try {
                AniLib.fetchMediaDetails(client, anilistId, preferences)
            } catch (e: Exception) {
                logD { "animeDetailsParse: AniLib fetch failed, falling back to pipe data: ${e.message}" }
                null
            }
        } else {
            null
        }

        val malId = snapshot?.malId
            ?: try {
                JSONObject(mediaJson).let { it.optJSONObject("media") ?: it }.optInt("idMal", 0).takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        if (anilistId > 0) {
            val existing = getMeta(anilistId)
            if (existing != null && malId != null) {
                existing.malId = malId
            } else if (existing == null) {
                getOrCreateMeta(anilistId, malId)
            }
        }

        if (snapshot != null) {
            return buildFromSnapshot(snapshot)
        }

        logD { "animeDetailsParse: using pipe API fallback" }
        val dto = try {
            jsonParser.decodeFromString<AnimeMediaDto>(mediaJson)
        } catch (_: Exception) {
            val jsonObj = JSONObject(mediaJson)
            val mediaObj = jsonObj.optJSONObject("media") ?: jsonObj
            return parseAnimeDetailsFromJsonObj(mediaObj)
        }

        return buildFromDto(dto)
    }

    private fun buildFromSnapshot(snapshot: MediaSnapshot): SAnime {
        val titleStyle = preferences.preferredTitleStyle
        val title = AniLib.resolveTitle(snapshot.title, titleStyle)
        val coverUrl = AniLib.resolveCoverUrl(snapshot.coverImage)
            .ifEmpty { snapshot.bannerImage?.ifEmpty { null } ?: "" }
        val status = mapStatus(snapshot.status)
        val studio = AniLib.resolveMainStudio(snapshot.studios)
        val genres = snapshot.genres.takeIf { it.isNotEmpty() }?.joinToString()
        val truncateAt = preferences.descriptionTruncation.toIntOrNull() ?: 0
        val description = snapshot.buildDescription(
            DescriptionOptions(
                stripHtml = preferences.stripHtml,
                truncateAt = truncateAt,
            ),
        )

        return SAnime.create().apply {
            title.takeIf(String::isNotBlank)?.let { this.title = it }
            thumbnail_url = coverUrl
            this.description = description
            genre = genres
            this.status = status
            author = studio
        }
    }

    private fun buildFromDto(dto: AnimeMediaDto): SAnime {
        val titleStyle = preferences.preferredTitleStyle
        val title = AniLib.resolveTitle(
            MediaTitle(
                userPreferred = dto.title?.userPreferred,
                romaji = dto.title?.romaji,
                english = dto.title?.english,
                native = dto.title?.native,
            ),
            titleStyle,
        )
        val coverUrl = AniLib.resolveCoverUrl(
            MediaCoverImage(
                extraLarge = dto.coverImage?.extraLarge,
                large = dto.coverImage?.large,
                medium = dto.coverImage?.medium,
            ),
        ).ifEmpty { dto.bannerImage?.ifEmpty { null } ?: "" }
        val rawDescription = dto.description ?: ""
        val description = if (preferences.stripHtml) stripHtml(rawDescription) else rawDescription
        val truncatedDescription = truncateDescription(description)
        val genres = dto.genres.takeIf { it.isNotEmpty() }?.joinToString()
        val status = mapStatus(dto.status)
        val studio = AniLib.resolveMainStudio(
            MediaStudios(
                edges = dto.studios?.edges?.map { edge ->
                    StudioEdge(
                        isMain = edge.isMain,
                        node = StudioNode(name = edge.node?.name),
                    )
                },
            ),
        )

        return SAnime.create().apply {
            title.takeIf(String::isNotBlank)?.let { this.title = it }
            thumbnail_url = coverUrl
            this.description = truncatedDescription
            genre = genres
            this.status = status
            author = studio
        }
    }

    private fun mapStatus(status: String?): Int = when (status?.uppercase()) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "CANCELLED" -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }

    private fun parseAnimeDetailsFromJsonObj(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitle(titleObj, titleStyle)

        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))
        val coverUrl = thumbnail.ifEmpty { bannerImage }

        val rawDescription = media.optString("description", "")
        val description = if (preferences.stripHtml) stripHtml(rawDescription) else rawDescription
        val truncatedDescription = truncateDescription(description)

        val genresArray = media.optJSONArray("genres")
        val genres = if (genresArray != null) {
            (0 until genresArray.length()).mapNotNull { genresArray.optString(it) }.joinToString()
        } else {
            null
        }

        val status = mapStatus(media.optString("status", ""))

        val studio = extractMainStudio(media.opt("studios"))

        val anilistId = media.optInt("id", 0)
        val malId = media.optInt("idMal", 0).takeIf { it > 0 }
        if (anilistId > 0) {
            val existing = getMeta(anilistId)
            if (existing != null && malId != null) {
                existing.malId = malId
            } else if (existing == null) {
                getOrCreateMeta(anilistId, malId)
            }
        }

        return SAnime.create().apply {
            title.takeIf(String::isNotBlank)?.let { this.title = it }
            thumbnail_url = coverUrl
            this.description = truncatedDescription
            genre = genres
            this.status = status
            author = studio
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val anilistId = anime.url.toIntOrNull()
            ?: throw IOException("Invalid anime URL: ${anime.url}")
        val query = buildPipeQuery(
            "anilistId" to anilistId,
        )
        return buildPipeRequest("episodes", "GET", query = query)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonObj = JSONObject(validateResponse(response).use { extractor.decryptResponse(it) })

        val providers = jsonObj.optJSONObject("providers") ?: run {
            Log.w(TAG, "episodeListParse: no 'providers' object in response")
            return emptyList()
        }
        val preferredProvider = preferences.preferredProvider
        val preferredSubType = preferences.preferredSubType
        val mergeAcrossProviders = preferences.mergeAcrossProviders
        val showProvider = preferences.showProviderInScanlator

        val anilistId = extractAnilistIdFromPipeRequest(response.request.url.toString())
        logD { "episodeListParse: anilistId=$anilistId, preferred=$preferredProvider, subType=$preferredSubType, merge=$mergeAcrossProviders" }

        val availableProviders = providers.keys().asSequence().toList()
        logD { "episodeListParse: available providers from response: $availableProviders" }
        val providerOrder = getProviderOrder()
        val primaryProvider = if (providers.optJSONObject(preferredProvider)?.optJSONObject("episodes") != null) {
            preferredProvider
        } else {
            providerOrder.firstOrNull { it in availableProviders && providers.optJSONObject(it)?.optJSONObject("episodes") != null }
                ?: availableProviders.firstOrNull { key -> providers.optJSONObject(key)?.optJSONObject("episodes") != null }
                ?: run {
                    Log.w(TAG, "episodeListParse: no provider has episodes, returning empty list")
                    return emptyList()
                }
        }

        if (primaryProvider != preferredProvider) {
            logD { "episodeListParse: preferred '$preferredProvider' has no episodes, fell back to '$primaryProvider'" }
        }

        val crossProviderMap = mutableMapOf<Float, MutableMap<String, MutableMap<String, String>>>()
        val episodeMetaMap = mutableMapOf<Float, Pair<Double, String>>()
        val providerSubTypesMap = mutableMapOf<String, List<String>>()

        for (providerKey in availableProviders) {
            val providerData = providers.optJSONObject(providerKey) ?: continue
            val episodesObj = providerData.optJSONObject("episodes") ?: continue
            val subTypes = episodesObj.keys().asSequence().toList()
            providerSubTypesMap[providerKey] = subTypes

            for (subType in subTypes) {
                val typeEpisodes = episodesObj.optJSONArray(subType) ?: continue
                for (i in 0 until typeEpisodes.length()) {
                    val epJson = typeEpisodes.getJSONObject(i)
                    val number = epJson.optDouble("number", 0.0).toFloat()
                    val id = epJson.optString("id", "")
                    val title = epJson.optString("title", "")

                    val providerEpIds = crossProviderMap.getOrPut(number) { mutableMapOf() }
                        .getOrPut(providerKey) { mutableMapOf() }
                    providerEpIds[subType] = id

                    if (number !in episodeMetaMap) {
                        episodeMetaMap[number] = epJson.optDouble("number", 0.0) to title
                    }
                }
            }
        }

        val episodes = mutableListOf<SEpisode>()
        val seenNumbers = mutableSetOf<Float>()

        val providersToProcess = mutableListOf<String>()
        providersToProcess.add(primaryProvider)
        for (providerKey in providerOrder) {
            if (providerKey != primaryProvider && providerKey in availableProviders) {
                providersToProcess.add(providerKey)
            }
        }
        for (providerKey in availableProviders) {
            if (providerKey != primaryProvider && providerKey !in providersToProcess) {
                providersToProcess.add(providerKey)
            }
        }

        for (providerKey in providersToProcess) {
            if (providerKey != primaryProvider) {
                if (mergeAcrossProviders && episodes.isEmpty()) continue
                if (!mergeAcrossProviders && episodes.isNotEmpty()) break
            }

            crossProviderMap.entries
                .filter { it.value.containsKey(providerKey) }
                .sortedBy { it.key }
                .forEach { (number, providerEpMap) ->
                    if (seenNumbers.add(number)) {
                        val (rawNumber, title) = episodeMetaMap[number] ?: return@forEach
                        val fallbackProviders = providerEpMap.filterKeys { it != providerKey }
                        episodes.add(
                            buildMergedEpisode(
                                rawNumber, title, providerKey, preferredSubType,
                                providerEpMap[providerKey] ?: emptyMap(),
                                providerSubTypesMap[providerKey] ?: emptyList(),
                                showProvider,
                                fallbackProviders, providerSubTypesMap, anilistId,
                            ),
                        )
                    }
                }
        }

        val airingSchedule = if (anilistId != null && anilistId > 0) {
            val meta = getMeta(anilistId)
            if (meta?.airingSchedule != null) {
                meta.airingSchedule!!
            } else {
                AniLib.fetchAiringSchedule(client, anilistId).schedule.also { schedule ->
                    if (schedule.isNotEmpty()) {
                        getOrCreateMeta(anilistId).airingSchedule = schedule
                    }
                }
            }
        } else {
            emptyMap()
        }

        val episodeZero = episodes.filter { it.episode_number.toInt() == 0 }
        val episodeRest = episodes.filter { it.episode_number.toInt() != 0 }

        val result = if (preferences.episodeSortOrder == "ascending") {
            episodeZero + episodeRest
        } else {
            episodeZero + episodeRest.reversed()
        }
        for (ep in result) {
            airingSchedule[ep.episode_number]?.let { ep.date_upload = it }
        }

        if (anilistId != null && anilistId > 0) {
            try {
                val epTitles = AniLib.fetchEpisodeTitles(client, anilistId)
                if (epTitles.episodes.isNotEmpty()) {
                    for (ep in result) {
                        if (ep.name == "Episode ${ep.episode_number.toInt()}") {
                            epTitles.episodes[ep.episode_number.toInt()]?.title?.let { title ->
                                ep.name = "Episode ${ep.episode_number.toInt()}: $title"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logD { "episodeListParse: ani.zip title fetch failed: ${e.message}" }
            }
        }

        val fillerMode = preferences.fillerDisplayMode
        if (anilistId != null && anilistId > 0 && fillerMode != "show") {
            try {
                val fillerMap = AniLib.fetchFillerData(client, anilistId, preferences).episodes
                if (fillerMap.isNotEmpty()) {
                    val markMixed = preferences.fillerMarkMixed
                    for (ep in result) {
                        val epFillerType = fillerMap[ep.episode_number.toInt()] ?: continue
                        when (epFillerType) {
                            FillerType.FILLER -> {
                                if (fillerMode == "hide") {
                                    ep.name = "\u200B${ep.name}"
                                } else {
                                    ep.scanlator = buildString {
                                        append(ep.scanlator ?: "")
                                        if (ep.scanlator?.isNotEmpty() == true) append(" \u2022 ")
                                        append("[Filler]")
                                    }
                                }
                            }
                            FillerType.MIXED_MANGA -> {
                                if (markMixed && fillerMode != "hide") {
                                    ep.scanlator = buildString {
                                        append(ep.scanlator ?: "")
                                        if (ep.scanlator?.isNotEmpty() == true) append(" \u2022 ")
                                        append("[Mixed Canon]")
                                    }
                                }
                            }
                            FillerType.ANIME_CANON -> {
                                if (markMixed && fillerMode != "hide") {
                                    ep.scanlator = buildString {
                                        append(ep.scanlator ?: "")
                                        if (ep.scanlator?.isNotEmpty() == true) append(" \u2022 ")
                                        append("[Anime Canon]")
                                    }
                                }
                            }
                            FillerType.MANGA_CANON -> { /* no mark needed */ }
                        }
                    }
                }
            } catch (e: Exception) {
                logD { "episodeListParse: AniFiller fetch failed: ${e.message}" }
            }
        }

        logD { "episodeListParse: ${result.size} episodes, sort=${preferences.episodeSortOrder}, filler=$fillerMode" }
        return result
    }

    private fun buildMergedEpisode(
        number: Double,
        title: String,
        provider: String,
        preferredSubType: String,
        subTypeIds: Map<String, String>,
        allSubTypes: List<String>,
        showProvider: Boolean = true,
        fallbackProviders: Map<String, Map<String, String>> = emptyMap(),
        providerSubTypesMap: Map<String, List<String>> = emptyMap(),
        anilistId: Int? = null,
    ): SEpisode {
        val defaultSubType = subTypeIds.keys.firstOrNull { it == preferredSubType }
            ?: allSubTypes.firstOrNull { it in subTypeIds }
            ?: subTypeIds.keys.first()
        val episodeId = subTypeIds[defaultSubType] ?: ""

        val episodeIdObj = JSONObject().apply {
            put("episodeId", episodeId)
            put("provider", provider)
            put("defaultSubType", defaultSubType)
            put("subTypes", JSONObject(subTypeIds))
            if (fallbackProviders.isNotEmpty()) {
                val fallbackObj = JSONObject()
                for ((fbProvider, fbSubTypes) in fallbackProviders) {
                    fallbackObj.put(fbProvider, JSONObject(fbSubTypes))
                }
                put("fallbackProviders", fallbackObj)
            }
            val fbProviderSubTypes = JSONObject()
            for ((fbProvider, fbSubTypeList) in providerSubTypesMap) {
                if (fbProvider == provider || fbProvider !in fallbackProviders) continue
                val arr = JSONArray()
                fbSubTypeList.forEach { arr.put(it) }
                fbProviderSubTypes.put(fbProvider, arr)
            }
            if (fbProviderSubTypes.length() > 0) {
                put("fallbackProviderSubTypes", fbProviderSubTypes)
            }
            anilistId?.let { put("anilistId", it) }
        }

        val allAvailableSubTypes = mutableSetOf<String>()
        allAvailableSubTypes.addAll(subTypeIds.keys)
        for ((_, fbSubTypes) in fallbackProviders) {
            allAvailableSubTypes.addAll(fbSubTypes.keys)
        }

        val scanlatorLabel = allAvailableSubTypes
            .filter { it in SCANLATOR_SUB_TYPES }
            .sortedBy { SUB_TYPE_DISPLAY_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .joinToString(", ") { formatSubTypeLabel(it) }

        val providerLabel = buildString {
            append(providerDisplayName(provider))
            if (fallbackProviders.isNotEmpty()) {
                fallbackProviders.keys.forEach { fbKey ->
                    append(", ")
                    append(providerDisplayName(fbKey))
                }
            }
        }

        return SEpisode.create().apply {
            episode_number = number.toFloat()
            name = if (title.isNotEmpty()) "Episode ${number.toInt()}: $title" else "Episode ${number.toInt()}"
            setUrlWithoutDomain(episodeIdObj.toString())
            scanlator = buildString {
                if (showProvider) append("$providerLabel \u2022 ")
                append(scanlatorLabel)
            }
        }
    }

    // ============================ Video Links ============================

    private class AnimeMeta(
        val anilistId: Int,
        var malId: Int? = null,
        @Volatile var airingSchedule: Map<Float, Long>? = null,
    )

    private val animeMetaCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Int, AnimeMeta>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnimeMeta>?): Boolean = size > 16
        },
    )

    private fun getOrCreateMeta(anilistId: Int, malId: Int? = null): AnimeMeta = synchronized(animeMetaCache) {
        animeMetaCache.getOrPut(anilistId) { AnimeMeta(anilistId, malId) }
    }

    private fun getMeta(anilistId: Int): AnimeMeta? = synchronized(animeMetaCache) {
        animeMetaCache[anilistId]
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeData = JSONObject(episode.url)
        val query = buildPipeQuery(
            "episodeId" to episodeData.getString("episodeId"),
            "provider" to episodeData.getString("provider"),
            "category" to episodeData.getString("defaultSubType"),
            "_ep" to episode.url,
        )
        return buildPipeRequest("sources", "GET", query = query)
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeData = extractEpisodeDataFromPipeRequest(response.request.url.toString())
        val provider = episodeData?.optString("provider", "") ?: ""
        val subTypesObj = episodeData?.optJSONObject("subTypes")
        val defaultSubType = episodeData?.optString("defaultSubType", "sub") ?: "sub"
        val fallbackProvidersObj = episodeData?.optJSONObject("fallbackProviders")
        logD { "videoListParse: provider=$provider, defaultSubType=$defaultSubType, hasSubTypes=${subTypesObj != null}, hasFallbacks=${fallbackProvidersObj != null}" }

        val videos = mutableListOf<Video>()

        val primaryVideos = extractor.parseStreamsFromResponse(response, defaultSubType, provider)
        logD { "videoListParse: ${primaryVideos.size} primary streams (subType=$defaultSubType, provider=$provider)" }
        videos.addAll(primaryVideos)

        if (preferences.includeAllSubTypes && subTypesObj != null && subTypesObj.length() > 1) {
            val requests = mutableListOf<Pair<String, String>>()
            for (subTypeKey in subTypesObj.keys()) {
                if (subTypeKey == defaultSubType) continue
                val subEpId = subTypesObj.optString(subTypeKey, "")
                if (subEpId.isEmpty()) continue
                requests.add(subTypeKey to subEpId)
            }
            logD { "videoListParse: fetching ${requests.size} additional sub-types: ${requests.map { it.first }}" }

            videos.addAll(
                requests.parallelCatchingFlatMapBlocking { (subTypeKey, subEpId) ->
                    val query = buildPipeQuery(
                        "episodeId" to subEpId,
                        "provider" to provider,
                        "category" to subTypeKey,
                    )
                    client.newCall(buildPipeRequest("sources", "GET", query = query)).execute().use { resp ->
                        extractor.parseStreamsFromResponse(resp, subTypeKey, provider)
                    }
                },
            )
        }

        if (fallbackProvidersObj != null && fallbackProvidersObj.length() > 0) {
            val shouldFetchFallbacks = preferences.includeAllProviders || videos.isEmpty()
            if (shouldFetchFallbacks) {
                val reason = if (videos.isEmpty()) "primary returned no videos" else "include all providers is enabled"
                logD { "videoListParse: fetching ${fallbackProvidersObj.length()} fallback providers ($reason)" }
                val fallbackResults = fallbackProvidersObj.keys().asSequence().toList().parallelCatchingFlatMapBlocking { fbProvider ->
                    val fbSubTypes = fallbackProvidersObj.optJSONObject(fbProvider) ?: return@parallelCatchingFlatMapBlocking emptyList()
                    val fbRequests = mutableListOf<Pair<String, String>>()
                    for (subTypeKey in fbSubTypes.keys()) {
                        val fbEpId = fbSubTypes.optString(subTypeKey, "")
                        if (fbEpId.isEmpty()) continue
                        fbRequests.add(subTypeKey to fbEpId)
                    }
                    logD { "videoListParse: trying fallback provider '$fbProvider' with ${fbRequests.size} sub-types" }
                    fbRequests.flatMap { (subTypeKey, fbEpId) ->
                        val query = buildPipeQuery(
                            "episodeId" to fbEpId,
                            "provider" to fbProvider,
                            "category" to subTypeKey,
                        )
                        try {
                            client.newCall(buildPipeRequest("sources", "GET", query = query)).execute().use { resp ->
                                extractor.parseStreamsFromResponse(resp, subTypeKey, fbProvider)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "videoListParse: fallback provider '$fbProvider' failed: ${e.message}")
                            emptyList()
                        }
                    }
                }
                if (fallbackResults.isNotEmpty()) {
                    logD { "videoListParse: fallback providers returned ${fallbackResults.size} videos" }
                    videos.addAll(fallbackResults)
                } else {
                    Log.w(TAG, "videoListParse: all fallback providers returned no videos")
                }
            }
        }

        logD { "videoListParse: returning ${videos.size} total videos" }
        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.preferredQuality
        val subTypeLabel = formatSubTypeLabel(preferences.preferredSubType)
        val providerName = providerDisplayName(preferences.preferredProvider)
        val qualityInt = quality.toIntOrNull() ?: 0
        val streamTypePref = preferences.preferredStreamType
        val includeAllProviders = preferences.includeAllProviders

        var working: List<Video> = this

        if (!includeAllProviders) {
            val providerFiltered = working.filter { it.quality.contains(providerName) }
            if (providerFiltered.isNotEmpty()) {
                logD { "video.sort: provider filter: ${working.size} → ${providerFiltered.size} (preferred=$providerName)" }
                working = providerFiltered
            } else {
                logD { "video.sort: provider filter matched nothing (preferred=$providerName), keeping all ${working.size} videos" }
            }
        }

        val filtered: List<Video> = when (streamTypePref) {
            "hls" -> working.filter { it.quality.contains("HLS") }
            "embed" -> working.filter { it.quality.contains("EMBED") }
            else -> working
        }

        if (filtered.isEmpty()) {
            logD { "video.sort: streamType='$streamTypePref' filtered out all ${working.size} videos, returning unfiltered by stream type" }
            return working
        }

        val sorted = filtered.sortedWith(
            compareByDescending<Video> { it.quality.contains("HLS") }
                .thenByDescending { it.quality.contains(providerName) }
                .thenByDescending { it.quality.contains(subTypeLabel) }
                .thenByDescending {
                    val q = QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    when {
                        qualityInt == 0 -> q
                        q == qualityInt -> 100000
                        q > 0 -> q
                        it.quality.contains(quality) -> 99999
                        else -> 0
                    }
                },
        )
        logD { "video.sort: $size → ${working.size} after provider filter → ${filtered.size} after streamType='$streamTypePref' filter → ${sorted.size} sorted (quality=$quality, provider=$providerName, subType=$subTypeLabel, includeAll=$includeAllProviders)" }
        return sorted
    }

    // ============================== URL ==============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/watch/${anime.url}"

    fun close() {
        configFetchJob?.cancel()
        mirrorFetchJob?.cancel()
        scope.cancel()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val episodeData = try {
            JSONObject(episode.url)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse episode URL data: ${e.message}")
            return baseUrl
        }
        val anilistId = episodeData.optInt("anilistId", 0)
        return if (anilistId > 0) "$baseUrl/watch/$anilistId" else baseUrl
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = MiruroFilters.FILTER_LIST

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        logD { "setupPreferenceScreen: fetchState=$fetchState, cacheProviders=${configCache.values}" }
        getConfigSync()
        getMirrorsSync()

        val mirrorPref = screen.getListPreference(
            key = PREF_MIRROR_KEY,
            title = PREF_MIRROR_TITLE,
            entries = mirrorCache.entries,
            entryValues = mirrorCache.values,
            default = PREF_MIRROR_DEFAULT,
            summary = "%s",
            onChange = { pref, value ->
                logD { "Mirror changed: $baseUrl → $value, invalidating config cache" }
                baseUrl = value
                configCache = ProviderConfigCache(
                    displayNames = KNOWN_DISPLAY_NAMES,
                    entries = DEFAULT_PROVIDER_ENTRIES,
                    values = DEFAULT_PROVIDER_VALUES,
                    config = null,
                )
                fetchState = ConfigFetchState.NOT_FETCHED
                fetchAttempts = 0
                preferences.edit().remove(PREF_CACHED_CONFIG_KEY).apply()
                launchConfigFetch(forceRefresh = true)
                pref.summary = value
                true
            },
        )
        screen.addPreference(mirrorPref)

        launchMirrorFetch(onSuccess = {
            logD { "setupPreferenceScreen: async mirror fetch completed, updating mirror pref (${mirrorCache.entries.size} entries)" }
            mirrorPref.entries = mirrorCache.entries.toTypedArray()
            mirrorPref.entryValues = mirrorCache.values.toTypedArray()
            mirrorPref.summary = preferences.preferredMirror
        })

        val providerPref = screen.getListPreference(
            key = PREF_PROVIDER_KEY,
            title = PREF_PROVIDER_TITLE,
            entries = configCache.entries,
            entryValues = configCache.values,
            default = PREF_PROVIDER_DEFAULT,
            summary = providerDisplayName(preferences.preferredProvider),
            onChange = { pref, value ->
                pref.summary = providerDisplayName(value)
                true
            },
        )
        screen.addPreference(providerPref)

        launchConfigFetch(onSuccess = {
            logD { "setupPreferenceScreen: async fetch completed, updating provider pref (${configCache.entries.size} entries)" }
            providerPref.entries = configCache.entries.toTypedArray()
            providerPref.entryValues = configCache.values.toTypedArray()
            val currentProvider = preferences.preferredProvider
            providerPref.summary = providerDisplayName(currentProvider)
        })

        screen.addListPreference(
            key = PREF_SUB_TYPE_KEY,
            title = PREF_SUB_TYPE_TITLE,
            entries = PREF_SUB_TYPE_ENTRIES,
            entryValues = PREF_SUB_TYPE_VALUES,
            default = PREF_SUB_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_STREAM_TYPE_KEY,
            title = PREF_STREAM_TYPE_TITLE,
            entries = PREF_STREAM_TYPE_ENTRIES,
            entryValues = PREF_STREAM_TYPE_VALUES,
            default = PREF_STREAM_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_STYLE_KEY,
            title = PREF_TITLE_STYLE_TITLE,
            entries = PREF_TITLE_STYLE_ENTRIES,
            entryValues = PREF_TITLE_STYLE_VALUES,
            default = PREF_TITLE_STYLE_DEFAULT,
            summary = "%s",
        )

        screen.addSwitchPreference(
            key = PREF_INCLUDE_ALL_SUB_TYPES_KEY,
            title = PREF_INCLUDE_ALL_SUB_TYPES_TITLE,
            default = PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT,
            summary = "When disabled, only fetches streams for the preferred sub type.",
        )

        screen.addSwitchPreference(
            key = PREF_STRIP_HTML_KEY,
            title = PREF_STRIP_HTML_TITLE,
            default = PREF_STRIP_HTML_DEFAULT,
            summary = "Strips HTML tags from anime descriptions.",
        )

        screen.addSwitchPreference(
            key = PREF_MERGE_PROVIDERS_KEY,
            title = PREF_MERGE_PROVIDERS_TITLE,
            default = PREF_MERGE_PROVIDERS_DEFAULT,
            summary = "Adds episodes from other providers that are missing from the preferred provider.",
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_PROVIDER_IN_SCANLATOR_KEY,
            title = PREF_SHOW_PROVIDER_IN_SCANLATOR_TITLE,
            default = PREF_SHOW_PROVIDER_IN_SCANLATOR_DEFAULT,
            summary = "Shows the provider name in the episode scanlator field.",
        )

        screen.addSwitchPreference(
            key = PREF_INCLUDE_ALL_PROVIDERS_KEY,
            title = PREF_INCLUDE_ALL_PROVIDERS_TITLE,
            default = PREF_INCLUDE_ALL_PROVIDERS_DEFAULT,
            summary = "When enabled, includes video streams from all available providers. When disabled, only the preferred provider's streams are shown.",
        )

        screen.addListPreference(
            key = PREF_EPISODE_SORT_KEY,
            title = PREF_EPISODE_SORT_TITLE,
            entries = PREF_EPISODE_SORT_ENTRIES,
            entryValues = PREF_EPISODE_SORT_VALUES,
            default = PREF_EPISODE_SORT_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DESCRIPTION_TRUNCATE_KEY,
            title = PREF_DESCRIPTION_TRUNCATE_TITLE,
            entries = PREF_DESCRIPTION_TRUNCATE_ENTRIES,
            entryValues = PREF_DESCRIPTION_TRUNCATE_VALUES,
            default = PREF_DESCRIPTION_TRUNCATE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_FILLER_DISPLAY_KEY,
            title = PREF_FILLER_DISPLAY_TITLE,
            entries = PREF_FILLER_DISPLAY_ENTRIES,
            entryValues = PREF_FILLER_DISPLAY_VALUES,
            default = PREF_FILLER_DISPLAY_DEFAULT,
            summary = "%s",
        )

        screen.addSwitchPreference(
            key = PREF_FILLER_MARK_MIXED_KEY,
            title = PREF_FILLER_MARK_MIXED_TITLE,
            default = PREF_FILLER_MARK_MIXED_DEFAULT,
            summary = "When enabled, mixed-canon episodes are also tagged. Only applies when filler handling is set to 'Mark in scanlator'.",
        )
    }

    // ============================== Helpers ==============================

    private fun truncateDescription(description: String): String {
        val limit = preferences.descriptionTruncation.toIntOrNull() ?: 0
        if (limit <= 0 || description.length <= limit) return description
        val cutIndex = description.lastIndexOf(' ', limit)
        return if (cutIndex > limit * 2 / 3) {
            description.substring(0, cutIndex) + "\u2026"
        } else {
            description.substring(0, limit) + "\u2026"
        }
    }

    private fun formatSubTypeLabel(subType: String): String = when (subType) {
        "sub" -> "Sub"
        "dub" -> "Dub"
        "ssub" -> "Soft Sub"
        "h-sub" -> "Hard Sub"
        else -> subType.replaceFirstChar { it.uppercase() }
    }
    // ============================== Pipe API ===============================

    private fun extractAnilistIdFromPipeRequest(url: String): Int? {
        return try {
            val encoded = url.substringAfter("e=", "")
            if (encoded.isEmpty()) return null
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = JSONObject(String(decoded, Charsets.UTF_8))
            val query = payload.optJSONObject("query") ?: return null
            query.optInt("anilistId", -1).takeIf { it > 0 }
        } catch (e: Exception) {
            logD { "Failed to extract anilistId from pipe URL: ${e.message}" }
            null
        }
    }

    private fun extractEpisodeDataFromPipeRequest(url: String): JSONObject? {
        return try {
            val encoded = url.substringAfter("e=", "")
                .substringBefore("&")
            if (encoded.isEmpty()) return null
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = JSONObject(String(decoded, Charsets.UTF_8))
            val query = payload.optJSONObject("query") ?: return null
            val epDataStr = query.optString("_ep", "")
            if (epDataStr.isEmpty()) return null
            JSONObject(epDataStr)
        } catch (e: Exception) {
            logD { "Failed to extract episode data from pipe URL: ${e.message}" }
            null
        }
    }

    private fun buildPipeRequest(
        path: String,
        method: String = "GET",
        query: JSONObject = JSONObject(),
        body: JSONObject = JSONObject(),
    ): Request {
        logD { "buildPipeRequest: $method $path, queryKeys=${query.keys().asSequence().toList()}" }
        val payload = JSONObject().apply {
            put("path", path)
            put("method", method)
            put("query", query)
            put("body", if (body.length() == 0) JSONObject.NULL else body)
            put("version", "0.2.0")
            put("timestamp", System.currentTimeMillis())
        }

        val jsonBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(jsonBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        return GET(
            "$baseUrl/api/secure/pipe?e=$encoded",
            headers = Headers.headersOf(
                "Accept",
                "*/*",
                "Referer",
                "$baseUrl/",
            ),
        )
    }

    private fun buildPipeQuery(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((key, value) in pairs) {
            if (value == null) continue
            when (value) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is JSONArray -> put(key, value)
                is JSONObject -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun stripHtml(input: String): String = input
        .replace(BR_REGEX, "\n")
        .replace(CLOSE_P_REGEX, "\n")
        .replace(HTML_TAG_REGEX, "")
        .trim()

    private fun validateResponse(response: Response): Response {
        val code = response.code
        if (code == 444) {
            Log.w(TAG, "validateResponse: HTTP 444 — provider does not have this content")
            response.close()
            throw IOException("Provider does not have this content")
        }
        if (code >= 500) {
            Log.w(TAG, "validateResponse: HTTP $code — server error")
            response.close()
            throw IOException("Series not yet available (HTTP $code)")
        }
        return response
    }

    private fun extractCoverImage(coverImage: Any?): String = when (coverImage) {
        is JSONObject -> coverImage.optString("extraLarge", "")
            .ifEmpty { coverImage.optString("large", "") }
            .ifEmpty { coverImage.optString("medium", "") }
        is String -> coverImage
        else -> ""
    }

    private fun extractBannerImage(bannerImage: Any?): String = when (bannerImage) {
        is String -> bannerImage
        else -> ""
    }

    private fun extractMainStudio(studios: Any?): String {
        val edges = when (studios) {
            is JSONObject -> studios.optJSONArray("edges")
            is JSONArray -> studios
            else -> return ""
        } ?: return ""

        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optBoolean("isMain", false)) {
                return edge.optJSONObject("node")?.optString("name", "") ?: ""
            }
        }

        return edges.optJSONObject(0)?.optJSONObject("node")?.optString("name", "") ?: ""
    }

    private fun parseAnimeListResponse(
        response: Response,
        fallbackKeys: List<String> = emptyList(),
    ): AnimesPage {
        val json = response.use { extractor.decryptResponse(it) }
        logD { "parseAnimeListResponse: json length=${json.length}, fallbackKeys=$fallbackKeys" }

        var hasNextPage = false
        val mediaArray = try {
            JSONArray(json)
        } catch (_: Exception) {
            val jsonObj = JSONObject(json)
            val pageInfo = jsonObj.optJSONObject("pageInfo")
            hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false
            jsonObj.optJSONArray("media")
                ?: fallbackKeys.firstNotNullOfOrNull { jsonObj.optJSONArray(it) }
                ?: return AnimesPage(emptyList(), false)
        }

        val animeList = (0 until mediaArray.length()).map { i ->
            parseAnimeFromMediaObj(mediaArray.getJSONObject(i))
        }

        if (!hasNextPage && animeList.size >= 20) {
            hasNextPage = true
        }

        return AnimesPage(animeList, hasNextPage)
    }

    private fun resolveTitle(titleObj: JSONObject, style: String): String {
        val fallbackChain = when (style) {
            "romaji" -> listOf("romaji", "userPreferred", "english", "native")
            "english" -> listOf("english", "romaji", "userPreferred", "native")
            "native" -> listOf("native", "userPreferred", "romaji", "english")
            else -> listOf("userPreferred", "romaji", "english", "native")
        }
        return fallbackChain.firstNotNullOfOrNull { key ->
            titleObj.optString(key, "")
                .takeIf { it.isNotBlank() && it != "null" }
        } ?: ""
    }

    private fun parseAnimeFromMediaObj(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val titleStyle = preferences.preferredTitleStyle
        val title = resolveTitle(titleObj, titleStyle)

        val id = media.optInt("id", 0).toString()
        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))

        return SAnime.create().apply {
            this.title = title.ifBlank { "Unknown" }
            thumbnail_url = thumbnail.ifEmpty { bannerImage }
            setUrlWithoutDomain(id)
        }
    }
}
