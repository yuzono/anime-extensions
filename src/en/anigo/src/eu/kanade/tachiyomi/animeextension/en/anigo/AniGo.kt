package eu.kanade.tachiyomi.animeextension.en.anigo

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.CountriesFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.LanguagesFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.RatingFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.SeasonsFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.SortByFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.TypesFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.YearsFilter
import eu.kanade.tachiyomi.animeextension.en.anigo.AniGoFilters.getFirstOrNull
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class AniGo :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniGo"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", "$baseUrl/")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")

    private var docHeaders: Headers by LazyMutable {
        headersBuilder().build()
    }

    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .removeAll("Sec-Fetch-User")
        .removeAll("Upgrade-Insecure-Requests")
        .build()

    override var client: OkHttpClient by LazyMutable {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = RATE_LIMIT, period = 1.seconds)
            .build()
    }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", docHeaders, cacheControl)

    override fun popularAnimeSelector() = "div.aniCard.medium div.unit"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val href = element.selectFirst("a.poster")?.attr("href")
            ?.takeIf { it.startsWith("/watch/") }
            ?: throw Exception("Invalid anime URL found in element")

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = element.selectFirst("h6.title")?.getTitle() ?: ""
            thumbnail_url = element.selectFirst("a.poster img")?.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination a[rel=next]"

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

    override fun relatedAnimeListSelector() = "div.aniCard.mini .unit"

    override fun relatedAnimeFromElement(element: Element): SAnime {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
        val href = linkEl?.attr("href")
            ?.takeIf { it.startsWith("/watch/") }
            ?: throw Exception("Invalid related anime URL found in element")

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = element.selectFirst("h6.title")?.getTitle() ?: ""
            thumbnail_url = element.selectFirst("div.poster img")?.attr("src") ?: element.selectFirst("img")?.attr("src")
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons div.season div.aitem div.inner").mapNotNull { season ->
            SAnime.create().apply {
                val url = season.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                thumbnail_url = season.selectFirst("img")?.attr("src")
                title = season.select("div.detail span").text()
            }
        }

        val related = document.select(relatedAnimeListSelector()).map { relatedAnimeFromElement(it) }
        return seasons + related
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniGoFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.select(".poster img").attr("src")

        val scorePosition = preferences.scorePosition
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(
                document.selectFirst("div.rate-box span")?.text(),
            )
            else -> ""
        }

        document.selectFirst("div#main-entity")?.let { info ->
            val titleEl = info.selectFirst("div.title")
            title = titleEl?.getTitle() ?: ""

            val altTitles = info.selectFirst("small.subTitle")?.text()?.split(";").orEmpty()
                .asSequence()
                .map { it.trim() }
                .filterNot { it.isBlank() }
                .distinctBy { it.lowercase() }
                .filterNot { it.equals(title, ignoreCase = true) }
                .joinToString("; ")

            val rating = info.selectFirst(".rating")?.text().orEmpty()

            info.selectFirst("div.detail")?.let { detail ->
                author = detail.getInfo("Studios:", isList = true)?.takeIf { it.isNotEmpty() }
                    ?: detail.getInfo("Producers:", isList = true)?.takeIf { it.isNotEmpty() }
                status = detail.getInfo("Status:")?.run(::parseStatus) ?: SAnime.UNKNOWN

                description = buildString {
                    if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                        append(fancyScore)
                        append("\n\n")
                    }

                    info.selectFirst(".desc")?.text()?.let { append(it + "\n") }
                    detail.getInfo("Country:", full = true)?.run(::append)
                    detail.getInfo("Premiered:", full = true)?.run(::append)
                    detail.getInfo("Date aired:", full = true)?.run(::append)
                    detail.getInfo("Broadcast:", full = true)?.run(::append)
                    detail.getInfo("Duration:", full = true)?.run(::append)
                    if (rating.isNotBlank()) append("\n**Rating:** $rating")
                    detail.select("div:containsOwn(Links:) a").forEach {
                        append("\n[${it.text()}](${it.attr("href")})")
                    }
                    if (altTitles.isNotBlank()) {
                        append("\n**Alternative Title:** $altTitles")
                    }
                    document.getCover()?.let { append("\n\n![Cover]($it)") }

                    if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(fancyScore)
                    }
                }
            }

            genre = info.select("div.genre a").eachText().joinToString()
        } ?: throw IllegalStateException("Invalid anime details page format")
    }

    private fun getFancyScore(score: String?): String {
        return try {
            val scoreDouble = score?.toDoubleOrNull() ?: return ""
            if (scoreDouble == 0.0) return ""

            val scoreBig = BigDecimal(score)
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

    private fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false,
    ): String? {
        if (isList) {
            return select("div:containsOwn($tag) a").eachText().joinToString()
        }
        val value = selectFirst("div:containsOwn($tag)")
            ?.text()?.removePrefix(tag)?.trim()
        return if (full && value != null) "\n**$tag** $value" else value
    }

    private val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    private val coverSelector by lazy { "div.playerBG" }

    private fun Document.getCover(): String? = selectFirst(coverSelector)?.getBackgroundImage()

    private fun Element.getBackgroundImage(): String? {
        val style = attr("style")
        return coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url}"

        val animeId = client.newCall(animeDetailsRequest(anime))
            .awaitSuccess().use {
                val document = it.asJsoup()
                val xData = document.selectFirst("div.playZone")?.attr("x-data")
                    ?: throw IllegalStateException("Anime ID not found (no playZone element)")
                idRegex.find(xData)?.groupValues?.getOrNull(1)
                    ?: throw IllegalStateException("Anime ID not found in x-data")
            }

        val enc = encDecEndpoints(animeId)

        val episodesResponse = client.newCall(
            GET("$baseUrl/api/v1/titles/$animeId/episodes?_=$enc", apiHeaders(animeUrl)),
        ).awaitSuccess().parseAs<AniGoEpisodesResponse>()

        if (episodesResponse.status != "ok") {
            throw IllegalStateException("Failed to fetch episodes: ${episodesResponse.status}")
        }

        return episodesResponse.result.rangedEpisodes
            .flatMap { it.episodes }
            .map { ep ->
                val subdub = when (ep.langs) {
                    1 -> "Sub"
                    2 -> "Dub"
                    3 -> "Dub & Sub"
                    else -> ""
                }

                val namePrefix = "Episode ${ep.number}"
                val detailName = ep.detailName?.takeIf { it.isNotBlank() && it != namePrefix }
                    ?.let { ": $it" }.orEmpty()
                val fillerTag = if (ep.isFiller == 1) " (Filler)" else ""

                SEpisode.create().apply {
                    name = namePrefix + detailName + fillerTag
                    url = ep.token
                    episode_number = ep.number.toFloat()
                    scanlator = subdub
                }
            }
            .reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val token = episode.url
        val enc = encDecEndpoints(token)

        val typeSelection = preferences.typeToggle
        val hosterSelection = preferences.hostToggle

        val epTokenResponse = client.newCall(
            GET("$baseUrl/api/v1/eptokens/$token?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<AniGoEpTokenResponse>()

        if (epTokenResponse.status != "ok") {
            return emptyList()
        }

        val servers = epTokenResponse.result.flatMap { epToken ->
            if (epToken.lang !in typeSelection) return@flatMap emptyList()

            epToken.links.mapNotNull { link ->
                if (link.serverTitle !in hosterSelection) return@mapNotNull null
                VideoCode(epToken.lang, link.id, link.serverTitle)
            }
        }

        return servers.parallelMapNotNull { server ->
            try {
                extractIframe(server)
            } catch (e: Exception) {
                Log.e("AniGo", "Failed to extract iframe from server: $server", e)
                null
            }
        }.parallelFlatMap { server ->
            try {
                extractVideo(server)
            } catch (e: Exception) {
                Log.e("AniGo", "Failed to extract video from server: $server", e)
                emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private var megaUpExtractor: MegaUpExtractor by LazyMutable { MegaUpExtractor(client, docHeaders) }

    private val jTitleRegex by lazy { """JTitle\(`([^`]*)`\)""".toRegex() }

    private val idRegex by lazy { Regex("""id:\s*'([^']+)'""") }

    private suspend fun extractIframe(server: VideoCode): VideoData {
        val (type, lid, serverName) = server

        val enc = encDecEndpoints(lid)

        val linkResponse = client.newCall(
            GET("$baseUrl/api/v1/links/$lid?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<AniGoLinkResponse>()

        if (linkResponse.status != "ok") {
            throw Exception("Failed to fetch link: ${linkResponse.status}")
        }

        val postBody = buildJsonObject {
            put("text", linkResponse.result)
        }
        val payload = postBody.toRequestBody()

        val reqHeaders = headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Content-Type", "application/json")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/watch")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .build()

        val iframe = client.newCall(POST("https://enc-dec.app/api/dec-kai", body = payload, headers = reqHeaders))
            .awaitSuccess().parseAs<IframeResponse>()
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

    private suspend fun extractVideo(server: VideoData): List<Video> = try {
        megaUpExtractor.videosFromUrl(
            server.iframe,
            server.serverName,
        )
    } catch (e: Exception) {
        Log.e("AniGo", "Error extracting videos for ${server.serverName}", e)
        emptyList()
    }

    private suspend fun encDecEndpoints(enc: String): String {
        val reqHeaders = headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/watch")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .build()
        return client.newCall(GET("https://enc-dec.app/api/enc-kai?text=$enc", reqHeaders))
            .awaitSuccess().parseAs<AniGoEncryptedResponse>().result
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

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "Completed", "Finished Airing" -> SAnime.COMPLETED
        "Releasing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun Element.getTitle(): String {
        val enTitle = text().trim()
        val xData = attr("x-data")
        val romajiTitle = jTitleRegex.find(xData)?.groupValues?.getOrNull(1)?.trim()
        return if (useEnglish) {
            enTitle.ifBlank { romajiTitle ?: "" }
        } else {
            romajiTitle?.ifBlank { enTitle } ?: enTitle
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"

        private val DOMAIN_ENTRIES = listOf("anigo.to")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = listOf("Romaji", "English")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES.first()

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = listOf("Server 1", "Server 2")

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

    private var useEnglish: Boolean by LazyMutable { preferences.getTitleLang == "English" }

    private val SharedPreferences.getTitleLang
        by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)

    private val SharedPreferences.prefQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val SharedPreferences.prefServer: String
        by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val SharedPreferences.prefType: String
        by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)

    private val SharedPreferences.hostToggle: Set<String>
        by preferences.delegate(PREF_HOSTER_KEY, HOSTERS.toSet())

    private val SharedPreferences.typeToggle: Set<String>
        by preferences.delegate(PREF_TYPE_TOGGLE_KEY, DEFAULT_TYPES)

    private val SharedPreferences.scorePosition: String
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
