package eu.kanade.tachiyomi.animeextension.de.aniworld

import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
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
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")!!
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img")!!.attr("data-src")
        anime.title = element.selectFirst("h3")!!.text()
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "div.seriesListContainer div"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neu")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")!!
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img")!!.attr("data-src")
        anime.title = element.selectFirst("h3")!!.text()
        return anime
    }

    // ===== SEARCH =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = Headers.Builder()
            .add("Referer", "$baseUrl/search")
            .add("origin", baseUrl)
            .add("connection", "keep-alive")
            .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
            .add("Upgrade-Insecure-Requests", "1")
            .add("cache-control", "")
            .add("accept", "*/*")
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
        val body = response.body.string()
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
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.series-title h1 span")!!.text()
        anime.thumbnail_url = baseUrl +
            document.selectFirst("div.seriesCoverBox img")!!.attr("data-src")
        anime.genre = document.select("div.genres ul li").joinToString { it.text() }
        anime.description = document.selectFirst("p.seri_des")!!.attr("data-full-description")
        document.selectFirst("div.cast li:contains(Produzent:) ul")?.let {
            val author = it.select("li").joinToString { li -> li.text() }
            anime.author = author
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seasonsElements = document.select("#stream > ul:nth-child(1) > li > a")
        if (seasonsElements.attr("href").contains("/filme")) {
            seasonsElements.forEach {
                val seasonEpList = parseMoviesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().useAsJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    private fun parseMoviesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().useAsJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        if (element.select("td.seasonEpisodeTitle a").attr("href").contains("/film")) {
            val num = element.attr("data-episode-season-id")
            episode.name = "Film $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.attr("data-episode-season-id").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
        } else {
            val season = element.select("td.seasonEpisodeTitle a").attr("href")
                .substringAfter("staffel-").substringBefore("/episode")
            val num = element.attr("data-episode-season-id")
            episode.name = "Staffel $season Folge $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.select("td meta").attr("content").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
        }
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val redirectlink = document.select("ul.row li")
        return redirectlink.parallelCatchingFlatMapBlocking {
            val langkey = it.attr("data-lang-key")
            val language = getLanguage(langkey)
            val redirectgs = baseUrl + it.selectFirst("a.watchEpisode")!!.attr("href")
            val hoster = it.select("a h4").text()
            when {
                hoster.contains(NAME_VOE, true) && enabledHosters.contains(NAME_VOE) -> {
                    val url = getRedirectedUrl(redirectgs)
                    VoeExtractor(client, headers).videosFromUrl(url, "($language) ")
                }

                hoster.contains(NAME_DOOD, true) && enabledHosters.contains(NAME_DOOD) -> {
                    val quality = "Doodstream $language"
                    val url = getRedirectedUrl(redirectgs)
                    DoodExtractor(client).videoFromUrl(url, quality)?.let(::listOf)
                }

                hoster.contains(NAME_STAPE, true) && enabledHosters.contains(NAME_STAPE) -> {
                    val quality = "Streamtape $language"
                    val url = getRedirectedUrl(redirectgs)
                    StreamTapeExtractor(client).videoFromUrl(url, quality)?.let(::listOf)
                }

                hoster.contains(NAME_VIZ, true) && enabledHosters.contains(NAME_VIZ) -> {
                    val quality = "Vidoza $language"
                    val url = getRedirectedUrl(redirectgs)
                    VidozaExtractor(client).videoFromUrl(url, quality)?.let(::listOf)
                }

                else -> null
            } ?: emptyList()
        }
    }

    private suspend fun getRedirectedUrl(url: String) = client.newCall(GET(url)).awaitSuccess().use { it.request.url.toString() }

    private fun getLanguage(langKey: String) = LANGS.toList().firstOrNull { langKey.contains(it.first) }?.second

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort() = sortedWith(
        compareByDescending<Video> { it.quality.contains(preferredHoster, true) }
            .thenByDescending { it.quality.contains(preferredLang, true) },
    )

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ===== PREFERENCES ======
    private val preferredLang by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)
    private val preferredHosterPref by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)
    private val preferredHoster = preferredHosterPref
        .takeIf { it in PREF_HOSTER_NAMES }
        ?: PREF_HOSTER_DEFAULT
    private val enabledHosters by preferences.delegate(PREF_HOSTERS_SELECTION_KEY, PREF_HOSTER_NAMES.toSet())

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
            title = "Bevorzugter hoster",
            entries = PREF_HOSTER_NAMES,
            entryValues = PREF_HOSTER_NAMES,
            default = PREF_HOSTER_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_HOSTERS_SELECTION_KEY,
            title = "Hoster auswählen",
            entries = PREF_HOSTER_NAMES,
            entryValues = PREF_HOSTER_NAMES,
            default = PREF_HOSTER_NAMES.toSet(),
            summary = "Wählen Sie die Hoster aus, die aktiviert werden sollen.",
        )
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTERS_SELECTION_KEY = "hoster_selection"
        private const val NAME_DOOD = "Doodstream"
        private const val NAME_STAPE = "Streamtape"
        private const val NAME_VOE = "VOE"
        private const val NAME_VIZ = "Vidoza"
        private const val NAME_FILEMOON = "Filemoon"
        private const val NAME_VIDMOLY = "Vidmoly"

        private val PREF_HOSTER_NAMES = listOf(NAME_VOE, NAME_DOOD, NAME_STAPE, NAME_VIZ)
        private val PREF_HOSTER_DEFAULT = PREF_HOSTER_NAMES.first()

        private const val PREF_LANG_KEY = "preferred_lang"
        private val LANGS = mapOf(
            "1" to "Deutscher Sub",
            "2" to "Deutscher Dub",
            "3" to "Englischer Sub",

        )
        private val PREF_LANGS = LANGS.values.toList()
        private val PREF_LANG_DEFAULT = PREF_LANGS.first()
    }
}
