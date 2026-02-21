package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.content.SharedPreferences
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vkextractor.VkExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animeextension.fr.animesama.extractors.VidMolyExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

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

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val page = response.request.url.fragment?.toInt() ?: 0
        val chunks = doc.select("#containerPepites > div a").chunked(5)
        val seasons = chunks.getOrNull(page - 1)?.flatMap {
            val animeUrl = "$baseUrl${it.attr("href")}"
            fetchAnimeSeasons(animeUrl)
        }?.toList().orEmpty()
        return AnimesPage(seasons, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page")

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.asJsoup()
        val seasons = animes.select("#containerAjoutsAnimes > div").flatMap {
            val animeUrl = it.getElementsByTag("a").attr("abs:href").toHttpUrl()
            val url = animeUrl.newBuilder()
                .removePathSegment(animeUrl.pathSize - 2)
                .removePathSegment(animeUrl.pathSize - 3)
                .build()
            fetchAnimeSeasons(url.toString())
        }.distinctBy { it.url }
        return AnimesPage(seasons, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // =============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

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
        val document = response.asJsoup()
        val anime = document.select("#list_catalog > div a").parallelFlatMapBlocking {
            fetchAnimeSeasons(it.attr("href"))
        }
        val page = response.request.url.queryParameterValues("page").firstOrNull()
        val hasNextPage = document.select("#list_pagination a:last-child").text() != page
        return AnimesPage(anime, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url.substringBeforeLast("/")}"
        val movie = anime.url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val players = VOICES_VALUES.map { fetchPlayers("$animeUrl/$it") }
        val episodes = playersToEpisodes(players)
        return if (movie == null) episodes.reversed() else listOf(episodes[movie])
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val videos = playerUrls.flatMapIndexed { i, it ->
            val prefix = "(${VOICES_VALUES[i].uppercase()}) "
            it.parallelCatchingFlatMap { playerUrl ->
                with(playerUrl) {
                    when {
                        contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(playerUrl, prefix)

                        contains("vk.") -> vkExtractor.videosFromUrl(playerUrl, prefix)

                        contains("sendvid.com") -> sendvidExtractor.videosFromUrl(playerUrl, prefix)

                        // .to doesn't work, and it's .biz that's used on the site
                        contains("vidmoly") -> vidmolyExtractor.videosFromUrl(playerUrl.replace(".to", ".biz"), prefix)

                        else -> emptyList()
                    }
                }
            }
        }
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

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).execute()
        return fetchAnimeSeasons(res)
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE) }

    private fun fetchAnimeSeasons(response: Response): List<SAnime> {
        val animeDoc = response.asJsoup()
        val animeUrl = response.request.url
        val animeName = animeDoc.getElementById("titreOeuvre")?.text() ?: ""

        val scripts = animeDoc.select("h2 + p + div > script, h2 + div > script").toString()
        val uncommented = commentRegex.replace(scripts, "")
        val animes = seasonRegex.findAll(uncommented).flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val movies = fetchPlayers(moviesUrl).ifEmpty { return@flatMapIndexed emptyList() }
                val movieNameRegex = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
                val moviesDoc = client.newCall(GET(moviesUrl)).execute().body.string()
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(movies.size) { i ->
                    val title = when {
                        animeIndex == 0 && movies.size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        movies.size == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Triple(title, "$moviesUrl#$i", SAnime.COMPLETED)
                }
            } else {
                listOf(Triple("$animeName $seasonName", "$animeUrl/$seasonStem", SAnime.UNKNOWN))
            }
        }

        return animes.map {
            SAnime.create().apply {
                title = it.first
                thumbnail_url = animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = animeDoc.select("h2:contains(synopsis) + p").text()
                genre = animeDoc.select("h2:contains(genres) + a").text().replace(" - ", ", ")
                setUrlWithoutDomain(it.second)
                status = it.third
                initialized = true
            }
        }.toList()
    }

    private fun playersToEpisodes(list: List<List<List<String>>>): List<SEpisode> = List(list.fold(0) { acc, it -> maxOf(acc, it.size) }) { episodeNumber ->
        val players = list.map { it.getOrElse(episodeNumber) { emptyList() } }
        SEpisode.create().apply {
            name = "Episode ${episodeNumber + 1}"
            url = json.encodeToString(players)
            episode_number = (episodeNumber + 1).toFloat()
            scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) VOICES_VALUES[i] else null }.joinToString().uppercase()
        }
    }

    private fun fetchPlayers(url: String): List<List<String>> {
        val docUrl = "$url/episodes.js"
        val doc = client.newCall(GET(docUrl)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        val urls = QuickJs.create().use { qjs ->
            qjs.evaluate(doc)
            val res = qjs.evaluate("JSON.stringify(Array.from({length: 10}, (e,i) => this[`eps\${i}`]).filter(e => e))")
            json.decodeFromString<List<List<String>>>(res as String)
        }

        if (urls.isEmpty() || urls[0].isEmpty()) return emptyList()
        return List(urls[0].size) { i -> urls.mapNotNull { it.getOrNull(i) }.distinct() }
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
        private const val PREF_URL_DEFAULT = "https://anime-sama.tv"
        private const val PREF_URL_SUMMARY = "Pour changer le domaine de l'extension. Voir https://anime-sama.pw"

        private val voicesMap = mapOf(
            "Préférer VOSTFR" to "vostfr",
            "Préférer VF" to "vf",
            "Préférer VF1" to "vf1",
            "Préférer VF2" to "vf2",
            "Préférer VA" to "va",
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
