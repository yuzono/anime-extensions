package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.content.SharedPreferences
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelFlatMapBlocking
import keiyoushi.utils.parallelMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class AnimeSama :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime-Sama"

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private var SharedPreferences.customDomain by preferences.delegate(PREF_URL_KEY, PREF_URL_DEFAULT)

    override var baseUrl by LazyMutable { preferences.customDomain.ifBlank { PREF_URL_DEFAULT }.sanitizeDomain() }

    override val client: OkHttpClient = super.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val maxRedirects = 5
            var request = chain.request()
            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect && redirectCount < maxRedirects) {
                val newUrl = response.header("Location") ?: break
                val newUrlHttp = request.url.resolve(newUrl) ?: break
                val redirectedDomain = newUrlHttp.run { "$scheme://$host" }
                if (redirectedDomain != baseUrl) {
                    updateDomain(redirectedDomain)
                }
                response.close()
                request = request.newBuilder()
                    .url(newUrlHttp)
                    .apply {
                        header("Origin", redirectedDomain)
                        header("Referer", "$redirectedDomain/")
                    }
                    .build()
                response = chain.proceed(request)
                redirectCount++
            }
            if (redirectCount >= maxRedirects) {
                response.close()
                throw java.io.IOException("Too many redirects: $maxRedirects")
            }
            response
        }.build()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val page = response.request.url.fragment?.toIntOrNull() ?: 0
        val chunks = doc.select("#containerPepites > div a").chunked(5)
        val seasons = chunks.getOrNull(page - 1)?.parallelCatchingFlatMapBlocking {
            val animeUrl = "$baseUrl${it.attr("href")}"
            fetchAnimeSeasons(animeUrl, "")
        }?.toList().orEmpty()
        return AnimesPage(seasons, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page")

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
        val seasons = animes.select("#containerAjoutsAnimes > div").parallelCatchingFlatMapBlocking {
            val animeUrl = it.getElementsByTag("a").attr("abs:href").toHttpUrl()
            val url = animeUrl.newBuilder()
                .removePathSegment(animeUrl.pathSize - 2)
                .removePathSegment(animeUrl.pathSize - 3)
                .build()
            fetchAnimeSeasons(url.toString(), "")
        }.distinctBy { it.url }
        return AnimesPage(seasons, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // =============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        } else if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val animeUrl = if (id.startsWith("/")) "$baseUrl$id" else "$baseUrl/$id"
            val seasons = fetchAnimeSeasons(animeUrl, "")
            return AnimesPage(seasons, false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = AnimeSamaFilters.getSearchFilters(filters)
        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }
        url.addQueryParameter("search", query)
        url.addQueryParameter("page", "$page")
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select("#list_catalog > div a").parallelFlatMapBlocking {
            fetchAnimeSeasons(it.attr("abs:href"), "")
        }
        val page = response.request.url.queryParameterValues("page").firstOrNull() ?: "1"
        val lastPage = document.select("#list_pagination a:last-child").text()
        val hasNextPage = lastPage.isNotEmpty() && lastPage != page
        return AnimesPage(anime, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val segments = anime.url.trim('/').split("/")
        val animeUrl = "$baseUrl/${segments.take(2).joinToString("/")}/"
        val season = segments.getOrNull(2) ?: ""

        val animes = fetchAnimeSeasons(animeUrl, season)
        return animes.firstOrNull() ?: anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.removeSuffix("/")
        val movie = url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val cleanUrl = url.substringBefore("#")
        val currentFolder = cleanUrl.substringAfterLast("/")
        val isVoiceFolder = VOICES_VALUES.contains(currentFolder)
        val parentUrl = if (isVoiceFolder) {
            "$baseUrl${cleanUrl.substringBeforeLast("/")}"
        } else {
            "$baseUrl$cleanUrl"
        }

        val paths = (listOf(currentFolder) + VOICES_VALUES).distinct()
        val players = paths.parallelMapBlocking { fetchPlayers("$parentUrl/$it") }
        val episodes = playersToEpisodes(players, paths)
        return if (movie == null) episodes.reversed() else listOf(episodes[movie])
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playerUrls = episode.url.parseAs<List<List<String>>>()
        val voiceNames = episode.scanlator?.split(", ") ?: emptyList()
        val videos = playerUrls.filter { it.isNotEmpty() }.flatMapIndexed { i, it ->
            val prefix = "(${voiceNames.getOrElse(i) { "" }}) "
            it.parallelCatchingFlatMap { playerUrl ->
                with(playerUrl) {
                    when {
                        contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(playerUrl, prefix)

                        contains("vk.") -> vkExtractor.videosFromUrl(playerUrl, prefix)

                        contains("sendvid.com") -> sendvidExtractor.videosFromUrl(playerUrl, prefix)

                        contains("vidmoly") -> vidmolyExtractor.videosFromUrl(playerUrl, prefix.trim())

                        else -> emptyList()
                    }
                }
            }
        }.sort()
        return videos
    }

    // ============================ Utils =============================
    override fun List<Video>.sort(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(voices, true) },
                { it.quality.contains(player, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    private suspend fun fetchAnimeSeasons(animeUrl: String, season: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).awaitSuccess()
        return fetchAnimeSeasons(res, season)
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE) }
    private val movieNameRegex by lazy { Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE) }

    private suspend fun fetchAnimeSeasons(response: Response, season: String): List<SAnime> {
        val animeDoc = response.useAsJsoup()
        val animeUrl = response.request.url.toString().removeSuffix("/")
        val animeName = animeDoc.selectFirst("h1")?.text() ?: ""

        val statusText = animeDoc.select(".info-lbl:contains(État) + .info-val")
            .firstOrNull()?.text() ?: ""

        val animeStatus = when {
            statusText.contains("En cours", true) -> SAnime.ONGOING
            statusText.contains("Terminé", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val thumbnailUrl = animeDoc.getElementById("coverOeuvre")?.attr("abs:src")
            ?: animeDoc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?: animeDoc.selectFirst("meta[itemprop=image]")?.attr("abs:content")

        val scripts = animeDoc.select("script").joinToString("\n") { it.data() }
        val uncommented = commentRegex.replace(scripts, "")
        val animes = seasonRegex.findAll(uncommented).withIndex().asIterable().parallelCatchingFlatMapBlocking { (animeIndex, seasonMatch) ->
            val (seasonName, seasonStem) = seasonMatch.destructured

            val stemSeason = seasonStem.substringBefore("/")
            if (season.isNotEmpty() && stemSeason != season) {
                return@parallelCatchingFlatMapBlocking emptyList()
            }

            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val movies = fetchPlayers(moviesUrl).ifEmpty { return@parallelCatchingFlatMapBlocking emptyList() }
                val moviesDoc = client.newCall(GET(moviesUrl)).awaitSuccess().bodyString()
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(movies.size) { i ->
                    val title = when {
                        animeIndex == 0 && movies.size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        movies.size == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Pair(title, "$moviesUrl#$i")
                }
            } else {
                val displaySeason = if (stemSeason.startsWith("saison")) {
                    "Saison " + stemSeason.substringAfter("saison").substringBefore("/")
                } else {
                    seasonName.substringBefore(" (")
                }
                listOf(Pair("$animeName $displaySeason", "$animeUrl/$seasonStem"))
            }
        }

        val descriptionText = animeDoc.selectFirst("#synopsisText")?.text() ?: ""
        val genresText = animeDoc.select(".genre-pill").joinToString(", ") { g -> g.text() }

        return animes.map {
            SAnime.create().apply {
                title = it.first
                thumbnail_url = thumbnailUrl
                description = descriptionText
                genre = genresText
                setUrlWithoutDomain(it.second.removeSuffix("/"))
                status = animeStatus
                initialized = true
            }
        }
    }

    private fun playersToEpisodes(list: List<List<List<String>>>, voiceNames: List<String>): List<SEpisode> {
        val episodeCount = list.maxOfOrNull { voice ->
            voice.maxOfOrNull { player -> player.size } ?: 0
        } ?: 0

        return List(episodeCount) { epIdx ->
            val episodeVoices = list.map { voicePlayers ->
                voicePlayers.mapNotNull { it.getOrNull(epIdx) }
            }
            SEpisode.create().apply {
                name = "Episode ${epIdx + 1}"
                url = episodeVoices.toJsonString()
                episode_number = (epIdx + 1).toFloat()
                scanlator = episodeVoices.mapIndexedNotNull { i, players ->
                    if (players.isNotEmpty()) voiceNames[i] else null
                }.joinToString().uppercase()
            }
        }
    }

    private suspend fun fetchPlayers(url: String): List<List<String>> {
        val docUrl = "${url.removeSuffix("/")}/episodes.js"
        return try {
            val doc = client.newCall(GET(docUrl))
                .awaitSuccess()
                .bodyString()
            QuickJs.create().use { qjs ->
                qjs.evaluate(doc)
                val res = qjs.evaluate($$"JSON.stringify(Object.keys(this).filter(k => /^eps[0-9]+$/.test(k)).sort((a, b) => parseInt(a.slice(3)) - parseInt(b.slice(3))).map(k => this[k]))")
                (res as String).parseAs<List<List<String>>>()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun String.sanitizeDomain() = trim().removeSuffix("/").ifBlank { PREF_URL_DEFAULT }

    private fun updateDomain(domain: String) {
        val newDomain = domain.sanitizeDomain()
        if (URLUtil.isValidUrl(newDomain)) {
            preferences.customDomain = newDomain
            baseUrl = newDomain
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_URL_KEY,
            title = PREF_URL_TITLE,
            default = PREF_URL_DEFAULT,
            summary = PREF_URL_SUMMARY,
            onChange = { _, newValue ->
                val newDomain = newValue.trim().removeSuffix("/")
                if (URLUtil.isValidUrl(newDomain)) {
                    updateDomain(newDomain)
                    true
                } else {
                    Toast.makeText(screen.context, "URL invalide. Exemple: $PREF_URL_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            },
        )

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = VOICES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = "Lecteur par défaut"
            entries = PLAYERS
            entryValues = PLAYERS_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_URL_KEY = "base_url_pref"
        private const val PREF_URL_TITLE = "URL de base"

        // Domain info at: https://anime-sama.pw
        private const val PREF_URL_DEFAULT = "https://anime-sama.to"
        private const val PREF_URL_SUMMARY = "Pour changer le domaine de l'extension. Voir https://anime-sama.pw"

        private val voicesMap = mapOf(
            "Préférer VOSTFR" to "vostfr",
            "Préférer VF" to "vf",
            "Préférer VF1" to "vf1",
            "Préférer VF2" to "vf2",
            "Préférer VA" to "va",
            "Préférer VAR" to "var",
            "Préférer VCN" to "vcn",
            "Préférer VJ" to "vj",
            "Préférer VKR" to "vkr",
            "Préférer VQC" to "vqc",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Sendvid" to "sendvid",
            "Sibnet" to "sibnet",
            "VK" to "vk",
            "VidMoly" to "vidmoly",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_PLAYER_KEY = "player_preference"
        private const val PREF_PLAYER_DEFAULT = "sibnet"
    }
}
