package eu.kanade.tachiyomi.animesource

import androidx.preference.PreferenceScreen

/**
 * An interface to add user preferences to the source.
 */
interface ConfigurableAnimeSource {
    /**
     * Implementations must override this method to add the user preferences.
     *
     * You can use some stubbed inheritors of [androidx.preference.Preference] here.
     *
     * **Common usage example:**
     * ```
     * // ============================== Settings ==============================
     * override fun setupPreferenceScreen(screen: PreferenceScreen) {
     *     ListPreference(screen.context).apply {
     *         key = PREF_QUALITY_KEY // String, like "pref_quality"
     *         title = PREF_QUALITY_TITLE // String, like "Preferred quality:"
     *         entries = PREF_QUALITY_ENTRIES // Array<String>, like arrayOf("240p", "720p"...)
     *         // Another Array<String>. Can be different from the property above, as long it have the same size
     *         // and equivalent values per index.
     *         entryValues = PREF_QUALITY_VALUES
     *         setDefaultValue(PREF_QUALITY_DEFAULT)
     *         summary = "%s"
     *     }.also(screen::addPreference)
     * }
     * ```
     */
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
