package keiyoushi.utils

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen

/**
 * Factory for creating common preference types.
 */
object PreferenceFactory {
    /**
     * Create a quality preference.
     */
    fun createQualityPreference(
        screen: PreferenceScreen,
        key: String = "preferred_quality",
        title: String = "Preferred quality",
        default: String = "720",
        entries: List<String> = listOf("1080p", "720p", "480p", "360p"),
        entryValues: List<String> = listOf("1080", "720", "480", "360"),
        onComplete: (String) -> Unit = {},
    ): ListPreference = screen.getListPreference(
        key = key,
        default = default,
        title = title,
        summary = "%s",
        entries = entries,
        entryValues = entryValues,
        onComplete = onComplete,
    ).also(screen::addPreference)

    /**
     * Create a server preference.
     */
    fun createServerPreference(
        screen: PreferenceScreen,
        key: String = "preferred_server",
        title: String = "Preferred server",
        default: String,
        entries: List<String>,
        entryValues: List<String> = entries,
        onComplete: (String) -> Unit = {},
    ): ListPreference = screen.getListPreference(
        key = key,
        default = default,
        title = title,
        summary = "%s",
        entries = entries,
        entryValues = entryValues,
        onComplete = onComplete,
    ).also(screen::addPreference)

    /**
     * Create a hoster selection preference.
     */
    fun createHosterSelectionPreference(
        screen: PreferenceScreen,
        key: String = "hoster_selection",
        title: String = "Enable/Disable Hosts",
        summary: String = "Select which video hosts to show",
        entries: List<String>,
        default: Set<String> = entries.toSet(),
        onComplete: (Set<String>) -> Unit = {},
    ): MultiSelectListPreference = screen.getSetPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        entries = entries,
        entryValues = entries,
        onComplete = onComplete,
    ).also(screen::addPreference)

    /**
     * Create a domain preference for mirror sites.
     */
    fun createDomainPreference(
        screen: PreferenceScreen,
        key: String = "preferred_domain",
        title: String = "Preferred domain (requires app restart)",
        default: String,
        entries: List<String>,
        entryValues: List<String>,
        onComplete: (String) -> Unit = {},
    ): ListPreference = screen.getListPreference(
        key = key,
        default = default,
        title = title,
        summary = "%s",
        entries = entries,
        entryValues = entryValues,
        restartRequired = true,
        onComplete = onComplete,
    ).also(screen::addPreference)

    /**
     * Create a title language preference.
     */
    fun createTitleLanguagePreference(
        screen: PreferenceScreen,
        key: String = "preferred_title_lang",
        title: String = "Preferred title language",
        default: String = "Romaji",
        entries: List<String> = listOf("Romaji", "English"),
        entryValues: List<String> = listOf("Romaji", "English"),
        onComplete: (String) -> Unit = {},
    ): ListPreference = screen.getListPreference(
        key = key,
        default = default,
        title = title,
        summary = "%s",
        entries = entries,
        entryValues = entryValues,
        onComplete = onComplete,
    ).also(screen::addPreference)

    /**
     * Create a type toggle preference (Sub/Dub/Raw).
     */
    fun createTypeTogglePreference(
        screen: PreferenceScreen,
        key: String = "preferred_type",
        title: String = "Preferred Type",
        default: String = "Sub",
        entries: List<String> = listOf("Sub", "Dub", "Mixed", "Raw"),
        entryValues: List<String> = listOf("Sub", "Dub", "Mixed", "Raw"),
        onComplete: (String) -> Unit = {},
    ): ListPreference = screen.getListPreference(
        key = key,
        default = default,
        title = title,
        summary = "%s",
        entries = entries,
        entryValues = entryValues,
        onComplete = onComplete,
    ).also(screen::addPreference)
}
