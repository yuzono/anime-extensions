package eu.kanade.tachiyomi.animeextension.ar.witanime

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SoraPlayExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WitAnime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "WIT ANIME"

    override val baseUrl get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.anime-card"

    override fun popularAnimeNextPageSelector() = "ul.pagination li:last-child a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/page/$page/")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.image") ?: element.selectFirst("a")

        link?.attr("href")?.also { setUrlWithoutDomain(it) }

        title = element.selectFirst("div.info h3")?.text()
            ?: link?.attr("title").orEmpty()

        thumbnail_url = link?.bgImageUrl()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episode/page/$page/")

    override fun latestUpdatesSelector() = "div.episode-card"
    override fun latestUpdatesNextPageSelector() = "ul.pagination li:last-child a"
    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        // Episode cards link both the episode and its anime; the entry must
        // point at the anime page so details parse correctly.
        val animeLink = element.select("div.info a").firstOrNull { it.attr("href").contains("/anime/") }

        animeLink?.attr("href")?.also { setUrlWithoutDomain(it) }

        title = animeLink?.selectFirst("h4")?.text()
            ?: animeLink?.text()
            ?: element.selectFirst("div.info h3")?.text().orEmpty()

        thumbnail_url = element.selectFirst("a.image")?.bgImageUrl()
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/?s=$query")

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()
    override fun searchAnimeSelector() = latestUpdatesSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.media-title h1")?.text().orEmpty()

        thumbnail_url = document.selectFirst("div.anime-card div.image")?.bgImageUrl()

        // Genres + useful info
        genre = document.select("ul.media-info li").eachText().joinToString()

        description = buildString {
            // Additional info
            document.select("ul.media-info li").eachText().forEach {
                append("$it\n")
            }
            // Story
            document.selectFirst("div.media-story div.content")?.text()?.also {
                append("\n$it")
            }
        }

        document.selectFirst("a[href*=\"/anime-status/\"]")?.text()?.also {
            status = when {
                it.contains("يعرض") -> SAnime.ONGOING
                it.contains("مكتمل") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = response.asJsoup()
        .select(episodeListSelector())
        .map(::episodeFromElement)
        .reversed()

    override fun episodeListSelector() = "ul.episodes-lists a.title"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        element.attr("href").also { setUrlWithoutDomain(it) }
        name = element.text()
        episode_number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("ul.server-list a.episode-server")
            .filter { it.attr("data-url").isNotBlank() }
            .distinctBy { it.text().trim() } // remove duplicates by server name
            .parallelCatchingFlatMapBlocking {
                extractVideos(it.attr("data-url"))
            }
    }

    private val soraPlayExtractor by lazy { SoraPlayExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private suspend fun extractVideos(url: String): List<Video> = when {
        url.contains("yonaplay") -> extractFromMulti(url)

        url.contains("soraplay") -> {
            when {
                url.contains("/mirror") -> extractFromMulti(url)
                else -> soraPlayExtractor.videosFromUrl(url, headers)
            }
        }

        url.contains("dood") -> {
            doodExtractor.videoFromUrl(url, "Dood mirror")
                ?.let(::listOf)
        }

        url.contains("4shared") -> {
            sharedExtractor.videosFromUrl(url)
                ?.let(::listOf)
        }

        url.contains("dropbox") -> {
            listOf(Video(url, "Dropbox mirror", url))
        }

        url.contains("dailymotion") -> {
            dailymotionExtractor.videosFromUrl(url)
        }

        url.contains("ok.ru") -> {
            okruExtractor.videosFromUrl(url)
        }

        url.contains("mp4upload.com") -> {
            mp4uploadExtractor.videosFromUrl(url, headers)
        }

        VIDBOM_REGEX.containsMatchIn(url) -> {
            vidBomExtractor.videosFromUrl(url)
        }

        else -> null
    } ?: emptyList()

    private fun extractFromMulti(url: String): List<Video> {
        val newHeaders = when {
            url.contains("soraplay") ->
                super.headersBuilder().set("referer", "https://yonaplay.org").build()

            else -> headers
        }
        val doc = client.newCall(GET(url, newHeaders)).execute()
            .useAsJsoup()
        return doc.select(".OD li").parallelCatchingFlatMapBlocking { element ->
            val videoUrl = element.attr("onclick").substringAfter("go_to_player('")
                .substringBefore("')")
                .let {
                    when {
                        it.startsWith("https:") -> it
                        else -> "https:$it"
                    }
                }

            extractVideos(videoUrl)
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

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
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
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
    private fun Element.bgImageUrl(): String? = BG_IMAGE_REGEX
        .find(attr("style"))
        ?.groupValues
        ?.getOrNull(1)
        ?.takeUnless(String::isBlank)

    companion object {
        private const val DEFAULT_BASE_URL = "https://witanime.onl"
        private const val PREF_BASE_URL_KEY = "override_base_url"
        private val BG_IMAGE_REGEX by lazy { Regex("""url\(['"]?(.*?)['"]?\)""") }
        private val EPISODE_NUMBER_REGEX by lazy { Regex("""الحلقة\s+(\d+)""") }
        // From TukTukCinema(AR)
        private val VIDBOM_REGEX by lazy { Regex("//v[aie]d[bp][aoe]?m") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "380p", "360p", "240p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
