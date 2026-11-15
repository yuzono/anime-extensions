package eu.kanade.tachiyomi.animeextension.fr.franime

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.embed4meextractor.Embed4MeExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class FrAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2) { it.host == "kitsu.io" }
        .build()

    private val preferences by getPreferencesLazy()

    private var SharedPreferences.preferredVoice by preferences.delegate(PREF_VOICE_KEY, PREF_VOICE_DEFAULT)
    private var SharedPreferences.preferredPlayer by preferences.delegate(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)
    private var SharedPreferences.preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = pagesToAnimesPage(database().sortedByDescending { it.note.toFloatOrNull() ?: 0f }, page)

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = pagesToAnimesPage(database().sortedByDescending { it.updatedDate ?: Long.MIN_VALUE }, page)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun getFilterList() = Filters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val anime = findAnimeByIdOrSlug(url.queryParameter("anime_id")?.toLongOrNull(), url.encodedPathSegments.lastOrNull().orEmpty())
                ?: throw Exception("Unsupported url")
            // Season URLs open the season itself; bare anime URLs open the parent.
            val seasonNumber = url.queryParameter("s")?.toIntOrNull()
            val entry = seasonNumber
                ?.let { seasonEntry(anime, it, anime.seasons.indexOfLast { s -> s.hasPlayers() }) }
                ?: anime.toSAnime(baseUrl)
            return AnimesPage(listOf(entry), false)
        }

        val params = Filters.get(filters)
        val pages = database()
            .filter { it.matchesQuery(query) && it.matchesFilters(params) }
            .sortedFor(params, query)
        return pagesToAnimesPage(pages, page)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Seasons ===============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val animeData = findAnimeByUrl(anime.url) ?: return emptyList()
        val lastPlayable = animeData.seasons.indexOfLast { it.hasPlayers() }

        // Season posters hit the franime API + Kitsu; fetch them concurrently.
        return animeData.seasons.indices
            .parallelCatchingFlatMap { index -> listOfNotNull(seasonEntry(animeData, index + 1, lastPlayable)) }
            .sortedBy { it.season_number }
    }

    private suspend fun seasonEntry(animeData: Anime, seasonNumber: Int, lastPlayable: Int): SAnime? {
        val index = seasonNumber - 1
        val season = animeData.seasons.getOrNull(index) ?: return null
        if (!season.hasPlayers()) return null

        val displayTitle = if (animeData.seasons.size == 1 && season.title.equals("Saison 1", true)) {
            animeData.displayTitle()
        } else {
            (animeData.displayTitle() + " " + season.title).trim()
        }
        return SAnime.create().apply {
            title = displayTitle
            thumbnail_url = seasonPoster(animeData, seasonNumber)
            description = animeData.seasonDescription()
            genre = animeData.genres.joinToString()
            // Earlier seasons of an ongoing series are done airing.
            status = if (index < lastPlayable) SAnime.COMPLETED else animeData.parseStatus()
            setUrlWithoutDomain("/anime/${urlStem(animeData)}?anime_id=${animeData.id}&s=$seasonNumber")
            season_number = trailingNumber(season.title) ?: seasonNumber.toDouble()
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeData = findAnimeByUrl(anime.url) ?: return anime
        val seasonNumber = (baseUrl + anime.url).toHttpUrl().queryParameter("s")?.toIntOrNull()
            ?: return animeData.toSAnime(baseUrl)

        return animeData.toSAnime(baseUrl).apply {
            url = anime.url
            title = anime.title
            thumbnail_url = seasonPoster(animeData, seasonNumber)
            animeData.seasons.getOrNull(seasonNumber - 1)?.let { description = animeData.seasonDescription() }
            fetch_type = FetchType.Episodes
            season_number = anime.season_number
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val seasonNumber = (baseUrl + anime.url).toHttpUrl().queryParameter("s")?.toIntOrNull() ?: 1
        val animeData = findAnimeByUrl(anime.url) ?: return emptyList()
        val season = animeData.seasons.getOrNull(seasonNumber - 1) ?: return emptyList()

        val seasonDetails = fetchSeasonDetails(animeData.id, seasonNumber)
        val kitsuId = seasonDetails?.kitsuId
            ?: seasonNumber.takeIf { it == 1 }?.let { animeData.sourceUrl?.substringAfterLast('/')?.toLongOrNull() }
        val detailsEpisodes = seasonDetails?.episodes.orEmpty()

        val kitsuEpisodes = kitsuId
            ?.takeIf { detailsEpisodes.isEmpty() || detailsEpisodes.none { it.thumbnail != null } }
            ?.let { fetchKitsuEpisodes(it) }
            .orEmpty()

        val detailsByNumber = buildMap {
            detailsEpisodes.forEach { details -> details.number?.let { put(it, details) } }
            kitsuEpisodes.forEach { kitsu ->
                kitsu.number?.let { number ->
                    put(number, get(number)?.fillFrom(kitsu) ?: kitsu)
                }
            }
        }

        val episodes = season.episodes.mapIndexedNotNull { index, episode ->
            if (!episode.hasPlayers()) return@mapIndexedNotNull null

            val details = detailsByNumber[index + 1]
            val voices = episode.voiceList()

            SEpisode.create().apply {
                setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                name = episode.title?.normalizeEpisodeName() ?: "Épisode ${index + 1}"
                episode_number = (trailingNumber(episode.title ?: "") ?: (index + 1).toDouble()).toFloat()
                scanlator = voices.joinToString(" & ")
                preview_url = details?.thumbnail
                date_upload = parseAirDate(details?.airDate)
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================= Hosters ================================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val animeData = findAnimeByUrl(episode.url) ?: return emptyList()
        val episodeData = animeData.seasons.getOrNull(seasonNumber - 1)
            ?.episodes?.getOrNull(episodeNumber - 1) ?: return emptyList()

        return buildList {
            for (lang in LANGUAGES) {
                val players = when (lang) {
                    "vo" -> episodeData.languages.vo.players
                    else -> episodeData.languages.vf.players
                }
                players.forEachIndexed { index, lecteur ->
                    if (lecteur.equals(UNAVAILABLE, true)) return@forEachIndexed
                    add(
                        Hoster(
                            hosterName = "(${lang.uppercase()}) ${lecteurLabel(lecteur)}",
                            internalData = "${animeData.id}|${seasonNumber - 1}|${episodeNumber - 1}|$lang|$index|$lecteur",
                        ),
                    )
                }
            }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voice = preferences.preferredVoice
        val player = preferences.preferredPlayer

        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains("(${voice.uppercase()})", true) }
                .thenByDescending { player.isNotEmpty() && it.hosterName.contains(player, true) },
        )
    }

    // ========================== Related Anime =============================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val animeData = findAnimeByUrl(anime.url) ?: return emptyList()
        val themes = animeData.genres.toSet()

        return database()
            .asSequence()
            .filter { it.id != animeData.id && (animeData.nsfw || !it.nsfw) }
            .map { it to it.genres.count { theme -> theme in themes } }
            .filter { (_, overlap) -> overlap > 0 }
            .sortedWith(
                compareByDescending<Pair<Anime, Int>> { (_, overlap) -> overlap }
                    .thenByDescending { (candidate, _) -> candidate.note.toFloatOrNull() ?: 0f },
            )
            .take(RELATED_LIMIT)
            .map { (candidate, _) -> candidate.toSAnime(baseUrl) }
            .toList()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val embed4meExtractor by lazy { Embed4MeExtractor(client, headers) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("|")
        val animeId = parts.getOrNull(0)?.toLongOrNull() ?: return emptyList()
        val seasonIdx = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val episodeIdx = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
        val lang = parts.getOrNull(3) ?: return emptyList()
        val playerIdx = parts.getOrNull(4)?.toIntOrNull() ?: return emptyList()

        val apiUrl = "$baseApiUrl/anime/$animeId/$seasonIdx/$episodeIdx/$lang/$playerIdx"
        val playerUrl = resolvePlayerUrl(apiUrl) ?: return emptyList()
        val videos = videosFromPlayer(playerUrl, "(${lang.uppercase()}) ")
            .distinctBy { it.videoTitle to it.videoUrl.substringBefore("?") }
        if (videos.isEmpty()) return emptyList()

        val quality = preferences.preferredQuality
        val qualityList = listOf("360", "480", "720", "1080")
        val sorted = videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality, true) }
                .thenByDescending { video -> qualityList.indexOfLast { video.videoTitle.contains(it) } },
        )

        val isPreferredVoice = hoster.hosterName.contains("(${preferences.preferredVoice.uppercase()})", true)
        val preferredIndex = when {
            isPreferredVoice && sorted.any { it.videoTitle.contains(quality, true) } ->
                sorted.indexOfFirst { it.videoTitle.contains(quality, true) }

            isPreferredVoice &&
                (
                    preferences.preferredPlayer.isEmpty() ||
                        hoster.hosterName.contains(preferences.preferredPlayer, true)
                    ) -> 0

            else -> -1
        }
        return sorted.mapIndexed { index, video -> video.copy(preferred = index == preferredIndex) }
    }

    private suspend fun resolvePlayerUrl(apiUrl: String): String? = try {
        val body = client.get(apiUrl).bodyString().trim()
        if (body.length > MAX_PLAYER_BODY_LENGTH) {
            null
        } else {
            val url = body.toHttpUrlOrNull()
            when {
                body.startsWith("{") -> null // {"msg":"Aucun lecteur disponible pour cette épisode"}

                // FRAnime wrapper URL (franime.fr/watch...), decoded client-side.
                url != null && url.host == baseUrl.toHttpUrl().host && url.encodedPath.startsWith("/watch") ->
                    decodeWatchUrl(url)

                // Direct player URL (legacy); host-validated by videosFromPlayer.
                url != null -> body

                else -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // The lecteur endpoint returns a franime.fr/watch2/?a=..&o=.. wrapper URL. Its params are
    // base64 -> hex -> bytes, and one param XORed with a single-byte key is the player URL.
    // The key varies per response, so derive it from the mandatory leading "h" of every
    // URL and scan each param once.
    private fun decodeWatchUrl(watchUrl: HttpUrl): String? {
        for (name in watchUrl.queryParameterNames) {
            val bytes = watchUrl.queryParameter(name)
                ?.let { value ->
                    runCatching {
                        Base64.decode(value, Base64.DEFAULT).toString(Charsets.US_ASCII).decodeHex()
                    }.getOrNull()
                }
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_WATCH_PARAM_BYTES }
                ?: continue

            val key = (bytes[0].toInt() and 0xFF) xor 'h'.code
            val decoded = xorDecode(bytes, key)
            if (decoded.isHttpUrl() && decoded.all { it.code in 32..126 }) {
                return decoded
            }
        }
        return null
    }

    private fun xorDecode(bytes: ByteArray, key: Int): String = bytes
        .map { it.toInt().xor(key).toByte() }.toByteArray()
        .toString(Charsets.US_ASCII)

    private fun String.isHttpUrl(): Boolean = startsWith("https://")

    private suspend fun videosFromPlayer(playerUrl: String, prefix: String): List<Video> {
        val url = playerUrl.toHttpUrlOrNull()
        // Only HTTPS URLs on known player hosts reach the extractors; brand
        // fragments rather than full domains, since several of these services
        // rotate TLDs.
        if (url == null || !url.isHttps) return emptyList()
        val host = url.host
        return try {
            when {
                host.contains("sendvid") ->
                    sendvidExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("sibnet") ->
                    sibnetExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("vk.com") || host.contains("vkvideo") ->
                    vkExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("vidmoly") || host.contains("ansembed") ->
                    vidmolyExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("filemoon") || host.contains("bysedikamoum") ->
                    filemoonExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("uqload") ->
                    uqloadExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("embed4me") ->
                    embed4meExtractor.videosFromUrl(playerUrl, prefix)

                VIDHIDE_LECTEURS.any { host.contains(it) } ->
                    vidhideExtractor.videosFromUrl(playerUrl) { quality -> "${prefix.trim()} Earnvids - $quality" }

                host.contains("dailymotion") ->
                    dailymotionExtractor.videosFromUrl(playerUrl, prefix)

                host.contains("yourupload") ->
                    yourUploadExtractor.videoFromUrl(playerUrl, headers, prefix = prefix)

                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================= Utilities ==============================
    private val baseApiUrl: String get() = "https://api.${baseUrl.toHttpUrl().host}/api"

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    @Volatile
    private var databaseCache: Pair<List<Anime>, Long>? = null

    private val databaseMutex = Mutex()

    private suspend fun database(): List<Anime> {
        databaseCache
            ?.takeIf { System.currentTimeMillis() - it.second < CATALOG_TTL_MS }
            ?.let { return it.first }
        return databaseMutex.withLock {
            databaseCache
                ?.takeIf { System.currentTimeMillis() - it.second < CATALOG_TTL_MS }
                ?.let { return it.first }
            client.get("$baseApiUrl/animes/").parseAs<List<Anime>>()
                .also { databaseCache = it to System.currentTimeMillis() }
        }
    }

    private suspend fun findAnimeByUrl(url: String): Anime? {
        val httpUrl = (baseUrl + url).toHttpUrl()
        return findAnimeByIdOrSlug(
            httpUrl
                .queryParameter("anime_id")?.toLongOrNull(),
            httpUrl.encodedPathSegments.lastOrNull().orEmpty(),
        )
    }

    private suspend fun findAnimeByIdOrSlug(animeId: Long?, stem: String): Anime? {
        val db = database()
        return animeId?.let { id -> db.firstOrNull { it.id == id } }
            ?: stem.takeIf { it.isNotEmpty() }?.let { s ->
                db.firstOrNull { slugOf(it.titleOriginal) == s || slugOf(it.title) == s }
            }
    }

    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): AnimesPage {
        val chunks = pages.chunked(50)
        val entries = chunks.getOrNull(page - 1)?.map { it.toSAnime(baseUrl) }.orEmpty()
        return AnimesPage(entries, chunks.size > page)
    }

    private fun List<Anime>.sortedFor(params: SearchParams, query: String): List<Anime> = when (params.sort) {
        "Mieux noté" -> sortedByDescending { it.note.toFloatOrNull() ?: 0f }
        "Moins bien noté" -> sortedBy { it.note.toFloatOrNull() ?: Float.MAX_VALUE }
        "A-Z" -> sortedBy { it.title.lowercase(Locale.ROOT) }
        "Z-A" -> sortedByDescending { it.title.lowercase(Locale.ROOT) }
        else -> if (query.isBlank()) {
            sortedByDescending { it.updatedDate ?: Long.MIN_VALUE }
        } else {
            sortedByDescending { it.relevance(query) }
        }
    }

    private fun Anime.relevance(query: String): Int = when {
        title.equals(query, true) || titleOriginal.equals(query, true) -> 3
        title.startsWith(query, true) || titleOriginal.startsWith(query, true) -> 2
        else -> 1
    }

    private suspend fun fetchSeasonDetails(animeId: Long, seasonNumber: Int): SeasonDetails? = try {
        client.get("$baseApiUrl/anime-seasons/$animeId/$seasonNumber").parseAs<SeasonDetails>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // Each season maps to its own Kitsu entry, with its own poster; fall back to the
    // catalog poster when the mapping is missing.
    private suspend fun seasonPoster(animeData: Anime, seasonNumber: Int): String? = fetchSeasonDetails(animeData.id, seasonNumber)?.kitsuId?.let { fetchKitsuPoster(it) }
        ?: animeData.posterUrl(baseUrl)

    private suspend fun fetchKitsuPoster(kitsuId: Long): String? = try {
        client.get("$KITSU_API/anime/$kitsuId")
            .parseAs<KitsuAnimeResponse>()
            .data?.attributes?.posterImage
            ?.let { it.large ?: it.medium ?: it.original }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchKitsuEpisodes(kitsuId: Long): List<EpisodeDetails> {
        val episodes = mutableListOf<EpisodeDetails>()
        var offset = 0
        try {
            while (offset < KITSU_MAX_EPISODES) {
                val page = client
                    .get("$KITSU_API/episodes?filter[mediaId]=$kitsuId&page[limit]=$KITSU_PAGE_SIZE&page[offset]=$offset")
                    .parseAs<KitsuEpisodesResponse>()
                episodes += page.data.mapNotNull { it.attributes?.toEpisodeDetails() }
                if (page.data.size < KITSU_PAGE_SIZE) break
                offset += KITSU_PAGE_SIZE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Return whatever was collected before the failure.
        }
        return episodes
    }

    private fun lecteurLabel(lecteur: String): String = when (lecteur.lowercase()) {
        "sendvid" -> "Sendvid"
        "sibnet" -> "Sibnet"
        "vk", "vkru" -> "VK"
        "vidmoly" -> "VidMoly"
        "filemoon" -> "Filemoon"
        "uqload" -> "Uqload"
        "lpayer" -> "Embed4me"
        "dailymotion" -> "Dailymotion"
        "yourupload" -> "YourUpload"
        in VIDHIDE_LECTEURS -> "Earnvids"
        else -> lecteur.uppercase()
    }

    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }
    private val trailingNumberRegex by lazy { Regex("""(\d+(?:\.\d+)?)$""") }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseAirDate(date: String?): Long = date?.let {
        synchronized(dateFormat) { dateFormat.tryParse(it) }
    } ?: 0L

    private fun slugOf(title: String) = titleRegex
        .replace(title, "")
        .replace(" ", "-").lowercase()

    // Fallback so blank titles never produce an empty URL stem.
    private fun urlStem(anime: Anime) = slugOf(anime.titleOriginal)
        .ifBlank { slugOf(anime.title) }
        .ifBlank { "anime-${anime.id}" }

    private fun trailingNumber(text: String): Double? = trailingNumberRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun String.normalizeEpisodeName() = replaceFirst(Regex("^[éeÉE]pisode\\b"), "Épisode")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Qualité préférée",
            entries = QUALITIES.toList(),
            entryValues = QUALITY_VALUES.toList(),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_VOICE_KEY,
            title = "Préférence des voix",
            entries = VOICES.toList(),
            entryValues = VOICES_VALUES.toList(),
            default = PREF_VOICE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_PLAYER_KEY,
            title = "Lecteur par défaut",
            entries = PLAYERS.toList(),
            entryValues = PLAYERS_VALUES.toList(),
            default = PREF_PLAYER_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private val LANGUAGES = listOf("vo", "vf")

        private const val KITSU_API = "https://kitsu.io/api/edge"
        private const val KITSU_PAGE_SIZE = 20
        private const val KITSU_MAX_EPISODES = 20 * KITSU_PAGE_SIZE

        private val voicesMap = mapOf(
            "Préférer VO" to "vo",
            "Préférer VF" to "vf",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Aucun" to "",
            "Sendvid" to "sendvid",
            "Sibnet" to "sibnet",
            "VK" to "vk",
            "VidMoly" to "vidmoly",
            "Filemoon" to "filemoon",
            "Uqload" to "uqload",
            "Embed4me" to "embed4me",
            "Earnvids" to "earnvids",
            "Dailymotion" to "dailymotion",
            "YourUpload" to "yourupload",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()

        private val VIDHIDE_LECTEURS = setOf("movearnpre", "smoothpre", "minochinos", "dingtezuni", "morencius")

        private const val CATALOG_TTL_MS = 30 * 60 * 1000L
        private const val MAX_PLAYER_BODY_LENGTH = 20_000
        private const val MAX_WATCH_PARAM_BYTES = 512

        private const val PREF_VOICE_KEY = "preferred_voice"
        private const val PREF_VOICE_DEFAULT = "vo"

        private const val PREF_PLAYER_KEY = "preferred_player"
        private const val PREF_PLAYER_DEFAULT = ""

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val RELATED_LIMIT = 12
    }
}
