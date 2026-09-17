package keiyoushi.templating

import android.content.SharedPreferences

/**
 * Mixin for extensions that support video type toggling (Sub/Dub/Raw/Mixed).
 *
 * This provides a standard pattern for:
 * - Type preference storage and retrieval
 * - Type filtering based on preference
 *
 * Usage in extensions:
 * ```kotlin
 * class MyExtension : AnimeExtension(), TypeToggle {
 *     override val typeTogglePrefKey = "preferred_type"
 *     override val typeToggleEntries = listOf("Sub", "Dub", "Raw")
 *     override val typeToggleDefault = "Sub"
 * }
 * ```
 */
interface TypeToggle {
    /** Preference key for video type. */
    val typeTogglePrefKey: String get() = "preferred_type"

    /** Available type options. */
    val typeToggleEntries: List<String> get() = listOf("Sub", "Dub", "Mixed", "Raw")

    /** Default type. */
    val typeToggleDefault: String get() = "Sub"

    /** Preference title shown in settings UI. */
    val typeTogglePrefTitle: String get() = "Preferred Type"

    /**
     * Get the preferred video type.
     *
     * @param preferences The SharedPreferences instance
     * @return The preferred type string
     */
    fun getPreferredType(preferences: SharedPreferences): String = preferences.getString(typeTogglePrefKey, typeToggleDefault)
        ?: typeToggleDefault

    /**
     * Check if a video matches the preferred type.
     *
     * @param videoType The type of the video (e.g., "Sub", "Dub")
     * @param preferences The SharedPreferences instance
     * @return true if the video matches the preferred type
     */
    fun matchesPreferredType(videoType: String, preferences: SharedPreferences): Boolean {
        val preferred = getPreferredType(preferences)
        return videoType.equals(preferred, ignoreCase = true)
    }

    /**
     * Get the CSS class selector for the preferred type.
     *
     * Used by themes like ZoroTheme that use CSS classes for type selection.
     *
     * @param preferences The SharedPreferences instance
     * @return CSS class name (e.g., "servers-sub", "servers-dub")
     */
    fun getTypeCssClass(preferences: SharedPreferences): String {
        val type = getPreferredType(preferences).lowercase()
        return "servers-$type"
    }
}

/**
 * Abstract base class for extensions with type toggle support.
 */
abstract class TypeToggleExtension :
    AnimeExtension(),
    TypeToggle {

    /**
     * Get the preferred video type.
     */
    fun getPreferredType(): String = getPreferredType(preferences)

    /**
     * Check if a video matches the preferred type.
     */
    fun matchesPreferredType(videoType: String): Boolean = matchesPreferredType(videoType, preferences)
}
