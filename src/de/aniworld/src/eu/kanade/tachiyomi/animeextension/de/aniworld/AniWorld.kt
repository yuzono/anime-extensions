package eu.kanade.tachiyomi.animeextension.de.aniworld

import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.VidozaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AniWorld :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniWorld"

    override val baseUrl = "https://aniworld.to"

    override val lang = "de"

    override val id: Long = 8286900189409315836

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val json: Json by injectLazy()

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = "div.seriesListContainer div"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-animes")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a")!!
        return SAnime.create().apply {
            title = element.selectFirst("h3")!!.text()
            setUrlWithoutDomain(linkElement.attr("abs:href"))
            thumbnail_url = linkElement.selectFirst("img")?.attr("abs:data-src")
        }
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "div.seriesListContainer div"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neu")

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // ===== SEARCH =====
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = Headers.Builder()
            .add("Referer", "$baseUrl/search")
            .add("origin", baseUrl)
            .add("x-requested-with", "XMLHttpRequest")
            .build()
        val httpUrl = "$baseUrl/ajax/seriesSearch".toHttpUrl().newBuilder().apply {
            addQueryParameter("keyword", query)
        }.build()
        return GET(httpUrl, headers = headers)
    }
    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.bodyString()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results.mapNotNull {
            val obj = it.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val link = obj["link"]?.jsonPrimitive?.content ?: return@mapNotNull null

            SAnime.create().apply {
                title = name
                url = "/anime/stream/$link"
                thumbnail_url = obj["cover"]?.jsonPrimitive?.content?.replace("150x225", "220x330")
                    ?.let { cover -> UrlUtils.fixUrl(cover, baseUrl) }
                description = obj["description"]?.jsonPrimitive?.content
            }
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("div.series-title h1 span")!!.text()
        thumbnail_url = document.selectFirst("div.seriesCoverBox img")?.attr("abs:data-src")
        genre = document.select("div.genres ul li").joinToString { it.text() }
        description = document.selectFirst("p.seri_des")?.attr("data-full-description")
        document.selectFirst("div.cast li:contains(Produzent:) ul")?.let { producerList: Element ->
            author = producerList.select("li").joinToString { li -> li.text() }
        }
        status = SAnime.UNKNOWN
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val seasonsElements = document.select("#stream > ul:nth-child(1) > li > a")
        return seasonsElements.parallelCatchingFlatMapBlocking {
            parseEpisodesFromSeries(it)
        }.reversed()
    }

    private suspend fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).awaitSuccess().useAsJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.mapNotNull { runCatching { episodeFromElement(it) }.getOrNull() }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val num = element.attr("data-episode-season-id")
        element.selectFirst("td.seasonEpisodeTitle a")!!.let { seasonEpisode: Element ->
            val name = seasonEpisode.select("span").text()
            val url = seasonEpisode.attr("href")
            if (url.contains("/filme")) {
                episode.name = "Film $num : $name"
                num.toFloatOrNull()?.let { episode.episode_number = it }
                episode.url = url
            } else {
                val season = url
                    .substringAfter("staffel-").substringBefore("/episode")
                episode.name = "Staffel $season Folge $num : $name"
                element.select("td meta").attr("content").toFloatOrNull()?.let { episode.episode_number = it }
                episode.url = url
            }
        }
        return episode
    }

    // ===== VIDEO SOURCES =====
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidozaExtractor by lazy { VidozaExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val redirectlink = document.select("ul.row li")
        val allowedHosters = PREF_HOSTER_NAMES - excludedHosters
        return redirectlink.parallelCatchingFlatMapBlocking { elm ->
            val langkey = elm.attr("data-lang-key")
            val language = getLanguage(langkey)
            val redirectgs = elm.selectFirst("a.watchEpisode")!!.attr("abs:href")
            val hoster = elm.select("a h4").text()
            val matchedHoster = allowedHosters
                .firstOrNull { hoster.contains(it, true) }
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val url = getRedirectedUrl(redirectgs)
            when (matchedHoster) {
                NAME_VOE -> voeExtractor.videosFromUrl(url, "($language) ")
                NAME_DOOD -> doodExtractor.videoFromUrl(url, "($language)", false)?.let(::listOf)
                NAME_STAPE -> streamTapeExtractor.videoFromUrl(url, "($language) $NAME_STAPE")?.let(::listOf)
                NAME_VIZ -> vidozaExtractor.videoFromUrl(url, "($language) $NAME_VIZ")?.let(::listOf)
                NAME_FILEMOON -> filemoonExtractor.videosFromUrl(url, "($language) $NAME_FILEMOON ", headers)
                NAME_VIDMOLY -> vidmolyExtractor.videosFromUrl(url, "($language)")
                else -> null
            } ?: emptyList()
        }
    }

    private suspend fun getRedirectedUrl(url: String) = client.newCall(GET(url)).awaitSuccess().use { it.request.url.toString() }

    private fun getLanguage(langKey: String) = LANGS.keys.firstOrNull { langKey.contains(it) }?.let { LANGS[it] } ?: "?"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort() = sortedWith(
        compareByDescending<Video> { it.quality.contains(preferredHoster, true) }
            .thenByDescending { it.quality.contains(preferredLang, true) },
    )

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ===== PREFERENCES ======
    private val preferredLang by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)
    private val preferredHosterPref by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)
    private val preferredHoster
        get() = preferredHosterPref.takeIf { it in PREF_HOSTER_NAMES } ?: PREF_HOSTER_DEFAULT
    private val excludedHosters by preferences.delegate(PREF_EXCLUDED_HOSTERS_KEY, emptySet<String>())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = "Bevorzugte Sprache",
            entries = PREF_LANGS,
            entryValues = PREF_LANGS,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Bevorzugter Hoster",
            entries = PREF_HOSTER_NAMES,
            entryValues = PREF_HOSTER_NAMES,
            default = PREF_HOSTER_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_EXCLUDED_HOSTERS_KEY,
            title = "Ausgeschlossene Hoster",
            entries = PREF_HOSTER_NAMES,
            entryValues = PREF_HOSTER_NAMES,
            default = emptySet(),
            summary = "Wählen Sie die Hoster aus, die Sie nicht wünschen.",
        )
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_EXCLUDED_HOSTERS_KEY = "excluded_hosters"
        private const val NAME_DOOD = "Doodstream"
        private const val NAME_STAPE = "Streamtape"
        private const val NAME_VOE = "VOE"
        private const val NAME_VIZ = "Vidoza"
        private const val NAME_FILEMOON = "Filemoon"
        private const val NAME_VIDMOLY = "Vidmoly"

        private val PREF_HOSTER_NAMES = listOf(NAME_VOE, NAME_DOOD, NAME_STAPE, NAME_VIZ, NAME_FILEMOON, NAME_VIDMOLY)
        private val PREF_HOSTER_DEFAULT = PREF_HOSTER_NAMES.first()

        private const val PREF_LANG_KEY = "preferred_lang"
        private val LANGS = mapOf(
            "3" to "Deutscher Sub",
            "1" to "Deutscher Dub",
            "2" to "Englischer Sub",
        )
        private val PREF_LANGS = LANGS.values.toList()
        private val PREF_LANG_DEFAULT = PREF_LANGS.first()
    }
}
