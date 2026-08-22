package keiyoushi.templating

import android.content.SharedPreferences

/**
 * Mixin for extensions that support bilingual title display (e.g., Romaji/English).
 *
 * This provides a standard pattern for:
 * - Title language preference
 * - Title selection based on preference
 *
 * Usage in extensions:
 * ```kotlin
 * class MyExtension : AnimeExtension(), BilingualTitle {
 *     override val bilingualTitlePrefKey = "preferred_title_lang"
 *     override val bilingualTitleEntries = listOf("Romaji", "English")
 *     override val bilingualTitleDefault = "Romaji"
 * }
 * ```
 */
interface BilingualTitle {
    /** Preference key for title language. */
    val bilingualTitlePrefKey: String get() = "preferred_title_lang"

    /** Available title language options. */
    val bilingualTitleEntries: List<String> get() = listOf("Romaji", "English")

    /** Default title language. */
    val bilingualTitleDefault: String get() = "Romaji"

    /** Preference title shown in settings UI. */
    val bilingualTitlePrefTitle: String get() = "Preferred title language"

    /**
     * Get the preferred title language.
     *
     * @param preferences The SharedPreferences instance
     * @return The preferred language ("Romaji" or "English")
     */
    fun getPreferredTitleLang(preferences: SharedPreferences): String = preferences.getString(bilingualTitlePrefKey, bilingualTitleDefault)
        ?: bilingualTitleDefault

    /**
     * Select the appropriate title based on preference.
     *
     * @param enTitle English title (nullable)
     * @param jpTitle Japanese/Romaji title (nullable)
     * @param preferences The SharedPreferences instance
     * @return The selected title, or first non-null title
     */
    fun selectTitle(
        enTitle: String?,
        jpTitle: String?,
        preferences: SharedPreferences,
    ): String? {
        val lang = getPreferredTitleLang(preferences)
        return if (lang == "English") {
            enTitle ?: jpTitle
        } else {
            jpTitle ?: enTitle
        }
    }

    /**
     * Check if English titles are preferred.
     */
    fun isEnglishPreferred(preferences: SharedPreferences): Boolean = getPreferredTitleLang(preferences) == "English"
}

/**
 * Abstract base class for extensions with bilingual title support.
 */
abstract class BilingualTitleExtension :
    AnimeExtension(),
    BilingualTitle {

    /**
     * Select the appropriate title based on preference.
     */
    fun selectTitle(enTitle: String?, jpTitle: String?): String? = selectTitle(enTitle, jpTitle, preferences)

    /**
     * Check if English titles are preferred.
     */
    fun isEnglishPreferred(): Boolean = isEnglishPreferred(preferences)
}
