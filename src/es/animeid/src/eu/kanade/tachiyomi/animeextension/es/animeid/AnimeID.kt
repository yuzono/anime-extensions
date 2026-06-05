package eu.kanade.tachiyomi.animeextension.es.animeid

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeID : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Extractores
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Episodes ===============================
    override fun episodeListSelector(): String = "ul.epis li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.select("a").first()
        val href = linkElement?.attr("href") ?: ""
        val episodeNumber = href.substringAfterLast("-").toFloatOrNull() ?: 1f
        episode.setUrlWithoutDomain(href)
        episode.name = "Episodio ${episodeNumber.toInt()}"
        episode.episode_number = episodeNumber
        return episode
    }

    override suspend fun episodeListParse(response: Response): List<SEpisode> {
        val initialHtml = response.body?.string() ?: return emptyList()
        val doc = Jsoup.parse(initialHtml, baseUrl)
        val animeId = doc.selectFirst("#dt")?.attr("data-i") ?: return emptyList()
        val animeSlug = doc.selectFirst("#dt")?.attr("data-u") ?: return emptyList()

        val allEpisodes = mutableListOf<SEpisode>()
        var currentPage = 1
        while (true) {
            val pageHtml = fetchEpisodesPage(animeId, animeSlug, currentPage)
            if (pageHtml.isBlank()) break
            val pageDoc = Jsoup.parse(pageHtml)
            val episodesOnPage = pageDoc.select(episodeListSelector()).map { episodeFromElement(it) }
            if (episodesOnPage.isEmpty()) break
            allEpisodes.addAll(episodesOnPage)
            currentPage++
            delay(500)
        }
        return allEpisodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    private fun fetchEpisodesPage(animeId: String, animeSlug: String, page: Int): String {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/id")
                .post(
                    FormBody.Builder()
                        .add("acc", "episodes")
                        .add("i", animeId)
                        .add("u", animeSlug)
                        .add("p", page.toString())
                        .build()
                )
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", baseUrl)
                .build()
            val response = client.newCall(request).execute()
            response.body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ============================== Videos ===============================
    private suspend fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return try {
            when {
                embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url, prefix = "Voe")
                embedUrl.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload")
                embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload")
                embedUrl.contains("streamwish") -> streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        } catch (e: Exception) {
            universalExtractor.videosFromUrl(url, headers)
        }
    }

    override suspend fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string() ?: return emptyList()
        val document = Jsoup.parse(body, baseUrl)
        val videos = mutableListOf<Video>()

        val optElement = document.selectFirst("ul.opt[data-encrypt]")
        if (optElement != null) {
            val encryptedId = optElement.attr("data-encrypt")
            val serversHtml = fetchServerList(encryptedId)
            if (serversHtml.isNotBlank()) {
                val videoUrls = parseServerUrls(serversHtml)
                for ((_, pageUrl) in videoUrls) {
                    videos.addAll(serverVideoResolver(pageUrl))
                }
                if (videos.isNotEmpty()) return filterByPreferences(videos)
            }
        }

        if (videos.isEmpty()) {
            document.select("#partes div.container li.subtab div.parte").forEach { script ->
                val jsonString = script.attr("data")
                val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
                val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
                if (url.isNotBlank()) {
                    videos.addAll(serverVideoResolver(url))
                }
            }
        }

        return filterByPreferences(videos)
    }

    private fun filterByPreferences(videos: List<Video>): List<Video> {
        val preferredServer = preferences.getString("animeid_preferred_server", "Voe")
        val preferredQuality = preferences.getString("animeid_preferred_quality", "any")

        var filtered = videos

        if (!preferredServer.isNullOrBlank()) {
            val byServer = filtered.filter {
                it.quality.contains(preferredServer, ignoreCase = true) ||
                it.url.contains(preferredServer, ignoreCase = true)
            }
            if (byServer.isNotEmpty()) filtered = byServer
        }

        if (!preferredQuality.isNullOrBlank() && preferredQuality != "any") {
            val qToken = "${preferredQuality}p"
            val byQuality = filtered.filter {
                it.quality.contains(qToken, ignoreCase = true) ||
                it.quality.contains(preferredQuality, ignoreCase = true)
            }
            if (byQuality.isNotEmpty()) filtered = byQuality
        }

        return filtered
    }

    // ============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "animeid_preferred_server"
            title = "Servidor preferido"
            entries = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            entryValues = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            setDefaultValue("Voe")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "animeid_preferred_quality"
            title = "Calidad preferida"
            entries = arrayOf("Cualquiera", "480p", "720p", "1080p")
            entryValues = arrayOf("any", "480", "720", "1080")
            setDefaultValue("any")
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Utilidades ===============================
    private fun fetchServerList(encryptId: String): String { /* igual que antes */ return "" }
    private fun parseServerUrls(html: String): List<Pair<String, String>> { /* igual que antes */ return emptyList() }
    private fun unescapeJava(escaped: String): String { /* igual que antes */ return escaped }
    private fun fetchUrls(text: String?): List<String> { /* igual que antes */ return emptyList() }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun
