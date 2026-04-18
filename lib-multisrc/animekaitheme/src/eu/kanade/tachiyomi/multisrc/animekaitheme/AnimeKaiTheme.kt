package eu.kanade.tachiyomi.multisrc.animekaitheme

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiThemeFilters.addListQueryParameter
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiThemeFilters.addQueryParameterIfNotEmpty
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.IframeResponse
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoData
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

abstract class AnimeKaiTheme(
    override val lang: String,
    override val name: String,
    private val domainEntries: List<String>,
    private val hosterNames: List<String>,
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val domainValues = domainEntries.map { "https://$it" }

    private val defaultBaseUrl = "https://${domainEntries.first()}"

    protected val preferences by getPreferencesLazy { clearOldPrefs() }

    override var baseUrl: String by preferences.delegate(PREF_DOMAIN_KEY, defaultBaseUrl)

    // Open value so sub-extensions can overwrite as it sees fit
    protected open val rateLimit = 5

    protected var docHeaders by LazyMutable { headersBuilder().build() }

    override var client: OkHttpClient by LazyMutable {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = rateLimit, period = 1.seconds)
            .build()
    }
    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", docHeaders, cacheControl)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).mapNotNull {
            runCatching { popularAnimeFromElement(it) }.getOrNull()
        }
        val nextPage = popularAnimeNextPageSelector()?.let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage == true)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", docHeaders, cacheControl)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector()).mapNotNull {
            runCatching { latestUpdatesFromElement(it) }.getOrNull()
        }
        val nextPage = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage == true)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeKaiThemeFilters.getSearchParameters(filters)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browser")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())

            addListQueryParameter("type", params.types)
            addListQueryParameter("genre", params.genres)
            addListQueryParameter("status", params.statuses)
            addQueryParameterIfNotEmpty("sort", params.sort)
            addListQueryParameter("season", params.seasons)
            addListQueryParameter("year", params.years)
            addListQueryParameter("rating", params.ratings)
            addListQueryParameter("country", params.countries)
            addListQueryParameter("language", params.languages)
        }.build().toString()

        return GET(url, docHeaders, cacheControl)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).mapNotNull {
            runCatching { searchAnimeFromElement(it) }.getOrNull()
        }
        val nextPage = searchAnimeNextPageSelector()?.let { document.selectFirst(it) != null }
        return AnimesPage(animes, nextPage == true)
    }

    override fun getFilterList(): AnimeFilterList = AnimeKaiThemeFilters.FILTER_LIST

    // ============================== Related ==============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons div.season div.aitem div.inner").mapNotNull { season ->
            SAnime.create().apply {
                val url = season.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                thumbnail_url = season.selectFirst("img")?.attr("abs:src")
                title = season.select("div.detail span").text().ifBlank { return@mapNotNull null }
            }
        }

        val related = document.select(relatedAnimeListSelector()).mapNotNull {
            runCatching { relatedAnimeFromElement(it) }.getOrNull()
        }
        return seasons + related
    }

    // ============================ Shared Utilities =========================

    protected open fun parseStatus(statusString: String): Int = when (statusString) {
        "Completed", "Finished Airing" -> SAnime.COMPLETED
        "Releasing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    protected open fun getFancyScore(score: String?): String {
        if (score.isNullOrBlank()) return ""
        return try {
            val scoreBig = score.toBigDecimalOrNull() ?: return ""
            if (scoreBig.signum() <= 0) return ""

            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP)
                .toInt().coerceIn(0, 5)
            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("★".repeat(stars))
                append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: Exception) {
            ""
        }
    }

    private val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    protected open fun Element.getBackgroundImage(): String? {
        val style = attr("style")
        return coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
    }

    protected open fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false,
    ): String? {
        if (isList) {
            return select("div:containsOwn($tag) a").eachText().joinToString()
        }
        val value = selectFirst("div:containsOwn($tag)")
            ?.text()?.removePrefix(tag)?.trim()
        return if (full && value != null) "\n**$tag** $value" else value
    }

    protected open suspend fun decryptIframeData(encryptedText: String, headers: Headers): String {
        val postBody = buildJsonObject { put("text", encryptedText) }.toRequestBody()
        return client.newCall(POST("https://enc-dec.app/api/dec-kai", body = postBody, headers = headers))
            .awaitSuccess().parseAs<IframeResponse>().result.url
    }

    protected open fun getTypeSuffix(type: String): String = when (type) {
        "sub" -> "Hard Sub"
        "softsub" -> "Soft Sub"
        "dub" -> "Dub & S-Sub"
        else -> type
    }

    // ============================ Abstract Video ==========================

    protected abstract suspend fun extractVideo(server: VideoData): List<Video>

    // ============================== Video Sort ============================

    override fun List<Video>.sort(): List<Video> {
        val quality = prefQuality
        val server = prefServer
        val type = prefType
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(type, true) },
        )
    }

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    // ============================== Preferences ===========================

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, defaultBaseUrl)!!.removePrefix("https://")
        val hostToggle = getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!!

        val invalidDomain = domain !in domainEntries
        val invalidHosters = hostToggle.any { it !in hosterNames }

        if (invalidDomain || invalidHosters) {
            edit().also { editor ->
                if (invalidDomain) editor.putString(PREF_DOMAIN_KEY, defaultBaseUrl)
                if (invalidHosters) {
                    editor.putStringSet(PREF_HOSTER_KEY, hosterNames.toSet())
                    editor.putString(PREF_SERVER_KEY, hosterNames.first())
                }
            }.apply()
        }
        return this
    }

    protected var useEnglish by LazyMutable { getTitleLang == "English" }

    protected val getTitleLang by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)
    protected val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    protected val prefServer by preferences.delegate(PREF_SERVER_KEY, hosterNames.first())
    protected val prefType by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)
    protected val hostToggle: Set<String> by preferences.delegate(PREF_HOSTER_KEY, hosterNames.toSet())
    protected val typeToggle: Set<String> by preferences.delegate(PREF_TYPE_TOGGLE_KEY, DEFAULT_TYPES)
    protected val scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = domainEntries,
            entryValues = domainValues,
            default = defaultBaseUrl,
            summary = "%s",
        ) {
            baseUrl = it
            updateDomainConfig()
        }

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_LIST,
            entryValues = PREF_TITLE_LANG_LIST,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        ) {
            useEnglish = it == "English"
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.first(),
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_ENTRIES,
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            title = "Score display position",
            entries = PREF_SCORE_POSITION_ENTRIES,
            entryValues = PREF_SCORE_POSITION_VALUES,
            default = PREF_SCORE_POSITION_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.toSet(),
        )

        screen.addSetPreference(
            key = PREF_TYPE_TOGGLE_KEY,
            title = "Enable/Disable Types",
            summary = "Select which video types to show in the episode list.\nDisable the ones you don't want to speed up loading.",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_VALUES,
            default = DEFAULT_TYPES,
        )
    }

    protected open fun updateDomainConfig() {
        client = network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = rateLimit, period = 1.seconds)
            .build()
        docHeaders = headersBuilder().build()
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = listOf("Romaji", "English")

        private const val PREF_QUALITY_KEY = "preferred_quality"

        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES.first()

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES_ENTRIES = listOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
        private val TYPES_VALUES = listOf("sub", "softsub", "dub")
        private val DEFAULT_TYPES = TYPES_VALUES.toSet()

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "[Soft Sub]"

        private const val PREF_SCORE_POSITION_KEY = "score_position"

        const val SCORE_POS_TOP = "top"
        const val SCORE_POS_BOTTOM = "bottom"
        const val SCORE_POS_NONE = "none"

        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = listOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = listOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)
    }
}
