package keiyoushi.templating

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference

/**
 * Auto-managed preference registry constructed from a declarative
 * [PreferenceEntry] schema.
 *
 * Responsibilities:
 * 1. **Auto-load** — each entry reads via [SharedPreferences] on demand.
 * 2. **Auto-persist** — writes go through [SharedPreferences.Editor].
 * 3. **Auto-register UI** — [renderTo] iterates the schema and calls the
 *    existing `add*Preference` helpers from `keiyoushi.utils`,
 *    producing the same UI as a manual `setupPreferenceScreen`.
 */
class PreferenceRegistry private constructor(
    private val preferences: SharedPreferences,
    private val schema: List<PreferenceEntry<*>>,
) {
    private val entriesByKey: Map<String, PreferenceEntry<*>> = schema.associateBy { it.key }

    /**
     * Typed read accessor.
     *
     * ```
     * val quality = registry["preferred_quality"] as String
     * val enabled = registry["mark_fillers"] as Boolean
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: String): T {
        val entry = entriesByKey[key]
            ?: throw IllegalArgumentException("No preference registered with key '$key'")
        return readValue(entry) as T
    }

    /**
     * Renders the full schema to the given [PreferenceScreen] using the
     * existing `keiyoushi.utils` preference helpers.
     */
    fun renderTo(screen: PreferenceScreen) {
        for (entry in schema) {
            when (entry) {
                is PreferenceEntry.Text -> screen.addEditTextPreference(
                    key = entry.key,
                    default = entry.default,
                    title = entry.title,
                    summary = entry.summary,
                    dialogMessage = entry.dialogMessage,
                    inputType = entry.inputType,
                    validate = entry.validate,
                    validationMessage = entry.validationMessage,
                    restartRequired = entry.restartRequired,
                    enabled = entry.enabled,
                    onChange = entry.onChange,
                    onComplete = entry.onComplete,
                )

                is PreferenceEntry.List -> screen.addListPreference(
                    key = entry.key,
                    default = entry.default,
                    title = entry.title,
                    summary = entry.summary,
                    entries = entry.entries,
                    entryValues = entry.entryValues,
                    restartRequired = entry.restartRequired,
                    enabled = entry.enabled,
                    onChange = entry.onChange,
                    onComplete = entry.onComplete,
                )

                is PreferenceEntry.Switch -> screen.addSwitchPreference(
                    key = entry.key,
                    default = entry.default,
                    title = entry.title,
                    summary = entry.summary,
                    restartRequired = entry.restartRequired,
                    enabled = entry.enabled,
                    onChange = entry.onChange,
                    onComplete = entry.onComplete,
                )

                is PreferenceEntry.MultiSelect -> screen.addSetPreference(
                    key = entry.key,
                    default = entry.default,
                    title = entry.title,
                    summary = entry.summary,
                    entries = entry.entries,
                    entryValues = entry.entryValues,
                    restartRequired = entry.restartRequired,
                    enabled = entry.enabled,
                    onChange = entry.onChange,
                    onComplete = entry.onComplete,
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readValue(entry: PreferenceEntry<*>): Any? {
        val key = entry.key
        val default = entry.default
        return try {
            when (default) {
                is String -> preferences.getString(key, default)
                is Int -> preferences.getInt(key, default)
                is Long -> preferences.getLong(key, default)
                is Float -> preferences.getFloat(key, default)
                is Boolean -> preferences.getBoolean(key, default)
                is Set<*> -> preferences.getStringSet(key, default as Set<String>)
                null -> preferences.all[key]
                else -> throw IllegalArgumentException("Unsupported type: ${default.javaClass}")
            }
        } catch (_: ClassCastException) {
            default
        }
    }

    companion object {
        fun fromSchema(
            schema: List<PreferenceEntry<*>>,
            preferences: SharedPreferences,
        ): PreferenceRegistry = PreferenceRegistry(preferences, schema)
    }
}
