package eu.kanade.tachiyomi.animeextension.all.yfantasy

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.Source
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl

class YFantasy : Source() {

    override val name = "YFantasy"

    override val baseUrl = "https://yfantasy.me"

    override val lang = "all"

    override val supportsLatest = true

    /** The video CDN serves thumbnails and streams only to requests referred from the site. */
    override fun headersBuilder() = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = getCatalog().toAnimesPage()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val catalog = getCatalog()
        val newestId = updateLatestId(catalog)
        val catalogId = catalog.maxOfOrNull { it.numericId } ?: FIRST_VIDEO_ID

        val newEntries = ((catalogId + 1)..newestId)
            .map(Int::toString)
            .parallelCatchingMapNotNull { getFeedEntry(it) }

        return (newEntries + catalog)
            .sortedByDescending { it.numericId }
            .toAnimesPage()
    }

    /**
     * Probes for ids newer than the stored one, tolerating up to [MAX_ID_GAP] unpublished ids
     * in a row, and stores the newest one that answered with a matching entry.
     */
    private suspend fun updateLatestId(catalog: List<AnimeDto>): Int {
        val storedId = maxOf(preferences.latestId, catalog.maxOfOrNull { it.numericId } ?: FIRST_VIDEO_ID)
        var newestId = storedId
        var candidateId = storedId + 1

        while (candidateId <= newestId + MAX_ID_GAP && candidateId <= storedId + MAX_ID_PROBES) {
            if (getFeedEntry(candidateId.toString()) != null) {
                newestId = candidateId
            }
            candidateId++
        }

        if (newestId != preferences.latestId) {
            preferences.latestId = newestId
        }

        return newestId
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.isBlank()) {
            val random = filters.firstInstanceOrNull<RandomFilter>()?.state == true

            return if (random) getRandomFeed().toAnimesPage() else AnimesPage(emptyList(), false)
        }

        return getCatalog()
            .filter { it.displayTitle.contains(query, ignoreCase = true) }
            .toAnimesPage()
    }

    // =============================== Filters ==============================

    class RandomFilter : AnimeFilter.CheckBox("Random", false)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Search the catalog by title, or leave it empty and tick Random"),
        RandomFilter(),
    )

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = getEntry(anime.url).toSAnime()

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/en?video=${anime.url}"

    // ============================== Episodes ==============================

    /** Feed entries only carry their public segment, so the rest is extracted from the site. */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val entry = getCatalog().firstOrNull { it.videoId == anime.url }
            ?: episodeExtractor.getAnime(anime.url)
            ?: getFeedEntry(anime.url)
            ?: throw Exception("Video ${anime.url} not found")

        return entry.segments.map { it.toSEpisode() }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = if (episode.isLocked) {
        "$baseUrl/en?video=${episode.url.substringBefore("_")}"
    } else {
        episode.videoUrl
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        if (episode.isLocked) return emptyList()

        // Only the master playlist is offered: it ties the separate audio renditions to the
        // video ones by itself, while the single renditions are video only.
        val masterVideo = legacyVideo(
            videoUrl = episode.videoUrl,
            videoTitle = "Auto",
            headers = headers,
        )

        val fallbackVideo = legacyVideo(
            videoUrl = "${episode.url}/$FALLBACK_FILE",
            videoTitle = "Fallback - 720p",
            headers = headers,
        )

        return (listOf(masterVideo) + fallbackVideo).toHosterList()
    }

    // ============================= Utilities ==============================

    private val episodeExtractor by lazy { EpisodeExtractor(client, headers, baseUrl) }

    private suspend fun getCatalog(): List<AnimeDto> = client.get(CATALOG_URL, headers).parseAs()

    private suspend fun getRandomFeed(): List<FeedItemDto> {
        val url = FEED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("shuffleTop", "1")
            .build()

        return client.get(url, headers).parseAs<FeedDto>().items
    }

    /** The feed answers with its own first entry when the requested id does not exist. */
    private suspend fun getFeedEntry(videoId: String): FeedItemDto? {
        val url = FEED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("limit", "1")
            .addQueryParameter("videoId", videoId)
            .build()

        return client.get(url, headers)
            .parseAs<FeedDto>()
            .items.firstOrNull()
            ?.takeIf { it.videoId == videoId }
    }

    /** The catalog takes precedence, as it is the only source that carries the hidden segments. */
    private suspend fun getEntry(videoId: String): VideoEntry = getCatalog().firstOrNull { it.videoId == videoId }
        ?: getFeedEntry(videoId)
        ?: throw Exception("Video $videoId not found")

    private fun List<VideoEntry>.toAnimesPage() = AnimesPage(map { it.toSAnime() }, false)

    private val VideoEntry.numericId: Int
        get() = videoId.toIntOrNull() ?: FIRST_VIDEO_ID

    /** Locked segments carry their segment id instead of a stream url. */
    private val SEpisode.isLocked: Boolean
        get() = !url.startsWith("http")

    private val SEpisode.videoUrl: String
        get() = "$url/$PLAYLIST_FILE"

    // ============================ Preferences =============================

    private var SharedPreferences.latestId by preferences.delegate(PREF_LATEST_ID_KEY, PREF_LATEST_ID_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    companion object {
        private const val FEED_URL = "https://yfantasy.me/api/videos/feed"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/baka-bon/truyen/refs/heads/master/yfantasy.json"

        private const val FIRST_VIDEO_ID = 10000
        private const val MAX_ID_GAP = 3
        private const val MAX_ID_PROBES = 30

        private const val PLAYLIST_FILE = "playlist.m3u8"
        private const val FALLBACK_FILE = "play_720p.mp4"

        private const val PREF_LATEST_ID_KEY = "pref_latest_video_id"
        private const val PREF_LATEST_ID_DEFAULT = FIRST_VIDEO_ID
    }
}
