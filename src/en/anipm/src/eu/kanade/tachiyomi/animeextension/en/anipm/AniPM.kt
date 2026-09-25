package eu.kanade.tachiyomi.animeextension.en.anipm

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.nanohttpd.protocols.http.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AniPM :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniPM"
    override val lang = "en"
    override val supportsLatest = true

    // Status: https://anipm.tv/
    override val baseUrl = "https://ani.pm"
    private val apiUrl get() = "$baseUrl/api"

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, PREF_AUDIO_EXCLUDE_DEFAULT)
            ?: PREF_AUDIO_EXCLUDE_DEFAULT

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =============================== Browse ===============================
    private fun apiHeaders(referer: String = "$baseUrl/anime") = headers.newBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", referer)
        .build()

    private fun catalogUrl(page: Int) = "$apiUrl/anime/catalog".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .apply { if (hideAdult) addQueryParameter("sfw", "1") }

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page).addQueryParameter("sort", "popular").build(), apiHeaders())

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page).addQueryParameter("sort", "newest").build(), apiHeaders())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = catalogUrl(page).addQueryParameter("q", query.trim())

        filters.forEach { f ->
            when (f) {
                is Filters.SortFilter -> f.getValue().let { url.addQueryParameter("sort", it) }
                is Filters.FormatFilter -> f.getValue()?.let { url.addQueryParameter("format", it) }
                is Filters.StatusFilter -> f.getValue()?.let { url.addQueryParameter("status", it) }
                is Filters.SeasonFilter -> f.getValue()?.let { url.addQueryParameter("season", it) }
                is Filters.YearFilter -> f.getValue()?.let { url.addQueryParameter("year", it) }

                is Filters.GenreFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("genre", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeGenre", it) }
                }
                is Filters.TagFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("tag", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeTag", it) }
                }
                is Filters.StudioFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("studio", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeStudio", it) }
                }
                else -> {}
            }
        }

        return GET(url.build(), apiHeaders())
    }

    override fun popularAnimeParse(response: Response) = parseCatalog(response)
    override fun latestUpdatesParse(response: Response) = parseCatalog(response)
    override fun searchAnimeParse(response: Response) = parseCatalog(response)

    private fun parseCatalog(response: Response): AnimesPage {
        val dto = response.parseAs<CatalogResponseDto>()

        val animes = dto.items.mapNotNull { it.toSAnime(baseUrl) }
        return AnimesPage(animes, dto.hasNextPage)
    }

    // =============================== Filters ==============================
    private val filterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var facetsMemo: FacetsDto? = null

    private fun loadCachedFacets(): FacetsDto? {
        facetsMemo?.let { return it }
        val names = { key: String ->
            preferences.getString(key, null)?.split('\n').orEmpty().filter(String::isNotBlank)
        }
        val genres = names(PREF_FACETS_GENRES_KEY)
        val tags = names(PREF_FACETS_TAGS_KEY)
        val studios = names(PREF_FACETS_STUDIOS_KEY)
        if (genres.isEmpty() && tags.isEmpty() && studios.isEmpty()) return null
        return FacetsDto(
            genres = genres.map { FacetDto(it) },
            tags = tags.map { FacetDto(it) },
            studios = studios.map { FacetDto(it) },
            updatedAt = preferences.getLong(PREF_FACETS_UPDATED_AT, 0L),
        ).also { facetsMemo = it }
    }

    private fun refreshFacetsIfStale() {
        val cached = loadCachedFacets()
        val now = System.currentTimeMillis()
        if (cached != null &&
            now - preferences.getLong(PREF_FACETS_FETCHED_AT, 0L) < FACETS_TTL_MS
        ) {
            return
        }

        filterScope.launch {
            try {
                val res = client.get("$apiUrl/anime/facets", apiHeaders())
                val fresh = res.use { it.parseAs<FacetsDto>() }

                // Server revision gate: rewrite prefs only when facets changed
                if (fresh.updatedAt != cached?.updatedAt) {
                    preferences.edit()
                        .putString(PREF_FACETS_GENRES_KEY, fresh.genres.joinToString("\n") { it.name })
                        .putString(PREF_FACETS_TAGS_KEY, fresh.tags.joinToString("\n") { it.name })
                        .putString(
                            PREF_FACETS_STUDIOS_KEY,
                            fresh.studios.filter { it.count >= STUDIO_MIN_COUNT }
                                .joinToString("\n") { it.name },
                        )
                        .putLong(PREF_FACETS_UPDATED_AT, fresh.updatedAt)
                        .putLong(PREF_FACETS_FETCHED_AT, now)
                        .apply()
                    facetsMemo = fresh
                } else {
                    preferences.edit().putLong(PREF_FACETS_FETCHED_AT, now).apply()
                }
            } catch (_: Exception) {
                // Fetch failed — stale cache keeps serving; retried after next TTL window
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        refreshFacetsIfStale()
        return Filters.build(loadCachedFacets())
    }

    // ============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(seriesUrl(anime.url), apiHeaders("$baseUrl/anime"))

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<SeriesResponseDto>().toSAnime(baseUrl)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    // ============================ Related ============================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val series = fetchSeries(anime.url)

        val related = series.relations.mapNotNull { rel ->
            val handle = rel.toHandle() ?: return@mapNotNull null
            SAnime.create().apply {
                url = handle
                title = rel.title ?: return@mapNotNull null
                thumbnail_url = absoluteCover(baseUrl, rel.poster)
            }
        }

        val recommended = fetchRecommendations(series.id)

        return buildList {
            val seen = HashSet<String>()
            related.forEach { if (seen.add(it.url)) add(it) }
            recommended.forEach { if (seen.add(it.url)) add(it) }
        }
    }

    private suspend fun fetchRecommendations(seriesId: Long): List<SAnime> = try {
        val context = "anime:$seriesId"
        val res = client.get(
            "$apiUrl/recommend".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "25")
                .addQueryParameter("context", context)
                .build(),
            apiHeaders(),
        )
        val dto = res.use { it.parseAs<RecommendResponseDto>() }

        dto.items.mapNotNull { it.toSAnime(baseUrl) }
    } catch (_: Exception) {
        emptyList()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val dto = fetchSeries(anime.url)

        val fillerSet = fetchFillerSet(dto.anilistId?.toLongOrNull(), dto.title)

        return dto.episodes.map { ep ->
            SEpisode.create().apply {
                val numStr = fmtNum(ep.number)
                episode_number = ep.number.toFloat()
                url = "${anime.url}/$numStr"
                val realTitle = ep.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode ", true) }
                name = buildString {
                    append("Episode $numStr")
                    realTitle?.let { append(" - $it") }
                }
                summary = ep.description
                preview_url = absoluteCover(baseUrl, ep.thumbnail)
                fillermark = ep.number in fillerSet
                scanlator = when {
                    ep.sub && ep.dub -> "Sub & Dub"
                    ep.sub -> "Sub"
                    ep.dub -> "Dub"
                    else -> null
                }
                date_upload = dateFormat.tryParse(ep.aired)
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (handle, num) = episode.url.split("/").let { it[0] to it.getOrElse(1) { "" } }
        return "$baseUrl/anime/$handle?ep=$num"
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val handle = episode.url.substringBefore('/')
        val epNum = episode.url.substringAfterLast('/')
        val dto = fetchSeries(handle)
        val ep = dto.episodes.firstOrNull { fmtNum(it.number) == epNum }
            ?: return emptyList()

        val epParam = ep.routeId ?: epNum
        val bootPackage = try {
            val bootRes = client.get(
                "$apiUrl/anime/playback-bootstrap/settlar/${dto.id}?ep=$epParam&lang=sub",
                apiHeaders(),
            )
            val boot = bootRes.use { it.parseAs<BootstrapDto>() }
            val epKey = fmtNum(ep.number)
            boot.anipmPackages?.episodes?.get(epKey)
        } catch (_: Exception) {
            null
        }

        val hasSub = bootPackage?.sub ?: ep.sub
        val hasSubhard = bootPackage?.subhard ?: ep.subhard
        val hasDub = bootPackage?.dub ?: ep.dub
        val hasDubhard = bootPackage?.dubhard ?: ep.dubhard

        return buildList {
            if (hasSub && "[Sub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Sub]", internalData = "anipm::${dto.id}/$epNum/sub"))
            }
            if (hasSubhard && "[Hard Sub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Hard Sub]", internalData = "anipm::${dto.id}/$epNum/subhard"))
            }
            if (hasDub && "[Dub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Dub]", internalData = "anipm::${dto.id}/$epNum/dub"))
            }
            if (hasDubhard && "[Hard Dub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Hard Dub]", internalData = "anipm::${dto.id}/$epNum/dubhard"))
            }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ========================== Hoster Sorting ==========================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val (primary, secondary) = when (preferredAudio) {
            "subhard" -> "[Hard Sub]" to "[Sub]"
            "dub" -> "[Dub]" to "[Hard Dub]"
            "dubhard" -> "[Hard Dub]" to "[Dub]"
            else -> "[Sub]" to "[Hard Sub]"
        }
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName == primary }
                .thenByDescending { it.hosterName == secondary },
        )
    }

    // =============================== Videos ===============================
    private val embedApi = "https://embed.settlar.io"

    private val embedHeaders = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val (handle, epNum, lang) = hoster.internalData.removePrefix("anipm::").split("/")
        return try {
            // 1) Bootstrap — numeric settlar ID (verified: /playback-bootstrap/settlar/8922)
            val settlarId = handle
            val bootLang = if (lang.startsWith("dub")) "dub" else "sub"
            val boot = client.get(
                "$apiUrl/anime/playback-bootstrap/settlar/$settlarId?ep=$epNum&lang=$bootLang",
                apiHeaders(),
            ).use { it.parseAs<BootstrapDto>() }
            val selection = boot.settlarSelection?.takeIf(String::isNotBlank)
                ?: return emptyList()

            // 2) Exchange selection for a settlar embed token (watch-page endpoint)
            val settlar = client.get(
                "$apiUrl/anime/settlar/session".toHttpUrl().newBuilder()
                    .addQueryParameter("selection", selection)
                    .addQueryParameter("provider", "anipm")
                    .addQueryParameter("ep", epNum)
                    .addQueryParameter("channel", lang)
                    .addQueryParameter("telemetry", "0")
                    .build(),
                apiHeaders(),
            ).use { it.parseAs<SettlarSessionDto>() }
            val token = settlar.embedUrl?.toHttpUrl()?.queryParameter("t")
                ?: return emptyList()

            // 3) Embed session → signed HLS manifest URL
            val embed = client.get("$embedApi/api/embed/session?t=$token", embedHeaders)
                .use { it.parseAs<EmbedSessionDto>() }
            val manifest = embed.source?.takeIf(String::isNotBlank)
                ?: return emptyList()

            val stamps = buildList {
                boot.skip?.op?.takeIf { it.end > it.start }?.let {
                    add(TimeStamp(it.start, it.end, name = "Intro", type = ChapterType.Opening))
                }
                boot.skip?.ed?.takeIf { it.end > it.start }?.let {
                    add(TimeStamp(it.start, it.end, name = "Outro", type = ChapterType.Ending))
                }
            }

            /*** We route through [SettlarProxy]. */
            val proxy = getProxy()
            val vids = playlistUtils.extractFromHls(
                playlistUrl = proxy.proxyUrl(manifest),
                referer = embedApi,
                masterHeaders = headers,
                videoHeaders = headers,
            )

            val qualityRank = PREF_QUALITY_VALUES.reversed()
            val sorted = vids.sortedWith(
                compareByDescending<Video> { it.videoTitle.contains(preferredQuality) }
                    .thenByDescending { video ->
                        qualityRank.indexOfFirst { video.videoTitle.contains(it) }
                    },
            )

            val isPreferredAudio = lang == preferredAudio
            val marked = sorted.mapIndexed { index, video ->
                when {
                    index == 0 && isPreferredAudio -> video.copy(preferred = true)
                    else -> video.copy(preferred = false)
                }
            }

            return if (stamps.isEmpty()) marked else marked.map { it.copy(timestamps = stamps) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Series fetch ============================
    private fun seriesUrl(handle: String): String = "$apiUrl/anime/series/${handle.removePrefix("set-")}?routes=e3"

    private suspend fun fetchSeries(handle: String): SeriesResponseDto {
        val res = client.get(seriesUrl(handle), apiHeaders())
        return res.use { it.parseAs<SeriesResponseDto>() }
    }

    private suspend fun fetchFillerSet(anilistId: Long?, title: String?): Set<Double> = try {
        val aid = anilistId?.takeIf { it in 1..<syntheticAniListBase } ?: return emptySet()
        val url = "$apiUrl/anime/filler".toHttpUrl().newBuilder()
            .addQueryParameter("anilistId", aid.toString())
            .addQueryParameter("title", title ?: "")
            .build()
        val res = client.get(url, apiHeaders())
        val ranges = res.use { it.parseAs<FillerResponseDto>() }.ranges
        buildSet {
            ranges?.filler.orEmpty().forEach { addRange(it) }
        }
    } catch (_: Exception) {
        emptySet()
    }

    private fun MutableSet<Double>.addRange(range: List<Int>) {
        val start = range.getOrNull(0) ?: return
        val end = range.getOrNull(1) ?: start
        if (end >= start) (start..end).forEach { add(it.toDouble()) }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun fmtNum(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen): Unit = with(screen) {
        addPreference(
            SwitchPreferenceCompat(context).apply {
                key = PREF_HIDE_ADULT_KEY
                title = "Hide 18+ Titles"
                summary = "Filters adult titles out of browse/search results."
                setDefaultValue(PREF_HIDE_ADULT_DEFAULT)
            },
        )
        addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
        )
        addPreference(
            MultiSelectListPreference(context).apply {
                key = PREF_AUDIO_EXCLUDE_KEY
                title = "Exclude Audio Types"
                entries = arrayOf("Sub", "Hard Sub", "Dub", "Hard Dub")
                entryValues = arrayOf("[Sub]", "[Hard Sub]", "[Dub]", "[Hard Dub]")
                setDefaultValue(PREF_AUDIO_EXCLUDE_DEFAULT)
                summary = "Hide videos of the selected audio types."
            },
        )
    }

    // ============================== Proxy =================================
    @Volatile
    private var proxy: SettlarProxy? = null

    @Synchronized
    private fun getProxy(): SettlarProxy {
        proxy?.takeIf { it.isAlive }?.let { return it }
        proxy?.stop()
        return SettlarProxy(headers).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            proxy = it
        }
    }

    companion object {
        private const val PAGE_SIZE = 30

        private const val PREF_HIDE_ADULT_KEY = "hide_adult"
        private const val PREF_HIDE_ADULT_DEFAULT = true

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Hard Sub", "Dub", "Hard Dub")
        private val PREF_AUDIO_VALUES = listOf("sub", "subhard", "dub", "dubhard")
        private const val PREF_AUDIO_DEFAULT = "sub"

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"
        private val PREF_AUDIO_EXCLUDE_DEFAULT = emptySet<String>()

        private const val PREF_FACETS_GENRES_KEY = "facets_genres"
        private const val PREF_FACETS_TAGS_KEY = "facets_tags"
        private const val PREF_FACETS_STUDIOS_KEY = "facets_studios"
        private const val PREF_FACETS_UPDATED_AT = "facets_updated_at"
        private const val PREF_FACETS_FETCHED_AT = "facets_fetched_at"
        private val FACETS_TTL_MS = 24 * 60 * 60 * 1000L

        private const val STUDIO_MIN_COUNT = 3

        fun parseStatus(status: String?): Int {
            val s = status?.lowercase() ?: return SAnime.UNKNOWN
            return when {
                "not" in s || "upcoming" in s -> SAnime.UNKNOWN
                "releasing" in s || "currently" in s -> SAnime.ONGOING
                "finished" in s -> SAnime.COMPLETED
                "cancel" in s -> SAnime.CANCELLED
                "hiatus" in s -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
        }
    }
}
