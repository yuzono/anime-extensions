package keiyoushi.templating

import androidx.preference.Preference

/**
 * Declarative description of a single preference entry.
 *
 * Extensions declare a list of [PreferenceEntry] instances via
 * [AnimeExtension.preferenceSchema]. The [PreferenceRegistry] wires up
 * typed [keiyoushi.utils.PreferenceDelegate] accessors, auto-persists
 * to [android.content.SharedPreferences], and renders the UI via
 * [PreferenceRegistry.renderTo].
 *
 * Each variant corresponds to one Android preference widget type:
 * - [EditTextPreference]           → EditTextPreference
 * - [ListPreference]               → ListPreference (single-select dropdown)
 * - [SwitchPreferenceCompat]       → SwitchPreferenceCompat (boolean toggle)
 * - [MultiSelectListPreference]    → MultiSelectListPreference
 */
sealed interface PreferenceEntry<T> {
    val key: String
    val title: String
    val summary: String
    val default: T
    val restartRequired: Boolean
    val enabled: Boolean
    val onChange: (Preference, T) -> Boolean
    val onComplete: (T) -> Unit

    /**
     * Single-line text input.
     */
    data class EditTextPreference(
        override val key: String,
        override val title: String,
        override val summary: String,
        override val default: String,
        override val restartRequired: Boolean = false,
        override val enabled: Boolean = true,
        val dialogMessage: String? = null,
        val inputType: Int? = null,
        val validate: ((String) -> Boolean)? = null,
        val validationMessage: ((String) -> String)? = null,
        override val onChange: (Preference, String) -> Boolean = { _, _ -> true },
        override val onComplete: (String) -> Unit = {},
    ) : PreferenceEntry<String>

    /**
     * Single-select dropdown.
     */
    data class ListPreference(
        override val key: String,
        override val title: String,
        override val summary: String,
        override val default: String,
        val entries: kotlin.collections.List<String>,
        val entryValues: kotlin.collections.List<String>,
        override val restartRequired: Boolean = false,
        override val enabled: Boolean = true,
        override val onChange: (Preference, String) -> Boolean = { _, _ -> true },
        override val onComplete: (String) -> Unit = {},
    ) : PreferenceEntry<String>

    /**
     * Boolean toggle.
     */
    data class SwitchPreferenceCompat(
        override val key: String,
        override val title: String,
        override val summary: String,
        override val default: Boolean,
        override val restartRequired: Boolean = false,
        override val enabled: Boolean = true,
        override val onChange: (Preference, Boolean) -> Boolean = { _, _ -> true },
        override val onComplete: (Boolean) -> Unit = {},
    ) : PreferenceEntry<Boolean>

    /**
     * Multi-select checkbox list.
     */
    data class MultiSelectListPreference(
        override val key: String,
        override val title: String,
        override val summary: String,
        override val default: Set<String>,
        val entries: kotlin.collections.List<String>,
        val entryValues: kotlin.collections.List<String>,
        override val restartRequired: Boolean = false,
        override val enabled: Boolean = true,
        override val onChange: (Preference, Set<String>) -> Boolean = { _, _ -> true },
        override val onComplete: (Set<String>) -> Unit = {},
    ) : PreferenceEntry<Set<String>>
}
