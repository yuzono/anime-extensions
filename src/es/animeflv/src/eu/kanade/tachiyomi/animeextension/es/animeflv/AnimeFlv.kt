package eu.kanade.tachiyomi.animeextension.es.animeflv

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AnimeFlv :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val SharedPreferences.userAgent by preferences.delegate(PREF_USER_AGENT, DESKTOP_USER_AGENT)

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", preferences.userAgent.ifBlank { network.defaultUserAgentProvider() })

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "YourUpload", "Okru", "Streamtape")

        private const val PREF_USER_AGENT = "preferred_user_agent"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?order=rating&page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = try {
            element.select("a div.Image figure img").attr("src")
        } catch (_: Exception) {
            element.select("a div.Image figure img").attr("data-cfsrc")
        }
        anime.description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=\"next\"]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        document.select("script").forEach { script ->
            if (script.data().contains("var anime_info =")) {
                val animeInfo = script.data().substringAfter("var anime_info = [").substringBefore("];")
                val arrInfo = json.decodeFromString<List<String>>("[$animeInfo]")

                val animeUri = arrInfo[2].replace("\"", "")
                val episodes = script.data().substringAfter("var episodes = [").substringBefore("];").trim()
                val arrEpisodes = episodes.split("],[")
                arrEpisodes.forEach { arrEp ->
                    val noEpisode = arrEp.replace("[", "").replace("]", "")
                        .split(",")[0]
                    val ep = SEpisode.create()
                    val url = "$baseUrl/ver/$animeUri-$noEpisode"
                    ep.setUrlWithoutDomain(url)
                    ep.name = "Episodio $noEpisode"
                    ep.episode_number = noEpisode.toFloat()
                    episodeList.add(ep)
                }
            }
        }
        return episodeList
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*--------------------------------Video extractors------------------------------------*/
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData(var videos = {)")?.data() ?: return emptyList()
        val responseString = jsonString.substringAfter("var videos =").substringBefore(";").trim()
        return json.decodeFromString<ServerModel>(responseString).sub.parallelCatchingFlatMapBlocking { it ->
            when (it.title) {
                "Stape" -> streamTapeExtractor.videosFromUrl(it.code)
                "Okru" -> okruExtractor.videosFromUrl(it.code)
                "YourUpload" -> yourUploadExtractor.videoFromUrl(it.code, headers = headers)
                "SW" -> streamWishExtractor.videosFromUrl(it.code, videoNameGen = { "StreamWish:$it" })
                else -> universalExtractor.videosFromUrl(it.code, headers)
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeFlvFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/browse?q=$query&page=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/browse${params.getQuery()}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFlvFilters.FILTER_LIST

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")!!.attr("abs:src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        anime.description = document.selectFirst("div.Description")!!.text().removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("En emision") -> SAnime.ONGOING
        statusString.contains("Finalizado") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesNextPageSelector() = null

    override fun latestUpdatesSelector() = "div.Container ul.ListEpisodios li a.fa-play"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("abs:href").replace("/ver/", "/anime/").substringBeforeLast("-"))
        anime.title = element.select("strong.Title").text()
        anime.thumbnail_url = element.select("span.Image img").attr("abs:src").replace("thumbs", "covers")
        return anime
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            key = PREF_USER_AGENT,
            title = "User-Agent",
            default = DESKTOP_USER_AGENT,
            summary = "Leave blank to use the default app user agent.",
            restartRequired = true,
        )
    }

    @Serializable
    data class ServerModel(
        @SerialName("SUB")
        val sub: List<Sub> = emptyList(),
    )

    @Serializable
    data class Sub(
        val server: String? = "",
        val title: String? = "",
        val ads: Long? = null,
        val code: String = "",
        @SerialName("allow_mobile")
        val allowMobile: Boolean? = false,
    )
}
