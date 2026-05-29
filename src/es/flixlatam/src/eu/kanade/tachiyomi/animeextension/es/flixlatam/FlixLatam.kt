package eu.kanade.tachiyomi.animeextension.es.flixlatam

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamsilkextractor.StreamSilkExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class FlixLatam :
    DooPlay(
        "es",
        "FlixLatam",
        "https://flixlatam.com",
    ) {
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/pelicula/page/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lanzamiento/2024/page/$page")

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")

        // Iterar sobre cada player
        return players.parallelCatchingFlatMapBlocking { player ->
            val url = getPlayerUrl(player)
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            if (url.contains("embed69")) {
                val htmlContent = client.newCall(GET(url)).awaitSuccess().bodyString()
                val links = extractNewExtractorLinks(htmlContent) ?: return@parallelCatchingFlatMapBlocking emptyList()
                links.parallelCatchingFlatMap { (link, language) ->
                    serverVideoResolver(link, " $language")
                }
            } else {
                emptyList()
            }
        }
    }

    private suspend fun getPlayerUrl(player: Element): String? {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .awaitSuccess().bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
            .takeIf(String::isNotBlank)
    }

    /*-------------------------------- Video extractors ------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private suspend fun serverVideoResolver(url: String, prefix: String = ""): List<Video> = when {
        "voe" in url -> voeExtractor.videosFromUrl(url, "$prefix ")
        "ok.ru" in url || "okru" in url -> okruExtractor.videosFromUrl(url, prefix)
        "filemoon" in url || "moonplayer" in url -> filemoonExtractor.videosFromUrl(url, "$prefix Filemoon:")
        "amazon" in url || "amz" in url -> extractAmazonVideo(url, prefix)
        "uqload" in url -> uqloadExtractor.videosFromUrl(url, prefix)
        "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers, "$prefix ")
        "streamwish" in url || "wish" in url -> streamWishExtractor.videosFromUrl(url, "$prefix StreamWish:")
        "doodstream" in url || "dood." in url -> doodExtractor.videosFromUrl(url.replace("https://doodstream.com/e/", "https://d0000d.com/e/"), "$prefix DoodStream")
        "streamlare" in url -> streamlareExtractor.videosFromUrl(url, prefix)
        "yourupload" in url -> yourUploadExtractor.videoFromUrl(url, headers, "$prefix ")
        "burstcloud" in url -> burstCloudExtractor.videoFromUrl(url, headers, "$prefix ")
        "fastream" in url -> fastreamExtractor.videosFromUrl(url, "$prefix Fastream:")
        "upstream" in url -> upstreamExtractor.videosFromUrl(url, "$prefix ")
        "streamsilk" in url -> streamSilkExtractor.videosFromUrl(url, "$prefix StreamSilk:")
        "streamtape" in url || "stp" in url -> streamTapeExtractor.videosFromUrl(url, "$prefix StreamTape")
        arrayOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide").any(url) -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })
        arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
        else -> emptyList()
    }

    private suspend fun extractAmazonVideo(url: String, prefix: String): List<Video> {
        val body = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val shareId = body.selectFirst("script:containsData(var shareId)")
            ?.data()
            ?.substringAfter("shareId = \"")
            ?.substringBefore("\"") ?: return emptyList()

        val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
            .awaitSuccess().useAsJsoup()

        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
        val amazonApi = client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
            .awaitSuccess().useAsJsoup()

        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
        return listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
    }

    private fun extractNewExtractorLinks(htmlContent: String): List<Pair<String, String>>? {
        val jsLinksMatch = getFirstMatch("""dataLink = (\[.+?]);""".toRegex(), htmlContent) ?: return null

        val items = jsLinksMatch.parseAs<List<Item>>()
        val idiomas = mapOf("LAT" to "[LAT]", "ESP" to "[CAST]", "SUB" to "[SUB]")

        return items.flatMap { item ->
            val languageCode = idiomas[item.video_language] ?: "unknown"
            item.sortedEmbeds.mapNotNull { embed ->
                runCatching {
                    val decryptedLink = CryptoAES.decrypt(embed.link, "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE")
                    Pair(decryptedLink, languageCode)
                }.getOrNull()
            }
        }.ifEmpty { null }
    }

    private fun getFirstMatch(regex: Regex, input: String): String? = regex.find(input)?.groupValues?.get(1)

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = FlixLatamFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FlixLatamFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("ratings", "tendencias", "pelicula")) {
                    "/${params.genre}"
                } else {
                    "/genero/${params.genre}"
                }
            }

            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        else -> "/"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    @Serializable
    data class Item(
        val file_id: Int,
        val video_language: String, // Campo nuevo para almacenar el idioma
        val sortedEmbeds: List<Embed>,
    )

    @Serializable
    data class Embed(
        val servername: String,
        val link: String,
        val type: String,
    )

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "[LAT]"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "VidGuard", "StreamHideVid", "Voe")
    }
}
