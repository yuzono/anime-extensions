package eu.kanade.tachiyomi.animeextension.en.luciferdonghua

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.animeextension.en.luciferdonghua.extractors.RumbleExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import kotlinx.coroutines.runBlocking
import okhttp3.Response

class LuciferDonghua :
    AnimeStream(
        "en",
        "LuciferDonghua",
        "https://luciferdonghua.in",
    ) {

    // ============================== Preferences ==============================
    override val prefQualityValues = arrayOf("2160p", "1440p", "1080p", "720p", "480p", "360p")
    override val prefQualityEntries = prefQualityValues

    private var SharedPreferences.ignorePreview
        by LazyMutable { preferences.getBoolean(IGNORE_PREVIEW_KEY, IGNORE_PREVIEW_DEFAULT) }

    private var SharedPreferences.enabledHosters
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!! }

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
            preferences.enabledHosters = it
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

    private companion object {
        private const val PREF_HOSTER_KEY = "luciferdonghua_hoster_selection"
        private val INTERNAL_HOSTER_NAMES = listOf("Dailymotion", "Rumble", "Ok.ru")
        private val PREF_HOSTER_ENTRY_VALUES = INTERNAL_HOSTER_NAMES.map { it.lowercase() }
        private val PREF_HOSTER_DEFAULT = INTERNAL_HOSTER_NAMES.map { it.lowercase() }.toSet()

        private const val IGNORE_PREVIEW_KEY = "luciferdonghua_ignore_preview"
        private const val IGNORE_PREVIEW_DEFAULT = true
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.eplister > ul > li a"

    override fun getEpisodeNumber(epNum: String) = epNum.replace("[4K]", "").substringBefore(" ").toFloatOrNull() ?: 0F

    override fun episodeListParse(response: Response): List<SEpisode> {
        var episodeList = super.episodeListParse(response)
        if (preferences.ignorePreview && episodeList.size > 2) {
            episodeList = episodeList.sortedByDescending { it.episode_number }
            if (episodeList[0].date_upload < episodeList[1].date_upload) {
                episodeList = episodeList.drop(1)
            }
        }
        return episodeList
    }

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> = runBlocking {
        val prefix = "$name - "
        when {
            preferences.enabledHosters.contains("ok.ru") && url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix)
            preferences.enabledHosters.contains("dailymotion") && url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
            preferences.enabledHosters.contains("rumble") && url.contains("rumble") -> rumbleExtractor.videosFromUrl(url, prefix)
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
