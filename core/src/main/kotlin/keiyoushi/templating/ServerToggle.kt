package keiyoushi.templating

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * Mixin for extensions that support toggling video servers/hosters.
 *
 * This provides a standard pattern for:
 * - Server selection preference
 * - Excluded server filtering
 * - Server availability tracking
 *
 * Usage in extensions:
 * ```kotlin
 * class MyExtension : AnimeExtension(), ServerToggle {
 *     override val serverToggleEntries = listOf("Server1", "Server2", "Server3")
 *     override val serverTogglePrefKey = "hoster_selection"
 * }
 * ```
 */
interface ServerToggle {
    /** List of available server/hoster names. */
    val serverToggleEntries: List<String>

    /** Preference key for storing selected/excluded servers. */
    val serverTogglePrefKey: String get() = "hoster_selection"

    /** Preference title shown in settings UI. */
    val serverTogglePrefTitle: String get() = "Enable/Disable Hosts"

    /** Preference summary. */
    val serverTogglePrefSummary: String get() = "Select which video hosts to show"

    /**
     * Get the set of enabled servers from preferences.
     *
     * @param preferences The SharedPreferences instance
     * @return Set of enabled server names
     */
    fun getEnabledServers(preferences: SharedPreferences): Set<String> = preferences.getStringSet(serverTogglePrefKey, serverToggleEntries.toSet())
        ?: serverToggleEntries.toSet()

    /**
     * Get the set of excluded servers from preferences.
     *
     * @param preferences The SharedPreferences instance
     * @return Set of excluded server names
     */
    fun getExcludedServers(preferences: SharedPreferences): Set<String> {
        val enabled = getEnabledServers(preferences)
        return serverToggleEntries.toSet() - enabled
    }

    /**
     * Filter videos by excluding servers not in the enabled set.
     *
     * @param videos List of videos to filter
     * @param preferences The SharedPreferences instance
     * @return Filtered list with only enabled servers
     */
    fun filterVideosByServer(
        videos: List<Video>,
        preferences: SharedPreferences,
    ): List<Video> {
        val excluded = getExcludedServers(preferences)
        if (excluded.isEmpty()) return videos
        return VideoUtils.filterByExcludedServers(videos, excluded)
    }

    /**
     * Check if a server is enabled.
     *
     * @param serverName The server name to check
     * @param preferences The SharedPreferences instance
     * @return true if the server is enabled
     */
    fun isServerEnabled(serverName: String, preferences: SharedPreferences): Boolean = serverName in getEnabledServers(preferences)
}

/**
 * Abstract base class for extensions with server toggle support.
 */
abstract class ServerToggleExtension :
    AnimeExtension(),
    ServerToggle {

    /**
     * Filter videos by excluding servers not in the enabled set.
     */
    fun filterVideos(videos: List<Video>): List<Video> = filterVideosByServer(videos, preferences)

    /**
     * Check if a server is enabled.
     */
    fun isServerEnabled(serverName: String): Boolean = isServerEnabled(serverName, preferences)
}
