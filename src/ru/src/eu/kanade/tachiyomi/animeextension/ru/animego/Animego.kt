package eu.kanade.tachiyomi.animeextension.ru.animego

import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class Animego :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeGO"

    override val baseUrl = "https://animego.online"

    override val lang = "ru"

    override val supportsLatest = true

    override val client: OkHttpClient =
        super.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(::playbackResolverInterceptor)
            .build()

    private val preferences by getPreferencesLazy()

    override fun getFilterList(): AnimeFilterList = Filters.FILTERS

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = "Предпочитаемый плеер"
            entries = PREF_PLAYER_ENTRIES
            entryValues = PREF_PLAYER_VALUES
            summary = "%s"
            setDefaultValue(PREF_PLAYER_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CDN_DUBBING_KEY
            title = "Предпочитаемая озвучка CDN"
            summary = "Точное или частичное совпадение названия студии"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_KODIK_DUBBING_KEY
            title = "Предпочитаемая озвучка Kodik"
            summary = "Точное или частичное совпадение названия студии"
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CDN_QUALITY_KEY
            title = "Предпочитаемое качество CDN"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            summary = "%s"
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_KODIK_QUALITY_KEY
            title = "Предпочитаемое качество Kodik"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            summary = "%s"
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)
    }

    override fun popularAnimeRequest(page: Int): Request = GET(buildPagedUrl("/top100/", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = animeListParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(buildLatestUpdatesUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = animeListParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val searchHeaders = headers.newBuilder()
                .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .build()
            val resultFrom = (page - 1) * SEARCH_PAGE_SIZE + 1
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("search_start", page.toString())
                .add("full_search", "0")
                .add("result_from", resultFrom.toString())
                .add("story", query)
                .build()
            return POST("$baseUrl/index.php?do=search", searchHeaders, body)
        }

        val filterPath = Filters.buildPath(filters)
        if (filterPath == null) {
            return latestUpdatesRequest(page)
        }

        return GET(buildPagedUrl(filterPath, page), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = animeListParse(response)

    override fun animeDetailsParse(response: Response): SAnime = response.asJsoup().toSAnime()

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used.")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().use { buildEpisodeList(it) }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    private suspend fun buildEpisodeList(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val playerState = parsePlayerState(document)
        val availability = linkedMapOf<EpisodeKey, EpisodeAvailability>()
        var kodikSeasonHint: Set<Int> = emptySet()
        var allohaSeasonHint: Set<Int> = emptySet()

        playerState.cdn?.let { cdn ->
            runLoggedSuspend("CDN playlist for ${response.request.url}") {
                fetchCdnPlaylist(cdn, response.request.url.toString())
            }?.items?.forEach { item ->
                val key = EpisodeKey(item.season, item.episode)
                val entry = availability.getOrPut(key) { EpisodeAvailability(key) }
                entry.cdnDubbings += item.displayDubbing()
            }
        }

        playerState.kodik?.let { kodik ->
            runLoggedSuspend("Kodik state for ${response.request.url}") {
                fetchKodikState(kodik, response.request.url.toString())
            }?.let { state ->
                kodikSeasonHint = state.episodesBySeason.keys
                state.translations.forEach { translation ->
                    state.episodesBySeason.forEach { (season, episodes) ->
                        episodes
                            .filter { translation.supportsEpisode(it.number) }
                            .forEach { episode ->
                                val key = EpisodeKey(season, episode.number)
                                val entry = availability.getOrPut(key) { EpisodeAvailability(key) }
                                entry.kodikDubbings += translation.title
                            }
                    }
                }
            }
        }

        playerState.alloha?.let { alloha ->
            runLoggedSuspend("Alloha state for ${response.request.url}") {
                fetchAllohaState(
                    config = alloha,
                    referer = response.request.url.toString(),
                    requestedEpisodeContext = null,
                )
            }?.let { state ->
                allohaSeasonHint = state.visibleSeasonIds
                state.tracksByEpisode.forEach { (key, tracks) ->
                    if (tracks.isEmpty()) return@forEach
                    val entry = availability.getOrPut(key) { EpisodeAvailability(key) }
                    tracks.mapTo(entry.allohaDubbings) { it.translationLabel }
                }
            }
        }

        val canonicalSeason = inferCanonicalSeason(
            pageUrl = response.request.url.toString(),
            pageTitle = document.selectFirst("h1")?.text().orEmpty(),
            kodikSeasons = kodikSeasonHint,
            allohaSeasons = allohaSeasonHint,
        )
        val filteredAvailability = if (
            canonicalSeason != null &&
            availability.keys.any { it.season == canonicalSeason }
        ) {
            val filtered = availability.filterKeys { it.season == canonicalSeason }
            if (filtered.size != availability.size) {
                Log.d(
                    name,
                    "AnimeGO: episode list filtered to canonical season $canonicalSeason " +
                        "(${filtered.size}/${availability.size})",
                )
            }
            filtered
        } else {
            availability
        }

        return filteredAvailability.values
            .sortedWith(compareByDescending<EpisodeAvailability> { it.key.season }.thenByDescending { it.key.episode })
            .map { it.toSEpisode(response.request.url.toString()) }
    }

    override fun videoUrlRequest(video: Video): Request {
        val headers = video.headers ?: Headers.headersOf()
        if (headers[HEADER_RESOLVER] != RESOLVER_ALLOHA) {
            return GET(video.url, headers)
        }

        val playbackReferer = headers["Referer"].orEmpty()
        val resolverKind = classifyAllohaResolverKind(playbackReferer)
        if (resolverKind != AllohaResolverKind.BNSI) {
            return GET(video.url, headers)
        }

        val videoId = video.url.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            .orEmpty()
        return buildAllohaResolverRequest(
            videoId = videoId,
            playbackReferer = playbackReferer,
            headers = headers,
        ) ?: GET(video.url, headers)
    }

    override fun videoUrlParse(response: Response): String = when (response.request.header(HEADER_RESOLVER)) {
        RESOLVER_KODIK -> resolveKodikVideoUrl(response)
        RESOLVER_CDN -> resolveCdnVideoUrl(response)
        RESOLVER_ALLOHA -> resolveAllohaVideoUrl(response)
        else -> response.request.url.toString()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().use { buildVideoList(it) }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException("Not used.")

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("Not used.")

    private suspend fun buildVideoList(response: Response): List<Video> {
        val episodeContext = extractEpisodeContext(response)
            ?: return emptyList()
        val season = episodeContext.first
        val episode = episodeContext.second
        val document = response.asJsoup()
        val playerState = parsePlayerState(document)
        val videos = mutableListOf<VideoCandidate>()

        playerState.cdn?.let { cdn ->
            val playlist = runLoggedSuspend("CDN playlist for ${response.request.url}") {
                fetchCdnPlaylist(cdn, response.request.url.toString())
            } ?: return@let
            playlist.items
                .filter { it.season == season && it.episode == episode }
                .sortedBy { it.displayDubbing() }
                .forEach { item ->
                    videos += buildLazyCdnVideos(item = item)
                }
            Log.d(name, "AnimeGO: CDN yielded ${videos.count { it.metadata.playerId == PLAYER_CDN }} videos for s$season e$episode")
        }

        playerState.kodik?.let { kodik ->
            runLoggedSuspend("Kodik state for ${response.request.url}") {
                fetchKodikState(kodik, response.request.url.toString())
            }?.let { state ->
                state.translations
                    .filter { it.supportsEpisode(episode) }
                    .forEach { translation ->
                        videos += buildLazyKodikVideos(
                            translation = translation,
                            season = season,
                            episode = episode,
                        )
                    }
            }
            Log.d(name, "AnimeGO: Kodik yielded ${videos.count { it.metadata.playerId == PLAYER_KODIK }} videos for s$season e$episode")
        }

        playerState.alloha?.let { alloha ->
            runLoggedSuspend("Alloha state for ${response.request.url}") {
                fetchAllohaState(
                    config = alloha,
                    referer = response.request.url.toString(),
                    requestedEpisodeContext = episodeContext,
                )
            }?.let { state ->
                val episodeTracks = state.tracksByEpisode[EpisodeKey(season, episode)].orEmpty()
                if (episodeTracks.isEmpty()) {
                    Log.d(name, "AnimeGO: Alloha missing episode s$season e$episode; buckets=${state.tracksByEpisode.size}")
                }
                episodeTracks.forEach { track ->
                    videos += buildLazyAllohaVideos(
                        track = track,
                        playbackReferer = state.refererUrl,
                        pageReferer = state.pageReferer,
                        bnsiMovieId = state.bnsiMovieId,
                        borthSeed = state.borthSeed,
                    )
                }
            }
            Log.d(name, "AnimeGO: Alloha yielded ${videos.count { it.metadata.playerId == PLAYER_ALLOHA }} videos for s$season e$episode")
        }

        Log.d(name, "AnimeGO: total videos before finalize for ${response.request.url} = ${videos.size}")
        return finalizeVideoCandidates(videos)
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    private fun animeListParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("a.poster-item.grid-item")
            .mapNotNull { it.toSAnimeOrNull() }
            .distinctBy { it.url }

        val currentPage = response.request.url.pathSegments
            .windowed(2)
            .firstOrNull { it[0] == "page" }
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1

        val hasNextPage = document.select("a[href*=\"/page/${currentPage + 1}/\"]").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    private fun Element.toSAnimeOrNull(): SAnime? {
        val href = selectFirst("a")?.attr("abs:href").orEmpty().ifBlank { attr("abs:href") }
        if (href.isBlank()) return null

        val anime = SAnime.create()
        anime.setUrlWithoutDomain(href)
        anime.title = selectFirst(".animego-online-poster-item__title")?.text().orEmpty().ifBlank {
            selectFirst("img")?.attr("alt").orEmpty().removePrefix("Постер к аниме ")
        }
        anime.thumbnail_url = selectFirst("img")?.attr("abs:src")
            ?.ifBlank { selectFirst("img")?.attr("abs:data-src").orEmpty() }
        return anime
    }

    private fun Document.toSAnime(): SAnime {
        val anime = SAnime.create()
        anime.title = selectFirst("h1")?.text().orEmpty()
        anime.thumbnail_url = selectFirst(".animego-online-page__poster img")?.attr("abs:src")
            ?.ifBlank { selectFirst(".animego-online-page__poster img")?.attr("abs:data-src").orEmpty() }
        anime.genre = select(".animego-online-page__details-list--genre a")
            .map(Element::text)
            .filter(String::isNotBlank)
            .joinToString()

        val details = select(".animego-online-page__details-list li").associate {
            val spans = it.select("span")
            spans.firstOrNull()?.text().orEmpty().trim(':') to spans.getOrNull(1)?.text().orEmpty()
        }

        anime.author = details["Студия"].orEmpty()
        details["Статус"]?.takeIf(String::isNotBlank)?.let { anime.status = parseAnimeStatus(it) }
        anime.description = buildList {
            details["Сезон"]?.takeIf(String::isNotBlank)?.let { add("Сезон: $it") }
            details["Длительность"]?.takeIf(String::isNotBlank)?.let { add("Длительность: $it") }
            details["Режиссер"]?.takeIf(String::isNotBlank)?.let { add("Режиссер: $it") }
            details["Студия"]?.takeIf(String::isNotBlank)?.let { add("Студия: $it") }
            selectFirst(".page__text")?.text()?.takeIf(String::isNotBlank)?.let { add(it) }
        }.joinToString("\n\n")
        anime.initialized = true
        return anime
    }

    private fun parsePlayerState(document: Document): PagePlayerState {
        val html = document.html()
        val cdn = document.selectFirst(".cvh")?.let {
            val mali = it.attr("data-mali")
            val pub = it.attr("data-pub")
            if (mali.isBlank() || pub.isBlank()) null else CdnConfig(mali = mali, pub = pub)
        }

        val xfPlayerParams = document.select(".xfplayer[data-params]")
            .mapNotNull { item ->
                item.attr("data-params")
                    .replace("&amp;", "&")
                    .takeIf(String::isNotBlank)
            }
        val kodik = xfPlayerParams
            .firstOrNull { params -> params.containsToken("kodik-player") }
            ?.let(::KodikConfig)

        val alloha = selectAllohaPlayerParams(xfPlayerParams)?.let(::AllohaConfig)

        Log.d(
            name,
            buildString {
                append("AnimeGO: parsePlayerState url=")
                append(document.location().ifBlank { "unknown" })
                append(" title=")
                append(document.title().take(80))
                append(" cvh=")
                append(cdn != null)
                append('/')
                append(document.select(".cvh").size)
                append(" kodik=")
                append(kodik != null)
                append('/')
                append(document.select(".xfplayer[data-params*=kodik-player]").size)
                append(" alloha=")
                append(alloha != null)
                append(" inputData=")
                append(document.selectFirst("#inputData") != null)
                append(" fileList=")
                append(html.contains("fileList"))
                append(" movie=")
                append(html.contains("const movie") || html.contains("movie ="))
                append(" userParam=")
                append(html.contains("userParam"))
            },
        )

        return PagePlayerState(cdn = cdn, kodik = kodik, alloha = alloha)
    }

    private suspend fun fetchCdnPlaylist(config: CdnConfig, referer: String): CdnPlaylistResponse {
        val url = CDN_PLAYLIST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("pub", config.pub)
            .addQueryParameter("id", config.mali)
            .addQueryParameter("aggr", "mali")
            .build()
        val request = GET(url, refererHeaders(referer))
        return client.newCall(request).awaitSuccess().use { it.parseAs<CdnPlaylistResponse>() }
    }

    private fun buildLazyCdnVideos(item: CdnPlaylistItem): List<VideoCandidate> {
        val metadata = PlaybackMetadata(
            playerId = PLAYER_CDN,
            playerLabel = PLAYER_CDN_LABEL,
            dubbingId = normalizeId(item.displayDubbing()),
            dubbingLabel = item.displayDubbing(),
            sortOrder = CDN_SORT_ORDER,
        )
        return COMMON_CDN_QUALITIES.map { quality ->
            VideoCandidate(
                video = buildVideo(
                    url = "$CDN_VIDEO_URL/${item.vkId}",
                    quality = quality,
                    metadata = metadata,
                    videoUrl = "null",
                    headers = buildCdnPlaybackHeaders()
                        .newBuilder()
                        .add(HEADER_RESOLVER, RESOLVER_CDN)
                        .add(HEADER_PREFERRED_QUALITY, quality)
                        .build(),
                ),
                metadata = metadata,
                quality = quality,
            )
        }
    }

    private suspend fun fetchKodikState(config: KodikConfig, referer: String): KodikState? {
        val request = GET(
            "$baseUrl/engine/ajax/controller.php?${config.params}",
            ajaxHeaders(referer),
        )
        val iframe = client.newCall(request).awaitSuccess().use { it.parseAs<KodikIframeResponse>() }
        if (!iframe.success || iframe.data.isBlank()) {
            Log.d(
                name,
                "AnimeGO: Kodik iframe missing for $referer success=${iframe.success} dataLength=${iframe.data.length}",
            )
            return null
        }

        val playerUrl = normalizeEmbeddedPlayerUrl(iframe.data)
        val pageRequest = GET(playerUrl, refererHeaders("$baseUrl/"))
        val page = client.newCall(pageRequest).awaitSuccess().use { it.asJsoup() }
        val state = parseKodikState(page)
        if (state == null) {
            Log.d(
                name,
                "AnimeGO: Kodik state missing for $referer title=${page.title().take(80)} urlParamsPresent=${AnimegoPatterns.KODIK_URL_PARAMS.containsMatchIn(page.html())}",
            )
        } else {
            Log.d(
                name,
                "AnimeGO: Kodik parsed seasons=${state.seasons.size} episodeBuckets=${state.episodesBySeason.size} translations=${state.translations.size} for $referer",
            )
        }
        return state
    }

    private suspend fun fetchAllohaState(
        config: AllohaConfig,
        referer: String,
        requestedEpisodeContext: Pair<Int, Int>?,
    ): AllohaState? {
        val paramsCandidates = buildAllohaControllerParamsCandidates(config.params)
        val effectiveEpisodeContext = requestedEpisodeContext ?: extractEpisodeContext(listOf(referer))
        Log.d(name, "AnimeGO: Alloha controller candidates=${paramsCandidates.joinToString(" || ")}")

        paramsCandidates.forEachIndexed { index, params ->
            val candidateKind = classifyAllohaControllerParams(params)
            val request = GET(
                "$baseUrl/engine/ajax/controller.php?$params",
                ajaxHeaders(referer),
            )
            val iframe = client.newCall(request).awaitSuccess().use { it.parseAs<KodikIframeResponse>() }
            if (!iframe.success || iframe.data.isBlank()) {
                Log.d(
                    name,
                    "AnimeGO: Alloha controller candidate ${index + 1}/${paramsCandidates.size} returned empty " +
                        "success=${iframe.success} kind=$candidateKind params=$params",
                )
                return@forEachIndexed
            }

            val playerUrl = normalizeEmbeddedPlayerUrl(iframe.data)
            val contextualPlayerUrl = effectiveEpisodeContext?.let { (season, episode) ->
                buildAllohaEpisodeReferer(
                    baseReferer = playerUrl,
                    season = season,
                    episode = episode,
                    translationId = 0,
                )
            } ?: playerUrl
            val playerHost = runCatching { contextualPlayerUrl.toHttpUrl().host }.getOrNull().orEmpty()
            Log.d(
                name,
                "AnimeGO: Alloha controller candidate ${index + 1}/${paramsCandidates.size} resolved host=$playerHost " +
                    "kind=$candidateKind url=${contextualPlayerUrl.take(180)}",
            )

            val pageRequest = GET(contextualPlayerUrl, refererHeaders(referer))
            val page = client.newCall(pageRequest).awaitSuccess().use { it.asJsoup() }
            val pageHtml = page.html()
            val bnsiMovieId = extractAllohaMovieId(pageHtml).orEmpty()
            Log.d(
                name,
                "AnimeGO: Alloha bnsiMovieId=$bnsiMovieId for ${contextualPlayerUrl.take(120)}",
            )

            var state = parseAllohaState(
                document = page,
                refererUrl = contextualPlayerUrl,
                pageReferer = referer,
            )?.let { parsed ->
                AllohaState(
                    refererUrl = parsed.refererUrl,
                    pageReferer = parsed.pageReferer,
                    tracksByEpisode = parsed.tracksByEpisode,
                    visibleSeasonIds = parsed.visibleSeasonIds,
                    bnsiMovieId = bnsiMovieId,
                    borthSeed = parsed.borthSeed,
                )
            }

            // Fallback: новый Alloha-плеер (SPA) не содержит треков в HTML.
            // Извлекаем movie ID из страницы и используем POST-резолвер.
            if (state == null && bnsiMovieId.isNotBlank() && effectiveEpisodeContext != null) {
                val (season, episode) = effectiveEpisodeContext
                Log.d(
                    name,
                    "AnimeGO: Alloha legacy parse empty, trying POST fallback " +
                        "bnsiMovieId=$bnsiMovieId s${season}e$episode url=${contextualPlayerUrl.take(120)}",
                )
                state = AllohaState(
                    refererUrl = contextualPlayerUrl,
                    pageReferer = referer,
                    tracksByEpisode = mapOf(
                        EpisodeKey(season, episode) to listOf(
                            AllohaTrack(
                                videoId = bnsiMovieId,
                                season = season,
                                episode = episode,
                                translationId = 0,
                                translationLabel = "Alloha",
                            ),
                        ),
                    ),
                    visibleSeasonIds = emptySet(),
                    bnsiMovieId = bnsiMovieId,
                    borthSeed = extractAllohaBorthSeed(pageHtml).orEmpty(),
                )
            } else if (state == null) {
                Log.d(
                    name,
                    "AnimeGO: Alloha POST fallback unavailable " +
                        "bnsiMovieId=$bnsiMovieId episodeContext=$effectiveEpisodeContext " +
                        "title=${page.title().take(80)}",
                )
            }

            if (state != null) {
                val trackCount = state.tracksByEpisode.values.sumOf { it.size }
                Log.d(
                    name,
                    "AnimeGO: Alloha candidate ${index + 1}/${paramsCandidates.size} parsed " +
                        "${state.tracksByEpisode.size} episode buckets and $trackCount tracks",
                )
                return state
            }

            Log.d(
                name,
                "AnimeGO: Alloha candidate ${index + 1}/${paramsCandidates.size} produced empty state " +
                    "title=${page.title().take(80)}",
            )
        }

        return null
    }

    private fun parseAllohaState(
        document: Document,
        refererUrl: String,
        pageReferer: String,
    ): AllohaState? {
        val html = document.html()
        val parsedTracks = extractAllohaTracksFromInputData(document)
            .ifEmpty { extractParlorateTracksFromInputData(document) }
            .ifEmpty {
                extractAllohaFileListPayload(html)?.let(::parseAllohaTracks).orEmpty()
            }
        if (parsedTracks.isEmpty()) {
            val selectors = document.select("[data-select]").eachAttr("data-select").joinToString()
            Log.d(
                name,
                "AnimeGO: Alloha inputData/fileList missing for $refererUrl title=${document.title().take(80)} " +
                    "selectors=$selectors scripts=${document.select("script").size} " +
                    "inputData=${document.selectFirst("#inputData") != null} " +
                    "fileList=${html.contains("fileList")} movie=${html.contains("const movie") || html.contains("movie =")} " +
                    "userParam=${html.contains("userParam")}",
            )
            return null
        }

        val visibleSeasonIds = extractAllohaVisibleSeasonIds(document)
        val seasonFilteredTracks = filterAllohaTracksByVisibleSeasons(parsedTracks, visibleSeasonIds)
        if (visibleSeasonIds.isNotEmpty() && seasonFilteredTracks.size != parsedTracks.size) {
            Log.d(
                name,
                "AnimeGO: Alloha filtered legacy tracks by visible seasons " +
                    "(${seasonFilteredTracks.size}/${parsedTracks.size}) seasons=${visibleSeasonIds.joinToString()}",
            )
        }

        val visibleTranslationIds = extractAllohaVisibleTranslationIds(document)
        val translationFilteredTracks = filterAllohaTracksByVisibleTranslations(seasonFilteredTracks, visibleTranslationIds)
        val effectiveTracks = if (translationFilteredTracks.isNotEmpty()) {
            if (translationFilteredTracks.size != seasonFilteredTracks.size) {
                Log.d(
                    name,
                    "AnimeGO: Alloha filtered legacy tracks by visible translations " +
                        "(${translationFilteredTracks.size}/${seasonFilteredTracks.size})",
                )
            }
            translationFilteredTracks
        } else {
            if (visibleTranslationIds.isNotEmpty()) {
                Log.d(
                    name,
                    "AnimeGO: Alloha visible translation filter produced empty set; fallback to legacy tracks",
                )
            }
            seasonFilteredTracks
        }
        val tracksByEpisode = linkedMapOf<EpisodeKey, MutableList<AllohaTrack>>()
        effectiveTracks.forEach { track ->
            val key = EpisodeKey(track.season, track.episode)
            tracksByEpisode.getOrPut(key) { mutableListOf() }
                .add(
                    AllohaTrack(
                        videoId = track.videoId,
                        season = key.season,
                        episode = key.episode,
                        translationId = track.translationId,
                        translationLabel = track.translationLabel,
                    ),
                )
        }

        if (tracksByEpisode.isEmpty()) {
            Log.d(name, "AnimeGO: Alloha tracks collapsed to 0 episode buckets for $refererUrl")
            return null
        }
        val deduplicated = tracksByEpisode.mapValues { (_, tracks) ->
            tracks.distinctBy { it.translationId to it.videoId }
        }
        return AllohaState(
            refererUrl = refererUrl,
            pageReferer = pageReferer,
            tracksByEpisode = deduplicated,
            visibleSeasonIds = visibleSeasonIds,
            borthSeed = extractAllohaBorthSeed(html).orEmpty(),
        )
    }

    private fun parseKodikState(document: Document): KodikState? {
        val html = document.html()
        val urlParams = AnimegoPatterns.KODIK_URL_PARAMS.find(html)?.groupValues?.get(1).orEmpty()
        if (urlParams.isBlank()) {
            Log.d(
                name,
                "AnimeGO: Kodik urlParams missing title=${document.title().take(80)} url=${document.location().ifBlank { "unknown" }}",
            )
            return null
        }

        val seasons = document.select(".serial-seasons-box select option")
            .mapNotNull { option ->
                val season = option.attr("value").toIntOrNull() ?: return@mapNotNull null
                val serialId = option.attr("data-serial-id")
                val serialHash = option.attr("data-serial-hash")
                if (serialId.isBlank() || serialHash.isBlank()) return@mapNotNull null
                KodikSeasonOption(season = season, serialId = serialId, serialHash = serialHash)
            }

        val episodesBySeason = parseKodikEpisodesBySeason(document, seasons)

        val translations = document.select(".serial-translations-box select option")
            .mapNotNull { option ->
                val translationId = option.attr("data-id").toIntOrNull() ?: return@mapNotNull null
                val mediaId = option.attr("data-media-id")
                val mediaHash = option.attr("data-media-hash")
                val mediaType = option.attr("data-media-type")
                if (mediaId.isBlank() || mediaHash.isBlank() || mediaType.isBlank()) return@mapNotNull null
                KodikTranslationOption(
                    translationId = translationId,
                    title = option.attr("data-title").ifBlank { option.text().substringBefore(" (") },
                    mediaId = mediaId,
                    mediaHash = mediaHash,
                    mediaType = mediaType,
                    episodeCount = option.attr("data-episode-count").toIntOrNull() ?: 0,
                )
            }

        return KodikState(
            urlParams = urlParams,
            seasons = seasons,
            episodesBySeason = episodesBySeason,
            translations = translations,
        )
    }

    private suspend inline fun <T> runLoggedSuspend(step: String, block: suspend () -> T): T? = runCatching { block() }
        .onFailure { error -> Log.e(name, "AnimeGO: $step failed", error) }
        .getOrNull()

    private fun buildLazyKodikVideos(
        translation: KodikTranslationOption,
        season: Int,
        episode: Int,
    ): List<VideoCandidate> {
        val translationUrl = buildKodikTranslationUrl(
            translation = translation,
            season = season,
            episode = episode,
        )

        val metadata = PlaybackMetadata(
            playerId = PLAYER_KODIK,
            playerLabel = PLAYER_KODIK_LABEL,
            dubbingId = normalizeId(translation.title),
            dubbingLabel = translation.title,
            sortOrder = KODIK_SORT_ORDER,
        )

        return COMMON_KODIK_QUALITIES.map { quality ->
            VideoCandidate(
                video = buildVideo(
                    url = translationUrl,
                    quality = quality,
                    metadata = metadata,
                    videoUrl = "null",
                    headers = buildResolverHeaders(
                        playbackReferer = translationUrl,
                        resolver = RESOLVER_KODIK,
                        preferredQuality = quality,
                    ),
                ),
                metadata = metadata,
                quality = quality,
            )
        }
    }

    private fun buildLazyAllohaVideos(
        track: AllohaTrack,
        playbackReferer: String,
        pageReferer: String,
        bnsiMovieId: String = "",
        borthSeed: String = "",
    ): List<VideoCandidate> {
        val contextualReferer = buildAllohaEpisodeReferer(
            baseReferer = playbackReferer,
            season = track.season,
            episode = track.episode,
            translationId = track.translationId,
        )
        val metadata = PlaybackMetadata(
            playerId = PLAYER_ALLOHA,
            playerLabel = PLAYER_ALLOHA_LABEL,
            dubbingId = normalizeId(track.translationLabel),
            dubbingLabel = track.translationLabel,
            sortOrder = ALLOHA_SORT_ORDER,
        )
        val resolvedVideoId = resolveAllohaTrackVideoId(
            trackVideoId = track.videoId,
            bnsiMovieId = bnsiMovieId,
        )
        val resolverKind = classifyAllohaResolverKind(contextualReferer)
        val resolverHeaders = Headers.Builder().apply {
            if (resolverKind == AllohaResolverKind.BNSI) {
                buildAllohaResolverHeaderMap(
                    playbackReferer = contextualReferer,
                    pageReferer = pageReferer,
                    preferredQuality = QUALITY_AUTO,
                    borthSeed = borthSeed,
                ).forEach { (name, value) ->
                    add(name, value)
                }
            } else {
                add(HEADER_RESOLVER, RESOLVER_ALLOHA)
                add("Referer", contextualReferer)
            }
        }.build()

        return COMMON_ALLOHA_QUALITIES.map { quality ->
            VideoCandidate(
                video = buildVideo(
                    url = buildAllohaResolverUrl(
                        playbackReferer = contextualReferer,
                        videoId = resolvedVideoId,
                        resolverKind = resolverKind,
                    ),
                    quality = quality,
                    metadata = metadata,
                    videoUrl = "null",
                    headers = resolverHeaders.newBuilder()
                        .set(HEADER_PREFERRED_QUALITY, quality)
                        .build(),
                ),
                metadata = metadata,
                quality = quality,
            )
        }
    }

    private fun buildAllohaResolverUrl(
        playbackReferer: String,
        videoId: String,
        resolverKind: AllohaResolverKind = classifyAllohaResolverKind(playbackReferer),
    ): String = when (resolverKind) {
        AllohaResolverKind.OPRAVAR -> "$OPAVAR_VIDEO_URL?video_id=$videoId"
        AllohaResolverKind.BNSI -> runCatching { playbackReferer.toHttpUrl() }.getOrNull()
            ?.newBuilder()
            ?.query(null)
            ?.fragment(null)
            ?.addPathSegments("bnsi/movies/$videoId")
            ?.build()
            ?.toString()
            ?: ALLOHA_VIDEO_URL.toHttpUrl().newBuilder()
                .addPathSegments("bnsi/movies/$videoId")
                .build()
                .toString()
        AllohaResolverKind.LEGACY_GET -> ALLOHA_VIDEO_URL.toHttpUrl().newBuilder()
            .addQueryParameter("video_id", videoId)
            .build()
            .toString()
    }

    private fun buildKodikTranslationUrl(
        translation: KodikTranslationOption,
        season: Int,
        episode: Int,
    ): String = "https://kodikplayer.com/${translation.mediaType}/${translation.mediaId}/${translation.mediaHash}/720p" +
        "?season=$season&episode=$episode"

    private fun playbackResolverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return when (request.header(HEADER_RESOLVER)) {
            RESOLVER_KODIK -> chain.proceedKodikResolver(request)
            RESOLVER_ALLOHA -> chain.proceedAllohaResolver(request)
            else -> chain.proceed(request)
        }
    }

    private fun Interceptor.Chain.proceedKodikResolver(request: Request): Response {
        val html = proceed(request).use { it.body.string() }
        val urlParams = AnimegoPatterns.KODIK_URL_PARAMS.find(html)?.groupValues?.get(1).orEmpty()
        val vInfo = parseKodikVideoInfo(html)
        if (urlParams.isBlank() || vInfo == null) {
            error("AnimeGO: Kodik resolver prerequisites missing")
        }
        val ftorRequest = buildKodikFtorRequest(urlParams, vInfo, request.headers)
            ?: error("AnimeGO: Kodik ftor request missing")
        return proceed(ftorRequest)
    }

    private fun Interceptor.Chain.proceedAllohaResolver(request: Request): Response {
        val response = proceed(request)
        if (
            response.code != 404 ||
            request.method.uppercase(Locale.ROOT) != "POST" ||
            classifyAllohaResolverKind(request.header("Referer").orEmpty()) != AllohaResolverKind.BNSI
        ) {
            return response
        }

        response.close()
        val playbackReferer = request.header("Referer").orEmpty()
        val preferredQuality = request.header(HEADER_PREFERRED_QUALITY).orEmpty()
        val videoId = request.url.pathSegments.lastOrNull().orEmpty()
        val fallbackUrl = buildAllohaResolverUrl(
            playbackReferer = playbackReferer,
            videoId = videoId,
            resolverKind = AllohaResolverKind.LEGACY_GET,
        )
        val fallbackHeaders = buildResolverHeaders(
            playbackReferer = playbackReferer,
            resolver = RESOLVER_ALLOHA,
            preferredQuality = preferredQuality,
        )
        return proceed(GET(fallbackUrl, fallbackHeaders))
    }

    private fun buildKodikFtorRequest(
        urlParamsRaw: String,
        vInfo: KodikVideoInfo,
        requestHeaders: Headers,
    ): Request? {
        if (urlParamsRaw.isBlank()) return null
        val form = urlParamsRaw.parseAs<KodikForm>()
        if (form.dSign.isBlank() || form.pd.isBlank()) return null

        val body = FormBody.Builder()
            .add("d", form.d)
            .add("d_sign", URLDecoder.decode(form.dSign, "UTF-8"))
            .add("pd", form.pd)
            .add("pd_sign", URLDecoder.decode(form.pdSign, "UTF-8"))
            .add("ref", URLDecoder.decode(form.ref, "UTF-8"))
            .add("ref_sign", URLDecoder.decode(form.refSign, "UTF-8"))
            .add("type", vInfo.type)
            .add("id", vInfo.id)
            .add("hash", vInfo.hash)
            .build()

        val ftorHeaders = requestHeaders.newBuilder()
            .removeAll(HEADER_RESOLVER)
            .set("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return POST("https://${form.pd}/ftor", ftorHeaders, body)
    }

    private fun parseKodikVideoInfo(html: String): KodikVideoInfo? {
        val type = AnimegoPatterns.KODIK_VINFO_TYPE.find(html)?.groupValues?.get(1).orEmpty()
        val id = AnimegoPatterns.KODIK_VINFO_ID.find(html)?.groupValues?.get(1).orEmpty()
        val hash = AnimegoPatterns.KODIK_VINFO_HASH.find(html)?.groupValues?.get(1).orEmpty()
        if (type.isBlank() || id.isBlank() || hash.isBlank()) return null
        return KodikVideoInfo(type = type, id = id, hash = hash)
    }

    private fun decodeKodikLink(link: String): String? {
        if (link.isBlank()) return null
        val shifted = rotateKodikCipher(link)
        val decoded = Base64.decode(shifted, Base64.DEFAULT).toString(Charsets.UTF_8)
        return decoded.takeIf(String::isNotBlank)?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
    }

    private fun resolveKodikVideoUrl(response: Response): String {
        val requestedQuality = response.request.header(HEADER_PREFERRED_QUALITY).orEmpty()
        val ftor = response.use { it.parseAs<KodikFtorResponse>() }
        val encodedLink = selectPreferredQualityValue(ftor.links.qualityMap(), requestedQuality)
            ?: run {
                Log.d(
                    name,
                    "AnimeGO: Kodik links missing for ${response.request.url} requestedQuality=$requestedQuality available=${ftor.links.keys.joinToString()}",
                )
                error("AnimeGO: Kodik links missing")
            }
        return decodeKodikLink(encodedLink) ?: run {
            Log.d(
                name,
                "AnimeGO: Kodik decode failed for ${response.request.url} requestedQuality=$requestedQuality encodedLength=${encodedLink.length}",
            )
            error("AnimeGO: Kodik decode failed")
        }
    }

    private fun resolveCdnVideoUrl(response: Response): String {
        val requestedQuality = response.request.header(HEADER_PREFERRED_QUALITY).orEmpty()
        val cdnResponse = response.use { it.parseAs<CdnVideoResponse>() }
        val directSources = cdnResponse.sources.qualityMap()
        val playbackHeaders = buildCdnPlaybackHeaders()
        val hlsUrl = cdnResponse.sources.hlsUrl.takeIf(String::isNotBlank)
        val hlsQualityMap = hlsUrl
            ?.let { fetchCdnHlsMaster(it, playbackHeaders) }
            ?.let { parseCdnHlsQualityMap(it, hlsUrl) }

        selectPreferredQualityValue(hlsQualityMap.orEmpty(), requestedQuality)?.let { return it }
        selectPreferredQualityValue(directSources, requestedQuality)?.let { return it }

        val fallbackUrl = selectCdnFallbackUrl(
            dashUrl = cdnResponse.sources.dashUrl,
            directSources = directSources,
            preferredQuality = requestedQuality,
        )
        if (fallbackUrl != null) {
            if (fallbackUrl == cdnResponse.sources.dashUrl) {
                Log.d(name, "AnimeGO: CDN fallbacking to DASH source")
            }
            return fallbackUrl
        }

        Log.d(
            name,
            "AnimeGO: CDN sources missing for ${response.request.url} requestedQuality=$requestedQuality " +
                "direct=${directSources.keys.joinToString()} hls=${hlsQualityMap?.keys?.joinToString().orEmpty()} " +
                "dash=${cdnResponse.sources.dashUrl.isNotBlank()}",
        )
        error("AnimeGO: CDN sources missing")
    }

    private fun resolveAllohaVideoUrl(response: Response): String {
        resolveAllohaVideoUrlOrNull(response)?.let { resolved ->
            Log.d(name, "AnimeGO: Alloha resolve result=${resolved.take(120)}")
            return resolved
        }

        Log.d(name, "AnimeGO: Alloha resolve result=NULL")
        error("AnimeGO: Alloha source missing")
    }

    private fun resolveAllohaVideoUrlOrNull(response: Response): String? {
        val requestedQuality = response.request.header(HEADER_PREFERRED_QUALITY).orEmpty()
        val playbackReferer = response.request.header("Referer").orEmpty().ifBlank { "$baseUrl/" }
        val responseBody = try {
            response.use { it.body.string() }
        } catch (e: Exception) {
            Log.e(name, "AnimeGO: Alloha bnsi body read failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        Log.d(
            name,
            "AnimeGO: Alloha resolve bodyLen=${responseBody.length} " +
                "hasHlsSource=${responseBody.contains("hlsSource")} " +
                "hasSrc=${responseBody.contains("\"src\"")} " +
                "bodyFirst200=${buildAllohaDebugPreview(responseBody, 200)}",
        )
        val hlsUrl = extractAllohaPlaybackUrl(responseBody, requestedQuality) ?: run {
            Log.d(
                name,
                "AnimeGO: Alloha source missing for ${response.request.url} status=${response.code} " +
                    "bodyLength=${responseBody.length} " +
                    "bodyPreview=${buildAllohaDebugPreview(responseBody, 500)} " +
                    "markers=src=${responseBody.contains("\"src\"")} hlsSource=${responseBody.contains("hlsSource")} " +
                    "inputData=${responseBody.contains("inputData")} " +
                    "fileList=${responseBody.contains("fileList")} movie=${responseBody.contains("const movie") || responseBody.contains("movie =")} " +
                    "userParam=${responseBody.contains("userParam")}",
            )
            return null
        }

        Log.d(
            name,
            "AnimeGO: Alloha source extracted for ${response.request.url} requestedQuality=$requestedQuality url=${hlsUrl.take(180)}",
        )

        val playbackHeaders = buildPlaybackHeaders(
            playbackReferer,
        )
        val manifest = runCatching { fetchCdnHlsMaster(hlsUrl, playbackHeaders) }.getOrNull()
            ?: run {
                Log.d(
                    name,
                    "AnimeGO: Alloha manifest unavailable for ${response.request.url} requestedQuality=$requestedQuality url=${hlsUrl.take(180)}",
                )
                return hlsUrl
            }
        val qualityMap = parseCdnHlsQualityMap(manifest, hlsUrl)
        if (qualityMap.isEmpty()) {
            Log.d(
                name,
                "AnimeGO: Alloha manifest had no variants for ${response.request.url} requestedQuality=$requestedQuality url=${hlsUrl.take(180)}",
            )
            return hlsUrl
        }

        return selectPreferredQualityValue(qualityMap, requestedQuality) ?: hlsUrl
    }

    private fun fetchCdnHlsMaster(hlsUrl: String, headers: Headers): String? {
        val request = GET(hlsUrl, headers)
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                Log.d(name, "AnimeGO: fetchCdnHlsMaster url=${hlsUrl.take(120)} status=${response.code} bodyLen=${body.length}")
                body.takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private fun finalizeVideoCandidates(candidates: List<VideoCandidate>): List<Video> {
        if (candidates.isEmpty()) return emptyList()

        val playerPref = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT).orEmpty()
        val cdnDubbing = preferences.getString(PREF_CDN_DUBBING_KEY, "").orEmpty()
        val kodikDubbing = preferences.getString(PREF_KODIK_DUBBING_KEY, "").orEmpty()
        val cdnQuality = preferences.getString(PREF_CDN_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()
        val kodikQuality = preferences.getString(PREF_KODIK_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()
        val sorted = candidates.sortedByDescending {
            scoreCandidate(
                candidate = it,
                playerPref = playerPref,
                cdnDubbing = cdnDubbing,
                kodikDubbing = kodikDubbing,
                cdnQuality = cdnQuality,
                kodikQuality = kodikQuality,
            )
        }

        return sorted.map { it.video }
    }

    private fun scoreCandidate(
        candidate: VideoCandidate,
        playerPref: String,
        cdnDubbing: String,
        kodikDubbing: String,
        cdnQuality: String,
        kodikQuality: String,
    ): Int {
        val metadata = candidate.metadata
        val playerScore = when (playerPref) {
            PLAYER_CDN -> if (metadata.playerId == PLAYER_CDN) 10_000 else 0
            PLAYER_KODIK -> if (metadata.playerId == PLAYER_KODIK) 10_000 else 0
            PLAYER_ALLOHA -> if (metadata.playerId == PLAYER_ALLOHA) 10_000 else 0
            else -> when (metadata.playerId) {
                PLAYER_CDN -> 6_000
                PLAYER_KODIK -> 5_000
                PLAYER_ALLOHA -> 4_000
                else -> 0
            }
        }

        val preferredDubbing = when (metadata.playerId) {
            PLAYER_CDN -> cdnDubbing
            PLAYER_KODIK -> kodikDubbing
            PLAYER_ALLOHA -> kodikDubbing
            else -> ""
        }
        val dubbingScore = when {
            preferredDubbing.isBlank() -> 500
            metadata.dubbingLabel.lowercase(Locale.ROOT)
                .contains(preferredDubbing.lowercase(Locale.ROOT)) -> 2_000
            else -> 0
        }

        val preferredQuality = when (metadata.playerId) {
            PLAYER_CDN -> cdnQuality
            PLAYER_KODIK -> kodikQuality
            PLAYER_ALLOHA -> kodikQuality
            else -> QUALITY_AUTO
        }
        val qualityRank = qualityRank(candidate.quality)
        val qualityScore = when {
            preferredQuality == QUALITY_AUTO -> qualityRank
            candidate.quality.lowercase(Locale.ROOT) ==
                "${preferredQuality}p".lowercase(Locale.ROOT) -> 1_000 + qualityRank
            else -> qualityRank
        }

        return playerScore + dubbingScore + qualityScore - metadata.sortOrder
    }

    private fun buildVideo(
        url: String,
        quality: String,
        metadata: PlaybackMetadata,
        videoUrl: String? = url,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ): Video = Video(
        url = url,
        quality = buildPlaybackLabel(metadata, quality),
        videoUrl = videoUrl,
        headers = headers ?: Headers.headersOf(),
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    )

    private fun buildPlaybackLabel(metadata: PlaybackMetadata, quality: String): String = "${metadata.playerLabel} $PLAYBACK_LABEL_DELIMITER ${metadata.dubbingLabel} $PLAYBACK_LABEL_DELIMITER $quality"

    private fun refererHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .apply {
            runCatching { referer.toHttpUrl().host }
                .getOrNull()
                ?.let { host -> set("Origin", "https://$host") }
        }
        .build()

    private fun ajaxHeaders(referer: String): Headers = refererHeaders(referer).newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun buildPlaybackHeaders(referer: String): Headers = refererHeaders(referer)

    private fun buildResolverHeaders(
        playbackReferer: String,
        resolver: String,
        preferredQuality: String,
    ): Headers = ajaxHeaders(playbackReferer).newBuilder()
        .set(HEADER_RESOLVER, resolver)
        .set(HEADER_PREFERRED_QUALITY, preferredQuality)
        .build()

    private fun extractEpisodeContext(response: Response): Pair<Int, Int>? {
        val urls = generateSequence(response) { it.priorResponse }
            .map { it.request.url.toString() }
            .toList()

        val context = extractEpisodeContext(urls)
        if (context == null) {
            Log.d(name, "AnimeGO: episode context missing after redirects for urls=$urls")
        }
        return context
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        if (page <= 1) return "$baseUrl$normalizedPath"
        val basePath = normalizedPath.removeSuffix("/")
        return "$baseUrl$basePath/page/$page/"
    }

    private fun buildLatestUpdatesUrl(page: Int): String = if (page <= 1) {
        "$baseUrl/anime-a2/"
    } else {
        "$baseUrl/anime/page/$page/"
    }

    private fun normalizeId(value: String): String = value.lowercase(Locale.ROOT)
        .replace(AnimegoPatterns.NORMALIZE_ID, "-")
        .trim('-')

    private fun qualityRank(quality: String): Int = when (quality.removeSuffix("p")) {
        "2160" -> 2160
        "1440" -> 1440
        "1080" -> 1080
        "720" -> 720
        "480" -> 480
        "360" -> 360
        "240" -> 240
        "144" -> 144
        else -> 0
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 36

        private const val PLAYER_CDN = "cdn"
        private const val PLAYER_KODIK = "kodik"
        private const val PLAYER_ALLOHA = "alloha"
        private const val PLAYER_CDN_LABEL = "CDN"
        private const val PLAYER_KODIK_LABEL = "Kodik"
        private const val PLAYER_ALLOHA_LABEL = "Alloha"

        private const val CDN_SORT_ORDER = 0
        private const val KODIK_SORT_ORDER = 10
        private const val ALLOHA_SORT_ORDER = 20

        private const val CDN_PLAYLIST_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist"
        private const val CDN_VIDEO_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/video"
        private const val ALLOHA_VIDEO_URL = "https://absciss.thealloha.club"

        private const val PREF_PLAYER_KEY = "preferred_player"
        private const val PREF_CDN_DUBBING_KEY = "preferred_dubbing_cdn"
        private const val PREF_KODIK_DUBBING_KEY = "preferred_dubbing_kodik"
        private const val PREF_CDN_QUALITY_KEY = "preferred_quality_cdn"
        private const val PREF_KODIK_QUALITY_KEY = "preferred_quality_kodik"

        private const val PREF_PLAYER_DEFAULT = "all"
        private val PREF_PLAYER_ENTRIES = arrayOf("Все", "CDN", "Kodik", "Alloha")
        private val PREF_PLAYER_VALUES = arrayOf(PREF_PLAYER_DEFAULT, PLAYER_CDN, PLAYER_KODIK, PLAYER_ALLOHA)

        private const val QUALITY_AUTO = "auto"
        private const val PREF_QUALITY_DEFAULT = QUALITY_AUTO
        private const val PLAYBACK_LABEL_DELIMITER = "•"
        private val COMMON_CDN_QUALITIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        private val COMMON_KODIK_QUALITIES = listOf("720p", "480p", "360p")
        private val COMMON_ALLOHA_QUALITIES = listOf("1080p", "720p", "480p", "360p")
        private const val OPAVAR_VIDEO_URL = "https://opravar.online/player/responce.php"
        private const val HEADER_RESOLVER = "X-AnimeGO-Resolver"
        private const val HEADER_PREFERRED_QUALITY = "X-AnimeGO-Preferred-Quality"
        internal const val HEADER_ALLOHA_BORTH = "X-AnimeGO-Alloha-Borth"
        internal const val HEADER_ALLOHA_PAGE_REFERER = "X-AnimeGO-Page-Referer"
        private const val RESOLVER_CDN = "cdn"
        private const val RESOLVER_KODIK = "kodik"
        private const val RESOLVER_ALLOHA = "alloha"
        private val PREF_QUALITY_ENTRIES = arrayOf("Лучшее доступное", "1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = arrayOf(QUALITY_AUTO, "1080", "720", "480", "360", "240")
    }
}

internal fun rotateKodikCipher(link: String): String = link.map { char ->
    when {
        char in 'A'..'Z' -> {
            val shifted = char.code + 18
            (if (shifted <= 'Z'.code) shifted else shifted - 26).toChar()
        }
        char in 'a'..'z' -> {
            val shifted = char.code + 18
            (if (shifted <= 'z'.code) shifted else shifted - 26).toChar()
        }
        else -> char
    }
}.joinToString(separator = "")

internal fun decodeJsSingleQuotedString(value: String): String {
    if (value.isEmpty()) return value

    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current != '\\') {
            builder.append(current)
            index++
            continue
        }

        if (index + 1 >= value.length) {
            builder.append('\\')
            break
        }

        when (val escape = value[index + 1]) {
            '\\' -> builder.append('\\')
            '\'' -> builder.append('\'')
            '"' -> builder.append('"')
            '/' -> builder.append('/')
            'b' -> builder.append('\b')
            'f' -> builder.append('\u000C')
            'n' -> builder.append('\n')
            'r' -> builder.append('\r')
            't' -> builder.append('\t')
            'u' -> {
                val end = index + 6
                if (end <= value.length) {
                    val codePoint = value.substring(index + 2, end).toIntOrNull(16)
                    if (codePoint != null) {
                        builder.append(codePoint.toChar())
                        index += 6
                        continue
                    }
                }
                builder.append("\\u")
                index += 2
                continue
            }
            'x' -> {
                val end = index + 4
                if (end <= value.length) {
                    val codePoint = value.substring(index + 2, end).toIntOrNull(16)
                    if (codePoint != null) {
                        builder.append(codePoint.toChar())
                        index += 4
                        continue
                    }
                }
                builder.append("\\x")
                index += 2
                continue
            }
            else -> builder.append(escape)
        }
        index += 2
    }

    return builder.toString()
}

internal fun normalizeEmbeddedPlayerUrl(rawValue: String): String {
    if (rawValue.isBlank()) return rawValue

    var normalized = decodeJsSingleQuotedString(rawValue.trim())
    if (normalized.contains("\\/") || normalized.contains("\\u")) {
        normalized = decodeJsSingleQuotedString(normalized)
    }

    normalized = normalized.removePrefix("\"").removeSuffix("\"")
    normalized = Parser.unescapeEntities(normalized, false)
    return normalized.replace("&amp;", "&")
}

internal fun extractAllohaFileListPayload(html: String): String? {
    if (html.isBlank()) return null

    val escapedPayload = AnimegoPatterns.ALLOHA_FILE_LIST.firstNotNullOfOrNull { regex ->
        regex.find(html)?.groupValues?.getOrNull(1)
    } ?: return null

    var decoded = decodeJsSingleQuotedString(escapedPayload)
    if (decoded.contains("\\\"") || decoded.contains("\\/")) {
        decoded = decodeJsSingleQuotedString(decoded)
    }
    return decoded.takeIf(String::isNotBlank)
}

internal fun buildAllohaControllerParamsCandidates(params: String): List<String> {
    if (params.isBlank()) return emptyList()

    val normalized = params.replace("&amp;", "&").trim()
    if (normalized.isBlank()) return emptyList()

    val id = AnimegoPatterns.CONTROLLER_ID
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
    val isLegacyParlorate =
        normalized.containsToken("mod=xfplayer") &&
            normalized.containsToken("name=parlorate") &&
            !id.isNullOrBlank()
    if (!isLegacyParlorate) return listOf(normalized)

    return listOf(
        normalized,
        "mod=alloha-player&url=1&action=iframe&id=$id",
    )
}

internal fun selectAllohaPlayerParams(paramsList: List<String>): String? {
    val normalizedParams = paramsList
        .map { it.replace("&amp;", "&").trim() }

    return normalizedParams.firstOrNull { params ->
        params.containsToken("mod=alloha-player")
    } ?: normalizedParams.firstOrNull { params ->
        params.containsToken("mod=xfplayer") && params.containsToken("name=parlorate")
    }
}

internal fun classifyAllohaControllerParams(params: String): String {
    val normalized = params.replace("&amp;", "&").trim()
    return when {
        normalized.containsToken("mod=alloha-player") -> "classic"
        normalized.containsToken("mod=xfplayer") -> "xfplayer"
        else -> "unknown"
    }
}

internal enum class AllohaResolverKind {
    OPRAVAR,
    BNSI,
    LEGACY_GET,
}

internal fun classifyAllohaResolverKind(playbackReferer: String): AllohaResolverKind {
    val normalized = playbackReferer.replace("&amp;", "&").lowercase(Locale.ROOT)
    val host = runCatching { playbackReferer.toHttpUrl().host }.getOrNull().orEmpty().lowercase(Locale.ROOT)
    val isOpravarHost = host.contains("opravar") || host.contains("gencit") ||
        normalized.contains("opravar.") || normalized.contains("gencit.")
    return when {
        isOpravarHost -> AllohaResolverKind.OPRAVAR
        isAllohaSpaTokenReferer(playbackReferer) -> AllohaResolverKind.BNSI
        else -> AllohaResolverKind.LEGACY_GET
    }
}

internal fun resolveAllohaTrackVideoId(
    trackVideoId: String,
    bnsiMovieId: String,
): String = trackVideoId.ifBlank { bnsiMovieId }

internal fun parseAllohaTracks(payload: String): List<ParsedAllohaTrack> {
    if (payload.isBlank()) return emptyList()

    val objectStart = payload.indexOf('{')
    val objectEnd = payload.lastIndexOf('}')
    if (objectStart == -1 || objectEnd <= objectStart) return emptyList()
    val jsonPayload = payload.substring(objectStart, objectEnd + 1)
    return runCatching { AllohaFileListParser(jsonPayload).parseTracks() }
        .getOrDefault(emptyList())
}

private class AllohaFileListParser(
    private val source: String,
) {
    private var index = 0

    fun parseTracks(): List<ParsedAllohaTrack> {
        skipWhitespace()
        return parseRootObject()
    }

    private fun parseRootObject(): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            if (key == "all" && peek() == '{') {
                tracks += parseAllObject()
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseAllObject(): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val season = parseString().toIntOrNull()?.takeIf { it > 0 }
            skipWhitespace()
            expect(':')
            skipWhitespace()

            when {
                season == null -> skipValue()
                peek() == '{' -> tracks += parseSeasonEpisodeObject(season)
                peek() == '[' -> tracks += parseSeasonLegacyArray(season)
                else -> skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseSeasonEpisodeObject(season: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            val episode = key.toIntOrNull()?.takeIf { it > 0 }
            when {
                episode == null -> skipValue()
                peek() == '{' -> tracks += parseTranslationMap(
                    translationMapSeason = season,
                    translationMapEpisode = episode,
                )
                peek() == '[' -> tracks += parseEpisodeLegacyArray(season, episode)
                else -> skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseSeasonLegacyArray(season: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            if (peek() == '{') {
                tracks += parseTranslationMap(
                    translationMapSeason = season,
                    translationMapEpisode = null,
                )
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseEpisodeLegacyArray(season: Int, episode: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            if (peek() == '{') {
                tracks += parseTranslationMap(
                    translationMapSeason = season,
                    translationMapEpisode = episode,
                )
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseTranslationMap(
        translationMapSeason: Int?,
        translationMapEpisode: Int?,
    ): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val translationKey = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            if (peek() == '{' && isTranslationKey(translationKey)) {
                parseTranslationObject(
                    translationKey = translationKey,
                    translationMapSeason = translationMapSeason,
                    translationMapEpisode = translationMapEpisode,
                )?.let { tracks += it }
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseTranslationObject(
        translationKey: String,
        translationMapSeason: Int?,
        translationMapEpisode: Int?,
    ): ParsedAllohaTrack? {
        expect('{')
        var videoId: String? = null
        var episodeFromObject: Int? = null
        var seasonFromObject: Int? = null
        var translationIdFromObject: Int? = null
        var translationLabel: String? = null
        var first = true

        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            when (key) {
                "id", "video_id" -> {
                    videoId = parseStringOrToken()
                        ?.takeIf { it.toLongOrNull()?.let { id -> id > 0L } == true }
                }
                "episode" -> {
                    episodeFromObject = parseStringOrToken()?.toIntOrNull()?.takeIf { it > 0 }
                }
                "season", "seasons" -> {
                    seasonFromObject = parseStringOrToken()?.toIntOrNull()?.takeIf { it > 0 }
                }
                "translation_id", "id_translation", "voice_id" -> {
                    translationIdFromObject = parseStringOrToken()?.toIntOrNull()
                }
                "translation", "voice_name" -> {
                    translationLabel = parseStringOrToken()?.takeIf(String::isNotBlank)
                }
                else -> skipValue()
            }
            first = false
        }

        val resolvedVideoId = videoId ?: return null
        val resolvedEpisode = translationMapEpisode ?: episodeFromObject ?: return null
        if (resolvedEpisode <= 0) return null

        val resolvedSeason = translationMapSeason ?: seasonFromObject ?: 1
        val resolvedTranslationId = translationIdFromObject
            ?: translationKey.removePrefix("t").toIntOrNull()
            ?: 0

        return ParsedAllohaTrack(
            season = resolvedSeason,
            episode = resolvedEpisode,
            videoId = resolvedVideoId,
            translationId = resolvedTranslationId,
            translationLabel = translationLabel
                ?: if (resolvedTranslationId > 0) "Translation $resolvedTranslationId" else "Translation",
        )
    }

    private fun isTranslationKey(key: String): Boolean {
        if (key.isBlank()) return false
        return key.startsWith("t") || key.toIntOrNull() != null
    }

    private fun parseStringOrToken(): String? {
        skipWhitespace()
        return if (peek() == '"') parseString() else parseScalarToken()
    }

    private fun skipValue() {
        skipWhitespace()
        when (peek()) {
            '{' -> {
                expect('{')
                var first = true
                while (true) {
                    skipWhitespace()
                    if (consumeIf('}')) break
                    if (!first) expect(',')
                    skipWhitespace()
                    parseString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    skipValue()
                    first = false
                }
            }
            '[' -> {
                expect('[')
                var first = true
                while (true) {
                    skipWhitespace()
                    if (consumeIf(']')) break
                    if (!first) expect(',')
                    skipWhitespace()
                    skipValue()
                    first = false
                }
            }
            '"' -> parseString()
            else -> parseScalarToken()
        }
    }

    private fun parseScalarToken(): String? {
        skipWhitespace()
        val start = index
        while (index < source.length && source[index] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
            index++
        }
        val token = source.substring(start, index).trim()
        if (token.isEmpty() || token == "null") return null
        return token
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    val escaped = source.getOrNull(index++) ?: break
                    when (escaped) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                            if (hex.length == 4) {
                                result.append(hex.toInt(16).toChar())
                                index += 4
                            }
                        }
                        else -> result.append(escaped)
                    }
                }
                else -> result.append(char)
            }
        }
        error("Unterminated string at $index")
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index++
        }
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun expect(expected: Char) {
        check(peek() == expected) { "Expected '$expected' at $index" }
        index++
    }

    private fun peek(): Char? = source.getOrNull(index)
}

internal fun extractAllohaTracksFromInputData(document: Document): List<ParsedAllohaTrack> {
    val inputData = document.selectFirst("#inputData") ?: return emptyList()
    val payload = Parser.unescapeEntities(inputData.html(), false).trim()
    if (payload.isBlank()) return emptyList()

    val jsonPayload = payload.substring(payload.indexOf('{'), payload.lastIndexOf('}') + 1)
    return runCatching { AllohaInputDataParser(jsonPayload).parseTracks() }
        .getOrDefault(emptyList())
}

internal fun extractParlorateTracksFromInputData(document: Document): List<ParsedAllohaTrack> {
    val inputData = document.selectFirst("#inputData") ?: return emptyList()
    val payload = Parser.unescapeEntities(inputData.html(), false).trim()
    if (payload.isBlank()) return emptyList()

    val jsonPayload = payload.substring(payload.indexOf('{'), payload.lastIndexOf('}') + 1)
    return runCatching { ParlorateInputDataParser(jsonPayload).parseTracks() }
        .getOrDefault(emptyList())
}

private class AllohaInputDataParser(
    private val source: String,
) {
    private var index = 0

    fun parseTracks(): List<ParsedAllohaTrack> {
        skipWhitespace()
        return parseSeasonObject()
    }

    private fun parseSeasonObject(): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val season = parseString().toIntOrNull()?.takeIf { it > 0 }
            skipWhitespace()
            expect(':')
            skipWhitespace()

            when {
                season == null -> skipValue()
                peek() == '{' -> tracks += parseEpisodeObject(season)
                peek() == '[' -> tracks += parseSeasonEpisodeArray(season)
                else -> skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseSeasonEpisodeArray(season: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            when (peek()) {
                '[' -> tracks += parseTrackArray(season, 0)
                '{' -> parseTrackObject(season, 0)?.let(tracks::add)
                else -> skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseEpisodeObject(season: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val episode = parseString().toIntOrNull()?.takeIf { it > 0 }
            skipWhitespace()
            expect(':')
            skipWhitespace()

            if (episode != null && peek() == '[') {
                tracks += parseTrackArray(season, episode)
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseTrackArray(season: Int, episode: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            val track = if (peek() == '{') {
                parseTrackObject(season, episode)
            } else {
                skipValue()
                null
            }
            if (track != null) tracks += track
            first = false
        }
        return tracks
    }

    private fun parseTrackObject(season: Int, episode: Int): ParsedAllohaTrack? {
        expect('{')
        var videoId: String? = null
        var seasonFromObject: Int? = null
        var episodeFromObject: Int? = null
        var translationId = 0
        var translationLabel: String? = null
        var first = true

        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            when (key) {
                "video_id" -> {
                    videoId = parseScalarToken()?.takeIf { it.toLongOrNull()?.let { id -> id > 0L } == true }
                }
                "season", "seasons" -> {
                    seasonFromObject = parseScalarToken()?.toIntOrNull()?.takeIf { it > 0 }
                }
                "episode" -> {
                    episodeFromObject = parseScalarToken()?.toIntOrNull()?.takeIf { it > 0 }
                }
                "voice_id" -> {
                    translationId = parseScalarToken()?.toIntOrNull() ?: 0
                }
                "voice_name" -> {
                    translationLabel = parseString().takeIf(String::isNotBlank)
                }
                else -> skipValue()
            }
            first = false
        }

        val resolvedVideoId = videoId ?: return null
        val resolvedEpisode = if (episode > 0) episode else (episodeFromObject ?: return null)
        if (resolvedEpisode <= 0) return null
        val resolvedSeason = if (season > 0) season else (seasonFromObject ?: 1)
        return ParsedAllohaTrack(
            season = resolvedSeason,
            episode = resolvedEpisode,
            videoId = resolvedVideoId,
            translationId = translationId,
            translationLabel = translationLabel
                ?: if (translationId > 0) "Translation $translationId" else "Translation",
        )
    }

    private fun skipValue() {
        skipWhitespace()
        when (peek()) {
            '{' -> {
                expect('{')
                var first = true
                while (true) {
                    skipWhitespace()
                    if (consumeIf('}')) break
                    if (!first) expect(',')
                    skipWhitespace()
                    parseString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    skipValue()
                    first = false
                }
            }
            '[' -> {
                expect('[')
                var first = true
                while (true) {
                    skipWhitespace()
                    if (consumeIf(']')) break
                    if (!first) expect(',')
                    skipWhitespace()
                    skipValue()
                    first = false
                }
            }
            '"' -> parseString()
            else -> parseScalarToken()
        }
    }

    private fun parseScalarToken(): String? {
        skipWhitespace()
        val start = index
        while (index < source.length && source[index] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
            index++
        }
        return source.substring(start, index).trim().takeIf(String::isNotEmpty)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    val escaped = source.getOrNull(index++) ?: break
                    when (escaped) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                            if (hex.length == 4) {
                                result.append(hex.toInt(16).toChar())
                                index += 4
                            }
                        }
                        else -> result.append(escaped)
                    }
                }
                else -> result.append(char)
            }
        }
        error("Unterminated string at $index")
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index++
        }
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun expect(expected: Char) {
        check(peek() == expected) { "Expected '$expected' at $index" }
        index++
    }

    private fun peek(): Char? = source.getOrNull(index)
}

internal class ParlorateInputDataParser(
    private val source: String,
) {
    private var index = 0

    fun parseTracks(): List<ParsedAllohaTrack> {
        skipWhitespace()
        return parseSeasonObject()
    }

    private fun parseSeasonObject(): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('{')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val season = parseString().toIntOrNull()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            if (season != null && peek() == '[') {
                tracks += parseDoubleNestedArray(season)
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseDoubleNestedArray(season: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var episode = 0
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            when (peek()) {
                '[' -> {
                    episode++
                    tracks += parseTrackArray(season, episode)
                }
                '{' -> parseTrackObject(season, 0)?.let(tracks::add)
                else -> skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseTrackArray(season: Int, episode: Int): List<ParsedAllohaTrack> {
        val tracks = mutableListOf<ParsedAllohaTrack>()
        expect('[')
        var first = true
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) break
            if (!first) expect(',')
            skipWhitespace()

            if (peek() == '{') {
                parseTrackObject(season, episode)?.let(tracks::add)
            } else {
                skipValue()
            }
            first = false
        }
        return tracks
    }

    private fun parseTrackObject(season: Int, episode: Int): ParsedAllohaTrack? {
        expect('{')
        var videoId: String? = null
        var translationId = 0
        var translationLabel = ""
        var first = true

        while (true) {
            skipWhitespace()
            if (consumeIf('}')) break
            if (!first) expect(',')
            skipWhitespace()

            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            when (key) {
                "video_id" -> videoId = parseValue()
                "voice_id" -> translationId = parseValue().toIntOrNull() ?: 0
                "voice_name" -> translationLabel = parseUnquotedOrString()
                else -> skipValue()
            }
            first = false
        }

        if (videoId.isNullOrBlank()) return null

        return ParsedAllohaTrack(
            season = season,
            episode = episode,
            videoId = videoId,
            translationId = translationId,
            translationLabel = translationLabel,
        )
    }

    private fun parseValue(): String {
        skipWhitespace()
        return when (peek()) {
            '"' -> parseString()
            else -> parseUnquoted()
        }
    }

    private fun parseUnquotedOrString(): String {
        skipWhitespace()
        return when (peek()) {
            '"' -> parseString()
            else -> parseUnquoted()
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = peek() ?: break
            when (c) {
                '\\' -> {
                    index++
                    val esc = peek() ?: break
                    when (esc) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'u' -> {
                            index++
                            val hex = source.substring(index, minOf(index + 4, source.length))
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            index += hex.length - 1
                        }
                        else -> sb.append(esc)
                    }
                    index++
                }
                '"' -> {
                    index++
                    break
                }
                else -> {
                    sb.append(c)
                    index++
                }
            }
        }
        return sb.toString()
    }

    private fun parseUnquoted(): String {
        val start = index
        while (true) {
            val c = peek()
            if (c == null || c == ',' || c == '}' || c == ']' || c.isWhitespace()) break
            index++
        }
        return source.substring(start, index)
    }

    private fun skipValue() {
        skipWhitespace()
        when (peek()) {
            '"' -> parseString()
            '[' -> {
                expect('[')
                skipUntilBalanced(']', '[')
            }
            '{' -> {
                expect('{')
                skipUntilBalanced('}', '{')
            }
            else -> {
                while (true) {
                    val c = peek() ?: break
                    if (c == ',' || c == '}' || c == ']') break
                    index++
                }
            }
        }
    }

    private fun skipUntilBalanced(close: Char, open: Char) {
        var depth = 1
        while (depth > 0) {
            val c = peek() ?: break
            when (c) {
                '"' -> parseString()
                open -> {
                    depth++
                    index++
                }
                close -> {
                    depth--
                    index++
                }
                else -> index++
            }
        }
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun expect(expected: Char) {
        check(peek() == expected) { "Expected '$expected' at $index" }
        index++
    }

    private fun peek(): Char? = source.getOrNull(index)
}

internal fun extractAllohaVisibleTranslationIds(document: Document): Set<Int> = extractAllohaVisibleSelectorIds(document, "translationType")

internal fun extractAllohaVisibleSeasonIds(document: Document): Set<Int> = extractAllohaVisibleSelectorIds(document, "seasonType")

private fun extractAllohaVisibleSelectorIds(document: Document, selectorPrefix: String): Set<Int> {
    val selectorRoots = document.select("[data-select]")
        .asSequence()
        .filter { element -> element.attr("data-select").lowercase(Locale.ROOT).contains(selectorPrefix.lowercase(Locale.ROOT)) }
        .toList()
    if (selectorRoots.isEmpty()) return emptySet()

    return selectorRoots
        .flatMap { root ->
            root.select("[data-id], option[value]")
                .mapNotNull { element ->
                    val value = element.attr("data-id")
                        .ifBlank { element.attr("value") }
                        .trim()
                    parseAllohaNumericToken(value)
                }
        }
        .toSet()
}

internal fun filterAllohaTracksByVisibleSeasons(
    tracks: List<ParsedAllohaTrack>,
    visibleSeasonIds: Set<Int>,
): List<ParsedAllohaTrack> {
    if (tracks.isEmpty() || visibleSeasonIds.isEmpty()) return tracks
    return tracks.filter { it.season in visibleSeasonIds }
}

internal fun filterAllohaTracksByVisibleTranslations(
    tracks: List<ParsedAllohaTrack>,
    visibleTranslationIds: Set<Int>,
): List<ParsedAllohaTrack> {
    if (tracks.isEmpty() || visibleTranslationIds.isEmpty()) return tracks
    return tracks.filter { it.translationId in visibleTranslationIds }
}

internal fun extractAllohaRequestFullContext(postBody: String): AllohaRequestFullContext? {
    if (postBody.isBlank()) return null
    val params = postBody.split('&')
        .mapNotNull { chunk ->
            val separatorIndex = chunk.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = runCatching {
                URLDecoder.decode(chunk.substring(0, separatorIndex), "UTF-8")
            }.getOrElse { chunk.substring(0, separatorIndex) }
            val value = runCatching {
                URLDecoder.decode(chunk.substring(separatorIndex + 1), "UTF-8")
            }.getOrElse { chunk.substring(separatorIndex + 1) }
            key to value
        }
        .toMap()

    val requestFull = params["request_full"]?.takeIf(String::isNotBlank) ?: return null
    val query = requestFull.substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return null

    val queryParams = query.split('&')
        .mapNotNull { part ->
            val separatorIndex = part.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null
            part.substring(0, separatorIndex) to part.substring(separatorIndex + 1)
        }
        .toMap()

    val season = queryParams["season"]?.toIntOrNull() ?: return null
    val episode = queryParams["episode"]?.toIntOrNull() ?: return null
    val voiceId = queryParams["voice"]?.toIntOrNull()

    return AllohaRequestFullContext(
        season = season,
        episode = episode,
        voiceId = voiceId,
    )
}

internal fun buildAllohaEpisodeReferer(
    baseReferer: String,
    season: Int,
    episode: Int,
    translationId: Int,
): String {
    val normalized = baseReferer.replace("&amp;", "&")
    val fragmentIndex = normalized.indexOf('#')
    val fragment = if (fragmentIndex >= 0) normalized.substring(fragmentIndex) else ""
    val withoutFragment = if (fragmentIndex >= 0) normalized.substring(0, fragmentIndex) else normalized
    val queryIndex = withoutFragment.indexOf('?')
    val basePath = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
    val rawQuery = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""

    val keysToReplace = setOf("season", "episode", "voice", "trbut")
    val preservedParams = rawQuery.split('&')
        .filter(String::isNotBlank)
        .filter { chunk ->
            val rawKey = chunk.substringBefore('=')
            val decodedKey = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            decodedKey !in keysToReplace
        }
        .toMutableList()

    preservedParams += "season=${URLEncoder.encode(season.toString(), "UTF-8")}"
    preservedParams += "episode=${URLEncoder.encode(episode.toString(), "UTF-8")}"
    if (translationId > 0) {
        preservedParams += "voice=${URLEncoder.encode(translationId.toString(), "UTF-8")}"
    }
    preservedParams += "trbut=0"

    val mergedQuery = preservedParams.joinToString("&")
    return if (mergedQuery.isBlank()) {
        "$basePath$fragment"
    } else {
        "$basePath?$mergedQuery$fragment"
    }
}

internal fun isAllohaSpaTokenReferer(referer: String): Boolean {
    val normalized = referer.replace("&amp;", "&")
    return normalized.contains("token_movie=") && normalized.contains("token=")
}

internal fun buildAllohaResolverRequest(
    videoId: String,
    playbackReferer: String,
    headers: Headers = Headers.headersOf(),
): Request? {
    if (videoId.isBlank() || playbackReferer.isBlank()) return null

    val refererUrl = playbackReferer.toHttpUrlOrNull() ?: return null
    val token = refererUrl.queryParameter("token")?.takeIf(String::isNotBlank) ?: return null
    val host = "${refererUrl.scheme}://${refererUrl.host}"
    val requestUrl = "$host/bnsi/movies/$videoId"
    // Чистый Referer (только token_movie, token, season) — как в браузере
    val cleanReferer = normalizeAllohaResolverReferer(playbackReferer) ?: return null
    Log.d(
        "AnimeGO",
        "AnimeGO: bnsi POST url=$requestUrl referer=$cleanReferer token=${token.take(8)}... videoId=$videoId " +
            "body=${buildAllohaRequestBodyPreview(token)}",
    )
    val borthHeader = headers["X-AnimeGO-Alloha-Borth"].orEmpty()
    Log.d(
        "AnimeGO",
        "AnimeGO: bnsi borth present=${borthHeader.isNotBlank()} " +
            "prefix=${borthHeader.take(18)} len=${borthHeader.length}",
    )
    val requestHeaders = headers.newBuilder()
        .set("Referer", cleanReferer)
        .set("Origin", host)
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Accept", "*/*")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("User-Agent", allohaNetworkUserAgent())
        .set("sec-ch-ua", """"Not(A:Brand";v="8", "Chromium";v="144"""")
        .set("sec-ch-ua-mobile", "?0")
        .set("sec-ch-ua-platform", """"Windows"""")
        .set("sec-fetch-dest", "empty")
        .set("sec-fetch-mode", "cors")
        .set("sec-fetch-site", "same-origin")
        .set("sec-fetch-storage-access", "none")
        .set("Cache-Control", "no-cache")
        .set("Pragma", "no-cache")
        .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .apply {
            borthHeader
                .takeIf(String::isNotBlank)
                ?.let { set("Borth", it) }
        }
        .build()
    val body = FormBody.Builder()
        .add("token", token)
        .add("av1", "true")
        .add("autoplay", "0")
        .add("audio", "")
        .add("subtitle", "")
        .build()

    return Request.Builder()
        .url(requestUrl)
        .headers(requestHeaders)
        .cacheControl(CacheControl.Builder().noCache().build())
        .post(body)
        .build()
}

private fun describeAllohaRequestBodyForLogs(request: Request): String {
    val formBody = request.body as? FormBody ?: return ""
    val values = linkedMapOf<String, String>()
    repeat(formBody.size) { index ->
        values[formBody.name(index)] = formBody.value(index)
    }

    val token = values["token"].orEmpty()
    if (values.keys.containsAll(listOf("token", "av1", "autoplay", "audio", "subtitle"))) {
        return buildAllohaRequestBodyPreview(token)
    }

    return values.entries.joinToString("&") { (key, value) ->
        val loggedValue = if (key == "token") redactAllohaDebugToken(value) else value
        "$key=$loggedValue"
    }
}

internal fun buildAllohaRequestBodyPreview(token: String): String = "token=${redactAllohaDebugToken(token)}&av1=true&autoplay=0&audio=&subtitle="

internal fun normalizeAllohaResolverReferer(playbackReferer: String): String? {
    val normalized = playbackReferer.replace("&amp;", "&")
    val refererUri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val rawQuery = refererUri.rawQuery.orEmpty()
    if (rawQuery.isBlank()) return normalized
    val isSpaTokenReferer = isAllohaSpaTokenReferer(normalized)

    val cleanedQuery = rawQuery.split('&')
        .filter(String::isNotBlank)
        .filter { chunk ->
            val rawKey = chunk.substringBefore('=')
            val decodedKey = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            decodedKey != "animego-season" &&
                decodedKey != "animego-episode" &&
                !(isSpaTokenReferer && decodedKey == "voice")
        }
        .joinToString("&")

    return URI(
        refererUri.scheme,
        refererUri.authority,
        refererUri.path,
        cleanedQuery.ifBlank { null },
        refererUri.fragment,
    ).toString()
}

internal fun buildAllohaDebugPreview(body: String, limit: Int = 500): String {
    val normalized = body.replace(AnimegoPatterns.WHITESPACE, " ").trim()
    if (normalized.length <= limit) return normalized
    return normalized.take((limit - 3).coerceAtLeast(0)) + "..."
}

private fun redactAllohaDebugToken(token: String): String {
    if (token.isBlank()) return ""
    return token.take(8) + "..."
}

internal fun extractAllohaBorthSeed(html: String): String? = AnimegoPatterns.BORTH_SEED
    .find(html)
    ?.groupValues
    ?.getOrNull(1)
    ?.takeIf(String::isNotBlank)

internal fun extractAllohaBorthTail(seed: String): String? {
    if (seed.isBlank()) return null
    val bitLengthPermuted = permuteAllohaSeedByBitLength(seed)
    val trailingZeroPermuted = permuteAllohaSeedByTrailingZeroes(bitLengthPermuted)
    return permuteAllohaSeedByPrimeStep(trailingZeroPermuted)
        .takeIf { it.isNotBlank() }
}

internal fun buildAllohaBorthValue(seed: String): String? {
    val tail = extractAllohaBorthTail(seed) ?: return null
    val fingerprint = buildAllohaFingerprintHash()
    return "$fingerprint|$tail"
}

internal fun buildAllohaResolverHeaderMap(
    playbackReferer: String,
    pageReferer: String,
    preferredQuality: String,
    borthSeed: String,
): Map<String, String> = linkedMapOf<String, String>().apply {
    put("Referer", playbackReferer)
    runCatching { playbackReferer.toHttpUrl().host }
        .getOrNull()
        ?.let { host -> put("Origin", "https://$host") }
    put("X-AnimeGO-Resolver", "alloha")
    put("X-AnimeGO-Preferred-Quality", preferredQuality)
    put("X-Requested-With", "XMLHttpRequest")
    if (pageReferer.isNotBlank() && pageReferer != playbackReferer) {
        put("X-AnimeGO-Page-Referer", pageReferer)
    }
    buildAllohaBorthValue(borthSeed)?.let {
        put("X-AnimeGO-Alloha-Borth", it)
    }
}

private fun buildAllohaFingerprintHash(): String {
    val components = buildAllohaFingerprintComponents()
    val payload = components.joinToString("||")
    val hash = sha256Hex(payload)
    Log.d(
        "AnimeGO",
        "AnimeGO: Alloha fingerprint " +
            "ua=${components[0].take(72)} " +
            "tz=${components[1]} " +
            "screen=${components[2]} " +
            "lang=${components[3]} " +
            "mem=${components[4]} " +
            "cores=${components[5]} " +
            "canvas=${components[6].take(18)} " +
            "webgl=${components[7].take(36)} " +
            "audio=${components[8]} " +
            "sha256=${hash.take(24)}",
    )
    return hash
}

private fun buildAllohaFingerprintComponents(): List<String> {
    val userAgent = allohaNetworkUserAgent()
    val timeZone = TimeZone.getDefault().id
    val screen = allohaFingerprintScreen()
    val language = Locale.getDefault().toLanguageTag().ifBlank { "ru-RU" }
    val deviceMemory = estimateAllohaDeviceMemoryBucket(Runtime.getRuntime().maxMemory())
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toString()
    val canvas = allohaFingerprintCanvasSeed(screen, language)
    val webgl = allohaFingerprintWebGlSeed()
    val audio = allohaFingerprintAudioSeed(deviceMemory, cores)
    return listOf(
        userAgent,
        timeZone,
        screen,
        language,
        deviceMemory,
        cores,
        canvas,
        webgl,
        audio,
    )
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun allohaNetworkUserAgent(): String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

internal fun allohaFingerprintScreen(): String = "1920x1080"

internal fun estimateAllohaDeviceMemoryBucket(totalBytes: Long): String {
    val totalGiB = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return when {
        totalGiB < 0.375 -> "0.25"
        totalGiB < 0.75 -> "0.5"
        totalGiB < 1.5 -> "1"
        totalGiB < 3.0 -> "2"
        totalGiB < 6.0 -> "4"
        else -> "8"
    }
}

internal fun allohaFingerprintCanvasSeed(screen: String, language: String): String = "data:image/png;base64,alloha-chrome-$screen-$language-desktop"

internal fun allohaFingerprintWebGlSeed(): String = "ANGLE (NVIDIA, NVIDIA GeForce GTX 1650 Direct3D11 vs_5_0 ps_5_0, D3D11)"

internal fun allohaFingerprintAudioSeed(deviceMemory: String, cores: String): String = "offline-audio:desktop:$deviceMemory:$cores:chrome"

private fun permuteAllohaSeedByBitLength(seed: String): String {
    if (seed.length <= 1) return seed
    val groupCount = computeAllohaGroupCount(seed.length)
    fun groupFor(index: Int): Int {
        if (index == 0) return 0
        var current = index
        var group = 0
        while (current > 0) {
            group++
            current = current ushr 1
        }
        return group
    }
    val counts = IntArray(groupCount + 1)
    repeat(seed.length) { index ->
        counts[groupFor(index)]++
    }
    val chunks = Array(groupCount + 1) { "" }
    var cursor = 0
    for (group in groupCount downTo 0) {
        val next = cursor + counts[group]
        chunks[group] = seed.substring(cursor, next)
        cursor = next
    }
    val offsets = IntArray(groupCount + 1)
    return buildString(seed.length) {
        repeat(seed.length) { index ->
            val group = groupFor(index)
            append(chunks[group][offsets[group]++])
        }
    }
}

private fun permuteAllohaSeedByTrailingZeroes(seed: String): String {
    if (seed.length <= 1) return seed
    val groupCount = computeAllohaGroupCount(seed.length)
    fun groupFor(index: Int): Int {
        if (index == 0) return groupCount
        var current = index
        var group = 0
        while ((current and 1) == 0) {
            group++
            current = current ushr 1
        }
        return group
    }
    val counts = IntArray(groupCount + 1)
    repeat(seed.length) { index ->
        counts[groupFor(index)]++
    }
    val chunks = Array(groupCount + 1) { "" }
    var cursor = 0
    for (group in 0..groupCount) {
        val next = cursor + counts[group]
        chunks[group] = seed.substring(cursor, next)
        cursor = next
    }
    val offsets = IntArray(groupCount + 1)
    val output = CharArray(seed.length)
    repeat(seed.length) { index ->
        val group = groupFor(index)
        output[index] = chunks[group][offsets[group]++]
    }
    return output.concatToString()
}

private fun permuteAllohaSeedByPrimeStep(seed: String): String {
    if (seed.length <= 1) return seed
    val modulo = generateSequence(maxOf(2, seed.length + 1)) { it + 1 }
        .first(::isPrime)
    val order = mutableListOf<Int>()
    val seen = BooleanArray(seed.length)
    var cursor = 0
    while (order.size < seed.length) {
        cursor = (cursor + 2) % modulo
        if (cursor < seed.length && !seen[cursor]) {
            order += cursor
            seen[cursor] = true
        }
    }
    val output = CharArray(seed.length)
    order.forEachIndexed { sourceIndex, targetIndex ->
        output[targetIndex] = seed[sourceIndex]
    }
    return output.concatToString()
}

private fun computeAllohaGroupCount(length: Int): Int {
    var groups = 0
    while ((1 shl groups) < length) {
        groups++
    }
    return groups
}

private fun isPrime(value: Int): Boolean {
    if (value < 2) return false
    if (value % 2 == 0) return value == 2
    var divisor = 3
    while (divisor * divisor <= value) {
        if (value % divisor == 0) return false
        divisor += 2
    }
    return true
}

internal fun extractEpisodeContext(urls: List<String>): Pair<Int, Int>? {
    return urls.firstNotNullOfOrNull { rawUrl ->
        val season = AnimegoPatterns.EPISODE_SEASON
            .find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return@firstNotNullOfOrNull null
        val episode = AnimegoPatterns.EPISODE_NUMBER
            .find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return@firstNotNullOfOrNull null
        season to episode
    }
}

internal fun inferCanonicalSeason(
    pageUrl: String,
    pageTitle: String,
    kodikSeasons: Set<Int>,
    allohaSeasons: Set<Int>,
): Int? {
    detectSeasonFromUrlOrTitle(pageUrl, pageTitle)?.let { return it }

    val singletonHints = listOf(kodikSeasons, allohaSeasons)
        .mapNotNull { seasons -> seasons.singleOrNull() }
    if (singletonHints.isEmpty()) return null

    val distinctHints = singletonHints.distinct()
    return if (distinctHints.size == 1) {
        distinctHints.first()
    } else {
        null
    }
}

private fun detectSeasonFromUrlOrTitle(url: String, title: String): Int? {
    val urlSeason = AnimegoPatterns.URL_SEASON
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (urlSeason != null && urlSeason > 0) return urlSeason

    val titleSeason = AnimegoPatterns.TITLE_SEASON
        .find(title)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        ?.toIntOrNull()
    if (titleSeason != null && titleSeason > 0) return titleSeason

    return null
}

internal fun parseKodikEpisodesBySeason(
    document: Document,
    seasons: List<KodikSeasonOption>,
): Map<Int, List<KodikEpisodeOption>> {
    val seasonBlocks = document.select(".series-options > div")
        .mapNotNull { seasonBlock ->
            val season = seasonBlock.classNames()
                .firstOrNull { it.startsWith("season-") }
                ?.removePrefix("season-")
                ?.toIntOrNull()
                ?: return@mapNotNull null

            val episodes = seasonBlock.select("option")
                .mapNotNull { option -> option.toKodikEpisodeOption() }
            season to episodes
        }
        .toMap()

    if (seasonBlocks.isNotEmpty()) {
        return seasonBlocks
    }

    val fallbackSeason = document.selectFirst(".serial-seasons-box select option[selected]")
        ?.attr("value")
        ?.toIntOrNull()
        ?: seasons.firstOrNull()?.season
        ?: 1

    val fallbackEpisodes = document.select(".serial-series-box select option")
        .mapNotNull { option -> option.toKodikEpisodeOption() }

    return if (fallbackEpisodes.isEmpty()) {
        emptyMap()
    } else {
        mapOf(fallbackSeason to fallbackEpisodes)
    }
}

internal fun extractAllohaSourceUrl(payload: String): String? {
    if (payload.isBlank()) return null
    val encoded = AnimegoPatterns.ALLOHA_SOURCE.find(payload)?.groupValues?.getOrNull(1) ?: return null
    return decodeJsSingleQuotedString(encoded)
        .replace("&amp;", "&")
        .takeIf(String::isNotBlank)
}

internal fun extractAllohaPlaybackUrl(payload: String, preferredQuality: String): String? {
    if (payload.isBlank()) return null
    if (!payload.contains("hlsSource")) {
        return extractAllohaSourceUrl(payload)
    }

    val normalizedQualityKeys = buildList {
        if (preferredQuality.isNotBlank()) {
            add(preferredQuality)
            val stripped = preferredQuality.removeSuffix("p")
            if (stripped != preferredQuality) add(stripped)
        }
        addAll(listOf("1080", "720", "480", "360"))
    }.distinct()

    normalizedQualityKeys.forEach { qualityKey ->
        val encoded = AnimegoPatterns.allohaQualityKey(qualityKey).find(payload)?.groupValues?.getOrNull(1)
            ?: return@forEach
        return decodeJsSingleQuotedString(encoded)
            .replace("&amp;", "&")
            .substringBefore(" or ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    return null
}

private fun extractAllohaMovieId(html: String): String? {
    if (html.isBlank()) return null

    val fileListMatch = AnimegoPatterns.ALLOHA_FILE_LIST_SIMPLE
        .find(html)?.groupValues?.getOrNull(1) ?: return null
    val decoded = decodeJsSingleQuotedString(fileListMatch)
    val activeId = AnimegoPatterns.ALLOHA_ACTIVE_ID
        .find(decoded)?.groupValues?.getOrNull(1)
    if (activeId != null) {
        Log.d("AnimeGO", "AnimeGO: extracted bnsi movieId=$activeId from fileList.active.id")
    }
    return activeId
}

private fun parseAllohaNumericToken(rawToken: String): Int? {
    if (rawToken.isBlank()) return null
    val token = rawToken.trim()
    val direct = token.removePrefix("t").toIntOrNull()
    if (direct != null) return direct
    return AnimegoPatterns.ALLOHA_NUMERIC_TOKEN
        .find(token)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private fun parseAllohaTranslationIdToken(rawToken: String): Int? = parseAllohaNumericToken(rawToken)

internal fun selectCdnFallbackUrl(
    dashUrl: String,
    directSources: Map<String, String>,
    preferredQuality: String,
): String? = selectPreferredQualityValue(directSources, preferredQuality)
    ?: dashUrl.takeIf(String::isNotBlank)

internal fun cdnPlaybackHeaderValues(): Map<String, String> = linkedMapOf(
    "Referer" to "https://player.cdnvideohub.com/",
    "Origin" to "https://player.cdnvideohub.com",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
)

internal fun buildCdnPlaybackHeaders(): Headers {
    val values = cdnPlaybackHeaderValues()
    return Headers.headersOf(
        "Referer",
        values.getValue("Referer"),
        "Origin",
        values.getValue("Origin"),
        "User-Agent",
        values.getValue("User-Agent"),
    )
}

internal fun extractAllohaSeasonEpisodeHint(streamUrl: String): Pair<Int, Int>? {
    if (streamUrl.isBlank()) return null
    val normalized = streamUrl.lowercase(Locale.US)
    val match = AnimegoPatterns.ALLOHA_SEASON_EPISODE
        .find(normalized)
        ?: return null
    val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    if (season <= 0 || episode <= 0) return null
    return season to episode
}

internal fun extractAllohaEpisodeHint(streamUrl: String): Int? {
    if (streamUrl.isBlank()) return null
    extractAllohaSeasonEpisodeHint(streamUrl)?.second?.let { return it }

    val normalized = streamUrl.lowercase(Locale.US)
    val tokenMatch = AnimegoPatterns.ALLOHA_EPISODE_TOKEN
        .find(normalized)
    val episode = tokenMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
    return episode.takeIf { it > 0 }
}

internal fun describeAllohaResolvedHint(streamUrl: String): String {
    val seasonEpisode = extractAllohaSeasonEpisodeHint(streamUrl)
    if (seasonEpisode != null) return "s${seasonEpisode.first}e${seasonEpisode.second}"
    val episode = extractAllohaEpisodeHint(streamUrl)
    return episode?.let { "e$it" } ?: "none"
}

internal fun isAllohaResolvedUrlMismatched(
    expectedSeason: Int,
    expectedEpisode: Int,
    resolvedUrl: String,
): Boolean {
    val seasonEpisode = extractAllohaSeasonEpisodeHint(resolvedUrl)
    if (seasonEpisode != null) {
        return seasonEpisode.first != expectedSeason || seasonEpisode.second != expectedEpisode
    }
    val episode = extractAllohaEpisodeHint(resolvedUrl) ?: return false
    return episode != expectedEpisode
}

internal fun parseCdnHlsQualityMap(manifest: String, baseUrl: String): Map<String, String> {
    val resolutionRegex = AnimegoPatterns.HLS_RESOLUTION
    val bandwidthRegex = AnimegoPatterns.HLS_BANDWIDTH
    val variantPathQualityRegex = AnimegoPatterns.HLS_VARIANT_PATH
    val lines = manifest.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    val basePlaylistUrl = URL(baseUrl)
    val result = linkedMapOf<String, String>()

    lines.forEachIndexed { index, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed

        val nextLine = lines.getOrNull(index + 1)
            ?.takeUnless { it.startsWith("#") }
            ?: return@forEachIndexed

        val qualityValue = resolutionRegex.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: variantPathQualityRegex.find(nextLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: bandwidthRegex.find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { bitrate ->
                    when {
                        bitrate >= 2_000_000 -> 1080
                        bitrate >= 1_000_000 -> 720
                        bitrate >= 650_000 -> 480
                        bitrate >= 350_000 -> 360
                        bitrate >= 200_000 -> 240
                        else -> 144
                    }
                }

        val quality = qualityValue?.let { "${it}p" }
            ?: return@forEachIndexed

        result.putIfAbsent(quality, URL(basePlaylistUrl, nextLine).toString())
    }

    return result
}

internal fun extractFirstCdnMediaSegment(manifest: String, playlistUrl: String): String? {
    val firstSegment = manifest.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return null

    return URL(URL(playlistUrl), firstSegment).toString()
}

internal fun orderCdnQualityFallback(qualities: List<String>, preferredQuality: String): List<String> {
    val sortedQualities = qualities.distinct()
        .sortedByDescending { quality -> quality.removeSuffix("p").toIntOrNull() ?: 0 }

    val preferredRank = preferredQuality.removeSuffix("p").toIntOrNull()
        ?: return sortedQualities

    val preferred = sortedQualities.filter { it.removeSuffix("p").toIntOrNull() == preferredRank }
    if (preferred.isEmpty()) return sortedQualities

    val lower = sortedQualities.filter { (it.removeSuffix("p").toIntOrNull() ?: Int.MAX_VALUE) < preferredRank }
    val higher = sortedQualities.filter { (it.removeSuffix("p").toIntOrNull() ?: Int.MIN_VALUE) > preferredRank }
    return preferred + lower + higher
}

internal fun <T> selectPreferredQualityValue(values: Map<String, T>, preferredQuality: String): T? {
    if (values.isEmpty()) return null
    values[preferredQuality]?.let { return it }
    return values.entries
        .sortedByDescending { (quality, _) -> quality.removeSuffix("p").toIntOrNull() ?: 0 }
        .firstOrNull()
        ?.value
}
