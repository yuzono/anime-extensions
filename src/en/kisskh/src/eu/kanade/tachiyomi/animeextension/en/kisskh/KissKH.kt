package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.util.Log
import android.util.LruCache
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.Source
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class KissKH : Source() {

    override val name = "KissKH"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val hideUnaired: Boolean
        get() = preferences.getBoolean(PREF_HIDE_UNAIRED_KEY, PREF_HIDE_UNAIRED_DEFAULT)

    private val preferredQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private var subDecryptor by LazyMutable { SubDecryptor(client, headers, baseUrl) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val videoKeyCache by lazy { LruCache<String, String>(100) }
    private val subKeyCache by lazy { LruCache<String, String>(100) }

    private val countdownDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }

    override val supportsRelatedAnimes = false

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchDramaPage(page, order = 1)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchDramaPage(page, order = 2)

    private fun browseUrl(page: Int, order: Int): String = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=$order&pageSize=$PAGE_SIZE"

    private suspend fun fetchDramaPage(page: Int, order: Int): AnimesPage {
        val response = client.get(browseUrl(page, order))
        val dto = response.parseAs<DramaPageDto>()
        val hasNextPage = dto.totalCount?.let { page < it } ?: (dto.data.size >= PAGE_SIZE)
        val animeList = dto.data.mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================

    private fun searchUrl(query: String) = "$baseUrl/api/DramaList/Search".toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("type", "0")
        .build()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.get(searchUrl(query))
        val list = response.parseAs<List<DramaDto>>()
        val animeList = list.mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun DramaDto.toSAnime(): SAnime? {
        val dramaTitle = title ?: return null
        val dramaId = id ?: return null
        val titleURI = dramaTitle.replace(titleUriRegex, "-")
        return SAnime.create().apply {
            this.title = dramaTitle
            url = "/Drama/$titleURI?id=$dramaId"
            thumbnail_url = thumbnail
        }
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val dto = fetchDramaDetails(anime)
        return SAnime.create().apply {
            dto.title?.let { title = it }
            status = parseStatus(dto.status)
            dto.description?.let { description = it }
            dto.thumbnail?.let { thumbnail_url = it }
            initialized = true
        }
    }

    private suspend fun fetchDramaDetails(anime: SAnime): DramaDetailDto {
        val id = requireNotNull(getAnimeUrl(anime).toHttpUrl().queryParameter("id")) { "Missing drama ID" }
        return client.get("$baseUrl/api/DramaList/Drama/$id?isq=false").parseAs()
    }

    private fun parseStatus(status: String?): Int {
        val normalizedStatus = status.orEmpty().lowercase(Locale.ROOT)
        return when {
            "ongoing" in normalizedStatus -> SAnime.ONGOING
            "completed" in normalizedStatus -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val dto = fetchDramaDetails(anime)
        val type = dto.type
        val episodesCount = dto.episodesCount ?: 1
        val status = dto.status.orEmpty().lowercase(Locale.ROOT)
        val isAiringOrUpcoming = "ongoing" in status || "upcoming" in status

        val episodes = if (hideUnaired && isAiringOrUpcoming) {
            filterUnairedEpisodes(dto.episodes)
        } else {
            dto.episodes
        }

        return episodes.mapNotNull { ep ->
            val epId = ep.id?.toString() ?: return@mapNotNull null
            val number = ep.number?.toString()?.removeSuffix(".0") ?: "1"
            SEpisode.create().apply {
                url = epId
                ep.number?.let { episode_number = it }
                when {
                    type.isNullOrBlank() -> {
                        name = "Video $number"
                    }

                    (type.contains("Hollywood") && episodesCount == 1) || type.contains("Movie") -> {
                        name = "Movie"
                    }

                    else -> {
                        name = "Episode $number"
                    }
                }
            }
        }
    }

    private suspend fun filterUnairedEpisodes(episodes: List<EpisodeDto>): List<EpisodeDto> {
        var firstAiredIndex = 0
        for ((index, ep) in episodes.withIndex()) {
            val epId = ep.id?.toString() ?: break
            val isUnaired = try {
                isEpisodeUnaired(epId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("KissKH", "Failed to check episode $epId: ${e.message}")
                break
            }
            if (isUnaired) {
                firstAiredIndex = index + 1
            } else {
                break
            }
        }
        return episodes.drop(firstAiredIndex)
    }

    private suspend fun isEpisodeUnaired(epId: String): Boolean {
        val videoDto = fetchEpisodeVideo(epId)
        return isCountdownWidget(videoDto.video, videoDto.type)
    }

    private suspend fun fetchEpisodeVideo(id: String): EpisodeVideoDto {
        val kkey = requestVideoKey(id)
        val url = "$baseUrl/api/DramaList/Episode/$id.png?err=false&ts=&time=&kkey=$kkey"
        return client.get(url, cacheControl = CacheControl.FORCE_NETWORK).parseAs()
    }

    private fun isCountdownWidget(videoUrl: String?, type: Int?): Boolean {
        if (type == TYPE_COUNTDOWN) return true
        if (videoUrl.isNullOrBlank()) return false
        return videoUrl.contains("tickcounter.com", ignoreCase = true) ||
            videoUrl.contains("/widget/countdown/", ignoreCase = true)
    }

    // =========================== Hosters & Videos ==========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val id = episode.url
        val videoDto = fetchEpisodeVideo(id)

        if (isCountdownWidget(videoDto.video, videoDto.type)) {
            val countdown = getCountdownDetails(videoDto.video)
            val message = if (countdown != null) {
                "This episode has not aired yet ($countdown)"
            } else {
                "This episode has not aired yet (countdown timer active)"
            }
            throw Exception(message)
        }

        val videoUrl = videoDto.video?.takeIf(String::isNotBlank) ?: return emptyList()

        return listOf(
            Hoster(
                hosterName = "KissKH",
                hosterUrl = videoUrl,
                internalData = id,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val id = hoster.internalData
        val videoUrl = hoster.hosterUrl.takeIf(String::isNotBlank) ?: return emptyList()

        val subList = try {
            val subKey = requestSubKey(id)
            client.get("$baseUrl/api/Sub/$id?kkey=$subKey")
                .parseAs<List<SubtitleDto>>()
                .parallelCatchingMapNotNull { item ->
                    val suburl = item.src?.takeIf(String::isNotBlank) ?: return@parallelCatchingMapNotNull null
                    val lang = item.label?.takeIf(String::isNotBlank) ?: "Unknown"
                    if (suburl.contains(".txt")) {
                        subDecryptor.getSubtitles(suburl, lang)
                    } else {
                        Track(suburl, lang)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KissKH", "Failed to fetch subtitles: ${e.message}")
            emptyList()
        }

        val fixedVideoUrl = UrlUtils.fixUrl(videoUrl) ?: return emptyList()
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        val video = Video(
            videoUrl = fixedVideoUrl,
            videoTitle = "FirstParty",
            subtitleTracks = subList,
            headers = videoHeaders,
            mpvArgs = listOf("sub-ass-override" to "strip"),
        )

        if (!fixedVideoUrl.toHttpUrl().encodedPath.endsWith(".m3u8", ignoreCase = true)) {
            return listOf(video)
        }

        return try {
            withContext(Dispatchers.IO) {
                playlistUtils.extractFromHls(
                    playlistUrl = fixedVideoUrl,
                    referer = "$baseUrl/",
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "FirstParty - $it" },
                    subtitleList = subList,
                )
            }.ifEmpty { listOf(video) }
                .sortedWith(
                    compareByDescending<Video> { it.videoTitle.contains(preferredQuality) }
                        .thenByDescending { video ->
                            QUALITY_REGEX.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        },
                )
                .mapIndexed { index, v ->
                    v.copy(
                        preferred = index == 0,
                        mpvArgs = video.mpvArgs,
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KissKH", "Failed to extract HLS qualities: ${e.message}")
            listOf(video)
        }
    }

    private suspend fun requestVideoKey(id: String): String {
        videoKeyCache[id]?.let { return it }
        val url = "${BuildConfig.KISSKH_API}$id&version=2.8.10"
        return client.get(url).parseAs<KeyDto>().key.also { videoKeyCache.put(id, it) }
    }

    private suspend fun requestSubKey(id: String): String {
        subKeyCache[id]?.let { return it }
        val url = "${BuildConfig.KISSKH_SUB_API}$id&version=2.8.10"
        return client.get(url).parseAs<KeyDto>().key.also { subKeyCache.put(id, it) }
    }

    private suspend fun getCountdownDetails(url: String?): String? = try {
        val widgetUrl = UrlUtils.fixUrl(url ?: return null) ?: return null
        val html = client.get(widgetUrl, cacheControl = CacheControl.FORCE_NETWORK).bodyString()
        val match = COUNTDOWN_REGEX.find(html) ?: return null
        val (dateStr, tzStr) = match.destructured
        val target = synchronized(countdownDateFormat) {
            countdownDateFormat.timeZone = TimeZone.getTimeZone(tzStr)
            countdownDateFormat.tryParse(dateStr).takeIf { it > 0L }
        } ?: return null
        val diff = target - System.currentTimeMillis()
        if (diff <= 0) {
            "airs soon"
        } else {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
            buildString {
                append("airs in ")
                if (days > 0) append("${days}d ")
                if (hours > 0 || days > 0) append("${hours}h ")
                append("${minutes}m")
            }.trim()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            subDecryptor = SubDecryptor(client, headers, baseUrl)
        }

        screen.addSwitchPreference(
            key = PREF_HIDE_UNAIRED_KEY,
            title = "Hide unaired episodes",
            summary = "Hide upcoming episodes that only have a countdown timer",
            default = PREF_HIDE_UNAIRED_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    private val titleUriRegex by lazy { Regex("[^a-zA-Z0-9]") }

    companion object {
        private const val PAGE_SIZE = 40
        private const val TYPE_COUNTDOWN = 2

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "kisskh.ovh",
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
            "kisskh.is",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        private const val PREF_HIDE_UNAIRED_KEY = "pref_hide_unaired_episodes"
        private const val PREF_HIDE_UNAIRED_DEFAULT = true

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val COUNTDOWN_REGEX by lazy {
            Regex("""window\.countdown\("([^"]+)",\s*"[^"]*",\s*\d+,\s*"[^"]*",\s*"([^"]+)"""")
        }

        private val QUALITY_REGEX by lazy { Regex("""(\d+)p?""") }
    }
}
