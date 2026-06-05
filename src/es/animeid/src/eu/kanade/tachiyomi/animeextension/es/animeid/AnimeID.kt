package eu.kanade.tachiyomi.animeextension.es.animeid

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.runBlocking

class AnimeID : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Extractores usando el client estándar
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }

    // ============================== Videos ===============================
private suspend fun serverVideoResolver(url: String): List<Video> {
    val embedUrl = url.lowercase()
    return try {
        when {
            embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url, prefix = "Voe: ")
            embedUrl.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload: ")
            embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload: ")
            embedUrl.contains("streamwish") -> streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    } catch (e: Exception) {
        universalExtractor.videosFromUrl(url, headers)
    }
}

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string() ?: return emptyList()
        val document = Jsoup.parse(body, baseUrl)
        val videos = mutableListOf<Video>()

        // Servidores vía AJAX
        val optElement = document.selectFirst("ul.opt[data-encrypt]")
        if (optElement != null) {
            val encryptedId = optElement.attr("data-encrypt")
            val serversHtml = fetchServerList(encryptedId)
            if (serversHtml.isNotBlank()) {
                val videoUrls = parseServerUrls(serversHtml)
                videoUrls.forEach { (_, pageUrl) ->
                    runBlocking { videos.addAll(serverVideoResolver(pageUrl)) }
                }
                if (videos.isNotEmpty()) return filterByPreferredServer(videos)
            }
        }

        // Fallback antiguo
        if (videos.isEmpty()) {
            document.select("#partes div.container li.subtab div.parte").forEach { script ->
                val jsonString = script.attr("data")
                val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
                val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
                if (url.isNotBlank()) {
                    runBlocking { videos.addAll(serverVideoResolver(url)) }
                }
            }
        }

        return filterByPreferredServer(videos)
    }

    // ============================== Preferencias ===============================
override fun setupPreferenceScreen(screen: PreferenceScreen) {
    ListPreference(screen.context).apply {
        key = "preferred_server"
        title = "Servidor preferido"
        entries = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
        entryValues = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
        setDefaultValue("Voe")
        summary = "%s"
    }.also(screen::addPreference)
}
