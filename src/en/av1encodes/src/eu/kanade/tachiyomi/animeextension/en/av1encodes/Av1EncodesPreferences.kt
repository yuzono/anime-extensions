package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference

internal const val PREF_QUALITY_KEY = "preferred_quality"
internal val QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
internal val QUALITY_VALUES = listOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360")
internal val PREF_QUALITY_DEFAULT = QUALITY_VALUES.first()

internal const val PREF_LINK_TYPE_KEY = "preferred_link_type"
internal const val PREF_LINK_TYPE_DEFAULT = "Stream"
internal val LINK_TYPE_ENTRIES = listOf("Stream", "Direct DL", "Dash", "Torrent")

internal const val PREF_SHOW_TORRENT_KEY = "show_torrent"
internal const val PREF_SHOW_TORRENT_DEFAULT = false

internal fun buildPreferenceScreen(screen: PreferenceScreen) {
    screen.addListPreference(
        key = PREF_QUALITY_KEY,
        title = "Preferred Resolution",
        summary = "%s\n\nIf a season shows no episodes, try a lower resolution.",
        entries = QUALITY_ENTRIES,
        entryValues = QUALITY_VALUES,
        default = PREF_QUALITY_DEFAULT,
    )

    screen.addListPreference(
        key = PREF_LINK_TYPE_KEY,
        title = "Preferred Server / Hoster",
        summary = "%s — this hoster will appear first in the server list.",
        entries = LINK_TYPE_ENTRIES,
        entryValues = LINK_TYPE_ENTRIES,
        default = PREF_LINK_TYPE_DEFAULT,
    )

    screen.addSwitchPreference(
        key = PREF_SHOW_TORRENT_KEY,
        title = "Show Torrent Link",
        summary = "Include the torrent link as a server option.",
        default = PREF_SHOW_TORRENT_DEFAULT,
    )
}

private val qualityNumberRegex by lazy { Regex("""\d{3,4}""") }
private val resolutionRegex by lazy { Regex("""(\d{3,4})p""") }

internal fun List<Video>.sortByPreferredQuality(preferences: SharedPreferences): List<Video> {
    val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
    val preferredResolution = qualityNumberRegex.findAll(preferredQuality).lastOrNull()?.value ?: preferredQuality
    return sortedWith(
        compareByDescending<Video> { it.videoTitle.contains(preferredResolution, ignoreCase = true) }
            .thenByDescending { resolutionRegex.find(it.videoTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 },
    )
}
