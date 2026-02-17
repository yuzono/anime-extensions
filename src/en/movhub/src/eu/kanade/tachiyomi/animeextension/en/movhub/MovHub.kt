package eu.kanade.tachiyomi.animeextension.en.movhub

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapNotNull
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

class MovHub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MovHub"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val preferences = getPreferences {
        clearOldPrefs()
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val apiClient by lazy {
        client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
                    )
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private fun headersBuilder(baseUrl: String = this.baseUrl): Headers.Builder = headers.newBuilder()
        .set("Referer", "$baseUrl/")

    private var docHeaders by LazyMutable {
        headersBuilder().build()
    }

    private var rapidShareExtractor by LazyMutable {
        RapidShareExtractor(client, docHeaders)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?sort=trending&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browser?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .also { builder ->
                MovHubFilters.getFilters(filters).forEach {
                    it.addQueryParameters(builder)
                }
            }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("div.movie-cards div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(poster.attr("href"))
                this.title = title
                thumbnail_url = item.selectFirst("img")?.attr("data-src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("h1.title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("div.poster img")?.attr("src")

        val isMovie = document.selectFirst("ol.breadcrumb li a[href*='/movie']") != null

        status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING

        genre = document.select("ul.mics li:has(a[href*=/genre/]) a").eachText().joinToString()
        author = document.select("ul.mics li:has(a[href*=/production/]) a").eachText().joinToString()

        // fancy score
        val scorePosition = preferences.scorePosition
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(document.selectFirst("div.detail-lower")?.attr("data-score"))
            else -> ""
        }

        description = buildString {
            if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                append(fancyScore)
                append("\n\n")
            }

            document.selectFirst(".description")?.text()?.also { append("$it\n\n") }

            val type = if (isMovie) "Movie" else "TV Show"
            append("**Type:** $type\n")

            fun getInfo(label: String): String? = document.selectFirst("ul.mics li:contains($label:)")
                ?.text()?.substringAfter(":")?.trim()

            getInfo("Country")?.let { append("**Country:** $it\n") }
            getInfo("Released")?.let { append("**Released:** $it\n") }
            getInfo("Casts")?.let { append("**Casts:** $it\n") }

            document.selectFirst(".metadata .IMDb")?.text()?.let {
                val rating = it.substringAfter("IMDb").trim()
                if (rating.isNotEmpty()) append("**IMDb:** $rating")
            }

            document.selectFirst("div.site-movie-bg")?.attr("style")?.let {
                val url = it.substringAfter("url('").substringBefore("')")
                if (url.isNotEmpty()) append("\n\n![Cover]($url)")
            }

            if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(fancyScore)
            }
        }
    }

    private fun getFancyScore(score: String?): String {
        val trimmedScore = score?.trim() ?: return ""
        if (trimmedScore.isEmpty()) return ""

        return try {
            val scoreBig = BigDecimal(trimmedScore)
            if (scoreBig.compareTo(BigDecimal.ZERO) == 0) return ""

            val stars = scoreBig.divide(BigDecimal(2))
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 5)

            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("★".repeat(stars))
                if (stars < 5) append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: Exception) {
            ""
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = MovHubFilters.FILTER_LIST

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = baseUrl + anime.url
        val document = client.newCall(GET(animeUrl, docHeaders)).awaitSuccess().use { it.asJsoup() }

        val contentId = document.selectFirst("#movie-rating[data-id]")?.attr("data-id")
            ?: return emptyList()

        val encryptedId = encrypt(contentId)
        val ajaxUrl = "$baseUrl/ajax/episodes/list?id=$contentId&_=$encryptedId"

        val resultDoc = client.newCall(GET(ajaxUrl, apiHeaders(animeUrl)))
            .awaitSuccess().use {
                it.parseAs<ResultResponse>(json = json).toDocument()
            }

        return resultDoc.select("ul.episodes[data-season]").flatMap { seasonElement ->
            val seasonNum = seasonElement.attr("data-season")

            seasonElement.select("li a").map { element ->
                if (element.selectFirst("span.num") != null) {
                    tvEpisodeFromElement(element, anime.url, seasonNum)
                } else {
                    movieEpisodeFromElement(element, anime.url)
                }
            }
        }.reversed()
            .ifEmpty {
                throw Exception("No episodes/movie found.")
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    private fun tvEpisodeFromElement(element: Element, animeUrl: String, seasonNum: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("num")
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "S$seasonNum E$epNum: ${element.selectFirst("span:not(.num)")?.text()?.trim()}"
        date_upload = parseDate(element.attr("title"))
    }

    private fun movieEpisodeFromElement(element: Element, animeUrl: String): SEpisode = SEpisode.create().apply {
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = 1F
        name = element.selectFirst("span")?.text()?.trim() ?: "Movie"
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (animeUrl, episodeId) = episode.url.split('#', limit = 2)
        val referer = baseUrl + animeUrl

        val encryptedId = encrypt(episodeId)
        val serversUrl = "$baseUrl/ajax/links/list?eid=$episodeId&_=$encryptedId"

        val serversDoc = client.newCall(GET(serversUrl, apiHeaders(referer)))
            .awaitSuccess().use {
                it.parseAs<ResultResponse>(json = json).toDocument()
            }
        return serversDoc.select("div.server").parallelMapNotNull { serverElement ->
            val serverName = serverElement.selectFirst("span")?.text()
                ?: return@parallelMapNotNull null

            if (serverName !in preferences.hosterPref) return@parallelMapNotNull null

            runCatching {
                val serverId = serverElement.attr("data-lid")
                val encryptedServerId = encrypt(serverId)
                val viewUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$encryptedServerId"

                val encryptedIframeResult = client.newCall(GET(viewUrl, apiHeaders(referer)))
                    .awaitSuccess().use {
                        it.parseAs<ResultResponse>(json = json).result
                    }

                val iframeUrl = decrypt(encryptedIframeResult)
                rapidShareExtractor.videosFromUrl(iframeUrl, serverName, preferences.subLangPref)
            }.getOrNull()
        }.flatten()
    }

    // ============================= Utilities ==============================

    private fun apiHeaders(referer: String) = docHeaders.newBuilder()
        .set("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun encrypt(text: String): String = apiClient.newCall(GET("https://enc-dec.app/api/enc-movies-flix?text=$text"))
        .awaitSuccess().use {
            it.parseAs<ResultResponse>(json = json).result
        }

    private suspend fun decrypt(text: String): String = apiClient.newCall(GET("https://enc-dec.app/api/dec-movies-flix?text=$text"))
        .awaitSuccess().use {
            it.parseAs<DecryptedIframeResponse>(json = json).result.url
        }

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val qualities = QUALITIES.reversed()

        return sortedWith(
            compareByDescending<Video> {
                it.quality.contains(quality, true) && it.quality.startsWith(server, true)
            }
                .thenByDescending { it.quality.contains(quality, true) }
                .thenByDescending { it.quality.startsWith(server, true) }
                .thenByDescending { video -> qualities.indexOfFirst { video.quality.contains(it) } },
        )
    }

    // ============================== Preferences ==============================

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.hosterPref by preferences.delegate(PREF_HOSTER_KEY, SERVERS.toSet())
    private val SharedPreferences.scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            ?: return this
        if (domain !in DOMAIN_VALUES) {
            edit()
                .putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
                .apply()
        }
        val hostToggle = getStringSet(PREF_HOSTER_KEY, SERVERS.toSet()) ?: return this
        if (hostToggle.any { it !in SERVERS }) {
            edit()
                .putStringSet(PREF_HOSTER_KEY, SERVERS.toSet())
                .putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                .apply()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            docHeaders = headersBuilder(it).build()
            rapidShareExtractor = RapidShareExtractor(client, docHeaders)
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITIES,
            entryValues = QUALITIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_LANG_KEY,
            title = "Preferred sub language",
            entries = SUB_LANGS,
            entryValues = SUB_LANGS,
            default = PREF_SUB_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVERS,
            entryValues = SERVERS,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            title = "Score display position",
            entries = PREF_SCORE_POSITION_ENTRIES,
            entryValues = PREF_SCORE_POSITION_VALUES,
            default = PREF_SCORE_POSITION_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/disable servers",
            entries = SERVERS,
            entryValues = SERVERS,
            default = SERVERS.toSet(),
            summary = "Select which video server to show in the episode list",
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private val DOMAIN_ENTRIES = listOf(
            "movhub.ws",
            "1movies.bz",
            "123moviesfree.bz",
            "bflix.la",
            "flixtor.mov",
            "hurawatch.la",
            "myflixer.fi",
            "sflix.fi",
            "soap2day.fi",
            "solarmovie.fi",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        const val PREF_QUALITY_KEY = "pref_quality_key"
        private val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = QUALITIES.first()

        const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        private val SUB_LANGS = listOf(
            "English",
            "Arabic",
            "Chinese",
            "French",
            "German",
            "Indonesian",
            "Italian",
            "Japanese",
            "Korean",
            "Persian",
            "Portuguese",
            "Russian",
            "Spanish",
            "Turkish",
            "Urdu",
            "Vietnamese",
        )
        internal val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        const val PREF_SERVER_KEY = "pref_server_key"
        private val SERVERS = listOf("Server 1", "Server 2")
        private val PREF_SERVER_DEFAULT = SERVERS.first()

        const val PREF_HOSTER_KEY = "pref_hoster_key"

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        private const val SCORE_POS_TOP = "top"
        private const val SCORE_POS_BOTTOM = "bottom"
        private const val SCORE_POS_NONE = "none"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = listOf(
            "Top of description",
            "Bottom of description",
            "Don't show",
        )
        private val PREF_SCORE_POSITION_VALUES = listOf(
            SCORE_POS_TOP,
            SCORE_POS_BOTTOM,
            SCORE_POS_NONE,
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
