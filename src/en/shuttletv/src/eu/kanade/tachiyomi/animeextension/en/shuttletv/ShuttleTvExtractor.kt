package eu.kanade.tachiyomi.animeextension.en.shuttletv

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.bodyString
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import keiyoushi.network.post as networkPost

/**
 * Resolves streams from CineSrc (the player behind ShuttleTV) by delegating
 * the whole challenge flow to the Enc-Dec API (enc-dec.app), which solves
 * the stage-1/stage-2 challenges server-side and rotates the Next.js
 * server-action ids on every response.
 *
 * Flow (mirrors EncDecEndpoints/samples/cinesrc.py):
 *  1. POST /api/c/bootstrap with x-cs-q          -> {r, p}
 *  2. GET  /api/c/issue                          -> stage-1 challenge
 *  3. POST enc-dec.app/api/enc-cinesrc (seed)    -> {seed, state}
 *  4. GET  /api/c/stage2/issue                   -> stage-2 challenge
 *  5. POST enc-dec.app/api/enc-cinesrc (encrypt) -> {token, headers, state}
 *  6. POST /embed/<..> Next-Action getProviderList -> servers (hosters)
 *  7. POST /embed/<..> Next-Action getStream     -> encrypted payload
 *  8. POST enc-dec.app/api/dec-cinesrc           -> playlist sources + captions
 *
 * Steps 1-6 run once per item and are cached as a [Session]; step 7-8 run
 * per hoster the user picks.
 */
class ShuttleTvExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private data class Challenge(
        val embedUrl: String,
        val token: String,
        val actionIds: ActionIdDto,
        val state: JsonElement,
    )

    private data class PathInfo(
        val embedUrl: String,
        val isMovie: Boolean,
        val tmdbId: String,
        val season: String?,
        val episode: String?,
    )

    private val playlistUtils by lazy { PlaylistUtils(client, baseHeaders()) }

    // The challenge token is bound to the User-Agent, so the exact same UA
    // must be sent on EVERY hop — cinesrc requests and enc-dec calls alike.
    private val userAgent: String = CHALLENGE_USER_AGENT

    // Cookie-free client: the proven flow (EncDecEndpoints sample) runs with
    // no cookie jar at all, while the app's persistent jar carries stale
    // cs_ac/cs_pb/DDoS-Guard cookies that can break challenge validation.
    private val challengeClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private fun baseHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", CHALLENGE_USER_AGENT)
        .set("Origin", CINESRC_ORIGIN)
        .set("Referer", "$CINESRC_ORIGIN/")
        .build()

    private fun encDecHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", CHALLENGE_USER_AGENT)
        .set("Referer", "https://enc-dec.app/")
        .build()

    private fun parsePath(path: String): PathInfo {
        val parts = path.trim('/').split('/')
        val isMovie = parts.first() == "movie"
        val tmdbId = parts.getOrNull(1) ?: throw IllegalArgumentException("Invalid path: $path")
        val season = if (!isMovie) parts.getOrNull(2)?.takeIf(String::isNotBlank) else null
        val episode = if (!isMovie) parts.getOrNull(3)?.takeIf(String::isNotBlank) else null

        val embedUrl = if (isMovie) {
            "$CINESRC_ORIGIN/embed/movie/$tmdbId"
        } else {
            "$CINESRC_ORIGIN/embed/tv/$tmdbId?s=$season&e=$episode"
        }

        return PathInfo(embedUrl, isMovie, tmdbId, season, episode)
    }

    /**
     * Servers available for the item, in the player's own order. Fetched
     * fresh on every call: the challenge flow runs end-to-end each time.
     */
    suspend fun getProviders(path: String): List<ProviderDto> {
        val info = parsePath(path)
        val challenge = prepareChallenge(info)

        // Server-action ids rotate per deploy; enc-dec delivers fresh ones.
        val providersBody = challengeClient.post(
            info.embedUrl,
            baseHeaders().newBuilder()
                .set("Next-Action", challenge.actionIds.getProviderList)
                .build(),
            EMPTY_LIST_BODY,
        ).bodyString()

        val providers = providersBody.extractNextJsRsc<List<ProviderDto>>()
        return when {
            providers == null -> {
                Log.w(TAG, "provider list unparseable, using fallback; body[0..140]=${providersBody.take(140)}")
                FALLBACK_PROVIDERS
            }
            else -> providers
        }
    }

    suspend fun videosFor(path: String, providerId: String): List<Video> {
        val info = parsePath(path)
        val serverName = serverName(providerId)

        val challenge = prepareChallenge(info)

        val payload = buildJsonArray {
            add(info.tmdbId)
            add(if (info.isMovie) "movie" else "show")
            add(info.season ?: UNDEFINED_ARG)
            add(info.episode ?: UNDEFINED_ARG)
            add(challenge.token)
            add(providerId)
        }.toString()

        val response = challengeClient.networkPost(
            info.embedUrl,
            baseHeaders().newBuilder()
                .set("Next-Action", challenge.actionIds.getStream)
                .build(),
            payload.toRequestBody(TEXT_PLAIN),
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val body = response.bodyString()
        if (INVALID_CHALLENGE_MARKER in body) {
            throw IllegalStateException("cinesrc rejected the challenge token")
        }
        val state = challenge.state

        val decrypted = challengeClient.post(
            "$ENC_DEC_API/dec-cinesrc",
            encDecHeaders(),
            body = DecRequestDto(text = body, state = state).toJsonRequestBody(),
        ).parseAs<EncDecResponseDto<DecryptedStreamDto>>().result

        return buildVideos(serverName, decrypted)
    }

    private fun serverName(providerId: String): String = SERVER_CATALOG.firstOrNull { it.id == providerId }
        ?.let { cleanName(it) }
        ?: providerId.replaceFirstChar { it.uppercase() }

    // ============================== Challenge =============================
    private suspend fun prepareChallenge(info: PathInfo): Challenge {
        // x-cs-q: base64url(JSON [type, id, season, episode]) — nulls for movies.
        val challengeId = buildJsonArray {
            add(if (info.isMovie) "movie" else "tv")
            add(info.tmdbId)
            add(info.season)
            add(info.episode)
        }.toString()
        val q = kotlin.io.encoding.Base64.UrlSafe
            .encode(challengeId.toByteArray(Charsets.UTF_8))
            .trimEnd('=')

        val bootstrap = challengeClient.post(
            BOOTSTRAP_URL,
            baseHeaders().newBuilder().set("x-cs-q", q).build(),
            EMPTY_BODY,
        ).parseAs<BootstrapDto>()

        val challengeHeaders = baseHeaders().newBuilder()
            .set("x-cs-b", CHALLENGE_BUILD)
            .set("x-cs-q", q)
            .set("x-cs-r", bootstrap.r)
            .set("x-cs-p", bootstrap.p)
            .build()

        val stage1 = challengeClient.get(ISSUE_URL, challengeHeaders)
            .parseAs<Stage1ChallengeDto>()
        val seed = challengeClient.post(
            "$ENC_DEC_API/enc-cinesrc",
            encDecHeaders(),
            body = SeedRequestDto(
                data = SeedDataDto(
                    url = info.embedUrl,
                    userAgent = userAgent,
                    challengeData = stage1,
                ),
                type = "seed",
            ).toJsonRequestBody(),
        ).parseAs<EncDecResponseDto<SeedResultDto>>().result
        val stage2 = challengeClient.get(STAGE2_ISSUE_URL, challengeHeaders)
            .parseAs<Stage2ChallengeDto>()

        // Solve the challenges natively (CSP4 PoW + stage-2 hash); the
        // solutions must accompany the challenges in the encrypt step or
        // cinesrc rejects the resulting token.
        val stage1Solution = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            CinesrcChallenge.solveStage1(stage1, seed.seed) { context.ensureActive() }
        }
        val stage2Solution = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            CinesrcChallenge.solveStage2(stage2) { context.ensureActive() }
        }

        val encrypted = challengeClient.post(
            "$ENC_DEC_API/enc-cinesrc",
            encDecHeaders(),
            body = EncryptRequestDto(
                data = EncryptDataDto(
                    url = info.embedUrl,
                    userAgent = userAgent,
                    challengeData = StageSolutionsDto(
                        stage1 = Stage1SolutionDto(challenge = stage1, solution = stage1Solution),
                        stage2 = Stage2SolutionDto(challenge = stage2, solution = stage2Solution),
                    ),
                    state = seed.state,
                ),
                type = "encrypt",
            ).toJsonRequestBody(),
        ).parseAs<EncDecResponseDto<EncryptResultDto>>().result

        return Challenge(
            embedUrl = info.embedUrl,
            token = "${encrypted.token}::c3::${bootstrap.r}",
            actionIds = encrypted.headers,
            state = encrypted.state,
        )
    }

    // =============================== Streams ==============================
    private suspend fun buildVideos(serverName: String, result: DecryptedStreamDto): List<Video> {
        if (result.error != null) {
            Log.i(TAG, "$serverName returned no streams: ${result.error}")
            return emptyList()
        }

        val subtitles = result.captions.mapNotNull { caption ->
            caption.url.takeIf(String::isNotBlank)?.let {
                Track(it, caption.display ?: caption.language ?: "und")
            }
        }

        return result.url.orEmpty()
            .filter { !it.url.isNullOrBlank() }
            .flatMap { source ->
                // The decrypt returns relative playlist paths
                // (/api/playlist/<token>.m3u8) — absolutize against cinesrc.
                val playlistUrl = source.url!!.let {
                    if (it.startsWith("/")) CINESRC_ORIGIN + it else it
                }
                val quality = source.height?.let { "${it}p" }
                    ?: source.label?.takeIf(String::isNotBlank)
                    ?: "Auto"

                fun fallbackVideo(): Video = Video(
                    videoUrl = playlistUrl,
                    videoTitle = buildLabel(serverName, quality, subtitles.size),
                    headers = baseHeaders(),
                    subtitleTracks = subtitles,
                )

                // The playlist proxy intermittently answers 400; probe with
                // retries before deciding the link is dead.
                var masterText: String? = null
                repeat(MASTER_FETCH_ATTEMPTS) {
                    if (masterText != null) return@repeat
                    masterText = runCatching {
                        challengeClient.get(playlistUrl, baseHeaders()).bodyString()
                    }.getOrNull()?.takeIf { it.startsWith("#EXTM3U") }
                }
                val playlist = masterText ?: return@flatMap listOf(fallbackVideo())

                if ("#EXT-X-STREAM-INF" !in playlist) {
                    // Plain media playlist — no variants to expand.
                    return@flatMap listOf(fallbackVideo())
                }

                // Master playlist — expand into per-quality videos. Retry when
                // PlaylistUtils could only produce its own fallback (its
                // internal fetch hit the flaky 400).
                var expanded = emptyList<Video>()
                repeat(MASTER_FETCH_ATTEMPTS) {
                    if (expanded.any { it.videoUrl != playlistUrl }) return@repeat
                    expanded = runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = playlistUrl,
                            videoNameGen = { variant ->
                                val effective = if (variant.isNotBlank() && variant != "Video") variant else quality
                                buildLabel(serverName, effective, subtitles.size)
                            },
                            subtitleList = subtitles,
                            masterHeaders = baseHeaders(),
                            videoHeaders = baseHeaders(),
                        )
                    }.getOrDefault(emptyList())
                }

                expanded.ifEmpty { listOf(fallbackVideo()) }
            }
            .distinctBy { it.videoUrl }
    }

    private fun buildLabel(serverName: String, quality: String, subCount: Int): String = buildString {
        append(serverName)
        if (quality.isNotBlank()) append(" · ").append(quality)
        if (subCount > 0) append(" · ").append(subCount).append(" subs")
    }

    companion object {
        private const val TAG = "ShuttleTvExtractor"
        private const val ENC_DEC_API = "https://enc-dec.app/api"
        private const val CINESRC_ORIGIN = "https://cinesrc.st"
        private const val BOOTSTRAP_URL = "$CINESRC_ORIGIN/api/c/bootstrap"
        private const val ISSUE_URL = "$CINESRC_ORIGIN/api/c/issue"
        private const val STAGE2_ISSUE_URL = "$CINESRC_ORIGIN/api/c/stage2/issue"

        // Bumped with cinesrc's PoW scripts (pow-worker-<build>.js); when the
        // site rotates its worker, update this to the new suffix.
        private const val CHALLENGE_BUILD = "20261014-a"

        // User agent that enc-dec uses in the sample Python script
        private const val CHALLENGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.37"

        // React Flight marker the client sends in place of missing args.
        private const val UNDEFINED_ARG = "\$undefined"

        private const val MASTER_FETCH_ATTEMPTS = 3
        private const val INVALID_CHALLENGE_MARKER = "e1:invalid_challenge"

        private val TEXT_PLAIN = "text/plain;charset=UTF-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val EMPTY_LIST_BODY = "[]".toRequestBody(TEXT_PLAIN)

        /**
         * Last-known server catalog, used when getProviderList can't be
         * resolved and as the preference-screen entries. Mirrors the
         * player's list (without display decorations).
         */
        val SERVER_CATALOG = listOf(
            ProviderDto(id = "nebula", name = "Nebula"),
            ProviderDto(id = "light", name = "Light"),
            ProviderDto(id = "wave", name = "Wave"),
            ProviderDto(id = "orbit", name = "Orbit"),
            ProviderDto(id = "storm", name = "Storm"),
            ProviderDto(id = "aurora", name = "Aurora"),
            ProviderDto(id = "flux", name = "Flux"),
            ProviderDto(id = "river", name = "River"),
            ProviderDto(id = "surge", name = "Surge"),
            ProviderDto(id = "mist", name = "Mist"),
            ProviderDto(id = "tempest", name = "Tempest"),
            ProviderDto(id = "thunder", name = "Thunder"),
            ProviderDto(id = "paris", name = "Paris (FR&ENG)"),
            ProviderDto(id = "brisa", name = "Brisa (ES/LAT)"),
        )

        private val FALLBACK_PROVIDERS = SERVER_CATALOG

        /**
         * Strips player decorations ("⭐️ Nebula" -> "Nebula").
         */
        fun cleanName(provider: ProviderDto): String = (provider.name ?: provider.id)
            .replace(Regex("""^[^\p{L}\p{N}]+"""), "")
            .trim()
            .ifBlank { provider.id }
    }
}
