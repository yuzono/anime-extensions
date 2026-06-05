package eu.kanade.tachiyomi.animeextension.es.animeav1

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import java.util.Locale

class AnimeAv1 :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeAv1"

    override val baseUrl = "https://animeav1.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "DUB")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "DUB")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"
        private val SERVER_LIST = arrayOf(
            "PixelDrain",
            "HLS",
            "StreamWish",
            "Voe",
            "YourUpload",
            "DoodStream",
            "FileLions",
            "VidHide",
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        val animeDetails = SAnime.create().apply {
            doc.selectFirst("h1.line-clamp-2")?.text()?.let { title = it }
            description = doc.selectFirst(".entry > p")?.text()
            genre = doc.select("header > .items-center > a").joinToString { it.text() }
            thumbnail_url = doc.selectFirst("img.object-cover")?.attr("abs:src")
        }
        doc.select("header > .items-center.text-sm span").eachText().forEach {
            when {
                it.contains("Finalizado") -> animeDetails.status = SAnime.COMPLETED
                it.contains("En emisión") -> animeDetails.status = SAnime.ONGOING
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val elements = document.select("article[class*=\"group/item\"]")
        val nextPage = document.select(".pointer-events-none:not([class*=\"max-sm:hidden\"]) ~ a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href").orEmpty())
                title = element.select("header h3").text()
                thumbnail_url = element.selectFirst(".bg-current img")?.attr("abs:src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeAv1Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/catalogo?search=$query&page=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/catalogo${params.getQuery().run { if (isNotBlank()) "$this&page=$page" else this }}", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.useAsJsoup()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data().orEmpty()
        val episodeListRegex = """episodes\s*:\s*\[([^]]*)]""".toRegex()
        val episodeRegex = """\{\s*id\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*number\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*\}""".toRegex()
        val baseUrl = doc.location().substringBefore("?").substringBefore("#")
        val episodes = episodeListRegex.find(script)?.let {
            episodeRegex.findAll(it.groupValues[1]).map { match ->
                val number = match.groupValues[2]
                SEpisode.create().apply {
                    name = "Episodio $number"
                    episode_number = number.toFloatOrNull() ?: 0F
                    setUrlWithoutDomain("$baseUrl/$number")
                }
            }.toList()
        }.orEmpty()

        return episodes.reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeAv1Filters.FILTER_LIST

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data() ?: return emptyList()

        val jsonRegex = Regex("""\{\s*server\s*:\s*"([^"]*)"\s*,\s*url\s*:\s*"([^"]*)"\s*\}""")
        val subRegex = Regex("""SUB\s*:\s*\[([^]]*)]""")
        val dubRegex = Regex("""DUB\s*:\s*\[([^]]*)]""")

        fun processMatches(regex: Regex, type: String): List<Triple<String, String, String>> = regex.findAll(script)
            .flatMap { jsonRegex.findAll(it.groupValues[1]) }
            .map {
                Triple(
                    it.groupValues[2].substringBefore("?embed"),
                    it.groupValues[1],
                    type,
                )
            }
            .distinctBy { it.first }.toList()

        val dubServers = processMatches(dubRegex, "DUB")
        val subServers = processMatches(subRegex, "SUB")

        return (dubServers + subServers).parallelCatchingFlatMapBlocking { (url, server, type) ->
            serverVideoResolver(url, type, server)
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun serverVideoResolver(url: String, prefix: String = "", serverName: String? = ""): List<Video> {
        val source = serverName?.ifEmpty { url } ?: url
        val matched = conventions
            .firstOrNull { (_, names) ->
                val sourceLower = source.lowercase(Locale.ROOT)
                names.any { it.lowercase(Locale.ROOT) in sourceLower }
            }
            ?.first
        return when (matched) {
            "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
            "pixeldrain" -> pixelDrainExtractor.videosFromUrl(url, "$prefix ")
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            "filelions" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" })
            "doodstream" -> doodExtractor.videosFromUrl(url, prefix)
            "vidhide" -> {
                val urlLower = url.lowercase(Locale.ROOT)
                val sourceLower = source.lowercase(Locale.ROOT)
                val name = if (urlLower.contains("streamhide") || urlLower.contains("streamvid") || sourceLower.contains("streamhidevid")) "StreamHideVid" else "VidHide"
                vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix $name:$it" })
            }
            "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
            "player.zilla" -> {
                val m3u = url.replace("play/", "m3u8/")
                listOf(Video(m3u, "$prefix HLS", m3u))
            }
            else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
        }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "mp4upload" to listOf("mp4upload"),
        "pixeldrain" to listOf("pixeldrain"),
        "player.zilla" to listOf("player.zilla"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "filelions" to listOf("filelions", "lion", "fviplions"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "yourupload" to listOf("yourupload", "upload"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(langPref, true) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
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
            key = PREF_LANG_KEY
            title = "Preferred Language"
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
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
    }
}
