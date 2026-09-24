package eu.kanade.tachiyomi.animeextension.es.tokianime

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Tokianime : AnimeHttpSource() {

    override val name = "Tokianime"

    override val baseUrl = "https://tokianime.tv"

    override val lang = "es"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<CatalogResponse>()
        val animes = data.items.map { it.toSAnime() }
        return AnimesPage(animes, data.items.size == PAGE_SIZE)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("sort", "trending")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .addQueryParameter("page", (page - 1).toString())

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

        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        StatusFilter(),
        FormatFilter(),
        AudioFilter(),
        SortFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        val currentSlug = response.request.url.pathSegments.last()
        val results = mutableListOf<SAnime>()

        // 1. Season/OVA links from the <ol> list (aria-label="Ver episodios de X")
        //    These are ALREADY merged into episodes, so skip them from related
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

            var title = el.text()
            if (title.isEmpty()) {
                val ariaLabel = el.attr("aria-label")
                title = ariaLabel.removePrefix("Ver episodios de ").removePrefix("Ver anime de ")
            }
            title = cleanRelatedTitle(title).ifEmpty { slug }

            results.add(
                SAnime.create().apply {
                    url = "/anime/$slug"
                    this.title = title
                    thumbnail_url = imgEl?.attr("src")?.takeIf { it.startsWith("http") }
                        ?: imgEl?.attr("data-src")?.takeIf { it.startsWith("http") }
                        ?: ""
                    initialized = false
                },
            )
        }

        return results.distinctBy { it.url }.take(15)
    }

    private fun cleanRelatedTitle(raw: String): String {
        var title = REGEX_AUDIO_PREFIX.replace(raw, "")
        title = REGEX_GENRE_STRIP.replace(title, "")
        title = REGEX_YEAR_EPS.replace(title, "")
        title = REGEX_YEAR.replace(title, "")
        return title.trim()
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

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.trimStart('/').removePrefix("anime/")

        val apiUrl = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("q", slug)
            .build()
        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val data = apiResponse.use { it.parseAs<CatalogResponse>() }
        val found = data.items.firstOrNull { it.slug == slug }

        return found?.toSAnime() ?: anime
    }

    // ============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.trimStart('/').removePrefix("anime/")

        val seasonEntries = try {
            val detailResponse = client.newCall(GET("$baseUrl/anime/$slug", headers)).execute()
            val doc = detailResponse.use { it.asJsoup() }
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
            Log.e(TAG, "getEpisodeList: failed to fetch detail page", e)
            emptyList()
        }

        if (seasonEntries.isEmpty()) {
            return fetchEpisodesForSlug(slug)
                .sortedByDescending { it.episode_number }
        }

        // Merge all episodes with labels from aria-label
        val allEpisodes = mutableListOf<SEpisode>()
        var episodeOffset = 0f

        for ((relSlug, label) in seasonEntries) {
            val relEpisodes = fetchEpisodesForSlug(relSlug)
            relEpisodes.forEach { ep ->
                ep.name = "$label - ${ep.name}"
                ep.episode_number = ep.episode_number + episodeOffset
                allEpisodes.add(ep)
            }
            episodeOffset += relEpisodes.size.toFloat()
        }

        return allEpisodes.sortedByDescending { it.episode_number }
    }

    private fun fetchEpisodesForSlug(slug: String): List<SEpisode> = try {
        val response = client.newCall(GET("$baseUrl/api/anime/$slug/episodes", headers)).execute()
        val data = response.use { it.parseAs<EpisodesResponse>() }
        data.withVideo.map { epNum ->
            val meta = data.meta[epNum.toString()]
            SEpisode.create().apply {
                url = "/watch/$slug/$epNum"
                name = buildString {
                    append("Episodio $epNum")
                    meta?.title?.let { append(" - $it") }
                }
                episode_number = epNum.toFloat()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchEpisodes: failed for $slug", e)
        emptyList()
    }

    // ============================== Video ===============================

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val response = client.newCall(GET(epUrl, headers)).execute()
        return response.use { parseVideosFromWatchPage(it) }
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

        val videos = data.rankedServers.mapNotNull { server ->
            val playSrc = server.play?.src ?: return@mapNotNull null
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            Video(
                url = videoUrl,
                quality = "${server.lang} - ${server.quality ?: "default"}",
                videoUrl = videoUrl,
            )
        }
        return videos
    }

    private fun parseVideosFromHtml(doc: org.jsoup.nodes.Document): List<Video> {
        val html = doc.html()
        return REGEX_SERVER_PATTERN.findAll(html).mapNotNull { match ->
            val playSrc = match.groupValues[4]
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            Video(
                url = videoUrl,
                quality = "${match.groupValues[2]} - ${match.groupValues[3]}",
                videoUrl = videoUrl,
            )
        }.toList()
    }

    companion object {
        private const val TAG = "Tokianime"
        private const val PAGE_SIZE = 36

        private val REGEX_AUDIO_PREFIX = Regex("^(?:LAT|CAST|SUB)+")

        private val REGEX_GENRE_STRIP = Regex(
            "\\s*(?:Acci.n|Aventura|Comedia|Drama|Fantas.a|Romance|Sci-Fi|" +
                "Sobrenatural|Misterio|Ecchi|Terror|Suspenso|Crimen|M.sica|" +
                "Shounen|Seinen|Shoujo|Slice of Life).*$",
        )

        private val REGEX_YEAR_EPS = Regex("\\s*\\d{4}\\s*[•·]?\\s*\\d+\\s*eps?$")
        private val REGEX_YEAR = Regex("\\s*\\d{4}\\s*$")

        private val REGEX_SERVER_PATTERN = Regex(
            """"sourceId":\s*"([^"]+)"[\s\S]*?"lang":\s*"([^"]+)"[\s\S]*?""" +
                """"quality":\s*"([^"]+)"[\s\S]*?"play":\s*\{[^}]*"src":\s*"([^"]+)"[\s\S]*?""" +
                """"kind":\s*"([^"]+)"""",
        )
    }
}
