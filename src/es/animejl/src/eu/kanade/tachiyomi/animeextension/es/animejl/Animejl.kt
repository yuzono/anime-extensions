package eu.kanade.tachiyomi.animeextension.es.animejl

import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.addListPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
@OptIn(keiyoushi.annotation.AnimiruOnly::class)
abstract class Animejl :
    AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = client.get("$baseUrl/animes?order=rating&page=$page", headers)
        .use { it.asJsoup().toAnimesPage() }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = client.get("$baseUrl/animes?order=updated&page=$page", headers)
        .use { it.asJsoup().toAnimesPage() }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = Filters.getQueryParameters(filters)
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("animes")

        when {
            query.isNotBlank() -> url.addQueryParameter("q", query)
            params.isNotEmpty() -> params.forEach { (name, value) -> url.addQueryParameter(name, value) }
            else -> url.addQueryParameter("order", "rating")
        }

        return client.get(url.addQueryParameter("page", page.toString()).build(), headers)
            .use { it.asJsoup().toAnimesPage() }
    }

    // =========================== Anime Details ============================

    // Details and episodes share the same page, so it is fetched only once
    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime = anime, episodes = episodes)

        val document = client.get("$baseUrl${anime.url}", headers).use { it.asJsoup() }
        return SAnimeUpdate(
            anime = if (fetchDetails) parseDetails(document, anime) else anime,
            episodes = if (fetchEpisodes) parseEpisodes(document, anime) else episodes,
        )
    }

    private fun parseDetails(document: Document, anime: SAnime): SAnime = anime.apply {
        title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")?.absUrl("src")
        description = document.selectFirst("div.Description")?.text()?.removeSurrounding("\"")
        genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        status = parseStatus(document.select("span.fa-tv").text())
        initialized = true
    }

    // ============================== Episodes ==============================

    // anime.url already carries the canonical id and slug
    private fun parseEpisodes(document: Document, anime: SAnime): List<SEpisode> {
        val script = document.selectFirst("script:containsData(var episodes =)")?.data()
            ?: return emptyList()
        val episodes = script.jsArray("episodes") ?: return emptyList()
        val animePath = anime.url.trimEnd('/')

        return episodes.map { entry ->
            val number = entry[0]!!.string
            SEpisode.create().apply {
                url = "$animePath/episodio-$number"
                name = "Episodio $number"
                episode_number = number.toFloatOrNull() ?: 0F
                date_upload = dateFormat.tryParse(entry[1]?.stringOrNull)
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================== Hosters ===============================

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get("$baseUrl${episode.url}", headers).use { it.asJsoup() }

        return document.select("script").asSequence()
            .flatMap { VIDEO_REGEX.findAll(it.data()) }
            .mapNotNull { match -> embedUrl(match.groupValues[2].trim()) }
            .distinct()
            .map { url -> Hoster(hosterUrl = url, hosterName = serverLabel(url)) }
            .toList()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedByDescending { it.hosterName == server }
    }

    // =============================== Videos ===============================

    // Dispatch by url so hosterName stays purely cosmetic
    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return when (serverLabel(url)) {
            "FileMoon" -> filemoonExtractor.videosFromUrl(url, headers = headers)
            "VidHide" -> vidHideExtractor.videosFromUrl(url)
            "BurstCloud" -> burstCloudExtractor.videoFromUrl(url, headers)
            "StreamWish" -> streamWishExtractor.videosFromUrl(url)
            "StreamTape" -> listOfNotNull(streamTapeExtractor.videoFromUrl(url))
            "Okru" -> okruExtractor.videosFromUrl(url)
            "YourUpload" -> yourUploadExtractor.videoFromUrl(url, headers)
            "Voe" -> voeExtractor.videosFromUrl(url)
            "Uqload" -> uqloadExtractor.videosFromUrl(url)
            "Mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
            else -> universalExtractor.videosFromUrl(url, headers)
        }.distinctBy { it.videoUrl }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { RESOLUTION_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // =============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ============================== Utilities =============================

    private fun Document.toAnimesPage(): AnimesPage = AnimesPage(
        select(ANIME_SELECTOR).map(::animeFromElement),
        selectFirst(NEXT_PAGE_SELECTOR) != null,
    )

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.Description a.Button")!!.absUrl("href"))
        title = element.selectFirst("a h3")!!.text()
        thumbnail_url = element.selectFirst("a div.Image figure img")?.absUrl("src")
        description = element.selectFirst("div.Description p:eq(2)")?.text()?.removeSurrounding("\"")
    }

    private fun parseStatus(statusString: String): Int {
        val status = statusString.lowercase()
        return when {
            "emis" in status -> SAnime.ONGOING
            "estreno" in status -> SAnime.ONGOING
            "finalizado" in status -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun embedUrl(raw: String): String? {
        // Each server is wrapped in an <iframe src="…">
        val url = if (raw.contains("<iframe", ignoreCase = true)) {
            Jsoup.parseBodyFragment(raw, baseUrl).selectFirst("iframe")?.absUrl("src")
        } else {
            raw
        }
        return when {
            url.isNullOrEmpty() -> null
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    // The arrays mix numbers and strings, so they are read with the JsonElement accessors
    private fun String.jsArray(variable: String): JsonArray? {
        val raw = substringAfter("var $variable = [", "")
            .substringBefore("];", "")
            .replace(TRAILING_COMMA_REGEX, "$1")
            .trim()
            .removeSuffix(",")
        return if (raw.isEmpty()) null else "[$raw]".parseAs<JsonArray>()
    }

    private fun serverLabel(url: String): String = when {
        "byse" in url || "filemoon" in url -> "FileMoon"
        "vidhide" in url || "streamhide" in url || "dhtpre" in url ||
            "morencius" in url || "hgcloud" in url -> "VidHide"
        "burstcloud" in url -> "BurstCloud"
        "streamtape" in url -> "StreamTape"
        "ok.ru" in url -> "Okru"
        "yourupload" in url -> "YourUpload"
        "streamwish" in url || "playerwish" in url -> "StreamWish"
        "voe" in url -> "Voe"
        "uqload" in url -> "Uqload"
        "mp4upload" in url -> "Mp4upload"
        else -> url.toHttpUrlOrNull()?.host ?: url
    }

    companion object {
        private const val ANIME_SELECTOR = "div.Container ul.ListAnimes li article"
        private const val NEXT_PAGE_SELECTOR = """ul.pagination li a[rel="next"]"""

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "FileMoon"
        private val SERVER_LIST = listOf(
            "FileMoon", "VidHide", "Voe", "YourUpload", "Mp4upload",
            "Okru", "BurstCloud", "StreamWish", "StreamTape", "Uqload",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")

        // video[0] = '<iframe src="…" …></iframe>'
        private val VIDEO_REGEX = Regex("""video\[?\d+\]?\s*=\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")
        private val TRAILING_COMMA_REGEX = Regex(""",\s*([\]}])""")
    }
}
