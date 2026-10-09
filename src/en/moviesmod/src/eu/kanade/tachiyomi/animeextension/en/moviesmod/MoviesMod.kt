package eu.kanade.tachiyomi.animeextension.en.moviesmod

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

class MoviesMod : Source() {

    override val id = 2828515480418041073L

    override val name = "Movies Mod"

    override val lang = "en"

    override val supportsLatest = false

    override val baseUrl by lazy {
        val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT
        if (stored == "https://moviesmod.red" || stored == "https://moviesmod.army") {
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
                    // Try baseUrl first, handling HTTP redirects
                    val resolvedFromBase = runCatching {
                        client.newBuilder()
                            .followRedirects(false)
                            .build()
                            .newCall(GET("$baseUrl/")).await().use { resp ->
                                when (resp.code) {
                                    301, 302, 307, 308 -> {
                                        val target = resp.headers["location"]
                                            ?.let { resp.request.url.resolve(it) }
                                            ?.takeIf { it.host.contains("moviesmod") }
                                        val origin = target?.let { "${it.scheme}://${it.host}" }
                                        if (origin != null && resp.code in setOf(301, 308)) {
                                            preferences.edit().putString(PREF_DOMAIN_KEY, origin).apply()
                                        }
                                        origin ?: baseUrl
                                    }
                                    in 200..299 -> baseUrl
                                    else -> null
                                }
                            }
                    }.getOrNull()

                    if (resolvedFromBase != null) return@withContext resolvedFromBase

                    // Fallback: resolve latest domain via mmodlist redirect which always points to current domain
                    val latest = runCatching {
                        client.newCall(GET(MMODLIST_URL, headers)).execute().use { resp ->
                            val body = resp.body.string()
                            // mmodlist returns 200 with meta refresh: url=https://moviesmod.zone
                            // Trim trailing dot that can come from sentence punctuation
                            Regex("""https://moviesmod\.[a-z0-9.-]+""").find(body)?.value?.trimEnd('/', '.')
                        }
                    }.getOrNull()

                    latest?.let {
                        preferences.edit().putString(PREF_DOMAIN_KEY, it).apply()
                        it
                    } ?: baseUrl
                }
            }
        }.getOrDefault(baseUrl)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun getAnimeUrl(anime: SAnime): String = currentBaseUrl + anime.url

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.get("$currentBaseUrl/page/$page/", headers)
        return parseAnimePage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.replace(" ", "+").lowercase()
        val response = client.get("$currentBaseUrl/search/$cleanQuery/page/$page", headers)
        return parseAnimePage(response)
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div#content_box div.post-cards > article").mapNotNull { element ->
            val linkEl = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val img = element.selectFirst("div.featured-thumbnail > img")
            val thumb = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("abs:src")
            val rawTitle = linkEl.attr("title").ifBlank { linkEl.text() }
            val cleanTitle = rawTitle.replace("Download", "").trim()
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
                title = cleanTitle
            }
        }
        val hasNextPage = doc.select("#content_box > nav > div > a.next.page-numbers").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.get(currentBaseUrl + anime.url, headers)
        val document = response.asJsoup()
        return SAnime.create().apply {
            initialized = true
            title = document.selectFirst(".entry-title")?.text()
                ?.replace("Download", "", true)?.trim() ?: "Movie"
            status = SAnime.UNKNOWN
            author = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__footer > span")?.text()
            description = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__teaser")?.text()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val doc = client.newCall(GET(currentBaseUrl + anime.url, headers)).execute().asJsoup()
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
        val childPageLoaded = AtomicBoolean(false)
        val triples = episodeElements.toList().parallelMapNotNullBlocking { row ->
            runCatching {
                val prevP = row.previousElementSiblings()
                    .firstOrNull { it.text().isNotBlank() }?.text().orEmpty()

                val quality = qualityRegex.find(prevP)?.value ?: "HD"
                val defaultName = if (isSerie) {
                    seasonRegex.find(prevP)?.value ?: "Season 1"
                } else {
                    movieTitleRegex.find(prevP.replace("Download", "").trim())?.value ?: "Movie"
                }

                val episodePageUrl = row.selectFirst("a[href]")?.attr("abs:href")?.takeUnless { it.isBlank() }
                    ?: return@parallelMapNotNullBlocking null

                val childUrl = extractChildUrl(episodePageUrl)

                val episodePageDocument = runCatching {
                    client.newCall(GET(childUrl, headers)).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNullBlocking null
                childPageLoaded.set(true)

                val links = episodePageDocument.select("div.timed-content-client_show_0_5_0 a")
                    .ifEmpty {
                        episodePageDocument.select("""a[href*="?sid="], a[href*="r?key="]""")
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

                    val url = linkElement.attr("abs:href").takeUnless(String::isBlank)
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

                scanlator = if (isSerie) {
                    seasonRegex.find(itemName)?.groupValues?.get(1)?.let { "Season $it" } ?: itemName.trim()
                } else {
                    null
                }
            }
        }

        if (grouped.isEmpty()) {
            throw Exception(
                if (childPageLoaded.get()) {
                    "Only Zip Pack Available"
                } else {
                    "Failed to load episode pages. Site may have changed or is behind Cloudflare."
                },
            )
        }
        grouped.reversed()
    }

    private fun extractChildUrl(mainUrl: String): String {
        return runCatching {
            val urlParam = mainUrl.toHttpUrl().queryParameter("url") ?: return mainUrl
            val flags = if (urlParam.contains("-") || urlParam.contains("_")) Base64.URL_SAFE else Base64.DEFAULT
            String(Base64.decode(urlParam, flags))
        }.getOrDefault(mainUrl)
    }

    // =========================== Hosters & Videos ==========================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "Default", hosterUrl = episode.url))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val urlJson = runCatching { json.decodeFromString<EpLinks>(hoster.hosterUrl) }.getOrNull()
            ?: return emptyList()

        return urlJson.urls.parallelCatchingFlatMap { eplink ->
            val mediaUrl = getMediaUrl(eplink) ?: return@parallelCatchingFlatMap emptyList()
            extractVideos(mediaUrl, eplink.quality)
        }.sortVideosByPreference()
    }

    private suspend fun extractVideos(fileUrl: String, quality: String): List<Video> {
        val doc = runCatching { client.get(fileUrl, headers).asJsoup() }.getOrNull()
            ?: return emptyList()

        val btns = doc.select("div.card-body a.btn")
        if (btns.isEmpty()) return emptyList()

        // Extract videos, handling HLS via PlaylistUtils where applicable
        return btns.flatMap { btn ->
            val href = btn.attr("abs:href").takeUnless { it.isBlank() } ?: return@flatMap emptyList()
            val size = SIZE_REGEX.find(btn.text())?.groupValues?.get(1)?.let { " - $it" } ?: ""

            when {
                href.contains("cdn.video-gen.xyz") || href.contains("video-seed.dev") ||
                    href.contains("r2.dev") || href.contains("instant.video-gen") -> {
                    val finalUrl = runCatching {
                        client.head(href, headers).use { resp ->
                            if (!resp.isSuccessful) return@use null
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
                href.contains("/login") -> emptyList()
                else -> {
                    // Fallback for r2.dev, seedtg.xyz, tgcdn_bot and future hosts - expose as direct
                    listOf(Video(href, "$quality - Direct$size", href))
                }
            }
        }
    }

    // ============================= Utilities ==============================
    private val redirectBypasser by lazy { RedirectorBypasser(client, headers) }

    private suspend fun getMediaUrl(epUrl: EpUrl): String? {
        val url = epUrl.url
        val mediaResponse = when {
            url.contains("?sid=") -> {
                val finalUrl = redirectBypasser.bypass(url) ?: return null
                client.get(finalUrl)
            }
            url.contains("r?key=") -> {
                client.get(url)
            }
            else -> return null
        }

        val path = mediaResponse.body.string().substringAfter("replace(\"").substringBefore("\"")
        if (path == "/404") return null

        return "https://" + mediaResponse.request.url.host + path
    }

    private fun List<Video>.sortVideosByPreference(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val ascSort = (preferences.getString(PREF_SIZE_SORT_KEY, PREF_SIZE_SORT_DEFAULT) ?: PREF_SIZE_SORT_DEFAULT) == "asc"

        val comparator = compareByDescending<Video> { it.videoTitle.contains(quality) }.let { cmp ->
            if (ascSort) {
                cmp.thenBy { it.videoTitle.fixQuality() }
            } else {
                cmp.thenByDescending { it.videoTitle.fixQuality() }
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
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SIZE_SORT_KEY,
            title = PREF_SIZE_SORT_TITLE,
            entries = PREF_SIZE_SORT_ENTRIES,
            entryValues = PREF_SIZE_SORT_VALUES,
            default = PREF_SIZE_SORT_DEFAULT,
            summary = PREF_SIZE_SORT_SUMMARY,
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            default = PREF_DOMAIN_DEFAULT,
            summary = getDomainPrefSummary(),
            getSummary = { it },
            dialogMessage = "For any change to be applied App restart is required.",
            restartRequired = true,
        )
    }

    @Serializable
    data class EpLinks(
        val urls: List<EpUrl> = emptyList(),
    )

    @Serializable
    data class EpUrl(
        val quality: String = "",
        val url: String = "",
    )

    private fun EpLinks.toJson(): String = json.encodeToString(this)

    private fun getDomainPrefSummary(): String = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    companion object {
        private val SIZE_REGEX = """\[((?:.(?!\[))+)]*\$""".toRegex(RegexOption.IGNORE_CASE)

        private const val PREF_DOMAIN_KEY = "pref_domain_new"
        private const val PREF_DOMAIN_TITLE = "Currently used domain"
        private const val PREF_DOMAIN_DEFAULT = "https://moviesmod.zone"
        private const val MMODLIST_URL = "https://mmodlist.org/?type=hollywood"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("2160p", "1080p", "720p", "480p")

        private const val PREF_SIZE_SORT_KEY = "preferred_size_sort"
        private const val PREF_SIZE_SORT_TITLE = "Preferred Size Sort"
        private const val PREF_SIZE_SORT_DEFAULT = "asc"
        private val PREF_SIZE_SORT_SUMMARY = """%s
            |Sort order to be used after the videos are sorted by their quality.
        """.trimMargin()
        private val PREF_SIZE_SORT_ENTRIES = listOf("Ascending", "Descending")
        private val PREF_SIZE_SORT_VALUES = listOf("asc", "desc")
    }
}
