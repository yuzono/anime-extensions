package eu.kanade.tachiyomi.animeextension.pt.sushianimes

import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class SushiAnimes :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Sushi Animes"

    override val baseUrl = "https://sushianimes.com.br"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trends", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select("a.list-trend").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst(".list-title")!!.text()
                thumbnail_url = element.selectFirst(".media-cover")?.attr("data-src")
                description = element.selectFirst(".list-description")?.text()
            }
        }
        return AnimesPage(anime, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodios?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select(".episode-grid a.list-movie:not(:has(.hentai-list-media))").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst(".list-caption")!!.text()
                thumbnail_url = element.selectFirst(".media-episode")?.attr("data-src")
            }
        }
        val hasNextPage = document.selectFirst("a.btn.btn-theme.ml-2") != null
        return AnimesPage(anime, hasNextPage)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = when {
        query.startsWith("https://") -> handleUrlSearch(page, query, filters)
        query.startsWith(PREFIX_SEARCH) -> handlePathSearch(query)
        else -> super.getSearchAnime(page, query, filters)
    }

    private suspend fun handleUrlSearch(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val url = query.toHttpUrl()
        if (url.host != baseUrl.toHttpUrl().host) {
            throw IllegalArgumentException("Unsupported URL host: ${url.host}")
        }
        val searchQuery = if (url.pathSegments.size > 1) {
            "${url.pathSegments[0]}/${url.pathSegments[1]}"
        } else {
            url.pathSegments.getOrNull(0)?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Unsupported URL path: ${url.pathSegments}")
        }
        return getSearchAnime(page, "${PREFIX_SEARCH}$searchQuery", filters)
    }

    private suspend fun handlePathSearch(query: String): AnimesPage {
        val path = query.removePrefix(PREFIX_SEARCH)
        val response = client.newCall(GET("$baseUrl/$path", headers)).awaitSuccess()
        val document = response.useAsJsoup()
        val doc = resolveRealDoc(document)
        val details = parseAnimeDetails(doc).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select("div.list-movie").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                title = element.selectFirst(".list-title")!!.text()
                thumbnail_url = element.selectFirst(".media-cover")?.attr("data-src")
            }
        }
        return AnimesPage(anime, false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val document = response.useAsJsoup()
        val doc = resolveRealDoc(document)
        return parseAnimeDetails(doc)
    }

    private fun parseAnimeDetails(doc: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("#title")?.text()
            ?: throw IllegalStateException("Title element not found in anime details")
        thumbnail_url = doc.selectFirst(".media-cover img")?.attr("src")
        description =
            doc.selectFirst(".detail-attr:contains(Sinopse) .text,.detail-attr:contains(Descrição) .text")
                ?.text()
        genre = doc.select(".category-list a, .categories a").eachText().joinToString()
        status = SAnime.UNKNOWN
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime)).awaitSuccess()
        val document = response.useAsJsoup()
        val doc = resolveRealDoc(document)
        val script = doc.selectFirst("script[type=\"application/ld+json\"]")
            ?: return emptyList()

        // Movies expose a single "Assistir" button instead of a season list.
        doc.selectFirst("a.btn:contains(Assistir)")?.let { btnMovie ->
            return parseMovieEpisode(btnMovie)
        }

        return parseSeriesEpisodes(script.data().trim().let(::sanitizeLdJsonNames))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun parseMovieEpisode(btnMovie: Element): List<SEpisode> = listOf(
        SEpisode.create().apply {
            setUrlWithoutDomain(btnMovie.absUrl("href"))
            name = "Filme"
            episode_number = 1F
        },
    )

    private fun parseSeriesEpisodes(jsonString: String): List<SEpisode> {
        val anime = try {
            jsonString.parseAs<AnimeDto>(JSON)
        } catch (e: Exception) {
            Log.w("SushiAnimes", "Failed to parse LD+JSON: ${e.message}")
            return emptyList()
        }

        val episodes = anime.containsSeason.flatMap { season ->
            season.episode.map { episode ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(episode.url)
                    name =
                        "Temporada ${season.seasonNumber} x ${episode.episodeNumber} - ${episode.name}"
                    episode_number = episode.episodeNumber.toFloatOrNull() ?: 0F
                }
            }
        }
        // Reverse so the list reads newest-first (site exposes oldest-first).
        return episodes.reversed()
    }

    // ============================ Video Links =============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).awaitSuccess()
        val document = response.useAsJsoup()

        val embeds = document.select("[data-embed]")
        if (embeds.isEmpty()) return emptyList()

        // The site requires a CSRF token (meta[name="csrf-token"] / var _TOKEN) on
        // POST requests, sent both as the X-CSRF-TOKEN header and the _TOKEN form field.
        val csrfToken = extractCsrfToken(document)

        val requestHeaders = headers.newBuilder().apply {
            if (csrfToken.isNotBlank()) add("X-CSRF-TOKEN", csrfToken)
        }.build()

        // Each [data-embed] is a video source; fetch in parallel and ignore failures.
        return embeds.parallelCatchingFlatMapBlocking { embed ->
            val id = embed.attr("data-embed")

            val formBody = FormBody.Builder()
                .add("id", id)
                .apply { if (csrfToken.isNotBlank()) add("_TOKEN", csrfToken) }
                .build()

            val request = POST("$baseUrl/ajax/embed", requestHeaders, formBody)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("SushiAnimes", "Failed to fetch embed for id=$id: HTTP ${response.code}")
                response.close()
                return@parallelCatchingFlatMapBlocking emptyList()
            }
            val body = response.bodyString()

            parseEmbedVideos(body)
        }.sort()
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    /**
     * Parses the HTML returned by /ajax/embed. It can be:
     *  - one or more <iframe src="..."> (e.g. the proxy.php?src=<real url> wrapper);
     *  - a script-based response exposing `var playerEmbed = "<url>"` and
     *    `var playerName = "<label>"` (e.g. "FullHD / HLS").
     */
    private suspend fun parseEmbedVideos(body: String): List<Video> {
        val doc = Jsoup.parse(body, baseUrl)
        val iframes = doc.select("iframe[src]")

        return if (iframes.isNotEmpty()) {
            parseIframeVideos(iframes)
        } else {
            parseScriptVideos(body)
        }
    }

    private suspend fun parseIframeVideos(iframes: Elements): List<Video> {
        val videos = mutableListOf<Video>()
        for (iframe in iframes) {
            val rawSrc = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            val videoUrl = if (rawSrc.contains("proxy.php")) {
                // The proxy wraps the real player URL in ?src=<real url>.
                rawSrc.toHttpUrlOrNull()?.queryParameter("src") ?: rawSrc
            } else {
                rawSrc
            }
            // Guard against relative/malformed URLs that would crash downstream extractors.
            if (videoUrl.toHttpUrlOrNull() == null) continue
            videos.addAll(getVideosFromUrl(videoUrl))
        }
        return videos
    }

    private fun parseScriptVideos(body: String): List<Video> {
        // Script-based response: extract the direct URL and quality label.
        val rawUrl = PLAYER_EMBED_REGEX.find(body)?.groupValues?.get(1) ?: return emptyList()
        // The site escapes slashes (e.g. "https:\/\/..."); normalize before use.
        val directUrl = rawUrl.replace("\\/", "/")

        val quality = PLAYER_NAME_REGEX.find(body)?.groupValues?.get(1)
            ?.let(::qualityFromLabel)
            .orEmpty()

        val label = if (quality.isBlank()) "Sushi Animes" else "Sushi Animes - $quality"

        return listOf(Video(directUrl, label, directUrl))
    }

    /**
     * Maps a player label (e.g. "FHD", "fullhd", "FullHD / HLS", "1080") to a quality tag.
     */
    private fun qualityFromLabel(label: String): String {
        val l = label.lowercase()
        return when {
            "fullhd" in l || "fhd" in l || "1080" in l -> "1080p"
            "720" in l || ("hd" in l && "fullhd" !in l) -> "720p"
            "480" in l || "sd" in l -> "480p"
            "360" in l -> "360p"
            "240" in l -> "240p"
            else -> label
        }
    }

    /**
     * Decides how to extract videos based on the player URL.
     */
    private suspend fun getVideosFromUrl(url: String): List<Video> = when {
        url.contains("blogger.com") -> bloggerExtractor.videosFromUrl(url, headers)
        else -> emptyList()
    }

    /**
     * Extracts the CSRF token the site expects on POST requests, trying in order:
     *  - `<meta name="csrf-token" content="...">`;
     *  - `var _TOKEN = "..."` inside an inline script.
     * Returns an empty string when no token is found (the request may still succeed).
     */
    private fun extractCsrfToken(document: Document): String {
        val fromMeta = document.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
        if (!fromMeta.isNullOrBlank()) return fromMeta

        val fromScript = document.selectFirst("script:containsData(_TOKEN)")?.data()
            ?.let { CSRF_TOKEN_REGEX.find(it)?.groupValues?.get(1) }
        if (!fromScript.isNullOrBlank()) return fromScript

        Log.w("SushiAnimes", "CSRF token not found on page")
        return ""
    }

    // ============================= Utilities ==============================
    private suspend fun resolveRealDoc(document: Document): Document {
        val menu = document.selectFirst(".episode-nav .home-list a")
        if (menu != null) {
            val originalUrl = menu.attr("href")
            return try {
                client.newCall(GET(originalUrl, headers)).awaitSuccess().useAsJsoup()
            } catch (e: Exception) {
                Log.w("SushiAnimes", "Failed to resolve real doc from $originalUrl: ${e.message}")
                document
            }
        }

        return document
    }

    /**
     * Uses regex to find the value of `"name": "..."` and escape only the
     * unescaped inner quotes within that value.
     */
    private fun sanitizeLdJsonNames(input: String): String = NAME_VALUE_REGEX.replace(input) { match ->
        val value = match.groupValues[1]
        val escaped = value.replace(UNESCAPED_QUOTE_REGEX, "\\\\\"")
        "\"name\": \"$escaped\""
    }

    // =========================== Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Qualidade de vídeo preferida",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    companion object {
        const val PREFIX_SEARCH = "path:"

        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = listOf("1080p", "720p", "480p", "360p", "240p")

        private val QUALITY_REGEX = Regex("(\\d+)p")

        private val PLAYER_EMBED_REGEX = Regex("""var playerEmbed\s*=\s*["']([^"']+)["']""")
        private val PLAYER_NAME_REGEX = Regex("""var playerName\s*=\s*["']([^"']+)["']""")
        private val CSRF_TOKEN_REGEX = Regex("""_TOKEN\s*=\s*["']([^"']+)["']""")

        private val NAME_VALUE_REGEX = Regex("\"name\"\\s*:\\s*\"(.*?)\",")
        private val UNESCAPED_QUOTE_REGEX = Regex("(?<!\\\\)\"")
    }
}
