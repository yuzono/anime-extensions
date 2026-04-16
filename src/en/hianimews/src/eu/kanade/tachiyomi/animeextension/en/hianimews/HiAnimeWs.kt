package eu.kanade.tachiyomi.animeextension.en.hianimews

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.CountriesFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.LanguagesFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.RatingFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.SeasonsFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.SortByFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.TypesFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.YearsFilter
import eu.kanade.tachiyomi.animeextension.en.hianimews.HiAnimeWsFilters.getFirstOrNull
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class HiAnimeWs :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HiAnimeWs"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")

    private var docHeaders by LazyMutable {
        headersBuilder().build()
    }

    override var client by LazyMutable {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = RATE_LIMIT, period = 1.seconds)
            .build()
    }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", docHeaders, cacheControl)

    override fun popularAnimeSelector() = "div.flw-item"

    override fun popularAnimeFromElement(element: Element): SAnime = element.toSAnime()

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", docHeaders, cacheControl)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browser")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())

            if (filters.isNotEmpty()) {
                filters.getFirstOrNull<TypesFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<GenresFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<StatusFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<SortByFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<SeasonsFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<YearsFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<RatingFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<CountriesFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<LanguagesFilter>()?.addQueryParameters(this)
            }
        }.build().toString()

        return GET(url, docHeaders, cacheControl)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun relatedAnimeListSelector() = "div.flw-item"

    override fun relatedAnimeFromElement(element: Element): SAnime = element.toSAnime()

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons div.season div.aitem div.inner").mapNotNull { it.toSeasonSAnime() }
        val related = document.select(relatedAnimeListSelector()).map { relatedAnimeFromElement(it) }
        return seasons + related
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = HiAnimeWsFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anisc-poster img.film-poster-img")?.attr("src") ?: ""

        val scorePosition = preferences.scorePosition
        val ratingText = document.selectFirst("div.rr-mark strong")?.ownText()?.trim()
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(ratingText)
            else -> ""
        }

        document.selectFirst("div.anisc-detail")?.let { info: Element ->
            title = info.selectFirst("h2.film-name a.dynamic-name")?.getTitle() ?: ""

            val producers = info.select("div.film-text a[href*=/producers/]").eachText().joinToString()
            author = producers.ifBlank { null }

            description = buildString {
                if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                    append(fancyScore)
                    append("\n\n")
                }

                info.selectFirst("div.film-description div.text")?.text()?.let { append(it + "\n") }

                if (producers.isNotBlank()) {
                    append("\n**Producers:** $producers")
                }

                document.getCover()?.let { append("\n\n![Cover]($it)") }

                if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(fancyScore)
                }
            }
        }
    }

    private fun getFancyScore(score: String?): String {
        if (score.isNullOrBlank()) return ""

        return try {
            val scoreBig = score.toBigDecimalOrNull() ?: return ""
            if (scoreBig.signum() <= 0) return ""

            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 5)

            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("★".repeat(stars))
                append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (e: ArithmeticException) {
            // Catch division errors if they occur
            ""
        } catch (e: NumberFormatException) {
            // Catch formatting errors
            ""
        }
    }

    private val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    private val coverSelector by lazy { "div.anis-cover" }

    private fun Document.getCover(): String? = selectFirst(coverSelector)?.getBackgroundImage()

    private fun Element.getBackgroundImage(): String? {
        val style = attr("style")
        return coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.eplist a"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = client.newCall(animeDetailsRequest(anime))
            .awaitSuccess().use {
                val document = it.asJsoup()
                document.selectFirst("div[data-id]")?.attr("data-id")
                    ?: throw IllegalStateException("Anime ID not found")
            }

        val enc = encDecEndpoints(animeId)
            .parseAs<ResultResponse>().result

        val chapterListRequest = GET("$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$enc", docHeaders)
        val document = client.newCall(chapterListRequest)
            .awaitSuccess().use { it.parseAs<ResultResponse>().toDocument() }

        val episodeElements = document.select(episodeListSelector())
        return episodeElements.mapNotNull {
            runCatching {
                episodeFromElement(it)
            }.getOrNull()
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val token = element.attr("token").ifEmpty {
            throw IllegalStateException("Token not found")
        }
        val epNum = element.attr("num")
        val subdubType = element.attr("langs").toIntOrNull() ?: 0
        val subdub = when (subdubType) {
            1 -> "Sub"
            3 -> "Dub & Sub"
            else -> ""
        }

        val namePrefix = "Episode $epNum"
        val name = element.selectFirst("span")?.text()
            ?.takeIf { it.isNotBlank() && it != namePrefix }
            ?.let { ": $it" }
            .orEmpty()

        return SEpisode.create().apply {
            this.name = namePrefix + name
            this.url = token
            episode_number = epNum.toFloat()
            scanlator = subdub
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val token = episode.url
        val enc = encDecEndpoints(token)
            .parseAs<ResultResponse>().result

        val typeSelection = preferences.typeToggle
        val hosterSelection = preferences.hostToggle

        val servers = client.newCall(GET("$baseUrl/ajax/links/list?token=$token&_=$enc", docHeaders))
            .awaitSuccess().use { response ->
                val document = response.parseAs<ResultResponse>().toDocument()

                document.select("div.ps_-block").flatMap { typeBlock ->
                    val typeClass = typeBlock.classNames().find { it.startsWith("ps_-block-") }
                    val type = typeClass?.removePrefix("ps_-block-") ?: return@flatMap emptyList()

                    if (type !in typeSelection) return@flatMap emptyList()

                    typeBlock.select("a.server[data-lid]")
                        .mapNotNull { serverElm ->
                            val serverId = serverElm.attr("data-lid")
                            val serverName = serverElm.text()
                            if (serverName !in hosterSelection) return@mapNotNull null

                            VideoCode(type, serverId, serverName)
                        }
                }
            }

        return servers.parallelMapNotNull { server ->
            runCatching { extractIframe(server) }.getOrNull()
        }.parallelFlatMap { server ->
            runCatching { extractVideo(server) }.getOrElse { emptyList() }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private var megaUpExtractor by LazyMutable { MegaUpExtractor(client, docHeaders) }

    private suspend fun extractIframe(server: VideoCode): VideoData {
        val (type, serverId, serverName) = server

        val enc = encDecEndpoints(serverId)
            .parseAs<ResultResponse>().result

        val encodedLink = client.newCall(GET("$baseUrl/ajax/links/view?id=$serverId&_=$enc", docHeaders))
            .awaitSuccess().parseAs<ResultResponse>().result

        val postBody = buildJsonObject {
            put("text", encodedLink)
        }
        val payload = postBody.toRequestBody()

        val iframe = client.newCall(POST("https://enc-dec.app/api/dec-kai", body = payload))
            .awaitSuccess().use { it.parseAs<IframeResponse>() }
            .result.url

        val typeSuffix = when (type) {
            "sub" -> "Hard Sub"
            "softsub" -> "Soft Sub"
            "dub" -> "Dub & S-Sub"
            else -> type
        }
        val name = "$serverName | [$typeSuffix]"

        return VideoData(iframe, name)
    }

    private suspend fun extractVideo(server: VideoData): List<Video> = megaUpExtractor.videosFromUrl(
        url = server.iframe,
        serverName = server.serverName,
    )

    private suspend fun encDecEndpoints(enc: String): String {
        val url = "https://enc-dec.app/api/enc-kai".toHttpUrl().newBuilder()
            .addQueryParameter("text", enc)
            .build()

        return client.newCall(GET(url, docHeaders)).awaitSuccess().use {
            it.body.string()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.prefQuality
        val server = preferences.prefServer
        val type = preferences.prefType
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(type, true) },
        )
    }

    /**
     * Builds an SAnime from a standard flw-item element.
     */
    private fun Element.toSAnime(): SAnime = SAnime.create().apply {
        selectFirst("a.film-poster-ahref")?.attr("href")?.let {
            setUrlWithoutDomain(it)
        }
        title = selectFirst("a.dynamic-name")?.getTitle() ?: ""
        thumbnail_url = selectFirst("img.film-poster-img")?.attr("data-src") ?: ""
    }

    /**
     * Builds an SAnime from a season item element (different structure than flw-item).
     */
    private fun Element.toSeasonSAnime(): SAnime? = SAnime.create().apply {
        val url = selectFirst("a")?.attr("href") ?: return null
        setUrlWithoutDomain(url)
        thumbnail_url = selectFirst("img")?.attr("src")
        title = select("div.detail span").text()
    }

    private fun Element.getTitle(): String {
        val enTitle = attr("title")
        val jpTitle = attr("data-jp")
        return if (useEnglish) {
            enTitle.ifBlank { text() }
        } else {
            jpTitle.ifBlank { text() }
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"

        private val DOMAIN_ENTRIES = listOf("hianime.ws")
        private val DOMAIN_VALUES = listOf("https://hianime.ws")
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = listOf("Romaji", "English")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES.first()

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = listOf(
            "Server 1",
            "Server 2",
        )

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_DEFAULT = HOSTERS.first()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES_ENTRIES = listOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
        private val TYPES_VALUES = listOf("sub", "softsub", "dub")
        private val DEFAULT_TYPES = TYPES_VALUES.toSet()

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "[Soft Sub]"

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        private const val SCORE_POS_TOP = "top"
        private const val SCORE_POS_BOTTOM = "bottom"
        private const val SCORE_POS_NONE = "none"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = listOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = listOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)

        private const val RATE_LIMIT = 5
    }

    // ============================== Settings ==============================

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!.removePrefix("https://")
        val hostToggle = getStringSet(PREF_HOSTER_KEY, HOSTERS.toSet())!!

        val invalidDomain = domain !in DOMAIN_ENTRIES
        val invalidHosters = hostToggle.any { it !in HOSTERS }

        if (invalidDomain || invalidHosters) {
            edit().also { editor ->
                if (invalidDomain) {
                    editor.putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
                }
                if (invalidHosters) {
                    editor.putStringSet(PREF_HOSTER_KEY, HOSTERS.toSet())
                    editor.putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                }
            }.apply()
        }
        return this
    }

    private var useEnglish by LazyMutable { preferences.getTitleLang == "English" }

    private val SharedPreferences.getTitleLang
        by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)

    private val SharedPreferences.prefQuality
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val SharedPreferences.prefServer
        by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val SharedPreferences.prefType
        by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)

    private val SharedPreferences.hostToggle: Set<String>
        by preferences.delegate(PREF_HOSTER_KEY, HOSTERS.toSet())

    private val SharedPreferences.typeToggle: Set<String>
        by preferences.delegate(PREF_TYPE_TOGGLE_KEY, DEFAULT_TYPES)

    private val SharedPreferences.scorePosition
        by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = headersBuilder().build()
            client = network.client.newBuilder()
                .rateLimitHost(baseUrl.toHttpUrl(), permits = RATE_LIMIT, period = 1.seconds)
                .build()
            megaUpExtractor = MegaUpExtractor(client, docHeaders)
        }

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_LIST,
            entryValues = PREF_TITLE_LANG_LIST,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        ) {
            useEnglish = it == "English"
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = HOSTERS,
            entryValues = HOSTERS,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_ENTRIES,
            default = PREF_TYPE_DEFAULT,
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
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = HOSTERS,
            entryValues = HOSTERS,
            default = HOSTERS.toSet(),
        )

        screen.addSetPreference(
            key = PREF_TYPE_TOGGLE_KEY,
            title = "Enable/Disable Types",
            summary = "Select which video types to show in the episode list.\nDisable the one you don't want to speed up loading.",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_VALUES,
            default = DEFAULT_TYPES,
        )
    }
}
