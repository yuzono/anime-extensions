package eu.kanade.tachiyomi.animeextension.es.veranime

import android.net.Uri
import androidx.preference.PreferenceScreen
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class VerAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "VerAni.me"

    override val baseUrl = "https://verani.me"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/page/$page/?orderby=popular", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val elements = document.select(".anime-card a, article a")
        val nextPage = document.select(".pagination .next, a.next").any()

        val animeList = elements.mapNotNull { element ->
            val href = element.attr("abs:href")
            if (href.isBlank() || href.contains("/page/") || href.contains("/animes/") || !href.contains("verani.me")) return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = element.selectFirst("h3")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim() ?: "Anime"
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        return AnimesPage(animeList, nextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/animes/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        GET("$baseUrl/page/$page/?s=$encodedQuery", headers)
    } else {
        popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: "Anime"
            description =
                document.selectFirst(".anime-hero-description, .sinopsis, .description, p.desc, .info p, .pelicula-overview p")?.text()
            genre = document.select("a[href*=\"categoria\"]").map { it.text().trim() }.distinct().joinToString()
            status = parseStatus(
                document.selectFirst("div.anime-info-label:contains(Estado) span, div.anime-info-label:contains(Estado), .status")
                    ?.text()?.replace("Estado", "", true)?.trim(),
            )
            if (status == SAnime.UNKNOWN && response.request.url.toString().contains("/pelicula/")) {
                status = SAnime.COMPLETED
            }
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "en emision", "en emisión" -> SAnime.ONGOING
        "finalizado" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = mutableListOf<SEpisode>()

        // Some series group episodes into seasons
        val groups = document.select(".temporada-group")

        if (groups.isNotEmpty()) {
            val formatSeason = groups.size > 1
            groups.forEach { group ->
                val seasonNumber = group.selectFirst(".temporada-badge, .temporada-name")?.text()?.let { text ->
                    Regex("""\d+""").find(text)?.value?.toIntOrNull()
                }
                val seasonPrefix = if (formatSeason && seasonNumber != null) {
                    "S${seasonNumber.toString().padStart(2, '0')} "
                } else {
                    ""
                }

                group.select(".capitulo-card-link, a[href*=\"capitulo\"], a[href*=\"episodio\"]").forEach { element ->
                    parseEpisodeElement(element, seasonPrefix)?.let { episodes.add(it) }
                }
            }
        } else {
            document.select(".capitulo-card-link, a[href*=\"capitulo\"], a[href*=\"episodio\"]").forEach { element ->
                parseEpisodeElement(element, "")?.let { episodes.add(it) }
            }
        }

        if (episodes.isEmpty() && document.selectFirst(".iframe-wrapper") != null) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Película"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1f
                },
            )
        }

        return episodes
    }

    private fun parseEpisodeElement(element: org.jsoup.nodes.Element, seasonPrefix: String): SEpisode? {
        val href = element.attr("abs:href")
        val text = element.text().trim()
        if (href.isBlank() || href.contains("proximos-capitulos") ||
            element.hasClass("ver-ahora") ||
            text.contains(
                "ver ahora",
                true,
            )
        ) {
            return null
        }

        return SEpisode.create().apply {
            name = seasonPrefix + text.ifBlank { "Capítulo" }
            setUrlWithoutDomain(href)

            val episodeNumber =
                Regex("""(?i)(?:capitulo|episodio)\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            if (episodeNumber != null) {
                episode_number = episodeNumber
            } else {
                Regex("""(?:capitulo|episodio)-(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
                    episode_number = it
                }
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val iframes = document.select("iframe[src], iframe[data-src]")

        return iframes.parallelCatchingFlatMapBlocking { iframe ->
            val src = iframe.attr("abs:src").ifEmpty { iframe.attr("abs:data-src") }
            if (src.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val parentItem = iframe.closest(".iframe-item")
            val language =
                parentItem?.selectFirst("span:contains(Idioma:)")?.text()?.substringAfter("Idioma:")?.trim() ?: ""

            val videos = serverVideoResolver(src)
            if (language.isNotBlank()) {
                videos.map {
                    Video(it.url, "[$language] ${it.quality}", it.videoUrl, it.headers, it.subtitleTracks, it.audioTracks)
                }
            } else {
                videos
            }
        }
    }

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val pixeldrainExtractor by lazy { PixelDrainExtractor() }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val unsBioExtractor by lazy { UnsBioExtractor(client, headers) }

    private suspend fun serverVideoResolver(url: String): List<Video> = when {
        arrayOf("ok.ru", "okru").any { url.contains(it, true) } -> okruExtractor.videosFromUrl(url)
        arrayOf("filelions", "lion", "fviplions").any {
            url.contains(
                it,
                true,
            )
        } -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })

        arrayOf("wishembed", "streamwish", "strwish", "wish").any {
            url.contains(
                it,
                true,
            )
        } -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })

        url.contains("animeav1.uns.bio", true) -> unsBioExtractor.videosFromUrl(url)

        arrayOf("vidhide", "streamhide", "guccihide", "streamvid").any {
            url.contains(
                it,
                true,
            )
        } -> vidHideExtractor.videosFromUrl(url)

        Uri.parse(url).host?.let { host ->
            host.equals("voe.sx", true) || host.endsWith(".voe.sx", true)
        } == true -> voeExtractor.videosFromUrl(url)

        Uri.parse(url).host?.let { host ->
            host.equals("yourupload.com", true) || host.endsWith(".yourupload.com", true)
        } == true -> yourUploadExtractor.videoFromUrl(
            url,
            headers = headers,
        )

        url.contains("zilla-networks", true) -> {
            if (url.contains("/play/")) {
                val base = url.substringBefore("/play/")
                val id = url.substringAfter("/play/").substringBefore("?")
                val m3u8Url = "$base/m3u8/$id"
                val headers = headers.newBuilder()
                    .set("Referer", "$base/")
                    .build()
                listOf(Video(m3u8Url, "Zilla-Networks", m3u8Url, headers = headers))
            } else {
                vidGuardExtractor.videosFromUrl(url)
            }
        }

        arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any {
            url.contains(
                it,
                true,
            )
        } -> vidGuardExtractor.videosFromUrl(url)

        arrayOf("mp4upload.com").any { url.contains(it, true) } -> mp4uploadExtractor.videosFromUrl(url, headers)
        arrayOf("pixeldrain.com").any { url.contains(it, true) } -> pixeldrainExtractor.videosFromUrl(url)
        else -> universalExtractor.videosFromUrl(url, headers)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
    }
}
