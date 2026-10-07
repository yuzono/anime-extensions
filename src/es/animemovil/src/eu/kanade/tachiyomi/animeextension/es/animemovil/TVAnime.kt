package eu.kanade.tachiyomi.animeextension.es.animemovil

import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

class TVAnime :
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
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = listOf(
            "Voe",
            "MP4Upload",
            "YourUpload",
            "StreamTape",
            "VidHide",
            "UPNShare",
            "Byse",
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio/?sort=rating&page=$page", headers)

    override fun popularAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/directorio/?sort=newest&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parseAnimeList(response.asJsoup())

    private fun parseAnimeList(document: Document): AnimesPage {
        val currentPage = document.selectFirst(".catalog-pagination .pagination-current")
            ?.text()?.toIntOrNull() ?: 1
        val maxPage = document.select(".catalog-pagination a[href*=page=]")
            .mapNotNull { anchor ->
                anchor.attr("abs:href")
                    .toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
            }
            .maxOrNull() ?: 1

        val animeList = document.select(".catalog-grid article.anime-card").mapNotNull { element ->
            val url = element.selectFirst("a.anime-card-image")?.attr("abs:href")
                ?: element.selectFirst(".anime-card-title a")?.attr("abs:href")
                ?: return@mapNotNull null
            val title = element.selectFirst(".anime-card-title")?.text()
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
        val url = "$baseUrl/directorio/".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
            return GET(url.build(), headers)
        }

        val filterParams = filters.getSearchParameters()
        if (filterParams.genre.isNotBlank()) {
            url.addQueryParameter("genre", filterParams.genre)
        }
        if (filterParams.type.isNotBlank()) {
            url.addQueryParameter("type", filterParams.type)
        }
        if (filterParams.status.isNotBlank()) {
            url.addQueryParameter("status", filterParams.status)
        }

        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.anime-title")!!.text()
            description = document.selectFirst("p.anime-synopsis")?.text()
            thumbnail_url = document.selectFirst(".anime-poster img")?.attr("abs:src")
            genre = document.select(".anime-genres a").joinToString { it.text() }
            status = parseStatus(document.selectFirst(".anime-status")?.text() ?: "")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url).useAsJsoup()
        return parseEpisodeList(document, anime.url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private suspend fun parseEpisodeList(document: Document, animeUrl: String): List<SEpisode> {
        val slug = document.selectFirst("#episodes-content")?.attr("data-anime-slug")?.takeIf { it.isNotBlank() }
            ?: animeUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return emptyList()

        val episodes = mutableListOf<SEpisode>()
        episodes += parseEpisodes(document.select(".episode-grid a.episode-card"))

        val lastRange = document.select("select.episode-range-select option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull() ?: 1
        for (range in 2..lastRange) {
            try {
                val rangeDocument = client.get("$baseUrl/anime/$slug/episodes/$range", ajaxHeaders).useAsJsoup()
                episodes += parseEpisodes(rangeDocument.select("a.episode-card"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Skip a failed range; episodes from other ranges are still used.
            }
        }

        return episodes.reversed()
    }

    private fun parseEpisodes(cards: Elements): List<SEpisode> = cards.mapNotNull { card ->
        val url = card.attr("abs:href")
        if (url.isBlank()) return@mapNotNull null
        val number = card.selectFirst(".episode-number")?.text()?.removePrefix("E")?.toFloatOrNull()
        SEpisode.create().apply {
            setUrlWithoutDomain(url)
            name = card.selectFirst(".episode-card-body strong")?.text()
                ?: number?.let { "Episodio ${it.toInt()}" }
                ?: "Episodio"
            episode_number = number ?: 0f
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(".watch-server").catchingFlatMapBlocking { button ->
            val url = button.attr("data-server-url").trim()
            if (url.isBlank()) {
                return@catchingFlatMapBlocking emptyList()
            }
            val serverName = button.attr("data-server-name").ifBlank { button.text() }
            val language = button.attr("data-server-language").trim()
            val label = if (language.isBlank()) serverName else "$serverName ($language)"
            serverVideoResolver(url, serverName, label)
        }
    }

    private suspend fun serverVideoResolver(url: String, serverName: String, label: String): List<Video> = when (serverName.lowercase()) {
        "voe" -> VoeExtractor(client, headers).videosFromUrl(url, prefix = "$label: ")

        "mp4upload" -> Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = "$label: ")

        "yourupload" -> YourUploadExtractor(client).videoFromUrl(url, headers, name = label)

        "streamtape" -> StreamTapeExtractor(client).videosFromUrl(url, quality = label)

        // VidHide and StreamWish use the same player.
        "vidhide", "streamwish" -> VidHideExtractor(client, headers).videosFromUrl(url) { "$label: $it" }

        "byse" -> FilemoonExtractor(client).videosFromUrl(url, prefix = "$label: ", headers = headers, referer = url)

        "mega" -> MegaExtractor(client, headers).videosFromUrl(url, prefix = "$label: ")

        else -> UniversalExtractor(client).videosFromUrl(url, headers, prefix = "$label: ")
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
        )
    }

    private fun parseStatus(text: String): Int {
        val status = text.lowercase()
        return when {
            status.contains("finalizado") -> SAnime.COMPLETED
            status.contains("en emisi") -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }
}
