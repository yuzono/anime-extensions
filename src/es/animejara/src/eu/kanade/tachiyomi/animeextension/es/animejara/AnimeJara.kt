package eu.kanade.tachiyomi.animeextension.es.animejara

import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class AnimeJara :
    AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    // ============================== Catalog ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = getPage("$baseUrl/catalogo/?paged=$page")

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPage("$baseUrl/catalogo/?estado=Emision&paged=$page")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/catalogo/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            AnimeJaraFilters.params(filters).forEach { (name, value) -> addQueryParameter(name, value) }
            if (page > 1) addQueryParameter("paged", page.toString())
        }.build()
        return getPage(url.toString())
    }

    override fun getFilterList(): AnimeFilterList = AnimeJaraFilters.getFilterList()

    private suspend fun getPage(url: String): AnimesPage {
        val document = getDocument(url)
        val animes = document.select(CARD_SELECTOR).map { card ->
            SAnime.create().apply {
                setUrlWithoutDomain(card.absUrl("href"))
                val data = card.attr("data-anime").takeIf { it.isNotEmpty() }?.parseAs<CardDto>()
                title = data?.titulo ?: card.selectFirst("img")!!.attr("alt")
                thumbnail_url = data?.poster ?: card.selectFirst("img")?.absUrl("src")
            }
        }
        return AnimesPage(animes, document.selectFirst(NEXT_PAGE_SELECTOR) != null)
    }

    // The site answers HTTP 404 on catalog page 2+ and on episode pages while still sending the full page
    private suspend fun getDocument(url: String): Document = client.get(url, headers, ensureSuccess = false).use { response ->
        if (!response.isSuccessful && response.code != 404) throw IOException("HTTP error ${response.code}")
        response.asJsoup()
    }

    // =========================== Details/Episodes =========================

    // Details and episodes share the same page, so it is fetched only once
    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime = anime, episodes = episodes)

        val document = getDocument("$baseUrl${anime.url}")
        // Episodes first: a malformed script must not leave details half-applied
        val newEpisodes = if (fetchEpisodes) parseEpisodes(document, anime.url) else episodes
        return SAnimeUpdate(
            anime = if (fetchDetails) parseDetails(document, anime) else anime,
            episodes = newEpisodes,
        )
    }

    private fun parseDetails(document: Document, anime: SAnime): SAnime = SAnime.create().apply {
        url = anime.url
        title = document.selectFirst(".anime-title-desktop, h1")!!.text()
        thumbnail_url = document.selectFirst("img.main-poster-img")?.absUrl("src")?.takeIf { it.isNotEmpty() } ?: anime.thumbnail_url
        genre = document.select(".anime-info .anime-categorias span").joinToString { it.text() }
            .takeIf { it.isNotEmpty() } ?: anime.genre
        description = document.selectFirst(".anime-info .anime-sinopsis-contenedor")?.wholeText()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: anime.description
        initialized = true
    }

    private fun parseEpisodes(document: Document, animeUrl: String): List<SEpisode> {
        if (animeUrl.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    url = animeUrl
                    name = "Película"
                    episode_number = 1F
                },
            )
        }

        val slug = animeUrl.trim('/').substringAfterLast('/')
        val script = document.select("script").map { it.data() }.firstOrNull { "TEMPORADAS_DATA" in it }
        val seasons = script?.jsonArrayAfter("TEMPORADAS_DATA")?.parseAs<List<SeasonDto>>()
            ?: throw IOException("Could not find the episode list. Check if there are any in WebView.")

        return seasons.flatMap { season ->
            season.episodes.map { episode ->
                SEpisode.create().apply {
                    // Same "Season N x E - title" format as DooPlay sources, so apps can group by season
                    url = "/episode/$slug-${season.number}x${episode.number}/"
                    name = "Season ${season.number} x ${episode.number} - ${episode.title?.ifBlank { null } ?: "Episodio ${episode.number}"}"
                    episode_number = episode.number.toFloatOrNull() ?: 0F
                    date_upload = dateFormat.tryParse(episode.updatedAt)
                    scanlator = episode.languages.joinToString().ifEmpty { null }
                }
            }
        }.reversed()
    }

    // ============================ Hosters/Videos ==========================

    // One Hoster per server named "SUB <server>" / "DUB <server>", so apps can list the servers and switch SUB/DUB
    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val document = getDocument("$baseUrl${episode.url}")
        val languages = document.select(".boton-idioma .lang-name").map { it.text().uppercase(Locale.ROOT) }
        val dubLanguage = preferences.getString(PREF_DUB_KEY, PREF_DUB_DEFAULT)!!

        val embeds = document.select("script").map { it.data() }.firstOrNull { "const enlaces" in it }
            ?.jsonArrayAfter("const enlaces")?.parseAs<List<String>>()
            ?: listOfNotNull(document.selectFirst("iframe[id^=iframe-video]")?.absUrl("src"))

        return embeds.flatMapIndexed { index, embed ->
            val language = languages.getOrNull(index).orEmpty()
            val type = when {
                language.startsWith("JAP") -> "SUB"
                language == dubLanguage -> "DUB"
                else -> return@flatMapIndexed emptyList() // the other dub language, chosen in the settings
            }
            serversFromEmbed(embed).map { (server, url) ->
                Hoster(hosterUrl = url, hosterName = "$type $server", internalData = "$language|$embed")
            }
        }.sortHosters()
    }

    private suspend fun serversFromEmbed(embedUrl: String): List<Pair<String, String>> {
        val embed = embedUrl.toHttpUrlOrNull()?.takeIf { it.isPlayerHost() } ?: return emptyList()
        val body = try {
            client.get(embed, headers.newBuilder().set("Referer", "$baseUrl/").build()).use { it.body.string() }
        } catch (e: IOException) {
            Log.w(TAG, "Embed $embedUrl failed: ${e.message}")
            return emptyList()
        }
        return PLAY_VIDEO_REGEX.findAll(body)
            .map { it.groupValues[1] }
            // hgcloud/hglink only redirect via JS; their mirrors serve the player directly
            .map { it.replace("hgcloud.to", "hanerix.com").replace("hglink.to", "hanerix.com") }
            .distinct()
            .mapNotNull { url ->
                when {
                    "streamtape" in url -> SERVER_STREAMTAPE to url
                    "hanerix" in url -> SERVER_STREAMHG to url
                    "filelions" in url -> SERVER_FILELIONS to url
                    "vidhide" in url -> SERVER_VIDHIDE to url
                    url.toHttpUrlOrNull()?.takeIf { it.isPlayerHost() && it.host.startsWith("nyuu.") } != null -> SERVER_NYUU to url
                    else -> null
                }
            }.toList()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        return sortedByDescending { it.hosterName.startsWith(type) }
    }

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val (language, embedUrl) = hoster.internalData.split("|", limit = 2)
        val server = hoster.hosterName.substringAfter(' ')
        val url = hoster.hosterUrl
        val playerHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        return when (server) {
            SERVER_STREAMTAPE -> listOfNotNull(streamTapeExtractor.videoFromUrl(url, "$language - Streamtape"))
            SERVER_FILELIONS -> streamWishExtractor.videosFromUrl(url) { quality -> "$language - FileLions: $quality" }
            SERVER_NYUU -> listOfNotNull(nyuuVideo(url, language))
            else -> VidHideExtractor(client, playerHeaders).videosFromUrl(url) { quality -> "$language - $server: $quality" }
        }.distinctBy { it.videoUrl }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { RESOLUTION_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // The player hosts, the only ones whose pages are fetched
    private fun HttpUrl.isPlayerHost() = scheme == "https" && host.endsWith("streamhj.top")

    // Nyuu: go.php -> ody_go.php opens a PHP session holding the video, then the DPlayer "url" redirects to a signed mp4
    private suspend fun nyuuVideo(goUrl: String, language: String): Video? {
        // Own cookie jar per call: the session holds one video, so parallel hosters must not share it
        val jar = object : CookieJar {
            private val stored = mutableListOf<Cookie>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                stored.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                stored.addAll(cookies)
            }

            override fun loadForRequest(url: HttpUrl) = stored.filter { it.matches(url) }
        }
        val nyuuClient = client.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()

        val playerUrl = goUrl.replace("/go.php", "/ody_go.php").toHttpUrl()
        val page = nyuuClient.get(playerUrl, headers.newBuilder().set("Referer", goUrl).build()).use { it.body.string() }
        val videoPath = NYUU_VIDEO_REGEX.find(page)?.groupValues?.get(1) ?: return null

        val referer = headers.newBuilder().set("Referer", playerUrl.toString()).build()
        var next = playerUrl.resolve(videoPath) ?: return null
        repeat(MAX_REDIRECTS) {
            if (!next.isPlayerHost()) {
                return Video(videoUrl = next.toString(), videoTitle = "$language - Nyuu")
            }
            val location = nyuuClient.get(next, referer, ensureSuccess = false).use { it.header("Location") } ?: return null
            next = next.resolve(location) ?: return null
        }
        return null
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferir SUB o DUB",
            entries = TYPE_LIST,
            entryValues = TYPE_LIST,
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_DUB_KEY,
            title = "Idioma del doblaje (DUB)",
            entries = DUB_LIST,
            entryValues = DUB_LIST,
            default = PREF_DUB_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Calidad preferida",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val TAG = "AnimeJara"

        private const val CARD_SELECTOR = "div.anime-card-wrapper a.anime-card"
        private const val NEXT_PAGE_SELECTOR = "#paginacion-container a:contains(»)"

        private const val SERVER_STREAMTAPE = "Streamtape"
        private const val SERVER_STREAMHG = "StreamHG"
        private const val SERVER_FILELIONS = "FileLions"
        private const val SERVER_VIDHIDE = "VidHide"
        private const val SERVER_NYUU = "Nyuu"

        private const val MAX_REDIRECTS = 10

        private val PLAY_VIDEO_REGEX = Regex("""playVideo\((?:&quot;|")(.*?)(?:&quot;|")\)""")
        private val NYUU_VIDEO_REGEX = Regex("""video:\s*\{\s*url:\s*'([^']+)'""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private val TYPE_LIST = listOf("SUB", "DUB")

        private const val PREF_DUB_KEY = "dub_lang"
        private const val PREF_DUB_DEFAULT = "LATINO"
        private val DUB_LIST = listOf("LATINO", "CASTELLANO")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")
    }
}
