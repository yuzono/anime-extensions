package eu.kanade.tachiyomi.animeextension.en.blzone

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

@Source
abstract class BLZone :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ---- FILTERS ----
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("Both", ""),
                Pair("Anime", "anime"),
                Pair("Drama", "dorama"),
            ),
        )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    // ---- POPULAR ----
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows")
            .map { popularAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster?.selectFirst("a")?.absUrl("href") ?: element.selectFirst("h3 a")?.absUrl("href")!!
        val img = poster?.selectFirst("img")
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() } ?: element.selectFirst("h3 a")!!.text()
        anime.thumbnail_url = img?.absUrl("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- LATEST ----
    override fun latestUpdatesRequest(page: Int): Request {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(animePageUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".items.full .item.tvshows")
            .map { latestAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        val response = client.get(animePageUrl, headers)
        val document = response.asJsoup()
        val animeList = document.select(".items.full .item.tvshows")
            .map { latestAnimeFromElement(it) }.toMutableList()

        if (page == 1) {
            try {
                val doramaResponse = client.get("$baseUrl/dorama/", headers)
                val doramaItems = doramaResponse.asJsoup()
                    .select(".items.full .item.tvshows")
                    .map { latestAnimeFromElement(it) }
                animeList.addAll(doramaItems)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(document: Document): Boolean = document.selectFirst(".pagination span.current + a") != null

    // ---- SEARCH ----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val url = baseUrl.toHttpUrl()
            .newBuilder().apply {
                if (typeFilter != null && !typeFilter.isDefault()) {
                    addPathSegment(typeFilter.toUriPart())
                }
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                addQueryParameter("s", query.trim())
            }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".search-page .result-item article").map { searchAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst(".thumbnail img")
        val link = element.selectFirst(".thumbnail a")?.absUrl("href") ?: element.selectFirst(".title a")?.absUrl("href")!!
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() } ?: element.selectFirst(".title a")!!.text()
        anime.thumbnail_url = img?.absUrl("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- DETAILS ----
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")
        (document.selectFirst(".sheader .data h1")?.text() ?: poster?.attr("alt"))?.let {
            anime.title = it
        }
        anime.thumbnail_url = poster?.absUrl("src")
        anime.genre = document.select(".sheader .sgeneros a").eachText().joinToString()
        val desc = document.selectFirst(".sbox .wp-content p")?.text()
            ?.takeIf { it.isNotBlank() }
        val altTitle = document.selectFirst(".custom_fields b.variante:contains(Original Title) + span.valor")?.text()
            ?.takeIf { it.isNotBlank() }
        anime.description = listOfNotNull(desc, altTitle)
            .joinToString("\n\n")
            .ifBlank { "No description available." }
        return anime
    }

    // ---- EPISODES ----
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("#episodes ul.episodios2 > li").map { episodeFromElement(it) }.reversed()
    }

    private fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")?.absUrl("href")!!
        ep.setUrlWithoutDomain(link)
        ep.name = element.selectFirst(".episodiotitle a")?.text() ?: "Episode"
        val episodeNum = EPISODE_NUMBER_REGEX.find(ep.name)?.groupValues?.getOrNull(1)
        episodeNum?.toFloatOrNull()?.let { ep.episode_number = it }
        return ep
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ---- VIDEO EXTRACTORS ----
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val mp4UploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    // ---- HOSTER LIST ----
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.get(baseUrl + episode.url)
        val document = response.asJsoup()
        val serverNames = document.select("#playeroptionsul li span.title").map { it.text() }
        val serverBoxes = document.select(".dooplay_player .source-box").drop(1)

        return serverBoxes.mapIndexedNotNull { index, box ->
            val matchedServerName = serverNames.getOrNull(index)?.let { serverName ->
                SERVER_LIST.firstOrNull { serverName.contains(it, ignoreCase = true) }
            } ?: return@mapIndexedNotNull null

            val iframe = box.selectFirst("iframe.metaframe")
            val src = iframe?.attr("src")?.trim().orEmpty()
            if (src.isBlank()) return@mapIndexedNotNull null
            val embedUrl = if (src.contains("/diclaimer/?url=")) {
                URLDecoder.decode(src.substringAfter("/diclaimer/?url="), UTF_8.name())
            } else {
                src
            }
            Hoster(
                hosterUrl = embedUrl,
                hosterName = matchedServerName,
                internalData = embedUrl,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.internalData.ifEmpty { hoster.hosterUrl }
        return try {
            serverVideoResolver(embedUrl)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serverVideoResolver(url: String): List<Video> = when {
        url.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, "FileMoon - ")
        url.contains("byseqekaho") -> filemoonExtractor.videosFromUrl(url, "Byse - ")
        url.contains("streamtape") -> streamtapeExtractor.videosFromUrl(url, "StreamTape")
        url.contains("mixdrop") || url.contains("mxdrop") -> mixDropExtractor.videosFromUrl(url, "en")
        url.contains("vgembed") -> vidGuardExtractor.videosFromUrl(url, "VidGuard")
        url.contains("mp4upload") -> mp4UploadExtractor.videosFromUrl(url, headers)
        url.contains("voe") -> voeExtractor.videosFromUrl(url, "Voe")
        url.contains("fsst.online") || url.contains("ruplay") -> resolveRuplay(url)
        else -> emptyList()
    }

    private fun resolveRuplay(url: String): List<Video> {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            val fileContent = body
                .substringAfter("Playerjs({", "")
                .substringAfter("file:\"", "")
                .substringBefore("\"", "")
            if (fileContent.isBlank()) return emptyList()

            fileContent
                .split(",")
                .mapNotNull { file ->
                    val videoUrl = file.substringAfter("]").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val quality = file.substringAfter("[", "").substringBefore("]").ifEmpty { "Default" }
                    val order = quality.filter { it.isDigit() }.toIntOrNull() ?: 0
                    Triple(videoUrl, quality, order)
                }
                .sortedByDescending { it.third }
                .map { (videoUrl, quality) ->
                    val videoHeaders = headers.newBuilder()
                        .set("Referer", videoUrl)
                        .build()
                    Video(
                        videoUrl = videoUrl,
                        videoTitle = "Ruplay - $quality",
                        headers = videoHeaders,
                    )
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.hosterName.contains(preferredServer, ignoreCase = true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(preferredServer, ignoreCase = true) },
        )
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
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf("Filemoon", "StreamTape", "MixDrop", "VidGuard", "MP4", "Voe", "Byse", "Ruplay")
        private val EPISODE_NUMBER_REGEX = Regex("""Episode (\d+)""", RegexOption.IGNORE_CASE)
    }
}
