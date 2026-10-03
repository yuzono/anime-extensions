package eu.kanade.tachiyomi.animeextension.en.animetake

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AnimeTake :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeTake"

    override val baseUrl = "https://animetake.tv"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client

    private val preferences by getPreferencesLazy()

    private val preferredQuality: String get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.lowercase()
    private val preferredServer: String get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!.lowercase()

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.get("$baseUrl/animelist/popular")
        return parseAnimesPage(response.asJsoup())
    }

    private fun parseAnimesPage(document: Document): AnimesPage {
        val animes = document.select(ANIME_LIST_SELECTOR).map(::animeFromElement)
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("div.animeposter a").first()!!.attr("abs:href"))
        thumbnail_url = element.select("div.animeposter img").attr("abs:data-src")
        title = element.select("span.animename").text().ifEmpty { error("Anime title selector returned nothing") }
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val year = Calendar.getInstance().get(Calendar.YEAR)

        val url = "$baseUrl/animelist/".toHttpUrl().newBuilder()
            .addQueryParameter("years[]", year.toString())
            .addQueryParameter("order[]", "datenewold")
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url)
        return parseAnimesPage(response.asJsoup())
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = if (query.isNotEmpty()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.lowercase())
                .addQueryParameter("page", page.toString())
                .build()
            client.get(url)
        } else {
            val params = Filters.getSearchParameters(filters)
            val url = "$baseUrl/animelist/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .apply {
                    params.letters.forEach { addQueryParameter("letters[]", it) }
                    params.genres.forEach {
                        addQueryParameter("genres[]", it.replace('+', ' '))
                    }
                    params.score.forEach { addQueryParameter("score[]", it) }
                    params.years.forEach { addQueryParameter("years[]", it) }
                    params.ratings.forEach { addQueryParameter("ratings[]", it) }
                }
                .build()
            client.get(url)
        }
        return parseAnimesPage(response.asJsoup())
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("div.component-animeinfo h2 b").text()
                .ifEmpty { error("Anime title selector returned nothing") }
            genre = document.select("span.badge-genre").joinToString { it.text() }
            description = document.select("div.component-animeinfo div.d-none.d-sm-block")
                .first()?.text()
            status = parseStatus(
                document.select("table.table-sm tr:contains(Status) td")
                    .last()?.text()?.lowercase()?.contains("ongoing") == true,
            )
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup()
        .select("div#episodes-tab-pane div.episodelist, div#episodes-tab-pane div.speciallist")
        .map(::episodeFromElement)
        .reversed()

    private fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.select("a[href]").first()!!.attr("abs:href"))
        date_upload = DATE_FORMATTER.tryParse(element.select("span.badge.date").text())
        val epName = element.select("span.animename").text()
        val part = element.select("div.animetitle span").text()
        name = if (part.isNotEmpty()) "$epName ($part)" else epName
        episode_number = epName.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // =========================== Hosters & Videos ==========================
    private val fileMoonExtractor by lazy { FilemoonExtractor(client) }

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.get(baseUrl + episode.url)
        val document = response.asJsoup()

        val serverNames = document.select("ul#videotab button[onclick]").associate { btn ->
            btn.attr("onclick").substringBefore("(") to btn.text()
        }

        val scripts = document.select("script").filter {
            val data = it.data()
            "function" in data && "/redirect/" in data
        }

        val hosters = scripts.flatMap { script ->
            FUNCTION_REDIRECT_REGEX.findAll(script.data()).map { match ->
                val funcName = match.groupValues[1]
                val redirectPath = match.groupValues[2]

                Hoster(
                    hosterUrl = "",
                    hosterName = serverNames[funcName] ?: funcName,
                    videoList = null,
                    internalData = redirectPath,
                    lazy = false,
                )
            }
        }

        return hosters
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> = this.sortedByDescending {
        canonicalServerName(it.hosterName).lowercase().contains(preferredServer)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = resolveEmbed(baseUrl + hoster.internalData) ?: return emptyList()
        val server = canonicalServerName(hoster.hosterName)

        return when {
            server == "Filemoon" || listOf("filemoon").any { it in embedUrl } ->
                fileMoonExtractor.videosFromUrl(embedUrl, "Filemoon - ")

            server == "Streamtape" || "streamtape" in embedUrl ->
                streamTapeExtractor.videosFromUrl(embedUrl)

            server == "Vidara" || "vidara" in embedUrl -> videosFromVidara(embedUrl)
            else -> emptyList()
        }
    }

    // Vidara embeds (a JWPlayer wrapper) resolve their real stream client-side via a
    // POST to /api/stream keyed by the embed URL's last path segment ("filecode").
    // There's no third-party extractor for this host, so we replicate that call.
    private suspend fun videosFromVidara(embedUrl: String): List<Video> {
        val embedHttpUrl = embedUrl.toHttpUrl()
        val filecode = embedHttpUrl.pathSegments.last()
        val apiUrl = "${embedHttpUrl.scheme}://${embedHttpUrl.host}/api/stream"

        val embedHeaders = headersBuilder()
            .set("Referer", embedUrl)
            .set("Origin", "${embedHttpUrl.scheme}://${embedHttpUrl.host}")
            .build()

        val response = client.post(apiUrl, embedHeaders, VidaraStreamRequestDto(filecode).toJsonRequestBody())

        val dto = response.parseAs<VidaraStreamDto>()
        return listOf(dto.toVideo(embedHeaders))
    }

    private suspend fun resolveEmbed(redirectUrl: String): String? = runCatching {
        client.get(redirectUrl).use { resp ->
            val finalUrl = resp.request.url.toString()
            when {
                !finalUrl.startsWith(baseUrl) -> finalUrl
                else -> resp.asJsoup().select("iframe").attr("abs:src").ifEmpty { null }
            }
        }
    }.getOrNull()

    // ============================== Filters ===============================
    override fun getFilterList() = Filters.FILTER_LIST

    // ============================= Utilities ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferredQuality

        return this.sortedByDescending { it.videoTitle.lowercase().contains(quality) }
    }

    private fun parseStatus(statusBool: Boolean): Int = if (statusBool) {
        SAnime.ONGOING
    } else {
        SAnime.COMPLETED
    }

    private fun canonicalServerName(label: String): String = when {
        label.startsWith("VID", ignoreCase = true) -> "Vidara"
        label.equals("FL", ignoreCase = true) -> "Filemoon"
        label.equals("ST", ignoreCase = true) -> "Streamtape"
        else -> label
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd LLLL yyyy", Locale.ENGLISH)
        }

        private const val ANIME_LIST_SELECTOR = "div.card.component-animelist"
        private const val NEXT_PAGE_SELECTOR = "ul.pagination > li.page-item:last-child:not(.disabled)"

        // Multiple server functions (vida(), fl(), ...) can live in a single inline <script>
        // block, so pair each function's block with the iframe src it assigns.
        private val FUNCTION_REDIRECT_REGEX = Regex(
            """function\s+(\w+)\s*\(\)\s*\{[^}]*?<iframe[^>]*src="([^"]+)"""",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server_v1"

        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "Vidara"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Vidara",
            "Filemoon",
            "Streamtape",
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }
}
