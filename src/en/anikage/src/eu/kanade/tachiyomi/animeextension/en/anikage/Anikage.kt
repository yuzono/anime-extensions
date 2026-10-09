package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class Anikage : Source() {

    override val name = "Anikage"

    override val baseUrl = "https://anikage.cc"

    private val apiUrl = "$baseUrl/api/media"

    override val lang = "en"

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    override val client = network.client.newBuilder()
        .rateLimit(4)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    private val preferredType: String
        by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)

    private val preferredQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override val migration: SharedPreferences.() -> Unit = {
        val oldType = getString("sub_or_dub", null)
        if (oldType != null && !contains(PREF_TYPE_KEY)) {
            edit().putString(PREF_TYPE_KEY, if (oldType.equals("dub", true)) "DUB" else "SUB").apply()
        }
        val oldAdult = getBoolean("is_adult", false)
        if (contains("is_adult") && !contains(PREF_ADULT_KEY)) {
            edit().putBoolean(PREF_ADULT_KEY, oldAdult).apply()
        }
        val oldQuality = getString("quality", null)
        if (oldQuality != null && !contains(PREF_QUALITY_KEY)) {
            edit().putString(PREF_QUALITY_KEY, oldQuality).apply()
        }
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(page, sort = "popularity")

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(page, sort = "updated")

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = filters.filterIsInstance<Filters.SortFilter>().firstOrNull()?.toUriPart()
            ?.takeIf { it.isNotEmpty() } ?: if (query.isBlank()) "popularity" else "score"
        val format = filters.filterIsInstance<Filters.FormatFilter>().firstOrNull()?.toUriPart().orEmpty()
        val status = filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        val season = filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<Filters.YearFilter>().firstOrNull()?.state?.trim().orEmpty()
        val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getIncluded().orEmpty()

        return browse(
            page = page,
            sort = sort,
            query = query,
            format = format,
            status = status,
            season = season,
            year = year,
            genres = genres,
        )
    }

    override fun getFilterList(): AnimeFilterList = Filters.build()

    private suspend fun browse(
        page: Int,
        sort: String,
        query: String = "",
        format: String = "",
        status: String = "",
        season: String = "",
        year: String = "",
        genres: List<String> = emptyList(),
    ): AnimesPage {
        val showAdult = preferences.getBoolean(PREF_ADULT_KEY, false)
        val url = "$apiUrl/anime/browse".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "25")
            addQueryParameter("adult", showAdult.toString())
            if (format.isNotEmpty()) addQueryParameter("format", format)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            if (season.isNotEmpty()) addQueryParameter("season", season)
            if (year.isNotEmpty()) {
                addQueryParameter("yearMin", year)
                addQueryParameter("yearMax", year)
            }
            if (genres.isNotEmpty()) addQueryParameter("genres", genres.joinToString(","))
        }.build()

        val response = client.get(url, headers)
        val dto = response.parseAs<BrowseResponseDto>()
        val animes = dto.data.map { it.toSAnime() }
        return AnimesPage(animes, dto.hasNext)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/info/${extractSlug(anime.url)}"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = extractSlug(anime.url)
        val response = client.get("$apiUrl/anime/$slug", headers)
        val dto = response.parseAs<DetailsResponseDto>()
        return (dto.anime ?: AnimeItemDto(slug = slug)).toSAnime().apply {
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = extractSlug(anime.url)
        val response = client.get("$apiUrl/anime/$slug/episodes", headers)
        return response.parseAs<List<EpisodeDto>>()
            .map { it.toSEpisode(slug) }
            .sortedByDescending { it.episode_number }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (slug, epNum) = parseEpisodeUrl(episode.url)
        return "$baseUrl/watch/$slug/$epNum"
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (slug, epNum) = parseEpisodeUrl(episode.url)

        val serverData = client.get(
            "$apiUrl/anime/$slug/episodes/$epNum/servers",
            headers,
        ).parseAs<ServersResponseDto>()

        val fetched = serverData.servers.parallelCatchingMapNotNull { server ->
            val serverId = server.id ?: return@parallelCatchingMapNotNull null
            val byLang = server.subTypes.parallelCatchingMapNotNull { lang ->
                val dto = client.get(
                    "$apiUrl/anime/$slug/episodes/$epNum/sources?provider=$serverId&lang=$lang&server=$serverId",
                    headers,
                ).parseAs<SourcesResponseDto>()
                lang to dto
            }
            if (byLang.isEmpty()) null else serverId to byLang
        }

        val refererByHost = mutableMapOf<String, String>()
        fetched.forEach { (_, byLang) ->
            byLang.forEach { (_, dto) ->
                dto.sources.forEach { src ->
                    val decoded = decodeStreamToken(src.url) ?: return@forEach
                    if (decoded.referer.isEmpty()) return@forEach
                    val host = decoded.url.hostOrEmpty()
                    if (host.isNotEmpty() && host !in refererByHost) {
                        refererByHost[host] = decoded.referer
                    }
                }
            }
        }

        return fetched.mapNotNull { (serverId, byLang) ->
            val entries = byLang.flatMap { (lang, dto) -> dto.toStreamEntries(lang, refererByHost) }
            if (entries.isEmpty()) return@mapNotNull null
            Hoster(
                hosterName = serverId.replaceFirstChar { it.uppercase() },
                hosterUrl = entries.toJsonString(json),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val entries = runCatching { hoster.hosterUrl.parseAs<List<StreamEntry>>(json) }
            .getOrNull() ?: return emptyList()

        val videos = entries
            .parallelCatchingFlatMap { entry -> resolveEntry(hoster.hosterName, entry) }
            .distinctBy { it.videoUrl }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredType, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    private suspend fun resolveEntry(providerLabel: String, entry: StreamEntry): List<Video> {
        val prefix = listOf(providerLabel, entry.lang, entry.label)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val tracks = entry.subtitles.map { Track(it.url, it.label) }
        val candidates = entry.refererCandidates()

        if (entry.url.isNotBlank()) {
            // `megg` hands out a progressive MP4 instead of a playlist.
            if (!entry.isM3U8) {
                return listOf(
                    Video(
                        videoUrl = entry.url,
                        videoTitle = prefix,
                        headers = refererHeaders(candidates.first()),
                        subtitleTracks = tracks,
                    ),
                )
            }

            // Most CDNs here reject a wrong Referer, and a handful of sources ship none at
            // all, so walk the candidates until one actually returns a playlist.
            candidates.forEach { referer ->
                val streamHeaders = refererHeaders(referer)
                val videos = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = entry.url,
                        referer = referer,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        subtitleList = tracks,
                        videoNameGen = { quality -> "$prefix - $quality" },
                    )
                }.getOrDefault(emptyList())
                if (videos.isNotEmpty()) {
                    return if (entry.url.needsLocalProxy()) videos.viaLocalProxy(referer) else videos
                }
            }
        }

        // Safety net in case the token key is rotated: scrape the embed page like before.
        return when {
            entry.embedUrl.isEmpty() -> emptyList()

            "megaplay.buzz" in entry.embedUrl || "vidtube.site" in entry.embedUrl ->
                megaPlayVideos(prefix, entry.embedUrl)

            else -> vibePlayerVideos(prefix, entry.embedUrl)
        }
    }

    /**
     * Re-serves already-resolved media playlists through the bundled local HLS proxy.
     *
     * Two hosts cannot be handed to the player directly. `vivibebe.site` keeps its segments
     * on an image CDN that only answers when the Referer is re-issued on every segment
     * request, and `echovideo.to` returns each playlist as `image/jpeg` from an
     * extension-less URL, so the player never recognises it as HLS in the first place. The
     * proxy re-fetches with our headers, strips any fake image header off the segments, and
     * serves both playlist and segments under the content types the player expects.
     */
    private fun List<Video>.viaLocalProxy(referer: String): List<Video> {
        if (isEmpty()) return this
        runCatching { if (!m3u8Server.isRunning()) m3u8Server.startServer() }
        if (!m3u8Server.isRunning()) return this

        val userAgent = headers["User-Agent"]
        return map { video ->
            val proxied = runCatching {
                m3u8Server.processM3u8Url(video.videoUrl, referer.takeIf { it.isNotBlank() }, userAgent)
            }.getOrNull() ?: return@map video

            Video(
                videoUrl = proxied,
                videoTitle = video.videoTitle,
                headers = video.headers,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }
    }

    private fun refererHeaders(referer: String): Headers = headers.newBuilder()
        .apply { if (referer.isBlank()) removeAll("Referer") else set("Referer", referer) }
        .build()

    /**
     * Resolves VibePlayer-style embeds (vivibebe.site, bibiemb.xyz, ...).
     * The player page exposes a plain master playlist in its source:
     * `const src = "https://<host>/public/stream/<id>/master.m3u8"`
     * plus an optional external subtitle passed through the `?sub=` query parameter.
     */
    private suspend fun vibePlayerVideos(label: String, embedUrl: String): List<Video> {
        val embedHost = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val html = client.get(embedUrl, refererHeaders("$baseUrl/")).bodyString()

        val masterUrl = VIBE_SRC_REGEX.find(html)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitles = embedUrl.toHttpUrl().queryParameter("sub")
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(Track(it, "English")) }
            ?: emptyList()

        val streamHeaders = refererHeaders("$embedHost/")

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = "$embedHost/",
            masterHeaders = streamHeaders,
            videoHeaders = streamHeaders,
            subtitleList = subtitles,
            videoNameGen = { quality -> "$label - $quality" },
        )
    }

    private suspend fun megaPlayVideos(label: String, embedUrl: String): List<Video> {
        val embedHost = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val html = client.get(embedUrl, refererHeaders("$baseUrl/")).bodyString()

        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()

        val apiHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val data = client.get("$embedHost/stream/getSources?id=$dataId", apiHeaders)
            .parseAs<MegaPlaySourcesDto>()

        val masterUrl = data.sources?.file ?: return emptyList()
        val subtitles = data.tracks
            .filter { it.kind == "captions" && !it.file.isNullOrBlank() }
            .map { Track(it.file!!, it.label ?: "Subtitle") }

        val streamHeaders = refererHeaders("$embedHost/")

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "$embedHost/",
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
                subtitleList = subtitles,
                videoNameGen = { quality -> "$label - $quality" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = masterUrl,
                    videoTitle = "$label - Auto",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    private fun extractSlug(url: String): String = url.removeSuffix("/").substringAfterLast("/")

    private fun parseEpisodeUrl(url: String): Pair<String, String> {
        if (url.contains("/episodes/")) {
            val slug = url.substringAfter("/anime/").substringBefore("/episodes/")
            val epNum = url.substringAfter("/episodes/").substringBefore("/sources").substringBefore("/")
            return slug to epNum
        }
        val slug = url.substringBefore("#").removeSuffix("/").substringAfterLast("/")
        val epNum = url.substringAfter("#ep=", "1")
        return slug to epNum
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred audio type",
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
            default = PREF_TYPE_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "360p"),
            entryValues = listOf("1080p", "720p", "360p"),
            default = PREF_QUALITY_DEFAULT,
        )
        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Show adult content",
            summary = "Include 18+ titles in browse and search results.",
            default = false,
        )
    }

    companion object {
        private val DATA_ID_REGEX by lazy { Regex("""data-id=["']?(\d+)""") }
        private val VIBE_SRC_REGEX by lazy { Regex("""const\s+src\s*=\s*"([^"]+\.m3u8[^"]*)"""") }
        private val M3U8_REGEX by lazy { Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""") }

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "SUB"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_ADULT_KEY = "show_adult"

        private val PROXY_HOSTS = setOf("vivibebe.site", "echovideo.to", "krussdomi.com")

        private fun String.needsLocalProxy(): Boolean {
            val host = hostOrEmpty()
            return PROXY_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }
}
