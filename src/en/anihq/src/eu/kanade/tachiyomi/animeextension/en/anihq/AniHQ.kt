package eu.kanade.tachiyomi.animeextension.en.anihq

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

class AniHQ :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniHQ"

    override val baseUrl = "https://anihq.cc"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val filters by lazy { Filters(baseUrl, client, preferences) }

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val preferredTitleLanguage: String
        get() = preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT) ?: PREF_TITLE_DEFAULT

    // ============================== Nonce ================================
    private val nonceRegex by lazy { Regex("""search_actions"\s*:\s*"(\w+)""") }

    @Volatile
    private var searchNonce: String? = null

    private class NonceRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun getSearchNonce(): String {
        searchNonce?.let { return it }
        for (page in listOf("$baseUrl/search/", "$baseUrl/")) {
            runCatching {
                client.newCall(GET(page, headers)).execute().use { response ->
                    nonceRegex.find(response.body.string())
                        ?.groupValues?.get(1)
                        ?.let { searchNonce = it }
                }
            }
            searchNonce?.let { return it }
        }
        throw Exception("Unable to fetch search nonce")
    }

    private suspend fun <T> withNonceRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        val rejected = generateSequence<Throwable>(e) { it.cause }
            .any { it is NonceRejectedException || it.message?.contains("403") == true }
        if (!rejected) throw e
        searchNonce = null
        block()
    }

    // ============================== Browse ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = withNonceRetry {
        parseCatalog(client.newCall(popularAnimeRequest(page)).awaitSuccess())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withNonceRetry {
        parseCatalog(client.newCall(latestUpdatesRequest(page)).awaitSuccess())
    }

    override fun popularAnimeRequest(page: Int) = searchRequest(page) {
        add("s_keyword", "")
        add("orderby", "popular")
        add("order", "DESC")
    }

    override fun latestUpdatesRequest(page: Int) = searchRequest(page) {
        add("s_keyword", "")
        add("orderby", "updated")
        add("order", "DESC")
    }

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun searchRequest(page: Int, build: FormBody.Builder.() -> Unit): Request = POST(
        "$baseUrl/wp-admin/admin-ajax.php",
        headers,
        FormBody.Builder()
            .add("action", "advanced_search")
            .add("nonce", getSearchNonce())
            .add("page", page.toString())
            .apply(build)
            .build(),
    )

    private fun parseCatalog(response: Response): AnimesPage {
        val results = try {
            response.parseAs<SearchResponseDto>()
        } catch (e: Exception) {
            searchNonce = null
            throw NonceRejectedException("Search nonce rejected", e)
        }

        val animes = Jsoup.parseBodyFragment(results.data.html).parseAnimes()
        return AnimesPage(animes, results.data.currentPage < results.data.maxPages)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = filters.getFilterList()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = when (url.pathSegments.getOrNull(0)) {
                "anime-show" -> url.pathSegments.getOrNull(1)
                "watch" -> url.pathSegments.getOrNull(1)?.let { toAnimeShowSlug(it) }
                else -> null
            } ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val details = getAnimeDetails(SAnime.create().apply { url = "/anime-show/$id" })
            return AnimesPage(listOf(details), false)
        }

        return withNonceRetry {
            parseCatalog(client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess())
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = searchRequest(page) {
        add("s_keyword", query)
        filters.filterIsInstance<Filters.QueryParameterFilter>().forEach {
            val (name, values) = it.toQueryParameter()
            values.forEach { value -> add(name, value) }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.newCall(animeDetailsRequest(anime)).awaitSuccess().useAsJsoup()

        val realDoc = document.selectFirst("div.anime-information h4 a")?.attr("abs:href")
            ?.takeIf { it.isNotBlank() && it != document.location() }
            ?.let { client.newCall(GET(it, headers)).awaitSuccess().useAsJsoup() }
            ?: document

        val info = (realDoc.selectFirst("div.anime-information") ?: realDoc)
            .select("dt")
            .associate { it.text().trim() to it.nextElementSibling()?.text().orEmpty() }

        fun infoValue(label: String): String? = info[label]?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }

        val mainTitle = (realDoc.selectFirst("h1")?.text() ?: realDoc.selectFirst("title")?.text())
            ?.cleanTitle().orEmpty()

        val aired = infoValue("Aired")

        val score = realDoc.selectFirst("svg.lucide-star + span")?.text()?.toFloatOrNull()

        val overview = cleanSynopsis(
            realDoc.selectFirst("section[aria-label='Anime Overview'] p")?.html(),
        )

        val variantSuffix = variantRegex.find(mainTitle)?.value.orEmpty()

        return SAnime.create().apply {
            setUrlWithoutDomain(realDoc.location())
            title = when (preferredTitleLanguage) {
                "native" -> infoValue("Native")?.let { "$it $variantSuffix".trim() } ?: mainTitle
                "english" -> infoValue("English")?.let { "$it $variantSuffix".trim() } ?: mainTitle
                else -> mainTitle
            }
            thumbnail_url = realDoc.selectFirst("img.wp-post-image")?.attr("abs:src")
                ?: realDoc.selectFirst("meta[property='og:image']")?.attr("content")
            genre = infoValue("Genres")
                ?: realDoc.select("div.flex a.hover\\:text-white")
                    .joinToString { it.text() }.takeIf(String::isNotBlank)
            author = infoValue("Studios")
            status = when {
                aired == null -> anime.status
                aired.startsWith("?") -> SAnime.UNKNOWN
                aired.endsWith("?") -> SAnime.ONGOING
                else -> SAnime.COMPLETED
            }
            description = listOfNotNull(
                score?.takeIf { it > 0f }?.let { s ->
                    val stars = (s / 2).roundToInt().coerceIn(0, 5)
                    "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $s"
                },
                overview,
            ).joinToString("\n\n").takeIf(String::isNotBlank)
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    private val titleCleanRegex by lazy {
        Regex(
            """\s*[-–—|]\s*AniHQ\b.*$|\(? *(?:All Episodes )?Watch(?: Anime Online| Online Free| Online)? *\)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun String.cleanTitle(): String = replace(titleCleanRegex, "").trim().trimEnd('-', '–', '—', '|', ' ')

    // ============================ Related ============================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val animeId = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
            .useAsJsoup()
            .findAnimeId()
            ?: return emptyList()

        return runCatching {
            val dto = client.get(
                "$baseUrl/wp-json/kiranime/v1/widget?name=recommended&id=$animeId",
            ).parseAs<RecommendedResponseDto>()

            Jsoup.parseBodyFragment(dto.html).parseAnimes()
        }.getOrDefault(emptyList())
    }

    private val regexId by lazy { Regex("""current_(?:post_data_id|anime_id)\s*=\s*(\d+)""") }

    private val watchEpisodeRegex by lazy { Regex("""(.+?)-episode-\d+(-[a-z-]+)?$""") }

    private fun toAnimeShowSlug(slug: String): String? = watchEpisodeRegex.find(slug)?.let { it.groupValues[1] + it.groupValues[2] }

    private val animeShowUrlRegex by lazy { Regex("""https://[^'"]+?/anime-show/[^'"]+""") }

    private fun Document.parseAnimes(): List<SAnime> {
        return select("article.anime-card").mapNotNull { card ->
            val element = card.selectFirst("h3 > a.stretched-link") ?: return@mapNotNull null
            val episodeUrl = element.attr("abs:href").ifBlank { return@mapNotNull null }

            val animeUrl = card.selectFirst("button[onclick*='anime-show']")
                ?.attr("onclick")
                ?.let { animeShowUrlRegex.find(it)?.value }
                ?: toAnimeShowSlug(episodeUrl.substringAfterLast("/watch/").trimEnd('/'))
                    ?.let { "$baseUrl/anime-show/$it" }
                ?: episodeUrl

            SAnime.create().apply {
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
                title = (element.attr("title").ifBlank { element.selectFirst("span")?.text() ?: element.text() })
                    .cleanTitle()
                status = (
                    card.selectFirst("div.absolute.top-2.right-2 span")
                        ?: card.selectFirst("[class*='-status']")
                    )
                    ?.text()?.let(::parseStatus) ?: SAnime.UNKNOWN
                setUrlWithoutDomain(animeUrl)
            }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val document = client.newCall(episodeListRequest(anime)).awaitSuccess().useAsJsoup()

        val animeId = document.findAnimeId()
            ?: throw Exception("Could not determine anime ID")

        fun episodesCall(page: Int) = GET(
            "$baseUrl/wp-admin/admin-ajax.php?action=get_episodes&anime_id=$animeId&page=$page&order=desc",
            headers,
        )

        val firstPage = episodesCall(1).fetchEpisodePage()
        val episodes = firstPage.data.episodes.toMutableList()
        val totalPages = firstPage.data.maxEpisodesPage.coerceAtLeast(1)

        if (totalPages > 1) {
            (2..totalPages).chunked(EPISODE_FETCH_BATCH).forEach { batch ->
                val fetched = batch.map { page ->
                    async(Dispatchers.IO) { episodesCall(page).fetchEpisodePage() }
                }.awaitAll()
                episodes += fetched.flatMap { it.data.episodes }
            }
        }

        return@coroutineScope episodes.map {
            it.toSEpisode(SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH))
        }.sortedByDescending { it.episode_number }
    }

    private suspend fun Request.fetchEpisodePage(): EpisodeResponseDto {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return client.newCall(this).awaitSuccess().parseAs()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Episode fetch failed")
    }

    private fun Document.findAnimeId(): String? = select("script").firstNotNullOfOrNull {
        regexId.find(it.data())
            ?.groupValues?.get(1)
    }
        ?: selectFirst("#seasonContent")
            ?.attr("data-season")

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Hosters ============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val rawHtml = client.get(baseUrl + episode.url).body.string()
        val document = Jsoup.parse(rawHtml, baseUrl)

        val hosters = mutableListOf<Hoster>()
        val seen = mutableSetOf<String>()

        fun addHoster(url: String, name: String) {
            if (seen.add(url)) hosters += Hoster(hosterUrl = url, hosterName = name)
        }

        fun nameFor(url: String, label: String? = null): String = when {
            "pixeldrain" in url -> "PixelDrain"
            "voe" in url -> label?.takeIf(String::isNotBlank) ?: "Voe"
            !label.isNullOrBlank() -> label
            else -> "Stream"
        }

        document.select("span[data-embed-id]").forEach { span ->
            val raw = span.attr("data-embed-id")
            val label = raw.substringBefore(":").trim().decodeBase64OrNull()
                ?.replace(Regex("(?i)(sub|dub)$"), " $1")
            val url = raw.substringAfter(":", "").trim().decodeBase64OrNull()
                ?.takeIf { it.startsWith("http") } ?: return@forEach
            addHoster(url, nameFor(url, label))
        }

        document.select("div.episode-player-box iframe").forEach { iframe ->
            val url = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
                .takeIf(String::isNotBlank) ?: return@forEach
            addHoster(url, nameFor(url))
        }

        document.select("section.download-section a.download-section-item-link").forEach { a ->
            val url = a.attr("abs:href").takeIf(String::isNotBlank) ?: return@forEach
            val res = a.parent()?.previousElementSibling()?.text()
                .orEmpty().ifBlank { "Download" }
            addHoster(url, nameFor(url) + " ($res)")
        }

        if (hosters.isEmpty()) {
            val unescaped = rawHtml.replace("\\/", "/")
            HOSTERS_REGEX.findAll(unescaped)
                .map { it.value }.distinct()
                .forEach { addHoster(it, nameFor(it)) }
        }

        val hidden = preferences.getStringSet(PREF_HIDE_HOSTERS_KEY, emptySet()).orEmpty()
            .map(String::lowercase)
        return hosters.filter { h ->
            hidden.none { it in h.hosterUrl.lowercase() }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferred = preferredServer.lowercase()
        return sortedByDescending { preferred in it.hosterUrl.lowercase() }
    }

    private fun String.decodeBase64OrNull(): String? = runCatching {
        decodeBase64()?.utf8()
    }.getOrNull()

    // ============================ Videos ============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val isPreferredServer = preferredServer.lowercase() in url.lowercase()
        return when {
            "voe" in url -> voeExtractor.videosFromUrl(url)
            "pixeldrain" in url -> {
                val res = PIXELDRAIN_QUALITY_REGEX.find(hoster.hosterName)?.groupValues?.getOrNull(1)
                pixelDrainExtractor.videosFromUrl(url, res?.let { "$it " } ?: "")
            }
            else -> emptyList()
        }.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality) }
                .thenBy { v ->
                    PREF_QUALITY_VALUES.indexOfFirst { v.videoTitle.contains(it) }
                        .let { if (it < 0) Int.MAX_VALUE else it }
                },
        ).mapIndexed { index, video ->
            video.copy(preferred = isPreferredServer && index == 0)
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Helpers ===============================
    private fun cleanSynopsis(raw: String?): String? = raw
        ?.replace(BR_REGEX, "\n")
        ?.let { Parser.unescapeEntities(it, false) }
        ?.replace(INLINE_TAG_REGEX, "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TITLE_KEY,
            title = "Preferred Title Language",
            default = PREF_TITLE_DEFAULT,
            summary = "%s",
            entries = PREF_TITLE_ENTRIES,
            entryValues = PREF_TITLE_VALUES,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = HOSTERS,
            entryValues = HOSTERS,
        )

        screen.addSetPreference(
            key = PREF_HIDE_HOSTERS_KEY,
            title = "Exclude Hosters",
            summary = "Select hosters to hide from the server list",
            entries = HOSTERS,
            entryValues = HOSTERS,
            default = emptySet(),
        )
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val EPISODE_FETCH_BATCH = 6

        private const val PREF_TITLE_KEY = "preferred_title_language"
        private const val PREF_TITLE_DEFAULT = "english"
        private val PREF_TITLE_ENTRIES = listOf("English", "Native")
        private val PREF_TITLE_VALUES = listOf("english", "native")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private val HOSTERS = listOf("Voe", "PixelDrain")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private const val PREF_HIDE_HOSTERS_KEY = "hidden_hosters"
        private val HOSTERS_REGEX = Regex("""https?://[^\s"'\\<>]*(?:voe|pixeldrain)\.[^\s"'\\<>]+""")

        private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val INLINE_TAG_REGEX = Regex("</?(?:i|b|em|strong)>", RegexOption.IGNORE_CASE)

        private val variantRegex by lazy {
            Regex("""\bEnglish\s+((?:Sub|Dub)bed|Sub|Dub)$""", RegexOption.IGNORE_CASE)
        }

        private val PIXELDRAIN_QUALITY_REGEX = Regex("""\((\d+p)\)""")

        fun parseStatus(status: String?): Int {
            val s = status?.lowercase() ?: return SAnime.UNKNOWN
            return when {
                "not" in s || "upcoming" in s -> SAnime.UNKNOWN
                "releasing" in s || "airing" in s || "currently" in s -> SAnime.ONGOING
                "finished" in s || "completed" in s -> SAnime.COMPLETED
                "cancel" in s -> SAnime.CANCELLED
                "hiatus" in s -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
        }
    }
}
