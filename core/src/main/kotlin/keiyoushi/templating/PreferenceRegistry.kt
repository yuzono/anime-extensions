package keiyoushi.templating

import android.content.SharedPreferences
import android.util.Log
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
     * val quality = registry.getString("preferred_quality")
     * val enabled = registry.getBoolean("mark_fillers")
     * ```
     */
    fun getString(key: String, default: String = ""): String = getValue<String>(key) ?: default

    fun getStringOrNull(key: String): String? = getValue<String>(key)

    fun getInt(key: String, default: Int = 0): Int = getValue<Int>(key) ?: default

    fun getLong(key: String, default: Long = 0L): Long = getValue<Long>(key) ?: default

    fun getFloat(key: String, default: Float = 0f): Float = getValue<Float>(key) ?: default

    fun getBoolean(key: String, default: Boolean = false): Boolean = getValue<Boolean>(key) ?: default

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> = getValue<Set<String>>(key) ?: default

    @Suppress("UNCHECKED_CAST")
    private fun <T> getValue(key: String): T? {
        val entry = entriesByKey[key]
            ?: throw IllegalArgumentException("No preference registered with key '$key'")
        return readValue(entry) as? T
    }

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
        } catch (e: ClassCastException) {
            Log.w(TAG, "Type mismatch for key '$key', returning default", e)
            default
        }
    }

    fun contains(key: String): Boolean = entriesByKey.containsKey(key)

    fun keys(): Set<String> = entriesByKey.keys

    fun size(): Int = entriesByKey.size

    companion object {
        private const val TAG = "PreferenceRegistry"

        fun fromSchema(
            schema: List<PreferenceEntry<*>>,
            preferences: SharedPreferences,
        ): PreferenceRegistry = PreferenceRegistry(preferences, schema)
    }
}
