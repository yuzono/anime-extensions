package eu.kanade.tachiyomi.animeextension.all.moviebox

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MovieBox : Source() {

    override val name = "MovieBox"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    override var baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)?.takeIf { it in DOMAIN_VALUES } ?: PREF_DOMAIN_DEFAULT
        set(value) {
            if (value == baseUrl) return
            preferences.edit().putString(PREF_DOMAIN_KEY, value).apply()
            apiHeaders = buildApiHeaders()
            client = buildClient()
        }

    private val apiBase: String get() = baseUrl + API_PATH

    // ============================ Headers & Client =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private var apiHeaders by LazyMutable { buildApiHeaders() }

    private fun buildApiHeaders(): Headers = headersBuilder()
        .set("Accept", "application/json")
        .set("X-Client-Info", "{\"timezone\":\"${TimeZone.getDefault().id}\"}")
        .set("X-Request-Lang", "en")
        .build()

    override var client by LazyMutable { buildClient() }

    private fun buildClient(): OkHttpClient = network.client.newBuilder()
        .rateLimit(API_RATE_LIMIT) { it.encodedPath.startsWith(API_PATH) }
        .addInterceptor(MirrorFailoverInterceptor())
        .addInterceptor(AuthInterceptor())
        .build()

    /** Plain client for the CDN: a rejected signed link must reach the proxy as-is so it can re-sign. */
    private val streamClient by lazy {
        network.client.newBuilder()
            .apply { interceptors().removeAll { it.javaClass.name.contains("Cloudflare", ignoreCase = true) } }
            .build()
    }

    private val proxy by lazy { KeepWarmProxy(streamClient) }

    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val prefDub by preferences.delegate(PREF_DUB_KEY, PREF_DUB_DEFAULT)
    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val prefServers by preferences.delegate(PREF_SERVERS_KEY, PREF_SERVERS_DEFAULT)
    private val prefSubtitle by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val prefLatest by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val prefBuffer by preferences.delegate(PREF_BUFFER_KEY, PREF_BUFFER_DEFAULT)
    private val prefKeepWarm by preferences.delegate(PREF_KEEP_WARM_KEY, PREF_KEEP_WARM_DEFAULT)

    // ============================== Auth ==================================

    private val tokenLock = Any()

    @Volatile
    private var cachedToken: String? = null

    private fun currentToken(): String? = cachedToken ?: preferences.getString(PREF_TOKEN_KEY, null)?.also { cachedToken = it }

    private fun validToken(): String? = currentToken()?.takeIf { tokenExpiry(it) > System.currentTimeMillis() + TOKEN_MARGIN_MS }

    private fun saveToken(token: String) {
        cachedToken = token
        preferences.edit().putString(PREF_TOKEN_KEY, token).apply()
    }

    /** Expiry of the site's guest JWT, or "never" when it cannot be read (the server then decides). */
    private fun tokenExpiry(token: String): Long = runCatching {
        val payload = token.split('.')[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        decoded.parseAs<JsonObject>()["exp"]!!.jsonPrimitive.longOrNull!! * 1000
    }.getOrDefault(Long.MAX_VALUE)

    /** The site hands out a guest token in the `x-user` header of its home endpoint. */
    private fun refreshToken(url: HttpUrl, rejected: String?): String? {
        synchronized(tokenLock) {
            val current = currentToken()
            if (current != null && current != rejected && tokenExpiry(current) > System.currentTimeMillis() + TOKEN_MARGIN_MS) {
                return current
            }
            val hosts = listOfNotNull(url.host, baseUrl.toHttpUrlOrNull()?.host, FALLBACK_API_HOST).distinct()
            for (host in hosts) {
                val token = runCatching {
                    val request = GET("https://$host$API_PATH/home?host=$host", apiHeaders, CacheControl.FORCE_NETWORK)
                    network.client.newCall(request).execute().use { it.header("x-user")?.parseAs<UserDto>()?.token }
                }.getOrNull()
                if (!token.isNullOrBlank()) {
                    saveToken(token)
                    return token
                }
            }
            return null
        }
    }

    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.url.encodedPath.startsWith(API_PATH) || request.url.encodedPath.endsWith("/home")) {
                return chain.proceed(request)
            }
            val token = validToken() ?: if (request.method == "POST") refreshToken(request.url, null) else null
            val response = chain.proceed(request.withToken(token))
            if (token == null) {
                response.header("x-user")
                    ?.let { header -> runCatching { header.parseAs<UserDto>().token }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::saveToken)
            }
            if (!response.isTokenRejected()) return response
            response.close()
            val fresh = refreshToken(request.url, token) ?: throw IOException("MovieBox: could not get a guest token")
            return chain.proceed(request.withToken(fresh))
        }

        private fun Request.withToken(token: String?): Request = if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()

        private fun Response.isTokenRejected(): Boolean = code == 401 || (code == 400 && peekBody(1024).string().contains("invalid token", ignoreCase = true))
    }

    /** A mirror that is down or blocked (non-JSON answer) is replaced by the platform's own API host. */
    private inner class MirrorFailoverInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            if (!url.encodedPath.startsWith(API_PATH) || url.host == FALLBACK_API_HOST) return chain.proceed(request)
            try {
                val response = chain.proceed(request)
                if (response.code < 500 && response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)) return response
                response.close()
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
            }
            val fallback = request.newBuilder().url(url.newBuilder().host(FALLBACK_API_HOST).build()).build()
            return chain.proceed(fallback)
        }
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$apiBase/subject/trending?page=$page&perPage=$PER_PAGE"
        val data = client.get(url, apiHeaders).parseAs<ApiResponse<SubjectListDto>>().requireData()
        return data.toAnimesPage()
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val channels = prefLatest.toIntOrNull()?.let(::listOf) ?: MIXED_CHANNELS
        return browse(page, channels, "Latest")
    }

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = MovieBoxFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val selection = MovieBoxFilters.parse(filters)
        val keyword = query.trim()

        if (keyword.startsWith("http")) {
            val link = keyword.toHttpUrlOrNull()
            val id = link?.queryParameter("id")
            val path = link?.pathSegments?.lastOrNull { it.isNotBlank() }
            if (link != null && (id != null || path != null)) {
                val subject = fetchDetail(id.orEmpty(), path.orEmpty()).subject
                return AnimesPage(listOfNotNull(subject?.toSAnime()), false)
            }
        }

        if (keyword.isNotEmpty()) {
            val body = buildJsonObject {
                put("keyword", keyword)
                put("page", page)
                put("perPage", PER_PAGE)
                put("subjectType", selection.type.searchType)
            }.toString().toJsonBody()
            val data = client.post("$apiBase/subject/search", apiHeaders, body).parseAs<ApiResponse<SubjectListDto>>().requireData()
            return data.toAnimesPage()
        }

        val channels = selection.type.channelId?.let(::listOf) ?: MIXED_CHANNELS
        return browse(page, channels, selection.sort, selection)
    }

    /** Uses the site's filter endpoint; "All" interleaves movies and series page by page. */
    private suspend fun browse(
        page: Int,
        channels: List<Int>,
        sort: String,
        selection: MovieBoxFilters.Selection? = null,
    ): AnimesPage {
        val pages = coroutineScope {
            channels.map { channel -> async { filterPage(page, channel, sort, selection) } }.awaitAll()
        }
        val lists = pages.map { it.subjects }
        val merged = buildList {
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let { add(it) } }
        }
        return AnimesPage(
            merged.distinctBy { it.subjectId }.map { it.toSAnime() },
            pages.any { it.pager?.hasMore == true },
        )
    }

    private suspend fun filterPage(page: Int, channel: Int, sort: String, selection: MovieBoxFilters.Selection?): SubjectListDto {
        val all = MovieBoxFilters.ALL
        val body = buildJsonObject {
            put("page", page)
            put("perPage", PER_PAGE)
            put("channelId", channel)
            put("sort", sort)
            put("country", selection?.country ?: all)
            put("year", selection?.year ?: all)
            if (channel != ANIME_CHANNEL) {
                put("genre", selection?.genre ?: all)
                put("classify", selection?.language ?: all)
            }
        }.toString().toJsonBody()
        return client.post("$apiBase/subject/filter", apiHeaders, body).parseAs<ApiResponse<SubjectListDto>>().requireData()
    }

    private fun SubjectListDto.toAnimesPage(): AnimesPage = AnimesPage(
        subjects.filter { it.subjectId.isNotBlank() }.distinctBy { it.subjectId }.map { it.toSAnime() },
        pager?.hasMore == true,
    )

    private fun SubjectDto.toSAnime(): SAnime = SAnime.create().also { anime ->
        anime.url = animePath(detailPath, subjectId)
        anime.title = title.trim()
        anime.thumbnail_url = cover?.url
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (subjectId, detailPath) = anime.url.ids()
        val detail = fetchDetail(subjectId, detailPath)
        val subject = detail.subject ?: throw Exception("Title not found")
        val stars = detail.stars.orEmpty()
        return SAnime.create().also { result ->
            result.url = anime.url
            result.title = subject.title.trim()
            result.thumbnail_url = subject.cover?.url ?: anime.thumbnail_url
            result.genre = (listOfNotNull(typeName(subject.subjectType)) + subject.genre.orEmpty().split(','))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString()
            result.author = stars.filter { it.character.equals("Director", ignoreCase = true) }
                .mapNotNull { it.name }
                .joinToString()
                .ifEmpty { null }
            result.artist = stars.filterNot { it.character.equals("Director", ignoreCase = true) }
                .mapNotNull { it.name }
                .take(MAX_CAST)
                .joinToString()
                .ifEmpty { null }
            result.status = if (subject.subjectType == TYPE_MOVIE) SAnime.COMPLETED else SAnime.UNKNOWN
            result.description = buildString {
                subject.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
                subject.imdbRatingValue?.takeIf { it.isNotBlank() && it != "0" }?.let { append("IMDb: ").append(it).append('\n') }
                subject.releaseDate?.takeIf { it.isNotBlank() }?.let { append("Released: ").append(it).append('\n') }
                subject.countryName?.takeIf { it.isNotBlank() }?.let { append("Country: ").append(it).append('\n') }
                val seasons = detail.resource?.seasons.orEmpty().filter { it.se > 0 }
                if (seasons.isNotEmpty()) append("Seasons: ").append(seasons.size).append('\n')
                subject.dubs?.map { it.lanName }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                    ?.let { append("Audio: ").append(it.joinToString()).append('\n') }
                subject.subtitles?.takeIf { it.isNotBlank() }?.let { append("Subtitles: ").append(it.replace(",", ", ")).append('\n') }
            }.trim()
            result.initialized = true
        }
    }

    private val detailCache = ConcurrentHashMap<String, Pair<Long, DetailDto>>()

    private suspend fun fetchDetail(subjectId: String, detailPath: String, fresh: Boolean = false): DetailDto {
        val cacheKey = subjectId.ifBlank { detailPath }
        if (!fresh) {
            detailCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < DETAIL_TTL_MS }?.let { return it.second }
        }
        val url = apiBase.toHttpUrl().newBuilder().addPathSegment("detail").apply {
            if (detailPath.isNotBlank()) addQueryParameter("detailPath", detailPath) else addQueryParameter("subjectId", subjectId)
        }.build()
        val detail = client.get(url, apiHeaders, CacheControl.FORCE_NETWORK).parseAs<ApiResponse<DetailDto>>().requireData()
        detail.subject?.subjectId?.takeIf { it.isNotBlank() }?.let { detailCache[it] = System.currentTimeMillis() to detail }
        detailCache[cacheKey] = System.currentTimeMillis() to detail
        return detail
    }

    // ============================== Related ===============================

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val (subjectId, _) = anime.url.ids()
        val url = "$apiBase/subject/detail-rec?subjectId=$subjectId&page=1&perPage=$RELATED_PER_PAGE"
        return client.get(url, apiHeaders).parseAs<ApiResponse<SubjectListDto>>().data?.subjects.orEmpty()
            .filter { it.subjectId.isNotBlank() && it.subjectId != subjectId }
            .distinctBy { it.subjectId }
            .map { it.toSAnime() }
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url.substringBefore("&se=")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (subjectId, detailPath) = anime.url.ids()
        val detail = fetchDetail(subjectId, detailPath, fresh = true)
        val subject = detail.subject ?: throw Exception("Title not found")
        val id = subject.subjectId.ifBlank { subjectId }
        val path = subject.detailPath.ifBlank { detailPath }
        val seasons = detail.resource?.seasons.orEmpty().filter { it.se > 0 }.sortedBy { it.se }

        if (subject.subjectType == TYPE_MOVIE || seasons.isEmpty()) {
            return listOf(
                SEpisode.create().also {
                    it.url = episodePath(path, id, 0, 0)
                    it.name = if (subject.subjectType == TYPE_MOVIE) "Movie" else subject.title.trim()
                    it.episode_number = 1F
                },
            )
        }

        val singleSeason = seasons.size == 1
        var index = 0
        return seasons.flatMap { season ->
            val numbers = season.allEp.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                .ifEmpty { (1..season.maxEp).toList() }
                .distinct()
                .sorted()
            numbers.map { ep ->
                index++
                SEpisode.create().also {
                    it.url = episodePath(path, id, season.se, ep)
                    it.name = "S${season.se} E$ep"
                    it.episode_number = if (singleSeason) ep.toFloat() else index.toFloat()
                }
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (subjectId, detailPath) = episode.url.ids()
        val url = (baseUrl + episode.url).toHttpUrl()
        val se = url.queryParameter("se")?.toIntOrNull() ?: 0
        val ep = url.queryParameter("ep")?.toIntOrNull() ?: 0

        val subject = runCatching { fetchDetail(subjectId, detailPath).subject }.getOrNull()
        val versions = subject?.dubs.orEmpty().filter { it.subjectId.isNotBlank() }.toMutableList()
        if (versions.none { it.subjectId == subjectId }) {
            versions.add(0, DubDto(subjectId = subjectId, lanName = subject?.corner?.takeIf { it.isNotBlank() } ?: ORIGINAL, original = true, detailPath = detailPath))
        }
        val refs = versions.distinctBy { it.subjectId }
            .map { DubRef(it.subjectId, it.detailPath.ifBlank { detailPath }, se, ep, it.lanName.ifBlank { ORIGINAL }) }
            .sortedWith(dubOrder())

        // Load the preferred version right away (skipping versions that lack this episode).
        val failed = mutableSetOf<Int>()
        var loadedIndex = -1
        var loadedVideos: List<Video> = emptyList()
        for (i in 0 until minOf(refs.size, EAGER_VERSIONS)) {
            val videos = runCatching { loadVideos(refs[i]) }.getOrDefault(emptyList())
            if (videos.isNotEmpty()) {
                loadedIndex = i
                loadedVideos = videos
                break
            }
            failed += i
        }

        val webUrl = getEpisodeUrl(episode)
        val hosters = refs.mapIndexedNotNull { i, ref ->
            when {
                i == loadedIndex -> Hoster(hosterUrl = webUrl, hosterName = ref.name, videoList = loadedVideos)
                i in failed -> null
                else -> Hoster(hosterUrl = webUrl, hosterName = ref.name, internalData = ref.toJsonString(), lazy = true)
            }
        }
        if (hosters.isEmpty()) throw Exception("No playable stream for this episode")
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.takeIf { it.isNotEmpty() }?.let { return it }
        val ref = hoster.internalData.parseAs<DubRef>()
        return loadVideos(ref).ifEmpty { throw Exception("No playable stream for ${hoster.hosterName}") }
    }

    override suspend fun resolveVideo(video: Video): Video? = video

    private suspend fun loadVideos(dub: DubRef): List<Video> = coroutineScope {
        for (host in playHosts()) {
            val play = async { runCatching { fetchPlay(host, dub.subjectId, dub.detailPath, dub.se, dub.ep) }.getOrNull() }
            val download = async { runCatching { fetchDownload(host, dub.subjectId, dub.detailPath, dub.se, dub.ep) }.getOrNull() }
            val videos = buildVideos(host, dub, play.await(), download.await())
            if (videos.isNotEmpty()) return@coroutineScope videos
        }
        emptyList()
    }

    private suspend fun buildVideos(host: String, dub: DubRef, play: PlayDto?, download: DownloadDto?): List<Video> {
        val enabled = prefServers
        val headers = videoHeaders(host)
        val streams = play?.streams.orEmpty().filter { it.isPlayable() }
        val downloads = download?.downloads.orEmpty().filter { !it.vipLocked && it.url.isNotBlank() }
        if (streams.isEmpty() && downloads.isEmpty() && play?.dash.orEmpty().none { it.isPlayable() } && play?.hls.isNullOrEmpty()) {
            return emptyList()
        }

        val subtitles = subtitlesOf(host, dub, download, streams.firstOrNull()?.id)
        val videos = mutableListOf<Video>()
        val streamRef = { kind: String, resolution: Int -> StreamRef(host, dub.subjectId, dub.detailPath, dub.se, dub.ep, kind, resolution) }

        if (KIND_STREAM in enabled) {
            streams.distinctBy { it.resolution() }.forEach { stream ->
                videos += mp4Video(streamRef(KIND_STREAM, stream.resolution()), stream.url, dub.name, headers, subtitles)
            }
        }
        if (KIND_DOWNLOAD in enabled) {
            downloads.distinctBy { it.resolution }.forEach { item ->
                videos += mp4Video(streamRef(KIND_DOWNLOAD, item.resolution), item.url, dub.name, headers, subtitles)
            }
        }
        if (KIND_DASH in enabled) {
            play?.dash.orEmpty().filter { it.isPlayable() }.forEach { dash ->
                val resolutions = dash.resolutions.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                val codec = dash.codecName?.uppercase()?.takeIf { it.isNotBlank() }
                videos += Video(
                    videoUrl = dash.url,
                    videoTitle = listOfNotNull(dub.name, SERVER_LABELS.getValue(KIND_DASH), codec, resolutions.maxOrNull()?.let { "${it}p (auto)" }).joinToString(SEPARATOR),
                    resolution = resolutions.maxOrNull(),
                    headers = headers.withSignature(dash.signCookie, dash.signHeaderKey, asCookie = true),
                    subtitleTracks = subtitles,
                    mpvArgs = mpvArgs(),
                    initialized = true,
                )
            }
        }
        if (KIND_HLS in enabled) {
            play?.hls.orEmpty().forEach { hls ->
                val hlsUrl = hls["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@forEach
                val resolution = hls["resolutions"]?.jsonPrimitive?.contentOrNull.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.maxOrNull()
                videos += Video(
                    videoUrl = hlsUrl,
                    videoTitle = listOfNotNull(dub.name, SERVER_LABELS.getValue(KIND_HLS), resolution?.let { "${it}p (auto)" }).joinToString(SEPARATOR),
                    resolution = resolution,
                    headers = headers.withSignature(
                        hls["signCookie"]?.jsonPrimitive?.contentOrNull,
                        hls["signHeaderKey"]?.jsonPrimitive?.contentOrNull,
                        asCookie = false,
                    ),
                    subtitleTracks = subtitles,
                    mpvArgs = mpvArgs(),
                    initialized = true,
                )
            }
        }
        return videos.sortVideos()
    }

    private fun StreamDto.isPlayable() = !vipLocked && url.startsWith("http")

    private fun StreamDto.resolution(): Int = resolutions.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 0

    private fun Headers.withSignature(signCookie: String?, headerKey: String?, asCookie: Boolean): Headers {
        val value = signCookie?.takeIf { it.isNotBlank() } ?: return this
        return newBuilder().apply {
            if (asCookie) set("Cookie", value)
            headerKey?.takeIf { it.isNotBlank() }?.let { set(it, value) }
        }.build()
    }

    private fun mp4Video(ref: StreamRef, url: String, dubName: String, headers: Headers, subtitles: List<Track>): Video {
        val state = stateFor(ref).also { it.seed(url) }
        val playUrl = if (prefKeepWarm) {
            keepWarmJob.start()
            proxy.register(ref.key, headers) { force -> state.url(force) }
        } else {
            url
        }
        val label = listOfNotNull(dubName, SERVER_LABELS.getValue(ref.kind), ref.resolution.takeIf { it > 0 }?.let { "${it}p" })
        return Video(
            videoUrl = playUrl,
            videoTitle = label.joinToString(SEPARATOR),
            resolution = ref.resolution.takeIf { it > 0 },
            headers = headers,
            subtitleTracks = subtitles,
            mpvArgs = mpvArgs(),
            initialized = true,
        )
    }

    private suspend fun subtitlesOf(host: String, dub: DubRef, download: DownloadDto?, streamId: String?): List<Track> {
        val captions = download?.captions.orEmpty().filter { it.url.startsWith("http") }.ifEmpty {
            if (streamId.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val url = "$host$API_PATH/subject/caption".toHttpUrl().newBuilder()
                        .addQueryParameter("format", "MP4")
                        .addQueryParameter("id", streamId)
                        .addQueryParameter("subjectId", dub.subjectId)
                        .addQueryParameter("detailPath", dub.detailPath)
                        .build()
                    client.get(url, playHeaders(host, dub.subjectId, dub.detailPath), CacheControl.FORCE_NETWORK)
                        .parseAs<ApiResponse<CaptionListDto>>().data?.captions.orEmpty()
                }.getOrDefault(emptyList()).filter { it.url.startsWith("http") }
            }
        }
        val preferred = prefSubtitle
        return captions
            .distinctBy { it.url }
            .sortedByDescending { it.lan.orEmpty().startsWith(preferred, ignoreCase = true) }
            .map { caption ->
                val code = caption.lan.orEmpty()
                val name = caption.lanName?.takeIf { it.isNotBlank() } ?: code
                Track(caption.url, if (code.isNotBlank() && !name.equals(code, ignoreCase = true)) "$name ($code)" else name)
            }
    }

    // ------------------------------ Play API ------------------------------

    @Volatile
    private var playerDomain: Pair<Long, String>? = null

    /** The site's dedicated player domain first, then the selected mirror and the platform API host. */
    private suspend fun playHosts(): List<String> {
        val player = playerDomain?.takeIf { System.currentTimeMillis() - it.first < PLAYER_DOMAIN_TTL_MS }?.second
            ?: runCatching {
                client.get("$apiBase/media-player/get-domain", apiHeaders, CacheControl.FORCE_NETWORK)
                    .parseAs<ApiResponse<String>>().data
            }.getOrNull()
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
                ?.also { playerDomain = System.currentTimeMillis() to it }
        // moviebox.ph marks 720p/1080p as VIP-only, so it is never used to play.
        val mirror = baseUrl.takeUnless { it.contains("moviebox.ph") }
        return listOfNotNull(player, mirror, "https://$FALLBACK_API_HOST").distinct()
    }

    /** The play endpoint only answers with streams when called from the site's player page. */
    private fun playHeaders(host: String, subjectId: String, detailPath: String): Headers = apiHeaders.newBuilder()
        .set("Referer", "$host/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail")
        .build()

    private fun videoHeaders(host: String): Headers = headersBuilder()
        .set("Referer", "$host/")
        .set("Accept", "*/*")
        .build()

    private fun episodeQuery(host: String, endpoint: String, subjectId: String, detailPath: String, se: Int, ep: Int): HttpUrl = "$host$API_PATH/subject/$endpoint".toHttpUrl().newBuilder()
        .addQueryParameter("subjectId", subjectId)
        .addQueryParameter("se", se.toString())
        .addQueryParameter("ep", ep.toString())
        .addQueryParameter("detailPath", detailPath)
        .build()

    private suspend fun fetchPlay(host: String, subjectId: String, detailPath: String, se: Int, ep: Int): PlayDto = client.get(episodeQuery(host, "play", subjectId, detailPath, se, ep), playHeaders(host, subjectId, detailPath), CacheControl.FORCE_NETWORK)
        .parseAs<ApiResponse<PlayDto>>()
        .requireData()

    private suspend fun fetchDownload(host: String, subjectId: String, detailPath: String, se: Int, ep: Int): DownloadDto = client.get(episodeQuery(host, "download", subjectId, detailPath, se, ep), playHeaders(host, subjectId, detailPath), CacheControl.FORCE_NETWORK)
        .parseAs<ApiResponse<DownloadDto>>()
        .requireData()

    /** Asks the API for a newly signed link of the same stream (same version, episode, server and quality). */
    private suspend fun resign(ref: StreamRef): String? {
        val hosts = (listOf(ref.host) + playHosts()).distinct()
        for (host in hosts) {
            val url = runCatching {
                when (ref.kind) {
                    KIND_DOWNLOAD -> fetchDownload(host, ref.subjectId, ref.detailPath, ref.se, ref.ep).downloads.orEmpty()
                        .filter { !it.vipLocked && it.url.startsWith("http") }
                        .minByOrNull { abs(it.resolution - ref.resolution) }
                        ?.url

                    else -> fetchPlay(host, ref.subjectId, ref.detailPath, ref.se, ref.ep).streams.orEmpty()
                        .filter { it.isPlayable() }
                        .minByOrNull { abs(it.resolution() - ref.resolution) }
                        ?.url
                }
            }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    // ----------------------------- Keep warm ------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One signed link per stream, re-signed on demand; shared by the player, the proxy and the refresher. */
    private inner class StreamState(private val ref: StreamRef) {
        private val mutex = Mutex()

        @Volatile private var url: String? = null

        @Volatile private var issuedAt = 0L

        @Volatile var lastUsed = System.currentTimeMillis()

        val age: Long get() = if (url == null) Long.MAX_VALUE else System.currentTimeMillis() - issuedAt

        fun seed(fresh: String) {
            url = fresh
            issuedAt = System.currentTimeMillis()
        }

        suspend fun url(forceRefresh: Boolean): String {
            lastUsed = System.currentTimeMillis()
            val stale = url
            return mutex.withLock {
                val current = url
                // Another caller may have refreshed while this one waited for the lock.
                if (current != null && (!forceRefresh || current != stale) && age < FRESH_FOR_MS) return@withLock current
                val fresh = resign(ref) ?: current ?: throw IOException("MovieBox: this stream is no longer available")
                seed(fresh)
                fresh
            }
        }
    }

    private val streams = ConcurrentHashMap<String, StreamState>()

    private fun stateFor(ref: StreamRef): StreamState = streams.getOrPut(ref.key) { StreamState(ref) }

    /**
     * Re-signs links in the background while the player is (or was recently) reading them, so a seek,
     * a reconnect or a resume after a long pause never hits an expired link. Idle streams are dropped.
     */
    private val keepWarmJob by lazy {
        scope.launch {
            while (isActive) {
                delay(KEEP_WARM_TICK_MS)
                val now = System.currentTimeMillis()
                streams.forEach { (key, state) ->
                    val lastUsed = maxOf(state.lastUsed, proxy.lastAccess(key) ?: 0L)
                    when {
                        now - lastUsed > FORGET_AFTER_MS -> streams.remove(key)
                        proxy.lastAccess(key) != null && now - lastUsed < KEEP_WARM_WINDOW_MS && state.age > REFRESH_AFTER_MS ->
                            launch { runCatching { state.url(forceRefresh = true) } }
                    }
                }
                proxy.forgetOlderThan(now - FORGET_AFTER_MS)
            }
        }
    }

    /**
     * Player options: a bigger demuxer cache than the app's 64 MiB default (see the preference) and
     * FFmpeg auto-reconnect, so a dropped connection resumes from the current byte instead of failing.
     */
    private fun mpvArgs(): List<Pair<String, String>> = listOf(
        "cache" to "yes",
        "demuxer-max-bytes" to "${prefBuffer}MiB",
        "demuxer-max-back-bytes" to "${(prefBuffer.toIntOrNull() ?: 96) / 2}MiB",
        "network-timeout" to "30",
        "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10",
    )

    // ============================== Sorting ===============================

    private fun dubOrder(): Comparator<DubRef> {
        val preferred = prefDub
        return compareByDescending<DubRef> { it.name.equals(preferred, ignoreCase = true) }
            .thenByDescending { it.name.equals(ORIGINAL, ignoreCase = true) }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferred = prefDub
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.equals(preferred, ignoreCase = true) }
                .thenByDescending { it.hosterName.equals(ORIGINAL, ignoreCase = true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = prefQuality.toIntOrNull() ?: 1080
        val server = SEPARATOR + SERVER_LABELS.getValue(prefServer.takeIf { it in SERVER_LABELS } ?: PREF_SERVER_DEFAULT)
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(server) }
                .thenByDescending { it.resolution == quality }
                .thenByDescending { (it.resolution ?: 0) <= quality }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================= Utilities ==============================

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 0) throw Exception("MovieBox: ${message ?: "error $code"}")
        return data ?: throw Exception("MovieBox: empty response")
    }

    /** `/movies/<detailPath>?id=<subjectId>` → (subjectId, detailPath). */
    private fun String.ids(): Pair<String, String> {
        val url = (baseUrl + this).toHttpUrl()
        val detailPath = url.pathSegments.getOrNull(1).orEmpty()
        return url.queryParameter("id").orEmpty() to detailPath
    }

    private fun animePath(detailPath: String, subjectId: String) = "/movies/$detailPath?id=$subjectId"

    private fun episodePath(detailPath: String, subjectId: String, se: Int, ep: Int) = "${animePath(detailPath, subjectId)}&se=$se&ep=$ep"

    private fun typeName(type: Int): String? = when (type) {
        TYPE_MOVIE -> "Movie"
        2 -> "TV Series"
        4 -> "Show"
        5 -> "Education"
        6 -> "Music"
        7 -> "Short TV"
        else -> null
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            default = PREF_DOMAIN_DEFAULT,
            title = "Mirror",
            summary = "%s\nIf a mirror is down the extension falls back to MovieBox's own API automatically.",
            entries = DOMAINS,
            entryValues = DOMAIN_VALUES,
            onChange = { _, value ->
                baseUrl = value
                true
            },
        )
        screen.addListPreference(
            key = PREF_DUB_KEY,
            default = PREF_DUB_DEFAULT,
            title = "Preferred audio / version",
            summary = "%s",
            entries = DUB_ENTRIES,
            entryValues = DUB_ENTRIES,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = QUALITIES.map { "${it}p" },
            entryValues = QUALITIES,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = SERVER_LABELS.values.toList(),
            entryValues = SERVER_LABELS.keys.toList(),
        )
        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            default = PREF_SERVERS_DEFAULT,
            title = "Enabled servers",
            summary = "Stream and Download are progressive MP4 (fast seeking); DASH is HEVC with adaptive quality",
            entries = SERVER_LABELS.values.toList(),
            entryValues = SERVER_LABELS.keys.toList(),
        )
        screen.addListPreference(
            key = PREF_SUB_KEY,
            default = PREF_SUB_DEFAULT,
            title = "Preferred subtitle language",
            summary = "%s",
            entries = SUB_LANGUAGES.map { it.first },
            entryValues = SUB_LANGUAGES.map { it.second },
        )
        screen.addListPreference(
            key = PREF_LATEST_KEY,
            default = PREF_LATEST_DEFAULT,
            title = "Latest section content",
            summary = "%s",
            entries = LATEST_ENTRIES.map { it.first },
            entryValues = LATEST_ENTRIES.map { it.second },
        )
        screen.addListPreference(
            key = PREF_BUFFER_KEY,
            default = PREF_BUFFER_DEFAULT,
            title = "Video buffer size",
            summary = "%s\nHow much video the player keeps ahead; bigger survives slow networks better",
            entries = PREF_BUFFER_ENTRIES,
            entryValues = PREF_BUFFER_VALUES,
        )
        screen.addSwitchPreference(
            key = PREF_KEEP_WARM_KEY,
            default = PREF_KEEP_WARM_DEFAULT,
            title = "Keep video links warm",
            summary = "Plays MP4 servers through a local relay that re-signs expired links, so pausing for a long time, seeking or resuming never kills the video",
        )
    }

    companion object {
        private const val API_PATH = "/wefeed-h5api-bff"
        private const val FALLBACK_API_HOST = "h5-api.aoneroom.com"
        private const val API_RATE_LIMIT = 10
        private const val PER_PAGE = 24
        private const val RELATED_PER_PAGE = 24
        private const val ANIME_CHANNEL = 1006
        private val MIXED_CHANNELS = listOf(1, 2)
        private const val TYPE_MOVIE = 1
        private const val MAX_CAST = 12
        private const val EAGER_VERSIONS = 3
        private const val ORIGINAL = "Original Audio"
        private const val SEPARATOR = " \u2022 "

        private const val TOKEN_MARGIN_MS = 24 * 60 * 60_000L
        private const val DETAIL_TTL_MS = 10 * 60_000L
        private const val PLAYER_DOMAIN_TTL_MS = 6 * 60 * 60_000L
        private const val FRESH_FOR_MS = 40 * 60_000L
        private const val REFRESH_AFTER_MS = 25 * 60_000L
        private const val KEEP_WARM_TICK_MS = 60_000L
        private const val KEEP_WARM_WINDOW_MS = 3 * 60 * 60_000L
        private const val FORGET_AFTER_MS = 8 * 60 * 60_000L

        private const val KIND_STREAM = "stream"
        private const val KIND_DOWNLOAD = "download"
        private const val KIND_DASH = "dash"
        private const val KIND_HLS = "hls"
        private val SERVER_LABELS = linkedMapOf(
            KIND_STREAM to "Stream",
            KIND_DOWNLOAD to "Download",
            KIND_DASH to "DASH",
            KIND_HLS to "HLS",
        )

        private const val PREF_TOKEN_KEY = "guest_token"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAINS = listOf(
            "themoviebox.org",
            "moviebox.pk",
            "moviebox.ph",
            "movieboxapp.in",
            "movie-box.tv",
            "moviebox.ac",
            "movieboxhd.net",
        )
        private val DOMAIN_VALUES = DOMAINS.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_DUB_KEY = "preferred_dub"
        private const val PREF_DUB_DEFAULT = ORIGINAL
        private val DUB_ENTRIES = listOf(ORIGINAL) + MovieBoxFilters.LANGUAGES.drop(1)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITIES = listOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = KIND_STREAM

        private const val PREF_SERVERS_KEY = "enabled_servers"
        private val PREF_SERVERS_DEFAULT = setOf(KIND_STREAM, KIND_DOWNLOAD, KIND_DASH, KIND_HLS)

        private const val PREF_SUB_KEY = "preferred_subtitle"
        private const val PREF_SUB_DEFAULT = "en"
        private val SUB_LANGUAGES = listOf(
            "English" to "en",
            "Bengali" to "bn",
            "Hindi" to "hi",
            "Urdu" to "ur",
            "Arabic" to "ar",
            "Spanish" to "es",
            "French" to "fr",
            "Portuguese" to "pt",
            "Indonesian" to "id",
            "Malay" to "ms",
            "Filipino" to "fil",
            "Russian" to "ru",
        )

        private const val PREF_LATEST_KEY = "latest_channel"
        private const val PREF_LATEST_DEFAULT = "mixed"
        private val LATEST_ENTRIES = listOf(
            "Movies & TV series" to "mixed",
            "Movies" to "1",
            "TV series" to "2",
            "Anime" to "1006",
        )

        private const val PREF_BUFFER_KEY = "buffer_size_mib"
        private const val PREF_BUFFER_DEFAULT = "96"
        private val PREF_BUFFER_ENTRIES = listOf("Normal (64 MiB)", "Slightly bigger (96 MiB)", "Large (128 MiB)", "Extra large (192 MiB)")
        private val PREF_BUFFER_VALUES = listOf("64", "96", "128", "192")

        private const val PREF_KEEP_WARM_KEY = "keep_links_warm"
        private const val PREF_KEEP_WARM_DEFAULT = true
    }
}
