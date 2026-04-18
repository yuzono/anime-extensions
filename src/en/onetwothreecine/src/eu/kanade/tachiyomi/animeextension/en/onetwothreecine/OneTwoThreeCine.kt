package eu.kanade.tachiyomi.animeextension.en.onetwothreecine

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
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.math.BigDecimal
import java.math.RoundingMode

class OneTwoThreeCine :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "123Cine"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences = getPreferences {
        clearOldPrefs()
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val apiClient by lazy { client.newBuilder().build() }

    private var docHeaders by LazyMutable { headersBuilder().build() }

    private var rapidShareExtractor by LazyMutable {
        RapidShareExtractor(client, docHeaders)
    }

    // ============================ Headers ============================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")

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

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?sort=trending&page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", docHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = CineFilters.getSearchParameters(filters)
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .apply {
                params.types.forEach { addQueryParameter("type[]", it) }
                params.genres.forEach { addQueryParameter("genre[]", it) }
                if (params.genres.isNotEmpty()) addQueryParameter("genre_mode", params.genreMode)
                params.countries.forEach { addQueryParameter("country[]", it) }
                if (params.countries.isNotEmpty()) addQueryParameter("country_mode", params.countryMode)
                params.years.forEach { addQueryParameter("year[]", it) }
                params.qualities.forEach { addQueryParameter("quality[]", it) }
                if (params.sort.isNotEmpty()) addQueryParameter("sort", params.sort)
            }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun getFilterList(): AnimeFilterList = CineFilters.FILTER_LIST

    // ========================= Shared Parse ============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.item > div.inner").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val href = poster.attr("abs:href")
                .takeIf { it.startsWith("$baseUrl/watch/") } ?: return@mapNotNull null
            val title = item.selectFirst("div.detail > div.title")?.text()?.trim()
                ?: return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = item.selectFirst("a.poster img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()

        val headWrapper = document.selectFirst(".head-movie-wrapper")
            ?: throw IllegalStateException("Invalid details page")

        title = headWrapper.selectFirst("h1.title")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst(".detail-start .poster img")?.attr("abs:src")

        val isMovie = headWrapper.selectFirst(".metadata .dot:matchesOwn(Movie)") != null
        status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING

        val descText = document.selectFirst(".movie-info .desc")?.text()

        val metaFoot = document.selectFirst(".mini-meta-foot")
        genre = metaFoot?.select("a[href^=/genre/]")?.eachText()?.joinToString() ?: ""

        val country = metaFoot?.select("a[href^=/country/]")?.firstOrNull()?.text() ?: ""
        val released = metaFoot?.select("div:containsOwn(Released:) span")?.text() ?: ""
        val quality = metaFoot?.select("div:containsOwn(Quality:) span")?.text() ?: ""

        val metaLines = document.select(".mini-meta-line .mini-meta")
        val director = metaLines.select("h2:containsOwn(Director) + div a").eachText().joinToString()
        val casts = metaLines.select("h2:containsOwn(Casts) + div a").eachText().joinToString()
        val productions = metaLines.select("h2:containsOwn(Productions) + div a").eachText().joinToString()

        val imdbScore = document.selectFirst(".mini-meta h2:containsOwn(Highlight) + div span")
            ?.text()?.removePrefix("IMDb ")?.trim()
        val fancyScore = getFancyScore(imdbScore)

        val scorePos = preferences.scorePosition

        description = buildString {
            if (scorePos == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                append(fancyScore)
                append("\n\n")
            }
            descText?.let { append(it) }
            if (quality.isNotBlank()) append("\n**Quality:** $quality")
            if (country.isNotBlank()) append("\n**Country:** $country")
            if (released.isNotBlank()) append("\n**Released:** $released")
            if (director.isNotBlank()) append("\n**Director:** $director")
            if (casts.isNotBlank()) append("\n**Casts:** $casts")
            if (productions.isNotBlank()) append("\n**Productions:** $productions")
            if (scorePos == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(fancyScore)
            }
        }
    }

    private fun getFancyScore(score: String?): String {
        if (score.isNullOrBlank()) return ""
        return try {
            val scoreBig = BigDecimal(score.trim())
            if (scoreBig.compareTo(BigDecimal.ZERO) == 0) return ""

            val stars = scoreBig.divide(BigDecimal(2))
                .setScale(0, RoundingMode.HALF_UP)
                .toInt().coerceIn(0, 5)
            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("★".repeat(stars))
                if (stars < 5) append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: NumberFormatException) {
            ""
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = fetchAnimeId(anime)
        val enc = encrypt(animeId)

        val episodesResponse = client.newCall(
            GET("$baseUrl/api/v1/titles/$animeId/episodes?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<EpisodesResponse>()

        if (episodesResponse.status != "ok") {
            throw Exception("Failed to fetch episodes: ${episodesResponse.status}")
        }

        return episodesResponse.result.rangedEpisodes.flatMap { range ->
            range.episodes.map { ep ->
                SEpisode.create().apply {
                    val namePrefix = "Episode ${ep.number}"
                    val detailName = ep.detailName?.takeIf { it.isNotBlank() && it != namePrefix }
                        ?.let { ": $it" }.orEmpty()
                    val fillerTag = if (ep.isFiller == 1) " (Filler)" else ""

                    name = namePrefix + detailName + fillerTag
                    url = ep.token
                    episode_number = ep.number.toFloat()
                    scanlator = when (ep.langs) {
                        1 -> "Sub"
                        2 -> "Dub"
                        3 -> "Dub & Sub"
                        else -> ""
                    }
                }
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val token = episode.url
        val enc = encrypt(token)
        val hosterSelection = preferences.hosterPref

        val serversResponse = client.newCall(
            GET("$baseUrl/api/v1/episodes/$token?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<EpisodeServersResponse>()

        if (serversResponse.status != "ok") return emptyList()

        val servers = serversResponse.result.flatMap { group ->
            group.links.mapNotNull { link ->
                if (link.serverTitle !in hosterSelection) return@mapNotNull null
                Triple(group.lang, link.id, link.serverTitle)
            }
        }

        return servers.parallelMapNotNull { (lang, lid, serverName) ->
            runCatching {
                val linkEnc = encrypt(lid)

                val linkResponse = client.newCall(
                    GET("$baseUrl/api/v1/links/$lid?_=$linkEnc", apiHeaders("$baseUrl/watch")),
                ).awaitSuccess().parseAs<LinkResponse>()

                if (linkResponse.status != "ok") return@runCatching null

                val iframeUrl = decrypt(linkResponse.result)
                val langSuffix = when (lang) {
                    "sub" -> "Sub"
                    "dub" -> "Dub"
                    "softsub" -> "Soft Sub"
                    else -> lang.replaceFirstChar { it.uppercase() }
                }
                val prefix = "$serverName [$langSuffix]"

                rapidShareExtractor.videosFromUrl(iframeUrl, prefix, preferences.subLangPref)
            }.getOrNull()
        }.flatten()
    }

    // ========================= Encryption ============================

    private suspend fun encrypt(text: String): String = apiClient.newCall(GET("https://enc-dec.app/api/enc-movies-flix?text=$text"))
        .awaitSuccess()
        .parseAs<ResultResponse>().result

    private suspend fun decrypt(text: String): String = apiClient.newCall(GET("https://enc-dec.app/api/dec-movies-flix?text=$text"))
        .awaitSuccess()
        .parseAs<DecryptedIframeResponse>().result.url

    // ========================= ID Extraction =========================

    private val watchIdRegex by lazy { Regex("""id:\s*'([^']+)'""") }

    private suspend fun fetchAnimeId(anime: SAnime): String = client.newCall(animeDetailsRequest(anime)).awaitSuccess().use { response ->
        val document = response.asJsoup()
        val xData = document.selectFirst("[x-data^='Watch']")?.attr("x-data")
            ?: document.selectFirst("[x-data]")?.attr("x-data")
            ?: throw IllegalStateException("Watch element not found on details page")

        watchIdRegex.find(xData)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("Anime ID not found in Watch init data")
    }

    // ============================ Video Sort ============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val qualitiesList = QUALITIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality, true) }
                .thenByDescending { video -> qualitiesList.indexOfFirst { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    // ============================== Preferences ===========================

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.hosterPref by preferences.delegate(PREF_HOSTER_KEY, SERVERS.toSet())
    private val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT)
    private val SharedPreferences.scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
        if (domain != null && domain !in DOMAIN_VALUES) {
            edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
        }
        val hostToggle = getStringSet(PREF_HOSTER_KEY, SERVERS.toSet())
        if (hostToggle != null && hostToggle.any { it !in SERVERS }) {
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
            docHeaders = headersBuilder().set("Referer", "$it/").build()
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
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVERS,
            entryValues = SERVERS,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_LANG_KEY,
            title = "Preferred subtitle language",
            entries = SUB_LANGS,
            entryValues = SUB_LANGS,
            default = PREF_SUB_LANG_DEFAULT,
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
            summary = "Select which video servers to show in the episode list",
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private val DOMAIN_ENTRIES = listOf("123cine.to")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = QUALITIES.first()

        private const val PREF_SERVER_KEY = "pref_server_key"
        private val SERVERS = listOf("Server 1", "Server 2")
        private val PREF_SERVER_DEFAULT = SERVERS.first()

        private const val PREF_HOSTER_KEY = "pref_hoster_key"

        private const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        private val SUB_LANGS = listOf(
            "English", "Arabic", "Chinese", "French", "German", "Indonesian",
            "Italian", "Japanese", "Korean", "Portuguese", "Russian",
            "Spanish", "Turkish", "Vietnamese",
        )
        private val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        private const val PREF_SCORE_POSITION_KEY = "score_position"
        private const val SCORE_POS_TOP = "top"
        private const val SCORE_POS_BOTTOM = "bottom"
        private const val SCORE_POS_NONE = "none"
        private const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        private val PREF_SCORE_POSITION_ENTRIES = listOf("Top of description", "Bottom of description", "Don't show")
        private val PREF_SCORE_POSITION_VALUES = listOf(SCORE_POS_TOP, SCORE_POS_BOTTOM, SCORE_POS_NONE)
    }
}
