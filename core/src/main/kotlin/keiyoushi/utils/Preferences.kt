package keiyoushi.utils

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

/**
 * Lazily returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { }
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
@Suppress("NOTHING_TO_INLINE")
inline fun getPreferences(sourceId: Long): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences("source_$sourceId", 0x0000)

// From https://al-e-shevelev.medium.com/mutable-lazy-in-kotlin-14233bed116d
class LazyMutable<T>(
    val initializer: () -> T,
) : ReadWriteProperty<Any?, T> {
    private object UninitializedValue

    @Volatile private var propValue: Any? = UninitializedValue

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val localValue = propValue

        if (localValue != UninitializedValue) {
            return localValue as T
        }

        return synchronized(this) {
            val localValue2 = propValue

            if (localValue2 != UninitializedValue) {
                localValue2 as T
            } else {
                val initializedValue = initializer()
                propValue = initializedValue
                initializedValue
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            propValue = value
        }
    }
}

/**
 * Delegate to write and read from preferences.
 *
 * @param preferences Shared preferences
 * @param key Key for preference
 * @param default Default value for preference, can be `null` for `String?` or `Set<String>?`.
 * Should be explicitly casted such as `null as String?`.
 */
class PreferenceDelegate<T>(
    val preferences: SharedPreferences,
    val key: String,
    val default: T,
) : ReadWriteProperty<Any?, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        /*
         * The synchronized(this) is not necessary here and may actually cause performance issues.
         * The getValue method only performs read operations on SharedPreferences, which are already thread-safe in Android.
         */
        return /* KMK --> synchronized(this) { KMK <-- */ try {
            when (default) {
                is String -> preferences.getString(key, default) as T
                is Int -> preferences.getInt(key, default) as T
                is Long -> preferences.getLong(key, default) as T
                is Float -> preferences.getFloat(key, default) as T
                is Boolean -> preferences.getBoolean(key, default) as T
                is Set<*> -> preferences.getStringSet(key, default as Set<String>) as T
                null -> preferences.all[key] as T
                else -> throw IllegalArgumentException("Unsupported type: ${default.javaClass}")
            }
        } catch (_: ClassCastException) { default }
    }

    @Suppress("UNCHECKED_CAST")
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            val editor = preferences.edit()
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                else -> throw IllegalArgumentException("Unsupported type: ${value.javaClass}")
            }
            editor.apply()
        }
    }
}

/**
 * Create [Preference] delegate.
 *
 * **WARNING**: Do not use this on a lib-multisrc module, as it will be initialized before the source is created,
 * which will cause the preferences to be created with the wrong source id.
 *
 * @param key Key for preference
 * @param default Default value for preference, can be `null` for `String?` or `Set<String>?`.
 * Should be explicitly casted such as `null as String?`.
 */
fun <T> SharedPreferences.delegate(key: String, default: T) =
    PreferenceDelegate(this, key, default)

private const val RESTART_MESSAGE = "Restart the app to apply the new setting."

/**
 * Get an [EditTextPreference] preference
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param getSummary Lambda to get summary based on text value
 * @param dialogMessage Preference dialog message
 * @param inputType Keyboard input type
 * @param validate Validate preference value before applying
 * @param validationMessage Validation message if text isn't valid, based on text value
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.getEditTextPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
): EditTextPreference {
    return EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.setDefaultValue(default)
        this.dialogTitle = title
        this.dialogMessage = dialogMessage
        this.setEnabled(enabled)

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }

            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(editable: Editable?) {
                            requireNotNull(editable)

                            val text = editable.toString()
                            val isValid = text.isBlank() || validate(text)

                            editText.error = if (!isValid) validationMessage?.invoke(text) else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)
                                ?.isEnabled = editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { pref, newValue ->
            val value = newValue as String
            val isValid = onChange(pref, value)
            if (isValid) {
                if (restartRequired) {
                    Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
                }

                this.summary = getSummary(value)
                onComplete(value)
            }
            isValid
        }
    }
}

/**
 * Add an [EditTextPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param getSummary Lambda to get summary based on text value
 * @param dialogMessage Preference dialog message
 * @param inputType Keyboard input type
 * @param validate Validate preference value before applying
 * @param validationMessage Validation message if text isn't valid, based on text value
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.addEditTextPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
) {
    getEditTextPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        getSummary = getSummary,
        dialogMessage = dialogMessage,
        inputType = inputType,
        validate = validate,
        validationMessage = validationMessage,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [ListPreference] preference
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.getListPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
): ListPreference {
    return ListPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.entries = entries.toTypedArray()
        this.entryValues = entryValues.toTypedArray()

        setDefaultValue(default)
        setEnabled(enabled)
        setOnPreferenceChangeListener { pref, newValue ->
            val value = newValue as String
            val isValid = onChange(pref, value)
            if (isValid) {
                if (restartRequired) {
                    Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
                }
                onComplete(value)
            }
            isValid
        }
    }
}

/**
 * Add a [ListPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.addListPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
) {
    getListPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        entries = entries,
        entryValues = entryValues,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [MultiSelectListPreference] preference
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.getSetPreference(
    key: String,
    default: Set<String>,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Set<String>) -> Boolean = { _, _ -> true },
    onComplete: (Set<String>) -> Unit = {},
): MultiSelectListPreference {
    return MultiSelectListPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.entries = entries.toTypedArray()
        this.entryValues = entryValues.toTypedArray()
        setDefaultValue(default)
        setEnabled(enabled)

        setOnPreferenceChangeListener { pref, newValues ->
            @Suppress("UNCHECKED_CAST")
            val values = newValues as Set<String>
            val isValid = onChange(pref, values)
            if (isValid) {
                if (restartRequired) {
                    Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
                }
                onComplete(values)
            }
            isValid
        }
    }
}

/**
 * Add a [MultiSelectListPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.addSetPreference(
    key: String,
    default: Set<String>,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Set<String>) -> Boolean = { _, _ -> true },
    onComplete: (Set<String>) -> Unit = {},
) {
    getSetPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        entries = entries,
        entryValues = entryValues,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [SwitchPreferenceCompat] preference
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.getSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Boolean) -> Boolean = { _, _ -> true },
    onComplete: (Boolean) -> Unit = {},
): SwitchPreferenceCompat {
    return SwitchPreferenceCompat(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        setDefaultValue(default)
        setEnabled(enabled)

        setOnPreferenceChangeListener { pref, newValue ->
            val value = newValue as Boolean
            val isValid = onChange(pref, value)
            if (isValid) {
                if (restartRequired) {
                    Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
                }
                onComplete(value)
            }
            isValid
        }
    }
}

/**
 * Add a [SwitchPreferenceCompat] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.addSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Boolean) -> Boolean = { _, _ -> true },
    onComplete: (Boolean) -> Unit = {},
) {
    getSwitchPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}
