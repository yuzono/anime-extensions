package keiyoushi.source

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CustomUrlPreferences(
    private val preferences: SharedPreferences,
    private val defaultUrl: String,
    private val title: String,
    private val dialogMessage: String,
) {
    private val prefBaseKey = PREF_BASE_URL_KEY
    private val prefDefaultKey = "defaultBaseUrl"

    init {
        val storedDefault = preferences.getString(prefDefaultKey, null)
        if (storedDefault != defaultUrl) {
            preferences.edit()
                .putString(prefBaseKey, defaultUrl)
                .putString(prefDefaultKey, defaultUrl)
                .apply()
        }
    }

    val baseUrl: String
        get() = preferences.getString(prefBaseKey, defaultUrl) ?: defaultUrl

    fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = prefBaseKey
            title = this@CustomUrlPreferences.title
            dialogTitle = this@CustomUrlPreferences.title
            dialogMessage = this@CustomUrlPreferences.dialogMessage

            val currentValue = preferences.getString(prefBaseKey, null)
            summary = currentValue?.takeIf { it.isNotBlank() } ?: defaultUrl
            setDefaultValue(defaultUrl)

            setOnBindEditTextListener { editText ->
                editText.hint = defaultUrl
                editText.setHorizontallyScrolling(true)
                editText.post { editText.selectAll() }
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun afterTextChanged(editable: Editable?) {
                            val text = editable?.toString() ?: ""
                            val isValid = text.isBlank() || text.toHttpUrlOrNull() != null
                            editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled = isValid
                        }

                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    },
                )
            }

            setOnPreferenceChangeListener { preference, newValue ->
                val text = newValue as String
                val httpUrl = text.toHttpUrlOrNull()
                val isValid = text.isBlank() || httpUrl != null
                if (isValid) {
                    val sanitizedValue = if (text.isBlank()) {
                        defaultUrl
                    } else {
                        // Keep only the origin, so a page URL pasted from the address bar still gives the site's base URL.
                        // HttpUrl.Builder puts back the brackets of an IPv6 host, which HttpUrl.host drops.
                        HttpUrl.Builder()
                            .scheme(httpUrl!!.scheme)
                            .host(httpUrl.host)
                            .port(httpUrl.port)
                            .build()
                            .toString()
                            .trimEnd('/')
                    }

                    (preference as EditTextPreference).text = sanitizedValue
                    summary = sanitizedValue

                    preferences.edit()
                        .putString(prefBaseKey, sanitizedValue)
                        .apply()
                }
                false
            }
        }.also(screen::addPreference)
    }

    companion object {
        /** Key of the custom base URL, for a source that stores a new domain announced by the site. */
        const val PREF_BASE_URL_KEY = "overrideBaseUrl"
    }
}
