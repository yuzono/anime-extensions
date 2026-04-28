package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.util.Log
import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class Hanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    /** CDN base URL for manifest and search API requests. */
    private val cdnBaseUrl = "https://cached.freeanimehentai.net"

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

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
        .build()

    /** Headers for video stream requests (m3u8, segments, AES key). */
    private fun playerVideoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
        .build()

    @Volatile
    private var authCookie: String? = null

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences by getPreferencesLazy()

    private val context: Application by injectLazy()

    @Volatile
    private var signatureProvider: SignatureProvider? = null

    @Volatile
    private var signatureProviderMode: String? = null
    private val signatureProviderMutex = Mutex()

    private suspend fun ensureSignatureProvider(): SignatureProvider {
        Log.d(TAG, "ensureSignatureProvider() called — currentMode=${preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)}, existingMode=$signatureProviderMode, hasProvider=${signatureProvider != null}")
        val currentProvider = signatureProvider
        val currentMode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
        if (currentProvider != null && currentMode == signatureProviderMode) {
            Log.d(TAG, "ensureSignatureProvider() — returning cached provider (mode=$currentMode)")
            return currentProvider
        }

        return signatureProviderMutex.withLock {
            val existing = signatureProvider
            if (existing != null && signatureProviderMode == currentMode) {
                Log.d(TAG, "ensureSignatureProvider() — double-check: returning cached provider (mode=$currentMode)")
                return existing
            }

            existing?.close()

            val provider = when (currentMode) {
                "webview" -> {
                    Log.d(TAG, "ensureSignatureProvider() — creating WebViewSignatureProvider")
                    WebViewSignatureProvider()
                }
                "wasm" -> {
                    val binary = runCatching {
                        withContext(Dispatchers.IO) { HanimeWasmBinary.fetchWasmBinary(client) }
                    }.getOrNull()
                    if (binary != null) {
                        Log.d(TAG, "ensureSignatureProvider() — WASM binary fetched (${binary.size} bytes), creating ChicorySignatureProvider")
                        ChicorySignatureProvider(binary)
                    } else {
                        Log.w(TAG, "WASM binary fetch failed — falling back to WebView provider")
                        WebViewSignatureProvider()
                    }
                }
                else -> {
                    Log.w(TAG, "ensureSignatureProvider() — unknown mode '$currentMode', falling back to WebViewSignatureProvider")
                    WebViewSignatureProvider()
                }
            }
            Log.d(TAG, "ensureSignatureProvider() — provider created successfully: ${provider.javaClass.simpleName}")
            signatureProvider = provider
            signatureProviderMode = currentMode
            provider
        }
    }

    // ── Search API (v10 GET endpoint) ──────────────────────────────────

    /** Cached full search response for pagination and client-side filtering. */
    @Volatile
    private var cachedSearchHits: List<HitsModel>? = null

    /** Timestamp of when [cachedSearchHits] was last fetched. */
    @Volatile
    private var cachedSearchHitsTimestamp: Long = 0L

    /** Maximum age of cached search hits before refetching (10 minutes). */
    private val searchHitsTtlMs = 10 * 60 * 1000L

    /**
     * Fetch or return cached search results from the v10 search API.
     * The API returns all content in a single response — pagination and
     * filtering are handled client-side.
     */
    private suspend fun fetchSearchHits(): List<HitsModel> {
        val cached = cachedSearchHits
        if (cached != null && (System.currentTimeMillis() - cachedSearchHitsTimestamp) < searchHitsTtlMs) {
            val ageMs = System.currentTimeMillis() - cachedSearchHitsTimestamp
            Log.d(TAG, "fetchSearchHits() — cache HIT (${cached.size} hits, age=${ageMs}ms, ttl=$searchHitsTtlMs ms)")
            return cached
        }
        Log.d(TAG, "fetchSearchHits() — cache MISS (cached=${cached != null}, age=${if (cached != null) System.currentTimeMillis() - cachedSearchHitsTimestamp else "N/A"}ms, ttl=$searchHitsTtlMs ms)")

        val signature = ensureSignatureProvider().getSignature()
        Log.d(TAG, "fetchSearchHits() — signature obtained: ${signature.signature.take(8)}...")
        val searchHeaders = headers.newBuilder().apply {
            SignatureHeaders.build(signature).forEach { (key, value) ->
                add(key, value)
            }
        }.build()

        val response = client.newCall(GET(SEARCH_API_URL, searchHeaders)).await()
        val hits = response.use { resp ->
            Log.d(TAG, "fetchSearchHits() — search API response code: ${resp.code}")
            val jsonLine = resp.body.string()
            if (jsonLine.isEmpty()) {
                Log.w(TAG, "fetchSearchHits() — search API returned empty body")
                emptyList()
            } else {
                jsonLine.parseAs<List<HitsModel>>()
            }
        }
        Log.d(TAG, "fetchSearchHits() — parsed ${hits.size} hits from search API")
        cachedSearchHits = hits
        cachedSearchHitsTimestamp = System.currentTimeMillis()
        return hits
    }

    /**
     * Build a GET request to the search API with signature authentication headers.
     * The v10 search endpoint requires x-signature, x-time, and x-signature-version headers.
     */
    private suspend fun searchApiRequest(): Request {
        val signature = ensureSignatureProvider().getSignature()
        val searchHeaders = headers.newBuilder().apply {
            SignatureHeaders.build(signature).forEach { (key, value) ->
                add(key, value)
            }
        }.build()
        return GET(SEARCH_API_URL, searchHeaders)
    }

    // ── Popular Anime ──────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(baseUrl, headers)

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

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
            orderBy = orderBy,
            ordering = ordering,
        )
    }

    // ── Latest Updates ─────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

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
            description = item.description?.replace(Regex("<[^>]*>"), "")
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
            filtered = filtered.filter { hit ->
                lowerTags.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
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
        val comparator = when (orderBy) {
            "views" -> compareByDescending<HitsModel> { it.views ?: 0L }
            "likes" -> compareByDescending<HitsModel> { it.likes ?: 0L }
            "created_at_unix", "published_at_unix" -> compareByDescending<HitsModel> { it.createdAtUnix ?: 0L }
            "released_at_unix" -> compareByDescending<HitsModel> { it.releasedAtUnix ?: 0L }
            else -> compareByDescending<HitsModel> { it.likes ?: 0L }
        }
        val sorted = if (ordering == "asc") filtered.sortedWith(comparator.reversed()) else filtered.sortedWith(comparator)

        // Paginate
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, sorted.size)
        val pageItems = if (fromIndex < sorted.size) sorted.subList(fromIndex, toIndex) else emptyList()
        val hasNextPage = toIndex < sorted.size

        return AnimesPage(parseHitsToAnimeList(pageItems), hasNextPage)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun isNumber(num: String) = (num.toIntOrNull() != null)

    private fun getTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.contains(" Ep ")) {
            return trimmed.split(" Ep ")[0].trim()
        }
        val split = trimmed.split(" ")
        return if (split.size > 1 && isNumber(split.last())) {
            split.dropLast(1).joinToString(" ").trim()
        } else {
            trimmed
        }
    }

    // ── Anime Details ──────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = getTitle(document.select("h1.tv-title").text())
            thumbnail_url = document.selectFirst("img.hvpi-cover")?.attr("src")
            author = document.selectFirst("a.hvpimbc-text")?.text() ?: ""
            description = document.select("div.hvpist-description p").joinToString("\n\n") { it.text() }
            status = SAnime.UNKNOWN
            genre = document.select("div.hvpis-text div.btn__content").joinToString { it.text() }
            initialized = true
            setUrlWithoutDomain(document.location())
        }
    }

    // ── Video List ─────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode) = GET(episode.url)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d(TAG, "getVideoList() — episode.url=${episode.url}")
        setAuthCookie()
        Log.d(TAG, "getVideoList() — authCookie ${if (authCookie != null) "present" else "absent"}")
        if (authCookie != null) {
            Log.d(TAG, "getVideoList() — taking premium branch")
            return fetchVideoListPremium(episode)
        }
        // Try manifest endpoint with WASM signature first
        Log.d(TAG, "getVideoList() — taking signature branch")
        return fetchVideoListWithSignature(episode)
    }

    /**
     * Fetch video list using the manifest endpoint with WASM-generated signature headers.
     *
     * Flow:
     * 1. Call /api/v8/video?id={slug} to get the numeric hvId
     * 2. Call the guest manifest endpoint with signature headers for real stream URLs
     * 3. Use PlaylistUtils to parse m3u8 playlists into properly-headed Video objects
     * 4. Fall back to decoy streams from /api/v8/video if manifest fails
     */
    private suspend fun fetchVideoListWithSignature(episode: SEpisode): List<Video> {
        // If hvid is embedded in the episode URL, skip the /api/v8/video call
        val directHvId = extractHvIdFromUrl(episode.url)
        Log.d(TAG, "fetchVideoListWithSignature() — directHvId=${directHvId ?: "null"}")
        if (directHvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(directHvId, retryOnAuthFailure = true)
                Log.d(TAG, "fetchVideoListWithSignature() — directHvId manifest returned ${manifestStreams.size} streams")
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (e: Exception) {
                Log.w(TAG, "fetchVideoListWithSignature() — directHvId manifest failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Fallback: resolve hvId via /api/v8/video (backward compatibility for single-episode URLs)
        val slug = episode.url.substringAfter("id=").substringBefore("&")
        Log.d(TAG, "fetchVideoListWithSignature() — extracting slug='$slug' for /api/v8/video fallback")

        var videoResponseCode = 0
        val videoString = client.newCall(GET("$baseUrl/api/v8/video?id=$slug", headers)).await().use {
            videoResponseCode = it.code
            it.body.string()
        }
        Log.d(TAG, "fetchVideoListWithSignature() — /api/v8/video HTTP $videoResponseCode (body ${videoString.length} chars)")
        if (videoString.isEmpty()) return emptyList()

        val videoModel = videoString.parseAs<VideoModel>()
        val hvId = videoModel.hentaiVideo?.id
            ?: videoModel.videosManifest?.servers?.firstOrNull()?.streams?.firstOrNull()?.hvId
        Log.d(TAG, "fetchVideoListWithSignature() — hvId resolved from video model: ${hvId ?: "null"}")

        if (hvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(hvId, retryOnAuthFailure = true)
                Log.d(TAG, "fetchVideoListWithSignature() — API hvId manifest returned ${manifestStreams.size} streams")
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (e: Exception) {
                Log.w(TAG, "fetchVideoListWithSignature() — API hvId manifest failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Final fallback: parse manifest streams from API response without guest filter
        Log.d(TAG, "fetchVideoListWithSignature() — falling back to parseVideoModelStreamsUnfiltered")
        return parseVideoModelStreamsUnfiltered(videoModel)
    }

    /** Last resort: the /api/v8/video response contains decoy manifest URLs (streamable.cloud) that don't work. Return empty rather than broken streams. */
    private fun tryParseDecoyStreams(@Suppress("UNUSED_PARAMETER") videoString: String): List<Video> {
        Log.w("Hanime", "Falling back to decoy streams — these URLs are non-functional, returning empty list")
        return emptyList()
    }

    /**
     * Parse manifest streams from a VideoModel without the isGuestAllowed filter.
     * Unlike [parseManifestStreams] which requires isGuestAllowed == true, this method
     * includes all streams except premium_alert kinds, making it effective as a fallback
     * when the CDN manifest endpoint fails (e.g. for non-premium multi-episode content).
     */
    private suspend fun parseVideoModelStreamsUnfiltered(videoModel: VideoModel): List<Video> {
        val servers = videoModel.videosManifest?.servers ?: return emptyList()
        val playerHeaders = playerVideoHeaders()
        Log.d(TAG, "parseVideoModelStreamsUnfiltered() — ${servers.size} servers in videoModel")

        return servers.flatMap { server ->
            val filtered = server.streams.filter { it.kind != "premium_alert" && it.url.contains(".m3u8") }
            Log.d(TAG, "parseVideoModelStreamsUnfiltered() — server '${server.name}': ${server.streams.size} total streams, ${filtered.size} after filter (kind!=premium_alert, has .m3u8)")
            filtered.flatMap { stream ->
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = stream.url,
                        masterHeaders = playerHeaders,
                        videoHeaders = playerHeaders,
                        videoNameGen = { quality ->
                            val label = if (quality == "Video") "${stream.height ?: "unknown"}p" else quality
                            "${server.name} - $label"
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "parseVideoModelStreamsUnfiltered() — playlist extraction failed for server '${server.name}' stream ${stream.height ?: "unknown"}p: ${e.javaClass.simpleName}: ${e.message}")
                    emptyList()
                }
            }
        }
    }

    /**
     * Fetch video streams from the CDN manifest endpoint using signature authentication.
     * When [retryOnAuthFailure] is true, a 401 response triggers escalating recovery:
     * 1. Retry with a fresh signature (no cache, so every call is fresh)
     * 2. If still 401, close and recreate the provider entirely, then retry
     */
    private suspend fun fetchManifestVideos(hvId: Long, retryOnAuthFailure: Boolean = false): List<Video> {
        val manifestUrl = "$cdnBaseUrl/api/v8/guest/videos/$hvId/manifest"
        val signature = ensureSignatureProvider().getSignature()
        val sigHeaders = headers.newBuilder().apply {
            SignatureHeaders.build(signature).forEach { (key, value) -> add(key, value) }
        }.build()
        Log.d(TAG, "fetchManifestVideos() — requesting manifest URL: $manifestUrl")
        Log.d(TAG, "fetchManifestVideos() — signature headers: x-signature=${signature.signature.take(8)}..., x-time=${signature.time}, x-signature-version=web2")
        Log.d(TAG, "fetchManifestVideos() — request headers include content-type: ${sigHeaders["content-type"]}")

        var manifestResponseCode = 0
        val result = client.newCall(
            GET(manifestUrl, sigHeaders),
        ).await().use { response ->
            val contentType = response.body.contentType()
            val bodyString = response.body.string()
            manifestResponseCode = response.code
            Log.d(TAG, "fetchManifestVideos() — manifest HTTP $manifestResponseCode (body ${bodyString.length} chars)")
            if (response.isSuccessful) {
                response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                    parseManifestStreams(rebuilt)
                }
            } else {
                val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                Log.w(TAG, "fetchManifestVideos() — manifest non-2xx ($manifestResponseCode) body: $truncated")
                emptyList()
            }
        }

        if (result.isNotEmpty()) return result

        if (manifestResponseCode == 401 && retryOnAuthFailure) {
            // Retry with a fresh signature (no cache, so every call is fresh)
            Log.d(TAG, "fetchManifestVideos() — 401 received, retry #1 with fresh signature")
            val freshSignature = ensureSignatureProvider().getSignature()
            val retryHeaders = headers.newBuilder().apply {
                SignatureHeaders.build(freshSignature).forEach { (key, value) -> add(key, value) }
            }.build()

            var retryResponseCode = 0
            val retryResult = client.newCall(
                GET(manifestUrl, retryHeaders),
            ).await().use { response ->
                val contentType = response.body.contentType()
                val bodyString = response.body.string()
                retryResponseCode = response.code
                Log.d(TAG, "fetchManifestVideos() — retry #1 manifest HTTP $retryResponseCode (body ${bodyString.length} chars)")
                if (response.isSuccessful) {
                    response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                        parseManifestStreams(rebuilt)
                    }
                } else {
                    val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                    Log.w(TAG, "fetchManifestVideos() — retry #1 non-2xx ($retryResponseCode) body: $truncated")
                    emptyList()
                }
            }

            if (retryResult.isNotEmpty()) return retryResult

            // Second retry: the provider itself may be in a bad state — recreate it
            if (retryResponseCode == 401) {
                Log.w(TAG, "fetchManifestVideos() — 401 persists after fresh signature — recreating signature provider")
                signatureProvider?.close()
                signatureProvider = null
                signatureProviderMode = null
                try {
                    Log.d(TAG, "fetchManifestVideos() — retry #2 after provider recreation")
                    val recreatedSignature = ensureSignatureProvider().getSignature()
                    Log.d(TAG, "fetchManifestVideos() — recreated signature: ${recreatedSignature.signature.take(8)}..., time=${recreatedSignature.time}")
                    val recreatedHeaders = headers.newBuilder().apply {
                        SignatureHeaders.build(recreatedSignature).forEach { (key, value) -> add(key, value) }
                    }.build()

                    return client.newCall(
                        GET(manifestUrl, recreatedHeaders),
                    ).await().use { response ->
                        val contentType = response.body.contentType()
                        val bodyString = response.body.string()
                        val responseCode = response.code
                        Log.d(TAG, "fetchManifestVideos() — retry #2 manifest HTTP $responseCode (body ${bodyString.length} chars)")
                        if (response.isSuccessful) {
                            response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                                parseManifestStreams(rebuilt)
                            }
                        } else {
                            if (responseCode == 401) {
                                Log.e(TAG, "fetchManifestVideos() — 401 persists after provider recreation — giving up")
                            } else {
                                val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                                Log.w(TAG, "fetchManifestVideos() — retry #2 non-2xx ($responseCode) body: $truncated")
                            }
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchManifestVideos() — provider recreation failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

        return emptyList()
    }

    /**
     * Parse the guest manifest response and extract HLS video streams.
     * Uses PlaylistUtils.extractFromHls() to properly handle multi-quality
     * m3u8 playlists and set correct headers for segment/AES key requests.
     */
    private suspend fun parseManifestStreams(response: Response): List<Video> {
        val responseString = response.body.string().ifEmpty { return emptyList() }
        val manifestData = responseString.parseAs<ManifestWrapper>()
        val playerHeaders = playerVideoHeaders()
        val servers = manifestData.videosManifest.servers
        Log.d(TAG, "parseManifestStreams() — ${servers.size} servers in manifest")

        return servers.flatMap { server ->
            val guestStreams = server.streams.filter { it.isGuestAllowed == true && it.url.contains(".m3u8") }
            Log.d(TAG, "parseManifestStreams() — server '${server.name}': ${server.streams.size} total streams, ${guestStreams.size} pass isGuestAllowed filter")
            guestStreams.flatMap { stream ->
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = stream.url,
                        masterHeaders = playerHeaders,
                        videoHeaders = playerHeaders,
                        videoNameGen = { quality ->
                            val label = if (quality == "Video") "${stream.height ?: "unknown"}p" else quality
                            "${server.name} - $label"
                        },
                    )
                } catch (_: Exception) {
                    // Fallback: create a single Video from the stream URL
                    listOf(Video(stream.url, "${server.name} - ${stream.height ?: "unknown"}p", stream.url, headers = playerHeaders))
                }
            }
        }.also { Log.d(TAG, "parseManifestStreams() — total videos produced: ${it.size}") }
    }

    private suspend fun fetchVideoListPremium(episode: SEpisode): List<Video> {
        val cookie = authCookie ?: return emptyList()

        // If hvid is embedded in the episode URL, skip the HTML page parse
        val directHvId = extractHvIdFromUrl(episode.url)
        Log.d(TAG, "fetchVideoListPremium() — directHvId=${directHvId ?: "null"}")
        if (directHvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(directHvId, retryOnAuthFailure = true)
                Log.d(TAG, "fetchVideoListPremium() — directHvId manifest returned ${manifestStreams.size} streams")
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (_: Exception) {
                // Fall through to HTML parsing below
                Log.w(TAG, "fetchVideoListPremium() — directHvId manifest failed, falling back to HTML parsing")
            }
        }

        // Fallback: resolve hvId from the HTML page (backward compatibility)
        val slug = episode.url.substringAfter("?id=").substringBefore("&")
        val headers = headers.newBuilder().add("cookie", cookie)
        Log.d(TAG, "fetchVideoListPremium() — fetching HTML page for slug='$slug' to extract __NUXT__ data")
        val document = client.newCall(GET("$baseUrl/videos/hentai/$slug", headers = headers.build())).await().asJsoup()

        val nuxtScript = document.selectFirst("script:containsData(__NUXT__)") ?: return emptyList()
        val nuxtData = nuxtScript.data()
            .substringAfter("__NUXT__=")
            .substringBeforeLast(";")
        val parsed = nuxtData.parseAs<WindowNuxt>()

        // Try CDN guest manifest first — it has real Golem server streams (not Shiva decoys)
        val hvId = parsed.state.data.video.hentai_video?.id
        Log.d(TAG, "fetchVideoListPremium() — hvId from __NUXT__: ${hvId ?: "null"}")
        if (hvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(hvId, retryOnAuthFailure = true)
                Log.d(TAG, "fetchVideoListPremium() — NUXT hvId manifest returned ${manifestStreams.size} streams")
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (_: Exception) {
                // Fall through to __NUXT__ streams below
                Log.w(TAG, "fetchVideoListPremium() — NUXT hvId manifest failed, falling back to NUXT streams")
            }
        }

        // Final fallback: parse manifest streams without guest filter
        Log.d(TAG, "fetchVideoListPremium() — falling back to NUXT parseVideoModelStreamsUnfiltered")
        val nuxtVideoModel = VideoModel(
            videosManifest = eu.kanade.tachiyomi.animeextension.en.hanime.VideosManifest(
                servers = parsed.state.data.video.videos_manifest.servers.map { nuxtServer ->
                    eu.kanade.tachiyomi.animeextension.en.hanime.Server(
                        name = nuxtServer.name,
                        streams = nuxtServer.streams.map { nuxtStream ->
                            eu.kanade.tachiyomi.animeextension.en.hanime.Stream(
                                height = nuxtStream.height,
                                url = nuxtStream.url,
                            )
                        },
                    )
                },
            ),
        )
        return parseVideoModelStreamsUnfiltered(nuxtVideoModel)
    }

    override fun videoListParse(response: Response): List<Video> {
        Log.d(TAG, "videoListParse() — attempting parseVideoModelStreams")
        return parseVideoModelStreams(response.body.string())
    }

    private fun parseVideoModelStreams(responseString: String): List<Video> {
        if (responseString.isEmpty()) return emptyList()
        val videoModel = responseString.parseAs<VideoModel>()
        val manifestStreams = videoModel.videosManifest?.servers?.flatMap { server ->
            server.streams.filter { it.kind != "premium_alert" && it.isGuestAllowed == true }
        } ?: emptyList()
        Log.d(TAG, "parseVideoModelStreams() — ${manifestStreams.size} streams after filtering")

        return if (manifestStreams.isNotEmpty()) {
            manifestStreams.map { stream ->
                Video(stream.url, "${stream.height ?: "unknown"}p", stream.url, headers = playerVideoHeaders())
            }
        } else {
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ── Episode List ───────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/v8/video?id=$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string().ifEmpty { return emptyList() }
        val videoModel = responseString.parseAs<VideoModel>()

        val currentSeriesName = getTitle(videoModel.hentaiVideo?.name ?: "")
        Log.d(TAG, "episodeListParse() — hentaiVideo name: ${videoModel.hentaiVideo?.name}, currentSeriesName: $currentSeriesName")
        val allFranchiseVideos = videoModel.hentaiFranchiseHentaiVideos ?: return emptyList()
        Log.d(TAG, "episodeListParse() — franchise video count: ${allFranchiseVideos.size}")

        val seriesVideos = allFranchiseVideos
            .filter { getTitle(it.name ?: "") == currentSeriesName }
        Log.d(TAG, "episodeListParse() — series videos matching '$currentSeriesName': ${seriesVideos.size} of ${allFranchiseVideos.size}")

        if (seriesVideos.isEmpty()) {
            // No matching series found in franchise; return just the current video as a single episode
            val currentVideo = videoModel.hentaiVideo ?: return emptyList()
            Log.d(TAG, "episodeListParse() — no series match, returning single episode: ${currentVideo.name}")
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = currentVideo.name ?: "Episode 1"
                    date_upload = (currentVideo.releasedAtUnix ?: 0) * 1000
                    val hvidParam = currentVideo.id?.let { id -> "&hvid=$id" } ?: ""
                    url = "$baseUrl/api/v8/video?id=${currentVideo.slug}$hvidParam"
                },
            )
        }

        return seriesVideos.mapIndexed { idx, it ->
            SEpisode.create().apply {
                episode_number = idx + 1f
                name = it.name ?: "Episode ${idx + 1}"
                date_upload = (it.releasedAtUnix ?: 0) * 1000
                val hvidParam = it.id?.let { id -> "&hvid=$id" } ?: ""
                url = "$baseUrl/api/v8/video?id=${it.slug}$hvidParam"
            }
        }.reversed().also { Log.d(TAG, "episodeListParse() — produced ${it.size} episodes") }
    }

    // ── URL Helpers ───────────────────────────────────────────────────

    /** Extract the `hvid` query parameter from an episode URL, or null if absent. */
    private fun extractHvIdFromUrl(url: String): Long? {
        val hvidParam = url.substringAfter("&hvid=", missingDelimiterValue = "").substringBefore("&")
        val result = hvidParam.toLongOrNull()
        Log.d(TAG, "extractHvIdFromUrl() — url=$url, hvidParam='$hvidParam', result=${result ?: "null"}")
        return result
    }

    // ── Auth ───────────────────────────────────────────────────────────

    private fun setAuthCookie() {
        if (authCookie == null) {
            val cookieList = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            Log.d(TAG, "setAuthCookie() — cookie jar has ${cookieList.size} cookies for $baseUrl")
            val sessionCookie = cookieList.firstOrNull { it.name == "htv3session" }
            Log.d(TAG, "setAuthCookie() — htv3session ${if (sessionCookie != null) "found, value=${sessionCookie.value.take(8)}..." else "not found"}")
            sessionCookie?.let { authCookie = "${it.name}=${it.value}" }
        }
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
        private const val SEARCH_API_URL = "https://cached.freeanimehentai.net/api/v10/search_hvs"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SIG_PROVIDER_KEY = "signature_provider"
        private const val PREF_SIG_PROVIDER_DEFAULT = "webview"
        private val SIG_PROVIDER_LIST = arrayOf("webview", "wasm")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }
        screen.addPreference(videoQualityPref)

        val sigProviderPref = ListPreference(screen.context).apply {
            key = PREF_SIG_PROVIDER_KEY
            title = "Signature provider"
            entries = arrayOf("WebView (Recommended)", "Chicory WASM Runtime (Experimental)")
            entryValues = SIG_PROVIDER_LIST
            setDefaultValue(PREF_SIG_PROVIDER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                // Force provider recreation on next access with the new preference
                signatureProvider?.close()
                signatureProvider = null
                signatureProviderMode = null
                true
            }
        }
        screen.addPreference(sigProviderPref)
    }
}
