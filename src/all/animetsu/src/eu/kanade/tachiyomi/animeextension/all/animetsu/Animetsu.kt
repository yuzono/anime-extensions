package eu.kanade.tachiyomi.animeextension.all.animetsu

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class Animetsu :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animetsu"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DOMAIN_VALUES.first()) ?: DOMAIN_VALUES.first()

    private val apiUrl: String
        get() = "$baseUrl/v2/api"

    private val proxyUrl = "https://mega-cloud.top/proxy"

    override val lang = "all"

    override val supportsLatest = true

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val enabledServers: Set<String>
        get() = preferences.getStringSet(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_PREFERRED_SERVER_KEY, PREF_PREFERRED_SERVER_DEFAULT) ?: PREF_PREFERRED_SERVER_DEFAULT

    private val enabledAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_TYPE_KEY, PREF_AUDIO_TYPE_DEFAULT) ?: PREF_AUDIO_TYPE_DEFAULT

    private val preferredAudioType: String
        get() = preferences.getString(PREF_PREFERRED_AUDIO_TYPE_KEY, PREF_PREFERRED_AUDIO_TYPE_DEFAULT) ?: PREF_PREFERRED_AUDIO_TYPE_DEFAULT

    private fun apiHeaders(referer: String = "$baseUrl/browse"): Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    override val client = network.client.newBuilder()
        .rateLimit(5, 1.seconds)
        .build()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/anime/search/?sort=popularity&page=$page&per_page=35", apiHeaders())

    override fun popularAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuSearchDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.map { it.toSAnime() }

        return AnimesPage(animes, dto.page < dto.last_page)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/anime/recent?page=$page&per_page=35", apiHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuRecentDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.map { it.toSAnime() }

        return AnimesPage(animes, dto.currentPage < dto.lastPage)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimetsuFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiUrl/anime/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "35")

            if (query.isNotBlank()) addQueryParameter("query", query)

            filters.filterIsInstance<AnimetsuFilters.SortFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("sort", it) }
            filters.filterIsInstance<AnimetsuFilters.FormatFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("format", it) }
            filters.filterIsInstance<AnimetsuFilters.StatusFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("status", it) }
            filters.filterIsInstance<AnimetsuFilters.SeasonFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("season", it) }
            filters.filterIsInstance<AnimetsuFilters.YearFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("year", it) }
            filters.filterIsInstance<AnimetsuFilters.CountryFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("country", it) }
            filters.filterIsInstance<AnimetsuFilters.SourceFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("source", it) }

            filters.filterIsInstance<AnimetsuFilters.GenreFilter>().firstOrNull()?.getSelectedValues()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("genres", it) }
            filters.filterIsInstance<AnimetsuFilters.TagFilter>().firstOrNull()?.getSelectedValues()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("tags", it) }
        }

        return GET(urlBuilder.build(), apiHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/info/${anime.url}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<AnimetsuAnimeDto>().toSAnime()

    // ============================== Related ===============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<AnimetsuAnimeDto>()
        val seenIds = mutableSetOf(dto.id)

        fun getTitle(titleDto: AnimetsuTitleDto?): String? = when (titleLanguage) {
            "english" -> titleDto?.english
            "native" -> titleDto?.native
            else -> titleDto?.romaji
        }?.takeIf { it.isNotBlank() }
            ?: titleDto?.romaji
            ?: titleDto?.english

        return buildList {
            dto.seasons?.mapNotNull { season ->
                if (season.id.isBlank() || season.id in seenIds) return@mapNotNull null
                seenIds.add(season.id)
                val seasonTitle = getTitle(season.title) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = season.id
                    title = seasonTitle
                    status = parseStatus(season.status)
                }
            }?.let { addAll(it) }

            dto.relations?.mapNotNull { rel ->
                if (rel.id.isBlank() || rel.id in seenIds) return@mapNotNull null
                seenIds.add(rel.id)
                val relTitle = getTitle(rel.title) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rel.id
                    title = relTitle
                    status = parseStatus(rel.status)
                }
            }?.let { addAll(it) }

            dto.recommendations?.mapNotNull { rec ->
                if (rec.id.isBlank() || rec.id in seenIds) return@mapNotNull null
                val recTitle = getTitle(rec.title) ?: return@mapNotNull null

                SAnime.create().apply {
                    url = rec.id
                    title = recTitle
                    status = parseStatus(rec.status)
                }
            }?.let { addAll(it) }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url
        val referer = getAnimeUrl(anime)

        val response = client.newCall(
            GET("$apiUrl/anime/eps/$animeId", apiHeaders(referer)),
        ).awaitSuccess()

        return response.parseAs<List<AnimetsuEpisodeDto>>()
            .mapNotNull { it.toSEpisode(animeId) }
            .sortedByDescending { it.episode_number }
    }

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/")
        val animeId = parts[0]
        val epNum = parts[1]
        val watchReferer = "$baseUrl/watch/$animeId"

        val serverResponse = client.newCall(
            GET("$apiUrl/anime/servers/$animeId/$epNum", apiHeaders(watchReferer)),
        ).awaitSuccess()

        val allServers = serverResponse.parseAs<List<AnimetsuServerDto>>()

        val servers = allServers
            .filter { server -> server.id in enabledServers }
            .sortedByDescending { server -> server.id == preferredServer.takeIf { pref -> pref != "none" } }

        val sortedAudioTypes = enabledAudioTypes
            .sortedByDescending { type -> type == preferredAudioType.takeIf { pref -> pref != "none" } }

        val playlistUtils = PlaylistUtils(client, apiHeaders(watchReferer))

        return servers.parallelCatchingFlatMapBlocking { server ->
            buildList {
                for (sourceType in sortedAudioTypes) {
                    try {
                        val audioLabel = sourceType.uppercase()
                        val sourceResponse = client.newCall(
                            GET("$apiUrl/anime/oppai/$animeId/$epNum?server=${server.id}&source_type=$sourceType", apiHeaders(watchReferer)),
                        ).execute()

                        val dto = sourceResponse.parseAs<AnimetsuVideoDto>()
                        val subtitleTracks = dto.subs?.map { sub ->
                            Track(sub.url, sub.lang ?: "Unknown")
                        } ?: emptyList()

                        val subLabel = when {
                            server.id.equals("pahe", ignoreCase = true) -> " [Hard Subs]"
                            server.id.equals("kite", ignoreCase = true) -> " [Soft Subs]"
                            server.id.equals("meg", ignoreCase = true) -> " [Hard Subs]"
                            server.id.equals("kiss", ignoreCase = true) -> " [Soft Subs]"
                            else -> ""
                        }

                        for (source in dto.sources) {
                            val fullUrl = when {
                                source.needProxy -> "$proxyUrl${source.url}"
                                source.url.startsWith("http") -> source.url
                                else -> "$baseUrl${source.url}"
                            }

                            when {
                                source.type?.contains("mp4") == true -> {
                                    add(
                                        Video(
                                            fullUrl,
                                            "${server.id.uppercase()}: ${source.quality} ($audioLabel)$subLabel",
                                            fullUrl,
                                            apiHeaders(watchReferer),
                                            subtitleTracks,
                                        ),
                                    )
                                }
                                source.type?.contains("mpegurl") == true -> {
                                    if (source.oldHls) {
                                        add(
                                            Video(
                                                fullUrl,
                                                "${server.id.uppercase()}: ${source.quality} ($audioLabel)$subLabel",
                                                fullUrl,
                                                apiHeaders(watchReferer),
                                                subtitleTracks,
                                            ),
                                        )
                                    } else {
                                        addAll(
                                            playlistUtils.extractFromHls(
                                                playlistUrl = fullUrl,
                                                videoNameGen = { quality ->
                                                    val cleanQuality = quality.substringBefore(" ").let { q ->
                                                        if (q.endsWith("P")) q.lowercase() else q
                                                    }
                                                    "${server.id.uppercase()}: $cleanQuality ($audioLabel)$subLabel"
                                                },
                                                referer = "$baseUrl/",
                                                subtitleList = subtitleTracks,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore if a specific audio type fails for this server
                    }
                }
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = baseUrl,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_ENTRIES,
            entryValues = PREF_TITLE_LANG_VALUES,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_PREFERRED_SERVER_KEY,
            title = "Preferred Host",
            entries = PREF_PREFERRED_SERVER_ENTRIES,
            entryValues = PREF_PREFERRED_SERVER_VALUES,
            default = PREF_PREFERRED_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_SERVER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = SERVER_ENTRIES,
            entryValues = SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_PREFERRED_AUDIO_TYPE_KEY,
            title = "Preferred Audio Type",
            entries = PREF_PREFERRED_AUDIO_TYPE_ENTRIES,
            entryValues = PREF_PREFERRED_AUDIO_TYPE_VALUES,
            default = PREF_PREFERRED_AUDIO_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_AUDIO_TYPE_KEY,
            title = "Enable/Disable Audio Types",
            summary = "Select which audio types to show (Sub, Dub)",
            entries = AUDIO_TYPE_ENTRIES,
            entryValues = AUDIO_TYPE_VALUES,
            default = PREF_AUDIO_TYPE_DEFAULT,
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_ADULT_KEY,
            title = "Hide Adult Content",
            summary = "Hides 18+ content from browse, search, and latest updates.",
            default = PREF_HIDE_ADULT_DEFAULT,
        )
    }

    // ============================= Utilities ==============================
    private fun parseStatus(status: String?): Int = when (status) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "NOT_YET_RELEASED" -> SAnime.UNKNOWN
        else -> SAnime.UNKNOWN
    }

    private fun AnimetsuAnimeDto.toSAnime() = SAnime.create().apply {
        val dto = this@toSAnime
        url = dto.id
        title = when (titleLanguage) {
            "english" -> dto.title?.english
            "native" -> dto.title?.native
            else -> dto.title?.romaji
        }?.takeIf { it.isNotBlank() }
            ?: dto.title?.romaji
            ?: dto.title?.english
            ?: "Unknown Title"

        thumbnail_url = dto.coverImage?.large ?: dto.coverImage?.medium
        genre = (dto.genres.orEmpty() + dto.tags.orEmpty()).joinToString(", ")
        status = parseStatus(dto.status)
        description = buildDescription(dto)
        artist = dto.staff?.filter {
            it.role in listOf("Original Story", "Original Creator", "Original Character Design")
        }?.mapNotNull { it.name }?.joinToString(", ") ?: ""
        author = dto.studios?.firstOrNull { it.isMain }?.name ?: ""
    }

    private fun buildDescription(dto: AnimetsuAnimeDto): String {
        val desc = StringBuilder()

        dto.description?.cleanHtml()?.let {
            desc.append(it)
        }

        val meta = mutableListOf<String>()
        dto.format?.let { meta.add(it.replace("_", " ").titleCase()) }
        dto.source?.let { meta.add("Source: ${it.replace("_", " ").titleCase()}") }
        dto.status?.let {
            val statusStr = when (it) {
                "RELEASING" -> "Airing"
                "FINISHED" -> "Finished"
                "NOT_YET_RELEASED" -> "Upcoming"
                "CANCELLED" -> "Cancelled"
                else -> it.replace("_", " ").titleCase()
            }
            meta.add(statusStr)
        }
        dto.totalEps?.let { meta.add("Episodes: $it") }
        dto.duration?.let { meta.add("Duration: $it min") }
        dto.season?.let { season ->
            val year = dto.year
            meta.add(if (year != null) "${season.titleCase()} $year" else season.titleCase())
        }
        dto.country?.let { meta.add("Country: $it") }
        if (meta.isNotEmpty()) {
            desc.append("\n\n").append(meta.joinToString(" | "))
        }

        val dates = mutableListOf<String>()
        dto.startDate?.let { dates.add("Start: $it") }
        dto.endDate?.let { dates.add("End: $it") }
        if (dates.isNotEmpty()) {
            desc.append("\n\n").append(dates.joinToString(" | "))
        }

        dto.averageScore?.let { score ->
            desc.append("\n\nScore: $score/100")
            dto.meanScore?.takeIf { it != score }?.let { mean ->
                desc.append(" (Mean: $mean/100)")
            }
        }

        val stats = mutableListOf<String>()
        dto.popularity?.let { stats.add("Popularity: $it") }
        dto.favourites?.let { stats.add("Favourites: $it") }
        dto.trending?.let { if (it > 0) stats.add("Trending: $it") }
        if (stats.isNotEmpty()) {
            desc.append("\n").append(stats.joinToString(" | "))
        }

        dto.studios?.takeIf { it.isNotEmpty() }?.let { studios ->
            val mainStudio = studios.firstOrNull { it.isMain }?.name
            val otherStudios = studios.filter { !it.isMain }.map { it.name }
            desc.append("\n\nStudio: ")
            if (mainStudio != null && otherStudios.isNotEmpty()) {
                desc.append("$mainStudio (${otherStudios.joinToString(", ")})")
            } else {
                desc.append(studios.joinToString(", ") { it.name })
            }
        }

        dto.synonyms?.takeIf { it.isNotEmpty() }?.let {
            desc.append("\n\nSynonyms: ").append(it.joinToString(", "))
        }

        dto.hashtag?.takeIf { it.isNotBlank() }?.let {
            desc.append("\nHashtag: $it")
        }

        val ids = mutableListOf<String>()
        dto.anilistId?.let { ids.add("AniList: $it") }
        dto.malId?.let { ids.add("MAL: $it") }
        if (ids.isNotEmpty()) {
            desc.append("\n\n").append(ids.joinToString(" | "))
        }

        dto.relations?.takeIf { it.isNotEmpty() }?.let { relations ->
            desc.append("\n\nRelations:")
            relations.forEach { rel ->
                val relTitle = rel.title?.english ?: rel.title?.romaji ?: rel.title?.native ?: "Unknown"
                val relType = rel.relationType?.replace("_", " ")?.titleCase() ?: ""
                val relFormat = rel.format?.replace("_", " ")?.titleCase() ?: ""
                val relSeasonYear = buildString {
                    rel.season?.let { append(it.titleCase()) }
                    rel.year?.let { y ->
                        if (isNotEmpty()) append(" ")
                        append(y)
                    }
                }
                desc.append("\n• $relTitle ($relFormat${if (relSeasonYear.isNotBlank()) ", $relSeasonYear" else ""}) [$relType]")
            }
        }

        dto.characters?.filter { it.role == "MAIN" }?.takeIf { it.isNotEmpty() }?.let { chars ->
            desc.append("\n\nMain Characters:")
            chars.forEach { char ->
                val va = char.voiceActor?.let { "${it.name} (${it.language})" } ?: "Unknown"
                desc.append("\n• ${char.name} (VA: $va)")
            }
        }

        dto.staff?.takeIf { it.isNotEmpty() }?.let { staffList ->
            desc.append("\n\nStaff:")
            staffList.forEach { s ->
                desc.append("\n• ${s.role}: ${s.name}")
            }
        }

        dto.trailer?.takeIf { it.isNotBlank() && it != "-" }?.let {
            desc.append("\n\nTrailer: https://www.youtube.com/watch?v=$it")
        }

        return desc.toString().trim()
    }

    private fun String.cleanHtml(): String = this
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<BR>", "\n")
        .replace("<BR/>", "\n")
        .replace("<i>", "")
        .replace("</i>", "")
        .replace("<b>", "")
        .replace("</b>", "")
        .replace("<em>", "")
        .replace("</em>", "")
        .trim()

    private fun String.titleCase(): String = split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("animetsu.live", "animetsu.bz", "animetsu.cc")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")

        private const val PREF_PREFERRED_SERVER_KEY = "preferred_server"
        private const val PREF_PREFERRED_SERVER_DEFAULT = "none"
        private val PREF_PREFERRED_SERVER_ENTRIES = listOf("None", "Pahe", "Kite", "Meg", "Kiss")
        private val PREF_PREFERRED_SERVER_VALUES = listOf("none", "pahe", "kite", "meg", "kiss")

        private const val PREF_SERVER_KEY = "enabled_servers"
        private val PREF_SERVER_DEFAULT = setOf("pahe", "kite", "meg", "kiss")
        private val SERVER_ENTRIES = listOf("Pahe", "Kite", "Meg", "Kiss")
        private val SERVER_VALUES = listOf("pahe", "kite", "meg", "kiss")

        private const val PREF_PREFERRED_AUDIO_TYPE_KEY = "preferred_audio_type"
        private const val PREF_PREFERRED_AUDIO_TYPE_DEFAULT = "none"
        private val PREF_PREFERRED_AUDIO_TYPE_ENTRIES = listOf("None", "Sub", "Dub")
        private val PREF_PREFERRED_AUDIO_TYPE_VALUES = listOf("none", "sub", "dub")

        private const val PREF_AUDIO_TYPE_KEY = "enabled_audio_types"
        private val PREF_AUDIO_TYPE_DEFAULT = setOf("sub")
        private val AUDIO_TYPE_ENTRIES = listOf("Sub", "Dub")
        private val AUDIO_TYPE_VALUES = listOf("sub", "dub")

        private const val PREF_HIDE_ADULT_KEY = "hide_adult_content"
        private const val PREF_HIDE_ADULT_DEFAULT = true
    }
}
