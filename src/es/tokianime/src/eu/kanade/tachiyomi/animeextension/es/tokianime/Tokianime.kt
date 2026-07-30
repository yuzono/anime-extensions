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
            .addQueryParameter("pageSize", "36")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<CatalogResponse>()
        val animes = data.items.map { it.toSAnime() }
        animes.take(2).forEach { Log.d(TAG, "popular: ${it.title} thumb=${it.thumbnail_url}") }
        return AnimesPage(animes, data.items.size == 36)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", "36")
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
            .addQueryParameter("pageSize", "36")
            .addQueryParameter("page", (page - 1).toString())

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        filters.filterIsInstance<StatusFilter>().firstOrNull()?.let {
            if (it.selected != "ALL") url.addQueryParameter("status", it.selected)
        }
        filters.filterIsInstance<FormatFilter>().firstOrNull()?.let {
            if (it.selected != "ALL") url.addQueryParameter("format", it.selected)
        }
        filters.filterIsInstance<AudioFilter>().firstOrNull()?.let {
            if (it.selected != "ALL") url.addQueryParameter("audio", it.selected)
        }
        filters.filterIsInstance<SortFilter>().firstOrNull()?.let {
            url.addQueryParameter("sort", it.selected)
        }
        filters.filterIsInstance<GenreFilter>().first().let { group ->
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

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.trimStart('/').removePrefix("anime/")
        Log.d(TAG, "animeDetailsRequest: slug=$slug")
        return GET("$baseUrl/anime/$slug", headers)
    }

    override fun relatedAnimeListRequest(anime: SAnime) = animeDetailsRequest(anime)

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

        Log.d(TAG, "relatedAnimeListParse: ${seasonSlugs.size} seasons merged, ${results.size} recommendations shown")
        return results.distinctBy { it.url }.take(15)
    }

    private fun cleanRelatedTitle(raw: String): String {
        var title = raw
        // Strip audio prefixes: SUB, LAT, CAST, LATSUB, LATCASTSUB, etc.
        title = title.replace(Regex("^(?:LAT|CAST|SUB)+"), "")
        // Strip trailing metadata: genres, year, episode count
        title = title.replace(Regex("\\s*Acci.n.*$"), "")
        title = title.replace(Regex("\\s*Aventura.*$"), "")
        title = title.replace(Regex("\\s*Comedia.*$"), "")
        title = title.replace(Regex("\\s*Drama.*$"), "")
        title = title.replace(Regex("\\s*Fantas.a.*$"), "")
        title = title.replace(Regex("\\s*Romance.*$"), "")
        title = title.replace(Regex("\\s*Sci-Fi.*$"), "")
        title = title.replace(Regex("\\s*Sobrenatural.*$"), "")
        title = title.replace(Regex("\\s*Misterio.*$"), "")
        title = title.replace(Regex("\\s*Ecchi.*$"), "")
        title = title.replace(Regex("\\s*Terror.*$"), "")
        title = title.replace(Regex("\\s*Suspenso.*$"), "")
        title = title.replace(Regex("\\s*Crimen.*$"), "")
        title = title.replace(Regex("\\s*M.sica.*$"), "")
        title = title.replace(Regex("\\s*Shounen.*$"), "")
        title = title.replace(Regex("\\s*Seinen.*$"), "")
        title = title.replace(Regex("\\s*Shoujo.*$"), "")
        title = title.replace(Regex("\\s*Slice of Life.*$"), "")
        title = title.replace(Regex("\\s*\\d{4}\\s*[•·]?\\s*\\d+\\s*eps?$"), "")
        title = title.replace(Regex("\\s*\\d{4}\\s*$"), "")
        return title.trim()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        Log.d(TAG, "animeDetailsParse: contentType=${response.header("Content-Type")}")
        val doc = response.asJsoup()
        val slug = response.request.url.pathSegments.last()

        // Try RSC extraction first
        val anime = try {
            doc.extractNextJs<CatalogAnime> { element ->
                element is JsonObject && "slug" in element && "title" in element
            }
        } catch (e: Exception) {
            Log.e(TAG, "animeDetailsParse: extractNextJs failed", e)
            null
        }

        if (anime != null) {
            Log.d(TAG, "animeDetailsParse: title=${anime.title}, cover=${anime.coverImage != null}")
            return anime.toSAnime()
        }

        // Fallback: extract from meta tags
        Log.d(TAG, "animeDetailsParse: RSC failed, trying meta tags for slug=$slug")
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
        Log.d(TAG, "getAnimeDetails: slug=$slug, current thumb=${anime.thumbnail_url}")

        val apiUrl = "$baseUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "0")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("q", slug)
            .build()
        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val data = apiResponse.parseAs<CatalogResponse>()
        val found = data.items.firstOrNull { it.slug == slug }
        Log.d(TAG, "getAnimeDetails: found=${found != null}, coverImage=${found?.coverImage}")

        val result = found?.toSAnime() ?: anime
        Log.d(TAG, "getAnimeDetails: returning thumb=${result.thumbnail_url}")
        return result
    }

    // ============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.trimStart('/').removePrefix("anime/")
        Log.d(TAG, "getEpisodeList: slug=$slug")

        // Check for season/OVA list from detail page <ol> section
        val seasonEntries = try {
            val detailResponse = client.newCall(GET("$baseUrl/anime/$slug", headers)).execute()
            val doc = detailResponse.asJsoup()
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

        Log.d(TAG, "getEpisodeList: found ${seasonEntries.size} season entries")

        // If no season list, fetch just the main anime
        if (seasonEntries.isEmpty()) {
            return fetchEpisodesForSlug(slug)
                .sortedByDescending { it.episode_number }
        }

        // Merge all episodes with labels from aria-label
        val allEpisodes = mutableListOf<SEpisode>()
        var episodeOffset = 0f

        for ((relSlug, label) in seasonEntries) {
            Log.d(TAG, "getEpisodeList: fetching $relSlug as '$label'")
            val relEpisodes = fetchEpisodesForSlug(relSlug)
            relEpisodes.forEach { ep ->
                ep.name = "$label - ${ep.name}"
                ep.episode_number = ep.episode_number + episodeOffset
                allEpisodes.add(ep)
            }
            episodeOffset += relEpisodes.size.toFloat()
        }

        Log.d(TAG, "getEpisodeList: total merged episodes=${allEpisodes.size}")
        return allEpisodes.sortedByDescending { it.episode_number }
    }

    private fun fetchEpisodesForSlug(slug: String): List<SEpisode> = try {
        val apiUrl = "$baseUrl/api/anime/$slug/episodes".toHttpUrl().newBuilder().build()
        val response = client.newCall(GET(apiUrl, headers)).execute()
        val data = response.parseAs<EpisodesResponse>()
        Log.d(TAG, "fetchEpisodes: ${data.withVideo.size} episodes for $slug")
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
        Log.d(TAG, "getVideoList: episode.url=${episode.url}")
        val epUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        Log.d(TAG, "getVideoList: fetching $epUrl")
        val response = client.newCall(GET(epUrl, headers)).execute()
        Log.d(TAG, "getVideoList: response code=${response.code}, contentType=${response.header("Content-Type")}")
        return parseVideosFromWatchPage(response)
    }

    private fun parseVideosFromWatchPage(response: Response): List<Video> {
        val doc = response.asJsoup()
        Log.d(TAG, "parseVideos: html length=${doc.html().length}")

        val data = try {
            doc.extractNextJs<RankedServersData> { element ->
                element is JsonObject && "rankedServers" in element
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVideos: extractNextJs exception", e)
            null
        }
        if (data == null) {
            Log.e(TAG, "parseVideos: extractNextJs returned null, trying regex fallback")
            return parseVideosFromHtml(doc)
        }
        Log.d(TAG, "parseVideos: found ${data.rankedServers.size} rankedServers")

        val videos = data.rankedServers.mapNotNull { server ->
            Log.d(TAG, "  server: lang=${server.lang}, quality=${server.quality}, play=${server.play?.src}, kind=${server.play?.kind}")
            val playSrc = server.play?.src ?: return@mapNotNull null
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            Video(
                url = videoUrl,
                quality = "${server.lang} - ${server.quality ?: "default"}",
                videoUrl = videoUrl,
            )
        }
        Log.d(TAG, "parseVideos: returning ${videos.size} videos")
        return videos
    }

    private fun parseVideosFromHtml(doc: org.jsoup.nodes.Document): List<Video> {
        val html = doc.html()
        val videos = mutableListOf<Video>()

        val serverPattern = Regex(""""sourceId":\s*"([^"]+)"[\s\S]*?"lang":\s*"([^"]+)"[\s\S]*?"quality":\s*"([^"]+)"[\s\S]*?"play":\s*\{[^}]*"src":\s*"([^"]+)"[\s\S]*?"kind":\s*"([^"]+)"""")
        serverPattern.findAll(html).forEach { match ->
            val sourceId = match.groupValues[1]
            val lang = match.groupValues[2]
            val quality = match.groupValues[3]
            val playSrc = match.groupValues[4]
            val kind = match.groupValues[5]
            Log.d(TAG, "regex fallback: sid=$sourceId lang=$lang q=$quality kind=$kind")
            val videoUrl = if (playSrc.startsWith("http")) playSrc else "$baseUrl$playSrc"
            videos.add(
                Video(
                    url = videoUrl,
                    quality = "$lang - $quality",
                    videoUrl = videoUrl,
                ),
            )
        }
        Log.d(TAG, "parseVideosFromHtml: returning ${videos.size} videos")
        return videos
    }

    companion object {
        private const val TAG = "Tokianime"
    }
}
