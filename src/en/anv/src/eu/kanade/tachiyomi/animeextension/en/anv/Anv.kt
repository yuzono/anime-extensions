package eu.kanade.tachiyomi.animeextension.en.anv

import android.webkit.CookieManager
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import keiyoushi.utils.Source as BaseSource

/**
 * English anime source implementation for ANV (`https://anv.to`).
 */
@Source
abstract class Anv : BaseSource() {

    override val supportsLatest = true

    override val supportsRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    private class WatchCookieTag(val cookiePair: String)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val explicitWatch = extractWatchCookiePair(req.header("Cookie"))
                if (explicitWatch == null) {
                    chain.proceed(req)
                } else {
                    chain.proceed(
                        req.newBuilder()
                            .tag(WatchCookieTag::class.java, WatchCookieTag(explicitWatch))
                            .build(),
                    )
                }
            }
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val taggedWatch = req.tag(WatchCookieTag::class.java)?.cookiePair
                if (taggedWatch == null) {
                    chain.proceed(req)
                } else {
                    val merged = mergeCookieHeaderWithWatch(req.header("Cookie"), taggedWatch)
                    chain.proceed(req.newBuilder().header("Cookie", merged).build())
                }
            }
            .rateLimit(6)
            .build()
    }

    private val jarMintedCookie = AtomicReference<String?>(null)

    private val cookieMintClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .cache(null)
            .cookieJar(
                object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = runCatching {
                        network.client.cookieJar.loadForRequest(url)
                            .filterNot { it.name.equals(WATCH_COOKIE_NAME, ignoreCase = true) }
                    }.getOrDefault(emptyList())

                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        cookies.lastOrNull {
                            it.name.equals(WATCH_COOKIE_NAME, ignoreCase = true) && it.value.isNotBlank()
                        }?.let { minted ->
                            jarMintedCookie.set("$WATCH_COOKIE_NAME=${minted.value}")
                        }
                        runCatching { network.client.cookieJar.saveFromResponse(url, cookies) }
                    }
                },
            )
            .rateLimit(6)
            .build()
    }

    private val prefTitleLang: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val prefPopularMode: String
        get() = preferences.getString(PREF_POPULAR_MODE_KEY, PREF_POPULAR_MODE_DEFAULT) ?: PREF_POPULAR_MODE_DEFAULT

    private val prefAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val prefServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val prefEnabledTypes: Set<String>
        get() = preferences.getStringSet(PREF_ENABLED_TYPES_KEY, DEFAULT_ENABLED_TYPES) ?: DEFAULT_ENABLED_TYPES

    private val prefEnabledServers: Set<String>
        get() = preferences.getStringSet(PREF_ENABLED_SERVERS_KEY, DEFAULT_ENABLED_SERVERS) ?: DEFAULT_ENABLED_SERVERS

    private val prefMarkFiller: Boolean
        get() = preferences.getBoolean(PREF_MARK_FILLER_KEY, true)

    private val prefHideFiller: Boolean
        get() = preferences.getBoolean(PREF_HIDE_FILLER_KEY, false)

    private val prefSkipTimes: Boolean
        get() = preferences.getBoolean(PREF_SKIP_TIMES_KEY, true)

    private val prefLocalProxy: Boolean
        get() = preferences.getBoolean(PREF_PROXY_KEY, true)

    private val streamProxy by lazy { AnvStreamProxy(network.client) }

    private class CachedMedia(
        val media: MediaDto,
        val expiresAtMs: Long,
    )

    private val detailCache: MutableMap<String, CachedMedia> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedMedia>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedMedia>?,
            ): Boolean = size > MAX_DETAIL_CACHE
        },
    )

    private val cookieLock = Any()
    private val cookieMintMutex = Mutex()
    private val homePagingLock = Any()

    @Volatile
    private var cachedWatchCookie: String? = null

    @Volatile
    private var cachedCookieOrigin: String? = null

    @Volatile
    private var cookieExpiresAtMs: Long = 0L

    private var homePageUsed: Boolean = false
    private var homeNextBrowsePage: Int = 1
    private val homeSeenIds: MutableSet<Long> = HashSet()

    /**
     * Adds the default `Referer` header pointing to the configured ANV base URL.
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    /**
     * Builds JSON API headers including `Origin` and `Referer`.
     */
    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Origin", baseUrl)
        .set("Referer", referer)
        .build()

    // ============================== Popular ===============================

    /**
     * Fetches popular, trending, top-rated, or home-featured anime pages.
     */
    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (prefPopularMode == "HOME") {
            return@withContext fetchHomePopularPage(page)
        }

        val sortOrder = when (prefPopularMode) {
            "TRENDING_DESC" -> "TRENDING_DESC"
            "SCORE_DESC" -> "SCORE_DESC"
            else -> "POPULARITY_DESC"
        }
        val variables = BrowseVariables(
            page = page.coerceAtLeast(1),
            perPage = PAGE_SIZE,
            sort = listOf(sortOrder),
            isAdult = false,
        )
        val pageData = executeCatalog<BrowseVariables, CatalogPageData>("BrowseAnime", variables)
        val mediaPage = pageData?.page
        val items = mediaPage?.media.orEmpty()
            .filter { !it.isAdult }
            .map { it.toSAnime(prefTitleLang) }
        AnimesPage(items, mediaPage?.pageInfo?.hasNextPage == true)
    }

    /**
     * Serves `HomeAnime` on page 1 and deduplicated `BrowseAnime` batches on subsequent or fallback pages.
     */
    private suspend fun fetchHomePopularPage(page: Int): AnimesPage {
        if (page <= 1) {
            synchronized(homePagingLock) {
                homePageUsed = false
                homeNextBrowsePage = 1
                homeSeenIds.clear()
            }
            val homeData = suspendRunCatching {
                executeCatalog<EmptyVariables, CatalogHomeData>("HomeAnime", EmptyVariables())
            }.getOrNull()
            val combined = buildList {
                homeData?.hero?.media?.let(::addAll)
                homeData?.trending?.media?.let(::addAll)
            }.distinctBy { it.id }.filter { !it.isAdult }

            if (combined.isNotEmpty()) {
                synchronized(homePagingLock) {
                    homePageUsed = true
                    homeNextBrowsePage = 1
                    combined.forEach { media ->
                        if (media.id > 0L) homeSeenIds.add(media.id)
                    }
                }
                return AnimesPage(
                    combined.map { it.toSAnime(prefTitleLang) },
                    true,
                )
            }
        }

        val (startCatalogPage, filterSeen) = synchronized(homePagingLock) {
            if (homePageUsed) {
                homeNextBrowsePage to HashSet(homeSeenIds)
            } else {
                page.coerceAtLeast(1) to emptySet<Long>()
            }
        }

        var currentCatalogPage = startCatalogPage
        repeat(3) {
            val variables = BrowseVariables(
                page = currentCatalogPage,
                perPage = PAGE_SIZE,
                sort = listOf("POPULARITY_DESC"),
                isAdult = false,
            )
            val pageData = executeCatalog<BrowseVariables, CatalogPageData>("BrowseAnime", variables)
            val mediaPage = pageData?.page
            val hasNext = mediaPage?.pageInfo?.hasNextPage == true
            val unseen = mediaPage?.media.orEmpty()
                .filter { !it.isAdult && (it.id <= 0L || it.id !in filterSeen) }

            currentCatalogPage++
            if (unseen.isNotEmpty() || !hasNext) {
                synchronized(homePagingLock) {
                    if (homePageUsed) {
                        homeNextBrowsePage = currentCatalogPage
                        unseen.forEach { media ->
                            if (media.id > 0L) homeSeenIds.add(media.id)
                        }
                    }
                }
                return AnimesPage(
                    unseen.map { it.toSAnime(prefTitleLang) },
                    hasNext,
                )
            }
        }

        synchronized(homePagingLock) {
            if (homePageUsed) {
                homeNextBrowsePage = currentCatalogPage
            }
        }
        return AnimesPage(emptyList(), false)
    }

    // =============================== Latest ===============================

    /**
     * Fetches recently aired episodes from `/api/recent-episodes` with an RSS fallback.
     */
    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext AnimesPage(emptyList(), false)

        val recent = suspendRunCatching {
            val request = GET("$baseUrl/api/recent-episodes", apiHeaders(), CacheControl.FORCE_NETWORK)
            executeWithRetry(request).use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.parseAs<RecentEpisodesResponse>(json)
            }
        }.getOrNull()

        if (recent != null && recent.schedules.isNotEmpty()) {
            val seenIds = HashSet<Long>()
            val list = mutableListOf<SAnime>()
            for (item in recent.schedules) {
                val media = item.media ?: continue
                if (media.isAdult) continue
                if (media.id > 0L && !seenIds.add(media.id)) continue
                list.add(media.toSAnime(prefTitleLang))
            }
            if (list.isNotEmpty()) {
                return@withContext AnimesPage(list, false)
            }
        }

        val rssItems = fetchRecentsRssFallback()
        AnimesPage(rssItems, false)
    }

    /**
     * Parses `/recents.xml` when the JSON recent-episodes endpoint is unavailable.
     */
    private suspend fun fetchRecentsRssFallback(): List<SAnime> = suspendRunCatching {
        val docHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val request = GET("$baseUrl/recents.xml", docHeaders, CacheControl.FORCE_NETWORK)
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return emptyList()
            val doc = Jsoup.parse(response.body.string(), baseUrl, Parser.xmlParser())
            val seenSlugs = HashSet<String>()
            doc.select("item").mapNotNull { item ->
                val link = item.selectFirst("link")?.text().orEmpty()
                val slug = extractAnimeSlugFromAnyUrl(link) ?: return@mapNotNull null
                if (!seenSlugs.add(slug)) return@mapNotNull null
                val rawTitle = item.selectFirst("title")?.text().orEmpty()
                val cleanTitle = rawTitle.replace(RSS_EPISODE_SUFFIX_REGEX, "").trim().ifEmpty { slug }
                SAnime.create().apply {
                    url = "/anime/$slug"
                    title = cleanTitle
                }
            }
        }
    }.getOrDefault(emptyList())

    // =============================== Search ===============================

    /**
     * Returns the search filter list synchronously without blocking the UI thread.
     */
    override fun getFilterList(): AnimeFilterList = Filters.buildFilterList()

    /**
     * Searches ANV by query, filters, `slug:` prefix, or direct `/anime/...` URL.
     */
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val trimmed = query.trim()

        val directSlug = when {
            trimmed.startsWith(PREFIX_SEARCH, ignoreCase = true) ->
                extractAnimeSlugFromAnyUrl(trimmed.substringAfter(':').trim())
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("/anime/", ignoreCase = true) ->
                extractAnimeSlugFromAnyUrl(trimmed)
            else -> null
        }

        if (directSlug != null) {
            val media = fetchMediaBySlug(directSlug)
            if (media.isAdult) {
                return@withContext AnimesPage(emptyList(), false)
            }
            return@withContext AnimesPage(listOf(media.toSAnimeDetails(prefTitleLang)), false)
        }

        val params = Filters.parseFilters(filters)
        val useSearchOp = trimmed.isNotEmpty() && !params.hasActiveFilters

        val pageData = if (useSearchOp) {
            executeCatalog<SearchVariables, CatalogPageData>(
                operation = "SearchAnime",
                variables = SearchVariables(
                    search = trimmed,
                    page = page,
                    perPage = PAGE_SIZE,
                ),
            )
        } else {
            executeCatalog<BrowseVariables, CatalogPageData>(
                operation = "BrowseAnime",
                variables = BrowseVariables(
                    page = page,
                    perPage = PAGE_SIZE,
                    sort = listOf(params.sort),
                    search = trimmed.takeIf { it.isNotEmpty() },
                    format = params.format.takeIf { it.isNotEmpty() },
                    status = params.status.takeIf { it.isNotEmpty() },
                    season = params.season.takeIf { it.isNotEmpty() },
                    seasonYear = params.seasonYear,
                    genres = params.genres.takeIf { it.isNotEmpty() },
                    isAdult = false,
                ),
            )
        }

        val mediaPage = pageData?.page
        val results = mediaPage?.media.orEmpty()
            .filter { !it.isAdult }
            .map { it.toSAnime(prefTitleLang) }
        AnimesPage(results, mediaPage?.pageInfo?.hasNextPage == true)
    }

    // =========================== Anime Details ============================

    /**
     * Resolves the canonical public web URL for [anime].
     */
    override fun getAnimeUrl(anime: SAnime): String {
        val cleanPath = anime.url.substringBefore('#').trim()
        return absolutizeUrl(cleanPath) ?: "$baseUrl/browse"
    }

    /**
     * Fetches full metadata for [anime] while enforcing SAFE content filtering.
     */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val slug = extractAnimeSlugFromAnyUrl(anime.url)
            ?: throw Exception("Invalid anime URL: ${anime.url}")
        val media = fetchMediaBySlug(slug)
        if (media.isAdult) {
            throw Exception("Adult content is disabled")
        }
        media.toSAnimeDetails(prefTitleLang)
    }

    /**
     * Pushes recommended non-adult anime from the detail payload and marks completion.
     */
    override suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
        try {
            val slug = extractAnimeSlugFromAnyUrl(anime.url)
            if (slug == null) {
                pushResults("Recommended" to emptyList(), true)
                return
            }
            val media = withContext(Dispatchers.IO) { fetchMediaBySlug(slug) }
            val related = if (media.isAdult) {
                emptyList()
            } else {
                media.recommendations?.nodes.orEmpty()
                    .mapNotNull { it.mediaRecommendation }
                    .filter { !it.isAdult && (it.id > 0L || !it.slug.isNullOrBlank()) }
                    .distinctBy { it.id.takeIf { id -> id > 0L } ?: it.slug }
                    .map { it.toSAnime(prefTitleLang) }
            }
            pushResults("Recommended" to related, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exceptionHandler(e)
        }
    }

    // ============================== Episodes ==============================

    /**
     * Resolves the canonical public watch URL for [episode].
     */
    override fun getEpisodeUrl(episode: SEpisode): String {
        val cleanPath = episode.url.substringBefore('#').trim()
        return absolutizeUrl(cleanPath) ?: baseUrl
    }

    /**
     * Builds the descending episode list with titles, thumbnails, and filler metadata.
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val slug = extractAnimeSlugFromAnyUrl(anime.url)
            ?: throw Exception("Invalid anime URL: ${anime.url}")
        val media = fetchMediaBySlug(slug)
        if (media.isAdult) {
            throw Exception("Adult content is disabled")
        }
        val canonicalSlug = media.resolveSlug()

        val streamingMap = LinkedHashMap<Int, StreamingEpisodeDto>()
        media.streamingEpisodes.orEmpty().forEachIndexed { idx, epDto ->
            val parsedNum = epDto.title
                ?.let { EPISODE_TITLE_NUM_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: (idx + 1)
            streamingMap.putIfAbsent(parsedNum, epDto)
        }

        val streamingMax = streamingMap.keys.maxOrNull() ?: 0
        val airingMax = ((media.nextAiringEpisode?.episode ?: 0) - 1).coerceAtLeast(0)
        val finishedMax = if (media.status.equals("FINISHED", ignoreCase = true)) {
            media.episodes ?: 0
        } else {
            0
        }

        var totalEpisodes = maxOf(streamingMax, airingMax, finishedMax)

        if (totalEpisodes <= 0) {
            totalEpisodes = when {
                media.format.equals("MOVIE", ignoreCase = true) &&
                    !media.status.equals("NOT_YET_RELEASED", ignoreCase = true) -> 1
                (media.episodes ?: 0) > 0 &&
                    !media.status.equals("NOT_YET_RELEASED", ignoreCase = true) -> media.episodes ?: 0
                else -> 0
            }
        }

        if (totalEpisodes <= 0) return@withContext emptyList()

        val bannerId = media.bannerAnilistId()
        val fragment = buildString {
            append("id=").append(media.id)
            media.idMal?.let { append("&mal=").append(it) }
            media.duration?.takeIf { it > 0 }?.let { append("&dur=").append(it) }
            bannerId?.let { append("&banner=").append(it) }
        }

        (totalEpisodes downTo 1).mapNotNull { epNum ->
            val streamMeta = streamingMap[epNum]
            val isFiller = streamMeta?.filler == true
            if (prefHideFiller && isFiller) return@mapNotNull null

            SEpisode.create().apply {
                url = "/anime/$canonicalSlug-episode-$epNum#$fragment"
                episode_number = epNum.toFloat()
                name = formatEpisodeTitle(streamMeta?.title, epNum)
                preview_url = streamMeta?.thumbnail?.takeIf { it.isNotBlank() }
                fillermark = prefMarkFiller && isFiller
                if (isFiller && !prefMarkFiller) {
                    scanlator = "Filler"
                }
            }
        }
    }

    /**
     * Normalizes episode titles to avoid duplicate leading `Episode N` prefixes.
     */
    private fun formatEpisodeTitle(rawTitle: String?, number: Int): String {
        val baseName = "Episode $number"
        val cleaned = rawTitle
            ?.replace(LEADING_EPISODE_LABEL_REGEX, "")
            ?.trim()
            .orEmpty()
        return when {
            cleaned.isEmpty() -> baseName
            cleaned.equals(baseName, ignoreCase = true) -> baseName
            else -> "$baseName: $cleaned"
        }
    }

    // ============================ Video Links =============================

    private class EpisodeTarget(
        val slug: String,
        val episode: Int,
        val catalogId: Long?,
        val malId: Long?,
        val durationMin: Int?,
        val bannerAnilistId: Long?,
    )

    /**
     * Resolves playable hosters, quality variants, subtitles, and skip timestamps for [episode].
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = coroutineScope {
        val target = parseEpisodeTarget(episode.url)
            ?: throw Exception("Unable to parse episode URL: ${episode.url}")

        val resolvedMedia = if (target.catalogId == null || (prefSkipTimes && target.malId == null)) {
            withContext(Dispatchers.IO) {
                suspendRunCatching { fetchMediaBySlug(target.slug) }.getOrNull()
            }
        } else {
            getCachedMedia(target.slug)
        }

        if (resolvedMedia?.isAdult == true) {
            throw Exception("Adult content is disabled")
        }

        val primaryId = target.catalogId ?: resolvedMedia?.id
            ?: throw Exception("Missing anime catalog ID for ${target.slug}")
        val fallbackBannerId = target.bannerAnilistId ?: resolvedMedia?.bannerAnilistId()
        val malId = target.malId ?: resolvedMedia?.idMal
        val durationSec = ((target.durationMin ?: resolvedMedia?.duration ?: 24).coerceAtLeast(1)) * 60
        val canonicalSlug = resolvedMedia?.resolveSlug()?.takeIf { it.isNotBlank() } ?: target.slug
        val watchUrl = "$baseUrl/anime/$canonicalSlug-episode-${target.episode}"

        val skipDeferred = async(Dispatchers.IO) {
            if (prefSkipTimes && malId != null && malId > 0L) {
                fetchSkipTimestamps(malId, target.episode, durationSec)
            } else {
                emptyList()
            }
        }

        val (episodePayload, activeCookie) = withContext(Dispatchers.IO) {
            fetchReadyEpisodePayload(
                primaryId = primaryId,
                fallbackBannerId = fallbackBannerId,
                malId = malId,
                episode = target.episode,
                watchUrl = watchUrl,
            )
        }

        val skipTimestamps = skipDeferred.await()

        val activeVariants = episodePayload.variants.filter { variant ->
            variant.sources.isNotEmpty() && isAudioTypeEnabled(variant.id, variant.label)
        }

        if (activeVariants.isEmpty()) {
            throw Exception("No streams matched your enabled audio preferences for episode ${target.episode}")
        }

        val streamHeaders = buildStreamHeaders(watchUrl, activeCookie)
        val useProxy = prefLocalProxy
        val batchId = if (useProxy) streamProxy.startBatch() else 0L

        class SourceCandidate(
            val variantId: String,
            val audioLabel: String,
            val serverLabel: String,
            val masterUrl: String,
            val tracks: List<EpisodeTrackDto>,
        )

        val candidates = mutableListOf<SourceCandidate>()
        for (variant in activeVariants) {
            val audioLabel = normalizeAudioLabel(variant.id, variant.label)
            val sharedTracks = variant.sources
                .flatMap { it.tracks }
                .filter { !it.type.equals("thumbnails", ignoreCase = true) && it.src.isNotBlank() }
                .distinctBy { trackKey(it) }

            val filteredSources = variant.sources.filter { src ->
                src.url.isNotBlank() && isServerEnabled(src.label)
            }

            val seenUrlsInVariant = HashSet<String>()
            for (source in filteredSources) {
                val absMaster = absolutizeUrl(source.url) ?: continue
                if (!seenUrlsInVariant.add(absMaster)) continue
                val sourceTracks = source.tracks
                    .filter { !it.type.equals("thumbnails", ignoreCase = true) && it.src.isNotBlank() }
                    .ifEmpty { sharedTracks }
                candidates.add(
                    SourceCandidate(
                        variantId = variant.id,
                        audioLabel = audioLabel,
                        serverLabel = source.label.ifBlank { "Server" },
                        masterUrl = absMaster,
                        tracks = sourceTracks,
                    ),
                )
            }
        }

        if (candidates.isEmpty()) {
            throw Exception("No streams matched your enabled server preferences for episode ${target.episode}")
        }

        val hosters = candidates.map { candidate ->
            async(Dispatchers.IO) {
                buildHosterForCandidate(
                    batchId = batchId,
                    candidateVariantId = candidate.variantId,
                    audioLabel = candidate.audioLabel,
                    serverLabel = candidate.serverLabel,
                    masterUrl = candidate.masterUrl,
                    rawTracks = candidate.tracks,
                    streamHeaders = streamHeaders,
                    skipTimestamps = skipTimestamps,
                    useProxy = useProxy,
                    refreshProvider = {
                        runBlocking(Dispatchers.IO) {
                            refreshCandidateBundle(
                                primaryId = primaryId,
                                fallbackBannerId = fallbackBannerId,
                                malId = malId,
                                episode = target.episode,
                                watchUrl = watchUrl,
                                variantId = candidate.variantId,
                                serverLabel = candidate.serverLabel,
                            )
                        }
                    },
                )
            }
        }.awaitAll().filterNotNull()

        hosters.ifEmpty {
            throw Exception("Failed to load video streams for episode ${target.episode}")
        }.sortHosters()
    }

    /**
     * Returns the pre-resolved [Video] list attached to [hoster].
     */
    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    /**
     * Parses a candidate master HLS stream into resolution [Video] entries and subtitle [Track]s.
     */
    private suspend fun buildHosterForCandidate(
        batchId: Long,
        candidateVariantId: String,
        audioLabel: String,
        serverLabel: String,
        masterUrl: String,
        rawTracks: List<EpisodeTrackDto>,
        streamHeaders: Headers,
        skipTimestamps: List<TimeStamp>,
        useProxy: Boolean,
        refreshProvider: () -> RefreshedSourceBundle?,
    ): Hoster? {
        val sortedRawTracks = rawTracks.sortedWith(
            compareByDescending<EpisodeTrackDto> { it.default }
                .thenByDescending {
                    it.language.equals("en", ignoreCase = true) ||
                        it.label?.contains("English", ignoreCase = true) == true
                },
        )

        val subtitleTracks = sortedRawTracks.mapIndexedNotNull { idx, track ->
            val absSubUrl = absolutizeUrl(track.src) ?: return@mapIndexedNotNull null
            val label = track.label?.trim()?.takeIf { it.isNotEmpty() }
                ?: track.language?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Track ${idx + 1}"
            val lookupKey = trackKey(track)
            val finalSubUrl = if (useProxy) {
                streamProxy.registerSubtitle(
                    batchId = batchId,
                    trackLookupKey = lookupKey,
                    url = absSubUrl,
                    headers = streamHeaders,
                    refresh = refreshProvider,
                )
            } else {
                absSubUrl
            }
            Track(finalSubUrl, label)
        }.distinctBy { it.lang }

        val parsedMaster = suspendRunCatching {
            executeWithRetry(GET(masterUrl, streamHeaders, CacheControl.FORCE_NETWORK)).use { resp ->
                if (!resp.isSuccessful) return@suspendRunCatching null
                val text = resp.body.string()
                if (!text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                    null
                } else if (text.contains("#EXT-X-STREAM-INF")) {
                    AnvStreamProxy.parseMasterPlaylist(text, resp.request.url.toString())
                } else {
                    null
                }
            }
        }.getOrNull()

        val parsedVariants = parsedMaster?.variants.orEmpty()
        val masterSessionKeys = parsedMaster?.sessionKeys.orEmpty()
        val canExposeSplitVariants = parsedVariants.isNotEmpty() && (useProxy || masterSessionKeys.isEmpty())

        val hosterDisplayName = "$serverLabel ($audioLabel)"
        val forceMpvArgs = if (useProxy) emptyList() else FORCE_MPEGTS_MPV_ARGS
        val forceFfmpegArgs = if (useProxy) emptyList() else FORCE_MPEGTS_FFMPEG_ARGS

        val videos = buildList {
            if (canExposeSplitVariants) {
                parsedVariants.forEachIndexed { idx, variant ->
                    val resHeight = variant.height
                    val qualityText = resHeight?.let { "${it}p" } ?: "Stream ${idx + 1}"
                    val playUrl = if (useProxy) {
                        streamProxy.registerHls(
                            batchId = batchId,
                            masterUrl = parsedMaster?.finalUrl ?: masterUrl,
                            variantUrl = variant.url,
                            targetHeight = resHeight,
                            headers = streamHeaders,
                            initialSessionKeys = masterSessionKeys,
                            refresh = refreshProvider,
                        )
                    } else {
                        variant.url
                    }
                    add(
                        Video(
                            videoUrl = playUrl,
                            videoTitle = "$serverLabel - $qualityText ($audioLabel)",
                            resolution = resHeight,
                            headers = if (useProxy) null else streamHeaders,
                            subtitleTracks = subtitleTracks,
                            timestamps = skipTimestamps,
                            mpvArgs = forceMpvArgs,
                            ffmpegStreamArgs = forceFfmpegArgs,
                            initialized = true,
                        ),
                    )
                }
            } else {
                val effectiveMaster = parsedMaster?.finalUrl ?: masterUrl
                val autoUrl = if (useProxy) {
                    streamProxy.registerHls(
                        batchId = batchId,
                        masterUrl = effectiveMaster,
                        variantUrl = null,
                        targetHeight = null,
                        headers = streamHeaders,
                        initialSessionKeys = masterSessionKeys,
                        refresh = refreshProvider,
                    )
                } else {
                    effectiveMaster
                }
                add(
                    Video(
                        videoUrl = autoUrl,
                        videoTitle = "$serverLabel - Auto ($audioLabel)",
                        resolution = null,
                        headers = if (useProxy) null else streamHeaders,
                        subtitleTracks = subtitleTracks,
                        timestamps = skipTimestamps,
                        mpvArgs = forceMpvArgs,
                        ffmpegStreamArgs = forceFfmpegArgs,
                        initialized = true,
                    ),
                )
            }
        }.sortVideos()

        if (videos.isEmpty()) return null

        return Hoster(
            hosterUrl = "$masterUrl#$candidateVariantId:$serverLabel",
            hosterName = hosterDisplayName,
            videoList = videos,
        )
    }

    /**
     * Sorts hosters according to the user's preferred audio variant and server order.
     */
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val wantedAudio = prefAudio
        val wantedServer = prefServer
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("($wantedAudio)", ignoreCase = true) }
                .thenByDescending {
                    wantedServer != "Any" &&
                        it.hosterName.startsWith(wantedServer, ignoreCase = true)
                }
                .thenBy { serverPriorityIndex(it.hosterName) },
        )
    }

    /**
     * Sorts video qualities with the user's preferred resolution first, then descending height.
     */
    override fun List<Video>.sortVideos(): List<Video> {
        val wantedRes = prefQuality.removeSuffix("p").toIntOrNull() ?: 1080
        return sortedWith(
            compareByDescending<Video> { it.resolution == wantedRes }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    /**
     * Assigns a deterministic tie-breaker priority across ANV's built-in servers.
     */
    private fun serverPriorityIndex(hosterName: String): Int {
        val lower = hosterName.lowercase()
        return when {
            lower.startsWith("pear") -> 0
            lower.startsWith("banana") -> 1
            lower.startsWith("orange") -> 2
            lower.startsWith("plum") -> 3
            else -> 4
        }
    }

    // ====================== Session, Episode & Skip APIs ==================

    /**
     * Polls `/api/episode` while `"loading"`, breaking immediately on terminal statuses.
     */
    private suspend fun fetchReadyEpisodePayload(
        primaryId: Long,
        fallbackBannerId: Long?,
        malId: Long?,
        episode: Int,
        watchUrl: String,
    ): Pair<EpisodeApiResponse, String> {
        var cookie = ensureWatchCookie(watchUrl, forceRefresh = false)

        val candidateIds = buildList {
            add(primaryId)
            if (fallbackBannerId != null && fallbackBannerId != primaryId) {
                add(fallbackBannerId)
            }
        }

        var lastReason: String? = null
        for (idCandidate in candidateIds) {
            poll@ for (pollAttempt in 0 until 3) {
                var (code, payload) = postEpisodeRequest(idCandidate, malId, episode, watchUrl, cookie)
                if (isEpisodeAuthFailure(code, payload)) {
                    cookie = ensureWatchCookie(watchUrl, forceRefresh = true)
                    val retried = postEpisodeRequest(idCandidate, malId, episode, watchUrl, cookie)
                    code = retried.first
                    payload = retried.second
                }
                if (payload != null) {
                    when (payload.status.lowercase()) {
                        "ready" -> {
                            if (payload.variants.isNotEmpty()) return payload to cookie
                            lastReason = "ready without playable variants"
                            break@poll
                        }
                        "loading" -> {
                            lastReason = "still preparing stream"
                            if (pollAttempt < 2) delay(1_200L)
                        }
                        else -> {
                            lastReason = payload.reason ?: payload.status.ifBlank { "unavailable" }
                            break@poll
                        }
                    }
                } else {
                    lastReason = "HTTP $code"
                    break@poll
                }
            }
        }

        throw Exception("Episode $episode is ${lastReason ?: "unavailable"}")
    }

    /**
     * Re-mints stream tokens and master playlist URLs for an active proxy entry.
     */
    private suspend fun refreshCandidateBundle(
        primaryId: Long,
        fallbackBannerId: Long?,
        malId: Long?,
        episode: Int,
        watchUrl: String,
        variantId: String,
        serverLabel: String,
    ): RefreshedSourceBundle? {
        val freshCookie = suspendRunCatching { ensureWatchCookie(watchUrl, forceRefresh = false) }.getOrNull()
            ?: return null
        val (code, firstPayload) = postEpisodeRequest(primaryId, malId, episode, watchUrl, freshCookie)
        var payload = firstPayload
        var cookieToUse = freshCookie
        if (isEpisodeAuthFailure(code, payload) || !payload?.status.equals("ready", ignoreCase = true)) {
            cookieToUse = suspendRunCatching { ensureWatchCookie(watchUrl, forceRefresh = true) }.getOrNull()
                ?: return null
            payload = postEpisodeRequest(primaryId, malId, episode, watchUrl, cookieToUse).second
            if ((payload == null || !payload.status.equals("ready", ignoreCase = true)) && fallbackBannerId != null) {
                payload = postEpisodeRequest(fallbackBannerId, malId, episode, watchUrl, cookieToUse).second
            }
        }
        val ready = payload?.takeIf { it.status.equals("ready", ignoreCase = true) } ?: return null
        val variant = ready.variants.firstOrNull { it.id.equals(variantId, ignoreCase = true) }
            ?: ready.variants.firstOrNull()
            ?: return null
        val source = variant.sources.firstOrNull { it.label.equals(serverLabel, ignoreCase = true) }
            ?: variant.sources.firstOrNull()
            ?: return null
        val absMaster = absolutizeUrl(source.url) ?: return null
        val tracksMap = (source.tracks.ifEmpty { variant.sources.flatMap { it.tracks } })
            .mapNotNull { t ->
                val abs = absolutizeUrl(t.src) ?: return@mapNotNull null
                trackKey(t) to abs
            }.toMap()
        return RefreshedSourceBundle(
            masterUrl = absMaster,
            headers = buildStreamHeaders(watchUrl, cookieToUse),
            tracksByKey = tracksMap,
        )
    }

    /**
     * Checks whether `/api/episode` rejected the `shiro_watch` session cookie.
     */
    private fun isEpisodeAuthFailure(code: Int, payload: EpisodeApiResponse?): Boolean {
        if (code == 401 || code == 403) return true
        val status = payload?.status?.lowercase().orEmpty()
        val reason = payload?.reason?.lowercase().orEmpty()
        return status == "unauthorized" ||
            status == "forbidden" ||
            reason.contains("cookie") ||
            reason.contains("session") ||
            reason.contains("unauthorized") ||
            reason.contains("forbidden")
    }

    /**
     * Posts an [EpisodeRequest] to `/api/episode` and streams the JSON response into [EpisodeApiResponse].
     */
    private suspend fun postEpisodeRequest(
        anilistId: Long,
        malId: Long?,
        episode: Int,
        watchUrl: String,
        cookie: String,
    ): Pair<Int, EpisodeApiResponse?> {
        val body = EpisodeRequest(
            anilistId = anilistId,
            malId = malId,
            episode = episode,
            first = true,
            prefer = prefAudio.lowercase(),
        ).toJsonRequestBody(json)

        val reqHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("Origin", baseUrl)
            .set("Referer", watchUrl)
            .set("Cookie", buildMergedCookieHeader(baseUrl, cookie))
            .build()

        val request = POST("$baseUrl/api/episode", reqHeaders, body, CacheControl.FORCE_NETWORK)
        return executeWithRetry(request).use { response ->
            val dto = suspendRunCatching { response.parseAs<EpisodeApiResponse>(json) }.getOrNull()
            response.code to dto
        }
    }

    /**
     * Resolves a valid `shiro_watch` cookie from memory, persistent storage, or a clean uncached mint request.
     */
    private suspend fun ensureWatchCookie(watchUrl: String, forceRefresh: Boolean): String {
        val origin = baseUrl
        if (!forceRefresh) {
            loadPersistedWatchCookie(origin)?.let { return it }
        }

        val staleBeforeWait = synchronized(cookieLock) { cachedWatchCookie }
        return cookieMintMutex.withLock {
            if (!forceRefresh) {
                loadPersistedWatchCookie(origin)?.let { return@withLock it }
            } else {
                val refreshedByPeer = synchronized(cookieLock) {
                    cachedWatchCookie?.takeIf {
                        cachedCookieOrigin == origin &&
                            System.currentTimeMillis() < cookieExpiresAtMs &&
                            it != staleBeforeWait
                    }
                }
                if (refreshedByPeer != null) {
                    return@withLock refreshedByPeer
                }
                clearPersistedWatchCookie(origin)
            }

            mintCookieFromWatchPage(watchUrl)?.let { minted ->
                return@withLock persistWatchCookie(origin, minted)
            }

            for (fallbackWatchUrl in resolveFallbackWatchUrls(origin)) {
                if (fallbackWatchUrl == watchUrl) continue
                mintCookieFromWatchPage(fallbackWatchUrl)?.let { minted ->
                    return@withLock persistWatchCookie(origin, minted)
                }
            }

            throw IOException("Failed to obtain $WATCH_COOKIE_NAME session cookie from $origin")
        }
    }

    /**
     * Loads an existing non-expired `shiro_watch` cookie from memory, `SharedPreferences`, or `CookieJar`.
     */
    private fun loadPersistedWatchCookie(origin: String): String? {
        val now = System.currentTimeMillis()
        synchronized(cookieLock) {
            val mem = cachedWatchCookie
            if (mem != null && cachedCookieOrigin == origin && now < cookieExpiresAtMs) {
                return mem
            }
        }

        val prefOrigin = preferences.getString(PREF_WATCH_COOKIE_ORIGIN_KEY, null)
        val prefExpires = preferences.getLong(PREF_WATCH_COOKIE_EXPIRES_KEY, 0L)
        val prefCookie = preferences.getString(PREF_WATCH_COOKIE_VALUE_KEY, null)
            ?.let(::extractWatchCookiePair)
        if (prefCookie != null && prefOrigin == origin && now < prefExpires) {
            synchronized(cookieLock) {
                cachedWatchCookie = prefCookie
                cachedCookieOrigin = origin
                cookieExpiresAtMs = prefExpires
            }
            return prefCookie
        }

        val jarCookie = origin.toHttpUrlOrNull()?.let { httpUrl ->
            runCatching {
                network.client.cookieJar.loadForRequest(httpUrl)
                    .lastOrNull {
                        it.name.equals(WATCH_COOKIE_NAME, ignoreCase = true) &&
                            it.value.isNotBlank() &&
                            it.expiresAt > now
                    }
                    ?.let { "$WATCH_COOKIE_NAME=${it.value}" }
            }.getOrNull()
        } ?: runCatching {
            CookieManager.getInstance()
                .getCookie("$origin/")
                ?.let(::extractWatchCookiePair)
        }.getOrNull()

        return jarCookie?.let { persistWatchCookie(origin, it) }
    }

    /**
     * Persists a freshly minted `shiro_watch` cookie across RAM, `SharedPreferences`, and `CookieJar`.
     */
    private fun persistWatchCookie(origin: String, cookiePair: String): String {
        val normalized = extractWatchCookiePair(cookiePair) ?: cookiePair
        val cookieValue = normalized.substringAfter('=')
        val expiresAt = System.currentTimeMillis() + COOKIE_TTL_MS

        synchronized(cookieLock) {
            cachedWatchCookie = normalized
            cachedCookieOrigin = origin
            cookieExpiresAtMs = expiresAt
        }

        preferences.edit()
            .putString(PREF_WATCH_COOKIE_VALUE_KEY, normalized)
            .putString(PREF_WATCH_COOKIE_ORIGIN_KEY, origin)
            .putLong(PREF_WATCH_COOKIE_EXPIRES_KEY, expiresAt)
            .apply()

        origin.toHttpUrlOrNull()?.let { httpUrl ->
            runCatching {
                val okCookie = Cookie.Builder()
                    .name(WATCH_COOKIE_NAME)
                    .value(cookieValue)
                    .domain(httpUrl.host)
                    .path("/")
                    .expiresAt(expiresAt)
                    .apply { if (httpUrl.isHttps) secure() }
                    .httpOnly()
                    .build()
                network.client.cookieJar.saveFromResponse(httpUrl, listOf(okCookie))
            }
            runCatching {
                val cm = CookieManager.getInstance()
                val maxAgeSec = COOKIE_TTL_MS / 1_000L
                cm.setCookie("$origin/", "$WATCH_COOKIE_NAME=$cookieValue; Path=/; Max-Age=$maxAgeSec")
                cm.flush()
            }
        }

        return normalized
    }

    /**
     * Clears expired `shiro_watch` state from RAM, `SharedPreferences`, and `CookieManager`.
     */
    private fun clearPersistedWatchCookie(origin: String) {
        synchronized(cookieLock) {
            cachedWatchCookie = null
            cachedCookieOrigin = null
            cookieExpiresAtMs = 0L
        }
        preferences.edit()
            .remove(PREF_WATCH_COOKIE_VALUE_KEY)
            .remove(PREF_WATCH_COOKIE_ORIGIN_KEY)
            .remove(PREF_WATCH_COOKIE_EXPIRES_KEY)
            .apply()
        origin.toHttpUrlOrNull()?.let { httpUrl ->
            runCatching {
                val expiredCookie = Cookie.Builder()
                    .name(WATCH_COOKIE_NAME)
                    .value("")
                    .domain(httpUrl.host)
                    .path("/")
                    .expiresAt(0L)
                    .build()
                network.client.cookieJar.saveFromResponse(httpUrl, listOf(expiredCookie))
            }
        }
        runCatching {
            val cm = CookieManager.getInstance()
            val host = origin.toHttpUrlOrNull()?.host
            cm.setCookie("$origin/", "$WATCH_COOKIE_NAME=; Path=/; Max-Age=0")
            if (!host.isNullOrBlank()) {
                cm.setCookie("$origin/", "$WATCH_COOKIE_NAME=; Domain=$host; Path=/; Max-Age=0")
                cm.setCookie("$origin/", "$WATCH_COOKIE_NAME=; Domain=.$host; Path=/; Max-Age=0")
            }
            cm.flush()
        }
    }

    /**
     * Mints a fresh `shiro_watch` cookie via [cookieMintClient] without sending stale `shiro_watch` or hitting disk cache.
     */
    private suspend fun mintCookieFromWatchPage(url: String): String? {
        val reqHeaders = headers.newBuilder()
            .removeAll("Cookie")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        jarMintedCookie.set(null)
        return suspendRunCatching {
            val request = GET(url, reqHeaders, CacheControl.FORCE_NETWORK)
            executeWithRetry(request, cookieMintClient).use { response ->
                val fromChain = extractSetCookieFromResponseChain(response)
                val fromJar = jarMintedCookie.getAndSet(null)
                fromChain ?: fromJar
            }
        }.getOrNull()
    }

    /**
     * Extracts `Set-Cookie: shiro_watch=...` across [response] and any prior redirect responses.
     */
    private fun extractSetCookieFromResponseChain(response: Response): String? = generateSequence(response) { it.priorResponse }
        .flatMap { hop -> hop.headers.values("Set-Cookie").asSequence() }
        .mapNotNull(::extractWatchCookiePair)
        .firstOrNull()

    /**
     * Resolves live episode watch-page URLs (`/anime/<slug>-episode-<N>`) when the primary watch URL cannot mint a cookie.
     */
    private suspend fun resolveFallbackWatchUrls(origin: String): List<String> {
        val results = LinkedHashSet<String>()

        suspendRunCatching {
            val req = GET("$origin/api/recent-episodes", apiHeaders(), CacheControl.FORCE_NETWORK)
            executeWithRetry(req, cookieMintClient).use { resp ->
                if (!resp.isSuccessful) return@use
                val dto = resp.parseAs<RecentEpisodesResponse>(json)
                dto.schedules.asSequence()
                    .filter { it.episode > 0 && it.media != null && !it.media.isAdult }
                    .mapNotNull { item ->
                        val slug = item.media?.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        "$origin/anime/$slug-episode-${item.episode}"
                    }
                    .take(2)
                    .forEach(results::add)
            }
        }
        if (results.isNotEmpty()) return results.toList()

        suspendRunCatching {
            val docHeaders = headers.newBuilder().removeAll("Cookie").set("Referer", "$origin/").build()
            val req = GET("$origin/", docHeaders, CacheControl.FORCE_NETWORK)
            executeWithRetry(req, cookieMintClient).use { resp ->
                if (!resp.isSuccessful) return@use
                val html = resp.body.string()
                HOME_WATCH_HREF_REGEX.findAll(html)
                    .map { "$origin${it.groupValues[1]}" }
                    .take(2)
                    .forEach(results::add)
            }
        }
        if (results.isNotEmpty()) return results.toList()

        suspendRunCatching {
            val docHeaders = headers.newBuilder().removeAll("Cookie").set("Referer", "$origin/").build()
            val req = GET("$origin/recents.xml", docHeaders, CacheControl.FORCE_NETWORK)
            executeWithRetry(req, cookieMintClient).use { resp ->
                if (!resp.isSuccessful) return@use
                val doc = Jsoup.parse(resp.body.string(), origin, Parser.xmlParser())
                doc.select("item > link")
                    .map { it.text().trim() }
                    .filter { EPISODE_PATH_REGEX.containsMatchIn(it) }
                    .take(2)
                    .forEach(results::add)
            }
        }

        return results.toList()
    }

    /**
     * Parses a `shiro_watch=<value>` pair from a `Cookie` or `Set-Cookie` header string.
     */
    private fun extractWatchCookiePair(rawHeader: String?): String? {
        if (rawHeader.isNullOrBlank()) return null
        return rawHeader.split(';')
            .map { it.trim() }
            .firstOrNull { token ->
                val eq = token.indexOf('=')
                eq > 0 &&
                    token.substring(0, eq).trim().equals(WATCH_COOKIE_NAME, ignoreCase = true) &&
                    token.substring(eq + 1).trim().isNotEmpty()
            }
            ?.let { token ->
                val value = token.substringAfter('=').trim()
                "$WATCH_COOKIE_NAME=$value"
            }
    }

    /**
     * Merges [watchCookiePair] into an existing `Cookie` header while replacing any stale `shiro_watch` value.
     */
    private fun mergeCookieHeaderWithWatch(existingHeader: String?, watchCookiePair: String): String {
        val normalizedWatch = extractWatchCookiePair(watchCookiePair) ?: watchCookiePair
        val kept = existingHeader.orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { token ->
                val eq = token.indexOf('=')
                eq > 0 && !token.substring(0, eq).trim().equals(WATCH_COOKIE_NAME, ignoreCase = true)
            }
        return (kept + normalizedWatch).joinToString("; ")
    }

    /**
     * Builds a complete `Cookie` header combining site cookies (such as `cf_clearance`) with [watchCookiePair].
     */
    private fun buildMergedCookieHeader(origin: String, watchCookiePair: String): String {
        val normalizedWatch = extractWatchCookiePair(watchCookiePair) ?: watchCookiePair
        val jarCookies = origin.toHttpUrlOrNull()?.let { httpUrl ->
            runCatching {
                network.client.cookieJar.loadForRequest(httpUrl)
                    .filterNot { it.name.equals(WATCH_COOKIE_NAME, ignoreCase = true) }
                    .map { "${it.name}=${it.value}" }
            }.getOrNull()
        }.orEmpty()
        return (jarCookies + normalizedWatch).joinToString("; ")
    }

    /**
     * Queries `/api/skip-times` for Opening and Ending chapter timestamps.
     */
    private suspend fun fetchSkipTimestamps(malId: Long, episode: Int, durationSec: Int): List<TimeStamp> = suspendRunCatching {
        val url = "$baseUrl/api/skip-times?malId=$malId&episode=$episode&duration=$durationSec"
        client.newCall(GET(url, apiHeaders(), CacheControl.FORCE_NETWORK)).await().use { response ->
            if (!response.isSuccessful) return emptyList()
            val dto = response.parseAs<SkipTimesResponse>(json)
            buildList {
                dto.intro?.takeIf { it.end > it.start && it.end > 0.0 }?.let { intro ->
                    add(
                        TimeStamp(
                            start = intro.start,
                            end = intro.end,
                            name = "Opening",
                            type = ChapterType.Opening,
                        ),
                    )
                }
                dto.outro?.takeIf { it.end > it.start && it.end > 0.0 }?.let { outro ->
                    add(
                        TimeStamp(
                            start = outro.start,
                            end = outro.end,
                            name = "Ending",
                            type = ChapterType.Ending,
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Builds stream request headers carrying `Origin`, `Referer`, and merged site + `shiro_watch` cookies.
     */
    private fun buildStreamHeaders(watchUrl: String, cookie: String): Headers = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Origin", baseUrl)
        .set("Referer", watchUrl)
        .set("Cookie", buildMergedCookieHeader(baseUrl, cookie))
        .build()

    // ========================== Catalog & Cache ===========================

    /**
     * Retrieves a non-expired [MediaDto] from the in-memory detail cache.
     */
    private fun getCachedMedia(slug: String): MediaDto? {
        val entry = detailCache[slug] ?: return null
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            detailCache.remove(slug)
            return null
        }
        return entry.media
    }

    /**
     * Stores [media] under both the requested [slug] and its resolved canonical slug.
     */
    private fun putCachedMedia(slug: String, media: MediaDto) {
        val expires = System.currentTimeMillis() + DETAIL_CACHE_TTL_MS
        detailCache[slug] = CachedMedia(media, expires)
        val resolved = media.resolveSlug()
        if (resolved != slug) {
            detailCache[resolved] = CachedMedia(media, expires)
        }
    }

    /**
     * Fetches anime details by slug (with numeric ID fallback) and caches the result.
     */
    private suspend fun fetchMediaBySlug(slug: String): MediaDto {
        getCachedMedia(slug)?.let { return it }

        val bySlug = suspendRunCatching {
            executeCatalog<SlugVariables, CatalogMediaData>(
                operation = "AnimeDetail",
                variables = SlugVariables(slug = slug),
                referer = "$baseUrl/anime/$slug",
            )?.media
        }.getOrNull()

        if (bySlug != null && (bySlug.id > 0L || !bySlug.slug.isNullOrBlank())) {
            putCachedMedia(slug, bySlug)
            return bySlug
        }

        val leadingId = LEADING_ID_SLUG_REGEX.find(slug)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (leadingId != null) {
            val byId = suspendRunCatching {
                executeCatalog<IdVariables, CatalogMediaData>(
                    operation = "AnimeDetail",
                    variables = IdVariables(id = leadingId),
                    referer = "$baseUrl/anime/$slug",
                )?.media
            }.getOrNull()
            if (byId != null && byId.id > 0L) {
                putCachedMedia(slug, byId)
                return byId
            }
        }

        throw Exception("Anime not found: $slug")
    }

    /**
     * Executes a typed POST operation against `/api/catalog`.
     */
    private suspend inline fun <reified V, reified T> executeCatalog(
        operation: String,
        variables: V,
        referer: String = "$baseUrl/",
    ): T? {
        val body = CatalogRequest(
            operation = operation,
            variables = variables,
        ).toJsonRequestBody(json)

        val request = POST("$baseUrl/api/catalog", apiHeaders(referer), body)
        return executeWithRetry(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Catalog HTTP ${response.code}")
            }
            val parsed = response.parseAs<CatalogResponse<T>>(json)
            if (parsed.data == null && !parsed.errors.isNullOrEmpty()) {
                throw IOException(parsed.errors.first().message ?: "Catalog error")
            }
            parsed.data
        }
    }

    /**
     * Executes an HTTP [request] on [httpClient] with automatic backoff on HTTP 429 responses.
     */
    private suspend fun executeWithRetry(
        request: Request,
        httpClient: OkHttpClient = client,
    ): Response {
        var attempt = 0
        while (true) {
            val response = httpClient.newCall(request).await()
            if (response.code != 429 || attempt >= 2) {
                return response
            }
            val waitMs = AnvStreamProxy.parseRetryAfterMs(response)
            response.close()
            delay(waitMs)
            attempt++
        }
    }

    /**
     * Wraps [block] in a `Result` while rethrowing coroutine [CancellationException]s.
     */
    private inline fun <R> suspendRunCatching(block: () -> R): Result<R> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    // ============================= URL Helpers ============================

    /**
     * Parses an absolute URL, `/anime/<slug>` path, or bare slug using [okhttp3.HttpUrl.pathSegments].
     */
    private fun extractAnimeSlugFromAnyUrl(raw: String): String? {
        val clean = raw.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return null

        val httpUrl = if (clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true)
        ) {
            clean.toHttpUrlOrNull()
        } else {
            baseUrl.toHttpUrlOrNull()?.resolve(clean)
        } ?: return null

        val segments = httpUrl.pathSegments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val slugCandidate = if (segments.first().equals("anime", ignoreCase = true)) {
            segments.getOrNull(1)
        } else {
            segments.last()
        }?.trim().orEmpty()

        if (slugCandidate.isEmpty()) return null
        return slugCandidate.replace(TRAILING_EPISODE_SLUG_REGEX, "").takeIf { it.isNotEmpty() }
    }

    /**
     * Parses an internal episode URL and `#id=...&mal=...` metadata fragment into [EpisodeTarget].
     */
    private fun parseEpisodeTarget(rawUrl: String): EpisodeTarget? {
        val pathPart = rawUrl.substringBefore('#').substringBefore('?').trim()
        val fragmentPart = rawUrl.substringAfter('#', "")

        val match = EPISODE_PATH_REGEX.find(pathPart) ?: return null
        val slug = match.groupValues[1]
        val episode = match.groupValues[2].toIntOrNull() ?: return null

        val params = fragmentPart.split('&')
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
            }.toMap()

        return EpisodeTarget(
            slug = slug,
            episode = episode,
            catalogId = params["id"]?.toLongOrNull(),
            malId = params["mal"]?.toLongOrNull(),
            durationMin = params["dur"]?.toIntOrNull(),
            bannerAnilistId = params["banner"]?.toLongOrNull(),
        )
    }

    /**
     * Resolves relative paths or protocol-relative URLs against [baseUrl].
     */
    private fun absolutizeUrl(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/$raw"
    }

    /**
     * Maps upstream variant codes (`sub`, `dub`, `hsub`) to display labels.
     */
    private fun normalizeAudioLabel(id: String, label: String): String {
        val lower = id.ifBlank { label }.lowercase()
        return when (lower) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "hsub", "h-sub" -> "H-Sub"
            else -> label.ifBlank { id.uppercase() }
        }
    }

    /**
     * Checks whether [id]/[label] is included in the user's enabled audio variants.
     */
    private fun isAudioTypeEnabled(id: String, label: String): Boolean {
        val normalized = normalizeAudioLabel(id, label)
        return prefEnabledTypes.any { it.equals(normalized, ignoreCase = true) }
    }

    /**
     * Checks whether [serverLabel] is included in the user's enabled servers.
     */
    private fun isServerEnabled(serverLabel: String): Boolean = prefEnabledServers.any { it.equals(serverLabel.trim(), ignoreCase = true) }

    /**
     * Produces a normalized lookup key for matching subtitle tracks across token refreshes.
     */
    private fun trackKey(track: EpisodeTrackDto): String = listOfNotNull(
        track.language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        track.label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        track.id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
    ).firstOrNull() ?: "default"

    // ============================= Preferences ============================

    /**
     * Registers user-configurable playback, language, filler, skip, and proxy preferences.
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            default = PREF_TITLE_LANG_DEFAULT,
            title = "Preferred title language",
            summary = "%s",
            entries = listOf("English", "Romaji", "Native"),
            entryValues = listOf("English", "Romaji", "Native"),
        )

        screen.addListPreference(
            key = PREF_POPULAR_MODE_KEY,
            default = PREF_POPULAR_MODE_DEFAULT,
            title = "Popular tab content",
            summary = "%s",
            entries = listOf("Most Popular", "Trending Now", "Home Featured + Trending", "Top Rated"),
            entryValues = listOf("POPULARITY_DESC", "TRENDING_DESC", "HOME", "SCORE_DESC"),
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s",
            entries = listOf("Sub", "Dub", "H-Sub"),
            entryValues = listOf("Sub", "Dub", "H-Sub"),
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = listOf("Any", "Pear", "Banana", "Orange", "Plum"),
            entryValues = listOf("Any", "Pear", "Banana", "Orange", "Plum"),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred video quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
        )

        screen.addSetPreference(
            key = PREF_ENABLED_TYPES_KEY,
            default = DEFAULT_ENABLED_TYPES,
            title = "Enabled audio types",
            summary = "Choose which audio variants to load",
            entries = listOf("Sub", "Dub", "H-Sub"),
            entryValues = listOf("Sub", "Dub", "H-Sub"),
        )

        screen.addSetPreference(
            key = PREF_ENABLED_SERVERS_KEY,
            default = DEFAULT_ENABLED_SERVERS,
            title = "Enabled servers",
            summary = "Choose which ANV stream servers to include",
            entries = listOf("Pear", "Banana", "Orange", "Plum"),
            entryValues = listOf("Pear", "Banana", "Orange", "Plum"),
        )

        screen.addSwitchPreference(
            key = PREF_SKIP_TIMES_KEY,
            default = true,
            title = "Enable Opening/Ending skip chapters",
            summary = "Fetch Intro & Outro timestamps from ANV skip-times API",
        )

        screen.addSwitchPreference(
            key = PREF_MARK_FILLER_KEY,
            default = true,
            title = "Mark filler episodes",
            summary = "Highlight episodes marked as filler in the episode list",
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_FILLER_KEY,
            default = false,
            title = "Hide filler episodes",
            summary = "Exclude filler episodes from the episode list",
        )

        screen.addSwitchPreference(
            key = PREF_PROXY_KEY,
            default = true,
            title = "Stream via local proxy",
            summary = "Recommended: attaches session cookies/referers to HLS segments & subtitles, " +
                "strips disguised image headers, and auto-refreshes expired stream tokens",
        )
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val PAGE_SIZE = 28
        private const val WATCH_COOKIE_NAME = "shiro_watch"
        private const val COOKIE_TTL_MS = 12 * 60 * 60 * 1_000L
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_DETAIL_CACHE = 64

        private const val PREF_WATCH_COOKIE_VALUE_KEY = "anv_watch_cookie_val"
        private const val PREF_WATCH_COOKIE_ORIGIN_KEY = "anv_watch_cookie_origin"
        private const val PREF_WATCH_COOKIE_EXPIRES_KEY = "anv_watch_cookie_exp"

        private val FORCE_MPEGTS_MPV_ARGS = listOf("demuxer-lavf-o" to "force_mpegts=1")
        private val FORCE_MPEGTS_FFMPEG_ARGS = listOf("force_mpegts" to "1")

        private val EPISODE_PATH_REGEX = Regex("""/anime/([a-zA-Z0-9-]+?)-episode-(\d+)$""")
        private val TRAILING_EPISODE_SLUG_REGEX = Regex("""-episode-\d+$""", RegexOption.IGNORE_CASE)
        private val LEADING_ID_SLUG_REGEX = Regex("""^(\d+)(?:-|$)""")
        private val EPISODE_TITLE_NUM_REGEX = Regex("""\b(?:episode|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val LEADING_EPISODE_LABEL_REGEX = Regex("""^episode\s*\d+\s*[—–:-]\s*""", RegexOption.IGNORE_CASE)
        private val RSS_EPISODE_SUFFIX_REGEX = Regex("""\s*[-—–]\s*Episode\s*\d+.*$""", RegexOption.IGNORE_CASE)
        private val HOME_WATCH_HREF_REGEX = Regex("""href="(/anime/[a-zA-Z0-9-]+-episode-\d+)"""")

        private const val PREF_TITLE_LANG_KEY = "anv_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"

        private const val PREF_POPULAR_MODE_KEY = "anv_popular_mode"
        private const val PREF_POPULAR_MODE_DEFAULT = "POPULARITY_DESC"

        private const val PREF_AUDIO_KEY = "anv_preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "anv_preferred_server"
        private const val PREF_SERVER_DEFAULT = "Any"

        private const val PREF_QUALITY_KEY = "anv_preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_ENABLED_TYPES_KEY = "anv_enabled_types"
        private val DEFAULT_ENABLED_TYPES = setOf("Sub", "Dub", "H-Sub")

        private const val PREF_ENABLED_SERVERS_KEY = "anv_enabled_servers"
        private val DEFAULT_ENABLED_SERVERS = setOf("Pear", "Banana", "Orange", "Plum")

        private const val PREF_SKIP_TIMES_KEY = "anv_skip_times"
        private const val PREF_MARK_FILLER_KEY = "anv_mark_filler"
        private const val PREF_HIDE_FILLER_KEY = "anv_hide_filler"
        private const val PREF_PROXY_KEY = "anv_local_proxy"
    }
}
