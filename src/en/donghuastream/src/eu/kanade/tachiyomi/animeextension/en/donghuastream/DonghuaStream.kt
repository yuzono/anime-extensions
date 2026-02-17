package eu.kanade.tachiyomi.animeextension.en.donghuastream

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors.RumbleExtractor
import eu.kanade.tachiyomi.animeextension.en.donghuastream.extractors.StreamPlayExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import okhttp3.Response

class DonghuaStream :
    AnimeStream(
        "en",
        "DonghuaStream",
        "https://donghuastream.org",
    ) {
    override val fetchFilters: Boolean
        get() = false

    // ============================ Manual Changes ==========================

    override fun popularAnimeNextPageSelector() = "div.mrgn a.r"

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/pagg/$page/?s=$query")

    private var SharedPreferences.ignorePreview
        by LazyMutable { preferences.getBoolean(IGNORE_PREVIEW_KEY, IGNORE_PREVIEW_DEFAULT) }

    private var SharedPreferences.getHosters
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!! }

    private companion object {
        private const val PREF_HOSTER_KEY = "dm_hoster_selection"
        private val INTERNAL_HOSTER_NAMES = listOf("Dailymotion", "Streamplay", "Rumble", "Ok.ru")
        private val PREF_HOSTER_ENTRY_VALUES = INTERNAL_HOSTER_NAMES.map { it.lowercase() }.toList()
        private val PREF_HOSTER_DEFAULT = INTERNAL_HOSTER_NAMES.map { it.lowercase() }.toSet()

        private const val IGNORE_PREVIEW_KEY = "dm_ignore_preview"
        private const val IGNORE_PREVIEW_DEFAULT = true
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "",
            entries = INTERNAL_HOSTER_NAMES,
            entryValues = PREF_HOSTER_ENTRY_VALUES,
            default = PREF_HOSTER_DEFAULT,
        ) {
            preferences.getHosters = it
        }

        screen.addSwitchPreference(
            key = IGNORE_PREVIEW_KEY,
            title = "Skip Preview episodes",
            summary = "",
            default = IGNORE_PREVIEW_DEFAULT,
        ) {
            preferences.ignorePreview = it
        }
    }

    override val prefQualityValues = arrayOf("2160p", "1440p", "1080p", "720p", "480p", "360p")
    override val prefQualityEntries = prefQualityValues

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response)
            .filter { !it.name.contains("Preview") || !preferences.ignorePreview }
    }

    // ============================ Video Links =============================

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val streamPlayExtractor by lazy { StreamPlayExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            preferences.getHosters.contains("dailymotion") and url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix = prefix)
            preferences.getHosters.contains("streamplay") and url.contains("streamplay") -> streamPlayExtractor.videosFromUrl(url, prefix = prefix)
            preferences.getHosters.contains("ok.ru") and url.contains("ok.ru") -> okruExtractor.videosFromUrl(url = if (url.startsWith("//")) "https:$url" else url, prefix = prefix)
            preferences.getHosters.contains("rumble") and url.contains("rumble") -> rumbleExtractor.videosFromUrl(url, prefix = prefix)
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
