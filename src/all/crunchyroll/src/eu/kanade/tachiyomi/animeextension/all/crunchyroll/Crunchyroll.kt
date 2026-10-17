package eu.kanade.tachiyomi.animeextension.all.crunchyroll

import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.ceil

/**
 * Crunchyroll, using the user's own account.
 *
 * Streams are Widevine-protected DASH. The manifest and license endpoint are passed
 * to the player through [Video.internalData]; the device CDM does the key exchange.
 */
class Crunchyroll :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Crunchyroll"

    override val baseUrl = "https://www.crunchyroll.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    init {
        // The app looks for a stored sub/dub value to decide whether to show its
        // switch, and a list preference does not persist its default until changed.
        if (!preferences.contains(PREF_AUDIO_TYPE)) {
            preferences.edit().putString(PREF_AUDIO_TYPE, PREF_AUDIO_TYPE_DEFAULT).apply()
        }
    }

    // ============================== Config ================================

    private val basicToken get() = preferences.getString(PREF_BASIC, "")!!.trim()
    private val etpRt get() = preferences.getString(PREF_ETP_RT, "")!!.trim()
    private val email get() = preferences.getString(PREF_EMAIL, "")!!.trim()
    private val password get() = preferences.getString(PREF_PASSWORD, "")!!.trim()
    private val contentLocale get() = preferences.getString(PREF_LOCALE, PREF_LOCALE_DEFAULT)!!
    private val dubLocale get() = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT)!!

    // Dubs are sibling seasons of one series, so the app's sub/dub switch flips
    // PREF_AUDIO_TYPE instead of picking a different entry. Null means "whatever
    // the original audio is" - not necessarily Japanese.
    private val audioLocale: String?
        get() {
            val dubbed = preferences.getString(PREF_AUDIO_TYPE, PREF_AUDIO_TYPE_DEFAULT) == AUDIO_DUB
            if (!dubbed) return null
            return dubLocale.takeUnless { it == ORIGINAL_LOCALE } ?: FALLBACK_DUB_LOCALE
        }
    private val subtitleLocale get() = preferences.getString(PREF_SUB, PREF_SUB_DEFAULT)!!

    private val deviceId: String by lazy {
        preferences.getString(PREF_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(PREF_DEVICE_ID, it).apply()
            }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    // =============================== Auth =================================

    @Volatile private var accessToken: String? = null

    @Volatile private var tokenExpiresAt = 0L

    @Volatile private var workingGrant: String? = null

    // No cookie jar: BridgeInterceptor sets the Cookie header with header(), which
    // replaces the etp_rt cookie set here and makes the exchange fail with
    // invalid_request.
    private val authClient = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override val client = network.client.newBuilder()
        .addInterceptor(::authIntercept)
        .build()

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/auth/v1/token")) {
            return chain.proceed(request)
        }

        val used = token()
        val response = chain.proceed(request.withToken(used))
        if (response.code != 401) return response

        // Token rejected early (revoked profile switch, clock skew) - retry once. The
        // rejected token is passed along so that a burst of 401s for the same stale
        // token triggers one refresh, not one each.
        response.close()
        return chain.proceed(request.withToken(token(rejected = used)))
    }

    private fun okhttp3.Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    @Synchronized
    private fun token(rejected: String? = null): String {
        val cached = accessToken
        if (cached != null && cached != rejected && System.currentTimeMillis() < tokenExpiresAt) {
            return cached
        }

        if (basicToken.isEmpty()) {
            throw IOException("Set the Basic auth token in the extension settings")
        }

        // A web credential only accepts the cookie exchange, a mobile/TV one only
        // the password grant, and the user cannot tell which they pasted.
        val grants = buildList {
            if (email.isNotEmpty() && password.isNotEmpty()) add(GRANT_PASSWORD)
            if (etpRt.isNotEmpty()) add(GRANT_COOKIE)
        }
            // Tokens last 300s; retrying a hopeless grant that often gets a 429.
            .sortedByDescending { it == workingGrant }

        if (grants.isEmpty()) {
            throw IOException("Set either the etp_rt cookie, or email + password, in the extension settings")
        }

        var lastError: String? = null

        for (grant in grants) {
            val form = FormBody.Builder().apply {
                add("scope", "offline_access")
                add("device_id", deviceId)
                add("device_name", DEVICE_NAME)
                add("device_type", DEVICE_TYPE)
                add("grant_type", grant)
                if (grant == GRANT_PASSWORD) {
                    add("username", email)
                    add("password", password)
                }
            }.build()

            val authHeaders = headersBuilder()
                .set("Authorization", "Basic $basicToken")
                .apply { if (grant == GRANT_COOKIE) set("Cookie", "etp_rt=$etpRt") }
                .build()

            val response = authClient.newCall(
                POST("$baseUrl/auth/v1/token", authHeaders, form),
            ).execute()

            if (!response.isSuccessful) {
                lastError = runCatching { response.parseAs<ErrorDto>().error }.getOrNull()
                    ?: "HTTP ${response.code}"
                Log.w(LOG_TAG, "token: grant '$grant' rejected ($lastError)")
                response.close()
                continue
            }

            val dto = response.parseAs<TokenDto>()
            workingGrant = grant
            accessToken = dto.accessToken
            tokenExpiresAt = System.currentTimeMillis() + (dto.expiresIn - 30).coerceAtLeast(30) * 1000L
            return dto.accessToken
        }

        throw IOException(
            when (lastError) {
                "unsupported_grant_type" ->
                    "Login rejected: this Basic token does not support the credentials given. " +
                        "A web token needs the etp_rt cookie; email + password needs a mobile/TV token."
                "invalid_grant" -> "Login rejected: the etp_rt cookie or password is no longer valid."
                else -> "Login failed: $lastError"
            },
        )
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(page, "popularity")

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(page, "newly_added")

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    private suspend fun browse(page: Int, sortBy: String): AnimesPage {
        val url = "$baseUrl/content/v2/discover/browse".toHttpUrl().newBuilder()
            .addQueryParameter("n", PAGE_SIZE.toString())
            .addQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("sort_by", sortBy)
            .addQueryParameter("type", "series")
            .addQueryParameter("locale", contentLocale)
            .build()

        val data = client.newCall(GET(url, headers)).execute().parseAs<BrowseResponseDto>()
        return AnimesPage(
            data.data.toEntries(),
            page * PAGE_SIZE < data.total,
        )
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.isBlank()) return browse(page, "popularity")

        val url = "$baseUrl/content/v2/discover/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("n", PAGE_SIZE.toString())
            .addQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("type", "series")
            .addQueryParameter("locale", contentLocale)
            .build()

        val data = client.newCall(GET(url, headers)).execute().parseAs<SearchResponseDto>()
        val items = data.data.firstOrNull { it.type == "series" }?.items.orEmpty()

        return AnimesPage(items.toEntries(), items.size >= PAGE_SIZE)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    // Crunchyroll keeps sequels as seasons of one series while trackers list them
    // as separate titles, so a multi-season series is offered one entry per season
    // - otherwise both titles map here and their episode numbers collide. Used by
    // browse and search alike so every listing behaves the same. A failed season
    // lookup fails the page (and can be retried) instead of silently showing a
    // combined entry.
    private suspend fun List<ContentItemDto>.toEntries(): List<SAnime> = fetchAll { item ->
        if (item.seasonCount > 1) seasonEntries(item) else listOf(item.toSAnime())
    }.flatten()

    private fun seasonEntries(item: ContentItemDto): List<SAnime> {
        val seasons = fetchSeasons(item.id)

        if (seasons.size < 2) return listOf(item.toSAnime())

        val series = item.toSAnime()
        return seasons.sortedBy { it.seasonNumber }.map { season ->
            SAnime.create().apply {
                // Only audio-independent ids are stored, so changing the audio
                // setting does not turn saved entries into new ones.
                url = "$SEASON_PREFIX${season.stableId}:${item.id}"
                title = season.cleanTitle.ifEmpty { "${series.title} S${season.seasonNumber}" }
                thumbnail_url = season.thumbnail ?: series.thumbnail_url
                description = series.description
                genre = series.genre
                status = series.status
                initialized = true
            }
        }
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = "$baseUrl/content/v2/cms/series/${anime.seriesId}?locale=$contentLocale"
        val data = client.newCall(GET(url, headers)).execute().parseAs<BrowseResponseDto>()
        val series = data.data.firstOrNull()?.toSAnime() ?: return anime
        // A season entry has its own title and artwork; only the blurb is shared.
        if (anime.seasonId == null) return series
        return anime.apply {
            description = series.description
            genre = series.genre
            status = series.status
            initialized = true
        }
    }

    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/series/${anime.seriesId}"

    /** Season entries are stored as `season:<stableSeasonId>:<seriesId>`. */
    private val SAnime.seasonId: String?
        get() = url.takeIf { it.startsWith(SEASON_PREFIX) }
            ?.removePrefix(SEASON_PREFIX)?.substringBefore(':')

    private val SAnime.seriesId: String
        get() = if (url.startsWith(SEASON_PREFIX)) url.substringAfterLast(':') else url

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // A season entry maps 1:1 onto a tracker season, so its episodes keep the
        // numbering the tracker expects and need no season label.
        anime.seasonId?.let { stableSeasonId ->
            // The stored id is audio-independent; pick the sibling for the current
            // audio setting here. If the season cannot be matched, the original is used.
            val season = fetchSeasons(anime.seriesId).firstOrNull { it.stableId == stableSeasonId }
            return fetchEpisodes(season?.guidFor(audioLocale) ?: stableSeasonId)
                .map { it.toSEpisode("", parseDate(it.date)) }
                .reversed()
        }

        // A series can list the same season once per audio locale; fetchSeasons
        // already collapses those.
        val wanted = fetchSeasons(anime.seriesId).sortedBy { it.seasonNumber }

        // Any failed season fails the whole list: a partial list would misnumber
        // every later season.
        val bySeason = wanted.fetchAll { season ->
            season to fetchEpisodes(season.guidFor(audioLocale))
        }

        // Every season restarts at episode 1, so numbering them as they come makes
        // season 2 collide with season 1 and the app keeps only one of each pair.
        // Each season starts above the highest number the previous one used, which
        // stays correct when a season has gaps or fractional/special numbers.
        var offset = 0f
        return bySeason.flatMap { (season, episodes) ->
            val label = if (bySeason.size > 1) {
                "S${season.seasonDisplayNumber.ifEmpty { season.seasonNumber.toString() }}"
            } else {
                ""
            }
            val numbered = episodes.map {
                it.toSEpisode(label, parseDate(it.date), offset)
            }
            numbered.maxOfOrNull { it.episode_number }?.let { offset = ceil(it) }
            numbered
        }.reversed()
    }

    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun seasonListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode) = "$baseUrl/watch/${episode.url}"

    private fun fetchSeasons(seriesId: String): List<SeasonDto> {
        val url = "$baseUrl/content/v2/cms/series/$seriesId/seasons?locale=$contentLocale"
        return client.newCall(GET(url, headers)).execute()
            .parseAs<SeasonsResponseDto>().data
            .distinctBy { it.seasonNumber to it.seasonDisplayNumber }
    }

    private fun fetchEpisodes(seasonId: String): List<EpisodeDto> {
        val url = "$baseUrl/content/v2/cms/seasons/$seasonId/episodes?locale=$contentLocale"
        return client.newCall(GET(url, headers)).execute()
            .parseAs<EpisodesResponseDto>().data
    }

    // SimpleDateFormat is not thread-safe and episode lists are built in parallel,
    // so every parse gets its own instance.
    private fun parseDate(date: String?): Long = newDateFormat().tryParse(date)

    // Runs the blocking lookups in parallel; the first failure cancels the rest and
    // is rethrown so the app can offer a retry.
    private suspend fun <A, B> List<A>.fetchAll(transform: suspend (A) -> B): List<B> = coroutineScope {
        this@fetchAll.map { async(Dispatchers.IO) { transform(it) } }.awaitAll()
    }

    // ============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        // No playback-stopped hook exists, so this is the only reliable place to
        // hand back the previous slot before taking another.
        releasePreviousStream()

        // Episode URLs hold the audio-independent id; resolve the version for the
        // current audio setting now.
        val mediaId = resolveEpisodeId(episode.url)

        val playUrl = "$PLAY_HOST/v1/$mediaId/$PLAY_PROFILE/play"
        val response = client.newCall(GET(playUrl, headers)).execute()

        if (!response.isSuccessful) {
            val body = runCatching { response.peekBody(2048).string() }.getOrDefault("")
            Log.e(LOG_TAG, "play: $mediaId -> HTTP ${response.code} $body")
            val detail = runCatching { response.parseAs<ErrorDto>() }.getOrNull()
            throw IOException(
                when {
                    detail?.error?.contains("subscription", true) == true ->
                        "Your subscription does not cover this episode"
                    detail?.error?.contains("Rejected", true) == true ->
                        "Playback rejected - too many active streams, or the episode is unavailable in your region"
                    detail?.error != null -> detail.error
                    else -> "Could not start playback (HTTP ${response.code})"
                },
            )
        }

        val play = response.parseAs<PlayResponseDto>()
        play.token?.let { registerStream(mediaId, it, play.session) }

        val offline = play.drm?.let { offlinePayload(mediaId) }

        val drmPayload = play.drm?.let { drm ->
            // The license proxy needs all three; any two of them still return 403.
            DrmPayload(
                drmScheme = drm.name,
                licenseUrl = drm.drmUrl,
                licenseHeaders = buildMap {
                    put("Authorization", "Bearer ${token()}")
                    put("x-cr-content-id", mediaId)
                    play.token?.let { put("x-cr-video-token", it) }
                },
                offline = offline,
            ).toJsonString()
        }.orEmpty()

        if (play.drm != null && play.token == null) {
            Log.w(LOG_TAG, "play response had DRM but no stream token - license will 403")
        }

        val title = buildString {
            append("Crunchyroll")
            if (play.audioLocale.isNotEmpty()) append(" · ${localeName(play.audioLocale)}")
            if (play.drm != null) append(" · DRM")
        }

        return listOf(
            Hoster(
                hosterName = title,
                internalData = play.url,
                videoList = buildVideoList(play, drmPayload),
            ),
        )
    }

    override fun hosterListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response) = throw UnsupportedOperationException()

    // Falls back to the stored (original-audio) id if the lookup fails, so playback
    // still works - just not in the chosen dub.
    private fun resolveEpisodeId(stableId: String): String {
        val wanted = audioLocale ?: return stableId
        return runCatching {
            val url = "$baseUrl/content/v2/cms/objects/$stableId?locale=$contentLocale"
            client.newCall(GET(url, headers)).execute()
                .parseAs<ObjectsResponseDto>().data
                .firstOrNull()?.guidFor(wanted)
        }.onFailure {
            Log.w(LOG_TAG, "audio version lookup for $stableId failed: ${it.message}")
        }.getOrNull() ?: stableId
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override fun videoListRequest(hoster: Hoster) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster) = throw UnsupportedOperationException()

    private fun buildVideoList(play: PlayResponseDto, drmPayload: String): List<Video> {
        // The player makes these requests itself, outside authIntercept, so the
        // token cannot be refreshed once it expires.
        val videoHeaders = headersBuilder()
            .set("Authorization", "Bearer ${token()}")
            .build()

        // A locale can appear in both maps, so merging them as maps would drop the
        // dialogue track in favour of the CC one.
        val subtitles = buildList {
            play.subtitles.forEach { (locale, sub) -> add(Track(sub.url, localeName(locale))) }
            play.captions.forEach { (locale, sub) -> add(Track(sub.url, "${localeName(locale)} (CC)")) }
        }.sortedWith(compareByDescending { it.lang.startsWith(localeName(subtitleLocale)) })

        val title = buildString {
            append("Crunchyroll")
            if (play.audioLocale.isNotEmpty()) append(" · ${localeName(play.audioLocale)}")
            if (play.drm != null) append(" · DRM")
        }

        return listOf(
            Video(
                videoUrl = play.url,
                videoTitle = title,
                headers = videoHeaders,
                subtitleTracks = subtitles,
                internalData = drmPayload,
            ),
        )
    }

    // Downloads come from their own profile: 720p only, no renewal session, and a
    // license endpoint that mints persistent licenses. Signed URLs last ~6h.
    // Never throws - a failure here must not stop playback.
    private fun offlinePayload(episodeId: String): OfflinePayload? = runCatching {
        val url = "$PLAY_HOST/v1/$episodeId/$DOWNLOAD_PROFILE/download"
        val response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val download = response.parseAs<PlayResponseDto>()
        val drm = download.drm ?: return null
        OfflinePayload(
            manifestUrl = download.url,
            headers = mapOf("Authorization" to "Bearer ${token()}"),
            licenseUrl = drm.drmUrl,
            licenseHeaders = buildMap {
                put("Authorization", "Bearer ${token()}")
                put("x-cr-content-id", episodeId)
                download.token?.let { put("x-cr-video-token", it) }
            },
        )
    }.getOrElse {
        Log.w(LOG_TAG, "offline: could not prepare download for $episodeId: ${it.message}")
        null
    }

    // ============================== Streams ===============================

    // Everything below that touches stream state runs under streamLock, so
    // overlapping getHosterList calls, the keep-alive ping and the release request
    // can never interleave: a ping cannot land after the release that follows it.
    private val streamLock = Any()

    private var lastStream: Pair<String, String>? = null

    // Release requests that have not been confirmed yet; retried on the next call.
    private val pendingReleases = mutableListOf<Pair<String, String>>()

    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var keepAliveJob: Job? = null

    private fun registerStream(id: String, streamToken: String, session: SessionDto?) = synchronized(streamLock) {
        // A stream registered by an overlapping call is released, not forgotten.
        lastStream?.let { queueRelease(it) }
        lastStream = id to streamToken
        startKeepAlive(id, streamToken, session)
        flushReleases()
    }

    private fun releasePreviousStream() = synchronized(streamLock) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        lastStream?.let { queueRelease(it) }
        lastStream = null
        flushReleases()
    }

    private fun queueRelease(stream: Pair<String, String>) {
        pendingReleases.add(stream)
        while (pendingReleases.size > MAX_PENDING_RELEASES) pendingReleases.removeAt(0)
    }

    // Caller holds streamLock. Whatever is not confirmed stays queued for next time.
    private fun flushReleases() {
        pendingReleases.removeAll { sendInactive(it) }
    }

    // True once the slot is known to be free: success, or the token already being gone.
    private fun sendInactive(stream: Pair<String, String>): Boolean {
        val (id, streamToken) = stream
        return runCatching {
            // Must be PATCH; a POST is silently ignored and leaks the slot until it
            // times out, after which play returns 420 TOO_MANY_ACTIVE_STREAMS.
            val request = Request.Builder()
                .url("$PLAY_HOST/v1/token/$id/$streamToken/inactive")
                .headers(headers)
                .patch(ByteArray(0).toRequestBody())
                .build()
            client.newCall(request).execute().use {
                it.isSuccessful || it.code == 404 || it.code == 410
            }
        }.getOrElse {
            Log.w(LOG_TAG, "release stream token $id failed: ${it.message}")
            false
        }
    }

    // A stream token expires ~renewSeconds after it is issued unless pinged, which
    // drops playback a few minutes in. Caller holds streamLock.
    private fun startKeepAlive(id: String, streamToken: String, session: SessionDto?) {
        keepAliveJob?.cancel()
        val renew = (session?.renewSeconds ?: 270).coerceAtLeast(60)
        val interval = (renew - 30).coerceAtLeast(30) * 1000L
        val deadline = System.currentTimeMillis() + SESSION_MAX_SECONDS * 1000L
        keepAliveJob = streamScope.launch {
            var failures = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(interval)
                val code = synchronized(streamLock) {
                    // Checked under the lock: once the stream is replaced or released,
                    // no further ping can be sent for it.
                    if (lastStream?.second != streamToken) return@launch
                    runCatching {
                        val request = Request.Builder()
                            .url("$PLAY_HOST/v1/token/$id/$streamToken/active")
                            .headers(headers)
                            .patch(ByteArray(0).toRequestBody())
                            .build()
                        client.newCall(request).execute().use { it.code }
                    }.getOrElse {
                        Log.w(LOG_TAG, "keep-alive $id failed: ${it.message}")
                        -1
                    }
                }
                // The token is gone; further pings are pointless.
                if (code == 404 || code == 410) return@launch
                if (code in 200..299) {
                    failures = 0
                } else if (++failures >= MAX_KEEPALIVE_FAILURES) {
                    // A session is dropped after ~370s without contact, so the slot is
                    // gone anyway - playback stopped, or the device went offline.
                    return@launch
                }
            }
        }
    }

    // ============================= Utilities ==============================

    private fun localeName(locale: String) = LOCALES[locale] ?: locale

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_BASIC,
            default = "",
            title = "Basic auth token",
            summary = "Required. From your browser: DevTools → Network → the auth/v1/token request → " +
                "Authorization header → the part after \"Basic \".",
            dialogMessage = "Paste only the base64 blob, without the leading \"Basic \".",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_ETP_RT,
            default = "",
            title = "etp_rt cookie",
            summary = "Used with a web Basic token. DevTools → Storage → Cookies → crunchyroll.com → etp_rt. " +
                "Re-authenticates on its own; only needs replacing if you log out in the browser.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_EMAIL,
            default = "",
            title = "Email (optional)",
            summary = "Only works with a mobile/TV Basic token. Leave blank to use the etp_rt cookie.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_PASSWORD,
            default = "",
            title = "Password (optional)",
            summary = "Stored on-device. Only used with a mobile/TV Basic token.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            restartRequired = true,
        )

        screen.addListPreference(
            key = PREF_AUDIO_TYPE,
            default = PREF_AUDIO_TYPE_DEFAULT,
            title = "Audio",
            summary = "%s",
            entries = listOf("Subbed (original audio)", "Dubbed"),
            entryValues = listOf(AUDIO_SUB, AUDIO_DUB),
        )

        screen.addListPreference(
            key = PREF_AUDIO,
            default = PREF_AUDIO_DEFAULT,
            title = "Dub language",
            summary = "Used when Audio is set to Dubbed. %s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )

        screen.addListPreference(
            key = PREF_SUB,
            default = PREF_SUB_DEFAULT,
            title = "Preferred subtitle language",
            summary = "%s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )

        screen.addListPreference(
            key = PREF_LOCALE,
            default = PREF_LOCALE_DEFAULT,
            title = "Metadata language",
            summary = "%s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )
    }

    companion object {
        private const val LOG_TAG = "Crunchyroll"

        /** Needs a mobile/TV client credential. */
        private const val GRANT_PASSWORD = "password"

        /** The only grant a web client credential accepts. */
        private const val GRANT_COOKIE = "etp_rt_cookie"

        private const val PAGE_SIZE = 36

        private const val PLAY_HOST = "https://cr-play-service.prd.crunchyrollsvc.com"

        // The service allows six hours, but with no playback-stopped hook a shorter
        // cap bounds how long a finished episode can hold a slot.
        private const val SESSION_MAX_SECONDS = 10800L

        /** Consecutive renewal failures before the session is presumed dead. */
        private const val MAX_KEEPALIVE_FAILURES = 2

        /** Unconfirmed release requests kept for retry. */
        private const val MAX_PENDING_RELEASES = 5

        // A web token only works on `web` paths; android/console answer "Outdated
        // Token". This profile also serves the full 1080p ladder.
        private const val PLAY_PROFILE = "web/firefox"

        // The only download profile that mints Widevine licenses; switch returns OMA.
        private const val DOWNLOAD_PROFILE = "android/phone"

        private const val SEASON_PREFIX = "season:"

        private const val DEVICE_NAME = "Firefox on Windows"
        private const val DEVICE_TYPE = "Firefox on Windows"

        private const val PREF_BASIC = "cr_basic_token"
        private const val PREF_ETP_RT = "cr_etp_rt"
        private const val PREF_EMAIL = "cr_email"
        private const val PREF_PASSWORD = "cr_password"
        private const val PREF_DEVICE_ID = "cr_device_id"

        // The key the app's shared sub/dub switch looks for.
        private const val PREF_AUDIO_TYPE = "is_sub_or_dub"

        // Must stay bare "sub"/"dub"; the app matches on that exactly.
        private const val AUDIO_SUB = "sub"
        private const val AUDIO_DUB = "dub"
        private const val PREF_AUDIO_TYPE_DEFAULT = AUDIO_SUB
        private const val ORIGINAL_LOCALE = "ja-JP"
        private const val FALLBACK_DUB_LOCALE = "en-US"

        private const val PREF_AUDIO = "cr_audio_locale"
        private const val PREF_AUDIO_DEFAULT = FALLBACK_DUB_LOCALE
        private const val PREF_SUB = "cr_subtitle_locale"
        private const val PREF_SUB_DEFAULT = "en-US"
        private const val PREF_LOCALE = "cr_content_locale"
        private const val PREF_LOCALE_DEFAULT = "en-US"

        private fun newDateFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val LOCALES = linkedMapOf(
            "ja-JP" to "Japanese",
            "en-US" to "English",
            "en-IN" to "English (India)",
            "es-419" to "Spanish (Latin America)",
            "es-ES" to "Spanish (Spain)",
            "pt-BR" to "Portuguese (Brazil)",
            "pt-PT" to "Portuguese (Portugal)",
            "fr-FR" to "French",
            "de-DE" to "German",
            "it-IT" to "Italian",
            "ru-RU" to "Russian",
            "ar-SA" to "Arabic",
            "hi-IN" to "Hindi",
            "ta-IN" to "Tamil",
            "te-IN" to "Telugu",
            "ms-MY" to "Malay",
            "id-ID" to "Indonesian",
            "th-TH" to "Thai",
            "vi-VN" to "Vietnamese",
            "ko-KR" to "Korean",
            "zh-CN" to "Chinese (Mainland)",
            "zh-HK" to "Chinese (Hong Kong)",
            "zh-TW" to "Chinese (Taiwan)",
            "pl-PL" to "Polish",
            "ca-ES" to "Catalan",
        )
    }
}

// Serialised into Video.internalData; ignored by players without DRM support.
@Serializable
class DrmPayload(
    val drmScheme: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val offline: OfflinePayload? = null,
)

@Serializable
class OfflinePayload(
    val manifestUrl: String,
    // Sent with manifest and segment requests; the manifest 401s without them.
    val headers: Map<String, String>,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
)
