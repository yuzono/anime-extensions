package eu.kanade.tachiyomi.animeextension.en.senshi

import android.content.SharedPreferences
import android.util.LruCache
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.tryParse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.nanohttpd.protocols.http.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class Senshi :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Senshi"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val serverLanguage: String
        get() = if (titleLanguage == "english") "EN" else "JP"

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val audioExclusions: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, PREF_AUDIO_EXCLUDE_DEFAULT)
            ?: PREF_AUDIO_EXCLUDE_DEFAULT

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    private val videoHeaders: Headers = headers.newBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .build()

    private val playlistUtils by lazy { PlaylistUtils(network.client, headers) }

    private val anicdnRefererInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host == IMAGE_CDN_HOST) {
            chain.proceed(request.newBuilder().header("Referer", "$baseUrl/").build())
        } else {
            chain.proceed(request)
        }
    }

    override val client = network.client.newBuilder()
        .addInterceptor(anicdnRefererInterceptor)
        .rateLimit(5) { it.host == baseUrl.toHttpUrl().host }
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Anime metadata ==============================
    private data class AnimeMeta(val malId: Int, val anilistId: Int?, val subCount: Int, val dubCount: Int)

    private val animeMetaCache by lazy { LruCache<String, AnimeMeta>(64) }
    private val animeMetaMutex = Mutex()

    private suspend fun fetchAnimeMeta(publicId: String): AnimeMeta? {
        animeMetaCache.get(publicId)?.let { return it }

        return animeMetaMutex.withLock {
            animeMetaCache.get(publicId)?.let { return@withLock it }

            try {
                client.get("$baseUrl/anime/$publicId", apiHeaders()).use { res ->
                    val dto = res.parseAs<SenshiAnimeDto>()
                    AnimeMeta(dto.id, dto.anilistId, dto.subCount, dto.dubCount)
                        .also { animeMetaCache.put(publicId, it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ======================= Popular / Latest / Search ====================
    // Single endpoint: POST /anime/filter. Page size 30; the response's `total`
    // is the ONLY pagination signal (the site itself paginates by 'total / 30').

    private var browsePage = 1

    private fun filterRequest(
        page: Int,
        sortBy: String,
        searchTerm: String = "",
        types: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        status: List<String> = emptyList(),
        seasons: List<String> = emptyList(),
        year: String = "",
        languages: List<String> = emptyList(),
    ): Request {
        browsePage = page
        val payload = buildJsonObject {
            put("searchTerm", searchTerm)
            put("types", JsonArray(types.map(::JsonPrimitive)))
            put("genres", JsonArray(genres.map(::JsonPrimitive)))
            put("status", JsonArray(status.map(::JsonPrimitive)))
            put("seasons", JsonArray(seasons.map(::JsonPrimitive)))
            put("year", year)
            put("studios", JsonArray(emptyList()))
            put("producers", JsonArray(emptyList()))
            put("languages", JsonArray(languages.map(::JsonPrimitive)))
            put("page", page)
            put("limit", PAGE_LIMIT)
            put("sortBy", sortBy)
            put("languagePreference", serverLanguage)
        }
        return POST("$baseUrl/anime/filter", apiHeaders(), payload.toString().toJsonBody())
    }

    private fun filterParse(response: Response): AnimesPage {
        val dto = response.parseAs<FilterResponseDto>()
        val animes = dto.data.mapNotNull { it.toSAnime(titleLanguage, baseUrl) }

        val hasNextPage = dto.data.isNotEmpty() && browsePage * PAGE_LIMIT < dto.total

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int) = filterRequest(page, sortBy = "score_desc")
    override fun popularAnimeParse(response: Response) = filterParse(response)

    override fun latestUpdatesRequest(page: Int) = filterRequest(page, sortBy = "recent")
    override fun latestUpdatesParse(response: Response) = filterParse(response)

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var searchTerm = query
        var sortBy = "score_desc"
        var types: List<String> = emptyList()
        var genres: List<String> = emptyList()
        var status: List<String> = emptyList()
        var seasons: List<String> = emptyList()
        var year = ""
        var languages: List<String> = emptyList()

        filters.forEach { filter ->
            when (filter) {
                is Filters.SortFilter -> sortBy = filter.getValue()
                is Filters.FormatFilter -> types = filter.getSelectedValues()
                is Filters.StatusFilter -> status = filter.getSelectedValues()
                is Filters.LanguageFilter -> languages = filter.getSelectedValues()
                is Filters.SeasonFilter -> seasons = filter.getSelectedValues()
                is Filters.YearFilter -> year = filter.getValue() ?: ""
                is Filters.GenreFilter -> {
                    genres = filter.getIncluded()
                    // Site shows genre exclusion, but it just ignores the genre (STATE_IGNORE behavior).
                }
                else -> {}
            }
        }
        if (searchTerm.isBlank()) searchTerm = ""

        return filterRequest(page, sortBy, searchTerm, types, genres, status, seasons, year, languages)
    }

    override fun searchAnimeParse(response: Response) = filterParse(response)

    // ============================== Anime Details ==============================
    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/watch/${anime.url}/1"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}", apiHeaders())

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<SenshiAnimeDto>()

        animeMetaCache.put(
            dto.publicId,
            AnimeMeta(dto.id, dto.anilistId, dto.subCount, dto.dubCount),
        )

        val anime = dto.toSAnime(titleLanguage, baseUrl) ?: throw Exception("Could not parse anime details")
        return anime.apply {
            description = buildDescription(dto)
            author = dto.studios?.takeIf(String::isNotBlank)
        }
    }

    private fun buildDescription(dto: SenshiAnimeDto): String = buildString {
        dto.score?.takeIf { it > 0.0 }?.let { score ->
            val stars = (score / 2.0).roundToInt().coerceIn(1, 5)
            append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${(score * 10).roundToInt()}")
            append("\n\n")
        }

        dto.aniDescription?.takeIf(String::isNotBlank)?.let { raw ->
            append(raw.replace(BR_REGEX, "\n").replace(HTML_TAG_REGEX, "").trim())
            append("\n\n")
        }

        val altTitles = dto.synonyms.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter {
                it.isNotBlank() && !it.startsWith("http", true) &&
                    it != dto.title && it != dto.titleEnglish
            }
            .distinct()

        val infoLines = buildList {
            if (altTitles.isNotEmpty()) add("**Alternative Titles**: ${altTitles.joinToString(" • ")}")
            dto.type?.takeIf(String::isNotBlank)?.let { add("**Format**: $it") }
            dto.aniSource?.takeIf(String::isNotBlank)?.let {
                add("**Source**: ${it.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }}")
            }
            dto.airingDate?.takeIf(String::isNotBlank)?.let { add("**Aired**: $it") }
            dto.aniEpisodes?.takeIf { it.isNotBlank() && it != "?" }?.let { add("**Episodes**: $it") }
            dto.duration?.takeIf(String::isNotBlank)?.let { add("**Duration**: $it") }
            if (dto.aniSeason != null && dto.aniYear != null) add("**Season**: ${dto.aniSeason} ${dto.aniYear}")
        }
        if (infoLines.isNotEmpty()) {
            append(infoLines.joinToString("\n"))
            append("\n")
        }

        val trackers = buildList {
            add("[MAL](https://myanimelist.net/anime/${dto.id})")
            dto.anilistId?.takeIf { it > 0 }?.let { add("[AniList](https://anilist.co/anime/$it)") }
            dto.tvdbId?.takeIf { it > 0 }?.let { add("[TVDB](https://thetvdb.com/series/$it)") }
        }
        append("**Trackers**: ${trackers.joinToString(" • ")}")
        append("\n")

        dto.trailer?.takeIf(String::isNotBlank)?.let { trailer ->
            trailer.substringAfter("/embed/", "").takeIf(String::isNotBlank)?.let { ytId ->
                append("**Trailer**: [YouTube](https://www.youtube.com/watch?v=$ytId)")
                append("\n")
            }
        }
    }

    // ======================== Related / Recommended =======================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val meta = animeMetaCache.get(anime.url) ?: fetchAnimeMeta(anime.url)

        val related = meta?.let {
            runCatching {
                client.get("$baseUrl/anime/${it.malId}/related", apiHeaders())
                    .use { res -> res.parseAs<List<SenshiAnimeDto>>() }
            }.getOrDefault(emptyList())
        } ?: emptyList()

        val recommended = runCatching {
            client.get("$baseUrl/anime/${anime.url}/recommended", apiHeaders())
                .use { res -> res.parseAs<List<SenshiAnimeDto>>() }
        }.getOrDefault(emptyList())

        val relatedAnime = related.mapNotNull { dto ->
            dto.toSAnime(titleLanguage, baseUrl)
        }

        return (relatedAnime + recommended.mapNotNull { it.toSAnime(titleLanguage, baseUrl) })
            .distinctBy { it.url }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val proxy = getProxyServer()
        val meta = animeMetaCache.get(anime.url) ?: fetchAnimeMeta(anime.url)
            ?: throw Exception("Could not load anime info. Refresh in WebView.")

        val episodes = try {
            client.get("$baseUrl/episodes/${meta.malId}", apiHeaders())
                .use { it.parseAs<List<EpisodeDto>>() }
        } catch (_: Exception) {
            throw Exception("Could not find any episodes. Check if there are any in WebView.")
        }

        return episodes.map { ep ->
            SEpisode.create().apply {
                episode_number = ep.epId.toFloat()
                url = "${anime.url}/${ep.epId}"
                preview_url = ep.epThumbnail?.takeIf(String::isNotBlank)
                    ?.let { proxy.proxyUrl(it) }
                val epNumStr = ep.epId.toString()
                name = buildString {
                    append(
                        if (ep.epTitle.isNotBlank() && !ep.epTitle.equals("Episode $epNumStr", ignoreCase = true)) {
                            "Episode $epNumStr - ${ep.epTitle}"
                        } else {
                            "Episode $epNumStr"
                        },
                    )
                    if (ep.epRecap) append(" [Recap]") // no native flag for recaps — name tag stays
                }

                fillermark = ep.epFiller

                val hasSub = ep.epId <= meta.subCount
                val hasDub = ep.epId <= meta.dubCount
                scanlator = when {
                    hasSub && hasDub -> "Sub & Dub"
                    hasSub -> "Sub"
                    hasDub -> "Dub"
                    else -> null
                }

                date_upload = dateFormat.tryParse(ep.createdAt)

                skipTimesCache.put(url, SkipTimes(ep.introStart, ep.introEnd, ep.outroStart, ep.outroEnd))
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (publicId, epNum) = episode.url.split("/")
        return "$baseUrl/watch/$publicId/$epNum"
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (publicId, epNum) = episode.url.split("/")
        val meta = animeMetaCache.get(publicId) ?: fetchAnimeMeta(publicId) ?: return emptyList()

        val embeds = try {
            client.get("$baseUrl/episode-embeds/${meta.malId}/$epNum", apiHeaders())
                .use { it.parseAs<List<EpisodeEmbedDto>>() }
        } catch (_: Exception) {
            return emptyList()
        }

        val epSkip = skipTimesCache.get(episode.url)

        fun resolve(epVal: Double?, msVal: Long?) = epVal ?: msVal?.div(1000.0)
        fun fmt(value: Double?) = value?.toString() ?: ""

        return embeds
            .filter { it.remoteSourceId != null && !it.status.isNullOrBlank() }
            // Dub & HardSub usually point at the SAME vidcloud source ("audio":
            // "both"); the separation happens at the manifest level.
            .distinctBy { it.status }
            .filter { it.status !in audioExclusions }
            .map { embed ->
                val tag = embed.status!!
                Hoster(
                    hosterName = "[$tag]",
                    internalData = "vidcloud::${embed.remoteSourceId}|||$tag|||" +
                        fmt(resolve(epSkip?.introStart, embed.introStartMs)) + "|||" +
                        fmt(resolve(epSkip?.introEnd, embed.introEndMs)) + "|||" +
                        fmt(resolve(epSkip?.outroStart, embed.outroStartMs)) + "|||" +
                        fmt(resolve(epSkip?.outroEnd, embed.outroEndMs)),
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedByDescending { it.hosterName.contains(preferredAudio) }

    // ========================== Video Extraction ==========================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        if (!hoster.internalData.startsWith("vidcloud::")) return emptyList()

        val parts = hoster.internalData.removePrefix("vidcloud::").split("|||")
        val sourceId = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return emptyList()

        val entries = try {
            client.get("https://s.vidcloud.se/_v1/sources?id=$sourceId", videoHeaders)
                .use { it.parseAs<List<VidcloudEntryDto>>() }
        } catch (_: Exception) {
            return emptyList()
        }

        val audioTag = parts.getOrNull(1).orEmpty()
        // Rendition patterns as observed in decrypted masters: audio/0_ja, audio/1_en
        val audioRendition = if (audioTag == "Dub") "1_en" else "0_ja"

        val proxy = getProxyServer()

        val timestamps = buildList {
            parts.getOrNull(2)?.toDoubleOrNull()?.let { s ->
                parts.getOrNull(3)?.toDoubleOrNull()?.let { e ->
                    if (e > s) add(TimeStamp(s, e, name = "Intro", type = ChapterType.Opening))
                }
            }
            parts.getOrNull(4)?.toDoubleOrNull()?.let { s ->
                parts.getOrNull(5)?.toDoubleOrNull()?.let { e ->
                    if (e > s) add(TimeStamp(s, e, name = "Outro", type = ChapterType.Ending))
                }
            }
        }

        return entries.flatMap { entry ->
            val src = entry.source?.src?.takeUnless(String::isBlank) ?: return@flatMap emptyList()

            val isDub = audioTag.equals("Dub", ignoreCase = true)

            val subtitles = entry.tracks
                .filter { !it.url.isNullOrBlank() && !it.label.equals("chapter", ignoreCase = true) }
                // Subs live on the SHARED source (Dub & HardSub point at one Vidcloud
                // stream), so each hoster keeps only its own set.
                .filter { track ->
                    val isDubTrack = track.label.orEmpty().contains("dub", ignoreCase = true) ||
                        track.url.orEmpty().contains("ai_dub")
                    if (isDub) isDubTrack else !isDubTrack
                }
                .ifEmpty {
                    entry.tracks.filter { !it.url.isNullOrBlank() && !it.label.equals("chapter", ignoreCase = true) }
                }
                .map { Track(proxy.proxyUrl(it.url!!), it.label ?: "Unknown") }

            playlistUtils.extractFromHls(
                playlistUrl = proxy.proxyUrl(src) + "&audio=$audioRendition",
                referer = "$baseUrl/",
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality -> quality },
                subtitleList = subtitles,
            )
        }
            .map { video -> if (timestamps.isNotEmpty()) video.copy(timestamps = timestamps) else video }
            .sortedByDescending { it.videoTitle.contains(preferredQuality) }
            .mapIndexed { index, video ->
                if (index == 0 && hoster.hosterName.contains(preferredAudio)) {
                    video.copy(preferred = true)
                } else {
                    video
                }
            }
    }

    // ========================= Proxy / Key Wiring =========================
    private val keyStore by lazy {
        Em3u8KeyStore(preferences, network.client, headers) { baseUrl }
    }

    @Volatile
    private var proxyServer: Em3u8Proxy? = null

    @Synchronized
    private fun getProxyServer(): Em3u8Proxy {
        if (proxyServer == null || !proxyServer!!.isAlive) {
            proxyServer?.stop()
            proxyServer = Em3u8Proxy(videoHeaders, network.client, keyStore)
                .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        }
        return proxyServer!!
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = PREF_TITLE_LANG_ENTRIES,
            entryValues = PREF_TITLE_LANG_VALUES,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = preferredQuality,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio Type",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = preferredAudio,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_AUDIO_EXCLUDE_KEY
            title = "Exclude Audio Types"
            entries = PREF_AUDIO_ENTRIES.toTypedArray()
            entryValues = PREF_AUDIO_VALUES.toTypedArray()
            setDefaultValue(PREF_AUDIO_EXCLUDE_DEFAULT)
            summary = "Hide videos of the selected audio types."
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_LIMIT = 30

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("senshi.to")
        private val PREF_DOMAIN_VALUES = listOf("https://senshi.to")
        private const val PREF_DOMAIN_DEFAULT = "https://senshi.to"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "480p")
        private val PREF_QUALITY_VALUES = listOf("1080", "480")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Dub")
        private val PREF_AUDIO_VALUES = listOf("HardSub", "Dub")
        private const val PREF_AUDIO_DEFAULT = "HardSub"

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"
        private val PREF_AUDIO_EXCLUDE_DEFAULT = emptySet<String>()

        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)

        private const val IMAGE_CDN_HOST = "img.anicdn.se"

        fun parseStatus(status: String?): Int = when (status?.trim()) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}
