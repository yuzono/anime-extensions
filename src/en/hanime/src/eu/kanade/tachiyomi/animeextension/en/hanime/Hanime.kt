package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Locale

class Hanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("content-type", "application/json")
        .add("Origin", "https://hanime.tv")
        .add("Referer", "https://hanime.tv/")
        .add("sec-ch-ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Android\"")

    /** Headers for video stream requests (m3u8, segments, AES key). */
    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences by getPreferencesLazy()

    @Volatile
    private var signatureProvider: SignatureProvider? = null

    @Volatile
    private var signatureProviderMode: String? = null
    private val signatureProviderMutex = Mutex()

    private suspend fun ensureSignatureProvider(): SignatureProvider {
        // Fast path: check if provider exists and mode hasn't changed
        val currentProvider = signatureProvider
        val currentMode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
        if (currentProvider != null && currentMode == signatureProviderMode) {
            return currentProvider
        }

        // Slow path: acquire lock and re-read preference inside the lock
        return signatureProviderMutex.withLock {
            val mode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
            val lockedProvider = signatureProvider
            if (lockedProvider != null && signatureProviderMode == mode) {
                lockedProvider
            } else {
                val existing = signatureProvider
                val newProvider = createSignatureProvider(mode)
                Log.d(TAG, "Signature provider created: ${newProvider.javaClass.simpleName}")
                signatureProvider = newProvider
                signatureProviderMode = mode
                existing?.close()
                newProvider
            }
        }
    }

    private suspend fun createSignatureProvider(mode: String?): SignatureProvider = when (mode) {
        "native" -> NativeSignatureProvider()
        "webview" -> WebViewSignatureProvider()
        "wasm" -> {
            val binary = runCatching {
                withContext(Dispatchers.IO) { HanimeWasmBinary.fetchWasmBinary(client) }
            }.getOrNull()
            if (binary != null) {
                ChicorySignatureProvider(binary)
            } else {
                Log.w(TAG, "WASM binary fetch failed — falling back to WebView provider")
                WebViewSignatureProvider()
            }
        }
        else -> {
            Log.w(TAG, "Unknown signature provider mode '$mode', falling back to WebViewSignatureProvider")
            WebViewSignatureProvider()
        }
    }

    // ── Search API (v10 GET endpoint) ──────────────────────────────────

    /** Cached full search response for pagination and client-side filtering. */
    @Volatile
    private var cachedSearchHits: List<HitsModel>? = null

    /** Timestamp of when [cachedSearchHits] was last fetched. */
    @Volatile
    private var cachedSearchHitsTimestamp: Long = 0L

    /** Maximum age of cached search hits before refetching (based on preference, default 10 minutes). */
    private val searchHitsTtlMs: Long
        get() = preferences.getString(PREF_CACHE_TTL_KEY, PREF_CACHE_TTL_DEFAULT)
            ?.toLongOrNull()?.times(60 * 1000L)
            ?: (10L * 60 * 1000L)

    /** Mutex to prevent concurrent search cache refreshes. */
    private val searchCacheMutex = Mutex()

    /**
     * Fetch or return cached search results from the v11 search API.
     * The API returns all content in a single response — pagination and
     * filtering are handled client-side.
     */
    private suspend fun fetchSearchHits(): List<HitsModel> {
        val ttlMs = searchHitsTtlMs

        // Fast path: check cache without lock
        val now = System.currentTimeMillis()
        val cached = cachedSearchHits
        if (cached != null && now - cachedSearchHitsTimestamp < ttlMs) {
            return cached
        }

        // Slow path: acquire lock to prevent redundant fetches
        return searchCacheMutex.withLock {
            // Re-check cache after acquiring lock (another thread may have fetched)
            val nowLocked = System.currentTimeMillis()
            val cachedLocked = cachedSearchHits
            if (cachedLocked != null && nowLocked - cachedSearchHitsTimestamp < ttlMs) {
                cachedLocked
            } else {
                val signature = ensureSignatureProvider().getSignature()
                val searchHeaders = headers.newBuilder().apply {
                    SignatureHeaders.build(signature).forEach { (key, value) ->
                        add(key, value)
                    }
                }.build()

                val response = client.newCall(GET(SEARCH_URL, searchHeaders)).await()
                val result = response.use { resp ->
                    val jsonLine = resp.body.string()
                    if (jsonLine.isEmpty()) {
                        Log.w(TAG, "fetchSearchHits() — search API returned empty body")
                        emptyList()
                    } else {
                        jsonLine.parseAs<SearchResponse>().data
                    }
                }
                cachedSearchHits = result
                cachedSearchHitsTimestamp = System.currentTimeMillis()
                result
            }
        }
    }

    // ── Popular Anime ──────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "likes", ordering = "desc")
    }

    // ── Search Anime ───────────────────────────────────────────────────

    private data class SearchParameters(
        val includedTags: List<String>,
        val blackListedTags: List<String>,
        val brands: List<String>,
        val tagsMode: String,
        val orderBy: String,
        val ordering: String,
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val (includedTags, blackListedTags, brands, tagsMode, orderBy, ordering) = getSearchParameters(filters)
        val allHits = fetchSearchHits()
        return paginateHits(
            hits = allHits,
            page = page,
            query = query,
            includedTags = includedTags,
            blackListedTags = blackListedTags,
            brands = brands,
            tagsMode = tagsMode,
            orderBy = orderBy,
            ordering = ordering,
        )
    }

    // ── Latest Updates ─────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "created_at_unix", ordering = "desc")
    }

    // ── Hit parsing & pagination ───────────────────────────────────────

    private fun parseHitsToAnimeList(hits: List<HitsModel>): List<SAnime> = hits.groupBy { getTitle(it.name) }.map { (_, items) -> items.first() }.map { item ->
        SAnime.create().apply {
            title = getTitle(item.name)
            thumbnail_url = item.coverUrl
            author = item.brand
            description = item.description?.replace(HTML_TAG_REGEX, "")
            status = SAnime.UNKNOWN
            genre = item.tags.joinToString { it }
            initialized = true
            setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.slug)
        }
    }

    private val pageSize = 24

    /**
     * Paginate and sort the full hit list for a given page number.
     * The v10 search API returns all content in one response, so
     * pagination is handled client-side.
     */
    private fun paginateHits(
        hits: List<HitsModel>,
        page: Int,
        query: String = "",
        includedTags: List<String> = emptyList(),
        blackListedTags: List<String> = emptyList(),
        brands: List<String> = emptyList(),
        tagsMode: String = "OR",
        orderBy: String = "likes",
        ordering: String = "desc",
    ): AnimesPage {
        var filtered = hits

        // Apply text search filter
        if (query.isNotEmpty()) {
            val lowerQuery = query.lowercase(Locale.US)
            filtered = filtered.filter { hit ->
                hit.name.lowercase(Locale.US).contains(lowerQuery) ||
                    hit.tags.any { tag -> tag.lowercase(Locale.US).contains(lowerQuery) } ||
                    (hit.brand?.lowercase(Locale.US)?.contains(lowerQuery) == true)
            }
        }

        // Apply tag inclusion filter
        if (includedTags.isNotEmpty()) {
            val lowerTags = includedTags.map { it.lowercase(Locale.US) }
            val isAndMode = tagsMode.equals("and", ignoreCase = true)
            filtered = filtered.filter { hit ->
                if (isAndMode) {
                    lowerTags.all { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                } else {
                    lowerTags.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                }
            }
        }

        // Apply tag blacklist filter
        if (blackListedTags.isNotEmpty()) {
            val lowerBlacklist = blackListedTags.map { it.lowercase(Locale.US) }
            filtered = filtered.filterNot { hit ->
                lowerBlacklist.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
            }
        }

        // Apply brand filter
        if (brands.isNotEmpty()) {
            val lowerBrands = brands.map { it.lowercase(Locale.US) }
            filtered = filtered.filter { hit ->
                hit.brand?.lowercase(Locale.US) in lowerBrands
            }
        }

        // Apply sorting
        val comparator: Comparator<HitsModel> = when (orderBy) {
            "views" -> compareByDescending { it.views ?: 0L }
            "likes" -> compareByDescending { it.likes ?: 0L }
            "created_at_unix", "published_at_unix" -> compareByDescending { it.createdAtUnix ?: 0L }
            "released_at_unix" -> compareByDescending { it.releasedAtUnix ?: 0L }
            "title_sortable" -> compareByDescending { it.name.lowercase(Locale.US) }
            else -> compareByDescending { it.likes ?: 0L }
        }
        val sorted = if (ordering == "asc") filtered.sortedWith(comparator.reversed()) else filtered.sortedWith(comparator)

        // Deduplicate by series BEFORE slicing so each series appears only once across pages
        val uniqueSeries = sorted.distinctBy { getTitle(it.name) }

        // Paginate
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, uniqueSeries.size)
        val pageItems = if (fromIndex < uniqueSeries.size) uniqueSeries.subList(fromIndex, toIndex) else emptyList()
        val hasNextPage = toIndex < uniqueSeries.size

        return AnimesPage(parseHitsToAnimeList(pageItems), hasNextPage)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun getTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.contains(" Ep ")) {
            return trimmed.split(" Ep ")[0].trim()
        }
        // Only strip trailing number if it's a standalone episode number
        // (1-3 digits at the end, preceded by a space)
        val match = EPISODE_SUFFIX_REGEX.find(trimmed)
        return if (match != null) {
            val beforeNumber = trimmed.substring(0, match.range.first)
            // Don't strip if the number is part of "Season N" (the N is a season label, not an episode number)
            if (beforeNumber.trimEnd().endsWith("Season", ignoreCase = true)) {
                trimmed
            }
            // Don't strip if the number is part of a compound like "x 3" or "- 3"
            else if (PREFIX_REGEX.containsMatchIn(beforeNumber)) {
                trimmed
            } else {
                beforeNumber.trim()
            }
        } else {
            trimmed
        }
    }

    private fun formatEpisodeTitle(rawName: String?, seriesName: String, index: Int, format: String): String {
        val fallback = "Episode ${index + 1}"
        if (rawName == null) return fallback
        if (format == "full") return rawName

        val trimmed = rawName.trim()
        // Try "Title Season N" pattern first (e.g. "Modaete yo, Adam-kun Season 1")
        val seasonMatch = SEASON_PATTERN_REGEX.find(trimmed)
        if (seasonMatch != null) {
            val seasonNum = seasonMatch.groupValues[1]
            return "Season $seasonNum - $fallback"
        }
        // Try "Title Ep N" pattern (e.g. "Some Title Ep 3")
        val epMatch = EP_PATTERN_REGEX.find(trimmed)
        if (epMatch != null) {
            return "Episode ${epMatch.groupValues[1]}"
        }
        // Try "Title N" pattern (e.g. "Enjo Kouhai 1")
        val numMatch = TRAILING_NUMBER_REGEX.find(trimmed)
        if (numMatch != null) {
            val beforeNumber = trimmed.substring(0, numMatch.range.first)
            // Only extract if the text before the number matches the series name
            if (beforeNumber.trim().equals(seriesName, ignoreCase = true)) {
                return "Episode ${numMatch.groupValues[1]}"
            }
        }
        // No recognizable pattern — use the raw name
        return trimmed
    }

    // ── Anime Details ──────────────────────────────────────────────────

    /**
     * The site migrated to a JS-rendered frontend without usable detail
     * markup, so details are built from the cached v11 search dataset
     * (which carries name/cover/brand/description/tags for every video).
     */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        fetchSearchHits()
        return buildAnimeFromSlug(extractSlugFromUrl(anime.url)) ?: anime
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.pathSegments.lastOrNull()
            ?.takeUnless { it.isBlank() }
            ?: return SAnime.create()
        return buildAnimeFromSlug(slug) ?: SAnime.create()
    }

    private fun buildAnimeFromSlug(slug: String): SAnime? {
        val hit = cachedSearchHits?.firstOrNull { it.slug == slug } ?: return null
        return SAnime.create().apply {
            title = getTitle(hit.name)
            thumbnail_url = hit.coverUrl
            author = hit.brand
            description = hit.description?.replace(HTML_TAG_REGEX, "")
            status = SAnime.UNKNOWN
            genre = hit.tags.joinToString { it }
            initialized = true
            setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + hit.slug)
        }
    }

    // ── Video List ─────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val slug = extractSlugFromUrl(episode.url)
        return fetchHandshakeVideos(slug)
    }

    /**
     * Fetch video streams via POST /api/v11/handshake on auth.hanime.tv.
     *
     * Flow:
     * 1. Obtain a signature bundle (`x-signature` / `x-time` headers) from
     *    the configured [SignatureProvider]
     * 2. Seal `{"timestamp_unix", "directive": "htv_player_handshake", "slug"}`
     *    into an AES-256-GCM token ([HandshakeCipher.seal])
     * 3. POST it as `{"token": ...}`; the server returns the encrypted
     *    payload in the `x-token` response header
     * 4. Open the token ([HandshakeCipher.open]) and keep sources with
     *    kind == "normal" (kind "promotion" entries are preroll/premium
     *    placeholders). Source URLs are relative (`/hls/{id}/{token}`)
     *    and are resolved against [baseUrl]
     * 5. Expand each playlist into playable videos via PlaylistUtils
     */
    private suspend fun fetchHandshakeVideos(slug: String): List<Video> {
        val signature = ensureSignatureProvider().getSignature()
        val payload = buildJsonObject {
            put("timestamp_unix", signature.time.toLong())
            put("directive", "htv_player_handshake")
            put("slug", slug)
        }.toString()
        val token = HandshakeCipher.seal(payload)

        val requestHeaders = headers.newBuilder()
            .set("x-signature-version", "web2")
            .set("x-signature", signature.signature)
            .set("x-time", signature.time)
            .set("x-csrf-token", "null")
            .build()

        val requestBody = """{"token":"$token"}"""
            .toRequestBody("application/json".toMediaType())

        return client.newCall(POST("$AUTH_BASE_URL/api/v11/handshake", requestHeaders, body = requestBody))
            .await()
            .use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchHandshakeVideos() — handshake non-2xx (${response.code}) for slug '$slug'")
                    return@use emptyList()
                }
                val xToken = response.header("x-token")
                if (xToken.isNullOrEmpty()) {
                    Log.w(TAG, "fetchHandshakeVideos() — missing x-token header for slug '$slug'")
                    return@use emptyList()
                }

                val handshakePayload = HandshakeCipher.open(xToken).parseAs<HandshakePayload>()
                val playerHeaders = videoHeaders()
                handshakePayload.sources
                    .filter { it.kind == "normal" && it.src.isNotBlank() }
                    .parallelFlatMap { stream ->
                        val streamUrl = resolveSourceUrl(stream.src)
                        runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                masterHeaders = playerHeaders,
                                videoHeaders = playerHeaders,
                                videoNameGen = { quality ->
                                    if (quality == "Video") "${stream.height ?: 0}p" else quality
                                },
                            )
                        }.getOrElse { e ->
                            Log.w(TAG, "fetchHandshakeVideos() — HLS extraction failed for $streamUrl: ${e.javaClass.simpleName}: ${e.message}")
                            listOf(Video(streamUrl, stream.label ?: "${stream.height ?: "?"}p", streamUrl, headers = playerHeaders))
                        }
                    }
            }
    }

    private fun resolveSourceUrl(src: String): String = if (src.startsWith("http")) src else baseUrl.trimEnd('/') + src

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { QUALITY_RESOLUTION_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ── Episode List ───────────────────────────────────────────────────

    /**
     * The v8 franchise API is gone, so episodes are derived from the cached
     * search dataset by grouping videos that share the same series title.
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val hits = fetchSearchHits()
        val slug = extractSlugFromUrl(anime.url)
        val current = hits.firstOrNull { it.slug == slug }
            ?: return listOf(singleEpisode(slug, anime.title))

        val seriesName = getTitle(current.name)
        val titleFormat = preferences.getString(PREF_EP_TITLE_FORMAT_KEY, PREF_EP_TITLE_FORMAT_DEFAULT) ?: PREF_EP_TITLE_FORMAT_DEFAULT

        return hits
            .filter { getTitle(it.name) == seriesName }
            .sortedByDescending { it.createdAtUnix ?: it.releasedAtUnix ?: 0L }
            .mapIndexed { idx, hit ->
                SEpisode.create().apply {
                    episode_number = parseEpisodeNumber(hit.name, seriesName, idx + 1)
                    name = formatEpisodeTitle(hit.name, seriesName, idx, titleFormat)
                    date_upload = (hit.createdAtUnix ?: hit.releasedAtUnix ?: 0L) * 1000
                    setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + hit.slug)
                }
            }
    }

    private fun singleEpisode(slug: String, seriesName: String): SEpisode = SEpisode.create().apply {
        episode_number = 1f
        name = seriesName
        setUrlWithoutDomain("https://hanime.tv/videos/hentai/$slug")
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ── URL Helpers ───────────────────────────────────────────────────

    /**
     * Extract the slug from an episode/anime URL. Handles both the current
     * `/videos/hentai/{slug}` scheme and legacy `...?id={slug}&hvid=N` URLs.
     */
    private fun extractSlugFromUrl(url: String): String = when {
        url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
        else -> url.substringBefore("?").substringAfterLast("/")
    }

    // ── Filters ────────────────────────────────────────────────────────

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagList(getTags()),
        BrandList(getBrands()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
        TagInclusionMode(),
    )

    internal class Tag(val id: String, name: String) : AnimeFilter.TriState(name)
    internal class Brand(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TagList(tags: List<Tag>) : AnimeFilter.Group<Tag>("Tags", tags)
    private class BrandList(brands: List<Brand>) : AnimeFilter.Group<Brand>("Brands", brands)
    private class TagInclusionMode : AnimeFilter.Select<String>("Included tags mode", arrayOf("And", "Or"), 0)

    private fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val includedTags = mutableListOf<String>()
        val blackListedTags = mutableListOf<String>()
        val brands = mutableListOf<String>()
        var tagsMode = "AND"
        var orderBy = "likes"
        var ordering = "desc"
        filters.forEach { filter ->
            when (filter) {
                is TagList -> {
                    filter.state.forEach { tag ->
                        if (tag.isIncluded()) {
                            includedTags.add(
                                tag.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        } else if (tag.isExcluded()) {
                            blackListedTags.add(
                                tag.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        }
                    }
                }

                is TagInclusionMode -> {
                    tagsMode = filter.values[filter.state].uppercase(Locale.US)
                }

                is SortFilter -> {
                    if (filter.state != null) {
                        val query = sortableList[filter.state!!.index].second
                        val value = when (filter.state!!.ascending) {
                            true -> "asc"
                            false -> "desc"
                        }
                        ordering = value
                        orderBy = query
                    }
                }

                is BrandList -> {
                    filter.state.forEach { brand ->
                        if (brand.state) {
                            brands.add(
                                brand.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        }
                    }
                }

                else -> {}
            }
        }
        return SearchParameters(includedTags.toList(), blackListedTags.toList(), brands.toList(), tagsMode, orderBy, ordering)
    }

    private fun getBrands() = listOf(
        Brand("37c-Binetsu", "37c-binetsu"),
        Brand("Adult Source Media", "adult-source-media"),
        Brand("Ajia-Do", "ajia-do"),
        Brand("Almond Collective", "almond-collective"),
        Brand("Alpha Polis", "alpha-polis"),
        Brand("Ameliatie", "ameliatie"),
        Brand("Amour", "amour"),
        Brand("Animac", "animac"),
        Brand("Antechinus", "antechinus"),
        Brand("APPP", "appp"),
        Brand("Arms", "arms"),
        Brand("Bishop", "bishop"),
        Brand("Blue Eyes", "blue-eyes"),
        Brand("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
        Brand("Bootleg", "bootleg"),
        Brand("BreakBottle", "breakbottle"),
        Brand("BugBug", "bugbug"),
        Brand("Bunnywalker", "bunnywalker"),
        Brand("Celeb", "celeb"),
        Brand("Central Park Media", "central-park-media"),
        Brand("ChiChinoya", "chichinoya"),
        Brand("Chocolat", "chocolat"),
        Brand("ChuChu", "chuchu"),
        Brand("Circle Tribute", "circle-tribute"),
        Brand("CoCoans", "cocoans"),
        Brand("Collaboration Works", "collaboration-works"),
        Brand("Comet", "comet"),
        Brand("Comic Media", "comic-media"),
        Brand("Cosmos", "cosmos"),
        Brand("Cranberry", "cranberry"),
        Brand("Crimson", "crimson"),
        Brand("D3", "d3"),
        Brand("Daiei", "daiei"),
        Brand("demodemon", "demodemon"),
        Brand("Digital Works", "digital-works"),
        Brand("Discovery", "discovery"),
        Brand("Dollhouse", "dollhouse"),
        Brand("EBIMARU-DO", "ebimaru-do"),
        Brand("Echo", "echo"),
        Brand("ECOLONUN", "ecolonun"),
        Brand("Edge", "edge"),
        Brand("Erozuki", "erozuki"),
        Brand("evee", "evee"),
        Brand("FINAL FUCK 7", "final-fuck-7"),
        Brand("Five Ways", "five-ways"),
        Brand("Friends Media Station", "friends-media-station"),
        Brand("Front Line", "front-line"),
        Brand("fruit", "fruit"),
        Brand("Godoy", "godoy"),
        Brand("GOLD BEAR", "gold-bear"),
        Brand("gomasioken", "gomasioken"),
        Brand("Green Bunny", "green-bunny"),
        Brand("Groover", "groover"),
        Brand("Hoods Entertainment", "hoods-entertainment"),
        Brand("Hot Bear", "hot-bear"),
        Brand("Hykobo", "hykobo"),
        Brand("IRONBELL", "ironbell"),
        Brand("Ivory Tower", "ivory-tower"),
        Brand("J.C.", "j-c"),
        Brand("Jellyfish", "jellyfish"),
        Brand("Jewel", "jewel"),
        Brand("Jumondo", "jumondo"),
        Brand("kate_sai", "kate_sai"),
        Brand("KENZsoft", "kenzsoft"),
        Brand("King Bee", "king-bee"),
        Brand("Kitty Media", "kitty-media"),
        Brand("Knack", "knack"),
        Brand("Kuril", "kuril"),
        Brand("L.", "l"),
        Brand("Lemon Heart", "lemon-heart"),
        Brand("Lilix", "lilix"),
        Brand("Lune Pictures", "lune-pictures"),
        Brand("Magic Bus", "magic-bus"),
        Brand("Magin Label", "magin-label"),
        Brand("Majin Petit", "majin-petit"),
        Brand("Marigold", "marigold"),
        Brand("Mary Jane", "mary-jane"),
        Brand("MediaBank", "mediabank"),
        Brand("Media Blasters", "media-blasters"),
        Brand("Metro Notes", "metro-notes"),
        Brand("Milky", "milky"),
        Brand("MiMiA Cute", "mimia-cute"),
        Brand("Moon Rock", "moon-rock"),
        Brand("Moonstone Cherry", "moonstone-cherry"),
        Brand("Mousou Senka", "mousou-senka"),
        Brand("MS Pictures", "ms-pictures"),
        Brand("Muse", "muse"),
        Brand("N43", "n43"),
        Brand("Nihikime no Dozeu", "nihikime-no-dozeu"),
        Brand("Nikkatsu Video", "nikkatsu-video"),
        Brand("nur", "nur"),
        Brand("NuTech Digital", "nutech-digital"),
        Brand("Obtain Future", "obtain-future"),
        Brand("Otodeli", "otodeli"),
        Brand("@ OZ", "oz"),
        Brand("Pashmina", "pashmina"),
        Brand("Passione", "passione"),
        Brand("Peach Pie", "peach-pie"),
        Brand("Pinkbell", "pinkbell"),
        Brand("Pink Pineapple", "pink-pineapple"),
        Brand("Pix", "pix"),
        Brand("Pixy Soft", "pixy-soft"),
        Brand("Pocomo Premium", "pocomo-premium"),
        Brand("PoRO", "poro"),
        Brand("Project No.9", "project-no-9"),
        Brand("Pumpkin Pie", "pumpkin-pie"),
        Brand("Queen Bee", "queen-bee"),
        Brand("Rabbit Gate", "rabbit-gate"),
        Brand("sakamotoJ", "sakamotoj"),
        Brand("Sakura Purin", "sakura-purin"),
        Brand("SANDWICHWORKS", "sandwichworks"),
        Brand("Schoolzone", "schoolzone"),
        Brand("seismic", "seismic"),
        Brand("SELFISH", "selfish"),
        Brand("Seven", "seven"),
        Brand("Shadow Prod. Co.", "shadow-prod-co"),
        Brand("Shelf", "shelf"),
        Brand("Shinyusha", "shinyusha"),
        Brand("ShoSai", "shosai"),
        Brand("Showten", "showten"),
        Brand("SoftCell", "softcell"),
        Brand("Soft on Demand", "soft-on-demand"),
        Brand("SPEED", "speed"),
        Brand("STARGATE3D", "stargate3d"),
        Brand("Studio 9 Maiami", "studio-9-maiami"),
        Brand("Studio Akai Shohosen", "studio-akai-shohosen"),
        Brand("Studio Deen", "studio-deen"),
        Brand("Studio Fantasia", "studio-fantasia"),
        Brand("Studio FOW", "studio-fow"),
        Brand("studio GGB", "studio-ggb"),
        Brand("Studio Houkiboshi", "studio-houkiboshi"),
        Brand("Studio Zealot", "studio-zealot"),
        Brand("Suiseisha", "suiseisha"),
        Brand("Suzuki Mirano", "suzuki-mirano"),
        Brand("SYLD", "syld"),
        Brand("TDK Core", "tdk-core"),
        Brand("t japan", "t-japan"),
        Brand("TNK", "tnk"),
        Brand("TOHO", "toho"),
        Brand("Toranoana", "toranoana"),
        Brand("T-Rex", "t-rex"),
        Brand("Triangle", "triangle"),
        Brand("Trimax", "trimax"),
        Brand("TYS Work", "tys-work"),
        Brand("U-Jin", "u-jin"),
        Brand("Umemaro-3D", "umemaro-3d"),
        Brand("Union Cho", "union-cho"),
        Brand("Valkyria", "valkyria"),
        Brand("Vanilla", "vanilla"),
        Brand("White Bear", "white-bear"),
        Brand("X City", "x-city"),
        Brand("yosino", "yosino"),
        Brand("Y.O.U.C.", "y-o-u-c"),
        Brand("ZIZ", "ziz"),
    )

    private fun getTags() = listOf(
        Tag("3D", "3D"),
        Tag("AHEGAO", "AHEGAO"),
        Tag("ANAL", "ANAL"),
        Tag("BDSM", "BDSM"),
        Tag("BIG BOOBS", "BIG BOOBS"),
        Tag("BLOW JOB", "BLOW JOB"),
        Tag("BONDAGE", "BONDAGE"),
        Tag("BOOB JOB", "BOOB JOB"),
        Tag("CENSORED", "CENSORED"),
        Tag("COMEDY", "COMEDY"),
        Tag("COSPLAY", "COSPLAY"),
        Tag("CREAMPIE", "CREAMPIE"),
        Tag("DARK SKIN", "DARK SKIN"),
        Tag("FACIAL", "FACIAL"),
        Tag("FANTASY", "FANTASY"),
        Tag("FILMED", "FILMED"),
        Tag("FOOT JOB", "FOOT JOB"),
        Tag("FUTANARI", "FUTANARI"),
        Tag("GANGBANG", "GANGBANG"),
        Tag("GLASSES", "GLASSES"),
        Tag("HAND JOB", "HAND JOB"),
        Tag("HAREM", "HAREM"),
        Tag("HD", "HD"),
        Tag("HORROR", "HORROR"),
        Tag("INCEST", "INCEST"),
        Tag("INFLATION", "INFLATION"),
        Tag("LACTATION", "LACTATION"),
        Tag("LOLI", "LOLI"),
        Tag("MAID", "MAID"),
        Tag("MASTURBATION", "MASTURBATION"),
        Tag("MILF", "MILF"),
        Tag("MIND BREAK", "MIND BREAK"),
        Tag("MIND CONTROL", "MIND CONTROL"),
        Tag("MONSTER", "MONSTER"),
        Tag("NEKOMIMI", "NEKOMIMI"),
        Tag("NTR", "NTR"),
        Tag("NURSE", "NURSE"),
        Tag("ORGY", "ORGY"),
        Tag("PLOT", "PLOT"),
        Tag("POV", "POV"),
        Tag("PREGNANT", "PREGNANT"),
        Tag("PUBLIC SEX", "PUBLIC SEX"),
        Tag("RAPE", "RAPE"),
        Tag("REVERSE RAPE", "REVERSE RAPE"),
        Tag("RIMJOB", "RIMJOB"),
        Tag("SCAT", "SCAT"),
        Tag("SCHOOL GIRL", "SCHOOL GIRL"),
        Tag("SHOTA", "SHOTA"),
        Tag("SOFTCORE", "SOFTCORE"),
        Tag("SWIMSUIT", "SWIMSUIT"),
        Tag("TEACHER", "TEACHER"),
        Tag("TENTACLE", "TENTACLE"),
        Tag("THREESOME", "THREESOME"),
        Tag("TOYS", "TOYS"),
        Tag("TRAP", "TRAP"),
        Tag("TSUNDERE", "TSUNDERE"),
        Tag("UGLY BASTARD", "UGLY BASTARD"),
        Tag("UNCENSORED", "UNCENSORED"),
        Tag("VANILLA", "VANILLA"),
        Tag("VIRGIN", "VIRGIN"),
        Tag("WATERSPORTS", "WATERSPORTS"),
        Tag("X-RAY", "X-RAY"),
        Tag("YAOI", "YAOI"),
        Tag("YURI", "YURI"),
    )

    private val sortableList = listOf(
        Pair("Uploads", "created_at_unix"),
        Pair("Views", "views"),
        Pair("Likes", "likes"),
        Pair("Release", "released_at_unix"),
        Pair("Alphabetical", "title_sortable"),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Sort", sortables, Selection(2, false))

    // ── Preferences ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Hanime"

        /** Guest search API (v11) — returns the full catalog wrapped in `{"data": [...]}`. */
        private const val SEARCH_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"

        /** Auth API host for the v11 handshake endpoint. */
        private const val AUTH_BASE_URL = "https://auth.hanime.tv"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SIG_PROVIDER_KEY = "signature_provider"
        private const val PREF_SIG_PROVIDER_DEFAULT = "native"
        private val SIG_PROVIDER_LIST = arrayOf("native", "webview", "wasm")

        private const val PREF_CACHE_TTL_KEY = "cache_duration"
        private const val PREF_CACHE_TTL_DEFAULT = "10"
        private val CACHE_TTL_LIST = arrayOf("1", "5", "10", "30")

        private const val PREF_EP_TITLE_FORMAT_KEY = "episode_title_format"
        private val EP_TITLE_FORMAT_ENTRIES = listOf("Clean (Episode N)", "Full (Series Name N)")
        private val EP_TITLE_FORMAT_LIST = listOf("clean", "full")
        private val PREF_EP_TITLE_FORMAT_DEFAULT = EP_TITLE_FORMAT_LIST.first()

        // Hoisted Regex constants — compiled once, reused on every call

        /** Matches HTML tags for description sanitization. */
        private val HTML_TAG_REGEX by lazy { Regex("<[^>]*>") }

        /** Matches a trailing episode suffix: a space followed by 1–3 digits at end of string. */
        private val EPISODE_SUFFIX_REGEX by lazy { Regex("""\s(\d{1,3})$""") }

        /** Matches "Season N" at end of a title (case-insensitive). */
        private val SEASON_PATTERN_REGEX by lazy { Regex("""\s+Season\s+(\d{1,3})$""", RegexOption.IGNORE_CASE) }

        /** Matches "Ep N" at end of a title (case-insensitive). */
        private val EP_PATTERN_REGEX by lazy { Regex("""\s+Ep\s+(\d{1,3})$""", RegexOption.IGNORE_CASE) }

        /** Matches a trailing number at end of a title: one or more spaces then 1–3 digits. */
        private val TRAILING_NUMBER_REGEX by lazy { Regex("""\s+(\d{1,3})$""") }

        /** Matches compound prefixes before a number (e.g. "x 3", "- 3", "× 3") that should NOT be stripped. */
        private val PREFIX_REGEX by lazy { Regex("""[\s\-×x]$""", RegexOption.IGNORE_CASE) }

        /** Extracts numeric quality value from a quality label like "1080p". */
        private val QUALITY_RESOLUTION_REGEX by lazy { Regex("""(\d+)p""") }

        /**
         * Parses the episode number from a raw video name, mirroring
         * [formatEpisodeTitle]'s identifier patterns. Falls back to [fallback]
         * when the name carries no recognizable episode number.
         */
        fun parseEpisodeNumber(rawName: String?, seriesName: String, fallback: Int): Float {
            if (rawName == null) return fallback.toFloat()
            val trimmed = rawName.trim()
            // "Title Season N" — N is a season label, not an episode number
            if (SEASON_PATTERN_REGEX.containsMatchIn(trimmed)) return fallback.toFloat()
            // "Title Ep N"
            EP_PATTERN_REGEX.find(trimmed)?.let { return it.groupValues[1].toFloat() }
            // "Title N" — only when the text before the number is the series name itself
            TRAILING_NUMBER_REGEX.find(trimmed)?.let { match ->
                val beforeNumber = trimmed.substring(0, match.range.first)
                if (beforeNumber.trim().equals(seriesName, ignoreCase = true)) {
                    return match.groupValues[1].toFloat()
                }
            }
            return fallback.toFloat()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Preferred Quality
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST.toList(),
            entryValues = QUALITY_LIST.toList(),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        // Signature Provider
        screen.addListPreference(
            key = PREF_SIG_PROVIDER_KEY,
            title = "Signature provider",
            entries = listOf("Direct SHA-256 computation (Recommended)", "WebView", "Chicory WASM Runtime (Experimental)"),
            entryValues = SIG_PROVIDER_LIST.toList(),
            default = PREF_SIG_PROVIDER_DEFAULT,
            summary = "%s",
        ) { _ ->
            signatureProvider?.close()
            signatureProvider = null
            signatureProviderMode = null
        }

        // Search Cache Duration
        screen.addListPreference(
            key = PREF_CACHE_TTL_KEY,
            title = "Search cache duration",
            entries = listOf("1 minute", "5 minutes", "10 minutes", "30 minutes"),
            entryValues = CACHE_TTL_LIST.toList(),
            default = PREF_CACHE_TTL_DEFAULT,
            summary = "%s",
        )

        // Episode Title Format
        screen.addListPreference(
            key = PREF_EP_TITLE_FORMAT_KEY,
            title = "Episode title format",
            entries = EP_TITLE_FORMAT_ENTRIES,
            entryValues = EP_TITLE_FORMAT_LIST,
            default = PREF_EP_TITLE_FORMAT_DEFAULT,
            summary = "%s",
        )
    }
}
