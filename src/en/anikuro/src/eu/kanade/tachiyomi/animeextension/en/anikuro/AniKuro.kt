package eu.kanade.tachiyomi.animeextension.en.anikuro

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class AniKuro :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniKuro"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val forceTsArgs = listOf("demuxer-lavf-o" to "force_mpegts=1")
    private val forceFfmpegArgs = listOf("force_mpegts" to "1")

    override val client = network.client.newBuilder()
        .rateLimit(3) { it.host.contains("anikuro") }
        .build()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/discovery/search?sort=POPULARITY_DESC&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeSearchPage(response)

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/discovery/search?sort=START_DATE_DESC&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeSearchPage(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sortVal = ""
        var formatVal = ""
        var statusVal = ""
        var seasonVal = ""
        var originVal = ""
        var yearVal = ""
        var minScoreVal = ""
        var genresVal = emptyList<String>()
        var excludeGenresVal = emptyList<String>()
        var tagsVal = emptyList<String>()
        var excludeTagsVal = emptyList<String>()

        filters.forEach { filter ->
            when (filter) {
                is Filters.SortFilter -> {
                    if (!filter.isDefault()) {
                        sortVal = filter.selectedValue()
                    }
                }

                is Filters.FormatFilter -> {
                    if (!filter.isDefault()) {
                        formatVal = filter.selectedValue()
                    }
                }

                is Filters.StatusFilter -> {
                    if (!filter.isDefault()) {
                        statusVal = filter.selectedValue()
                    }
                }

                is Filters.SeasonFilter -> {
                    if (!filter.isDefault()) {
                        seasonVal = filter.selectedValue()
                    }
                }

                is Filters.OriginFilter -> {
                    if (!filter.isDefault()) {
                        originVal = filter.selectedValue()
                    }
                }

                is Filters.YearFilter -> {
                    if (!filter.isDefault()) {
                        yearVal = filter.selectedValue()
                    }
                }

                is Filters.MinScoreFilter -> {
                    if (!filter.isDefault()) {
                        minScoreVal = filter.selectedValue()
                    }
                }

                is Filters.GenreFilter -> {
                    val inc = filter.getIncluded()
                    val exc = filter.getExcluded()
                    if (inc.isNotEmpty()) {
                        genresVal = inc
                    }
                    if (exc.isNotEmpty()) {
                        excludeGenresVal = exc
                    }
                }

                is Filters.TagFilter -> {
                    val inc = filter.getIncluded()
                    val exc = filter.getExcluded()
                    if (inc.isNotEmpty()) {
                        tagsVal = inc
                    }
                    if (exc.isNotEmpty()) {
                        excludeTagsVal = exc
                    }
                }

                else -> {}
            }
        }

        val urlBuilder = "$baseUrl/api/v1/discovery/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", "20")
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            }
            if (sortVal.isNotBlank()) addQueryParameter("sort", sortVal)
            if (formatVal.isNotBlank()) addQueryParameter("formats", formatVal)
            if (statusVal.isNotBlank()) addQueryParameter("statuses", statusVal)
            if (seasonVal.isNotBlank()) addQueryParameter("season", seasonVal)
            if (originVal.isNotBlank()) addQueryParameter("country", originVal)
            if (yearVal.isNotBlank()) addQueryParameter("year", yearVal)
            if (minScoreVal.isNotBlank()) addQueryParameter("minScore", minScoreVal)
            if (genresVal.isNotEmpty()) addQueryParameter("genres", genresVal.joinToString(","))
            if (excludeGenresVal.isNotEmpty()) addQueryParameter("excludeGenres", excludeGenresVal.joinToString(","))
            if (tagsVal.isNotEmpty()) addQueryParameter("tags", tagsVal.joinToString(","))
            if (excludeTagsVal.isNotEmpty()) addQueryParameter("excludeTags", excludeTagsVal.joinToString(","))
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeSearchPage(response)

    private fun parseAnimeSearchPage(response: Response): AnimesPage {
        val dto = response.parseAs<SearchResponseDto>()
        val items = dto.data?.items ?: emptyList()
        val animeList = items.mapNotNull { item ->
            item.id?.let { id ->
                SAnime.create().apply {
                    url = "/watch/$id"
                    title = item.title.getTitle()
                    thumbnail_url = item.coverImage?.extraLarge
                        ?: item.coverImage?.large
                        ?: item.images?.cover
                        ?: item.banner
                    fetch_type = FetchType.Episodes
                }
            }
        }
        val perPage = dto.meta?.filters?.perPage ?: 20
        val hasNextPage = items.size >= perPage
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.GenreFilter(),
        Filters.TagFilter(),
        Filters.FormatFilter(),
        Filters.OriginFilter(),
        Filters.YearFilter(),
        Filters.SeasonFilter(),
        Filters.MinScoreFilter(),
        Filters.StatusFilter(),
    )

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/api/v1/anime/$id/full", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<AnimeDetailResponseDto>()
        val data = dto.data ?: return SAnime.create()

        return SAnime.create().apply {
            title = data.title.getTitle()
            thumbnail_url = data.images?.cover
                ?: data.coverImage?.extraLarge
                ?: data.coverImage?.large
            genre = data.genres?.sortedBy { it }?.joinToString(", ")
            author = data.studio
            status = when (data.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                data.averageScore?.let { score ->
                    if (score > 0) {
                        val stars = (score / 20).coerceIn(0, 5)
                        append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $score\n\n")
                    }
                }
                if (!data.description.isNullOrBlank()) {
                    append(data.description)
                }
                if (!data.format.isNullOrBlank()) append("\n\nFormat: ${data.format}")
                if (!data.season.isNullOrBlank() && data.seasonYear != null) append("\nSeason: ${data.season}")
            }.trim()
            initialized = true
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    // =========================== Related Anime ============================
    override val disableRelatedAnimesBySearch = true

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/api/v1/anime/$id/full", headers)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<AnimeDetailResponseDto>()
        val data = dto.data ?: return emptyList()
        val items = (data.relations ?: emptyList()) + (data.recommendations ?: emptyList())

        return items.distinctBy { it.id }.mapNotNull { item ->
            val itemId = item.id ?: return@mapNotNull null
            SAnime.create().apply {
                url = "/watch/$itemId"
                title = item.title.getTitle()
                thumbnail_url = item.coverImage?.extraLarge
                    ?: item.coverImage?.large
                    ?: item.images?.cover
                fetch_type = FetchType.Episodes
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/api/v1/anime/$id/episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val dto = response.parseAs<EpisodesResponseDto>()
        val epList = dto.data?.episodes ?: emptyList()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        return epList.map { ep ->
            val numDisplay = ep.displayNumber ?: ep.number?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "1"
            val animeId = ep.id?.substringBefore(":") ?: "1"
            SEpisode.create().apply {
                url = "/watch/$animeId:$numDisplay"
                name = if (!ep.title.isNullOrBlank()) "Episode $numDisplay: ${ep.title}" else "Episode $numDisplay"
                episode_number = ep.number ?: 0f
                summary = ep.description ?: ep.overview
                preview_url = ep.thumbnail ?: ep.image
                fillermark = ep.filler == true
                scanlator = when {
                    ep.variants?.contains("sub") == true && ep.variants.contains("dub") -> "Sub & Dub"
                    ep.variants?.contains("sub") == true -> "Sub"
                    ep.variants?.contains("dub") == true -> "Dub"
                    else -> ep.variants?.joinToString(" & ") { it.replaceFirstChar(Char::titlecase) }
                }
                date_upload = ep.airedAt?.let {
                    runCatching { dateFormat.parse(it)?.time }.getOrNull()
                } ?: 0L
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String = "$baseUrl${episode.url}"

    // ========================== Video Extraction =========================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "AniKuro",
            hosterUrl = "$baseUrl${episode.url}",
        ),
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val watchPart = hoster.hosterUrl.substringAfter("/watch/").substringBefore("?")
        val animeId = watchPart.substringBefore(":")
        val epNum = watchPart.substringAfter(":", "1")

        val watchUrl = "$baseUrl/watch/$animeId:$epNum"
        val videoReqUrl = "$baseUrl/api/v1/animepower/video/$animeId/$epNum"
        val videoHeaders = headers.newBuilder()
            .set("Referer", watchUrl)
            .set("Origin", baseUrl)
            .build()
        val res = runCatching {
            client.get(videoReqUrl, videoHeaders)
        }.getOrNull() ?: return emptyList()

        val dto = runCatching { res.parseAs<ProviderResponseDto>() }.getOrNull()
            ?: return emptyList()

        val normalizedList = dto.data?.normalized ?: emptyList()
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        val videos = normalizedList.flatMap { norm ->
            val variantStr = when (norm.variant?.lowercase()) {
                "sub" -> "Sub"
                "dub" -> "Dub"
                "soft-sub" -> "Soft-Sub"
                else -> norm.variant?.replaceFirstChar(Char::titlecase) ?: "Sub"
            }

            if (variantStr.uppercase() in excludedTypes) {
                return@flatMap emptyList()
            }

            val subTracks = norm.subtitles?.mapNotNull { sub ->
                val subUrl = sub.url ?: return@mapNotNull null
                Track(subUrl, sub.label ?: sub.lang ?: "Subtitle")
            } ?: emptyList()

            val streamHeadersBuilder = headers.newBuilder()
                .set("Referer", watchUrl)
                .set("Origin", baseUrl)

            norm.headers?.forEach { (k, v) ->
                streamHeadersBuilder[k] = v
            }
            val vidHeaders = streamHeadersBuilder.build()

            norm.sources?.flatMap { src ->
                val streamUrl = src.url ?: return@flatMap emptyList()
                val qualityLabel = src.quality ?: "Default"

                if (src.isM3U8 == true || streamUrl.contains(".m3u8")) {
                    playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = watchUrl,
                        masterHeaders = vidHeaders,
                        videoHeaders = vidHeaders,
                        videoNameGen = { quality -> "$variantStr - $quality" },
                        subtitleList = subTracks,
                    ).map { video ->
                        video.copy(
                            mpvArgs = forceTsArgs,
                            ffmpegStreamArgs = forceFfmpegArgs,
                        )
                    }
                } else {
                    val titleStr = "$variantStr - $qualityLabel"
                    listOf(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = titleStr,
                            headers = vidHeaders,
                            subtitleTracks = subTracks,
                            mpvArgs = forceTsArgs,
                            ffmpegStreamArgs = forceFfmpegArgs,
                        ),
                    )
                }
            } ?: emptyList()
        }

        val sortedVideos = videos.sortedWith(
            compareByDescending<Video> { video ->
                video.videoTitle.startsWith(prefType, ignoreCase = true)
            }.thenByDescending { video ->
                video.videoTitle.contains(prefQuality)
            }.thenBy { video ->
                PREF_QUALITY_VALUES.indexOfFirst { video.videoTitle.contains(it) }
                    .let { if (it < 0) Int.MAX_VALUE else it }
            },
        )

        return sortedVideos.mapIndexed { index, video ->
            if (index == 0) video.copy(preferred = true) else video
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Helpers ===============================
    private val preferredTitleLanguage: String
        get() = preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT) ?: PREF_TITLE_DEFAULT

    private fun TitleDto?.getTitle(): String {
        requireNotNull(this) { "Title is required" }
        return when (preferredTitleLanguage) {
            "english" -> english ?: userPreferred ?: romaji ?: native!!
            "native" -> native ?: userPreferred ?: romaji ?: english!!
            else -> romaji ?: userPreferred ?: english ?: native!!
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
        )

        screen.addListPreference(
            key = PREF_TITLE_KEY,
            title = "Preferred Title Language",
            default = PREF_TITLE_DEFAULT,
            summary = "%s",
            entries = PREF_TITLE_ENTRIES,
            entryValues = PREF_TITLE_VALUES,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_VALUES,
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio types to hide",
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_VALUES,
            default = emptySet(),
        )
    }

    companion object {
        // Note: https://anikuro.site is the domain list
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("anikuro.to", "anikuro.ru")
        private val PREF_DOMAIN_VALUES = listOf("https://anikuro.to", "https://anikuro.ru")
        private const val PREF_DOMAIN_DEFAULT = "https://anikuro.to"

        private const val PREF_TITLE_KEY = "pref_title_language"
        private const val PREF_TITLE_DEFAULT = "english"
        private val PREF_TITLE_ENTRIES = listOf("Romaji", "English", "Native")
        private val PREF_TITLE_VALUES = listOf("romaji", "english", "native")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private val PREF_TYPE_ENTRIES = listOf("Sub", "Dub", "Soft-Sub")
        private val PREF_TYPE_VALUES = listOf("SUB", "DUB", "SOFT-SUB")
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
    }
}
