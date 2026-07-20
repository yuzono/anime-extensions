package keiyoushi.templating

/**
 * Common preference factory methods for frequently-used preference patterns.
 *
 * These factories create [PreferenceEntry] instances with sensible defaults
 * for the most common preference types found across extensions.
 */
object CommonPreferences {
    /**
     * Create a quality preference.
     *
     * @param key Preference key (default: "preferred_quality")
     * @param title Preference title
     * @param default Default quality value (default: "720")
     * @param entries Display entries (default: 1080p, 720p, 480p, 360p)
     * @param entryValues Entry values (default: 1080, 720, 480, 360)
     * @param onComplete Callback when preference changes
     */
    fun quality(
        key: String = "preferred_quality",
        title: String = "Preferred quality",
        default: String = "720",
        entries: List<String> = listOf("1080p", "720p", "480p", "360p"),
        entryValues: List<String> = listOf("1080", "720", "480", "360"),
        onComplete: (String) -> Unit = {},
    ): PreferenceEntry.List = PreferenceEntry.List(
        key = key,
        title = title,
        summary = "%s",
        default = default,
        entries = entries,
        entryValues = entryValues,
        onComplete = onComplete,
    )

    /**
     * Create a server preference.
     *
     * @param key Preference key (default: "preferred_server")
     * @param title Preference title
     * @param default Default server name
     * @param entries Available server names
     * @param onComplete Callback when preference changes
     */
    fun server(
        key: String = "preferred_server",
        title: String = "Preferred server",
        default: String,
        entries: List<String>,
        onComplete: (String) -> Unit = {},
    ): PreferenceEntry.List = PreferenceEntry.List(
        key = key,
        title = title,
        summary = "%s",
        default = default,
        entries = entries,
        entryValues = entries,
        onComplete = onComplete,
    )

    /**
     * Create a domain preference for mirror sites.
     *
     * @param key Preference key (default: "preferred_domain")
     * @param title Preference title
     * @param default Default domain
     * @param entries Display entries (e.g., "site1.com", "site2.com")
     * @param entryValues Entry values (e.g., "https://site1.com", "https://site2.com")
     * @param onComplete Callback when preference changes
     */
    fun domain(
        key: String = "preferred_domain",
        title: String = "Preferred domain (requires app restart)",
        default: String,
        entries: List<String>,
        entryValues: List<String>,
        onComplete: (String) -> Unit = {},
    ): PreferenceEntry.List = PreferenceEntry.List(
        key = key,
        title = title,
        summary = "%s",
        default = default,
        entries = entries,
        entryValues = entryValues,
        restartRequired = true,
        onComplete = onComplete,
    )

    /**
     * Create a hoster selection preference.
     *
     * @param key Preference key (default: "hoster_selection")
     * @param title Preference title
     * @param summary Preference summary
     * @param entries Available hoster names
     * @param default Default selected hosters (all enabled)
     * @param onComplete Callback when preference changes
     */
    fun hosterSelection(
        key: String = "hoster_selection",
        title: String = "Enable/Disable Hosts",
        summary: String = "Select which video hosts to show",
        entries: List<String>,
        default: Set<String> = entries.toSet(),
        onComplete: (Set<String>) -> Unit = {},
    ): PreferenceEntry.MultiSelect = PreferenceEntry.MultiSelect(
        key = key,
        title = title,
        summary = summary,
        default = default,
        entries = entries,
        entryValues = entries,
        onComplete = onComplete,
    )

    /**
     * Create a title language preference.
     *
     * @param key Preference key (default: "preferred_title_lang")
     * @param title Preference title
     * @param default Default language (default: "Romaji")
     * @param entries Available languages
     * @param onComplete Callback when preference changes
     */
    fun titleLanguage(
        key: String = "preferred_title_lang",
        title: String = "Preferred title language",
        default: String = "Romaji",
        entries: List<String> = listOf("Romaji", "English"),
        onComplete: (String) -> Unit = {},
    ): PreferenceEntry.List = PreferenceEntry.List(
        key = key,
        title = title,
        summary = "%s",
        default = default,
        entries = entries,
        entryValues = entries,
        onComplete = onComplete,
    )

    /**
     * Create a type toggle preference (Sub/Dub/Raw).
     *
     * @param key Preference key (default: "preferred_type")
     * @param title Preference title
     * @param default Default type (default: "Sub")
     * @param entries Available types
     * @param onComplete Callback when preference changes
     */
    fun typeToggle(
        key: String = "preferred_type",
        title: String = "Preferred Type",
        default: String = "Sub",
        entries: List<String> = listOf("Sub", "Dub", "Mixed", "Raw"),
        onComplete: (String) -> Unit = {},
    ): PreferenceEntry.List = PreferenceEntry.List(
        key = key,
        title = title,
        summary = "%s",
        default = default,
        entries = entries,
        entryValues = entries,
        onComplete = onComplete,
    )

    /**
     * Create a filler marking preference.
     *
     * @param key Preference key (default: "mark_fillers")
     * @param title Preference title
     * @param default Default value (default: true)
     * @param onComplete Callback when preference changes
     */
    fun markFillers(
        key: String = "mark_fillers",
        title: String = "Mark filler episodes",
        default: Boolean = true,
        onComplete: (Boolean) -> Unit = {},
    ): PreferenceEntry.Switch = PreferenceEntry.Switch(
        key = key,
        title = title,
        summary = "Mark filler episodes in the episode list",
        default = default,
        onComplete = onComplete,
    )
}
