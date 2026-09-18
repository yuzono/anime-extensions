package eu.kanade.tachiyomi.animeextension.ar.okanime

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Okanime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Okanime"

    override val baseUrl get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.container > div.section:last-child div.anime-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.anime-title > h4 > a")?.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recently-uploaded-episodes?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "ul.pagination > li:last-child:not(.disabled)"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host && url.host != "www.okanime.xyz") {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = "$baseUrl/search/?s=$query"
        .let { if (page > 1) "$it&page=$page" else it }
        .let(::GET)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1.animepage-h1, div.author-info-title > h1")?.text()
            ?: document.title().substringBefore(" مترجم").substringBefore(" |")
        genre = document.select("div.animepage-genres a, div.review-author-info a").eachText().joinToString()

        thumbnail_url = document.selectFirst("img.animepage-poster, div.text-right img")?.attr("src")
        status = document.select("dl.animepage-meta div.animepage-meta-row, div.text-right div.full-list-info").firstOrNull { row ->
            row.selectFirst("dt")?.text()?.contains("الحالة") == true ||
                row.text().contains("حالة الأنمي")
        }?.selectFirst("dd, a")?.text().let {
            when {
                it?.contains("يعرض") == true -> SAnime.ONGOING
                it?.contains("مكتمل") == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
        description = buildString {
            document.selectFirst("div.animepage-synopsis div.synopsis-text, div.review-content")
                ?.text()
                ?.takeIf { it.isNotBlank() }
                ?.let { append("$it\n") }

            document.select("dl.animepage-meta div.animepage-meta-row, div.text-right div.full-list-info").forEach { info ->
                info.selectFirst("dt")?.text()?.let { label ->
                    val value = info.selectFirst("dd")?.text().orEmpty()
                    if (value.isNotBlank()) append("\n$label: $value")
                } ?: info.select("small")
                    .eachText()
                    .joinToString(": ")
                    .takeIf { it.isNotBlank() }
                    ?.let { append("\n$it") }
            }
        }.trim().takeIf { it.isNotBlank() }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "a.ep-compact-btn, div.row div.episode-card div.anime-title a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val label = element.attr("title").ifBlank { element.text() }.trim()
        episode_number = label.substringAfterLast(" ").trim().toFloatOrNull()
            ?: element.text().trim().toFloatOrNull()
            ?: element.attr("href").substringAfterLast("-").substringBefore("/").toFloatOrNull()
            ?: 1F
        name = label.ifBlank { "الحلقة $episode_number" }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return response.useAsJsoup()
            .select("a.ep-link")
            .parallelCatchingFlatMapBlocking { element ->
                val quality = element.selectFirst("span")?.text().orEmpty().let {
                    when (it) {
                        "HD" -> "720p"
                        "FHD" -> "1080p"
                        "SD" -> "480p"
                        else -> "240p"
                    }
                }
                val url = serverUrlFromElement(element)
                if (url.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()
                extractVideosFromUrl(url, quality, hosterSelection)
            }
    }

    // Site renders server urls in Alpine.js attrs (@click="setServer('...')")
    // instead of data-src; legacy data-src kept as fallback.
    private fun serverUrlFromElement(element: Element): String {
        element.attr("data-src").takeIf { it.isNotBlank() }?.let { return it }
        val attrs = element.attributes().asList().map { it.value }
        attrs.firstOrNull { "setServer('" in it }
            ?.substringAfter("setServer('").substringBefore("'")
            .takeIf { it.isNotBlank() }?.let { return it }
        attrs.firstOrNull { "activeUrl === '" in it }
            ?.substringAfter("activeUrl === '").substringBefore("'")
            .takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    // Inspirated by JavGuru(all)
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private suspend fun extractVideosFromUrl(url: String, quality: String, selection: Set<String>): List<Video> = when {
        "https://doo" in url && "/e/" in url && selection.contains("Dood") -> {
            doodExtractor.videoFromUrl(url, "DoodStream - $quality")
                ?.let(::listOf)
        }

        "mp4upload" in url && selection.contains("Mp4upload") -> {
            mp4uploadExtractor.videosFromUrl(url, headers)
        }

        "ok.ru" in url && selection.contains("Okru") -> {
            okruExtractor.videosFromUrl(url)
        }

        "voe.sx" in url && selection.contains("Voe") -> {
            voeExtractor.videosFromUrl(url)
        }

        VID_BOM_DOMAINS.any(url::contains) && selection.contains("VidBom") -> {
            vidBomExtractor.videosFromUrl(url)
        }

        else -> null
    }.orEmpty()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Server URL"
            summary = "Custom server URL (requires app restart). Current: ${preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)}"
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "Server URL"
            setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = "Custom server URL (requires app restart). Current: $newValue"
                true
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val DEFAULT_BASE_URL = "https://ww3.okanime.xyz"
        private const val PREF_BASE_URL_KEY = "override_base_url"

        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_HOSTER_SELECTION_KEY = "pref_hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Enable/Disable hosts"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Dood", "Voe", "Mp4upload", "VidBom", "Okru")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_ENTRIES.toSet() }
    }
}
