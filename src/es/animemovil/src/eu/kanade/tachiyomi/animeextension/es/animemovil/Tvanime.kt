package eu.kanade.tachiyomi.animeextension.es.animemovil

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.net.URLEncoder

class Tvanime :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    // AnimeMovil -> TVAnime
    override val id: Long = 3892773447316414021L

    override val name = "TVAnime"

    override val baseUrl = "https://tvanime.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val ajaxHeaders by lazy {
        headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "text/html")
            .build()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("Voe", "MP4Upload", "YourUpload", "StreamTape", "Mega", "UPNShare", "Byse")
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio/?sort=rating&page=$page", headers)

    override fun popularAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/directorio/?sort=newest&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parseAnimeList(response.asJsoup())

    private fun parseAnimeList(document: Document): AnimesPage {
        val currentPage = document.selectFirst(".catalog-pagination .pagination-current")
            ?.text()?.trim()?.toIntOrNull() ?: 1
        val maxPage = document.select(".catalog-pagination a[href*=page=]")
            .mapNotNull { Regex("page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

        val animeList = document.select(".catalog-grid article.anime-card").mapNotNull { element ->
            val url = element.selectFirst("a.anime-card-image")?.attr("abs:href")
                ?: element.selectFirst(".anime-card-title a")?.attr("abs:href")
                ?: return@mapNotNull null
            val title = element.selectFirst(".anime-card-title")?.text()?.trim()
                ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = element.selectFirst(".anime-card-image img")?.attr("abs:src")
                status = parseStatus(element.select(".anime-card-meta span").text())
            }
        }
        return AnimesPage(animeList, currentPage < maxPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/directorio/?q=${URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        }

        val filterParams = filters.getSearchParameters()

        val params = mutableMapOf<String, String>()
        if (filterParams.genre.isNotBlank()) {
            params["genre"] = filterParams.genre
        }
        if (filterParams.type.isNotBlank()) {
            params["type"] = filterParams.type
        }
        if (filterParams.status.isNotBlank()) {
            params["status"] = filterParams.status
        }
        params["page"] = "$page"

        return GET("$baseUrl/directorio/?${encodeQuery(params)}", headers)
    }

    override fun searchAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.anime-title")?.text()?.trim() ?: ""
            description = document.selectFirst("p.anime-synopsis")?.text()?.trim()
            thumbnail_url = document.selectFirst(".anime-poster img")?.attr("abs:src")
            genre = document.select(".anime-genres a").joinToString { it.text().trim() }
            status = parseStatus(document.selectFirst(".anime-status")?.text() ?: "")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url, headers).useAsJsoup()
        return parseEpisodeList(document, anime.url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private suspend fun parseEpisodeList(document: Document, animeUrl: String): List<SEpisode> {
        val slug = document.selectFirst("#episodes-content")?.attr("data-anime-slug")
            ?: animeUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return emptyList()

        val episodes = mutableListOf<SEpisode>()
        episodes += parseEpisodes(document.select(".episode-grid a.episode-card"))

        val lastRange = document.select("select.episode-range-select option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull() ?: 1
        for (range in 2..lastRange) {
            runCatching {
                client.get("$baseUrl/anime/$slug/episodes/$range", ajaxHeaders).useAsJsoup()
            }.getOrNull()?.let { rangeDocument ->
                episodes += parseEpisodes(rangeDocument.select("a.episode-card"))
            }
        }

        return episodes.reversed()
    }

    private fun parseEpisodes(cards: Elements): List<SEpisode> = cards.mapNotNull { card ->
        val url = card.attr("abs:href").ifBlank { card.attr("href") }
        if (url.isBlank()) return@mapNotNull null
        val number = card.selectFirst(".episode-number")?.text()?.removePrefix("E")?.trim()?.toFloatOrNull()
        SEpisode.create().apply {
            setUrlWithoutDomain(url)
            name = card.selectFirst(".episode-card-body strong")?.text()?.trim()
                ?: number?.let { "Episodio ${it.toInt()}" }
                ?: "Episodio"
            episode_number = number ?: 0f
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(".watch-server").catchingFlatMapBlocking { button ->
            val url = button.attr("data-server-url").trim()
            if (url.isBlank() || url.contains("mega.nz")) {
                return@catchingFlatMapBlocking emptyList()
            }
            val serverName = button.attr("data-server-name").ifBlank { button.text().trim() }
            val language = button.attr("data-server-language").trim()
            val label = if (language.isBlank()) serverName else "$serverName ($language)"
            serverVideoResolver(url, label)
        }
    }

    private suspend fun serverVideoResolver(url: String, label: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> {
                VoeExtractor(client, headers).videosFromUrl(url, prefix = "$label: ")
            }

            embedUrl.contains("mp4upload") -> {
                Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = "$label: ")
            }

            embedUrl.contains("yourupload") -> {
                YourUploadExtractor(client).videoFromUrl(url, headers, name = label)
            }

            embedUrl.contains("streamtape") -> {
                StreamTapeExtractor(client).videosFromUrl(url, quality = label)
            }

            else -> {
                UniversalExtractor(client).videosFromUrl(url, headers, prefix = "$label: ")
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

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
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Finalizado", true) -> SAnime.COMPLETED
        text.contains("En emisi", true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun encodeQuery(params: Map<String, String>): String = params.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}
