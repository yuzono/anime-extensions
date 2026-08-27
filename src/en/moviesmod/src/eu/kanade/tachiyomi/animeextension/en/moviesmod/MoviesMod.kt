package eu.kanade.tachiyomi.animeextension.en.moviesmod

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class MoviesMod :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Movies Mod"

    override val baseUrl by lazy {
        val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        if (stored == "https://moviesmod.red") {
            preferences.edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
            PREF_DOMAIN_DEFAULT
        } else {
            stored
        }
    }

    private val currentBaseUrl by lazy {
        runCatching {
            runBlocking {
                withContext(Dispatchers.Default) {
                    client.newBuilder()
                        .followRedirects(false)
                        .build()
                        .newCall(GET("$baseUrl/")).await().use { resp ->
                            when (resp.code) {
                                301 -> {
                                    (resp.headers["location"]?.substringBeforeLast("/") ?: baseUrl).also {
                                        preferences.edit().putString(PREF_DOMAIN_KEY, it).apply()
                                    }
                                }

                                else -> baseUrl
                            }
                        }
                }
            }
        }.getOrDefault(baseUrl)
    }

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$currentBaseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div#content_box div.post-cards > article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        val img = element.selectFirst("div.featured-thumbnail > img")
        thumbnail_url = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("abs:src")
        title = element.select("a").attr("title")
            .replace("Download", "").trim()
    }

    override fun popularAnimeNextPageSelector(): String = "#content_box > nav > div > a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$currentBaseUrl/search/$cleanQuery/page/$page")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        initialized = true
        title = document.selectFirst(".entry-title")?.text()
            ?.replace("Download", "", true)?.trim() ?: "Movie"
        status = SAnime.UNKNOWN
        author = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__footer > span")?.text()
        description = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__teaser")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = GET(currentBaseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        // Original selector + fallback for site redesign / domain change
        val episodeElements = doc.select("p:has(a.maxbutton-episode-links,a.maxbutton-download-links)")
            .ifEmpty { doc.select("p:has(a[class*=maxbutton])") }
            .asSequence()

        if (!episodeElements.iterator().hasNext()) {
            throw Exception("No episode links found. Site may have changed or is behind Cloudflare.")
        }

        val qualityRegex = "\\d{3,4}p(?:\\s+\\w+)?".toRegex(RegexOption.IGNORE_CASE)
        val seasonRegex = "[ .]?S(?:eason)?[ .]?(\\d{1,2})[ .]?".toRegex(RegexOption.IGNORE_CASE)
        val movieTitleRegex = "^[^(]+\n?".toRegex(RegexOption.IGNORE_CASE)

        // Safe check for series vs movie; avoid NPE on empty or missing text
        val isSerie = episodeElements.firstOrNull()?.selectFirst("a")?.text()?.equals("Episode Links", ignoreCase = true) == true

        // Parallelize child-page fetches to avoid performance regression vs sequential Jsoup.connect
        val triples = episodeElements.toList().parallelMapNotNullBlocking { row ->
            runCatching {
                val prevP = row.previousElementSibling()?.text()?.takeIf { it.isNotBlank() }
                    ?: row.previousElementSiblings().firstOrNull()?.text().orEmpty()

                val quality = qualityRegex.find(prevP)?.value ?: "HD"
                val defaultName = if (isSerie) {
                    seasonRegex.find(prevP)?.value ?: "Season 1"
                } else {
                    movieTitleRegex.find(prevP.replace("Download", "").trim())?.value ?: "Movie"
                }

                val episodePageUrl = row.selectFirst("a[href]")?.attr("href")?.takeUnless { it.isBlank() }
                    ?: return@parallelMapNotNullBlocking null

                val childUrl = extractChildUrl(episodePageUrl)

                val episodePageDocument = runCatching {
                    client.newCall(GET(childUrl, headers)).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNullBlocking null

                val links = episodePageDocument.select("div.timed-content-client_show_0_5_0 a")
                    .ifEmpty {
                        episodePageDocument.select("a[href*=?sid=], a[href*=r?key=]")
                    }

                links.mapIndexedNotNull { index, linkElement ->
                    val episode = if (isSerie) {
                        linkElement.text()
                            .replace("Episode", "", true)
                            .trim()
                            .toIntOrNull() ?: (index + 1)
                    } else {
                        0
                    }

                    val url = linkElement.attr("href").takeUnless(String::isBlank)
                        ?: return@mapIndexedNotNull null

                    Triple(
                        Pair(defaultName, episode),
                        url,
                        if (isSerie) quality else "$quality ${linkElement.text()}".trim(),
                    )
                }
            }.getOrNull()
        }.flatten()

        val grouped = triples.groupBy { it.first }.values.mapIndexed { index, items ->
            val (itemName, episodeNum) = items.first().first

            SEpisode.create().apply {
                url = EpLinks(
                    urls = items.map { triple ->
                        EpUrl(url = triple.second, quality = triple.third)
                    },
                ).toJson()

                name = if (isSerie) "$itemName Ep $episodeNum" else itemName

                episode_number = if (isSerie) episodeNum.toFloat() else (index + 1).toFloat()
            }
        }

        if (grouped.isEmpty()) throw Exception("Only Zip Pack Available")
        return grouped.reversed()
    }

    private fun extractChildUrl(mainUrl: String): String {
        return runCatching {
            val urlParam = mainUrl.toHttpUrl().queryParameter("url") ?: return mainUrl
            runCatching { String(Base64.decode(urlParam, Base64.DEFAULT)) }
                .getOrElse { String(Base64.decode(urlParam, Base64.URL_SAFE)) }
        }.getOrDefault(mainUrl)
    }

    override fun episodeListSelector(): String = "p:has(a.maxbutton-episode-links)"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)

        return urlJson.urls.parallelCatchingFlatMap { eplink ->
            val mediaUrl = getMediaUrl(eplink) ?: return@parallelCatchingFlatMap emptyList()
            extractVideos(mediaUrl, eplink.quality)
        }
    }

    private fun extractVideos(fileUrl: String, quality: String): List<Video> {
        val doc = runCatching { client.newCall(GET(fileUrl, headers)).execute().asJsoup() }.getOrNull()
            ?: return emptyList()

        val btns = doc.select("div.card-body a.btn")
        if (btns.isEmpty()) return emptyList()

        // Extract videos, handling HLS via PlaylistUtils where applicable
        return btns.flatMap { btn ->
            val href = btn.attr("abs:href").takeUnless { it.isBlank() } ?: return@flatMap emptyList()
            val size = SIZE_REGEX.find(btn.text())?.groupValues?.get(1)?.let { " - $it" } ?: ""

            when {
                href.contains("cdn.video-gen.xyz") || href.contains("video-seed.dev") -> {
                    val finalUrl = runCatching {
                        val headRequest = GET(href, headers).newBuilder().head().build()
                        client.newCall(headRequest).execute().use { resp ->
                            resp.request.url.queryParameter("url") ?: resp.request.url.toString()
                        }
                    }.getOrNull() ?: href

                    if (finalUrl.contains(".m3u8")) {
                        runCatching {
                            playlistUtils.extractFromHls(
                                finalUrl,
                                videoNameGen = { q -> "$quality - $q$size" },
                            )
                        }.getOrDefault(listOf(Video(finalUrl, "$quality - HLS$size", finalUrl)))
                    } else {
                        listOf(Video(finalUrl, "$quality - Instant$size", finalUrl))
                    }
                }
                href.contains(".m3u8") -> {
                    runCatching {
                        playlistUtils.extractFromHls(
                            href,
                            videoNameGen = { q -> "$quality - $q$size" },
                        )
                    }.getOrDefault(emptyList())
                }
                href.contains("/login") || href.contains("seedtg.xyz") -> emptyList()
                else -> emptyList()
            }
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private val redirectBypasser by lazy { RedirectorBypasser(client, headers) }

    private fun getMediaUrl(epUrl: EpUrl): String? {
        val url = epUrl.url
        val mediaResponse = if (url.contains("?sid=")) {
            /* redirector bs */
            val finalUrl = redirectBypasser.bypass(url) ?: return null
            client.newCall(GET(finalUrl)).execute()
        } else if (url.contains("r?key=")) {
            /* everything under control */
            client.newCall(GET(url)).execute()
        } else {
            return null
        }

        val path = mediaResponse.body.string().substringAfter("replace(\"").substringBefore("\"")

        if (path == "/404") return null

        return "https://" + mediaResponse.request.url.host + path
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val ascSort = preferences.getString(PREF_SIZE_SORT_KEY, PREF_SIZE_SORT_DEFAULT)!! == "asc"

        val comparator = compareByDescending<Video> { it.quality.contains(quality) }.let { cmp ->
            if (ascSort) {
                cmp.thenBy { it.quality.fixQuality() }
            } else {
                cmp.thenByDescending { it.quality.fixQuality() }
            }
        }
        return sortedWith(comparator)
    }

    private fun String.fixQuality(): Float {
        val size = substringAfterLast("-").trim()
        return if (size.contains("GB", true)) {
            size.replace("GB", "", true)
                .toFloatOrNull()?.let { it * 1000 } ?: 1F
        } else {
            size.replace("MB", "", true)
                .toFloatOrNull() ?: 1F
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SIZE_SORT_KEY
            title = PREF_SIZE_SORT_TITLE
            entries = PREF_SIZE_SORT_ENTRIES
            entryValues = PREF_SIZE_SORT_VALUES
            setDefaultValue(PREF_SIZE_SORT_DEFAULT)
            summary = PREF_SIZE_SORT_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            dialogTitle = PREF_DOMAIN_DIALOG_TITLE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).ifEmpty { PREF_DOMAIN_DEFAULT }
                    preferences.edit().putString(key, value).commit().also {
                        summary = getDomainPrefSummary()
                    }
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    @Serializable
    data class EpLinks(
        val urls: List<EpUrl>,
    )

    @Serializable
    data class EpUrl(
        val quality: String,
        val url: String,
    )

    private fun EpLinks.toJson(): String = json.encodeToString(this)

    private fun getDomainPrefSummary(): String = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!.let {
        """$it
                |For any change to be applied App restart is required.
        """.trimMargin()
    }

    companion object {
        private val SIZE_REGEX = "\\[((?:.(?!\\[))+)]*\\$".toRegex(RegexOption.IGNORE_CASE)

        private const val PREF_DOMAIN_KEY = "pref_domain_new"
        private const val PREF_DOMAIN_TITLE = "Currently used domain"
        private const val PREF_DOMAIN_DEFAULT = "https://moviesmod.army"
        private const val PREF_DOMAIN_DIALOG_TITLE = PREF_DOMAIN_TITLE

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Prefferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p")

        private const val PREF_SIZE_SORT_KEY = "preferred_size_sort"
        private const val PREF_SIZE_SORT_TITLE = "Preferred Size Sort"
        private const val PREF_SIZE_SORT_DEFAULT = "asc"
        private val PREF_SIZE_SORT_SUMMARY = """%s
            |Sort order to be used after the videos are sorted by their quality.
        """.trimMargin()
        private val PREF_SIZE_SORT_ENTRIES = arrayOf("Ascending", "Descending")
        private val PREF_SIZE_SORT_VALUES = arrayOf("asc", "desc")
    }
}
