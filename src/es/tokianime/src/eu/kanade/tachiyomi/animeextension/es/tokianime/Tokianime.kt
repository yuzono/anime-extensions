package eu.kanade.tachiyomi.animeextension.es.tokianime

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Tokianime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Tokianime"

    override val baseUrl = "https://tokianime.tv"

    override val lang = "es"

    override val supportsLatest = true

    // The host answers 429 past roughly eight requests per second, so stay below that.
    override val client = network.client.newBuilder()
        .rateLimit(5) { it.host == baseUrl.toHttpUrl().host }
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchCatalog(
        catalogUrl(page).addQueryParameter("sort", "popular").build(),
    )

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // "/ultimos" is a single page of about 120 entries, so it is sliced into pages here.
        val cards = client.get("$baseUrl/ultimos").useAsJsoup()
            .select("a[href^=/watch/]")
            .mapNotNull { el ->
                val slug = el.attr("href").removePrefix("/watch/").substringBefore('/')
                if (slug.isEmpty()) return@mapNotNull null
                val title = REGEX_EPISODE_SUFFIX.replace(el.attr("aria-label").removePrefix("Ver "), "")
                Triple(
                    slug,
                    title.ifEmpty { slug },
                    el.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") },
                )
            }
            .distinctBy { it.first }

        val from = (page - 1) * LATEST_PAGE_SIZE
        if (from >= cards.size) return AnimesPage(emptyList(), false)

        // The cards carry an episode still; the calendar already knows real covers for a
        // third of them. The rest keep the still and pick the cover up once opened.
        val covers = fetchCalendarCovers()
        val slice = cards.subList(from, minOf(from + LATEST_PAGE_SIZE, cards.size))

        val animes = slice.map { (slug, title, still) ->
            SAnime.create().apply {
                url = "/anime/$slug"
                this.title = title
                thumbnail_url = covers[slug] ?: still
                initialized = false
            }
        }

        return AnimesPage(animes, from + LATEST_PAGE_SIZE < cards.size)
    }

    /**
     * Slug to portrait cover for every anime on the weekly calendar, where each card embeds
     * the catalog cover. Falls back to the episode stills when the page cannot be read.
     */
    private suspend fun fetchCalendarCovers(): Map<String, String> = try {
        client.get("$baseUrl/calendario").useAsJsoup()
            .select("a[href^=/anime/]")
            .mapNotNull { el ->
                val slug = el.attr("href").removePrefix("/anime/").trim()
                val cover = el.select("img")
                    .map { it.attr("src") }
                    .firstOrNull { it.startsWith("http") && "$IMAGE_HOST/c/" in it }
                if (slug.isEmpty() || cover == null) null else slug to cover
            }
            .toMap()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "fetchCalendarCovers: failed", e)
        emptyMap()
    }

    // ============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = catalogUrl(page)

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        filters.firstInstanceOrNull<StatusFilter>()?.let {
            if (it.selected != "ALL") url.addQueryParameter("status", it.selected)
        }
        filters.firstInstanceOrNull<FormatFilter>()?.let {
            if (it.selected != "ALL") url.addQueryParameter("format", it.selected)
        }
        filters.firstInstanceOrNull<AudioFilter>()?.let {
            if (it.selected != "ALL") url.addQueryParameter("audio", it.selected)
        }
        filters.firstInstanceOrNull<SortFilter>()?.let {
            url.addQueryParameter("sort", it.selected)
        }
        filters.firstInstanceOrNull<GenreFilter>()?.let { group ->
            val selected = group.state.filter { it.state }.map { it.name }
            if (selected.isNotEmpty()) {
                url.addQueryParameter("genres", selected.joinToString(","))
            }
        }

        return fetchCatalog(url.build())
    }

    private fun catalogUrl(page: Int): HttpUrl.Builder = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
        .addQueryParameter("adult", ADULT)
        .addQueryParameter("pageSize", PAGE_SIZE.toString())
        .addQueryParameter("page", (page - 1).toString())

    private suspend fun fetchCatalog(url: HttpUrl): AnimesPage {
        val data = client.get(url).parseAs<CatalogResponse>()
        val animes = data.items.map { it.toSAnime() }
        return AnimesPage(animes, data.items.size == PAGE_SIZE)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        StatusFilter(),
        FormatFilter(),
        AudioFilter(),
        SortFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.trimStart('/').removePrefix("anime/")

        val apiUrl = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", ADULT)
            .addQueryParameter("pageSize", DETAIL_LOOKUP_PAGE_SIZE)
            .addQueryParameter("q", slug)
            .build()
        val data = client.get(apiUrl).parseAs<CatalogResponse>()
        val found = data.items.firstOrNull { it.slug == slug }
        if (found != null) return found.toSAnime()

        // The catalog search does not resolve every slug ("no-game-no-life" answers with an
        // empty page), so fall back to the detail page whose og:image always carries a cover.
        return client.get("$baseUrl/anime/$slug").use { animeDetailsParse(it) }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val slug = response.request.url.pathSegments.last()

        val anime = try {
            doc.extractNextJs<CatalogAnime> { element ->
                element is JsonObject && "slug" in element && "title" in element
            }
        } catch (e: Exception) {
            Log.e(TAG, "animeDetailsParse: extractNextJs failed", e)
            null
        }

        if (anime != null) {
            return anime.toSAnime()
        }

        val title = doc.select("meta[property=og:title]").attr("content")
            .removeSuffix(" Sub Español Online HD")
            .removeSuffix(" Sub Online HD")
            .ifEmpty { slug }
        val description = doc.select("meta[property=og:description]").attr("content")
        val image = doc.select("meta[property=og:image]").attr("content")

        return SAnime.create().apply {
            this.url = "/anime/$slug"
            this.title = title
            this.thumbnail_url = image
            this.description = description
            this.initialized = true
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        val currentSlug = response.request.url.pathSegments.last()
        val results = mutableListOf<SAnime>()

        // 1. Season/OVA links from the <ol> list - already merged into episodes, so skip them
        val seasonSlugs = mutableSetOf<String>()
        doc.select("ol a[href^=/anime/]").forEach { el ->
            val href = el.attr("href")
            val slug = href.removePrefix("/anime/").trim()
            if (slug.isNotEmpty() && slug != currentSlug) {
                seasonSlugs.add(slug)
            }
        }

        // 2. Recommendation links from other sections (card-style with text/images)
        doc.select("a[href^=/anime/].anime-card-touch, a[href^=/anime/][draggable]").forEach { el ->
            val href = el.attr("href")
            val slug = href.removePrefix("/anime/").trim()
            if (slug.isEmpty() || slug == currentSlug || slug in seasonSlugs) return@forEach
            val imgEl = el.selectFirst("img")

            // The title lives in its own <h3>; el.text() also drags in badges, genres and "2024 • 12 eps".
            val title = el.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: el.attr("aria-label")
                    .removePrefix("Ver episodios de ")
                    .removePrefix("Ver anime de ")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                ?: cleanRelatedTitle(el.text())
            if (title.isEmpty()) return@forEach

            results.add(
                SAnime.create().apply {
                    url = "/anime/$slug"
                    this.title = title
                    thumbnail_url = imgEl?.attr("src")?.takeIf { it.startsWith("http") }
                        ?: imgEl?.attr("data-src")?.takeIf { it.startsWith("http") }
                    initialized = false
                },
            )
        }

        return results.distinctBy { it.url }.take(MAX_RELATED)
    }

    /** Last resort for cards exposing neither <h3> nor aria-label: strips badges, genres and the "2024 • 12 eps" line. */
    private fun cleanRelatedTitle(raw: String): String {
        var title = REGEX_BADGE_PREFIX.replace(raw, "")
        title = REGEX_GENRE_STRIP.replace(title, "")
        title = REGEX_YEAR_EPS.replace(title, "")
        title = REGEX_YEAR.replace(title, "")
        return title.trim()
    }

    // ============================== Episodes ===============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.trimStart('/').removePrefix("anime/")

        val seasonEntries = try {
            val doc = client.get("$baseUrl/anime/$slug").useAsJsoup()
            doc.select("ol a[href^=/anime/]").mapNotNull { el ->
                val href = el.attr("href")
                val relSlug = href.removePrefix("/anime/").trim()
                val ariaLabel = el.attr("aria-label")
                    .removePrefix("Ver episodios de ")
                    .removePrefix("Ver anime de ")
                if (relSlug.isNotEmpty() && ariaLabel.isNotEmpty()) {
                    Pair(relSlug, ariaLabel)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "getEpisodeList: failed to fetch detail page", e)
            emptyList()
        }

        if (seasonEntries.isEmpty()) {
            return fetchEpisodesForSlug(slug, mutableSetOf())
                .sortedByDescending { it.episode_number }
        }

        // Merge all episodes with labels from aria-label
        val allEpisodes = mutableListOf<SEpisode>()
        val usedTitles = mutableSetOf<String>()
        var episodeOffset = 0f

        for ((relSlug, label) in seasonEntries) {
            val relEpisodes = fetchEpisodesForSlug(relSlug, usedTitles)
            // Read the raw maximum before the loop rewrites the numbers with the current
            // offset - reading it afterwards would apply the offset twice per season.
            val blockEnd = relEpisodes.maxOfOrNull { it.episode_number } ?: 0f
            relEpisodes.forEach { ep ->
                ep.name = "$label - ${ep.name}"
                ep.episode_number = ep.episode_number + episodeOffset
                allEpisodes.add(ep)
            }
            episodeOffset += blockEnd
        }

        return allEpisodes.sortedByDescending { it.episode_number }
    }

    private suspend fun fetchEpisodesForSlug(slug: String, usedTitles: MutableSet<String>): List<SEpisode> = try {
        val data = client.get("$baseUrl/api/anime/$slug/episodes").parseAs<EpisodesResponse>()
        data.withVideo.map { epNum ->
            // The API repeats the same season-2 titles for every "season-*" slug, so a title
            // is only kept the first time it appears across the merged seasons.
            val metaTitle = data.meta[epNum.toString()]?.title
                ?.trim()
                ?.takeIf { it.isNotEmpty() && usedTitles.add(it) }
            SEpisode.create().apply {
                url = "/watch/$slug/$epNum"
                name = buildString {
                    append("Episodio $epNum")
                    metaTitle?.let { append(" - $it") }
                }
                episode_number = epNum.toFloat()
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "fetchEpisodes: failed for $slug", e)
        throw e
    }

    // ============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = client.get("$baseUrl${episode.url}")
        .use { parseVideosFromWatchPage(it) }
        .toHosterList()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = sortVideosByPreference(hoster.videoList.orEmpty())

    override fun List<Video>.sortVideos(): List<Video> = sortVideosByPreference(this)

    /** Puts the preferred audio first and, within it, the preferred quality - the first entry is the one the app picks. */
    private fun sortVideosByPreference(videos: List<Video>): List<Video> {
        val audio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT).orEmpty()
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return videos.sortedWith(
            // Equality rather than contains(): everything before " - " is the whole audio tag and
            // "SUB" must not also rank "SUB-EN"; the empty value of "Cualquiera" then matches
            // nothing at all, so the payload order is kept.
            compareByDescending<Video> {
                it.videoTitle.substringBefore(" - ").equals(audio, ignoreCase = true)
            }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) },
        ).mapIndexed { index, video -> video.copy(preferred = index == 0) }
    }

    private fun parseVideosFromWatchPage(response: Response): List<Video> {
        val doc = response.asJsoup()

        val data = try {
            doc.extractNextJs<RankedServersData> { element ->
                element is JsonObject && "rankedServers" in element
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVideos: extractNextJs exception", e)
            null
        }
        if (data == null) {
            return parseVideosFromHtml(doc)
        }

        return data.rankedServers.mapNotNull { server ->
            val playSrc = server.play?.src ?: return@mapNotNull null
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            Video(
                videoUrl = videoUrl,
                videoTitle = "${server.lang} - ${server.quality ?: "default"}",
            )
        }
    }

    private fun parseVideosFromHtml(doc: org.jsoup.nodes.Document): List<Video> {
        // The payload is embedded in a Next.js flight string, where quotes and unicode are escaped.
        // Scan from "rankedServers" onwards and unescape it before matching.
        val html = doc.html()
        val payload = html.substring(html.indexOf("rankedServers").coerceAtLeast(0))
            .replace("\\\"", "\"")
            .replace(REGEX_UNICODE_ESCAPE) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }

        return REGEX_SERVER_PATTERN.findAll(payload).mapNotNull { match ->
            val playSrc = match.groupValues[3]
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            // Same label the DTO parser builds, so both paths sort identically: `quality` is
            // optional here because the +18 host omits the key and falls back to "default".
            val quality = REGEX_QUALITY.find(match.groupValues[2])
                ?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            Video(
                videoUrl = videoUrl,
                videoTitle = "${match.groupValues[1]} - ${quality ?: "default"}",
            )
        }.toList()
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Audio preferido",
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Calidad preferida",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )
    }

    // ===================== Legacy request / parse API =====================
    // Browsing, details and episodes are fetched through the suspend functions above,
    // so these abstract members are only implemented to satisfy the compiler.

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    companion object {
        private const val TAG = "Tokianime"
        private const val ADULT = "0"
        private const val PAGE_SIZE = 36
        private const val MAX_RELATED = 15

        /** Image proxy that serves the catalog cover under the "/c/" mode. */
        private const val IMAGE_HOST = "img.tokianime.tv"

        /** Entries of "/ultimos" pushed per page - the page itself holds about 120. */
        private const val LATEST_PAGE_SIZE = 36

        /** Catalog page size used when resolving a single slug in [getAnimeDetails]. */
        private const val DETAIL_LOOKUP_PAGE_SIZE = "8"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"
        private val PREF_AUDIO_ENTRIES = listOf(
            "Subtitulado",
            "Español Latino",
            "Castellano",
            "Doblado",
            "Audio japonés",
            "Cualquiera",
        )
        private val PREF_AUDIO_VALUES = listOf("SUB", "LAT", "CAST", "DUB", "RAW", "")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "Cualquiera")
        private val PREF_QUALITY_VALUES = listOf("1080p", "720p", "480p", "")

        /** Leading badge spans, each followed by whitespace so a title merely starting with them ("SUBaru…") is left alone. */
        private val REGEX_BADGE_PREFIX = Regex(
            """^\s*(?:(?:\+?18|18\+|NSFW|NC17|LAT|CAST|DUB|SUB|RAW|VOSE)\s+)+""",
        )

        private val REGEX_GENRE_STRIP = Regex(
            "\\s*(?:Acci.n|Aventura|Comedia|Drama|Fantas.a|Romance|Sci-Fi|" +
                "Sobrenatural|Misterio|Ecchi|Terror|Suspenso|Crimen|M.sica|" +
                "Shounen|Seinen|Shoujo|Slice of Life|" +
                "Hentai|Adulto|Escolares|Ahegao|Anal|Harem|MILFs?|Yuri|Incesto|" +
                "Orgias|Bondage|BDSM|Hardcore|Futanari|Tetonas|Sin Censura|Uncensored).*$",
            RegexOption.IGNORE_CASE,
        )

        private val REGEX_YEAR_EPS = Regex("\\s*\\d{4}\\s*[•·]?\\s*\\d+\\s*eps?$")
        private val REGEX_YEAR = Regex("\\s*\\d{4}\\s*$")

        /**
         * [^{}]* cannot cross an object, so group 2 is exactly this server's own text up to
         * "play" - [REGEX_QUALITY] reads `quality` from it, a key the +18 host never sends.
         */
        private val REGEX_SERVER_PATTERN = Regex(
            """"lang":\s*"([^"]+)"([^{}]*?)"play":\s*\{[^{}]*?"src":\s*"([^"]+)"""",
        )

        private val REGEX_QUALITY = Regex(""""quality":\s*(?:"([^"]+)"|null)""")

        private val REGEX_EPISODE_SUFFIX = Regex(""",\s*(?:episodio|cap[ií]tulo)\s+\d+$""")

        private val REGEX_UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
    }
}
