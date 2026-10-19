package eu.kanade.tachiyomi.animeextension.all.yfantasy

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.yfantasy.YFantasy.Companion.MAX_ID_GAP
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
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

    // =========================== Related Anime ============================

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val catalog = getCatalog()
        val tags = (catalog.firstOrNull { it.videoId == anime.url } ?: getFeedEntry(anime.url))
            ?.tags
            ?.toSet()
            .orEmpty()

        if (tags.isEmpty()) return emptyList()

        return catalog.asSequence()
            .filterNot { it.videoId == anime.url }
            .map { entry -> entry to entry.tags.count(tags::contains) }
            .filter { (_, shared) -> shared > 0 }
            .sortedByDescending { (_, shared) -> shared }
            .take(MAX_RELATED_ENTRIES)
            .map { (entry, _) -> entry.toSAnime() }.toList()
    }

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

        val fallbackVideo = Video(
            videoUrl = "${episode.url}/$FALLBACK_FILE",
            videoTitle = "Fallback - 720p",
            headers = headers,
        )

        return listOf(
            Hoster(
                hosterName = "CDN",
                videoList = getRenditionVideos(episode) + fallbackVideo,
            ),
        )
    }

    /**
     * Offers every video rendition paired with a single audio rendition, rather than the master
     * playlist or one video with the other languages as external tracks. The player's FFmpeg
     * cannot seek a freshly opened fMP4 HLS playlist past its first segment, so the master's
     * late audio refill drops the buffered video, and switching to an external audio track
     * mid-playback yields no sound. A language is picked by switching video instead.
     */
    private suspend fun getRenditionVideos(episode: SEpisode): List<Video> {
        val masterUrl = episode.videoUrl.toHttpUrl()
        val masterVideo = Video(videoUrl = masterUrl.toString(), videoTitle = "Auto", headers = headers)

        val lines = try {
            client.get(masterUrl, headers).body.string().lines()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read the master playlist $masterUrl", e)
            return listOf(masterVideo)
        }

        val audioAttributes = lines
            .filter { it.startsWith(MEDIA_TAG) }
            .map { it.hlsAttributes() }
            .filter { it["TYPE"] == "AUDIO" && it["URI"] != null }

        val originalLanguage = getSpeechLanguage(episode)
        val audios = audioAttributes.map { attributes ->
            val language = attributes["LANGUAGE"]?.primaryLanguage()
            val isOriginal = when {
                originalLanguage != null -> language == originalLanguage
                else -> attributes["DEFAULT"] == "YES" || audioAttributes.size == 1
            }

            Audio(
                url = masterUrl.resolve(attributes["URI"]!!).toString(),
                language = language,
                name = language?.toLanguageName() ?: attributes["NAME"].orEmpty(),
                isOriginal = isOriginal,
            )
        }

        val renditions = lines.withIndex()
            .filter { (_, line) -> line.startsWith(STREAM_INF_TAG) }
            .mapNotNull { (index, line) ->
                val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: return@mapNotNull null
                val attributes = line.hlsAttributes()
                val codecs = attributes["CODECS"].orEmpty()

                Rendition(
                    url = masterUrl.resolve(uri).toString(),
                    encoding = if ("hvc1" in codecs || "hev1" in codecs) ENCODING_HEVC else ENCODING_H264,
                    quality = attributes["RESOLUTION"]
                        ?.split("x")
                        ?.mapNotNull(String::toIntOrNull)
                        ?.minOrNull(),
                )
            }

        val encoding = preferences.encoding
        val quality = preferences.quality.toIntOrNull()
        val language = preferences.audioLanguage

        val pairs = renditions.flatMap { rendition ->
            audios.ifEmpty { listOf(null) }.map { audio -> rendition to audio }
        }

        val singleAudioVideos = pairs
            .sortedWith(
                compareByDescending<Pair<Rendition, Audio?>> { (rendition, _) -> rendition.encoding == encoding }
                    .thenByDescending { (rendition, _) -> rendition.quality == quality }
                    .thenByDescending { (_, audio) -> audio.matches(language) }
                    .thenByDescending { (rendition, _) -> rendition.quality ?: 0 }
                    .thenByDescending { (_, audio) -> audio?.isOriginal == true },
            )
            .map { (rendition, audio) ->
                Video(
                    videoUrl = rendition.url,
                    videoTitle = rendition.title + audio?.let { " - ${it.label}" }.orEmpty(),
                    headers = headers,
                    preferred = rendition.encoding == encoding &&
                        rendition.quality == quality &&
                        audio.matches(language),
                    audioTracks = listOfNotNull(audio?.toTrack()),
                )
            }

        // Every language in one video, mostly for downloads: the preferred language comes
        // first, as it is the track selected when the player has no language preference.
        val allAudioTracks = audios
            .sortedWith(compareByDescending<Audio> { it.matches(language) }.thenByDescending { it.isOriginal })
            .map { it.toTrack() }

        val allAudioVideos = if (audios.size < 2) {
            emptyList()
        } else {
            renditions
                .sortedWith(
                    compareByDescending<Rendition> { it.encoding == encoding }
                        .thenByDescending { it.quality == quality }
                        .thenByDescending { it.quality ?: 0 },
                )
                .map { rendition ->
                    Video(
                        videoUrl = rendition.url,
                        videoTitle = "${rendition.title} - All audio",
                        headers = headers,
                        audioTracks = allAudioTracks,
                    )
                }
        }

        return (singleAudioVideos + allAudioVideos).ifEmpty { listOf(masterVideo) }
    }

    /** The catalog is the only source of the speech language, keyed by the episode's stream. */
    private suspend fun getSpeechLanguage(episode: SEpisode): String? = try {
        getCatalog().asSequence()
            .flatMap { it.segments }
            .firstOrNull { it.videoBaseUrl == episode.url }
            ?.speechLanguage
            ?.primaryLanguage()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Could not read the speech language of ${episode.url}", e)
        null
    }

    private class Rendition(val url: String, val encoding: String, val quality: Int?) {
        val title: String
            get() = encoding + quality?.let { " ${it}p" }.orEmpty()
    }

    private class Audio(val url: String, val language: String?, val name: String, val isOriginal: Boolean) {
        val label: String
            get() = if (isOriginal) "$name (Original)" else name

        fun toTrack() = Track(url, label)
    }

    /** A missing audio rendition matches any preference, as there is nothing to choose from. */
    private fun Audio?.matches(preference: String): Boolean = when {
        this == null -> true
        preference == AUDIO_ORIGINAL -> isOriginal
        else -> language == preference
    }

    private fun String.primaryLanguage(): String = substringBefore("-").lowercase()

    private fun String.hlsAttributes(): Map<String, String> = HLS_ATTRIBUTE_REGEX
        .findAll(substringAfter(":"))
        .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

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

    private val SharedPreferences.encoding by preferences.delegate(PREF_ENCODING_KEY, ENCODING_HEVC)

    private val SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val SharedPreferences.audioLanguage by preferences.delegate(PREF_AUDIO_KEY, AUDIO_ORIGINAL)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_ENCODING_KEY,
            default = ENCODING_HEVC,
            title = "Preferred video encoding",
            summary = "%s",
            entries = listOf(ENCODING_HEVC, ENCODING_H264),
            entryValues = listOf(ENCODING_HEVC, ENCODING_H264),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred video quality",
            summary = "%s",
            entries = PREF_QUALITY_VALUES.map { "${it}p" },
            entryValues = PREF_QUALITY_VALUES,
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = AUDIO_ORIGINAL,
            title = "Preferred audio language",
            summary = "%s",
            entries = listOf("Original") + AUDIO_LANGUAGES.map { it.toLanguageName() },
            entryValues = listOf(AUDIO_ORIGINAL) + AUDIO_LANGUAGES,
        )
    }

    companion object {
        private const val FEED_URL = "https://yfantasy.me/api/videos/feed"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/baka-bon/truyen/refs/heads/master/yfantasy.json"

        private const val FIRST_VIDEO_ID = 10000
        private const val MAX_RELATED_ENTRIES = 20
        private const val MAX_ID_GAP = 3
        private const val MAX_ID_PROBES = 30

        private const val PLAYLIST_FILE = "playlist.m3u8"
        private const val FALLBACK_FILE = "play_720p.mp4"

        private const val LOG_TAG = "YFantasy"
        private const val MEDIA_TAG = "#EXT-X-MEDIA:"
        private const val STREAM_INF_TAG = "#EXT-X-STREAM-INF:"
        private val HLS_ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        private const val ENCODING_HEVC = "HEVC"
        private const val ENCODING_H264 = "H.264"
        private const val AUDIO_ORIGINAL = "original"
        private val AUDIO_LANGUAGES = listOf("zh", "en", "ja", "ko")

        private const val PREF_ENCODING_KEY = "pref_video_encoding"
        private const val PREF_QUALITY_KEY = "pref_video_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_VALUES = listOf("720", "480")
        private const val PREF_AUDIO_KEY = "pref_audio_language"

        private const val PREF_LATEST_ID_KEY = "pref_latest_video_id"
        private const val PREF_LATEST_ID_DEFAULT = FIRST_VIDEO_ID
    }
}
