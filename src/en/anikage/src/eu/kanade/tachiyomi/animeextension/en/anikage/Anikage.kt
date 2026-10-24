package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class Anikage : Source() {

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

    override val name = "Anikage"

    override val baseUrl = "https://anikage.cc"

    private val apiUrl = "$baseUrl/api/media"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(4)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val titleStyle: String
        by preferences.delegate(PREF_TITLE_KEY, PREF_TITLE_DEFAULT)

    private val preferredQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val preferredType: String
        by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)

    private val forceTsArgs = listOf("demuxer-lavf-o" to "force_mpegts=1")
    private val forceFfmpegArgs = listOf("force_mpegts" to "1")

    init {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Filters.fetchFilters(client, baseUrl, preferences, headers)
            }
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
        val tags = filters.filterIsInstance<Filters.TagFilter>().firstOrNull()?.getIncluded().orEmpty()

        return browse(
            page = page,
            sort = sort,
            query = query,
            format = format,
            status = status,
            season = season,
            year = year,
            genres = genres,
            tags = tags,
        )
    }

    override fun getFilterList(): AnimeFilterList = Filters.build(preferences)

    private suspend fun browse(
        page: Int,
        sort: String,
        query: String = "",
        format: String = "",
        status: String = "",
        season: String = "",
        year: String = "",
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
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
            if (tags.isNotEmpty()) addQueryParameter("tags", tags.joinToString(","))
        }.build()

        val response = client.get(url, headers)
        val dto = response.parseAs<BrowseResponseDto>()
        val animes = dto.data.map { it.toSAnime(titleStyle) }
        return AnimesPage(animes, dto.hasNext)
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/info/${extractSlug(anime.url)}"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = extractSlug(anime.url)
        val response = client.get("$apiUrl/anime/$slug", headers)
        val dto = response.parseAs<DetailsResponseDto>()
        return (dto.anime ?: AnimeItemDto(slug = slug)).toSAnime(titleStyle).apply {
            initialized = true
        }
    }

    // =========================== Related Anime ============================

    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val slug = extractSlug(anime.url)
        val response = client.get("$apiUrl/anime/$slug", headers)
        val dto = response.parseAs<DetailsResponseDto>()
        val info = dto.anime ?: return emptyList()

        return buildList {
            info.relations?.forEach { rel ->
                val relSlug = rel.slug?.takeIf { it.isNotBlank() } ?: return@forEach
                if (relSlug == slug || any { it.url == "/anime/info/$relSlug" }) return@forEach
                val title = rel.title?.best(titleStyle)?.takeIf { it.isNotBlank() } ?: return@forEach
                add(
                    SAnime.create().apply {
                        url = "/anime/info/$relSlug"
                        this.title = title
                        thumbnail_url = rel.coverImage ?: rel.bannerImage
                        status = when (rel.status?.uppercase()) {
                            "RELEASING" -> SAnime.ONGOING
                            "FINISHED" -> SAnime.COMPLETED
                            "NOT_YET_RELEASED" -> SAnime.LICENSED
                            "HIATUS" -> SAnime.ON_HIATUS
                            "CANCELLED" -> SAnime.CANCELLED
                            else -> SAnime.UNKNOWN
                        }
                        genre = listOfNotNull(rel.format, rel.relationType).joinToString()
                    },
                )
            }

            info.recommendations?.forEach { rec ->
                val recSlug = rec.slug?.takeIf { it.isNotBlank() } ?: return@forEach
                if (recSlug == slug || any { it.url == "/anime/info/$recSlug" }) return@forEach
                val title = rec.title?.best(titleStyle)?.takeIf { it.isNotBlank() } ?: return@forEach
                add(
                    SAnime.create().apply {
                        url = "/anime/info/$recSlug"
                        this.title = title
                        thumbnail_url = rec.coverImage ?: rec.bannerImage
                        status = when (rec.status?.uppercase()) {
                            "RELEASING" -> SAnime.ONGOING
                            "FINISHED" -> SAnime.COMPLETED
                            "NOT_YET_RELEASED" -> SAnime.LICENSED
                            "HIATUS" -> SAnime.ON_HIATUS
                            "CANCELLED" -> SAnime.CANCELLED
                            else -> SAnime.UNKNOWN
                        }
                        genre = rec.format
                    },
                )
            }
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
        return "$baseUrl/anime/watch/$slug?ep=$epNum"
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
                    return videos.map { video ->
                        video.copy(
                            mpvArgs = forceTsArgs,
                            ffmpegStreamArgs = forceFfmpegArgs,
                        )
                    }
                }
            }
        }

        // Safety net in case the token key is rotated: scrape the embed page like before.
        val fallbackVideos = when {
            entry.embedUrl.isEmpty() -> emptyList()

            "megaplay.buzz" in entry.embedUrl || "vidtube.site" in entry.embedUrl ->
                megaPlayVideos(prefix, entry.embedUrl)

            else -> vibePlayerVideos(prefix, entry.embedUrl)
        }
        return fallbackVideos.map { video ->
            video.copy(
                mpvArgs = forceTsArgs,
                ffmpegStreamArgs = forceFfmpegArgs,
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

    private fun extractSlug(url: String): String = if (url.contains("/episodes/")) {
        url.substringAfter("/anime/").substringBefore("/episodes/")
    } else {
        url.substringBefore("#").substringBefore("?").removeSuffix("/").substringAfterLast("/")
    }

    private fun parseEpisodeUrl(url: String): Pair<String, String> {
        if (url.contains("/episodes/")) {
            val slug = url.substringAfter("/anime/").substringBefore("/episodes/")
            val epNum = url.substringAfter("/episodes/").substringBefore("/sources").substringBefore("/servers").substringBefore("/")
            return slug to epNum
        }
        val slug = url.substringBefore("#").substringBefore("?").removeSuffix("/").substringAfterLast("/")
        val epNum = when {
            url.contains("#ep=") -> url.substringAfter("#ep=").substringBefore("&")
            url.contains("ep=") -> url.substringAfter("ep=").substringBefore("&")
            else -> "1"
        }
        return slug to epNum
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TITLE_KEY,
            title = "Preferred Title Style",
            summary = "%s",
            entries = listOf("English", "Romaji"),
            entryValues = listOf("english", "romaji"),
            default = PREF_TITLE_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "360p"),
            entryValues = listOf("1080p", "720p", "360p"),
            default = PREF_QUALITY_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred audio type",
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
            default = PREF_TYPE_DEFAULT,
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

        private const val PREF_TITLE_KEY = "title_format"
        private const val PREF_TITLE_DEFAULT = "english"

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "SUB"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_ADULT_KEY = "show_adult"
    }
}
