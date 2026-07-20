package keiyoushi.templating

import android.content.SharedPreferences

/**
 * Mixin for extensions that support multiple mirror sites/domains.
 *
 * This provides a standard pattern for:
 * - Domain preference storage and retrieval
 * - Client rebuild on domain change
 * - Old preference cleanup on domain migration
 *
 * Usage in extensions:
 * ```kotlin
 * class MyExtension : AnimeExtension(), MirrorSupport {
 *     override val mirrorSupportEntries = listOf("site1.com", "site2.com", "site3.com")
 *     override val mirrorSupportDefault = "site1.com"
 *     override fun onMirrorChanged(newDomain: String) {
 *         // Rebuild client with new rate-limit host
 *     }
 * }
 * ```
 */
interface MirrorSupport {
    /** List of available mirror domains. */
    val mirrorSupportEntries: List<String>

    /** Default domain to use. */
    val mirrorSupportDefault: String

    /** Preference key for storing the selected domain. */
    val mirrorSupportPrefKey: String get() = "preferred_domain"

    /** Preference title shown in settings UI. */
    val mirrorSupportPrefTitle: String get() = "Preferred domain (requires app restart)"

    /**
     * Called when the user selects a new mirror domain.
     * Use this to rebuild clients, update headers, etc.
     */
    fun onMirrorChanged(newDomain: String)

    /**
     * Get the current base URL from preferences.
     *
     * @param preferences The SharedPreferences instance
     * @return The current base URL (with protocol)
     */
    fun getMirrorBaseUrl(preferences: SharedPreferences): String = preferences.getString(mirrorSupportPrefKey, mirrorSupportDefault)
        ?: "https://$mirrorSupportDefault"

    /**
     * Set the base URL in preferences.
     *
     * @param preferences The SharedPreferences instance
     * @param baseUrl The new base URL (with protocol)
     */
    fun setMirrorBaseUrl(preferences: SharedPreferences, baseUrl: String) {
        preferences.edit().putString(mirrorSupportPrefKey, baseUrl).apply()
    }

    /**
     * Clear old/invalid domain preferences.
     *
     * Call this during preference migration to reset domains that
     * are no longer in the mirrorSupportEntries list.
     *
     * @param preferences The SharedPreferences instance
     */
    fun clearOldMirrorPrefs(preferences: SharedPreferences) {
        val currentDomain = preferences.getString(mirrorSupportPrefKey, null)
        val validDomains = mirrorSupportEntries.map { "https://$it" }

        if (currentDomain != null && currentDomain !in validDomains) {
            preferences.edit().putString(mirrorSupportPrefKey, mirrorSupportDefault).apply()
        }
    }

    /**
     * Get the full URL for a mirror domain.
     */
    fun mirrorFullUrl(domain: String): String = if (domain.startsWith("http")) domain else "https://$domain"
}

/**
 * Abstract base class for extensions that support multiple mirrors.
 *
 * Extends [AnimeExtension] and implements [MirrorSupport] with
 * standard preference handling and client rebuild support.
 */
abstract class MirrorAnimeExtension :
    AnimeExtension(),
    MirrorSupport {

    override fun onMirrorChanged(newDomain: String) {
        // Default implementation: subclasses can override for custom behavior
    }

    /**
     * Get the current base URL.
     */
    fun currentBaseUrl(): String = getMirrorBaseUrl(preferences)

    /**
     * Setup mirror preference in the preference screen.
     *
     * Call this from setupPreferenceScreen() if you need custom
     * preference ordering, otherwise it's handled automatically
     * via preferenceSchema.
     */
    fun setupMirrorPreference() {
        // This is a helper for extensions that don't use the schema system
        // For schema-based extensions, add a PreferenceEntry.List to preferenceSchema
    }
}
