package eu.kanade.tachiyomi.animeextension.es.animeav1

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        private const val PREF_MEGA_SOFTWARE_DECODING_KEY = "mega_software_decoding"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "DUB")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "DUB")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"
        private val SERVER_LIST = arrayOf(
            "PixelDrain",
            "UPNShare",
            "Mega",
            "HLS",
            "StreamWish",
            "Voe",
            "YourUpload",
            "DoodStream",
            "FileLions",
            "VidHide",
            "StreamTape",
        )

        private val MEGA_HOSTS = listOf("mega.nz", "mega.co.nz")

        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val SERVER_REGEX = Regex("""\{\s*server\s*:\s*"([^"]*)"\s*,\s*url\s*:\s*"([^"]*)"\s*\}""")
        private val SUB_REGEX = Regex("""SUB\s*:\s*\[([^]]*)]""")
        private val DUB_REGEX = Regex("""DUB\s*:\s*\[([^]]*)]""")
        private val EPISODE_LIST_REGEX = Regex("""episodes\s*:\s*\[([^]]*)]""")
        private val EPISODE_REGEX = Regex("""\{\s*id\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*number\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*\}""")
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
        val baseUrl = doc.location().substringBefore("?").substringBefore("#")
        val episodes = EPISODE_LIST_REGEX.find(script)?.let {
            EPISODE_REGEX.findAll(it.groupValues[1]).map { match ->
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

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeAv1Filters.FILTER_LIST

    override fun hosterListParse(response: Response): List<Hoster> {
        val doc = response.useAsJsoup()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data() ?: return emptyList()

        val embeds = script.substringAfter("embeds:", "").substringBefore("downloads:")
        val downloads = script.substringAfter("downloads:", "")

        fun processMatches(block: String, regex: Regex, type: String): List<Triple<String, String, String>> = regex.findAll(block)
            .flatMap { SERVER_REGEX.findAll(it.groupValues[1]) }
            .map {
                val url = it.groupValues[2]
                Triple(
                    if (url.toHttpUrlOrNull()?.host in MEGA_HOSTS) url else url.substringBefore("?embed"),
                    it.groupValues[1],
                    type,
                )
            }
            .distinctBy { it.first }.toList()

        fun servers(type: String, regex: Regex) = processMatches(embeds, regex, type) +
            processMatches(downloads, regex, type).filter { (url, _, _) ->
                url.toHttpUrlOrNull()?.host in MEGA_HOSTS
            }

        val dubServers = servers("DUB", DUB_REGEX)
        val subServers = servers("SUB", SUB_REGEX)

        return (dubServers + subServers).distinctBy { it.first to it.third }.map { (url, server, type) ->
            val matched = findServer(url.toHttpUrlOrNull()?.host.orEmpty().lowercase(Locale.ROOT))
                ?: findServer(server.lowercase(Locale.ROOT))
            val name = when (matched) {
                "uns" -> "UPNShare"
                "player.zilla" -> "HLS"
                else -> SERVER_LIST.firstOrNull { it.equals(matched, true) } ?: server
            }
            Hoster(hosterUrl = url, hosterName = "$type $name", internalData = HosterData(server, type).toJsonString())
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.parseAs<HosterData>()
        return serverVideoResolver(hoster.hosterUrl, data.language, data.server).sortVideos()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val language = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(language, true) }
                .thenByDescending { it.hosterName.contains(server, true) },
        )
    }

    @Serializable
    private class HosterData(val server: String, val language: String)

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val megaExtractor by lazy { MegaExtractor(client, headers) }
    private val unsExtractor by lazy { UnsExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    private suspend fun serverVideoResolver(url: String, prefix: String, serverName: String): List<Video> {
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase(Locale.ROOT)
        val matched = findServer(host) ?: findServer(serverName.lowercase(Locale.ROOT))
        return when (matched) {
            "mega" -> megaExtractor.videosFromUrl(url, "$prefix ").map { video ->
                if (preferences.getBoolean(PREF_MEGA_SOFTWARE_DECODING_KEY, true)) {
                    video.copy(mpvArgs = video.mpvArgs.filterNot { it.first == "hwdec" } + ("hwdec" to "no"))
                } else {
                    video
                }
            }
            "uns" -> unsExtractor.videosFromUrl(url, "$prefix ")
            "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
            "pixeldrain" -> pixelDrainExtractor.videosFromUrl(url, "$prefix ")
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            "filelions" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" })
            "doodstream" -> doodExtractor.videosFromUrl(url, prefix)
            "streamtape" -> streamTapeExtractor.videosFromUrl(url, "$prefix StreamTape")
            "vidhide" -> {
                val name = if (host.contains("streamhide") || host.contains("streamvid")) "StreamHideVid" else "VidHide"
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

    private fun findServer(source: String): String? = if (source == "mega" || source in MEGA_HOSTS) {
        "mega"
    } else {
        conventions.firstOrNull { (_, names) ->
            names.any { it.lowercase(Locale.ROOT) in source }
        }?.first
    }

    private val conventions = listOf(
        "uns" to listOf("uns.bio", "upnshare"),
        "streamtape" to listOf("streamtape", "strtape", "shavetape", "streamadblock"),
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "mp4upload" to listOf("mp4upload"),
        "pixeldrain" to listOf("pixeldrain"),
        "player.zilla" to listOf("player.zilla"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "filelions" to listOf("filelions", "lion", "fviplions"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d"),
        "yourupload" to listOf("yourupload", "upload"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(langPref, true) },
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
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

        screen.addSwitchPreference(
            key = PREF_MEGA_SOFTWARE_DECODING_KEY,
            default = true,
            title = "Use software decoding for Mega",
            summary = "Uses software decoding in the built-in player to avoid green lines. Turn off to use your player's decoder setting.",
        )
    }
}
