package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import android.content.SharedPreferences
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.AgeFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.FeaturedFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.GenresFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.QualityFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.ScoreFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.ServiceFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.SortFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.YearFilter
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.i18n.Intl
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class StreamingCommunity(override val lang: String, private val showType: String) :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "StreamingUnity (${showType.replaceFirstChar { it.uppercaseChar() }})"

    private val preferences by getPreferencesLazy()

    private var SharedPreferences.customDomain by preferences.delegate(PREF_CUSTOM_DOMAIN_KEY, DOMAIN_DEFAULT)

    private var homepage by LazyMutable { preferences.customDomain.ifBlank { DOMAIN_DEFAULT }.sanitizeDomain() }

    override val client: OkHttpClient = super.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val maxRedirects = 5
            var request = chain.request()
            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect && redirectCount < maxRedirects) {
                val newUrl = response.header("Location") ?: break
                val newUrlHttp = request.url.resolve(newUrl) ?: break
                val redirectedDomain = newUrlHttp.run { "$scheme://$host" }
                if (redirectedDomain != homepage) {
                    updateDomain(redirectedDomain)
                }
                response.close()
                request = request.newBuilder()
                    .url(newUrlHttp)
                    .apply {
                        apiHeaders["Origin"]?.let { header("Origin", it) }
                        apiHeaders["Referer"]?.let { header("Referer", it) }
                    }
                    .build()
                response = chain.proceed(request)
                redirectCount++
            }
            if (redirectCount >= maxRedirects) {
                response.close()
                throw java.io.IOException("Too many redirects: $maxRedirects")
            }
            response
        }.build()

    override val baseUrl: String
        get() = "$homepage/$lang"

    override val supportsLatest = true

    private val intl = Intl(
        language = Locale(lang).language,
        baseLanguage = "en",
        availableLanguages = setOf("en", "it"),
        classLoader = this::class.java.classLoader!!,
    )

    private val apiHeadersRef by lazy { AtomicReference(newApiHeader()) }
    private fun newApiHeader() = headers.newBuilder()
        .add("Origin", homepage)
        .add("Referer", "$homepage/")
        .build()

    private var apiHeaders: Headers
        get() = apiHeadersRef.get()
        set(value) = apiHeadersRef.set(value)

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = when (page) {
        1 -> GET("$baseUrl/browse/top10?type=$showType", apiHeaders)

        2 -> GET("$baseUrl/browse/trending?type=$showType", apiHeaders)

        else ->
            GET("$baseUrl/archive?type=$showType&sort=views&page=${page - 2}", apiHeaders)
    }

    private var imageCdn = "https://cdn.${baseUrl.toHttpUrl().host}/images/"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = parseBrowse(response)
        return AnimesPage(animeList, browseHasNextPage(response, animeList.size))
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse/latest?type=$showType&page=$page", apiHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<GenresFilter>().firstOrNull()
        val featuredFilter = filters.filterIsInstance<FeaturedFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val yearFilter = filters.filterIsInstance<YearFilter>().firstOrNull()
        val scoreFilter = filters.filterIsInstance<ScoreFilter>().firstOrNull()
        val serviceFilter = filters.filterIsInstance<ServiceFilter>().firstOrNull()
        val qualityFilter = filters.filterIsInstance<QualityFilter>().firstOrNull()
        val ageFilter = filters.filterIsInstance<AgeFilter>().firstOrNull()

        val httpUrlBuilder = baseUrl.toHttpUrl().newBuilder()
        httpUrlBuilder.apply {
            addPathSegment("archive")
            addQueryParameter("search", query)
            if (sortFilter?.isDefault() == false) {
                addQueryParameter(sortFilter.uri, sortFilter.toUriPart())
            }
            if (yearFilter?.isDefault() == false) {
                addQueryParameter(yearFilter.uri, yearFilter.toUriPart())
            }
            if (scoreFilter?.isDefault() == false) {
                addQueryParameter(scoreFilter.uri, scoreFilter.toUriPart())
            }
            if (serviceFilter?.isDefault() == false) {
                addQueryParameter(serviceFilter.uri, serviceFilter.toUriPart())
            }
            if (qualityFilter?.isDefault() == false) {
                addQueryParameter(qualityFilter.uri, qualityFilter.toUriPart())
            }
            if (ageFilter?.isDefault() == false) {
                addQueryParameter(ageFilter.uri, ageFilter.toUriPart())
            }
        }

        genresFilter?.addToUri(httpUrlBuilder)

        if (featuredFilter?.isDefault() == false) {
            httpUrlBuilder.addQueryParameter(featuredFilter.uri, featuredFilter.toUriPart())
        }

        httpUrlBuilder.apply {
            addQueryParameter("type", showType)
            addQueryParameter("page", page.toString())
        }

        return GET(httpUrlBuilder.build(), apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animeList = parseBrowse(response)
        return AnimesPage(animeList, browseHasNextPage(response, animeList.size))
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/titles/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val title = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        ).props.title
            ?: error("Anime details parsing error: title is null.")

        return title.toSAnimeUpdate(intl)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val sliders = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        ).props.sliders

        return sliders?.flatMap { slider -> slider.titles.map { it.toSAnime(imageCdn) } } ?: emptyList()
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val parsed = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        )
        val data = parsed.props
        val episodeList = mutableListOf<SEpisode>()

        if (data.title == null) return emptyList()

        if (data.loadedSeason == null) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Film"
                    url = data.title.id.toString()
                    date_upload = with(data.title) {
                        (release_date ?: last_air_date)?.let(::parseDate)
                            ?: (created_at ?: updated_at)?.let(::parseDateTime)
                            ?: 0L
                    }
                },
            )
        } else {
            val seasonIntl = intl["season"]
            val episodeIntl = intl["episode"]

            // Concurrently fetch episode data for all seasons
            val allSeasonEpisodes = runBlocking {
                // Bridge to coroutine world
                coroutineScope {
                    // Create a scope for structured concurrency
                    data.title.seasons.map { season ->
                        async {
                            // Launch each season fetch asynchronously
                            val episodes = if (season.id == data.loadedSeason.id) {
                                data.loadedSeason.episodes
                            } else {
                                val seasonResponse = client.newCall(
                                    GET("${response.request.url}/season-${season.number}", apiHeaders),
                                ).awaitSuccess() // Suspend call for network request
                                json.decodeFromString<SingleShowResponse>(seasonResponse.getData()).props.loadedSeason?.episodes
                                    ?: emptyList()
                            }
                            Pair(season, episodes) // Return season object and its episodes
                        }
                    }.awaitAll() // Wait for all async operations to complete
                }
            }

            // Process the fetched data
            allSeasonEpisodes.forEach { (season, episodeData) ->
                episodeData.forEach { episode ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "$seasonIntl ${season.number} $episodeIntl ${episode.number} - ${episode.name}"
                            url = "${data.title.id}?episode_id=${episode.id}&next_episode=1"
                            date_upload = season.release_date?.let(::parseDate)
                                ?: (episode.created_at ?: episode.updated_at)?.let(::parseDateTime)
                                ?: 0L
                        },
                    )
                }
            }
        }

        val episodes = episodeList
            .mapIndexed { index, episode ->
                episode.apply {
                    episode_number = (index + 1).toFloat()
                }
            }
            .reversed()

        return data.title.preview?.let {
            episodes +
                SEpisode.create().apply {
                    name = "Preview"
                    episode_number = 0F
                    url = it.embed_url
                }
        } ?: episodes
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url
        return when {
            url.startsWith("https://") -> vixCloudExtractor(url)

            else -> {
                val iframeUrl = client.newCall(
                    GET("$baseUrl/iframe/$url", headers),
                ).awaitSuccess().use {
                    it.asJsoup()
                        .selectFirst("iframe[src]")?.attr("abs:src")
                        ?: error("Failed to extract iframe")
                }
                vixCloudExtractor(iframeUrl)
            }
        }
    }

    // https://vixcloud.co/embed/262817?token=321fd9c4f94fcd28b522d1f3ba2a8d77&expires=1752778149&canPlayFHD=1&canBypassAds=1
    private suspend fun vixCloudExtractor(iframeUrl: String): List<Video> {
        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", iframeUrl.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .build()

        val iframe = client.newCall(GET(iframeUrl, iframeHeaders)).awaitSuccess().asJsoup()
        val script = iframe.selectFirst("script:containsData(masterPlaylist)")?.data()
            ?: error("Failed to extract masterPlaylist script")
        val playlistUrl = PLAYLIST_URL_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract playlist URL")
        val token = TOKEN_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract token")
        val expires = EXPIRES_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract expires")

        val masterPlUrl = buildString {
            append(playlistUrl)
            append(if (playlistUrl.contains('?')) '&' else '?')
            append("h=1&token=")
            append(token)
            append("&expires=")
            append(expires)
            append("&lang=")
            append(lang)
        }

        // The player lists its mirror servers in `window.streams`; every mirror's
        // master playlist carries the SAME renditions and subtitle/audio lists
        // (verified live: 38 SUBTITLES entries on each). Merging mirrors used to
        // duplicate every subtitle track (~76 tracks for ~38 real ones) and
        // double the work. Resolve all mirrors concurrently for resilience — a
        // failing edge (expired token / 5xx / empty result) falls through to the
        // next one — but consume ONLY the first successful extraction. Fall back
        // to the plain master playlist when the array isn't there. Subtitle/audio
        // renditions are regex-parsed out of each master playlist and are never
        // fetched here, so nothing per-track needs parallelizing.
        val serverPlaylists = SERVERS_REGEX.findAll(script)
            .map { match ->
                match.groupValues[1] to buildMasterPlaylistUrl(match.groupValues[2].unescapeJs(), token, expires)
            }
            .ifEmpty { sequenceOf("" to masterPlUrl) }
            .toList()

        return coroutineScope {
            serverPlaylists.map { (_, serverPlaylistUrl) ->
                async {
                    runCatching {
                        playlistUtils.extractFromHls(playlistUrl = serverPlaylistUrl)
                            .takeIf { it.isNotEmpty() }
                    }.getOrNull()
                }
            }.awaitAll()
                .firstOrNull { !it.isNullOrEmpty() }
                ?: runCatching { playlistUtils.extractFromHls(playlistUrl = masterPlUrl) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    .orEmpty()
        }.proxySubtitles()
    }

    /**
     * Routes every subtitle track through [SubtitleServer] - see that class for
     * why the upstream rendition URLs are slow to open. Audio tracks keep their
     * upstream URLs, since the player streams those as the HLS renditions they
     * already are, but are still deduplicated: a title whose mirrors advertise
     * the same rendition list would otherwise list each language twice.
     */
    private fun List<Video>.proxySubtitles(): List<Video> = map { video ->
        video.copy(
            subtitleTracks = SubtitleServer.proxy(client, video.subtitleTracks, video.headers ?: headers),
            audioTracks = video.audioTracks.distinctBy(Track::url),
        )
    }

    private fun buildMasterPlaylistUrl(base: String, token: String, expires: String) = base + (if ('?' in base) '&' else '?') + "h=1&token=$token&expires=$expires&lang=$lang"

    private fun String.unescapeJs() = replace("\\/", "/").replace("\\u0026", "&")

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun parseBrowse(response: Response): List<SAnime> = json.decodeFromString<BrowseResponse>(response.getData()).props.also { props ->
        props.cdn_url?.takeIf { it.isNotBlank() }?.let { imageCdn = "$it/images/" }
    }.titles.map { it.toSAnime(imageCdn) }

    private fun browseHasNextPage(response: Response, size: Int): Boolean {
        if (size < PAGE_SIZE) return false
        val url = response.request.url
        return when {
            url.encodedPath.contains("/browse/top10") || url.encodedPath.contains("/browse/trending") -> true
            // The site serves at most MAX_ARCHIVE_PAGES archive pages and answers 503 beyond that.
            url.encodedPath.endsWith("/archive") -> (url.queryParameter("page")?.toIntOrNull() ?: 1) < MAX_ARCHIVE_PAGES
            else -> true
        }
    }

    private fun Response.getData(): String = if (headers["content-type"]?.contains("application/json") == true) {
        body.string()
    } else {
        asJsoup().selectFirst("div#app[data-page]")
            ?.attr("data-page")
            ?.replace("&quot;", "\"")
            ?: error("Failed to extract data-page")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains("${quality}p") },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val DOMAIN_DEFAULT = "https://streamingunity.vip"
        private const val PREF_CUSTOM_DOMAIN_KEY = "custom_domain_v${BuildConfig.VERSION_NAME}"
        private const val TAG = "StreamingCommunity"
        private const val PAGE_SIZE = 60
        private const val MAX_ARCHIVE_PAGES = 20

        private val PLAYLIST_URL_REGEX = Regex("""url: ?'(.*?)'""")
        private val EXPIRES_REGEX = Regex("""'expires': ?'(\d+)'""")
        private val TOKEN_REGEX = Regex("""'token': ?'([\w-]+)'""")

        // window.streams = [{"name":"Server1","active":false,"url":"https:\/\/vixcloud.co\/playlist\/174559?b=1\u0026ub=1"}, …]
        private val SERVERS_REGEX = Regex("""\{"name":"([^"]+)","active":[a-z]+,"url":"([^"]+)"\}""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()

        private val DATE_TIME_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        internal fun parseStatus(statusString: String?): Int = when (statusString) {
            "Ended" -> SAnime.COMPLETED
            "Released" -> SAnime.COMPLETED
            "Returning Series" -> SAnime.ONGOING
            "Canceled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        private fun parseDateTime(dateStr: String): Long = runCatching { DATE_TIME_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L

        private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // ============================== Settings ==============================

    private fun String.sanitizeDomain() = trim().removeSuffix("/").ifBlank { DOMAIN_DEFAULT }

    private fun updateDomain(domain: String) {
        val newDomain = domain.sanitizeDomain()
        if (URLUtil.isValidUrl(newDomain)) {
            Log.i(TAG, "Updating domain to: $newDomain")
            preferences.customDomain = newDomain
            homepage = newDomain
            apiHeaders = newApiHeader()
        }
    }

    val addressUrlSummary: (String) -> String = {
        it.ifBlank { DOMAIN_DEFAULT }
            .let { domain -> "Current domain: \"${domain}\"\nLeave blank to reset to default domain." }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_CUSTOM_DOMAIN_KEY,
            title = "Custom domain",
            default = DOMAIN_DEFAULT,
            summary = addressUrlSummary(homepage),
            getSummary = addressUrlSummary,
            onChange = { _, newValue ->
                val newDomain = newValue.trim().removeSuffix("/")
                if (newDomain.isBlank() || URLUtil.isValidUrl(newDomain)) {
                    updateDomain(newDomain)
                    // this `true` will update the preference to empty string if the new value is blank &
                    // override domain set in `updateDomain`, so make sure to guard `homepage` against blank values.
                    // But it's needed to update the preference summary.
                    true
                } else {
                    Toast.makeText(screen.context, "Invalid URL. Example: $DOMAIN_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            },
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenresFilter(intl),
        SortFilter(intl),
        ScoreFilter(intl),
        YearFilter(intl),
        ServiceFilter(intl),
        QualityFilter(intl),
        AgeFilter(intl),
        AnimeFilter.Separator(),
        FeaturedFilter(intl),
    )
}
